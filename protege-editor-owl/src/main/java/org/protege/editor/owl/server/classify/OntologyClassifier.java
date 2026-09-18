package org.protege.editor.owl.server.classify;

import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Server-side hook for classifying a project's ontology. Declared here (dependency inversion) so the
 * server can run the curator's classifier without protege-editor-owl depending on the nci-curator
 * plugin. The implementation is discovered via {@link java.util.ServiceLoader}; if none is on the
 * classpath the server reports classification as unavailable rather than failing.
 *
 * <p>The result is the inferred hierarchy as axioms; the server writes those into the project's
 * separate {@code /inferred} named graph. Classification never touches the asserted graph.
 */
public interface OntologyClassifier {

    /** Classify {@code head} (a materialized project head) and return the inferred axioms. */
    ClassificationOutcome classify(OWLOntology head);
}
