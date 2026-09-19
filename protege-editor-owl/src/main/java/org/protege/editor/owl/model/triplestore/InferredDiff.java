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
            // Classification results = subsumptions the reasoner DISCOVERED: inferred subClassOf edges
            // that are neither asserted nor trivially entailed by a defined class's own genus (a defined
            // class bar = foo AND (R some X) always yields bar subClassOf foo -- that is definitional,
            // not a discovery). Two anchored shapes, each cheap because the anchor scopes the cross-graph
            // anti-join Virtuoso would otherwise reject (~10000s) on the full graph:
            //   (1) a primitive class (asserted owl:Class, no equivalentClass) with an inferred parent it
            //       does not assert -- the reasoner-derived subsumption (e.g. bar placed under foo);
            //   (2) a defined class with an inferred parent that is not one of its own genus conjuncts.
            // (2) is usually empty; (1) is the typical curator result. Both are tiny, so the panel no
            // longer materialises the whole inferred hierarchy (which was ~37k trivial genus edges).
            String primitiveDiscovered = LazyTripleStore.PREFIXES
                  + "SELECT DISTINCT ?c ?p WHERE { "
                  + "  GRAPH <" + inferred + "> { ?c rdfs:subClassOf ?p } "
                  + "  GRAPH <" + asserted + "> { ?c a owl:Class } "
                  + "  FILTER NOT EXISTS { GRAPH <" + asserted + "> { ?c rdfs:subClassOf ?p } } "
                  + "  FILTER NOT EXISTS { GRAPH <" + asserted + "> { ?c owl:equivalentClass ?e } } }";
            String definedDiscovered = LazyTripleStore.PREFIXES
                  + "SELECT DISTINCT ?c ?p WHERE { "
                  + "  GRAPH <" + inferred + "> { ?c rdfs:subClassOf ?p } "
                  + "  GRAPH <" + asserted + "> { ?c owl:equivalentClass ?edef } "
                  + "  FILTER(?c != ?p) "
                  + "  FILTER NOT EXISTS { GRAPH <" + asserted + "> { "
                  + "    ?c owl:equivalentClass ?e2 . ?e2 owl:intersectionOf ?l . ?l rdf:rest*/rdf:first ?p } } }";
            for (String query : new String[] { primitiveDiscovered, definedDiscovered }) {
                for (String[] pair : store.selectPairs(query, "c", "p")) {
                    if (pair[0] != null && pair[1] != null) {
                        result.add(df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(pair[0])),
                                df.getOWLClass(IRI.create(pair[1]))));
                    }
                }
            }

            // Inferred equivalences are few; still drop any already asserted (either direction) via a
            // cheap client-side diff from two JOIN-only queries (no anti-join, no isIRI).
            String allEquivQuery = LazyTripleStore.PREFIXES
                  + "SELECT ?a ?b WHERE { GRAPH <" + inferred + "> { ?a owl:equivalentClass ?b . "
                  + "FILTER(?a != ?b) } }";
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
