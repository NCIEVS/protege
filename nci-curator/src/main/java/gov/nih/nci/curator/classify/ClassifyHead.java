package gov.nih.nci.curator.classify;

import java.io.File;
import java.util.Set;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import gov.nih.nci.curator.owlapi.KnowledgeBase;

/**
 * Standalone proof harness: load an ontology file, run the headless {@link ProjectClassifier} over
 * it, and report the inferred hierarchy (a stand-in for classifying a server-materialized head).
 * Usage: {@code ClassifyHead <ontology-file>}.
 */
public class ClassifyHead {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ClassifyHead <ontology-file>");
            System.exit(2);
        }
        File file = new File(args[0]);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.setOntologyLoaderConfiguration(new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));

        long t0 = System.currentTimeMillis();
        OWLOntology ont = manager.loadOntologyFromOntologyDocument(file);
        int classCount = ont.getClassesInSignature().size();
        System.out.println("Loaded " + file.getName() + ": " + classCount + " classes in "
                + (System.currentTimeMillis() - t0) + " ms");

        long t1 = System.currentTimeMillis();
        ClassificationResult result = new ProjectClassifier().classify(ont);
        System.out.println("Classified in " + (System.currentTimeMillis() - t1) + " ms");

        if (!result.isClassified()) {
            System.out.println("REJECTED (role domain/range issues): badRoles on "
                    + result.getBadRoles().size() + " classes, badConstructs on "
                    + result.getBadConstructs().size() + " classes");
            return;
        }

        InferredHierarchy hierarchy = result.getHierarchy();
        // Diff the inferred direct-supers against the asserted parents to count what classification
        // actually added, and print a small sample of it.
        KnowledgeBase kb = new KnowledgeBase(ont);
        int directEdges = 0;
        int equivalents = 0;
        int inferredNotAsserted = 0;
        int sample = 0;
        for (OWLClass c : hierarchy.getClasses()) {
            if (c.isOWLThing() || c.isOWLNothing()) {
                continue;
            }
            Set<OWLClass> supers = hierarchy.getDirectSuperClasses(c);
            directEdges += supers.size();
            equivalents += hierarchy.getEquivalents(c).size();
            Set<OWLClass> asserted = kb.getStatedParents(c);
            for (OWLClass sup : supers) {
                if (sup.isOWLThing() || asserted.contains(sup)) {
                    continue;
                }
                inferredNotAsserted++;
                if (sample < 25) {
                    System.out.println("  inferred: " + c.getIRI().getShortForm() + " <= "
                            + sup.getIRI().getShortForm());
                    sample++;
                }
            }
        }
        System.out.println("classes=" + hierarchy.getClasses().size()
                + " directSuperEdges=" + directEdges
                + " equivalencePairs=" + (equivalents / 2)
                + " inferredNotAsserted=" + inferredNotAsserted);
    }
}
