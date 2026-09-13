package org.protege.editor.owl.client.index;

import java.io.IOException;

import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Client-side hook for seeding the local Lucene index from a server-built index. Declared here
 * (dependency inversion) so the open flow can seed the plugin's index without protege-editor-owl
 * depending on the lucene-search-tab plugin. Discovered via {@link java.util.ServiceLoader}; if none
 * is present the open flow skips seeding and the plugin builds its index as usual.
 */
public interface ProjectIndexSeeder {

    /**
     * Seed the local index for {@code ontology} from {@code indexZip}, but only when no local index
     * exists yet (so an incrementally-maintained index is never overwritten).
     *
     * @return {@code true} if a fresh index was seeded, {@code false} if one already existed.
     */
    boolean seedIndex(OWLOntology ontology, byte[] indexZip) throws IOException;
}
