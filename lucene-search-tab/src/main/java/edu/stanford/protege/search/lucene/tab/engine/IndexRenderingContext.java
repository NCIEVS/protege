package edu.stanford.protege.search.lucene.tab.engine;

import java.util.Optional;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObject;

/**
 * The two things {@link SearchTabIndexer} needs from its environment: how to render an object to its
 * display name, and how to resolve an IRI to an entity. Splitting these out lets the same indexer run
 * against the editor kit (client) or headless against a loaded ontology (server), so the client's
 * incrementally-maintained index and the server-built base index are produced by identical code.
 */
public interface IndexRenderingContext {

    String getRendering(OWLObject object);

    Optional<OWLEntity> getEntity(IRI iri);
}
