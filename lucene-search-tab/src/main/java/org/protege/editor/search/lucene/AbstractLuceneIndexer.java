package org.protege.editor.search.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriterConfig;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLObjectVisitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;

/**
 * Author: Josef Hardi <josef.hardi@stanford.edu><br>
 * Stanford University<br>
 * Bio-Medical Informatics Research Group<br>
 * Date: 04/11/2015
 */
public abstract class AbstractLuceneIndexer implements OWLObjectVisitor {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractLuceneIndexer.class);

    private final Analyzer DEFAULT_ANALYZER = new StandardAnalyzer();

    private final Analyzer textAnalyzer;

    public AbstractLuceneIndexer() {
        textAnalyzer = DEFAULT_ANALYZER;
    }

    public AbstractLuceneIndexer(Analyzer analyzer) {
        textAnalyzer = analyzer;
    }

    public IndexWriterConfig getIndexWriterConfig() {
        return new IndexWriterConfig(textAnalyzer);
    }

    public Analyzer getTextAnalyzer() {
        return textAnalyzer;
    }

    public abstract IndexItemsCollector getIndexItemsCollector(IndexDelegator delegator, IndexProgressListener listener);

    public void doIndex(IndexDelegator delegator, SearchContext context, IndexProgressListener listener) throws IOException {
        IndexItemsCollector collector = getIndexItemsCollector(delegator, listener);
        for (OWLOntology ontology : context.getOntologies()) {
            logger.info("... collecting items to index from {}", ontology.getOntologyID().getDefaultDocumentIRI().get());
            ontology.accept(collector);
        }
        delegator.commitIndex();
    }

    public void doAppend(IndexDelegator delegator, AddChangeSet changeSet, 
    		boolean commitAtEnd) throws IOException {
        delegator.appendIndex(changeSet, commitAtEnd);
    }

    public void doRemove(IndexDelegator delegator, RemoveChangeSet changeSet,
    		boolean commitAtEnd) throws IOException {
        delegator.removeIndex(changeSet, commitAtEnd);
    }

    public interface IndexProgressListener {
        
        void fireIndexingProgressed(long progress);
    }
}
