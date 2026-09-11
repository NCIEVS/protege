package org.protege.editor.owl.model.triplestore;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Thin shared client for reading a project's named graph from the triple store (Virtuoso) over
 * SPARQL. Backs the lazy read model (hierarchy provider, entity renderer, frames) so endpoint/graph
 * configuration and connection handling live in one place. Queries the store directly for now
 * (client-&gt;Virtuoso); a server-mediated read path is a later decision.
 *
 * <p>Config: {@code -Dnci.tripleStore.url} (default {@code http://localhost:8890/sparql/}) and
 * {@code -Dnci.tripleStore.graph} (the project graph IRI).
 */
public final class LazyTripleStore {

    private static final Logger logger = LoggerFactory.getLogger(LazyTripleStore.class);

    public static final String PREFIXES =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> "
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> "
          + "PREFIX owl: <http://www.w3.org/2002/07/owl#> ";

    private final String graph;
    private final SPARQLRepository repository;

    public LazyTripleStore() {
        this(System.getProperty("nci.tripleStore.url", "http://localhost:8890/sparql/"),
             System.getProperty("nci.tripleStore.graph"));
    }

    public LazyTripleStore(String endpointUrl, String graphIri) {
        this.graph = graphIri;
        this.repository = new SPARQLRepository(endpointUrl);
        this.repository.initialize();
        if (isConfigured()) {
            logger.info("Lazy triple store client on {} graph <{}>", endpointUrl, graphIri);
        } else {
            logger.warn("Lazy triple store client has no graph set (-Dnci.tripleStore.graph)");
        }
    }

    public boolean isConfigured() {
        return graph != null && !graph.isEmpty();
    }

    public String graph() {
        return graph;
    }

    /** Values bound to {@code var} across all rows (empty on error). */
    public Set<String> selectValues(String query, String var) {
        Set<String> result = new HashSet<>();
        try (RepositoryConnection conn = repository.getConnection()) {
            TupleQuery tupleQuery = conn.prepareTupleQuery(QueryLanguage.SPARQL, query);
            try (TupleQueryResult rows = tupleQuery.evaluate()) {
                while (rows.hasNext()) {
                    Value v = rows.next().getValue(var);
                    if (v != null) {
                        result.add(v.stringValue());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("SELECT failed (returning empty): {}", query, e);
        }
        return result;
    }

    /** First value bound to any of {@code vars}, trying them in order (empty on none/error). */
    public Optional<String> selectFirst(String query, String... vars) {
        try (RepositoryConnection conn = repository.getConnection()) {
            TupleQuery tupleQuery = conn.prepareTupleQuery(QueryLanguage.SPARQL, query);
            try (TupleQueryResult rows = tupleQuery.evaluate()) {
                if (rows.hasNext()) {
                    BindingSet row = rows.next();
                    for (String var : vars) {
                        Value v = row.getValue(var);
                        if (v != null) {
                            return Optional.of(v.stringValue());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("SELECT failed (returning empty): {}", query, e);
        }
        return Optional.empty();
    }

    public boolean ask(String query) {
        try (RepositoryConnection conn = repository.getConnection()) {
            BooleanQuery ask = conn.prepareBooleanQuery(QueryLanguage.SPARQL, query);
            return ask.evaluate();
        } catch (Exception e) {
            logger.error("ASK failed (returning false): {}", query, e);
            return false;
        }
    }

    public void shutDown() {
        try {
            repository.shutDown();
        } catch (Exception e) {
            logger.warn("Error shutting down triple store connection", e);
        }
    }
}
