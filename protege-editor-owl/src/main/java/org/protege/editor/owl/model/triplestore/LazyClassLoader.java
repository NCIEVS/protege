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
    // Virtuoso exposes blank nodes as nodeID:// IRIs, which (unlike a bare bnode label) can be
    // queried; the BFS follows them and unifies them back to blank nodes for OWL parsing.
    private static final String NODEID_PREFIX = "nodeID://";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL_NS = "http://www.w3.org/2002/07/owl#";

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
        Model prepared = deskolemise(molecule);
        declareAnnotationProperties(prepared);
        byte[] rdfxml = toRdfXml(prepared);
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

    /** BFS the anonymous (blank-node / skolem) closure reachable from the seed IRIs. */
    private Model fetchClosure(java.util.Collection<String> seeds) {
        Model total = new LinkedHashModel();
        expand(new ArrayDeque<>(seeds), new HashSet<>(), total);
        return total;
    }

    private Model fetchMolecule(String classIri) {
        Model total = new LinkedHashModel();
        Deque<String> frontier = new ArrayDeque<>();

        // Reified owl:Axiom nodes annotate the class via owl:annotatedSource (synonym qualifiers etc.).
        Model inbound = store.construct(LazyTripleStore.PREFIXES
                + "CONSTRUCT { ?ax ?p ?o } WHERE { GRAPH <" + store.graph() + "> { "
                + "?ax owl:annotatedSource <" + classIri + "> . ?ax ?p ?o } }");
        total.addAll(inbound);
        for (Value o : inbound.objects()) {
            if (isExpandable(o)) {
                frontier.add(describeKey(o));
            }
        }

        frontier.add(classIri);
        expand(frontier, new HashSet<>(), total);
        return total;
    }

    private void expand(Deque<String> frontier, Set<String> described, Model total) {
        while (!frontier.isEmpty()) {
            String node = frontier.poll();
            if (!described.add(node)) {
                continue;
            }
            Model description = store.describe(node);
            total.addAll(description);
            for (Value o : description.objects()) {
                if (isExpandable(o)) {
                    String key = describeKey(o);
                    if (!described.contains(key)) {
                        frontier.add(key);
                    }
                }
            }
        }
    }

    // The queryable form of an anonymous node: a real bnode is addressed by its nodeID:// IRI;
    // skolem/nodeID IRIs are already queryable as-is.
    private String describeKey(Value value) {
        if (value instanceof BNode) {
            return NODEID_PREFIX + ((BNode) value).getID();
        }
        return value.stringValue();
    }

    // Only follow anonymous structure (real bnodes, Virtuoso nodeID:// IRIs, skolem IRIs); never a
    // named neighbour, so the molecule stays scoped to one entity.
    private boolean isExpandable(Value value) {
        if (value instanceof BNode) {
            return true;
        }
        if (value instanceof org.eclipse.rdf4j.model.IRI) {
            String iri = value.stringValue();
            return iri.startsWith(SKOLEM_PREFIX) || iri.startsWith(NODEID_PREFIX);
        }
        return false;
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

    // Normalise anonymous nodes to blank nodes so OWL's RDF mapping recognises anonymous class
    // expressions/lists: a nodeID:// IRI becomes the bnode with the same label (unifying with the
    // referencing triple's bnode); a skolem IRI becomes a stable fresh bnode; real bnodes are kept.
    private Value mapValue(Value value, Map<String, BNode> bnodes) {
        if (value instanceof org.eclipse.rdf4j.model.IRI) {
            String iri = value.stringValue();
            if (iri.startsWith(SKOLEM_PREFIX)) {
                return bnodes.computeIfAbsent(iri, k -> vf.createBNode());
            }
            if (iri.startsWith(NODEID_PREFIX)) {
                return vf.createBNode(iri.substring(NODEID_PREFIX.length()));
            }
        }
        return value;
    }

    // owlapi parses each molecule in isolation, so a non-builtin predicate (an NCI P-property) must
    // be declared an annotation property here or owlapi cannot fold owl:Axiom reifications onto
    // their base assertion -- which would drop synonym/definition qualifiers and duplicate values.
    private void declareAnnotationProperties(Model model) {
        Set<org.eclipse.rdf4j.model.IRI> predicates = new HashSet<>();
        for (Statement st : model) {
            predicates.add(st.getPredicate());
        }
        org.eclipse.rdf4j.model.IRI rdfType = vf.createIRI(RDF_NS + "type");
        org.eclipse.rdf4j.model.IRI annotationProperty = vf.createIRI(OWL_NS + "AnnotationProperty");
        for (org.eclipse.rdf4j.model.IRI predicate : predicates) {
            String iri = predicate.stringValue();
            if (!iri.startsWith(RDF_NS) && !iri.startsWith(RDFS_NS) && !iri.startsWith(OWL_NS)) {
                model.add(predicate, rdfType, annotationProperty);
            }
        }
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
