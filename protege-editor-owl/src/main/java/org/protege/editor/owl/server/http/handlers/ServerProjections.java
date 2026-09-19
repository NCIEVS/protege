package org.protege.editor.owl.server.http.handlers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

	// Triples per SPARQL INSERT DATA statement during a full load. Bigger batches mean far fewer HTTP
	// round-trips (the dominant cost of a full-Thesaurus load), bounded only by Virtuoso's SPARQL
	// compiler memory (SP030) on very large statements. Tunable at runtime with
	// -Dnci.tripleStore.batchSize so it can be dialled in at scale without a rebuild.
	private static final int TRIPLESTORE_BATCH =
			Integer.getInteger("nci.tripleStore.batchSize", 10000);

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

	// Load an ontology's triples into the project's Virtuoso graph in Virtuoso-safe batches, reusing
	// the same ChangesetRdf path as commits so the initial triples match committed ones. When
	// reload=true the graph is cleared first and the revision marker reset to the base (0), so the
	// graph matches a freshly-written snapshot baseline (squash / recovery); at create (reload=false)
	// the marker is left unset so the first commit replays from the start.
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
			Set<Triple> pending = new HashSet<>();
			// The ontology declaration (<ont> a owl:Ontology) is the ontology header, not an axiom, so
			// add it explicitly: the lazy client discovers the ontology IRI from it.
			if (ont.getOntologyID().getOntologyIRI().isPresent()) {
				String ontologyIri = ont.getOntologyID().getOntologyIRI().get().toString();
				pending.add(new Triple("<" + ontologyIri + ">",
						"<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>",
						"<http://www.w3.org/2002/07/owl#Ontology>"));
			}
			long total = 0;
			// One reusable renderer for the whole load: rendering each axiom in its own fresh manager +
			// ontology (the old ChangesetRdf.axiomToTriples per call) dominated a full-Thesaurus load.
			ChangesetRdf.Renderer renderer = new ChangesetRdf.Renderer();
			for (OWLAxiom axiom : ont.getAxioms()) {
				pending.addAll(renderer.render(axiom));
				if (pending.size() >= TRIPLESTORE_BATCH) {
					total += flushTriples(store, pending, projectId);
					pending = new HashSet<>();
				}
			}
			total += flushTriples(store, pending, projectId);
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

	// Insert one batch as a single small SPARQL update; a failed batch is logged and skipped so one
	// bad batch does not abort the whole load.
	private long flushTriples(SparqlStore store, Set<Triple> triples, ProjectId projectId) {
		if (triples.isEmpty()) {
			return 0;
		}
		try {
			store.apply(new RdfChangeSet(triples, Collections.<Triple>emptySet()));
			return triples.size();
		}
		catch (Exception e) {
			logger.error("Triple store batch of " + triples.size() + " triples failed for " + projectId
					+ "; continuing", e);
			return 0;
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
