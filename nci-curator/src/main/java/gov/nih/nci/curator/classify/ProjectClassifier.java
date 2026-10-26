package gov.nih.nci.curator.classify;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;

import gov.nih.nci.curator.owlapi.KnowledgeBase;
import gov.nih.nci.curator.taxonomy.Taxonomy;

/**
 * Headless entry point to the curator's full classification: classify an ontology (e.g. a
 * server-materialized project head) and return the inferred hierarchy, with no UI, no reasoner
 * registration, and no dependence on the Protege model manager. This is the reusable core the
 * server-side classify endpoint and the inferred-graph projection are built on.
 */
public class ProjectClassifier {

    /** Classify {@code ont} and return its inferred hierarchy, or the role issues that blocked it. */
    public ClassificationResult classify(OWLOntology ont) {
        KnowledgeBase kb = new KnowledgeBase(ont);
        try {
            kb.classify();
            Taxonomy<OWLClass> taxonomy = kb.getTaxonomyBuilder().getTaxonomy();
            return ClassificationResult.classified(new InferredHierarchy(taxonomy));
        } catch (InconsistentOntologyException e) {
            // The curator refuses to classify while roles violate their domain/range; surface the
            // offending axioms rather than a bare failure so the caller can report them.
            return ClassificationResult.rejected(kb.getBadRoles(), kb.getBadConstructs());
        }
    }
}
