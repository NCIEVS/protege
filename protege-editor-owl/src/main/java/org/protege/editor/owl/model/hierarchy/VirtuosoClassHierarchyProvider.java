package org.protege.editor.owl.model.hierarchy;

import org.protege.editor.owl.model.triplestore.LazyLabelCache;
import org.protege.editor.owl.model.triplestore.LazyTripleStore;
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

    // Local edits go to the in-RAM ontology before they reach Virtuoso, so keep the browsing caches
    // (and the tree) in step with named subClassOf add/removes instead of only re-querying the store.
    private final OWLOntologyChangeListener ontologyListener = this::handleOntologyChanges;

    public VirtuosoClassHierarchyProvider(OWLOntologyManager manager) {
        super(manager);
        this.df = manager.getOWLDataFactory();
        this.thing = df.getOWLThing();
        manager.addOntologyChangeListener(ontologyListener);
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
    }

    @Override
    public Set<OWLClass> getRoots() {
        return Collections.singleton(thing);
    }

    @Override
    protected Set<OWLClass> getUnfilteredChildren(OWLClass object) {
        if (!store.isConfigured()) {
            return Collections.emptySet();
        }
        Set<OWLClass> children = childrenCache.get(object);
        if (children == null) {
            if (object.equals(thing)) {
                children = runClassQuery(thingChildrenQuery(), "c");
                childrenCache.put(object, children);
            } else {
                children = fetchChildrenAndGrandchildren(object);
            }
        }
        // The returned children are about to be sorted (a render per child) and painted, so batch-
        // prime their labels in one query. This fires for cache hits too: the tree computes a node's
        // +box by loading and sorting that node's children, so priming the returned set here means a
        // child's grandchildren are primed in one query right before they are sorted, instead of one
        // SPARQL per grandchild. prefetch() skips already-cached IRIs, so repeat calls are cheap.
        prefetchLabels(children);
        return children;
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

    private String thingChildrenQuery() {
        // Children of owl:Thing = named classes with no named superclass and no named genus
        // (the roots/orphans), mirroring AssertedClassHierarchyProvider's terminal elements.
        return PREFIXES
              + "SELECT DISTINCT ?c WHERE { GRAPH <" + store.graph() + "> { "
              + "  ?c rdf:type owl:Class . "
              + "  FILTER(isIRI(?c) && ?c != owl:Thing && ?c != owl:Nothing) "
              + "  FILTER NOT EXISTS { ?c rdfs:subClassOf ?sup . FILTER(isIRI(?sup) && ?sup != owl:Thing) } "
              + "  FILTER NOT EXISTS { ?c owl:equivalentClass ?eq . ?eq owl:intersectionOf ?l . "
              + "                      ?l rdf:rest*/rdf:first ?g . FILTER(isIRI(?g)) } "
              + "} }";
    }

    // Fetch a parent's children AND each child's children, caching every child's child set so the
    // tree's per-child +box lookups (a getChildren call on each child) are cache hits instead of a
    // SPARQL round-trip each -- the "one depth ahead" prefetch. Done as TWO bounded queries rather
    // than one nested two-level query: the grandchildren query binds the children with VALUES so the
    // defined-class list path (rdf:rest*/rdf:first) is anchored, whereas the nested form left it over
    // an unbounded variable and Virtuoso's cost estimator rejected it ("estimated execution time
    // exceeds the limit").
    private Set<OWLClass> fetchChildrenAndGrandchildren(OWLClass parent) {
        Set<OWLClass> children = runClassQuery(childrenQuery(parent.getIRI().toString()), "c");
        childrenCache.put(parent, children);
        if (children.isEmpty()) {
            return children;
        }
        Map<OWLClass, Set<OWLClass>> grandchildren = new HashMap<>();
        for (String[] row : store.selectPairs(grandchildrenQuery(children), "c", "gc")) {
            if (row[0] == null || row[1] == null) {
                continue;
            }
            grandchildren.computeIfAbsent(df.getOWLClass(IRI.create(row[0])), k -> new HashSet<>())
                    .add(df.getOWLClass(IRI.create(row[1])));
        }
        for (OWLClass child : children) {
            childrenCache.putIfAbsent(child, grandchildren.getOrDefault(child, Collections.emptySet()));
        }
        return children;
    }

    // A class's direct children: asserted subclasses or defined classes whose genus is the parent.
    // Anchored on the parent, so the list path is bounded.
    private String childrenQuery(String parentIri) {
        return PREFIXES
              + "SELECT DISTINCT ?c WHERE { GRAPH <" + store.graph() + "> { "
              + "  { ?c rdfs:subClassOf <" + parentIri + "> } "
              + "  UNION "
              + "  { ?c owl:equivalentClass ?e . ?e owl:intersectionOf ?l . ?l rdf:rest*/rdf:first <" + parentIri + "> } "
              + "  FILTER(isIRI(?c) && ?c != <" + parentIri + ">) "
              + "} }";
    }

    // The children of a known set of classes, the set bound by VALUES so each anchor is fixed and the
    // list path stays bounded (unlike a nested grandchildren OPTIONAL over an unbound variable).
    private String grandchildrenQuery(Set<OWLClass> classes) {
        StringBuilder values = new StringBuilder();
        for (OWLClass c : classes) {
            values.append('<').append(c.getIRI()).append("> ");
        }
        return PREFIXES
              + "SELECT DISTINCT ?c ?gc WHERE { GRAPH <" + store.graph() + "> { "
              + "  VALUES ?c { " + values + "} "
              + "  { ?gc rdfs:subClassOf ?c } "
              + "  UNION "
              + "  { ?gc owl:equivalentClass ?e . ?e owl:intersectionOf ?l . ?l rdf:rest*/rdf:first ?c } "
              + "  FILTER(isIRI(?gc) && ?gc != ?c) "
              + "} }";
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
              + "  { <" + c + "> rdfs:subClassOf ?p . FILTER(isIRI(?p) && ?p != owl:Thing) } "
              + "  UNION "
              + "  { <" + c + "> owl:equivalentClass ?eq . ?eq owl:intersectionOf ?l . "
              + "    ?l rdf:rest*/rdf:first ?p . FILTER(isIRI(?p)) } "
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
              + "  FILTER(isIRI(?e) && ?e != <" + c + ">) "
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
        for (String value : store.selectValues(query, var)) {
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
            return false;
        }
        Set<OWLClass> updated = new HashSet<>(kids);
        boolean changed = add ? updated.add(sub) : updated.remove(sub);
        if (changed) {
            childrenCache.put(sup, updated);
        }
        return changed;
    }
}
