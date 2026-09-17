package org.protege.editor.owl.ui.renderer;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.triplestore.LazyLabelCache;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntologyChange;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    // A label edit (rdfs:label / NCI pref name) lands in the in-RAM ontology before it reaches the
    // store, so refresh the shared cache from the model and repaint; otherwise a new/renamed entity
    // shows its bare code in the tree and search results until commit+restart.
    @Override
    protected void processChanges(List<? extends OWLOntologyChange> changes) {
        LazyLabelCache cache = LazyLabelCache.getInstance();
        Set<IRI> affected = new HashSet<>();
        for (OWLOntologyChange change : changes) {
            if (!change.isAxiomChange()) {
                continue;
            }
            OWLAxiom axiom = change.getAxiom();
            if (!(axiom instanceof OWLAnnotationAssertionAxiom)) {
                continue;
            }
            OWLAnnotationAssertionAxiom aaa = (OWLAnnotationAssertionAxiom) axiom;
            if (aaa.getSubject() instanceof IRI && cache.isLabelProperty(aaa.getProperty().getIRI())) {
                affected.add((IRI) aaa.getSubject());
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        OWLModelManager manager = getOWLModelManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        for (IRI iri : affected) {
            if (cache.refreshFromModel(iri, manager.getActiveOntologies())) {
                fireRenderingChanged(df.getOWLClass(iri));
            }
        }
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
