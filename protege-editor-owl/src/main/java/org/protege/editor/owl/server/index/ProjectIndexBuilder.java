package org.protege.editor.owl.server.index;

import java.io.File;
import java.io.IOException;

import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Server-side hook for building a project's Lucene search index from its ontology. Declared here
 * (dependency inversion) so the server can reuse the client's indexer without protege-editor-owl
 * depending on the lucene-search-tab plugin. The implementation is discovered via
 * {@link java.util.ServiceLoader}; if none is on the classpath the server skips index building.
 */
public interface ProjectIndexBuilder {

    /** Build (or overwrite) the Lucene index for {@code ontology} into {@code indexDirectory}. */
    void buildIndex(OWLOntology ontology, File indexDirectory) throws IOException;
}
