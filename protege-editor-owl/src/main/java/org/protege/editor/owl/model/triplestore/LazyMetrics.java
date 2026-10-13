package org.protege.editor.owl.model.triplestore;

/**
 * Server-side (Virtuoso) COUNT helpers for the Active Ontology metrics under the lazy model. The
 * stock metrics count the sparse in-RAM signature, which is wrong when a server project is open, so
 * the entity-count metrics ask Virtuoso instead. Each method returns {@code null} when the lazy
 * model is inactive (a locally file-opened ontology) or the query fails, so the caller falls back to
 * the stock count.
 */
public final class LazyMetrics {

    private static final String OWL = "http://www.w3.org/2002/07/owl#";

    private LazyMetrics() {
    }

    /** Named (non-skolem) {@code owl:Class} count, or null if the lazy model is inactive / the query fails. */
    public static Integer namedClassCount() {
        LazyTripleStore store = new LazyTripleStore();
        if (!store.isConfigured()) {
            return null;
        }
        String g = store.graph();
        Integer named = scalar(store, "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + g + "> { ?c a <"
                + OWL + "Class> FILTER(!STRSTARTS(STR(?c), \"urn:skolem:\")) } }");
        if (named != null) {
            return named;
        }
        // STRSTARTS over the full class scan can trip a cost-limited Virtuoso; fall back to the raw
        // owl:Class total (cheap, slightly inflated by anonymous intersection nodes).
        return scalar(store, "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + g + "> { ?c a <"
                + OWL + "Class> } }");
    }

    /** Count of subjects typed {@code owl:<localName>} in the active graph, or null if inactive / fails. */
    public static Integer owlTypeCount(String localName) {
        LazyTripleStore store = new LazyTripleStore();
        if (!store.isConfigured()) {
            return null;
        }
        return scalar(store, "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <" + store.graph() + "> { ?x a <"
                + OWL + localName + "> } }");
    }

    private static Integer scalar(LazyTripleStore store, String query) {
        for (String v : store.selectValuesCsv(query)) {
            try {
                return Integer.valueOf(v.trim());
            } catch (NumberFormatException ignore) {
                // non-numeric cell -> treat as no result
            }
        }
        return null;
    }
}
