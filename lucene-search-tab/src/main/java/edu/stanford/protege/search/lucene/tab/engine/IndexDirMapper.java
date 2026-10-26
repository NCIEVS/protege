package edu.stanford.protege.search.lucene.tab.engine;

import org.semanticweb.owlapi.model.OWLOntology;

public interface IndexDirMapper {
	public String getIndexDirId(OWLOntology ont);

}
