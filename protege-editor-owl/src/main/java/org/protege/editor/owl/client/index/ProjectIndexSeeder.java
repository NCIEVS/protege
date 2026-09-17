package org.protege.editor.owl.client.index;

import java.io.IOException;

/**
 * Client-side hook for seeding the local Lucene index from a server-built index. Declared here
 * (dependency inversion) so the open flow can seed the plugin's index without protege-editor-owl
 * depending on the lucene-search-tab plugin. Discovered via {@link java.util.ServiceLoader}; if none
 * is present the open flow skips seeding and the plugin builds its index as usual.
 *
 * <p>The {@code indexDirId} must be the same id the search manager uses for the project (in the NCI
 * setup, the project id), so the seeded directory is the one the manager opens.
 */
public interface ProjectIndexSeeder {

    /** Whether a local index already exists for {@code indexDirId} (so no seed/download is needed). */
    boolean hasLocalIndex(String indexDirId);

    /**
     * Seed the local index for {@code indexDirId} from {@code indexZip}, but only when no local index
     * exists yet (so an incrementally-maintained index is never overwritten).
     *
     * @return {@code true} if a fresh index was seeded, {@code false} if one already existed.
     */
    boolean seedIndex(String indexDirId, byte[] indexZip) throws IOException;

    /** Remove the local index for {@code indexDirId} (record + files), e.g. after a server squash reset. */
    void dropLocalIndex(String indexDirId);
}
