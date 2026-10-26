package gov.nih.nci.curator.classify;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;

/**
 * Outcome of a headless classification: either the inferred hierarchy, or -- when the curator
 * refuses to classify because of role domain/range problems -- the offending axioms per class (the
 * same signal the edit-time checks surface). The server's classify endpoint returns one or the other.
 */
public class ClassificationResult {

    private final boolean classified;
    private final InferredHierarchy hierarchy;
    private final Map<OWLClass, Set<OWLAxiom>> badRoles;
    private final Map<OWLClass, Set<OWLAxiom>> badConstructs;

    private ClassificationResult(boolean classified, InferredHierarchy hierarchy,
            Map<OWLClass, Set<OWLAxiom>> badRoles, Map<OWLClass, Set<OWLAxiom>> badConstructs) {
        this.classified = classified;
        this.hierarchy = hierarchy;
        this.badRoles = badRoles;
        this.badConstructs = badConstructs;
    }

    public static ClassificationResult classified(InferredHierarchy hierarchy) {
        return new ClassificationResult(true, hierarchy, Collections.emptyMap(), Collections.emptyMap());
    }

    public static ClassificationResult rejected(Map<OWLClass, Set<OWLAxiom>> badRoles,
            Map<OWLClass, Set<OWLAxiom>> badConstructs) {
        return new ClassificationResult(false, null,
                badRoles == null ? Collections.emptyMap() : badRoles,
                badConstructs == null ? Collections.emptyMap() : badConstructs);
    }

    public boolean isClassified() {
        return classified;
    }

    public InferredHierarchy getHierarchy() {
        return hierarchy;
    }

    public Map<OWLClass, Set<OWLAxiom>> getBadRoles() {
        return badRoles;
    }

    public Map<OWLClass, Set<OWLAxiom>> getBadConstructs() {
        return badConstructs;
    }
}
