package org.protege.editor.owl.model.triplestore;

import org.semanticweb.owlapi.model.IRI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared, batch-primeable cache of entity display labels for the lazy read model. Both the entity
 * renderer (per-row paint) and the hierarchy provider (sorting siblings) resolve labels through
 * here, so a single batch prefetch can prime a whole set of siblings in one SPARQL query instead of
 * one round trip per IRI. Without this, expanding a node with K children fired K label queries (one
 * per child, for the sort comparator) and scrolling fired one per newly visible row.
 *
 * <p>Config mirrors {@code VirtuosoEntityRenderer}: preferred label is {@code rdfs:label} with the
 * NCI preferred name ({@code P108}, overridable via {@code -Dnci.tripleStore.prefNameProperty}) as a
 * fallback, and the IRI short form when neither is present.
 */
public final class LazyLabelCache {

    private static final Logger logger = LoggerFactory.getLogger(LazyLabelCache.class);

    private static final LazyLabelCache INSTANCE = new LazyLabelCache();

    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final int CHUNK = 1000;

    private final LazyTripleStore store = new LazyTripleStore();
    private final String prefNameProperty = System.getProperty("nci.tripleStore.prefNameProperty",
            "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#P108");
    private final Map<IRI, String> cache = new ConcurrentHashMap<>();

    private LazyLabelCache() {
    }

    public static LazyLabelCache getInstance() {
        return INSTANCE;
    }

    public boolean isConfigured() {
        return store.isConfigured();
    }

    /** Cached display label for a single IRI, fetching (and caching) it if not already primed. */
    public String label(IRI iri) {
        if (!store.isConfigured()) {
            return iri.getShortForm();
        }
        String cached = cache.get(iri);
        if (cached != null) {
            return cached;
        }
        prefetch(Collections.singleton(iri));
        return cache.getOrDefault(iri, iri.getShortForm());
    }

    /**
     * Fetch labels for many IRIs in one query and prime the cache, so subsequent per-row/sort
     * renders are cache hits rather than a SPARQL round trip each. Already-cached IRIs are skipped.
     */
    public void prefetch(Collection<IRI> iris) {
        if (!store.isConfigured() || iris.isEmpty()) {
            return;
        }
        List<IRI> missing = new ArrayList<>();
        for (IRI iri : iris) {
            if (!cache.containsKey(iri)) {
                missing.add(iri);
            }
        }
        for (int i = 0; i < missing.size(); i += CHUNK) {
            queryChunk(missing.subList(i, Math.min(i + CHUNK, missing.size())));
        }
    }

    private void queryChunk(List<IRI> iris) {
        StringBuilder values = new StringBuilder();
        for (IRI iri : iris) {
            values.append('<').append(iri).append("> ");
        }
        String query = LazyTripleStore.PREFIXES
              + "SELECT ?s ?label ?pref WHERE { GRAPH <" + store.graph() + "> { "
              + "  VALUES ?s { " + values + "} "
              + "  OPTIONAL { ?s <" + RDFS_LABEL + "> ?label } "
              + "  OPTIONAL { ?s <" + prefNameProperty + "> ?pref } "
              + "} }";
        Map<String, String> labels = store.selectMap(query, "s", "label", "pref");
        for (IRI iri : iris) {
            String label = labels.get(iri.toString());
            cache.put(iri, label != null ? label : iri.getShortForm());
        }
        logger.debug("Primed {} labels ({} resolved)", iris.size(), labels.size());
    }

    public void clear() {
        cache.clear();
    }
}
