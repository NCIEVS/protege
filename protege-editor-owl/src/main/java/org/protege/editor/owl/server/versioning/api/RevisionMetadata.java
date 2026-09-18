package org.protege.editor.owl.server.versioning.api;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.annotation.Nonnull;

import org.protege.editor.owl.server.http.messages.History;

/**
 * @author Josef Hardi <johardi@stanford.edu> <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class RevisionMetadata implements Serializable {

    private static final long serialVersionUID = -1198003999159038367L;

    public static final String AUTHOR_USERNAME = "author.username";
    public static final String AUTHOR_NAME = "author.name";
    public static final String AUTHOR_EMAIL = "author.email";
    public static final String CHANGE_DATE = "change.date";
    public static final String CHANGE_COMMENT = "change.comment";
    public static final String EVS_RECORDS = "evs.records";

    private final DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    private final String authorName;
    private final String authorId;
    private final String authorEmail;

    private final Date changeDate;
    private final String comment;

    // EVS/audit descriptors captured from the editor's intent (op type, code, name, reference); ride
    // in the commit so the server records them in the same transaction and persists them in the log.
    private final List<History> evsRecords;

    public RevisionMetadata(@Nonnull String authorId, String authorName, String authorEmail, @Nonnull String comment) {
        this(authorId, authorName, authorEmail, new Date(), comment);
    }

    public RevisionMetadata(@Nonnull String authorId, String authorName, String authorEmail, Date changeDate, @Nonnull String comment) {
        this(authorId, authorName, authorEmail, changeDate, comment, Collections.emptyList());
    }

    public RevisionMetadata(@Nonnull String authorId, String authorName, String authorEmail, Date changeDate,
            @Nonnull String comment, List<History> evsRecords) {
        this.authorId = authorId.trim();
        this.authorName = authorName.trim();
        this.authorEmail = authorEmail.trim();
        this.changeDate = changeDate;
        this.comment = comment.trim();
        this.evsRecords = (evsRecords == null || evsRecords.isEmpty())
                ? Collections.emptyList()
                : new ArrayList<>(evsRecords);
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getAuthorId() {
        return authorId;
    }

    public String getAuthorEmail() {
        return authorEmail;
    }

    public Date getDate() {
        return changeDate;
    }

    public String getComment() {
        return comment;
    }

    /** EVS/audit descriptors for this revision; empty when the commit carried no editor intent. */
    public List<History> getEvsRecords() {
        return (evsRecords == null) ? Collections.emptyList() : evsRecords;
    }

    public String getLogMessage() {
        String authorFormat = String.format("%s", getAuthorId());
        if (authorName != null && !authorName.isEmpty()) {
            authorFormat = String.format("%s (%s)", getAuthorName(), getAuthorId());
            if (authorEmail != null && !authorEmail.isEmpty()) {
                authorFormat = String.format("%s (%s) <%s>", getAuthorName(), getAuthorId(), getAuthorEmail());
            }
        }
        else {
            if (authorEmail != null && !authorEmail.isEmpty()) {
                authorFormat = String.format("%s <%s>", getAuthorId(), getAuthorEmail());
            }
        }
        String template = "Author: %s\n"
                + "Date: %s\n"
                + "Comment: %s\n";
        return String.format(template, authorFormat, dateFormat.format(getDate()), getComment());
    }

    @Override
    public int hashCode() {
        return authorId.hashCode() + 42 * comment.hashCode() + changeDate.hashCode() / 42;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof RevisionMetadata)) {
            return false;
        }
        RevisionMetadata other = (RevisionMetadata) obj;
        return other.getAuthorId().equals(authorId) && other.getDate().equals(changeDate)
                && other.getComment().equals(comment);
    }

    @Override
    public String toString() {
        return getLogMessage();
    }
}
