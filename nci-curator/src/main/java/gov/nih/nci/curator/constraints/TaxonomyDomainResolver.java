package gov.nih.nci.curator.constraints;

import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.taxonomy.Taxonomy;

/**
 * Domain resolver backed by the stated taxonomy's {@code "domain"} datum, as computed by full
 * classification. Used on the classify path so its behaviour is byte-for-byte what it was before the
 * edit-time checks were factored out.
 */
public class TaxonomyDomainResolver implements DomainResolver {

    private final Taxonomy<OWLClass> stated;

    public TaxonomyDomainResolver(Taxonomy<OWLClass> stated) {
        this.stated = stated;
    }

    @Override
    public OWLClass domainOf(OWLClass c) {
        return (OWLClass) stated.getDatum(c, "domain");
    }
}
