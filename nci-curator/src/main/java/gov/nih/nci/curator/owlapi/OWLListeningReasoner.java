// Copyright (c) 2006 - 2015, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.owlapi;

import java.util.Set;

import org.semanticweb.owlapi.model.OWLOntologyChangeListener;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * @author Evren Sirin
 */
public interface OWLListeningReasoner extends OWLReasoner, OWLOntologyChangeListener {
	void setListenChanges(boolean listen);
	
	Set<OWLSubClassOfAxiom> getAllInferredSuperClasses();

	boolean isListenChanges();
}