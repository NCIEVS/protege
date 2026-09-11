package org.protege.editor.owl.model.hierarchy;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
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
        return childrenCache.computeIfAbsent(object, this::queryChildren);
    }

    private Set<OWLClass> queryChildren(OWLClass object) {
        final String query;
        if (object.equals(thing)) {
            // Children of owl:Thing = named classes with no named superclass and no named genus
            // (the roots/orphans), mirroring AssertedClassHierarchyProvider's terminal elements.
            query = PREFIXES
                  + "SELECT DISTINCT ?c WHERE { GRAPH <" + graph + "> { "
                  + "  ?c rdf:type owl:Class . "
                  + "  FILTER(isIRI(?c) && ?c != owl:Thing && ?c != owl:Nothing) "
                  + "  FILTER NOT EXISTS { ?c rdfs:subClassOf ?sup . FILTER(isIRI(?sup) && ?sup != owl:Thing) } "
                  + "  FILTER NOT EXISTS { ?c owl:equivalentClass ?eq . ?eq owl:intersectionOf ?l . "
                  + "                      ?l rdf:rest*/rdf:first ?g . FILTER(isIRI(?g)) } "
                  + "} }";
        } else {
            final String p = object.getIRI().toString();
            query = PREFIXES
                  + "SELECT DISTINCT ?c WHERE { GRAPH <" + graph + "> { "
                  + "  { ?c rdfs:subClassOf <" + p + "> } "
                  + "  UNION "
                  + "  { ?c owl:equivalentClass ?eq . ?eq owl:intersectionOf ?l . ?l rdf:rest*/rdf:first <" + p + "> } "
                  + "  FILTER(isIRI(?c) && ?c != <" + p + ">) "
                  + "} }";
        }
        return runClassQuery(query, "c");
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
