package gov.nih.nci.curator.constraints;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;

/**
 * A single edit-time constraint violation: the offending class, the axiom that caused it, the kind
 * of check, and a human-readable message. Returned (not thrown) so callers can surface violations
 * without blocking the edit.
 */
public class Violation {

    public enum Kind {
        /** A class expression the curator does not support (complement, allValuesFrom, cardinality, ...). */
        UNSUPPORTED_CONSTRUCT,
        /** A role used where its declared domain/range is incompatible with the class or filler. */
        BAD_ROLE_DOMAIN_RANGE
    }

    private final OWLClass cls;
    private final OWLAxiom axiom;
    private final Kind kind;
    private final String message;

    public Violation(OWLClass cls, OWLAxiom axiom, Kind kind, String message) {
        this.cls = cls;
        this.axiom = axiom;
        this.kind = kind;
        this.message = message;
    }

    public OWLClass getOWLClass() {
        return cls;
    }

    public OWLAxiom getAxiom() {
        return axiom;
    }

    public Kind getKind() {
        return kind;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return kind + ": " + message;
    }
}
