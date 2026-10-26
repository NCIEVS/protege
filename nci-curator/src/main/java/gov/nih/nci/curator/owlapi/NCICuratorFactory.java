// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.owlapi;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.IllegalConfigurationException;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * <p>
 * Title:
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2007
 * </p>
 * <p>
 * Company: Clark & Parsia, LLC. <http://www.clarkparsia.com>
 * </p>
 * 
 * @author Evren Sirin
 */
public class NCICuratorFactory implements OWLReasonerFactory {
	private static final NCICuratorFactory INSTANCE = new NCICuratorFactory();
	
	/**
	 * Returns a static factory instance that can be used to create reasoners.
	 * 
	 * @return a static factory instance 
	 */
	public static NCICuratorFactory getInstance() {
		return INSTANCE;
	}

	
	/**
	 * {@inheritDoc}
	 */
	public String getReasonerName() {
		return "Pellet";
	}

	public String toString() {
		return getReasonerName();
	}

	/**
	 * {@inheritDoc}
	 */
	public NCICurator createReasoner(OWLOntology ontology) {
		return new NCICurator( ontology, new NCICuratorConfig() );
	}

	/**
	 * {@inheritDoc}
	 */
	public NCICurator createReasoner(OWLOntology ontology, OWLReasonerConfiguration config)
			throws IllegalConfigurationException {
		return new NCICurator( ontology, config(config).buffering(true) );
	}

	/**
	 * {@inheritDoc}
	 */
	public NCICurator createNonBufferingReasoner(OWLOntology ontology) {
		return new NCICurator(ontology, new NCICuratorConfig().buffering(false));
	}

	/**
	 * {@inheritDoc}
	 */
	public NCICurator createNonBufferingReasoner(OWLOntology ontology, OWLReasonerConfiguration config) throws IllegalConfigurationException {
		return new NCICurator( ontology, config(config).buffering(false) );
	}

	private NCICuratorConfig config(OWLReasonerConfiguration config) {
		return (config instanceof NCICuratorConfig) ? (NCICuratorConfig) config : new NCICuratorConfig(config);
	}
}
