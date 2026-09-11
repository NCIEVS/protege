package org.protege.editor.owl.ui.renderer;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.triplestore.LazyLabelCache;
import org.semanticweb.owlapi.model.IRI;

/**
 * Entity renderer for the lazy read model: resolves an entity's display label by querying the
 * project's named graph in the triple store instead of an in-RAM ontology, so the tree/frames show
 * names rather than bare IRIs/codes. Falls back to the IRI short form when no label is found.
 *
 * <p>Enabled with {@code -Dnci.lazyHierarchy=true}. Labels are resolved and cached through the shared
 * {@link LazyLabelCache} (rdfs:label with NCI {@code P108} fallback), which the hierarchy provider
 * batch-primes for siblings so sorting/painting a node's children is cache hits, not a query per row.
 */
public class VirtuosoEntityRenderer extends AbstractOWLEntityRenderer {

    @Override
    public void initialise() {
        // No setup required beyond the shared label cache.
    }

    @Override
    public String render(IRI iri) {
        return LazyLabelCache.getInstance().label(iri);
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
        LazyLabelCache.getInstance().clear();
    }
}
