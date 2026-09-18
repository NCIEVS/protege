package org.protege.editor.owl.server.classify;

import java.util.Collections;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLAxiom;

/**
 * Outcome of a classification: either the inferred hierarchy as axioms (to write into the project's
 * {@code /inferred} graph), or a rejection when the classifier refused the ontology (e.g. role
 * domain/range problems), in which case the inferred graph is left untouched.
 */
public final class ClassificationOutcome {

    private final boolean classified;
    private final Set<OWLAxiom> inferredAxioms;

    private ClassificationOutcome(boolean classified, Set<OWLAxiom> inferredAxioms) {
        this.classified = classified;
        this.inferredAxioms = inferredAxioms;
    }

    public static ClassificationOutcome classified(Set<OWLAxiom> inferredAxioms) {
        return new ClassificationOutcome(true,
                inferredAxioms == null ? Collections.<OWLAxiom>emptySet() : inferredAxioms);
    }

    public static ClassificationOutcome rejected() {
        return new ClassificationOutcome(false, Collections.<OWLAxiom>emptySet());
    }

    public boolean isClassified() {
        return classified;
    }

    public Set<OWLAxiom> getInferredAxioms() {
        return inferredAxioms;
    }
}
