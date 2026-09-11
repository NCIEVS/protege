package org.protege.editor.owl.model.hierarchy;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.protege.editor.owl.model.triplestore.LazyLabelCache;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
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
 * <p>Enabled via {@code -Dnci.lazyHierarchy=true}. Endpoint from {@code -Dnci.tripleStore.url}
 * (default {@code http://localhost:8890/sparql/}); the project graph IRI from
 * {@code -Dnci.tripleStore.graph}. Queries the store directly for now (client-&gt;Virtuoso); a
 * server-mediated read path is a later decision.
 */
public class VirtuosoClassHierarchyProvider extends AbstractOWLObjectHierarchyProvider<OWLClass> {

    private static final Logger logger = LoggerFactory.getLogger(VirtuosoClassHierarchyProvider.class);

    private static final String PREFIXES =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
          + "PREFIX owl: <http://www.w3.org/2002/07/owl#> ";

    private final OWLDataFactory df;
    private final OWLClass thing;
    private final String graph;
    private final SPARQLRepository repository;

    // Read-only browsing cache. No feed-driven invalidation yet (slice 1); clearCaches() is the hook.
    private final Map<OWLClass, Set<OWLClass>> childrenCache = new ConcurrentHashMap<>();
    private final Map<OWLClass, Set<OWLClass>> parentsCache = new ConcurrentHashMap<>();
    private final Map<OWLClass, Set<OWLClass>> equivalentsCache = new ConcurrentHashMap<>();

    public VirtuosoClassHierarchyProvider(OWLOntologyManager manager, String endpointUrl, String graphIri) {
        super(manager);
        this.df = manager.getOWLDataFactory();
        this.thing = df.getOWLThing();
        this.graph = graphIri;
        this.repository = new SPARQLRepository(endpointUrl);
        this.repository.initialize();
        if (graphIri == null || graphIri.isEmpty()) {
            logger.warn("Lazy hierarchy enabled but no graph set (-Dnci.tripleStore.graph); the tree will be empty");
        } else {
            logger.info("Lazy class hierarchy querying {} graph <{}>", endpointUrl, graphIri);
        }
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
        if (graph == null || graph.isEmpty()) {
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
              + "SELECT DISTINCT ?c WHERE { GRAPH <" + graph + "> { "
              + "  ?c rdf:type owl:Class . "
              + "  FILTER(isIRI(?c) && ?c != owl:Thing && ?c != owl:Nothing) "
              + "  FILTER NOT EXISTS { ?c rdfs:subClassOf ?sup . FILTER(isIRI(?sup) && ?sup != owl:Thing) } "
              + "  FILTER NOT EXISTS { ?c owl:equivalentClass ?eq . ?eq owl:intersectionOf ?l . "
              + "                      ?l rdf:rest*/rdf:first ?g . FILTER(isIRI(?g)) } "
              + "} }";
    }

    // Fetch a parent's children AND each child's children in one query, caching every child's child
    // set so the tree's per-child +box lookups (a getChildren call on each child) are cache hits
    // instead of a SPARQL round-trip each -- the "one depth ahead" prefetch in a single query.
    private Set<OWLClass> fetchChildrenAndGrandchildren(OWLClass parent) {
        final String p = parent.getIRI().toString();
        final String query = PREFIXES
              + "SELECT DISTINCT ?c ?gc WHERE { GRAPH <" + graph + "> { "
              + "  { ?c rdfs:subClassOf <" + p + "> } "
              + "  UNION "
              + "  { ?c owl:equivalentClass ?e1 . ?e1 owl:intersectionOf ?l1 . ?l1 rdf:rest*/rdf:first <" + p + "> } "
              + "  FILTER(isIRI(?c) && ?c != <" + p + ">) "
              + "  OPTIONAL { "
              + "    { ?gc rdfs:subClassOf ?c } "
              + "    UNION "
              + "    { ?gc owl:equivalentClass ?e2 . ?e2 owl:intersectionOf ?l2 . ?l2 rdf:rest*/rdf:first ?c } "
              + "    FILTER(isIRI(?gc) && ?gc != ?c) "
              + "  } "
              + "} }";

        Set<OWLClass> children = new HashSet<>();
        Map<OWLClass, Set<OWLClass>> grandchildren = new HashMap<>();
        try (RepositoryConnection conn = repository.getConnection()) {
            TupleQuery tupleQuery = conn.prepareTupleQuery(QueryLanguage.SPARQL, query);
            try (TupleQueryResult rows = tupleQuery.evaluate()) {
                while (rows.hasNext()) {
                    BindingSet row = rows.next();
                    Value childValue = row.getValue("c");
                    if (childValue == null) {
                        continue;
                    }
                    OWLClass child = df.getOWLClass(IRI.create(childValue.stringValue()));
                    children.add(child);
                    Value grandchildValue = row.getValue("gc");
                    if (grandchildValue != null) {
                        grandchildren.computeIfAbsent(child, k -> new HashSet<>())
                                .add(df.getOWLClass(IRI.create(grandchildValue.stringValue())));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Two-level children query failed for {} (returning empty)", parent, e);
        }

        childrenCache.put(parent, children);
        for (OWLClass child : children) {
            childrenCache.putIfAbsent(child, grandchildren.getOrDefault(child, Collections.emptySet()));
        }
        return children;
    }

    @Override
    public Set<OWLClass> getParents(OWLClass object) {
        if (graph == null || graph.isEmpty() || object.equals(thing)) {
            return Collections.emptySet();
        }
        return parentsCache.computeIfAbsent(object, this::queryParents);
    }

    private Set<OWLClass> queryParents(OWLClass object) {
        final String c = object.getIRI().toString();
        final String query = PREFIXES
              + "SELECT DISTINCT ?p WHERE { GRAPH <" + graph + "> { "
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
        if (graph == null || graph.isEmpty()) {
            return Collections.emptySet();
        }
        // Cached: the tree cell renderer asks for equivalents on every cell paint, so an uncached
        // query here fired a SPARQL per row per repaint (a query storm while scrolling).
        return equivalentsCache.computeIfAbsent(object, this::queryEquivalents);
    }

    private Set<OWLClass> queryEquivalents(OWLClass object) {
        final String c = object.getIRI().toString();
        final String query = PREFIXES
              + "SELECT DISTINCT ?e WHERE { GRAPH <" + graph + "> { "
              + "  { <" + c + "> owl:equivalentClass ?e } UNION { ?e owl:equivalentClass <" + c + "> } "
              + "  FILTER(isIRI(?e) && ?e != <" + c + ">) "
              + "} }";
        return runClassQuery(query, "e");
    }

    @Override
    public boolean containsReference(OWLClass object) {
        if (graph == null || graph.isEmpty()) {
            return false;
        }
        final String c = object.getIRI().toString();
        final String query = PREFIXES
              + "ASK { GRAPH <" + graph + "> { { <" + c + "> ?p ?o } UNION { ?s ?q <" + c + "> } } }";
        try (RepositoryConnection conn = repository.getConnection()) {
            BooleanQuery ask = conn.prepareBooleanQuery(QueryLanguage.SPARQL, query);
            return ask.evaluate();
        } catch (Exception e) {
            logger.error("containsReference query failed for {}", object, e);
            return false;
        }
    }

    private Set<OWLClass> runClassQuery(String query, String var) {
        Set<OWLClass> result = new HashSet<>();
        try (RepositoryConnection conn = repository.getConnection()) {
            TupleQuery tupleQuery = conn.prepareTupleQuery(QueryLanguage.SPARQL, query);
            try (TupleQueryResult rows = tupleQuery.evaluate()) {
                while (rows.hasNext()) {
                    BindingSet row = rows.next();
                    Value v = row.getValue(var);
                    if (v != null) {
                        result.add(df.getOWLClass(IRI.create(v.stringValue())));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Hierarchy query failed (returning empty): {}", query, e);
        }
        return result;
    }

    @Override
    public void dispose() {
        super.dispose();
        clearCaches();
        try {
            repository.shutDown();
        } catch (Exception e) {
            logger.warn("Error shutting down triple store connection", e);
        }
    }
}
