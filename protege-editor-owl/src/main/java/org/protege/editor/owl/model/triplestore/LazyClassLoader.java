package org.protege.editor.owl.model.triplestore;

import gov.nih.nci.owlrdf.OwlRdfIO;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.client.SessionRecorder;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lazily materialises a single class's axioms into the in-RAM ontology by fetching its RDF
 * "molecule" from the triple store, so the existing frames/property tabs (which read the active
 * ontology) populate without being rewritten. Reconstruction reverses the write transform:
 *
 * <ol>
 *   <li>fetch the class's forward-reachable triples (following only skolem/blank nodes, so named
 *       neighbours aren't dragged in) plus the reified {@code owl:Axiom} nodes that annotate it;
 *   <li>de-skolemise: rewrite {@code urn:skolem:*} IRIs back to blank nodes, which OWL's RDF
 *       mapping requires for anonymous class expressions (restrictions, defined-class
 *       {@code intersectionOf}, reified synonym annotations);
 *   <li>parse RDF/XML back to OWL via {@code owl-rdf-io} and add the axioms to the active ontology.
 * </ol>
 *
 * <p>The additions are applied with the {@link SessionRecorder} paused so they are never seen as
 * uncommitted edits (the same technique {@code UpdateAction} uses for pulled changes).
 */
public final class LazyClassLoader {

    private static final Logger logger = LoggerFactory.getLogger(LazyClassLoader.class);

    private static final String SKOLEM_PREFIX = "urn:skolem:";

    private static LazyClassLoader instance;

    public static synchronized LazyClassLoader getInstance() {
        if (instance == null) {
            instance = new LazyClassLoader();
        }
        return instance;
    }

    private final LazyTripleStore store = new LazyTripleStore();
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final Set<String> loaded = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private volatile boolean schemaLoaded = false;

    private boolean isActive() {
        return store.isConfigured();
    }

    /** Ensure the class's axioms are present in the active ontology (no-op if already loaded). */
    public void ensureLoaded(OWLClass cls, OWLEditorKit editorKit) {
        if (!isActive() || cls == null || cls.isOWLThing() || cls.isOWLNothing()) {
            return;
        }
        String classIri = cls.getIRI().toString();
        if (loaded.contains(classIri)) {
            return;
        }
        try {
            int count = materialise(fetchMolecule(classIri), editorKit);
            loaded.add(classIri);
            logger.info("Lazily loaded {} axioms for {}", count, classIri);
        } catch (Exception e) {
            logger.error("Failed to lazily load class {}", classIri, e);
        }
    }

    /**
     * Eagerly materialise the ontology schema -- annotation/object/data properties and datatypes
     * with their definitions -- into the active ontology. The schema is small and always resident
     * in the lazy model (only class data is fetched on demand), so config-driven lookups (complex
     * properties, code/pref-name props, role properties, datatype enumerations) resolve against the
     * ontology signature. Runs once.
     */
    public synchronized void ensureSchemaLoaded(OWLEditorKit editorKit) {
        if (!isActive() || schemaLoaded) {
            return;
        }
        try {
            Set<String> seeds = store.selectValues(LazyTripleStore.PREFIXES
                    + "SELECT DISTINCT ?s WHERE { GRAPH <" + store.graph() + "> { ?s a ?t . "
                    + "FILTER(?t IN (owl:AnnotationProperty, owl:ObjectProperty, "
                    + "owl:DatatypeProperty, rdfs:Datatype)) } }", "s");
            int count = materialise(fetchClosure(seeds), editorKit);
            schemaLoaded = true;
            logger.info("Lazily loaded schema: {} axioms from {} entities", count, seeds.size());
        } catch (Exception e) {
            logger.error("Failed to lazily load schema", e);
        }
    }

    /** De-skolemise, parse RDF->OWL and add to the active ontology (recording paused). */
    private int materialise(Model molecule, OWLEditorKit editorKit) throws Exception {
        if (molecule.isEmpty()) {
            return 0;
        }
        byte[] rdfxml = toRdfXml(deskolemise(molecule));
        OWLOntology reconstructed = OwlRdfIO.load(new ByteArrayInputStream(rdfxml));
        Set<OWLAxiom> axioms = new HashSet<>();
        reconstructed.axioms().forEach(axioms::add);

        OWLModelManager modelManager = editorKit.getOWLModelManager();
        OWLOntology target = modelManager.getActiveOntology();
        SessionRecorder recorder = SessionRecorder.getInstance(editorKit);
        recorder.stopRecording();
        try {
            modelManager.getOWLOntologyManager().addAxioms(target, axioms.stream());
        } finally {
            recorder.startRecording();
        }
        return axioms.size();
    }

    /** BFS the skolem/blank-node closure reachable from the seed IRIs. */
    private Model fetchClosure(java.util.Collection<String> seeds) {
        Model total = new LinkedHashModel();
        Deque<String> frontier = new ArrayDeque<>(seeds);
        Set<String> described = new HashSet<>();
        while (!frontier.isEmpty()) {
            String node = frontier.poll();
            if (!described.add(node)) {
                continue;
            }
            Model description = store.describe(node);
            total.addAll(description);
            for (Value o : description.objects()) {
                if (isExpandable(o) && !described.contains(o.stringValue())) {
                    frontier.add(o.stringValue());
                }
            }
        }
        return total;
    }

    private Model fetchMolecule(String classIri) {
        Model total = new LinkedHashModel();
        Deque<String> frontier = new ArrayDeque<>();
        Set<String> described = new HashSet<>();

        // Reified owl:Axiom nodes annotate the class via owl:annotatedSource (synonym qualifiers etc.).
        Model inbound = store.construct(LazyTripleStore.PREFIXES
                + "CONSTRUCT { ?ax ?p ?o } WHERE { GRAPH <" + store.graph() + "> { "
                + "?ax owl:annotatedSource <" + classIri + "> . ?ax ?p ?o } }");
        total.addAll(inbound);
        for (Value o : inbound.objects()) {
            if (isExpandable(o)) {
                frontier.add(o.stringValue());
            }
        }

        frontier.add(classIri);
        while (!frontier.isEmpty()) {
            String node = frontier.poll();
            if (!described.add(node)) {
                continue;
            }
            Model description = store.describe(node);
            total.addAll(description);
            for (Value o : description.objects()) {
                if (isExpandable(o) && !described.contains(o.stringValue())) {
                    frontier.add(o.stringValue());
                }
            }
        }
        return total;
    }

    // Only follow anonymous structure (skolemised bnodes / real bnodes); never a named neighbour.
    private boolean isExpandable(Value value) {
        if (value instanceof BNode) {
            return true;
        }
        return value instanceof org.eclipse.rdf4j.model.IRI && value.stringValue().startsWith(SKOLEM_PREFIX);
    }

    private Model deskolemise(Model molecule) {
        Model out = new LinkedHashModel();
        Map<String, BNode> bnodes = new HashMap<>();
        for (Statement st : molecule) {
            Resource subject = (Resource) mapValue(st.getSubject(), bnodes);
            Value object = mapValue(st.getObject(), bnodes);
            out.add(subject, st.getPredicate(), object);
        }
        return out;
    }

    private Value mapValue(Value value, Map<String, BNode> bnodes) {
        if (value instanceof org.eclipse.rdf4j.model.IRI && value.stringValue().startsWith(SKOLEM_PREFIX)) {
            return bnodes.computeIfAbsent(value.stringValue(), k -> vf.createBNode());
        }
        return value;
    }

    private byte[] toRdfXml(Model model) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Rio.write(model, out, RDFFormat.RDFXML);
        return out.toByteArray();
    }

    /** Drop the cached record for a class so its molecule is refetched on next selection. */
    public void invalidate(OWLClass cls) {
        if (cls != null) {
            loaded.remove(cls.getIRI().toString());
        }
    }
}
