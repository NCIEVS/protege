package gov.nih.nci.curator.constraints;

import org.semanticweb.owlapi.model.OWLClass;

/**
 * Resolves the "domain" of a class -- the top-level NCI kind (root) it descends from -- which the
 * edit-time role checks compare against a property's declared domain/range. Full classification
 * computes this once over the whole graph and stashes it as a taxonomy datum; at edit time it is a
 * per-class ancestor-to-root walk, so the whole graph need not be resident. Abstracting it lets the
 * same checks run in both contexts (and later over a lazy triple-store ancestor query).
 */
public interface DomainResolver {

    /** The top-level kind {@code c} descends from; {@code c} itself when it is a root. */
    OWLClass domainOf(OWLClass c);
}
