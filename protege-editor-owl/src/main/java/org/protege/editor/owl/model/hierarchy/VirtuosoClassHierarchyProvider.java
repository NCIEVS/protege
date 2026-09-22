package org.protege.editor.owl.model.hierarchy;

import org.protege.editor.owl.model.triplestore.LazyLabelCache;
import org.protege.editor.owl.model.triplestore.LazyTripleStore;
import org.protege.editor.owl.model.triplestore.TripleStoreContext;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lazy, read-only asserted class hierarchy provider that answers children/parents/roots by
 * querying the project's named graph in the triple store (Virtuoso) instead of walking an
 * in-RAM ontology. Slice 1 of the "lazy read model": the tree renders without the full ontology
 * resident. OWL API value objects ({@link OWLClass}) are kept at the boundary; only the data
 * source changes.
 *
 * <p>Enabled via {@code -Dnci.lazyHierarchy=true}. Endpoint and project graph come from the shared
 * {@link org.protege.editor.owl.model.triplestore.TripleStoreContext}, which Open-From-Server
 * configures from the opened project; until then the context has no graph, this provider is dormant
 * and the tree is empty. Queries the store directly for now (client-&gt;Virtuoso); a server-mediated
 * read path is a later decision.
 */
public class VirtuosoClassHierarchyProvider extends AbstractOWLObjectHierarchyProvider<OWLClass> {

    private static final Logger logger = LoggerFactory.getLogger(VirtuosoClassHierarchyProvider.class);

    private static final String PREFIXES =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
          + "PREFIX owl: <http://www.w3.org/2002/07/owl#> ";

    private final OWLDataFactory df;
    private final OWLClass thing;
    private final LazyTripleStore store = new LazyTripleStore();

    // Read-only browsing cache. No feed-driven invalidation yet (slice 1); clearCaches() is the hook.
    private final Map<OWLClass, Set<OWLClass>> childrenCache = new ConcurrentHashMap<>();
    private final Map<OWLClass, Set<OWLClass>> parentsCache = new ConcurrentHashMap<>();
    private final Map<OWLClass, Set<OWLClass>> equivalentsCache = new ConcurrentHashMap<>();

    // genus IRI -> defined-class IRIs (strings, to avoid ~74k OWLClass/IRI allocations while building
    // the whole index; converted to OWLClass per-parent on lookup). See definedByGenus().
    private volatile Map<String, Set<String>> definedByGenus;
    // The graph the index was built for; the index survives clearCaches and rebuilds only on a change.
    private volatile String definedByGenusGraph;

    // Leaf status per class, recorded from the per-child EXISTS flag when its parent's children were
    // fetched; answers isLeaf() so the tree draws a node's children's +boxes without loading the
    // grandchildren.
    private final Map<OWLClass, Boolean> leafCache = new ConcurrentHashMap<>();

    // Guards the one-shot background warm-up (genus index + roots) per opened project.
    private final java.util.concurrent.atomic.AtomicBoolean warmUpStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Local edits go to the in-RAM ontology before they reach Virtuoso, so keep the browsing caches
    // (and the tree) in step with named subClassOf add/removes instead of only re-querying the store.
    private final OWLOntologyChangeListener ontologyListener = this::handleOntologyChanges;

    public VirtuosoClassHierarchyProvider(OWLOntologyManager manager) {
        super(manager);
        this.df = manager.getOWLDataFactory();
        this.thing = df.getOWLThing();
        manager.addOntologyChangeListener(ontologyListener);
        // Warm the caches during open-from-server (graph configured), well before the tree is
        // clickable, so the first expansion does not pay the genus-index build.
        TripleStoreContext.getInstance().onConfigure(this::maybeWarmUp);
    }

    @Override
    public void setOntologies(Set<OWLOntology> ontologies) {
        // Nothing to load: the hierarchy is sourced from the triple store, not an in-RAM ontology.
        clearCaches();
        fireHierarchyChanged();
    }

    public void clearCaches() {
        childrenCache.clear();
        parentsCache.clear();
        equivalentsCache.clear();
        leafCache.clear();
        // definedByGenus is NOT cleared here: it is expensive and graph-derived, so it survives a
        // spurious setOntologies and is rebuilt by definedByGenus() only when the graph changes.
        warmUpStarted.set(false);
    }

    // Prime, off the EDT, everything the first tree interaction needs: the genus index (~0.9s once)
    // and owl:Thing's children (the roots and their child sets), so the first click on Thing is
    // instant instead of paying the genus build plus the roots-children fetch. Best-effort, once per
    // opened project. Triggered from getRoots (the graph is configured by then), not setOntologies
    // (which can fire before Open-From-Server sets the graph).
    private void maybeWarmUp() {
        if (store.isConfigured() && warmUpStarted.compareAndSet(false, true)) {
            Thread t = new Thread(this::warmUp, "virtuoso-hierarchy-warmup");
            t.setDaemon(true);
            t.start();
        }
    }

    private void warmUp() {
        try {
            if (!store.isConfigured()) {
                return;
            }
            long t0 = System.currentTimeMillis();
            definedByGenus();
            long t1 = System.currentTimeMillis();
            getUnfilteredChildren(thing);
            logger.info("[perf] warmUp done: genusIndex {}ms, roots {}ms",
                    (t1 - t0), (System.currentTimeMillis() - t1));
        }
        catch (Exception e) {
            // Warm-up is a pure optimisation; the first interaction will simply do the work on demand.
        }
    }

    @Override
    public Set<OWLClass> getRoots() {
        // The tree asks for roots once the project graph is open, so kick the one-shot background
        // warm-up here rather than at setOntologies (which can fire before the graph is configured).
        maybeWarmUp();
        return Collections.singleton(thing);
    }

    @Override
    protected Set<OWLClass> getUnfilteredChildren(OWLClass object) {
        if (!store.isConfigured()) {
            return Collections.emptySet();
        }
        long t0 = System.currentTimeMillis();
        boolean hit = childrenCache.containsKey(object);
        Set<OWLClass> children = childrenCache.get(object);
        if (children == null) {
            children = object.equals(thing) ? fetchRoots() : fetchChildren(object);
            childrenCache.put(object, children);
        }
        prefetchLabels(children);
        long dt = System.currentTimeMillis() - t0;
        if (dt > 200) {
            logger.info("[perf] getChildren({}) {}ms: {} children, cacheHit={}",
                    object.isOWLThing() ? "owl:Thing" : object.getIRI().getShortForm(), dt, children.size(), hit);
        }
        return children;
    }

    // Whether a class is a leaf, from the flag cached when its parent's children were fetched (via the
    // per-child EXISTS in the children query). Empty => the tree loads children to decide (fallback for
    // a node reached without going through its parent). This lets the tree draw a node's children's
    // +boxes without loading the grandchildren.
    @Override
    public java.util.Optional<Boolean> isLeaf(OWLClass object) {
        Boolean leaf = leafCache.get(object);
        return leaf == null ? java.util.Optional.empty() : java.util.Optional.of(leaf);
    }

    // Batch-prime the display labels of a set of classes in one query, so the tree's sibling sort
    // (a render per child) and the per-row paint are cache hits instead of a SPARQL round trip each.
    private void prefetchLabels(Set<OWLClass> classes) {
        if (classes.isEmpty()) {
            return;
        }
        List<IRI> iris = new ArrayList<>(classes.size());
        for (OWLClass c : classes) {
            iris.add(c.getIRI());
        }
        LazyLabelCache.getInstance().prefetch(iris);
    }

    // owl:Thing's children = the roots (named classes with no named superclass and no named genus),
    // each tagged by an EXISTS with whether it has an asserted subclass, so their +boxes need no
    // grandchild load.
    private Set<OWLClass> fetchRoots() {
        Set<OWLClass> roots = new HashSet<>();
        for (String[] row : store.selectPairsCsv(rootsLeafQuery())) {
            recordChild(roots, row);
        }
        return roots;
    }

    private String rootsLeafQuery() {
        String g = store.graph();
        return PREFIXES
              + "SELECT ?c (EXISTS { GRAPH <" + g + "> { ?gc rdfs:subClassOf ?c . "
              + "    FILTER(?gc != ?c && !STRSTARTS(STR(?gc), \"urn:skolem:\")) } } AS ?h) "
              + "WHERE { GRAPH <" + g + "> { "
              + "  ?c rdf:type owl:Class . "
              + "  FILTER(isIRI(?c) && ?c != owl:Thing && ?c != owl:Nothing && !STRSTARTS(STR(?c), \"urn:skolem:\")) "
              + "  FILTER NOT EXISTS { ?c rdfs:subClassOf ?sup . FILTER(isIRI(?sup) && ?sup != owl:Thing) } "
              + "  FILTER NOT EXISTS { ?c owl:equivalentClass ?eq . ?eq owl:intersectionOf ?l . "
              + "                      ?l rdf:rest*/rdf:first ?gg . FILTER(isIRI(?gg)) } "
              + "} }";
    }

    // A class's direct children in one query, N rows: asserted named subclasses each carrying an
    // EXISTS leaf flag, plus the defined classes whose genus is it (from the genus index). No
    // grandchildren are fetched -- the flags drive the children's +boxes via isLeaf().
    private Set<OWLClass> fetchChildren(OWLClass parent) {
        Set<OWLClass> children = new HashSet<>();
        for (String[] row : store.selectPairsCsv(subclassChildrenQuery(parent.getIRI().toString()))) {
            recordChild(children, row);
        }
        for (OWLClass defChild : definedChildren(parent)) {
            children.add(defChild);
            // A defined class is a leaf unless it is itself the genus of another defined class.
            leafCache.putIfAbsent(defChild, !isGenus(defChild));
        }
        return children;
    }

    // Add a (child IRI, hasAssertedSubclass flag) CSV row to 'children' and record its leaf status: a
    // class is a leaf iff it has no asserted subclass AND is not a genus of a defined class.
    private void recordChild(Set<OWLClass> children, String[] row) {
        if (row[0] == null || row[0].isEmpty()) {
            return;
        }
        OWLClass c = df.getOWLClass(IRI.create(row[0]));
        children.add(c);
        boolean hasAssertedKids = row.length > 1 && ("1".equals(row[1]) || "true".equalsIgnoreCase(row[1]));
        leafCache.put(c, !hasAssertedKids && !isGenus(c));
    }

    private boolean isGenus(OWLClass c) {
        return definedByGenus().containsKey(c.getIRI().toString());
    }

    // Defined classes whose genus (a named conjunct of their equivalentClass intersection) is parent,
    // from the once-built genus index. This replaces the reverse list path (?l rdf:rest*/rdf:first
    // <parent>, parent bound as OBJECT over many VALUES anchors), which made Virtuoso's cost estimator
    // reject the query on the full graph ("estimated execution time exceeds the limit").
    private Set<OWLClass> definedChildren(OWLClass parent) {
        Set<String> iris = definedByGenus().get(parent.getIRI().toString());
        if (iris == null || iris.isEmpty()) {
            return Collections.emptySet();
        }
        Set<OWLClass> out = new HashSet<>(iris.size() * 2);
        for (String iri : iris) {
            out.add(df.getOWLClass(IRI.create(iri)));
        }
        return out;
    }

    // A class's asserted named subclasses, each with a per-child EXISTS flag for whether IT has an
    // asserted subclass (its leaf status, combined with the genus index in recordChild). No isIRI
    // filter: redundant with the skolem STRSTARTS exclusion, and it skews Virtuoso's cost estimate.
    private String subclassChildrenQuery(String parentIri) {
        String g = store.graph();
        return PREFIXES
              + "SELECT ?c (EXISTS { GRAPH <" + g + "> { ?gc rdfs:subClassOf ?c . "
              + "    FILTER(?gc != ?c && !STRSTARTS(STR(?gc), \"urn:skolem:\")) } } AS ?h) "
              + "WHERE { GRAPH <" + g + "> { "
              + "  ?c rdfs:subClassOf <" + parentIri + "> "
              + "  FILTER(?c != <" + parentIri + "> && !STRSTARTS(STR(?c), \"urn:skolem:\")) "
              + "} }";
    }

    // genus -> defined classes that name it as a conjunct of their equivalentClass intersection. Built
    // once (forward direction, ~0.5s over the full graph) and cached, so per-node children lookups
    // never issue the reverse list path Virtuoso cannot plan. Reset by clearCaches() on refresh.
    private Map<String, Set<String>> definedByGenus() {
        String g = store.graph();
        Map<String, Set<String>> map = definedByGenus;
        if (map == null || !java.util.Objects.equals(g, definedByGenusGraph)) {
            synchronized (this) {
                if (definedByGenus == null || !java.util.Objects.equals(g, definedByGenusGraph)) {
                    definedByGenus = buildDefinedByGenus();
                    definedByGenusGraph = g;
                }
                map = definedByGenus;
            }
        }
        return map;
    }

    private Map<String, Set<String>> buildDefinedByGenus() {
        Map<String, Set<String>> map = new HashMap<>();
        if (!store.isConfigured()) {
            return map;
        }
        long t0 = System.currentTimeMillis();
        String query = PREFIXES
              + "SELECT ?def ?genus WHERE { GRAPH <" + store.graph() + "> { "
              + "  ?def owl:equivalentClass ?e . ?e owl:intersectionOf ?l . ?l rdf:rest*/rdf:first ?genus . "
              + "  FILTER(?def != ?genus "
              + "    && !STRSTARTS(STR(?def), \"urn:skolem:\") && !STRSTARTS(STR(?genus), \"urn:skolem:\")) "
              + "} }";
        // CSV fast path + string keys: ~37k IRI pairs; both rdf4j per-row parsing and eager OWLClass/IRI
        // allocation dominated, so keep IRIs as strings here and convert per-parent in definedChildren.
        for (String[] row : store.selectPairsCsv(query)) {
            if (row[0] == null || row[1] == null) {
                continue;
            }
            map.computeIfAbsent(row[1], k -> new HashSet<>()).add(row[0]);
        }
        logger.info("[perf] buildDefinedByGenus {}ms: {} genus keys", (System.currentTimeMillis() - t0), map.size());
        return map;
    }

    @Override
    public Set<OWLClass> getParents(OWLClass object) {
        if (!store.isConfigured() || object.equals(thing)) {
            return Collections.emptySet();
        }
        return parentsCache.computeIfAbsent(object, this::queryParents);
    }

    private Set<OWLClass> queryParents(OWLClass object) {
        final String c = object.getIRI().toString();
        final String query = PREFIXES
              + "SELECT DISTINCT ?p WHERE { GRAPH <" + store.graph() + "> { "
              + "  { <" + c + "> rdfs:subClassOf ?p . FILTER(isIRI(?p) && ?p != owl:Thing && !STRSTARTS(STR(?p), \"urn:skolem:\")) } "
              + "  UNION "
              + "  { <" + c + "> owl:equivalentClass ?eq . ?eq owl:intersectionOf ?l . "
              + "    ?l rdf:rest*/rdf:first ?p . FILTER(isIRI(?p) && !STRSTARTS(STR(?p), \"urn:skolem:\")) } "
              + "} }";
        Set<OWLClass> parents = runClassQuery(query, "p");
        // No named parent -> it hangs directly under the root (an orphan), same as the asserted provider.
        if (parents.isEmpty()) {
            return Collections.singleton(thing);
        }
        return parents;
    }

    @Override
    public Set<OWLClass> getEquivalents(OWLClass object) {
        if (!store.isConfigured()) {
            return Collections.emptySet();
        }
        // Cached: the tree cell renderer asks for equivalents on every cell paint, so an uncached
        // query here fired a SPARQL per row per repaint (a query storm while scrolling).
        return equivalentsCache.computeIfAbsent(object, this::queryEquivalents);
    }

    private Set<OWLClass> queryEquivalents(OWLClass object) {
        final String c = object.getIRI().toString();
        final String query = PREFIXES
              + "SELECT DISTINCT ?e WHERE { GRAPH <" + store.graph() + "> { "
              + "  { <" + c + "> owl:equivalentClass ?e } UNION { ?e owl:equivalentClass <" + c + "> } "
              + "  FILTER(isIRI(?e) && ?e != <" + c + "> && !STRSTARTS(STR(?e), \"urn:skolem:\")) "
              + "} }";
        return runClassQuery(query, "e");
    }

    @Override
    public boolean containsReference(OWLClass object) {
        if (!store.isConfigured()) {
            return false;
        }
        final String c = object.getIRI().toString();
        final String query = PREFIXES
              + "ASK { GRAPH <" + store.graph() + "> { { <" + c + "> ?p ?o } UNION { ?s ?q <" + c + "> } } }";
        return store.ask(query);
    }

    private Set<OWLClass> runClassQuery(String query, String var) {
        Set<OWLClass> result = new HashSet<>();
        // CSV fast path (single projected column): rdf4j added ~0.5s per query even for tiny results.
        for (String value : store.selectValuesCsv(query)) {
            result.add(df.getOWLClass(IRI.create(value)));
        }
        return result;
    }

    @Override
    public void dispose() {
        super.dispose();
        getManager().removeOntologyChangeListener(ontologyListener);
        clearCaches();
        store.shutDown();
    }

    // Reflect named subClassOf edits in the caches so a newly created/moved class appears (or a
    // removed one disappears) without an app restart. Fires nodeChanged only for parents whose cached
    // child set actually changed, so lazy schema/class materialisation -- which re-adds edges already
    // in the cache -- does not cause an event storm. Defined-class (equivalentClass genus) edits are
    // handled alongside, mirroring the parents SPARQL which UNIONs subClassOf and the named genus.
    private void handleOntologyChanges(List<? extends OWLOntologyChange> changes) {
        Set<OWLClass> changedParents = new HashSet<>();
        for (OWLOntologyChange change : changes) {
            if (!change.isAxiomChange()) {
                continue;
            }
            OWLAxiom axiom = change.getAxiom();
            boolean add = change instanceof AddAxiom;
            if (axiom instanceof OWLSubClassOfAxiom) {
                handleSubClassOf((OWLSubClassOfAxiom) axiom, add, changedParents);
            } else if (axiom instanceof OWLEquivalentClassesAxiom) {
                handleEquivalentClasses((OWLEquivalentClassesAxiom) axiom, add, changedParents);
            }
        }
        for (OWLClass parent : changedParents) {
            fireNodeChanged(parent);
        }
    }

    private void handleSubClassOf(OWLSubClassOfAxiom sc, boolean add, Set<OWLClass> changedParents) {
        if (sc.getSubClass().isAnonymous() || sc.getSuperClass().isAnonymous()) {
            return;
        }
        OWLClass sub = sc.getSubClass().asOWLClass();
        OWLClass sup = sc.getSuperClass().asOWLClass();
        if (updateChildEdge(sub, sup, add)) {
            changedParents.add(sup);
        }
        parentsCache.remove(sub); // sub's parent set may have changed; re-query on demand
    }

    // A defined class is EquivalentClasses(named, ObjectIntersectionOf(genus..., restrictions...)); the
    // named conjuncts of the intersection are its genus parents (exactly what the parents SPARQL reads),
    // so treat each (definedClass, genus) pair like a subClassOf edge. A plain named-named equivalence
    // has no intersection, so it only invalidates the equivalents cache.
    private void handleEquivalentClasses(OWLEquivalentClassesAxiom eq, boolean add,
                                         Set<OWLClass> changedParents) {
        Set<OWLClass> definedClasses = new HashSet<>();
        Set<OWLClass> genusParents = new HashSet<>();
        for (OWLClassExpression operand : eq.getClassExpressions()) {
            if (!operand.isAnonymous()) {
                definedClasses.add(operand.asOWLClass());
            } else if (operand instanceof OWLObjectIntersectionOf) {
                for (OWLClassExpression conjunct : ((OWLObjectIntersectionOf) operand).getOperands()) {
                    if (!conjunct.isAnonymous()) {
                        genusParents.add(conjunct.asOWLClass());
                    }
                }
            }
        }
        for (OWLClass sub : definedClasses) {
            for (OWLClass sup : genusParents) {
                if (updateChildEdge(sub, sup, add)) {
                    changedParents.add(sup);
                }
            }
            parentsCache.remove(sub);   // parent set may have changed; re-query on demand
            equivalentsCache.remove(sub); // its equivalent set changed too
        }
    }

    // Add/remove a child edge in the cached child set of sup; returns whether the set actually changed
    // (false when sup is not cached, or the edge was already present/absent).
    private boolean updateChildEdge(OWLClass sub, OWLClass sup, boolean add) {
        Set<OWLClass> kids = childrenCache.get(sup);
        if (kids == null) {
            // sup's children not fetched yet; still fix its leaf flag so its +box appears on add.
            if (add) {
                leafCache.put(sup, false);
            } else {
                leafCache.remove(sup);
            }
            return false;
        }
        Set<OWLClass> updated = new HashSet<>(kids);
        boolean changed = add ? updated.add(sub) : updated.remove(sub);
        if (changed) {
            childrenCache.put(sup, updated);
            leafCache.put(sup, updated.isEmpty());
        }
        return changed;
    }
}
