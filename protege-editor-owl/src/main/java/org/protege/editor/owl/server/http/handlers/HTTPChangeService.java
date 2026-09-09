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

import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
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
import org.protege.editor.owl.server.versioning.Commit;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.HistoryFile;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiomVisitor;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import gov.nih.nci.owlvirtuoso.ChangesetRdf;
import gov.nih.nci.owlvirtuoso.RdfChangeSet;
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

	private final ServerLayer serverLayer;
	private ChangeService changeService;

	private static final String DATE_FORMATTER = "yyyyMMdd-HHmmss";

	private static final String TRIPLESTORE = "triple_store_url";

	private boolean update_triple_store = false;
	private String triple_store_url = "http://localhost:8890/sparql/";
	private String graphName = null;
	private String updateEndpoint = null;
	private String ncit = null;
	SPARQLRepository repo = null;

	private String prefix = null;

	private String subClassOf = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
	private String type = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
	private String owlclass = "http://www.w3.org/2002/07/owl#Class";
	
	
	private String buildAnonParentQuery(OWLSubClassOfAxiom ax, boolean add) {
		if (ax.getSuperClass() instanceof OWLObjectSomeValuesFrom) {
			SimpleValueFactory factory = SimpleValueFactory.getInstance();

			String source = ax.getSubClass().asOWLClass().getIRI().getShortForm();
			Resource sub = factory.createIRI(ax.getSubClass().asOWLClass().getIRI().getIRIString());
			org.eclipse.rdf4j.model.IRI pred = factory.createIRI(subClassOf);

			OWLObjectSomeValuesFrom osvf = (OWLObjectSomeValuesFrom) ax.getSuperClass();
			String objStr = osvf.getProperty().asOWLObjectProperty().getIRI().getShortForm();
			String fillerStr = osvf.getFiller().asOWLClass().getIRI().getShortForm();

			String base = prefix;
			if (add) {
				base += "insert { graph " + graphName + " { ";
				base += "ncit:" + source + " rdfs:subClassOf _:foo .";
				base += " _:foo rdf:type owl:Restriction ; ";
				base += " owl:onProperty ncit:" + objStr + " ; ";
				base += " owl:someValuesFrom ncit:" + fillerStr + " . ";

				return base += " } } WHERE {}";

			} else {
				base += "delete { graph " + graphName + " { ";
				base += "?o ?q ?r . ";
				base += " ncit:" + source + " rdfs:subClassOf ?o } } where { ";

				base += " ncit:" + source + " rdfs:subClassOf ?o . ";
				base += " ?o rdf:type owl:Restriction ; ";
				base += " owl:onProperty ncit:" + objStr + " ; ";
				base += " owl:someValuesFrom ncit:" + fillerStr + " . ";
				return base + " ?o ?q ?r }";

			}
		}
		return "";

	}

	private String buildCompQuery(OWLAnnotationAssertionAxiom ax, boolean add) {
		SimpleValueFactory factory = SimpleValueFactory.getInstance();

		String source = ax.getSubject().asIRI().get().getShortForm();
		String prop = ax.getProperty().asOWLAnnotationProperty().getIRI().getShortForm();
		OWLLiteral val = ax.getValue().asLiteral().get();
		String valstr = factory.createLiteral(val.getLiteral()).stringValue();

		String base = prefix;
		if (add) {
			base += "insert { graph " + graphName + " { ";
		} else {
			base += "delete { graph " + graphName + " { ";

		}

		// In both cases all main assertion

		base += "ncit:" + source + " ncit:" + prop + " " + "\"" + valstr + "\" . ";

		if (ax.getAnnotations().isEmpty()) {
			return base += " } } WHERE {}";
		} else {
			if (add) {
				base += "[] rdf:type owl:Axiom ; ";
				base += "owl:annotatedSource ncit:" + source + " ; ";
				base += "owl:annotatedProperty ncit:" + prop + " ; ";
				base += "owl:annotatedTarget " + "\"" + valstr + "\" ";

				for (OWLAnnotation an : ax.getAnnotations()) {
					base += " ; ";
					String anProp = an.getProperty().getIRI().getShortForm();
					OWLLiteral anVal = an.getValue().asLiteral().get();
					String anValStr = factory.createLiteral(anVal.getLiteral()).stringValue();
					base += "ncit:" + anProp + " \"" + anValStr + "\"";

				}

				return base + " } } WHERE {}";
			} else {
				base += "?s ?p ?o  } } WHERE { ";
				base += "?s owl:annotatedSource ncit:" + source + " ; ";
				base += "owl:annotatedProperty ncit:" + prop + " ; ";
				base += "owl:annotatedTarget " + "\"" + valstr + "\" ";

				for (OWLAnnotation an : ax.getAnnotations()) {
					base += " ; ";
					String anProp = an.getProperty().getIRI().getShortForm();
					OWLLiteral anVal = an.getValue().asLiteral().get();
					String anValStr = factory.createLiteral(anVal.getLiteral()).stringValue();
					base += "ncit:" + anProp + " \"" + anValStr + "\"";

				}
				base += " . ";

				return base + " ?s ?p ?o }";

			}

		}

	}

	private class ConvertToRdf implements OWLAxiomVisitor {

		private boolean add = true;

		public void updateRdf(OWLOntologyChange oc) {
			add = oc.isAddAxiom();
			oc.getAxiom().accept(this);

		}

		public void visit(OWLDeclarationAxiom axiom) {
			SimpleValueFactory factory = SimpleValueFactory.getInstance();

			Resource sub = factory.createIRI(axiom.getEntity().getIRI().getIRIString());
			org.eclipse.rdf4j.model.IRI pred = factory.createIRI(type);
			org.eclipse.rdf4j.model.IRI val = factory.createIRI(owlclass);
			
			Statement st = factory.createStatement(sub, pred, val, null);
			Resource context = factory.createIRI(updateEndpoint);
			RepositoryConnection conn = repo.getConnection();

			if (add) {
				conn.add(st, context);
			} else {
				conn.remove(st, context);
			}

			conn.close();

		}

		public void visit(OWLAnnotationAssertionAxiom ax) {

			String q = buildCompQuery(ax, add);

			Query query = repo.getConnection().prepareQuery(QueryLanguage.SPARQL, q);

			if (query instanceof GraphQuery) {
				GraphQueryResult res = ((GraphQuery) query).evaluate();
			}
		}

		public void visit(OWLSubClassOfAxiom ax) {
			if (ax.getSuperClass().isOWLClass()) {
				SimpleValueFactory factory = SimpleValueFactory.getInstance();
				
				Resource sub = factory.createIRI(ax.getSubClass().asOWLClass().getIRI().getIRIString());
				org.eclipse.rdf4j.model.IRI pred = factory.createIRI(subClassOf);
				Resource sup = factory.createIRI(ax.getSuperClass().asOWLClass().getIRI().getIRIString());

				Statement st = factory.createStatement(sub, pred, sup, null);
				Resource context = factory.createIRI(updateEndpoint);
				RepositoryConnection conn = repo.getConnection();

				if (add) {
					conn.add(st, context);
				} else {
					conn.remove(st, context);
				}

				conn.close();

			} else {
				String q = buildAnonParentQuery(ax, add);

				Query query = repo.getConnection().prepareQuery(QueryLanguage.SPARQL, q);

				if (query instanceof GraphQuery) {
					GraphQueryResult res = ((GraphQuery) query).evaluate();
				}
				
			}
		}
	}

	private ConvertToRdf changeProcessor = null;

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
				writeTripleStore(bundle, p, hist.getHeadRevision());
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

	// Non-lossy replacement for the old ConvertToRdf path: transform the whole bundle's changes to
	// RDF and apply them to Virtuoso as one revision-stamped SPARQL Update. Endpoint from the
	// triple_store_url config; graph derived from the project namespace/name.
	private void writeTripleStore(CommitBundle bundle, Project p, DocumentRevision head) {
		String graph = p.namespace() + "/" + p.getName().get();
		SPARQLRepository repository = new SPARQLRepository(triple_store_url);
		repository.initialize();
		try {
			List<OWLOntologyChange> changes = new ArrayList<>();
			for (Commit c : bundle.getCommits()) {
				changes.addAll(c.getChanges());
			}
			RdfChangeSet rdf = ChangesetRdf.transform(changes);
			new SparqlStore(repository, graph).apply(rdf, head.getRevisionNumber());
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
