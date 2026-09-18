package org.protege.editor.owl.model.triplestore;

import java.util.HashSet;
import java.util.Set;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;

/**
 * Computes the inferred-minus-asserted diff for the current project directly in the triple store: the
 * inferred edges the server-side classifier wrote into the {@code <graph>/inferred} graph that are
 * not already asserted in the project graph. Backs the "Curator Classification results" panel under
 * the lazy read model, replacing the in-client reasoner as the source of the displayed suggestions.
 * Display only -- promoting an edge to the asserted model is a normal manual edit.
 */
public final class InferredDiff {

    private InferredDiff() {
    }

    /** Inferred subclass/equivalence edges not present in the asserted graph, as axioms. */
    public static Set<OWLAxiom> compute(OWLDataFactory df) {
        Set<OWLAxiom> result = new HashSet<>();
        String asserted = TripleStoreContext.getInstance().graph();
        if (asserted == null || asserted.isEmpty()) {
            return result;
        }
        String inferred = asserted + "/inferred";
        LazyTripleStore store = new LazyTripleStore();
        try {
            String subClassQuery = LazyTripleStore.PREFIXES
                  + "SELECT ?c ?p WHERE { "
                  + "  GRAPH <" + inferred + "> { ?c rdfs:subClassOf ?p . FILTER(isIRI(?c) && isIRI(?p)) } "
                  + "  FILTER NOT EXISTS { GRAPH <" + asserted + "> { ?c rdfs:subClassOf ?p } } "
                  + "}";
            for (String[] pair : store.selectPairs(subClassQuery, "c", "p")) {
                if (pair[0] != null && pair[1] != null) {
                    result.add(df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(pair[0])),
                            df.getOWLClass(IRI.create(pair[1]))));
                }
            }

            String equivalentQuery = LazyTripleStore.PREFIXES
                  + "SELECT ?a ?b WHERE { "
                  + "  GRAPH <" + inferred + "> { ?a owl:equivalentClass ?b . "
                  + "                             FILTER(isIRI(?a) && isIRI(?b) && ?a != ?b) } "
                  + "  FILTER NOT EXISTS { GRAPH <" + asserted + "> { "
                  + "    { ?a owl:equivalentClass ?b } UNION { ?b owl:equivalentClass ?a } } } "
                  + "}";
            Set<String> seen = new HashSet<>();
            for (String[] pair : store.selectPairs(equivalentQuery, "a", "b")) {
                if (pair[0] == null || pair[1] == null) {
                    continue;
                }
                // equivalence is symmetric; keep one axiom per unordered pair
                String key = pair[0].compareTo(pair[1]) < 0 ? pair[0] + '|' + pair[1] : pair[1] + '|' + pair[0];
                if (seen.add(key)) {
                    result.add(df.getOWLEquivalentClassesAxiom(df.getOWLClass(IRI.create(pair[0])),
                            df.getOWLClass(IRI.create(pair[1]))));
                }
            }
        } finally {
            store.shutDown();
        }
        return result;
    }
}
