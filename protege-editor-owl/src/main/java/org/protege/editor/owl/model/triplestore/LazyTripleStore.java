package org.protege.editor.owl.model.triplestore;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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

    // Endpoint/graph come from the shared TripleStoreContext unless explicitly overridden (tests).
    // The repository is built lazily on first use, so nothing connects until a project is opened.
    private final String overrideEndpoint;
    private final String overrideGraph;
    private volatile SPARQLRepository repository;
    private volatile String repositoryEndpoint;

    public LazyTripleStore() {
        this.overrideEndpoint = null;
        this.overrideGraph = null;
    }

    public LazyTripleStore(String endpointUrl, String graphIri) {
        this.overrideEndpoint = endpointUrl;
        this.overrideGraph = graphIri;
    }

    private String endpoint() {
        return overrideEndpoint != null ? overrideEndpoint : TripleStoreContext.getInstance().endpoint();
    }

    public boolean isConfigured() {
        String g = graph();
        return g != null && !g.isEmpty();
    }

    public String graph() {
        return overrideGraph != null ? overrideGraph : TripleStoreContext.getInstance().graph();
    }

    // Lazily create (or recreate, if the endpoint changed) the SPARQL repository. Callers use this
    // only after checking isConfigured(), so a connection is opened only once a project is active.
    private synchronized RepositoryConnection getConnection() {
        String ep = endpoint();
        if (repository == null || !ep.equals(repositoryEndpoint)) {
            SPARQLRepository previous = repository;
            repository = new SPARQLRepository(ep);
            repository.initialize();
            repositoryEndpoint = ep;
            logger.info("Lazy triple store client on {} graph <{}>", ep, graph());
            if (previous != null) {
                try {
                    previous.shutDown();
                } catch (Exception e) {
                    logger.warn("Error shutting down previous triple store connection", e);
                }
            }
        }
        return repository.getConnection();
    }

    /** Values bound to {@code var} across all rows (empty on error). */
    public Set<String> selectValues(String query, String var) {
        Set<String> result = new HashSet<>();
        try (RepositoryConnection conn = getConnection()) {
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
        try (RepositoryConnection conn = getConnection()) {
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

    /** Maps each keyVar value to the first non-null value among valVars (first row wins per key). */
    public Map<String, String> selectMap(String query, String keyVar, String... valVars) {
        Map<String, String> result = new HashMap<>();
        try (RepositoryConnection conn = getConnection()) {
            TupleQuery tupleQuery = conn.prepareTupleQuery(QueryLanguage.SPARQL, query);
            try (TupleQueryResult rows = tupleQuery.evaluate()) {
                while (rows.hasNext()) {
                    BindingSet row = rows.next();
                    Value key = row.getValue(keyVar);
                    if (key == null) {
                        continue;
                    }
                    for (String var : valVars) {
                        Value v = row.getValue(var);
                        if (v != null) {
                            result.putIfAbsent(key.stringValue(), v.stringValue());
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("SELECT failed (returning empty): {}", query, e);
        }
        return result;
    }

    public boolean ask(String query) {
        try (RepositoryConnection conn = getConnection()) {
            BooleanQuery ask = conn.prepareBooleanQuery(QueryLanguage.SPARQL, query);
            return ask.evaluate();
        } catch (Exception e) {
            logger.error("ASK failed (returning false): {}", query, e);
            return false;
        }
    }

    /** Runs a CONSTRUCT and returns the resulting triples (empty model on error). */
    public Model construct(String query) {
        Model model = new LinkedHashModel();
        try (RepositoryConnection conn = getConnection()) {
            GraphQuery graphQuery = conn.prepareGraphQuery(QueryLanguage.SPARQL, query);
            try (GraphQueryResult rows = graphQuery.evaluate()) {
                while (rows.hasNext()) {
                    model.add(rows.next());
                }
            }
        } catch (Exception e) {
            logger.error("CONSTRUCT failed (returning empty): {}", query, e);
        }
        return model;
    }

    /** For each row, [value of keyVar, value of otherVar] (either may be null); empty on error. */
    public java.util.List<String[]> selectPairs(String query, String keyVar, String otherVar) {
        java.util.List<String[]> rowsOut = new java.util.ArrayList<>();
        try (RepositoryConnection conn = getConnection()) {
            TupleQuery tupleQuery = conn.prepareTupleQuery(QueryLanguage.SPARQL, query);
            try (TupleQueryResult rows = tupleQuery.evaluate()) {
                while (rows.hasNext()) {
                    BindingSet row = rows.next();
                    Value k = row.getValue(keyVar);
                    Value o = row.getValue(otherVar);
                    rowsOut.add(new String[] { k == null ? null : k.stringValue(),
                                               o == null ? null : o.stringValue() });
                }
            }
        } catch (Exception e) {
            logger.error("SELECT failed (returning empty): {}", query, e);
        }
        return rowsOut;
    }

    // Fast path for hot tree queries: fetch text/csv directly over HTTP and parse it, avoiding rdf4j's
    // ~0.5s per-query overhead (measured: a small children query is ~5ms via curl vs ~470ms via rdf4j).
    // Assumes cells are IRIs (no embedded commas/quotes/newlines), which holds for the hierarchy queries.
    public java.util.List<String[]> selectPairsCsv(String query) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String line : csvRows(query)) {
            java.util.List<String> cells = splitCsv(line);
            if (cells.size() >= 2) {
                out.add(new String[] { cells.get(0), cells.get(1) });
            }
        }
        return out;
    }

    /** Single-column IRI result over the CSV fast path (see {@link #selectPairsCsv}). */
    public Set<String> selectValuesCsv(String query) {
        Set<String> out = new HashSet<>();
        for (String line : csvRows(query)) {
            java.util.List<String> cells = splitCsv(line);
            if (!cells.isEmpty() && !cells.get(0).isEmpty()) {
                out.add(cells.get(0));
            }
        }
        return out;
    }

    // POST the query asking for text/csv and return the data rows (header dropped); empty on error.
    private java.util.List<String> csvRows(String query) {
        java.util.List<String> rows = new java.util.ArrayList<>();
        java.net.HttpURLConnection conn = null;
        try {
            byte[] body = ("query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&format=csv")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            conn = (java.net.HttpURLConnection) new java.net.URL(endpoint()).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Accept", "text/csv");
            conn.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                r.readLine(); // header row
                String line;
                while ((line = r.readLine()) != null) {
                    rows.add(line);
                }
            }
        } catch (Exception e) {
            logger.error("CSV SELECT failed (returning empty): {}", query, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return rows;
    }

    // Split one Virtuoso CSV row into cells, honouring quotes. Cells are either "quoted" (IRIs/strings,
    // no embedded quote/comma in our queries) or bare (e.g. an EXISTS flag 1/0). Quotes are stripped.
    private static java.util.List<String> splitCsv(String line) {
        java.util.List<String> cells = new java.util.ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i <= n) {
            if (i < n && line.charAt(i) == '"') {
                int end = line.indexOf('"', i + 1);
                if (end < 0) {
                    cells.add(line.substring(i + 1));
                    break;
                }
                cells.add(line.substring(i + 1, end));
                i = end + 2; // past the closing quote and the following comma (if any)
            }
            else {
                int comma = line.indexOf(',', i);
                if (comma < 0) {
                    cells.add(line.substring(i));
                    break;
                }
                cells.add(line.substring(i, comma));
                i = comma + 1;
            }
        }
        return cells;
    }

    /** All triples with the given IRI as subject, within the project graph. */
    public Model describe(String subjectIri) {        return construct(PREFIXES + "CONSTRUCT { <" + subjectIri + "> ?p ?o } WHERE { GRAPH <"
                + graph() + "> { <" + subjectIri + "> ?p ?o } }");
    }

    public synchronized void shutDown() {
        if (repository == null) {
            return;
        }
        try {
            repository.shutDown();
        } catch (Exception e) {
            logger.warn("Error shutting down triple store connection", e);
        } finally {
            repository = null;
            repositoryEndpoint = null;
        }
    }
}
