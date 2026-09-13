package org.protege.editor.owl.server.http.handlers;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import edu.stanford.protege.metaproject.impl.ServerStatus;
import org.protege.editor.owl.server.api.ServerLayer;
import org.protege.editor.owl.server.api.exception.AuthorizationException;
import org.protege.editor.owl.server.api.exception.ServerServiceException;
import org.protege.editor.owl.server.http.HTTPServer;
import org.protege.editor.owl.server.http.ServerEndpoints;
import org.protege.editor.owl.server.http.ServerProperties;
import org.protege.editor.owl.server.http.exception.ServerException;
import org.protege.editor.owl.server.security.LoginTimeoutException;
import org.protege.editor.owl.server.util.SnapShot;
import org.protege.editor.owl.server.versioning.api.ServerDocument;
import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.nih.nci.owlvirtuoso.ChangesetRdf;
import gov.nih.nci.owlvirtuoso.SparqlStore;

import edu.stanford.protege.metaproject.ConfigurationManager;
import edu.stanford.protege.metaproject.api.AuthToken;
import edu.stanford.protege.metaproject.api.Description;
import edu.stanford.protege.metaproject.api.Name;
import edu.stanford.protege.metaproject.api.PolicyFactory;
import edu.stanford.protege.metaproject.api.Project;
import edu.stanford.protege.metaproject.api.ProjectId;
import edu.stanford.protege.metaproject.api.ProjectOptions;
import edu.stanford.protege.metaproject.api.Serializer;
import edu.stanford.protege.metaproject.api.ServerConfiguration;
import edu.stanford.protege.metaproject.api.UserId;
import edu.stanford.protege.metaproject.api.exception.ObjectConversionException;
import edu.stanford.protege.metaproject.serialization.DefaultJsonSerializer;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

public class MetaprojectHandler extends BaseRoutingHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(MetaprojectHandler.class);
	private static final PolicyFactory f = ConfigurationManager.getFactory();
	private final ServerLayer serverLayer;

	// Bulk-load the initial ontology into Virtuoso in batches of this many axioms per SPARQL Update.
	private static final String TRIPLESTORE = "triple_store_url";
	private static final int TRIPLESTORE_BATCH = 5000;
	private boolean update_triple_store = false;
	private String triple_store_url = "http://localhost:8890/sparql/";

	private boolean requiredRestarting = false;

	public MetaprojectHandler(ServerLayer serverLayer) {
		this.serverLayer = serverLayer;
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
		}
		catch (IOException | ClassNotFoundException | ObjectConversionException e) {
			internalServerErrorStatusCode(exchange, "Server failed to receive the sent data", e);
		}
		catch (LoginTimeoutException e) {
			loginTimeoutErrorStatusCode(exchange, e);
		}
		catch (ServerException e) {
			handleServerException(exchange, e);
		}
		finally {
			exchange.endExchange(); // end the request
		}
		
		// A special directive when the server needs to be restarted after completing the request
		if (requiredRestarting) {
			try {
				HTTPServer.server().restart();
			}
			catch (ServerException e) {
				handleServerException(exchange, e);
			}
			finally {
				requiredRestarting = false;
			}
		}
	}

	private void handlingRequest(HttpServerExchange exchange)
			throws IOException, ClassNotFoundException, ObjectConversionException,
			LoginTimeoutException, ServerException {
		String requestPath = exchange.getRequestPath();
		HttpString requestMethod = exchange.getRequestMethod();
		if (requestPath.equals(ServerEndpoints.PROJECTS)) {
			UserId userId = f.getUserId(getQueryParameter(exchange, "userid"));
			retrieveProjectList(userId, exchange.getOutputStream());
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT) && requestMethod.equals(Methods.POST)) {
			ObjectInputStream ois = new ObjectInputStream(exchange.getInputStream());
			ProjectId pid = (ProjectId) ois.readObject();
			String nspc = (String) ois.readObject();
			Name pname = (Name) ois.readObject();
			Description desc = (Description) ois.readObject();
			UserId uid = (UserId) ois.readObject();
			Set<ProjectId> imps = (Set<ProjectId>) ois.readObject();
			Optional<ProjectOptions> oopts = Optional.ofNullable((ProjectOptions) ois.readObject());
			createNewProject(getAuthToken(exchange), pid, nspc, pname, desc, uid, oopts, exchange.getOutputStream());
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT_UPDATE) && requestMethod.equals(Methods.POST)) {
			ObjectInputStream ois = new ObjectInputStream(exchange.getInputStream());
			Project proj = (Project) ois.readObject();
			
			updateProject(getAuthToken(exchange), proj);
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT) && requestMethod.equals(Methods.GET)) {
			ProjectId projectId = f.getProjectId(getQueryParameter(exchange, "projectid"));
			openExistingProject(getAuthToken(exchange), projectId, exchange.getOutputStream());
			Optional<String> checksum = serverLayer.getSnapshotChecksum(projectId);
			if (checksum.isPresent()) {
				exchange.getResponseHeaders().put(new HttpString(ServerProperties.SNAPSHOT_CHECKSUM_HEADER),
						checksum.get());
			}
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT) && requestMethod.equals(Methods.DELETE)) {
			ProjectId projectId = f.getProjectId(getQueryParameter(exchange, "projectid"));
			boolean incFiles = Boolean.parseBoolean(getQueryParameter(exchange, "includefiles"));
			deleteExistingProject(getAuthToken(exchange), projectId, incFiles);
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT_SNAPSHOT) && requestMethod.equals(Methods.POST)) {
			ObjectInputStream ois = new ObjectInputStream(exchange.getInputStream());
			ProjectId pid = (ProjectId) ois.readObject();
			SnapShot snapshot = (SnapShot) ois.readObject();
			createProjectSnapshot(pid, snapshot, exchange.getOutputStream());
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT_SNAPSHOT) && requestMethod.equals(Methods.GET)) {
			ProjectId projectId = f.getProjectId(getQueryParameter(exchange, "projectid"));
			retrieveProjectSnapshot(projectId, exchange.getOutputStream());
		}
		else if (requestPath.equals(ServerEndpoints.METAPROJECT) && requestMethod.equals(Methods.GET)) {
			retrieveMetaproject(exchange);
		}
		else if (requestPath.equals(ServerEndpoints.METAPROJECT) && requestMethod.equals(Methods.POST)) {
			Serializer serl = new DefaultJsonSerializer();
			ServerConfiguration cfg = serl.parse(new InputStreamReader(exchange.getInputStream()), ServerConfiguration.class);
			updateMetaproject(cfg);
			requiredRestarting = true;
		} else if (requestPath.equals(ServerEndpoints.PROJECTS_UNCLASSIFIED) && requestMethod.equals(Methods.GET)) {
        retrieveProjectsUnclassified(exchange);
    } else if (requestPath.equals(ServerEndpoints.SERVER_STATUS) && requestMethod.equals(Methods.GET)) {
			retrieveServerStatus(exchange.getOutputStream());
		}
	}

	public void retrieveProjectsUnclassified(HttpServerExchange exchange) throws ServerException {
		try {
			ObjectOutputStream oos = new ObjectOutputStream(exchange.getOutputStream());
			List<Project> projects = new ArrayList<>();
			try {
				for (Project project : serverLayer.getAllProjects(getAuthToken(exchange))) {
					com.google.common.base.Optional<edu.stanford.protege.metaproject.api.ProjectOptions> options = project.getOptions();
					if (options.isPresent() &&
							"true".equals(options.get().getValue("classifiable"))) {
						projects.add(project);
					}
				}
				oos.writeObject(projects);
			} catch (AuthorizationException e) {
				throw new ServerException(StatusCodes.UNAUTHORIZED, e);
			} catch (ServerServiceException e) {
				throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, e);
			} catch (LoginTimeoutException e) {
				throw new RuntimeException(e);
			}
		} catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit returned data", e);
		}
	}

	/*
	 * Private methods that handlers each service provided by the server end-point above.
	 */

	private void retrieveProjectList(UserId userId, OutputStream os) throws ServerException {
		try {
			List<Project> projects = new ArrayList<>(serverLayer.getConfiguration().getProjects(userId));
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(projects);
		}
		catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the returned data", e);
		}
	}

	private void createNewProject(AuthToken authToken, ProjectId pid, String nspc, Name pname,
			Description desc, UserId uid, Optional<ProjectOptions> oopts, OutputStream os) throws ServerException {
		try {
			ServerDocument doc = serverLayer.createProject(authToken, pid, nspc, pname, desc, uid, oopts);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(doc);
		}
		catch (AuthorizationException e) {
			throw new ServerException(StatusCodes.UNAUTHORIZED, "Access denied", e);
		}
		catch (ServerServiceException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to create the project", e);
		}
		catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the returned data", e);
		}
	}
	
	private void updateProject(AuthToken authToken, Project proj) throws ServerException {
		try {
			serverLayer.updateProject(authToken, proj.getId(), proj);
			updateMetaproject(serverLayer.getConfiguration());
		}
		catch (AuthorizationException e) {
			throw new ServerException(StatusCodes.UNAUTHORIZED, "Access denied", e);
		}
		catch (ServerServiceException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to create the project", e);
		}
	}

	private void openExistingProject(AuthToken authToken, ProjectId projectId, OutputStream os) throws ServerException {
		try {
			ServerDocument sdoc = serverLayer.openProject(authToken, projectId);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(sdoc);
		}
		catch (AuthorizationException e) {
			throw new ServerException(StatusCodes.UNAUTHORIZED, "Access denied", e);
		}
		catch (ServerServiceException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to open the project", e);
		}
		catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the returned data", e);
		}
	}

	private void deleteExistingProject(AuthToken authToken, ProjectId projectId, boolean incFiles) throws ServerException {
		try {
			serverLayer.deleteProject(authToken, projectId, incFiles);
		}
		catch (AuthorizationException e) {
			throw new ServerException(StatusCodes.UNAUTHORIZED, "Access denied", e);
		}
		catch (ServerServiceException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to delete the project", e);
		}
	}

	private void createProjectSnapshot(ProjectId projectId, SnapShot snapshot, OutputStream os) throws ServerException {
		try {
			serverLayer.saveProjectSnapshot(snapshot, projectId, os);
			loadIntoTripleStore(projectId, snapshot.getOntology());
		}
		catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to create project snapshot", e);
		}
	}

	// Load a project's ontology into its Virtuoso named graph in batches, reusing the same
	// ChangesetRdf -> SparqlStore path as commits so the initial triples match committed ones. The
	// graph is derived like the commit write path (project namespace + "/" + name). At creation the
	// history is empty (head 0), so the marker is left unset and the first commit replays from START.
	private void loadIntoTripleStore(ProjectId projectId, OWLOntology ont) {
		if (!update_triple_store) {
			return;
		}
		SPARQLRepository repository = null;
		try {
			Project project = serverLayer.getConfiguration().getProject(projectId);
			String graph = project.namespace() + "/" + project.getName().get();
			repository = new SPARQLRepository(triple_store_url);
			repository.initialize();
			SparqlStore store = new SparqlStore(repository, graph);
			List<OWLOntologyChange> batch = new ArrayList<>(TRIPLESTORE_BATCH);
			long total = 0;
			for (OWLAxiom axiom : ont.getAxioms()) {
				batch.add(new AddAxiom(ont, axiom));
				if (batch.size() >= TRIPLESTORE_BATCH) {
					store.apply(ChangesetRdf.transform(batch));
					total += batch.size();
					batch.clear();
				}
			}
			if (!batch.isEmpty()) {
				store.apply(ChangesetRdf.transform(batch));
				total += batch.size();
			}
			logger.info("Loaded {} axioms into triple store graph <{}> for project {}", total, graph, projectId);
		}
		catch (Exception e) {
			logger.error("Failed to load project " + projectId + " into the triple store; "
					+ "the snapshot is saved and the load can be retried via update snapshot", e);
		}
		finally {
			if (repository != null) {
				try {
					repository.shutDown();
				}
				catch (Exception e) {
					logger.warn("Error shutting down triple store connection", e);
				}
			}
		}
	}

	private void retrieveProjectSnapshot(ProjectId projectId, OutputStream os) throws ServerException {
		try {
			OWLOntology ontIn = serverLayer.loadProjectSnapshot(projectId);
			try {
				ObjectOutputStream oos = new ObjectOutputStream(os);
				oos.writeObject(new SnapShot(ontIn));
				oos.writeObject(serverLayer.getSnapshotChecksum(projectId).get());
			}
			catch (IOException e) {
				throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the returned data", e);
			}
		}
		catch (OWLOntologyCreationException | IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to fetch project snapshot", e);
		}
	}

	private void retrieveMetaproject(HttpServerExchange exchange) throws ServerException {
		try {
			Serializer serl = new DefaultJsonSerializer();
			exchange.getResponseSender().send(serl.write(serverLayer.getConfiguration(), ServerConfiguration.class));
		}
		catch (Exception e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to get server configuration", e);
		}
	}

	private void updateMetaproject(ServerConfiguration cfg) throws ServerException {
		try {
			String configLocation = System.getProperty(HTTPServer.SERVER_CONFIGURATION_PROPERTY);
			ConfigurationManager.getConfigurationWriter().saveConfiguration(cfg, new File(configLocation));
		}
		catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to save changes of the metaproject", e);
		}
	}

	private void retrieveServerStatus(OutputStream os) throws ServerException {
		try {
			ObjectOutputStream oos = new ObjectOutputStream(os);
			ServerStatus serverStatus = new ServerStatus(HTTPServer.server().pausedUser());
			oos.writeObject(serverStatus);
		}
		catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to construct ServerStatus");
		}
	}
}
