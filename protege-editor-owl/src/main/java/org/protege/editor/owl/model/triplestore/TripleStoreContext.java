package org.protege.editor.owl.model.triplestore;

/**
 * Holds the triple store (Virtuoso) endpoint and named graph the lazy read model targets, and acts
 * as the gate that keeps that model dormant until a project is opened. The lazy hierarchy provider,
 * entity renderer/label cache and class loader read their endpoint and graph from here per query
 * rather than capturing them at construction, so before a project is opened there is no graph, the
 * model is not {@link #isActive() active}, and nothing connects to the store.
 *
 * <p>Open-From-Server configures this from the opened project the same way the server derives its
 * write graph: endpoint from the server's {@code triple_store_url} config property, graph from the
 * project's {@code namespace + "/" + name}. Defaults fall back to {@code -Dnci.tripleStore.url} and
 * {@code -Dnci.tripleStore.graph} so direct-property testing still works.
 */
public final class TripleStoreContext {

    private static final TripleStoreContext INSTANCE = new TripleStoreContext();

    private static final String DEFAULT_ENDPOINT = "http://localhost:8890/sparql/";

    private volatile String endpoint = System.getProperty("nci.tripleStore.url", DEFAULT_ENDPOINT);
    private volatile String graph = System.getProperty("nci.tripleStore.graph");

    private TripleStoreContext() {
    }

    public static TripleStoreContext getInstance() {
        return INSTANCE;
    }

    /** Point the lazy read model at a project's named graph (called on open-from-server). */
    public void configure(String endpoint, String graph) {
        if (endpoint != null && !endpoint.isEmpty()) {
            this.endpoint = endpoint;
        }
        this.graph = graph;
    }

    /** Clear the target so the lazy read model goes dormant again (e.g. project closed). */
    public void clear() {
        this.graph = null;
    }

    public String endpoint() {
        return endpoint;
    }

    public String graph() {
        return graph;
    }

    /** Active only once a graph is configured; the lazy read model stays dormant until then. */
    public boolean isActive() {
        return graph != null && !graph.isEmpty();
    }
}
