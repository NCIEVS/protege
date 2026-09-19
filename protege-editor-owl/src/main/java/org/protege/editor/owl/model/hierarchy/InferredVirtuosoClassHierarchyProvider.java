package org.protege.editor.owl.model.hierarchy;

import org.protege.editor.owl.model.triplestore.LazyLabelCache;
import org.protege.editor.owl.model.triplestore.LazyTripleStore;
import org.protege.editor.owl.model.triplestore.TripleStoreContext;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy, read-only <em>inferred</em> class hierarchy provider: answers children/parents/equivalents
 * by querying the project's {@code <graph>/inferred} named graph, which the server-side classifier
 * fills with the inferred direct-subclass and equivalence edges. It mirrors
 * {@link VirtuosoClassHierarchyProvider}, but the inferred graph holds only {@code rdfs:subClassOf}
 * and {@code owl:equivalentClass} triples (no class declarations or defined-class genus), so the
 * queries are simpler. Class labels still come from the asserted graph, resolved by the shared
 * renderer/{@link LazyLabelCache}.
 *
 * <p>Enabled with {@code -Dnci.lazyHierarchy=true}. Dormant until a project is open, and empty until
 * that project has been classified. Reflects the last classification; call {@link #clearCaches()}
 * (and fire a hierarchy change) after a re-classify to refresh.
 */
public class InferredVirtuosoClassHierarchyProvider extends AbstractOWLObjectHierarchyProvider<OWLClass> {

    private static final String PREFIXES =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
          + "PREFIX owl: <http://www.w3.org/2002/07/owl#> ";

    private final OWLDataFactory df;
    private final OWLClass thing;
    // Same Virtuoso endpoint as the asserted store; only the graph clause differs, so the default
    // (context-backed) store is reused and the inferred graph is set per query.
    private final LazyTripleStore store = new LazyTripleStore();

    private final Map<OWLClass, Set<OWLClass>> childrenCache = new ConcurrentHashMap<>();
    private final Map<OWLClass, Set<OWLClass>> parentsCache = new ConcurrentHashMap<>();
    private final Map<OWLClass, Set<OWLClass>> equivalentsCache = new ConcurrentHashMap<>();

    public InferredVirtuosoClassHierarchyProvider(OWLOntologyManager manager) {
        super(manager);
        this.df = manager.getOWLDataFactory();
        this.thing = df.getOWLThing();
    }

    // The inferred graph is the project graph with an /inferred suffix, resolved dynamically because
    // the project graph is only set once Open-From-Server configures the context.
    private String graph() {
        String g = TripleStoreContext.getInstance().graph();
        return (g == null || g.isEmpty()) ? null : g + "/inferred";
    }

    private boolean isConfigured() {
        return store.isConfigured() && graph() != null;
    }

    @Override
    public void setOntologies(Set<OWLOntology> ontologies) {
        clearCaches();
        fireHierarchyChanged();
    }

    public void clearCaches() {
        childrenCache.clear();
        parentsCache.clear();
        equivalentsCache.clear();
    }

    /** Drop cached results and repaint the tree, e.g. after the project was re-classified. */
    public void refresh() {
        clearCaches();
        fireHierarchyChanged();
    }

    @Override
    public Set<OWLClass> getRoots() {
        return Collections.singleton(thing);
    }

    @Override
    protected Set<OWLClass> getUnfilteredChildren(OWLClass object) {
        if (!isConfigured()) {
            return Collections.emptySet();
        }
        Set<OWLClass> children = childrenCache.get(object);
        if (children == null) {
            children = object.equals(thing) ? rootsChildren() : runClassQuery(childrenQuery(object), "c");
            childrenCache.put(object, children);
        }
        prefetchLabels(children);
        return children;
    }

    // Batch-prime labels (from the asserted graph) so the tree's sibling sort and per-row paint are
    // cache hits rather than a SPARQL round trip each.
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

    // Children of owl:Thing in the inferred hierarchy = the roots of the inferred subclass DAG: classes
    // that have inferred children but no inferred parent. Computed as a client-side set difference
    // (superclasses minus those that are themselves subclasses) from two JOIN-only queries: the
    // single-query form (UNION + isIRI + NOT EXISTS anti-join) is estimated at thousands of seconds and
    // rejected by Virtuoso's cost estimator on the full graph. The inferred graph has no skolem nodes,
    // so no isIRI filter is needed (it also skews the estimate into rejection).
    private Set<OWLClass> rootsChildren() {
        Set<OWLClass> superclasses = runClassQuery(PREFIXES
              + "SELECT DISTINCT ?p WHERE { GRAPH <" + graph() + "> { ?c rdfs:subClassOf ?p } }", "p");
        Set<OWLClass> internal = runClassQuery(PREFIXES
              + "SELECT DISTINCT ?p WHERE { GRAPH <" + graph() + "> { "
              + "?c rdfs:subClassOf ?p . ?p rdfs:subClassOf ?sup } }", "p");
        superclasses.removeAll(internal);
        superclasses.remove(thing); // Thing is the tree root itself, never its own child
        return superclasses;
    }

    private String childrenQuery(OWLClass parent) {
        return PREFIXES
              + "SELECT DISTINCT ?c WHERE { GRAPH <" + graph() + "> { "
              + "  ?c rdfs:subClassOf <" + parent.getIRI() + "> "
              + "} }";
    }

    @Override
    public Set<OWLClass> getParents(OWLClass object) {
        if (!isConfigured() || object.equals(thing)) {
            return Collections.emptySet();
        }
        return parentsCache.computeIfAbsent(object, this::queryParents);
    }

    private Set<OWLClass> queryParents(OWLClass object) {
        final String query = PREFIXES
              + "SELECT DISTINCT ?p WHERE { GRAPH <" + graph() + "> { "
              + "  <" + object.getIRI() + "> rdfs:subClassOf ?p "
              + "} }";
        Set<OWLClass> parents = runClassQuery(query, "p");
        // No inferred parent -> it hangs directly under the root of the inferred tree.
        if (parents.isEmpty()) {
            return Collections.singleton(thing);
        }
        return parents;
    }

    @Override
    public Set<OWLClass> getEquivalents(OWLClass object) {
        if (!isConfigured() || object.equals(thing)) {
            return Collections.emptySet();
        }
        return equivalentsCache.computeIfAbsent(object, this::queryEquivalents);
    }

    private Set<OWLClass> queryEquivalents(OWLClass object) {
        final String c = object.getIRI().toString();
        final String query = PREFIXES
              + "SELECT DISTINCT ?e WHERE { GRAPH <" + graph() + "> { "
              + "  { <" + c + "> owl:equivalentClass ?e } UNION { ?e owl:equivalentClass <" + c + "> } "
              + "  FILTER(?e != <" + c + ">) "
              + "} }";
        return runClassQuery(query, "e");
    }

    @Override
    public boolean containsReference(OWLClass object) {
        if (!isConfigured()) {
            return false;
        }
        final String c = object.getIRI().toString();
        final String query = PREFIXES
              + "ASK { GRAPH <" + graph() + "> { { <" + c + "> ?p ?o } UNION { ?s ?q <" + c + "> } } }";
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
        clearCaches();
        store.shutDown();
    }
}
