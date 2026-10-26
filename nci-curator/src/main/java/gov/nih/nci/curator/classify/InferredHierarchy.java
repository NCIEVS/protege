package gov.nih.nci.curator.classify;

import java.util.HashSet;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import gov.nih.nci.curator.taxonomy.Taxonomy;

/**
 * Read-only view of a classified taxonomy: the inferred direct-subclass edges and equivalence sets
 * the curator computed. This is the payload a server-side classify produces and the future inferred
 * named-graph projection is written from -- it holds only OWL value objects, no reasoner state.
 */
public class InferredHierarchy {

    private final Taxonomy<OWLClass> taxonomy;

    public InferredHierarchy(Taxonomy<OWLClass> taxonomy) {
        this.taxonomy = taxonomy;
    }

    public Set<OWLClass> getClasses() {
        return taxonomy.getClasses();
    }

    /** Direct inferred superclasses of {@code c}, flattened across equivalence sets. */
    public Set<OWLClass> getDirectSuperClasses(OWLClass c) {
        return taxonomy.getFlattenedSupers(c, true);
    }

    /** Classes inferred equivalent to {@code c}, excluding {@code c} itself. */
    public Set<OWLClass> getEquivalents(OWLClass c) {
        Set<OWLClass> equivalents = new HashSet<>(taxonomy.getAllEquivalents(c));
        equivalents.remove(c);
        return equivalents;
    }

    /** The inferred direct named subclass edges as axioms (owl:Thing/Nothing excluded). */
    public Set<OWLSubClassOfAxiom> toSubClassOfAxioms(OWLDataFactory df) {
        Set<OWLSubClassOfAxiom> axioms = new HashSet<>();
        for (OWLClass c : getClasses()) {
            if (c.isOWLThing() || c.isOWLNothing()) {
                continue;
            }
            for (OWLClass sup : getDirectSuperClasses(c)) {
                if (!c.equals(sup) && !sup.isOWLThing() && !sup.isOWLNothing()) {
                    axioms.add(df.getOWLSubClassOfAxiom(c, sup));
                }
            }
        }
        return axioms;
    }

    /** The inferred equivalence sets as axioms (one per set of size &gt; 1, owl:Thing/Nothing excluded). */
    public Set<OWLEquivalentClassesAxiom> toEquivalentClassesAxioms(OWLDataFactory df) {
        Set<OWLEquivalentClassesAxiom> axioms = new HashSet<>();
        Set<OWLClass> seen = new HashSet<>();
        for (OWLClass c : getClasses()) {
            if (c.isOWLThing() || c.isOWLNothing() || seen.contains(c)) {
                continue;
            }
            Set<OWLClass> members = new HashSet<>(taxonomy.getAllEquivalents(c));
            members.add(c);
            members.remove(df.getOWLThing());
            members.remove(df.getOWLNothing());
            if (members.size() > 1) {
                axioms.add(df.getOWLEquivalentClassesAxiom(new HashSet<OWLClassExpression>(members)));
                seen.addAll(members);
            }
        }
        return axioms;
    }

    /** The full inferred projection payload: direct subclass edges plus equivalence axioms. */
    public Set<OWLAxiom> toAxioms(OWLDataFactory df) {
        Set<OWLAxiom> axioms = new HashSet<>();
        axioms.addAll(toSubClassOfAxioms(df));
        axioms.addAll(toEquivalentClassesAxioms(df));
        return axioms;
    }
}
