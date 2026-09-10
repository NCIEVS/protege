package org.protege.editor.owl.server.conflict;

import edu.stanford.protege.metaproject.api.AuthToken;
import edu.stanford.protege.metaproject.api.ProjectId;

import org.protege.editor.owl.server.api.ChangeService;
import org.protege.editor.owl.server.api.CommitBundle;
import org.protege.editor.owl.server.api.ServerFilterAdapter;
import org.protege.editor.owl.server.api.ServerLayer;
import org.protege.editor.owl.server.api.exception.AuthorizationException;
import org.protege.editor.owl.server.api.exception.OutOfSyncException;
import org.protege.editor.owl.server.api.exception.ServerServiceException;
import org.protege.editor.owl.server.versioning.Commit;
import org.protege.editor.owl.server.versioning.InvalidHistoryFileException;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.HistoryFile;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationSubject;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Represents the conflict detection layer that will check if user changes .
 *
 * @author Josef Hardi <johardi@stanford.edu> <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class ConflictDetectionFilter extends ServerFilterAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ConflictDetectionFilter.class);

    private final ChangeService changeService;

    public ConflictDetectionFilter(ServerLayer delegate, ChangeService changeService) {
        super(delegate);
        this.changeService = changeService;
    }

    @Override
    public synchronized ChangeHistory commit(AuthToken token, ProjectId projectId, CommitBundle commitBundle)
            throws AuthorizationException, OutOfSyncException, ServerServiceException {
        try {
            // TODO: head revision is checked here, but another thread may already be proceeding to do a commit
            String projectFilePath = getHistoryFilePath(projectId);
            HistoryFile historyFile = HistoryFile.openExisting(projectFilePath);
            DocumentRevision serverHeadRevision = changeService.getHeadRevision(historyFile);
            DocumentRevision commitBaseRevision = commitBundle.getBaseRevision();
            logger.info("Commit gate: base r{} vs server head r{}",
                    commitBaseRevision.getRevisionNumber(), serverHeadRevision.getRevisionNumber());
            if (isOutdated(commitBaseRevision, serverHeadRevision)) {
                // The commit was built against an older head. Accept it anyway if it does not
                // touch any entity that changed in the interval (commitBaseRevision, head] -- a
                // per-entity gate (Decision #2) that lets modelers editing disjoint areas commit
                // concurrently. Fall back to the global reject when either side of the check is
                // not axiom-scoped (see touchedEntities), so a real conflict is never missed.
                Optional<Set<IRI>> incoming = touchedEntities(incomingChanges(commitBundle));
                Optional<Set<IRI>> intervalChanges = touchedEntities(
                        changesBetween(historyFile, commitBaseRevision, serverHeadRevision));
                if (!incoming.isPresent() || !intervalChanges.isPresent()) {
                    logger.error("Out of sync (non-axiom change present; using global gate)");
                    throw new OutOfSyncException("The local copy is outdated. Please do update.");
                }
                Set<IRI> conflicts = new TreeSet<>(incoming.get());
                conflicts.retainAll(intervalChanges.get());
                if (!conflicts.isEmpty()) {
                    logger.error("Out of sync on {} entit(y/ies): {}", conflicts.size(), conflicts);
                    throw new OutOfSyncException(
                            "The local copy is outdated for: " + conflicts + ". Please do update.");
                }
                logger.info("Accepting commit based on r{} against head r{}: touched entities are "
                        + "disjoint from the {} change(s) since (per-entity gate)",
                        commitBaseRevision.getRevisionNumber(), serverHeadRevision.getRevisionNumber(),
                        intervalChanges.get().size());
            }
            return super.commit(token, projectId, commitBundle);
        }
        catch (InvalidHistoryFileException e) {
        	String message = "Unable to access history file in remote server";
            logger.error(printLog(token.getUser(), "Commit changes", message), e);
            throw new ServerServiceException(message, e);
        }
    }

    private boolean isOutdated(DocumentRevision clientHeadRevision, DocumentRevision serverHeadRevision) {
        return clientHeadRevision.compareTo(serverHeadRevision) < 0;
    }

    private List<OWLOntologyChange> incomingChanges(CommitBundle commitBundle) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        for (Commit commit : commitBundle.getCommits()) {
            changes.addAll(commit.getChanges());
        }
        return changes;
    }

    private List<OWLOntologyChange> changesBetween(HistoryFile historyFile, DocumentRevision from,
            DocumentRevision to) throws ServerServiceException {
        // getChanges is exclusive of the start revision, so this is the interval (from, to].
        ChangeHistory history = changeService.getChanges(historyFile, from, to);
        List<OWLOntologyChange> changes = new ArrayList<>();
        for (List<OWLOntologyChange> revisionChanges : history.getRevisions().values()) {
            changes.addAll(revisionChanges);
        }
        return changes;
    }

    /**
     * The IRIs of the entities each change is "about" (its subject). Returns empty when any change
     * is not an axiom change, signalling the caller to fall back to the coarse global gate rather
     * than risk missing a conflict.
     */
    private Optional<Set<IRI>> touchedEntities(List<OWLOntologyChange> changes) {
        Set<IRI> subjects = new HashSet<>();
        for (OWLOntologyChange change : changes) {
            if (!change.isAxiomChange()) {
                return Optional.empty();
            }
            addSubjects(change.getAxiom(), subjects);
        }
        return Optional.of(subjects);
    }

    private void addSubjects(OWLAxiom axiom, Set<IRI> out) {
        if (axiom instanceof OWLSubClassOfAxiom) {
            OWLClassExpression sub = ((OWLSubClassOfAxiom) axiom).getSubClass();
            if (!sub.isAnonymous()) {
                out.add(sub.asOWLClass().getIRI());
                return;
            }
        } else if (axiom instanceof OWLAnnotationAssertionAxiom) {
            OWLAnnotationSubject subject = ((OWLAnnotationAssertionAxiom) axiom).getSubject();
            if (subject instanceof IRI) {
                out.add((IRI) subject);
                return;
            }
        } else if (axiom instanceof OWLEquivalentClassesAxiom) {
            addNamedClassIris(((OWLEquivalentClassesAxiom) axiom).classExpressions(), out);
            return;
        } else if (axiom instanceof OWLDisjointClassesAxiom) {
            addNamedClassIris(((OWLDisjointClassesAxiom) axiom).classExpressions(), out);
            return;
        } else if (axiom instanceof OWLDeclarationAxiom) {
            out.add(((OWLDeclarationAxiom) axiom).getEntity().getIRI());
            return;
        }
        // Unknown or anonymous-subject axiom: fall back to the whole signature. This can only
        // over-report (a false conflict), never under-report, so correctness is preserved.
        axiom.signature().forEach(entity -> out.add(entity.getIRI()));
    }

    private void addNamedClassIris(Stream<OWLClassExpression> expressions, Set<IRI> out) {
        expressions.filter(expression -> !expression.isAnonymous())
                .forEach(expression -> out.add(expression.asOWLClass().getIRI()));
    }
}
