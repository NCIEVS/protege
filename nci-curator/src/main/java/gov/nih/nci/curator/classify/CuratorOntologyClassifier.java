package gov.nih.nci.curator.classify;

import org.protege.editor.owl.server.classify.ClassificationOutcome;
import org.protege.editor.owl.server.classify.OntologyClassifier;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Server-side {@link OntologyClassifier} that runs the curator's headless classifier over a
 * materialized project head and returns the inferred hierarchy (subclass edges + equivalences) as
 * axioms. Discovered by the server through {@code ServiceLoader}, so protege-editor-owl classifies
 * without depending on this plugin. A rejection (role domain/range issues) is passed through so the
 * server leaves the inferred graph untouched.
 */
public class CuratorOntologyClassifier implements OntologyClassifier {

    @Override
    public ClassificationOutcome classify(OWLOntology head) {
        ClassificationResult result = new ProjectClassifier().classify(head);
        if (!result.isClassified()) {
            return ClassificationOutcome.rejected();
        }
        OWLDataFactory df = head.getOWLOntologyManager().getOWLDataFactory();
        return ClassificationOutcome.classified(result.getHierarchy().toAxioms(df));
    }
}
