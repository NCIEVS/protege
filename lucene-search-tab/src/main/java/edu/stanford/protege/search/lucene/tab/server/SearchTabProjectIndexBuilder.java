package edu.stanford.protege.search.lucene.tab.server;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.protege.editor.owl.server.index.ProjectIndexBuilder;
import org.protege.editor.search.lucene.IndexDelegator;
import org.protege.editor.search.lucene.IndexItemsCollector;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologySetProvider;
import org.semanticweb.owlapi.util.AnnotationValueShortFormProvider;
import org.semanticweb.owlapi.util.ShortFormProvider;

import edu.stanford.protege.search.lucene.tab.engine.IndexRenderingContext;
import edu.stanford.protege.search.lucene.tab.engine.SearchTabIndexer;

/**
 * Server-side {@link ProjectIndexBuilder} that runs the client's {@link SearchTabIndexer} headlessly
 * against a loaded ontology, so the server-built base index is produced by the same code as the
 * client's incremental index. The editor kit's two hooks are supplied from the ontology itself:
 * display names via an rdfs:label / NCI P108 short-form provider (matching the client renderer) and
 * IRI-to-entity lookup via the ontology signature. Discovered by the server through ServiceLoader.
 */
public class SearchTabProjectIndexBuilder implements ProjectIndexBuilder {

    private static final String PREF_NAME_PROPERTY =
            "http://ncicb.nci.nih.gov/xml/owl/EVS/Thesaurus.owl#P108";

    @Override
    public void buildIndex(OWLOntology ontology, File indexDirectory) throws IOException {
        indexDirectory.mkdirs();
        SearchTabIndexer indexer = new SearchTabIndexer(headlessContext(ontology));
        Directory directory = FSDirectory.open(indexDirectory.toPath());
        IndexDelegator delegator = IndexDelegator.getInstance(directory, indexer.getIndexWriterConfig());
        try {
            IndexItemsCollector collector = indexer.getIndexItemsCollector(delegator, progress -> { });
            ontology.accept(collector);
            delegator.commitIndex();
        }
        finally {
            delegator.dispose();
        }
    }

    private static IndexRenderingContext headlessContext(OWLOntology ontology) {
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLAnnotationProperty rdfsLabel = df.getRDFSLabel();
        OWLAnnotationProperty prefName = df.getOWLAnnotationProperty(IRI.create(PREF_NAME_PROPERTY));
        OWLOntologySetProvider ontologies = () -> java.util.stream.Stream.of(ontology);
        final ShortFormProvider baseShortForms = new AnnotationValueShortFormProvider(
                Arrays.asList(rdfsLabel, prefName), Collections.emptyMap(), ontologies);
        // Cache each entity's short form: it is stable during the build and is otherwise recomputed
        // (an annotation lookup) for every occurrence of the entity across the ~200K logical axioms.
        final Map<OWLEntity, String> shortFormCache = new HashMap<>();
        final ShortFormProvider shortForms = new ShortFormProvider() {
            @Override
            public String getShortForm(OWLEntity entity) {
                return shortFormCache.computeIfAbsent(entity, baseShortForms::getShortForm);
            }
            @Override
            public void dispose() {
                baseShortForms.dispose();
            }
        };
        final ManchesterOWLSyntaxOWLObjectRendererImpl axiomRenderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
        axiomRenderer.setShortFormProvider(shortForms);
        // Index the signature once: the indexer resolves an entity per annotation assertion, and
        // ontology.entitiesInSignature(iri) is a full signature scan per call -- O(N^2) over the
        // millions of lookups when indexing the whole Thesaurus (the server build took many minutes
        // vs. the old client index's under a minute).
        final Map<IRI, OWLEntity> entityByIri = new HashMap<>();
        ontology.signature().forEach(e -> entityByIri.putIfAbsent(e.getIRI(), e));
        return new IndexRenderingContext() {
            @Override
            public String getRendering(OWLObject object) {
                if (object instanceof OWLEntity) {
                    return shortForms.getShortForm((OWLEntity) object);
                }
                return axiomRenderer.render(object);
            }
            @Override
            public Optional<OWLEntity> getEntity(IRI iri) {
                return Optional.ofNullable(entityByIri.get(iri));
            }
        };
    }
}
