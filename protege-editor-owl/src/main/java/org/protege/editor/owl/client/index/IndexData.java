package org.protege.editor.owl.client.index;

/**
 * A server-built Lucene index download: the zipped index files plus the revision the index reflects,
 * so a client can seed its local index and then replay only the changesets after that revision.
 */
public class IndexData {

    private final int revision;
    private final byte[] zip;

    public IndexData(int revision, byte[] zip) {
        this.revision = revision;
        this.zip = zip;
    }

    public int getRevision() {
        return revision;
    }

    public byte[] getZip() {
        return zip;
    }
}
