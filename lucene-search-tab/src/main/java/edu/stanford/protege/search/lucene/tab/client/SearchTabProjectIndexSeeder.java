package edu.stanford.protege.search.lucene.tab.client;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.protege.editor.owl.client.index.ProjectIndexSeeder;
import org.protege.editor.search.lucene.LuceneIndexPreferences;

/**
 * Client-side {@link ProjectIndexSeeder} that unzips a server-built index into the plugin's index
 * directory for the given id (the same id the search manager uses for the project), so the manager
 * finds a populated index and skips its own build. Seeds only when no local index exists, so an
 * incrementally-maintained index is never overwritten. Discovered via ServiceLoader.
 */
public class SearchTabProjectIndexSeeder implements ProjectIndexSeeder {

    @Override
    public boolean hasLocalIndex(String indexDirId) {
        return LuceneIndexPreferences.containsIndexRecord(indexDirId);
    }

    @Override
    public boolean seedIndex(String indexDirId, byte[] indexZip) throws IOException {
        if (LuceneIndexPreferences.containsIndexRecord(indexDirId)) {
            return false; // keep the existing (incrementally-maintained) index
        }
        LuceneIndexPreferences.addIndexRecord(indexDirId);
        File dir = new File(LuceneIndexPreferences.getIndexDirectoryLocation(indexDirId));
        dir.mkdirs();
        unzip(indexZip, dir);
        return true;
    }

    @Override
    public void dropLocalIndex(String indexDirId) {
        LuceneIndexPreferences.removeIndexRecord(indexDirId);
    }

    // The server ships a flat set of Lucene files (no subdirectories).
    private static void unzip(byte[] zip, File dir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(dir, entry.getName());
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zis.read(buffer)) > 0) {
                        os.write(buffer, 0, read);
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
