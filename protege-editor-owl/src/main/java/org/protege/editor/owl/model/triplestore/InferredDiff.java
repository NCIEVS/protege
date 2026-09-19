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
            // Virtuoso's cost estimator rejects a cross-graph anti-join (NOT EXISTS / MINUS /
            // OPTIONAL+!BOUND) over the full graph -- it estimates ~10000s (> the 400s limit) and
            // refuses to run, even though the equivalent JOIN runs in milliseconds. So compute the set
            // difference on the client from two cheap queries: all inferred edges, minus the inferred
            // edges that are also asserted (a cross-graph join).
            String allSubQuery = LazyTripleStore.PREFIXES
                  + "SELECT ?c ?p WHERE { GRAPH <" + inferred + "> { "
                  + "?c rdfs:subClassOf ?p . FILTER(isIRI(?c) && isIRI(?p)) } }";
            String assertedSubQuery = LazyTripleStore.PREFIXES
                  + "SELECT ?c ?p WHERE { GRAPH <" + inferred + "> { ?c rdfs:subClassOf ?p } "
                  + "GRAPH <" + asserted + "> { ?c rdfs:subClassOf ?p } }";
            Set<String> assertedSub = pairKeys(store, assertedSubQuery, "c", "p");
            for (String[] pair : store.selectPairs(allSubQuery, "c", "p")) {
                if (pair[0] == null || pair[1] == null || assertedSub.contains(key(pair[0], pair[1]))) {
                    continue;
                }
                result.add(df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(pair[0])),
                        df.getOWLClass(IRI.create(pair[1]))));
            }

            String allEquivQuery = LazyTripleStore.PREFIXES
                  + "SELECT ?a ?b WHERE { GRAPH <" + inferred + "> { ?a owl:equivalentClass ?b . "
                  + "FILTER(isIRI(?a) && isIRI(?b) && ?a != ?b) } }";
            String assertedEquivQuery = LazyTripleStore.PREFIXES
                  + "SELECT ?a ?b WHERE { GRAPH <" + inferred + "> { ?a owl:equivalentClass ?b } "
                  + "GRAPH <" + asserted + "> { { ?a owl:equivalentClass ?b } UNION { ?b owl:equivalentClass ?a } } }";
            Set<String> assertedEquiv = pairKeys(store, assertedEquivQuery, "a", "b");
            Set<String> seen = new HashSet<>();
            for (String[] pair : store.selectPairs(allEquivQuery, "a", "b")) {
                if (pair[0] == null || pair[1] == null || assertedEquiv.contains(key(pair[0], pair[1]))) {
                    continue;
                }
                // equivalence is symmetric; keep one axiom per unordered pair
                String unordered = pair[0].compareTo(pair[1]) < 0 ? key(pair[0], pair[1]) : key(pair[1], pair[0]);
                if (seen.add(unordered)) {
                    result.add(df.getOWLEquivalentClassesAxiom(df.getOWLClass(IRI.create(pair[0])),
                            df.getOWLClass(IRI.create(pair[1]))));
                }
            }
        } finally {
            store.shutDown();
        }
        return result;
    }

    // The set of directed pair keys returned by a two-variable query.
    private static Set<String> pairKeys(LazyTripleStore store, String query, String a, String b) {
        Set<String> keys = new HashSet<>();
        for (String[] pair : store.selectPairs(query, a, b)) {
            if (pair[0] != null && pair[1] != null) {
                keys.add(key(pair[0], pair[1]));
            }
        }
        return keys;
    }

    // A delimiter that cannot occur in an IRI, so subject+object round-trips unambiguously.
    private static String key(String s, String o) {
        return s + '\u0001' + o;
    }

}
