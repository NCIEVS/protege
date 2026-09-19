package org.protege.editor.owl.server.http.handlers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import org.eclipse.rdf4j.repository.sparql.SPARQLRepository;
import org.protege.editor.owl.server.api.ServerLayer;
import org.protege.editor.owl.server.classify.ClassificationOutcome;
import org.protege.editor.owl.server.classify.OntologyClassifier;
import org.protege.editor.owl.server.index.ProjectIndexBuilder;
import org.protege.editor.owl.server.versioning.ChangeHistoryUtils;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stanford.protege.metaproject.api.Project;
import edu.stanford.protege.metaproject.api.ProjectId;
import gov.nih.nci.owlvirtuoso.ChangesetRdf;
import gov.nih.nci.owlvirtuoso.RdfChangeSet;
import gov.nih.nci.owlvirtuoso.SparqlStore;
import gov.nih.nci.owlvirtuoso.Triple;

/**
 * Server-side (re)derivation of a project's projections from the authoritative snapshot + change
 * log: the ontology at HEAD, the Virtuoso graph, and the Lucene index. Shared by project create and
 * reindex ({@code MetaprojectHandler}) and squash ({@code HTTPChangeService}) so the three callers
 * build the projections from one code path.
 */
class ServerProjections {

	private static final Logger logger = LoggerFactory.getLogger(ServerProjections.class);

	// Triples per bulk POST to Virtuoso's SPARQL Graph Store CRUD endpoint during a full load. That
	// endpoint runs the streaming TTLP bulk parser, not the SPARQL compiler, so it is not bound by the
	// SP030 statement-size limit that capped the old INSERT DATA path at 500 triples; ~135k triples/s
	// measured. The batch only bounds the per-POST body held in memory. Tunable at runtime with
	// -Dnci.tripleStore.batchSize so it can be dialled in at scale without a rebuild.
	private static final int TRIPLESTORE_BATCH =
			Integer.getInteger("nci.tripleStore.batchSize", 200000);

	private final ServerLayer serverLayer;
	private final boolean updateTripleStore;
	private final String tripleStoreUrl;
	private final ProjectIndexBuilder indexBuilder;
	// Present only when the curator plugin is on the server classpath (ServiceLoader); null disables
	// server-side classification.
	private final OntologyClassifier classifier;

	ServerProjections(ServerLayer serverLayer, boolean updateTripleStore, String tripleStoreUrl,
			ProjectIndexBuilder indexBuilder, OntologyClassifier classifier) {
		this.serverLayer = serverLayer;
		this.updateTripleStore = updateTripleStore;
		this.tripleStoreUrl = tripleStoreUrl;
		this.indexBuilder = indexBuilder;
		this.classifier = classifier;
	}

	// Reconstruct the project's ontology at HEAD = snapshot baseline + replay(change log), the same
	// snapshot-plus-replay the old client used to build its in-RAM model.
	OWLOntology materializeHead(ProjectId projectId) throws Exception {
		OWLOntology ontology = serverLayer.loadProjectSnapshot(projectId);
		ChangeHistory history = ChangeHistoryUtils.readChanges(serverLayer.createHistoryFile(projectId));
		List<OWLOntologyChange> changes = ChangeHistoryUtils.getOntologyChanges(history, ontology);
		ontology.getOWLOntologyManager().applyChanges(changes);
		return ontology;
	}

	int headRevision(ProjectId projectId) throws IOException {
		return ChangeHistoryUtils.readChanges(serverLayer.createHistoryFile(projectId))
				.getHeadRevision().getRevisionNumber();
	}

	// Classify the materialized head via the curator (ServiceLoader) and materialize the inferred
	// hierarchy into the project's separate <graph>/inferred named graph, tagged with the classified
	// revision. This is a derived projection: it never touches the asserted graph, and is rebuilt from
	// scratch each classify. Returns a short status string for the client. Triples go in Virtuoso-safe
	// batches via replaceGraph. A rejection (role domain/range issues) leaves the inferred graph as-is.
	String classify(ProjectId projectId, OWLOntology ont, int revision) {
		if (classifier == null) {
			return "no-classifier";
		}
		if (!updateTripleStore) {
			return "triplestore-disabled";
		}
		ClassificationOutcome outcome = classifier.classify(ont);
		if (!outcome.isClassified()) {
			return "rejected";
		}
		SPARQLRepository repository = null;
		try {
			Project project = serverLayer.getConfiguration().getProject(projectId);
			String graph = project.namespace() + "/" + project.getName().get() + "/inferred";
			repository = new SPARQLRepository(tripleStoreUrl);
			repository.initialize();
			SparqlStore store = new SparqlStore(repository, graph);
			store.replaceGraph(ChangesetRdf.insertsFor(outcome.getInferredAxioms()), revision);
			logger.info("Classified project {}: wrote {} inferred axioms to <{}> at revision {}",
					projectId, outcome.getInferredAxioms().size(), graph, revision);
			return "classified";
		}
		catch (Exception e) {
			logger.error("Failed to write inferred graph for " + projectId, e);
			return "error";
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

	// Load an ontology's triples into the project's Virtuoso graph via the SPARQL Graph Store CRUD
	// endpoint (streaming TTLP bulk parser), reusing the same ChangesetRdf rendering as commits so the
	// initial triples match committed ones. When reload=true the graph is cleared first and the
	// revision marker reset to the base (0), so the graph matches a freshly-written snapshot baseline
	// (squash / recovery); at create (reload=false) the marker is left unset so the first commit
	// replays from the start.
	void loadTripleStore(ProjectId projectId, OWLOntology ont, boolean reload) {
		if (!updateTripleStore) {
			return;
		}
		SPARQLRepository repository = null;
		try {
			Project project = serverLayer.getConfiguration().getProject(projectId);
			String graph = project.namespace() + "/" + project.getName().get();
			repository = new SPARQLRepository(tripleStoreUrl);
			repository.initialize();
			SparqlStore store = new SparqlStore(repository, graph);
			if (reload) {
				store.clearGraph();
			}
			String crudEndpoint = graphCrudEndpoint(tripleStoreUrl);
			// One reusable renderer for the whole load: rendering each axiom in its own fresh manager +
			// ontology (the old ChangesetRdf.axiomToTriples per call) dominated a full-Thesaurus load.
			ChangesetRdf.Renderer renderer = new ChangesetRdf.Renderer();
			StringBuilder batch = new StringBuilder();
			int buffered = 0;
			long total = 0;
			// The ontology declaration (<ont> a owl:Ontology) is the ontology header, not an axiom, so
			// add it explicitly: the lazy client discovers the ontology IRI from it.
			if (ont.getOntologyID().getOntologyIRI().isPresent()) {
				String ontologyIri = ont.getOntologyID().getOntologyIRI().get().toString();
				batch.append("<").append(ontologyIri).append("> ")
						.append("<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> ")
						.append("<http://www.w3.org/2002/07/owl#Ontology> .\n");
				buffered++;
			}
			for (OWLAxiom axiom : ont.getAxioms()) {
				for (Triple triple : renderer.render(axiom)) {
					batch.append(triple.toNTriple()).append('\n');
					buffered++;
				}
				if (buffered >= TRIPLESTORE_BATCH) {
					total += postTriples(crudEndpoint, graph, batch, buffered, projectId);
					batch.setLength(0);
					buffered = 0;
				}
			}
			total += postTriples(crudEndpoint, graph, batch, buffered, projectId);
			if (reload) {
				// Reset the marker to the new baseline (0) so the graph and the fresh (empty) history agree.
				store.apply(new RdfChangeSet(Collections.<Triple>emptySet(), Collections.<Triple>emptySet()), 0);
			}
			logger.info("Loaded {} triples into triple store graph <{}> for project {} (reload={})",
					total, graph, projectId, reload);
		}
		catch (Exception e) {
			logger.error("Failed to load project " + projectId + " into the triple store", e);
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

	// Derive Virtuoso's SPARQL Graph Store CRUD endpoint from the SPARQL query endpoint
	// (…/sparql[/] -> …/sparql-graph-crud/), the standard Virtuoso pairing.
	private static String graphCrudEndpoint(String sparqlUrl) {
		return sparqlUrl.replaceFirst("/sparql/?$", "") + "/sparql-graph-crud/";
	}

	// POST one batch of N-Triples to the CRUD endpoint (append into the graph). Virtuoso's bulk parser
	// is not bound by the SP030 statement-size limit, so a batch can be far larger than an INSERT DATA
	// statement. A failed batch is logged and skipped so one bad batch does not abort the whole load.
	private long postTriples(String crudEndpoint, String graph, CharSequence ntriples, int count,
			ProjectId projectId) {
		if (count == 0) {
			return 0;
		}
		HttpURLConnection conn = null;
		try {
			URL url = new URL(crudEndpoint + "?graph-uri=" + URLEncoder.encode(graph, "UTF-8"));
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type", "text/plain");
			byte[] body = ntriples.toString().getBytes(StandardCharsets.UTF_8);
			conn.setFixedLengthStreamingMode(body.length);
			try (OutputStream os = conn.getOutputStream()) {
				os.write(body);
			}
			int code = conn.getResponseCode();
			if (code >= 200 && code < 300) {
				return count;
			}
			logger.error("Triple store bulk POST of {} triples failed for {}: HTTP {}", count, projectId, code);
			return 0;
		}
		catch (Exception e) {
			logger.error("Triple store bulk POST of " + count + " triples failed for " + projectId
					+ "; continuing", e);
			return 0;
		}
		finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	// Drop the project's Virtuoso graphs (asserted + inferred) and their revision markers when the
	// project is deleted. Best-effort: a derived graph left behind is recoverable, so failure is logged
	// not fatal. Takes the Project captured before it was removed from the configuration.
	void dropProject(Project project) {
		if (!updateTripleStore) {
			return;
		}
		String graph = project.namespace() + "/" + project.getName().get();
		SPARQLRepository repository = null;
		try {
			repository = new SPARQLRepository(tripleStoreUrl);
			repository.initialize();
			new SparqlStore(repository, graph).dropGraph();
			new SparqlStore(repository, graph + "/inferred").dropGraph();
			logger.info("Dropped triple store graphs <{}> and <{}/inferred>", graph, graph);
		}
		catch (Exception e) {
			logger.error("Failed to drop triple store graph <" + graph + ">", e);
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

	// Build the Lucene index for the ontology (clearing the directory first) and record the revision
	// it reflects. Non-fatal on failure. Disabled when no ProjectIndexBuilder is on the classpath.
	void buildIndex(ProjectId projectId, OWLOntology ont, int revision) {
		if (indexBuilder == null) {
			return;
		}
		try {
			File dir = indexDirectory(projectId);
			clearDirectory(dir);
			indexBuilder.buildIndex(ont, dir);
			writeIndexRevision(projectId, revision);
			logger.info("Built search index for project {} at {} (revision {})", projectId, dir, revision);
		}
		catch (Exception e) {
			logger.error("Failed to build search index for project " + projectId, e);
		}
	}

	File indexDirectory(ProjectId projectId) {
		return new File(serverLayer.getHistoryFilePath(projectId) + "-index");
	}

	private File indexRevisionFile(ProjectId projectId) {
		return new File(serverLayer.getHistoryFilePath(projectId) + "-index.revision");
	}

	void writeIndexRevision(ProjectId projectId, int revision) throws IOException {
		try (OutputStream os = new FileOutputStream(indexRevisionFile(projectId))) {
			os.write(String.valueOf(revision).getBytes(StandardCharsets.UTF_8));
		}
	}

	String readIndexRevision(ProjectId projectId) {
		try {
			return new String(Files.readAllBytes(indexRevisionFile(projectId).toPath()), StandardCharsets.UTF_8).trim();
		}
		catch (IOException e) {
			return "0";
		}
	}

	// Delete the (flat) contents of a Lucene index directory so a rebuild starts from empty.
	private static void clearDirectory(File dir) {
		if (dir.isDirectory()) {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File file : files) {
					file.delete();
				}
			}
		}
	}
}
