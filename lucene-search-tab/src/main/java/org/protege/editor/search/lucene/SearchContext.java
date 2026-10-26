package org.protege.editor.search.lucene;

import org.protege.editor.owl.OWLEditorKit;

import org.semanticweb.owlapi.model.OWLOntology;

import edu.stanford.protege.search.lucene.tab.engine.IndexDirMapper;

import java.util.Set;

/**
 * @author Josef Hardi <johardi@stanford.edu><br>
 * Stanford University<br>
 * Bio-Medical Informatics Research Group<br>
 * Date: 21/06/2016
 */
public class SearchContext {

    private OWLEditorKit editorKit;
    
    private IndexDirMapper dirMapper = null;
    
    public void setIndexDirMapper(IndexDirMapper mapper) {
    	dirMapper = mapper;
    }

    public SearchContext(OWLEditorKit editorKit) {
        this.editorKit = editorKit;
    }

    public OWLEditorKit getEditorKit() {
        return editorKit;
    }
    
    public String getIndexDirId() {
    	if (dirMapper != null) {
    		return dirMapper.getIndexDirId(getActiveOntology());
    	}
    	return new Integer(getActiveOntology().getOntologyID().getOntologyIRI().get().hashCode()).toString();
    }

    public OWLOntology getActiveOntology() {
        return editorKit.getOWLModelManager().getActiveOntology();
    }

    public Set<OWLOntology> getOntologies() {
        return editorKit.getOWLModelManager().getActiveOntologies();
    }

	public boolean isIndexable() {
		if (getActiveOntology() != null) {
			return !getActiveOntology().isEmpty() && !getActiveOntology().isAnonymous();
		}
		return false;
	}
}