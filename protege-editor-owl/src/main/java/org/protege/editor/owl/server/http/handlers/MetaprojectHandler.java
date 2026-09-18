package org.protege.editor.owl.server.http.handlers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import edu.stanford.protege.metaproject.impl.ServerStatus;
import org.protege.editor.owl.server.api.ServerLayer;
import org.protege.editor.owl.server.api.exception.AuthorizationException;
import org.protege.editor.owl.server.api.exception.ServerServiceException;
import org.protege.editor.owl.server.classify.OntologyClassifier;
import org.protege.editor.owl.server.http.HTTPServer;
import org.protege.editor.owl.server.http.ServerEndpoints;
import org.protege.editor.owl.server.http.ServerProperties;
import org.protege.editor.owl.server.http.exception.ServerException;
import org.protege.editor.owl.server.index.ProjectIndexBuilder;
import org.protege.editor.owl.server.security.LoginTimeoutException;
import org.protege.editor.owl.server.util.SnapShot;
import org.protege.editor.owl.server.versioning.api.ServerDocument;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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

	private static final String TRIPLESTORE = "triple_store_url";
	private boolean update_triple_store = false;
	private String triple_store_url = "http://localhost:8890/sparql/";

	// Present only when the search plugin is on the server classpath (ServiceLoader); null disables
	// server-side index building.
	private final ProjectIndexBuilder indexBuilder;

	// Present only when the curator plugin is on the server classpath (ServiceLoader); null disables
	// server-side classification.
	private final OntologyClassifier classifier;

	private final ServerProjections projections;

	private boolean requiredRestarting = false;

	public MetaprojectHandler(ServerLayer serverLayer) {
		this.serverLayer = serverLayer;
		Object uts = System.getProperty(HTTPServer.UPDATE_TRIPLE_STORE);
		if (uts != null) {
			update_triple_store = Boolean.parseBoolean((String) uts);
			triple_store_url = serverLayer.getConfiguration().getProperty(TRIPLESTORE);
		}
		this.indexBuilder = loadIndexBuilder();
		this.classifier = loadClassifier();
		this.projections = new ServerProjections(serverLayer, update_triple_store, triple_store_url, indexBuilder, classifier);
	}

	private static ProjectIndexBuilder loadIndexBuilder() {
		Iterator<ProjectIndexBuilder> it = ServiceLoader.load(ProjectIndexBuilder.class).iterator();
		if (it.hasNext()) {
			ProjectIndexBuilder builder = it.next();
			logger.info("Server-side search index builder: {}", builder.getClass().getName());
			return builder;
		}
		logger.info("No ProjectIndexBuilder on the classpath; server-side search indexing is disabled");
		return null;
	}

	private static OntologyClassifier loadClassifier() {
		Iterator<OntologyClassifier> it = ServiceLoader.load(OntologyClassifier.class).iterator();
		if (it.hasNext()) {
			OntologyClassifier c = it.next();
			logger.info("Server-side ontology classifier: {}", c.getClass().getName());
			return c;
		}
		logger.info("No OntologyClassifier on the classpath; server-side classification is disabled");
		return null;
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
		else if (requestPath.equals(ServerEndpoints.PROJECT_INDEX) && requestMethod.equals(Methods.GET)) {
			ProjectId projectId = f.getProjectId(getQueryParameter(exchange, "projectid"));
			retrieveProjectIndex(projectId, exchange.getOutputStream());
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT_INDEX) && requestMethod.equals(Methods.POST)) {
			ProjectId projectId = f.getProjectId(getQueryParameter(exchange, "projectid"));
			rebuildProjectIndex(projectId, exchange.getOutputStream());
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT_EXPORT) && requestMethod.equals(Methods.GET)) {
			ProjectId projectId = f.getProjectId(getQueryParameter(exchange, "projectid"));
			retrieveProjectExport(projectId, exchange.getOutputStream());
		}
		else if (requestPath.equals(ServerEndpoints.PROJECT_CLASSIFY) && requestMethod.equals(Methods.POST)) {
			ProjectId projectId = f.getProjectId(getQueryParameter(exchange, "projectid"));
			classifyProject(projectId, exchange.getOutputStream());
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
			projections.loadTripleStore(projectId, snapshot.getOntology(), false);
			projections.buildIndex(projectId, snapshot.getOntology(), 0);
		}
		catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to create project snapshot", e);
		}
	}

	// Classify the project at HEAD (snapshot + replay) via the curator and materialize the inferred
	// hierarchy into the project's separate /inferred graph. Responds with a short status string
	// ("classified", "rejected", "no-classifier", "triplestore-disabled", "error"). The asserted graph
	// is never touched; the inferred graph reflects this classify until the next one.
	private void classifyProject(ProjectId projectId, OutputStream os) throws ServerException {
		try {
			OWLOntology ontology = projections.materializeHead(projectId);
			int head = projections.headRevision(projectId);
			String status = projections.classify(projectId, ontology, head);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(status);
		}
		catch (Exception e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR,
					"Server failed to classify " + projectId, e);
		}
	}

	// Rebuild the search index at HEAD from snapshot + replay, so a project whose index is missing or
	// stale (e.g. a client "reindex") gets a complete index reflecting the current revision. Responds
	// with the revision the rebuilt index reflects so the client can seed and replay from there.
	private void rebuildProjectIndex(ProjectId projectId, OutputStream os) throws ServerException {		try {
			OWLOntology ontology = projections.materializeHead(projectId);
			int head = projections.headRevision(projectId);
			projections.buildIndex(projectId, ontology, head);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(String.valueOf(head));
		}
		catch (Exception e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR,
					"Server failed to rebuild the search index for " + projectId, e);
		}
	}

	// Serve the project's Lucene index as one zip plus the revision it reflects, so a client can seed
	// its local index and then replay only the changesets after that revision.
	private void retrieveProjectIndex(ProjectId projectId, OutputStream os) throws ServerException {
		try {
			File dir = projections.indexDirectory(projectId);
			if (!dir.isDirectory()) {
				throw new ServerException(StatusCodes.NOT_FOUND, "No search index for project " + projectId);
			}
			String revision = projections.readIndexRevision(projectId);
			byte[] zip = zipDirectory(dir);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(revision);
			oos.writeObject(zip);
		}
		catch (IOException e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to transmit the index", e);
		}
	}

	// A Lucene FSDirectory is a flat set of files; zip them (non-recursive) into a byte array.
	private static byte[] zipDirectory(File dir) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(bos)) {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File file : files) {
					if (file.isFile()) {
						zos.putNextEntry(new ZipEntry(file.getName()));
						Files.copy(file.toPath(), zos);
						zos.closeEntry();
					}
				}
			}
		}
		return bos.toByteArray();
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

	// Serve the project's ontology at HEAD (snapshot + replay) for a client "export from server": the
	// lazy client's in-memory ontology holds only fetched fragments, so the full ontology to write out
	// as an OWL file comes from the server.
	private void retrieveProjectExport(ProjectId projectId, OutputStream os) throws ServerException {
		try {
			OWLOntology ontology = projections.materializeHead(projectId);
			ObjectOutputStream oos = new ObjectOutputStream(os);
			oos.writeObject(new SnapShot(ontology));
		}
		catch (Exception e) {
			throw new ServerException(StatusCodes.INTERNAL_SERVER_ERROR, "Server failed to export the project", e);
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
