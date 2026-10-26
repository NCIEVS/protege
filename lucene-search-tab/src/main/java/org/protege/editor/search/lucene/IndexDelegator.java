package org.protege.editor.search.lucene;

import org.protege.editor.core.Disposable;
import org.protege.editor.search.lucene.AbstractLuceneIndexer.IndexProgressListener;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import com.google.common.base.Stopwatch;

/**
 * Author: Josef Hardi <josef.hardi@stanford.edu><br>
 * Stanford University<br>
 * Bio-Medical Informatics Research Group<br>
 * Date: 29/01/2016
 */
public class IndexDelegator implements Disposable {

    private static final Logger logger = LoggerFactory.getLogger(IndexDelegator.class);

    private final Directory directory;

    private final IndexWriter indexWriter;

    private IndexSearcher indexSearcher;

    private DirectoryReader currentReader;
    
    private boolean indexDirty = false;

    // Prevent external instantiation
    private IndexDelegator(@Nonnull IndexWriter writer, @Nonnull Directory directory) {
        this.indexWriter = writer;
        this.directory = directory;
    }

    public static IndexDelegator getInstance(@Nonnull Directory directory, @Nonnull IndexWriterConfig writerConfig) throws IOException {
        IndexWriter writer = new IndexWriter(directory, writerConfig);
        return new IndexDelegator(writer, directory);
    }

    public IndexWriter getWriter() {
        return indexWriter;
    }

    public IndexSearcher getSearcher() throws IOException {
    	if ((indexSearcher == null) || indexDirty) {
    		currentReader = DirectoryReader.open(directory);
    		indexSearcher = new IndexSearcher(currentReader);
    	}
    	indexDirty = false;
    	return indexSearcher;
    }

    public boolean indexExists() {
        try {
            return DirectoryReader.indexExists(directory);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read index directory", e);
        }
    }
    
    private int progress = 0;
    private int totalDocsSize = 0;
    private int whenToReportProgress = 0;
    
    public void setTotalDocsSize(int s) { 
    	totalDocsSize = s;
    }
    
    public void buildIndex(Document document, IndexProgressListener listener) {
    		progress++;
    		whenToReportProgress++;
        
            try {
				indexWriter.addDocument(document);
				if (whenToReportProgress > 20000) {
					int percent = percentage(progress, totalDocsSize);
					listener.fireIndexingProgressed(percent);
					
					whenToReportProgress = 0;
				}
				
			} catch (IOException e) {
				e.printStackTrace();
			}       
        
       
    }

    public void appendIndex(AddChangeSet changeSet, 
    		boolean commitAtEnd) throws IOException {
        for (Document doc : changeSet) {
            indexWriter.addDocument(doc);
        }
        if (!commitAtEnd) {
        	commitIndex();
        }
    }

    public void removeIndex(RemoveChangeSet changeSet,
    		boolean commitAtEnd) throws IOException {
    	for (List<Term> terms : changeSet) {
    		BooleanQuery.Builder builder = new BooleanQuery.Builder();
    		for (Term term : terms) {
    			builder.add(new TermQuery(term), Occur.MUST);
    		}            
    		indexWriter.deleteDocuments(builder.build());
    		indexWriter.flush();
    	}
    	if (!commitAtEnd) {
    		commitIndex();
    	}
    }

    @Override
    public void dispose() throws IOException {
        if (isOpen(indexWriter)) {
        	indexWriter.commit();
            indexWriter.close();
            directory.close();
            if (currentReader != null) {
                currentReader.close();
            }
        }
    }

    /*
     * Private utility methods
     */

    private boolean isOpen(final IndexWriter indexWriter) {
        if (indexWriter == null) return false;
        return indexWriter.isOpen();
    }

    public void commitIndex() throws IOException {
        if (isOpen(indexWriter)) {
            indexWriter.commit();
            indexDirty = true;
        }
    }

    private static int percentage(int progress, int total) {
        return (progress * 100) / total;
    }
}
