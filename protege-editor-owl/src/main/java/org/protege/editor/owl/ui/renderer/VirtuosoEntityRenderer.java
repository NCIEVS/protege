package org.protege.editor.owl.ui.renderer;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.triplestore.LazyTripleStore;
import org.semanticweb.owlapi.model.IRI;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity renderer for the lazy read model: resolves an entity's display label by querying the
 * project's named graph in the triple store instead of an in-RAM ontology, so the tree/frames show
 * names rather than bare IRIs/codes. Falls back to the IRI short form when no label is found.
 *
 * <p>Enabled with {@code -Dnci.lazyHierarchy=true}. The preferred-label property defaults to
 * {@code rdfs:label} with the NCI preferred name ({@code P108}) as a fallback; override the latter
 * with {@code -Dnci.tripleStore.prefNameProperty}. Results are cached per IRI.
 */
public class VirtuosoEntityRenderer extends AbstractOWLEntityRenderer {

    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    private final LazyTripleStore store = new LazyTripleStore();
    private final String prefNameProperty = System.getProperty("nci.tripleStore.prefNameProperty",
            "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#P108");
    private final Map<IRI, String> cache = new ConcurrentHashMap<>();

    @Override
    public void initialise() {
        // No setup required beyond the triple store client.
    }

    @Override
    public String render(IRI iri) {
        if (!store.isConfigured()) {
            return iri.getShortForm();
        }
        return cache.computeIfAbsent(iri, this::queryLabel);
    }

    private String queryLabel(IRI iri) {
        String query = LazyTripleStore.PREFIXES
              + "SELECT ?label ?pref WHERE { GRAPH <" + store.graph() + "> { "
              + "  OPTIONAL { <" + iri + "> <" + RDFS_LABEL + "> ?label } "
              + "  OPTIONAL { <" + iri + "> <" + prefNameProperty + "> ?pref } "
              + "} } LIMIT 1";
        Optional<String> label = store.selectFirst(query, "label", "pref");
        return label.orElseGet(iri::getShortForm);
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public boolean configure(OWLEditorKit eKit) {
        return false;
    }

    @Override
    protected void disposeRenderer() {
        cache.clear();
        store.shutDown();
    }
}
