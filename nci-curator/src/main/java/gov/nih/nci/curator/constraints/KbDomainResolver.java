package gov.nih.nci.curator.constraints;

import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.owlapi.KnowledgeBase;

/**
 * Domain resolver that walks a single class's asserted parents to its root ({@link
 * KnowledgeBase#getClassDomain}), so an edit-time check needs only the edited class's ancestor chain
 * rather than a whole-graph domain pass. A root is treated as its own domain (matching how the
 * stated taxonomy assigns the {@code "domain"} datum to roots).
 */
public class KbDomainResolver implements DomainResolver {

    private final KnowledgeBase kb;

    public KbDomainResolver(KnowledgeBase kb) {
        this.kb = kb;
    }

    @Override
    public OWLClass domainOf(OWLClass c) {
        OWLClass domain = kb.getClassDomain(c);
        return (domain != null) ? domain : c;
    }
}
