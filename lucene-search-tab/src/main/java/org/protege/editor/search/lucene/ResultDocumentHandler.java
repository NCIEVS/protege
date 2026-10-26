package org.protege.editor.search.lucene;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.protege.editor.owl.model.search.SearchCategory;
import org.protege.editor.owl.model.search.SearchMetadata;
import org.protege.editor.owl.model.search.SearchResult;
import org.protege.editor.owl.model.search.SearchResultMatch;

import org.apache.lucene.document.Document;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.ImmutableList;

/**
 * Author: Josef Hardi <josef.hardi@stanford.edu><br>
 * Stanford University<br>
 * Bio-Medical Informatics Research Group<br>
 * Date: 23/06/2016
 */
public class ResultDocumentHandler extends AbstractDocumentHandler {

    private OWLEditorKit editorKit;
    private Set<SearchResult> results = new HashSet<>();

    public ResultDocumentHandler(OWLEditorKit editorKit) {
        this.editorKit = editorKit;
    }

    public Set<SearchResult> getSearchResults() {
        return results;
    }

    @Override
    public void handle(SearchCategory category, Document doc) {
        Optional<OWLEntity> subject = getOWLEntity(doc.get(IndexField.ENTITY_IRI));
        if (!subject.isPresent()) {
            // Lazy model: a matching class may not be in the loaded signature yet; reconstruct it
            // from the index (annotation-value hits carry no type, and only classes are lazy).
            subject = entityFromIndex(doc.get(IndexField.ENTITY_IRI), doc.get(IndexField.ENTITY_TYPE));
        }
        if (subject.isPresent()) {
            SearchMetadata metadata = createSearchMetadata(category, doc, subject.get());
            SearchResult searchResult = new SearchResult(metadata, createEmptySearchResultMatch());
            results.add(searchResult);
        }
    }

    private Optional<OWLEntity> entityFromIndex(String iri, String entityType) {
        if (iri == null) {
            return Optional.empty();
        }
        OWLDataFactory df = editorKit.getOWLModelManager().getOWLDataFactory();
        IRI entityIri = IRI.create(iri);
        if (entityType != null) {
            switch (entityType) {
                case "ObjectProperty":     return Optional.of(df.getOWLObjectProperty(entityIri));
                case "DataProperty":       return Optional.of(df.getOWLDataProperty(entityIri));
                case "AnnotationProperty": return Optional.of(df.getOWLAnnotationProperty(entityIri));
                case "NamedIndividual":    return Optional.of(df.getOWLNamedIndividual(entityIri));
                case "Datatype":           return Optional.of(df.getOWLDatatype(entityIri));
                default:                   break;
            }
        }
        return Optional.of(df.getOWLClass(entityIri));
    }

    private SearchMetadata createSearchMetadata(SearchCategory category, Document doc, OWLEntity subject) {
        String subjectName = editorKit.getOWLModelManager().getRendering(subject);
        switch (category) {
            case IRI: return new SearchMetadata(category, "IRI", subject, subjectName, getContent(doc, IndexField.ENTITY_IRI));
            case DISPLAY_NAME: return new SearchMetadata(category, "DISPLAY NAME", subject, subjectName, getContent(doc, IndexField.DISPLAY_NAME));
            case ANNOTATION_VALUE: return new SearchMetadata(category, getContent(doc, IndexField.ANNOTATION_DISPLAY_NAME), subject, subjectName, getContent(doc, IndexField.ANNOTATION_TEXT));
            case LOGICAL_AXIOM: return new SearchMetadata(category, getContent(doc, IndexField.AXIOM_TYPE), subject, subjectName, getContent(doc, IndexField.AXIOM_DISPLAY_NAME));
            case OTHER: return new SearchMetadata(category, "OTHER", subject, subjectName, "(Found in multiple fields)");
            default: break;
        }
        return null;
    }

    private String getContent(Document doc, String fieldName) {
        return doc.get(fieldName);
    }

    private Optional<OWLEntity> getOWLEntity(String identifier) {
        OWLEntityFinder finder = editorKit.getOWLModelManager().getOWLEntityFinder();
        return finder.getEntities(IRI.create(identifier)).stream().findFirst();
    }

    private ImmutableList<SearchResultMatch> createEmptySearchResultMatch() {
        ImmutableList.Builder<SearchResultMatch> builder = new ImmutableList.Builder<>();
        builder.add(new SearchResultMatch("", 0, 0));
        return builder.build();
    }
}
