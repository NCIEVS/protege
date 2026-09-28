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
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
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
    private static final int SCHEMA_SEED_BATCH = 200;
    // Safety cap on the reverse skolem walk (a defined-class intersection list is at most a few
    // dozen conjuncts deep); prevents a runaway if the graph is malformed.
    private static final int REVERSE_WALK_LIMIT = 50;
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
    private OWLOntology schemaOntology;

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
            long t0 = System.currentTimeMillis();
            org.eclipse.rdf4j.model.Model molecule = fetchMolecule(classIri);
            long t1 = System.currentTimeMillis();
            int count = materialise(molecule, editorKit);
            loaded.add(classIri);
            logger.info("Lazily loaded {} axioms for {} ({} triples) in {}ms = fetch {}ms + parse {}ms",
                    count, classIri, molecule.size(), (System.currentTimeMillis() - t0),
                    (t1 - t0), (System.currentTimeMillis() - t1));
        } catch (Exception e) {
            logger.error("Failed to lazily load class {}", classIri, e);
        }
    }

    /**
     * Materialise into the active ontology every named class that references {@code entity} as a
     * subclass child, an object-property role filler ({@code R some/only entity}), or an
     * object-valued association target, so an in-RAM {@code getReferencingAxioms(entity)} sees the
     * full reference closure. In the lazy model the active ontology holds only browsed classes, so a
     * merge/retire reference retargeting would otherwise silently miss references on unbrowsed
     * classes. No-op unless the lazy model is active.
     *
     * @param associationProps IRIs of the object-valued association (annotation) properties; the
     *   predicate set must be bound -- a variable-predicate reverse lookup is orders of magnitude
     *   slower on the full graph.
     */
    public void ensureReferencingClassesLoaded(OWLClass entity, OWLEditorKit editorKit,
                                               java.util.Collection<String> associationProps) {
        if (!isActive() || entity == null || entity.isOWLThing() || entity.isOWLNothing()) {
            return;
        }
        String e = entity.getIRI().toString();
        try {
            Set<String> owners = new HashSet<>();
            owners.addAll(subclassChildrenOf(e));
            owners.addAll(roleFillerOwnersOf(e));
            owners.addAll(associationSourcesOf(e, associationProps));
            owners.remove(e);
            OWLDataFactory df = editorKit.getOWLModelManager().getOWLDataFactory();
            for (String iri : owners) {
                ensureLoaded(df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(iri)), editorKit);
            }
            logger.info("Primed {} referencing classes of {} for reference retargeting", owners.size(), e);
        } catch (Exception ex) {
            logger.error("Failed to prime referencing classes of {}", e, ex);
        }
    }

    // Named classes that assert {@code entity} as a direct superclass.
    private Set<String> subclassChildrenOf(String entity) {
        return store.selectValuesCsv(LazyTripleStore.PREFIXES
                + "SELECT ?c WHERE { GRAPH <" + store.graph() + "> { ?c rdfs:subClassOf <" + entity
                + "> FILTER(isIRI(?c) && !STRSTARTS(STR(?c), \"" + SKOLEM_PREFIX + "\")) } }");
    }

    // Named classes carrying an object-valued association whose value is {@code entity}. The
    // predicate set is bound via VALUES: a variable predicate on a bound object is ~1000x slower.
    private Set<String> associationSourcesOf(String entity, java.util.Collection<String> associationProps) {
        if (associationProps == null || associationProps.isEmpty()) {
            return Collections.emptySet();
        }
        StringBuilder values = new StringBuilder();
        for (String p : associationProps) {
            values.append('<').append(p).append("> ");
        }
        return store.selectValuesCsv("SELECT ?c WHERE { GRAPH <" + store.graph() + "> { VALUES ?p { "
                + values + "} ?c ?p <" + entity + "> "
                + "FILTER(isIRI(?c) && !STRSTARTS(STR(?c), \"" + SKOLEM_PREFIX + "\")) } }");
    }

    // Named classes that use {@code entity} as an object-property role filler ({@code R some/only
    // entity}), found by a bounded reverse skolem walk: seed on the restriction nodes, then follow
    // {@code ?s ?p ?x} up the per-axiom skolem structure (list/intersection nodes) to the owning
    // named class. Anchored VALUES steps are used instead of an inverse property path, which Virtuoso
    // cost-rejects on the full graph. All bnodes of one axiom share a skolem hash, so the walk stays
    // within the axiom and terminates at its named subject.
    private Set<String> roleFillerOwnersOf(String entity) {
        Set<String> owners = new HashSet<>();
        Set<String> frontier = store.selectValuesCsv(LazyTripleStore.PREFIXES
                + "SELECT ?r WHERE { GRAPH <" + store.graph() + "> { "
                + "{ ?r owl:someValuesFrom <" + entity + "> } UNION { ?r owl:allValuesFrom <" + entity
                + "> } } }");
        Set<String> seen = new HashSet<>(frontier);
        int guard = 0;
        while (!frontier.isEmpty() && guard++ < REVERSE_WALK_LIMIT) {
            StringBuilder values = new StringBuilder();
            for (String n : frontier) {
                values.append('<').append(n).append("> ");
            }
            Set<String> parents = store.selectValuesCsv("SELECT DISTINCT ?s WHERE { GRAPH <"
                    + store.graph() + "> { VALUES ?x { " + values + "} ?s ?p ?x } }");
            Set<String> nextSkolem = new HashSet<>();
            for (String p : parents) {
                if (p.startsWith(SKOLEM_PREFIX)) {
                    if (seen.add(p)) {
                        nextSkolem.add(p);
                    }
                } else {
                    owners.add(p);
                }
            }
            frontier = nextSkolem;
        }
        return owners;
    }

    /**
     * Eagerly materialise the ontology schema -- annotation/object/data properties and datatypes
     * with their definitions -- into the active ontology. The schema is small and always resident
     * in the lazy model (only class data is fetched on demand), so config-driven lookups (complex
     * properties, code/pref-name props, role properties, datatype enumerations) resolve against the
     * ontology signature. Runs once.
     */
    public synchronized void ensureSchemaLoaded(OWLEditorKit editorKit) {
        if (!isActive()) {
            return;
        }
        OWLOntology active = editorKit.getOWLModelManager().getActiveOntology();
        if (schemaLoaded && active == schemaOntology) {
            return;
        }
        // A new active ontology (project (re)open, e.g. after a squash, or a project switch) starts
        // empty, so the schema and per-class caches from the previous ontology no longer apply --
        // reload the schema into this ontology.
        schemaLoaded = false;
        loaded.clear();
        try {
            Set<String> seeds = store.selectValues(LazyTripleStore.PREFIXES
                    + "SELECT DISTINCT ?s WHERE { GRAPH <" + store.graph() + "> { ?s a ?t . "
                    + "FILTER(?t IN (owl:AnnotationProperty, owl:ObjectProperty, "
                    + "owl:DatatypeProperty, rdfs:Datatype)) } }", "s");
            int count = materialise(fetchClosure(seeds), editorKit);
            declareStandardAnnotationProperties(editorKit);
            schemaLoaded = true;
            schemaOntology = active;
            logger.info("Lazily loaded schema: {} axioms from {} entities", count, seeds.size());
        } catch (Exception e) {
            logger.error("Failed to lazily load schema", e);
        }
    }

    /**
     * rdfs:label / rdfs:comment are used on entities but not declared in the graph, so the schema
     * seed query misses them. Declare them so they are in the ontology signature (e.g. the search
     * tab's annotation-property list) from the start rather than only after a class that uses them
     * is loaded.
     */
    private void declareStandardAnnotationProperties(OWLEditorKit editorKit) {
        OWLModelManager modelManager = editorKit.getOWLModelManager();
        OWLDataFactory df = modelManager.getOWLDataFactory();
        Set<OWLAxiom> declarations = new HashSet<>();
        declarations.add(df.getOWLDeclarationAxiom(df.getRDFSLabel()));
        declarations.add(df.getOWLDeclarationAxiom(df.getRDFSComment()));
        OWLOntology target = modelManager.getActiveOntology();
        SessionRecorder recorder = SessionRecorder.getInstance(editorKit);
        recorder.stopRecording();
        try {
            modelManager.getOWLOntologyManager().addAxioms(target, declarations.stream());
        } finally {
            recorder.startRecording();
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

    /**
     * An entity's own triples plus the skolem-node closure of its anonymous expressions. Our loaded
     * graph skolemises all anonymous nodes to stable {@code urn:skolem:} IRIs (no real blank nodes),
     * so the structure is walked level-by-level with fast VALUES-anchored queries rather than one
     * unbounded property path -- which Virtuoso cannot evaluate on the full graph (it returns an
     * "ANYTIME timeout"). Only skolem nodes are expanded, so named neighbours (role fillers, parents)
     * are referenced but never dragged in. Blank-node identity is not a concern here because the nodes
     * are stable IRIs; {@code deskolemise} keys on the IRI string, so a multi-query walk re-addresses
     * them consistently.
     */
    private Model closure(String iri) {
        return skolemClosure(Collections.singletonList(iri));
    }

    // BFS the skolem-node structure reachable from a set of seed nodes: fetch the seeds' own triples,
    // then follow only skolem objects level by level (never a hierarchy/reference predicate). Replaces
    // unbounded property-path CONSTRUCTs, which Virtuoso cannot evaluate on the full graph -- it
    // returns an S1TAT "ANYTIME timeout" and, worse, silently truncated results that corrupt lists.
    private Model skolemClosure(java.util.Collection<String> seeds) {
        Model total = new LinkedHashModel();
        if (seeds.isEmpty()) {
            return total;
        }
        total.addAll(directTriples(seeds));
        Set<String> seen = new HashSet<>(seeds);
        Set<String> frontier = newSkolemObjects(total, seen);
        while (!frontier.isEmpty()) {
            Model level = directTriples(frontier);
            total.addAll(level);
            frontier = newSkolemObjects(level, seen);
        }
        return total;
    }

    // The not-yet-seen skolem-IRI objects in a model (recorded in {@code seen}) -- the next BFS frontier.
    private Set<String> newSkolemObjects(Model model, Set<String> seen) {
        Set<String> next = new HashSet<>();
        for (Statement st : model) {
            Value o = st.getObject();
            if (o instanceof Resource && o.stringValue().startsWith(SKOLEM_PREFIX) && seen.add(o.stringValue())) {
                next.add(o.stringValue());
            }
        }
        return next;
    }

    // Materialise the schema without the per-seed structural-path loop (which blocked open ~24s):
    // properties need only their own triples (fast VALUES CONSTRUCTs), and only datatypes carry a
    // structural (oneOf enum) closure, fetched for all of them in one query.
    private Model fetchClosure(java.util.Collection<String> seeds) {
        Model total = new LinkedHashModel();
        java.util.List<String> list = new java.util.ArrayList<>(seeds);
        for (int i = 0; i < list.size(); i += SCHEMA_SEED_BATCH) {
            total.addAll(directTriples(list.subList(i, Math.min(i + SCHEMA_SEED_BATCH, list.size()))));
        }
        total.addAll(datatypeClosure());
        return total;
    }

    // A batch of seeds' own triples in one CONSTRUCT (no property path -- fast).
    private Model directTriples(java.util.Collection<String> seeds) {
        StringBuilder values = new StringBuilder();
        for (String seed : seeds) {
            values.append('<').append(seed).append("> ");
        }
        return store.construct(LazyTripleStore.PREFIXES
                + "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + store.graph() + "> { "
                + "VALUES ?s { " + values + "} ?s ?p ?o } }");
    }

    // Every enumerated datatype's oneOf-list structure, walked as a bounded skolem BFS from the
    // datatype's definition node. A single transitive property-path CONSTRUCT ANYTIME-timed-out on the
    // full graph and returned truncated lists, so the schema's DataOneOf axioms failed to parse.
    private Model datatypeClosure() {
        Set<String> entries = store.selectValues(LazyTripleStore.PREFIXES
                + "SELECT DISTINCT ?entry WHERE { GRAPH <" + store.graph() + "> { "
                + "?dt a rdfs:Datatype . ?dt (rdfs:subClassOf|owl:equivalentClass) ?entry . "
                + "FILTER(isBlank(?entry) || STRSTARTS(STR(?entry), \"" + SKOLEM_PREFIX + "\")) } }",
                "entry");
        return skolemClosure(entries);
    }

    private Model fetchMolecule(String classIri) {
        Model total = new LinkedHashModel();
        total.addAll(closure(classIri));
        // Reified owl:Axiom nodes annotate the class via owl:annotatedSource (synonym/definition
        // qualifiers); they point inbound to the class, so are fetched separately.
        total.addAll(store.construct(LazyTripleStore.PREFIXES
                + "CONSTRUCT { ?ax ?p ?o } WHERE { GRAPH <" + store.graph() + "> { "
                + "?ax owl:annotatedSource <" + classIri + "> . ?ax ?p ?o } }"));
        return total;
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

    // Graphs written by our commit path skolemise blank nodes to urn:skolem: IRIs; turn those back
    // into blank nodes so OWL's RDF mapping recognises the anonymous expressions. (Graphs loaded
    // directly from an OWL file already use blank nodes, which pass through unchanged.)
    private Value mapValue(Value value, Map<String, BNode> bnodes) {
        if (value instanceof org.eclipse.rdf4j.model.IRI && value.stringValue().startsWith(SKOLEM_PREFIX)) {
            return bnodes.computeIfAbsent(value.stringValue(), k -> vf.createBNode());
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
