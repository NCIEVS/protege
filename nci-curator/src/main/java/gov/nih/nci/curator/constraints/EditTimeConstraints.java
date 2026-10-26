package gov.nih.nci.curator.constraints;

import java.util.ArrayList;
import java.util.List;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;

import gov.nih.nci.curator.CuratorReasonerPreferences;
import gov.nih.nci.curator.CuratorReasonerPreferences.OptionalEditChecksTask;
import gov.nih.nci.curator.owlapi.KnowledgeBase;
import gov.nih.nci.curator.utils.RolesVisitor;

/**
 * Runs the curator's edit-time constraint checks against a single class, independent of a full
 * classification, so a modeler gets feedback while editing rather than only after classify. Each
 * check is gated by its {@link CuratorReasonerPreferences} toggle (all off by default), and the
 * "domain" a check compares against is supplied by a {@link DomainResolver} so no whole-graph
 * taxonomy is required. Violations are returned, never thrown, so the caller decides how to surface
 * them without blocking the edit.
 */
public class EditTimeConstraints {

    private final RolesVisitor rolesVisitor;
    private final CuratorReasonerPreferences prefs;

    public EditTimeConstraints(KnowledgeBase kb, OWLOntology ont) {
        this.rolesVisitor = new RolesVisitor(null, ont, kb);
        this.prefs = CuratorReasonerPreferences.getInstance();
    }

    /** Run the enabled edit-time checks against {@code c}, resolving domains via {@code resolver}. */
    public List<Violation> check(OWLClass c, DomainResolver resolver) {
        List<Violation> violations = new ArrayList<>();
        // The role checks (unsupported constructs + role domain/range) are the ones implemented as
        // per-class checks that need only the class's own axioms plus its ancestor-to-root domain;
        // gate them behind CHECK_UNSUPPORTED_CONSTRUCTS. The remaining OptionalEditChecksTask entries
        // (redundant parent, disjoint parents, subclass-of-defined, bad re-tree) are still produced
        // only as byproducts of full classification and are not yet standalone here.
        if (prefs.isEnabled(OptionalEditChecksTask.CHECK_UNSUPPORTED_CONSTRUCTS)) {
            rolesVisitor.setEntity(c, true, resolver);
            c.accept(rolesVisitor);
            String name = c.getIRI().getShortForm();
            for (OWLAxiom ax : rolesVisitor.bad_constructs) {
                violations.add(new Violation(c, ax, Violation.Kind.UNSUPPORTED_CONSTRUCT,
                        "Class " + name + " uses an unsupported construct"));
            }
            for (OWLAxiom ax : rolesVisitor.bad_roles) {
                violations.add(new Violation(c, ax, Violation.Kind.BAD_ROLE_DOMAIN_RANGE,
                        "Class " + name + " uses a role outside its declared domain/range"));
            }
        }
        return violations;
    }
}
