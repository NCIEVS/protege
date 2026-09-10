package org.protege.editor.owl.server.http.handlers;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.protege.editor.owl.server.api.ChangeService;
import org.protege.editor.owl.server.api.CommitBundle;
import org.protege.editor.owl.server.api.ServerLayer;
import org.protege.editor.owl.server.api.exception.AuthorizationException;
import org.protege.editor.owl.server.api.exception.OutOfSyncException;
import org.protege.editor.owl.server.api.exception.ServerServiceException;
import org.protege.editor.owl.server.http.HTTPServer;
import org.protege.editor.owl.server.http.ServerEndpoints;
import org.protege.editor.owl.server.http.ServerProperties;
import org.protege.editor.owl.server.http.exception.ServerException;
import org.protege.editor.owl.server.security.LoginTimeoutException;
import org.protege.editor.owl.server.util.SnapShot;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.HistoryFile;
import org.semanticweb.owlapi.model.OWLOntologyChange;

import gov.nih.nci.owlvirtuoso.ChangesetRdf;
import gov.nih.nci.owlvirtuoso.SparqlStore;

import com.google.common.collect.Lists;

import edu.stanford.protege.metaproject.api.AuthToken;
import edu.stanford.protege.metaproject.api.Project;
import edu.stanford.protege.metaproject.api.ProjectId;
import edu.stanford.protege.metaproject.api.User;
import edu.stanford.protege.metaproject.api.exception.UnknownProjectIdException;
import edu.stanford.protege.metaproject.impl.ProjectIdImpl;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.StatusCodes;

public class HTTPChangeService extends BaseRoutingHandler {

	private static final Logger logger = LoggerFactory.getLogger(HTTPChangeService.class);

	private final ServerLayer serverLayer;
	private ChangeService changeService;

	private static final String DATE_FORMATTER = "yyyyMMdd-HHmmss";

	private static final String TRIPLESTORE = "triple_store_url";

	private boolean update_triple_store = false;
	private String triple_store_url = "http://localhost:8890/sparql/";
	
	

	static enum PauseAllowed {
		OK, NOT_WORKFLOW_MANAGER, NOT_PAUSING_USER, SERVER_PAUSED;

		String message() {
			switch (this) {
			case OK:
				return "";
			case NOT_WORKFLOW_MANAGER:
				return "you aren't a workflow manager";
			case NOT_PAUSING_USER:
				return "Server in maintenance mode. Only pauser can do these actions now";
			case SERVER_PAUSED:
				return "Server in maintenance mode. Please try again later";
			}
			return "impossible"; // impossible to reach
		}

		boolean ok() {
			return this.equals(OK);
		}

		static PauseAllowed create(ProjectId projectId, User user, String path) {
			if (ServerEndpoints.LATEST_CHANGES.equals(path)) {
				return OK;
			} else if (HTTPServer.server().isWorkFlowManager(user, projectId)
					&& HTTPServer.server().isPausingUser(user)) {
				return OK;
			} else {
				return SERVER_PAUSED;
			}
		}
	}

	public HTTPChangeService(ServerLayer serverLayer, ChangeService changeService) {
		this.serverLayer = serverLayer;
		this.changeService = changeService;
		Object uts = System.getProperty(HTTPServer.UPDATE_TRIPLE_STORE);
		if (uts != null) {
			update_triple_store = Boolean.parseBoolean((String) uts);
			triple_store_url = serverLayer.getConfiguration().getProperty(TRIPLESTORE);
		}
	}

	@Override
	public void handleRequest(HttpServerExchange exchange) {
		try {
			handlingRequest(exchange);
		} catch (IOException | ClassNotFoundException e) {
			internalServerErrorStatusCode(exchange, "Server failed to receive the sent data", e);
		} catch (LoginTimeoutException e) {
			loginTimeoutErrorStatusCode(exchange, e);
		} catch (ServerException e) {
			handleServerException(exchange, e);
		} catch (UnknownProjectIdException e) {
			internalServerErrorStatusCode(exchange, "Server failed to receive valie project id", e);

		} finally {
			exchange.endExchange(); // end the request
		}
	}

	private void handlingRequest(HttpServerExchange exchange) throws IOException, ClassNotFoundException,
			LoginTimeoutException, ServerException, UnknownProjectIdException {
		ObjectInputStream ois = new ObjectInputStream(exchange.getInputStream());

		String requestPath = exchange.getRequestPath();
		if (HTTPServer.server().isPaused()) {
			List<String> allowedPausedEndpoints = Lists.newArrayList(ServerEndpoints.COMMIT, ServerEndpoints.SQUASH,
					ServerEndpoints.LATEST_CHANGES);
			if (allowedPausedEndpoints.contains(requestPath)) {
				User user = this.getAuthToken(exchange).getUser();
				PauseAllowed ccs = PauseAllowed.create(projectId(exchange), user, requestPath);
				if (!ccs.ok()) {
					throw new ServerException(StatusCodes.SERVICE_UNAVAILABLE, ccs.message());
				}
			} else {
				throw new ServerException(StatusCodes.SERVICE_UNAVAILABLE, PauseAllowed.SERVER_PAUSED.message());
			}
		}

		if (requestPath.equals(ServerEndpoints.COMMIT) || requestPath.equals(ServerEndpoints.HEAD)
				|| requestPath.equals(ServerEndpoints.ALL_CHANGES) || requestPath.equals(ServerEndpoints.LATEST_CHANGES)
				|| requestPath.equals(ServerEndpoints.SQUASH)) {
			ProjectId projectId = projectId(exchange);

			String clientChecksum = exchange.getRequestHeaders().getFirst(ServerProperties.SNAPSHOT_CHECKSUM_HEADER);
			if (clientChecksum == null) {
				throw new ServerException(StatusCodes.BAD_REQUEST,
						"project " + projectId + " does not have a checksum");
			}
			Optional<String> serverChecksum = serverLayer.getSnapshotChecksum(projectId);
			if (serverChecksum.isPresent() && !clientChecksum.equals(serverChecksum.get())) {
				throw new ServerException(ServerProperties.HISTORY_SNAPSHOT_OUT_OF_DATE,
						"History snapshot out of date for " + projectId + ": " + clientChecksum + " != "
								+ serverChecksum.get());
			}
		}

		if (requestPath.equals(ServerEndpoints.COMMIT)) {
			CommitBundle bundle = (CommitBundle) ois.readObject();
			submitCommitBundle(getAuthToken(exchange), projectId(exchange), bundle, exchange.getOutputStream());
		} else if (requestPath.equals(ServerEndpoints.ALL_CHANGES)) {
			HistoryFile file = (HistoryFile) ois.readObject();
			retrieveAllChanges(file, exchange.getOutputStream());
		} else if (requestPath.equals(ServerEndpoints.LATEST_CHANGES)) {
			HistoryFile file = (HistoryFile) ois.readObject();
			DocumentRevision start = (DocumentRevision) ois.readObject();
			retrieveLatestChanges(file, start, exchange.getOutputStream());
		} else if (requestPath.equals(ServerEndpoints.HEAD)) {
			HistoryFile file = (HistoryFile) ois.readObject();
			retrieveHeadRevision(file, exchange.getOutputStream());
		} else if (requestPath.equals(ServerEndpoints.SQUASH)) {
			SnapShot snapshot = (SnapShot) ois.readObject();
			squashHistory(snapshot, projectId(exchange), exchange.getOutputStream());
		}
	}

	private ProjectId projectId(HttpServerExchange exchange) throws ServerException {
		String sProjectId = exchange.getRequestHeaders().getFirst(ServerProperties.PROJECTID_HEADER);
		if (sProjectId == null) {
			throw new ServerException(StatusCodes.BAD_REQUEST, "Missing ProjectId");
		}
		return new ProjectIdImpl(sProjectId);
	}

	/*
	 * Private methods that handlers each service provided by the server end-point
	 * above.
	 */

	private void submitCommitBundle(AuthToken authToken, ProjectId projectId, CommitBundle bundle, OutputStream os)
			throws ServerException, UnknownProjectIdException {
		try {
			ChangeHistory hist = serverLayer.commit(authToken, projectId, bundle);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(hist);
			if (this.update_triple_store) {
				Project p = serverLayer.getConfiguration().getProject(projectId);
				writeTripleStore(p, hist.getHeadRevision());
			}
		} catch (AuthorizationException e) {
			throw new ServerException(StatusCodes.UNAUTHORIZED, "Access denied", e);
		} catch (OutOfSyncException e) {
			throw new ServerException(StatusCodes.CONFLICT, "Commit failed, please update your local copy first", e);
		} catch (ServerServiceException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to receive the commit data", e);
		} catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the returned data",
					e);
		}
	}

	// Non-lossy, self-healing replacement for the old ConvertToRdf path. Rather than applying only
	// this bundle, sync Virtuoso from its last-applied-revision marker up to the new head: on a normal
	// commit that is just the new changes, but after a restart/outage it also replays any gap.
	// getChanges is exclusive of `from`, so from=marker yields exactly marker+1..head. Endpoint from
	// triple_store_url config; graph from project namespace/name. A triple-store failure is logged but
	// never fails the commit — the changeset log is authoritative and the next commit replays.
	private void writeTripleStore(Project p, DocumentRevision head) {
		String graph = p.namespace() + "/" + p.getName().get();
		SPARQLRepository repository = new SPARQLRepository(triple_store_url);
		repository.initialize();
		try {
			SparqlStore store = new SparqlStore(repository, graph);
			long marker = store.lastAppliedRevision();
			DocumentRevision from = (marker < 0)
					? DocumentRevision.START_REVISION
					: DocumentRevision.create((int) marker);
			// A marker left on the graph by a previous ontology/project can be ahead of this
			// history's head, which would make the replay range invalid (getChanges/crop throws
			// "toKey out of range"). Treat such a marker as stale and resync the whole current
			// history from the start.
			if (from.aheadOf(head)) {
				logger.warn("Triple store marker r{} is ahead of head r{} for graph {}; "
						+ "treating it as stale and resyncing from the start", marker,
						head.getRevisionNumber(), graph);
				from = DocumentRevision.START_REVISION;
			}
			HistoryFile file = HistoryFile.openExisting(serverLayer.getHistoryFilePath(p.getId()));
			ChangeHistory history = changeService.getChanges(file, from, head);
			List<OWLOntologyChange> changes = new ArrayList<>();
			for (List<OWLOntologyChange> revisionChanges : history.getRevisions().values()) {
				changes.addAll(revisionChanges);
			}
			store.apply(ChangesetRdf.transform(changes), head.getRevisionNumber());
		} catch (Exception e) {
			logger.error("Triple store update failed for " + p.getId()
					+ "; the changeset log is authoritative and the next commit will replay from the marker", e);
		} finally {
			repository.shutDown();
		}
	}

	private void retrieveAllChanges(HistoryFile file, OutputStream os) throws ServerException {
		try {
			DocumentRevision headRevision = changeService.getHeadRevision(file);
			ChangeHistory history = changeService.getChanges(file, DocumentRevision.START_REVISION, headRevision);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(history);
		} catch (ServerServiceException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to get all changes", e);
		} catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the returned data",
					e);
		}
	}

	private void retrieveLatestChanges(HistoryFile file, DocumentRevision start, OutputStream os)
			throws ServerException {
		try {
			DocumentRevision headRevision = changeService.getHeadRevision(file);
			ChangeHistory history = changeService.getChanges(file, start, headRevision);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(history);
		} catch (ServerServiceException | IllegalArgumentException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to get the latest changes", e);
		} catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the returned data",
					e);
		}
	}

	private void retrieveHeadRevision(HistoryFile file, OutputStream os) throws ServerException {
		try {
			DocumentRevision headRevision = changeService.getHeadRevision(file);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(headRevision);
		} catch (ServerServiceException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to get the head revision", e);
		} catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the returned data",
					e);
		}
	}

	private void squashHistory(SnapShot snapShot, ProjectId projectId, OutputStream os) throws IOException {
		HistoryFile historyFile = serverLayer.createHistoryFile(projectId);
		String historyName = historyFile.getName();

		DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_FORMATTER);
		LocalDateTime localDateTime = LocalDateTime.now();
		String formatDateTime = localDateTime.format(formatter);

		String archiveDir = serverLayer.getConfiguration().getProperty(ServerProperties.ARCHIVE_ROOT) + File.separator
				+ projectId.get() + File.separator + "squash-" + formatDateTime + File.separator;

		String dataDir = serverLayer.getConfiguration().getServerRoot() + File.separator + projectId.get()
				+ File.separator;

		String gencodeName = serverLayer.getConfiguration().getProperty(ServerProperties.CODEGEN_FILE);

		String snapshotName = historyName + "-snapshot";
		String checksumName = snapshotName + "-checksum";

		String fullHistoryPath = dataDir + historyName;
		String backupName = new StringBuilder(fullHistoryPath)
				.insert(fullHistoryPath.lastIndexOf(File.separator) + 1, "~").toString();

		Files.createDirectories(Paths.get(archiveDir));
		Files.move(Paths.get(dataDir + historyName), Paths.get(archiveDir + historyName));
		Files.move(Paths.get(dataDir + snapshotName), Paths.get(archiveDir + snapshotName));
		Files.move(Paths.get(dataDir + checksumName), Paths.get(archiveDir + checksumName));
		Files.copy(Paths.get(dataDir + gencodeName), Paths.get(archiveDir + gencodeName));
		try {
			Files.delete(Paths.get(backupName));
		} catch (NoSuchFileException e) {
			// no back up means no history, ok to proceed, just ignore this
		}
		Files.createFile(Paths.get(dataDir + historyName));

		serverLayer.saveProjectSnapshot(snapShot, projectId, os);

		changeService.clearHistoryCacheEntry(historyFile);
	}
}
