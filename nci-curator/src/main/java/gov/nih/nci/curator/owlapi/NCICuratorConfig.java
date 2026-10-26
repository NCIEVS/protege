// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.owlapi;

import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.FreshEntityPolicy;
import org.semanticweb.owlapi.reasoner.IndividualNodeSetPolicy;
import org.semanticweb.owlapi.reasoner.NullReasonerProgressMonitor;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.ReasonerProgressMonitor;

/**
 *
 * @author Evren Sirin
 */
public class NCICuratorConfig implements OWLReasonerConfiguration  {
	private ReasonerProgressMonitor progressMonitor = new NullReasonerProgressMonitor();
	private FreshEntityPolicy freshEntityPolicy = gov.nih.nci.curator.CuratorOptions.SILENT_UNDEFINED_ENTITY_HANDLING
	                                              ? FreshEntityPolicy.ALLOW
	                                              : FreshEntityPolicy.DISALLOW;
	private IndividualNodeSetPolicy individualNodeSetPolicy = IndividualNodeSetPolicy.BY_SAME_AS;
	private long timeOut = 0;
	private BufferingMode bufferingMode = BufferingMode.BUFFERING;
	private OWLOntologyManager manager = null;
	private boolean listenChanges = true;

	public NCICuratorConfig() {
	}

	public NCICuratorConfig(OWLReasonerConfiguration source) {
		this.progressMonitor = source.getProgressMonitor();
		this.freshEntityPolicy = source.getFreshEntityPolicy();
		this.individualNodeSetPolicy = source.getIndividualNodeSetPolicy();
		this.timeOut = source.getTimeOut();
	}

	@Override
	public ReasonerProgressMonitor getProgressMonitor() {
		return progressMonitor;
	}

	public NCICuratorConfig progressMonitor(final ReasonerProgressMonitor theProgressMonitor) {
		progressMonitor = theProgressMonitor;
		return this;
	}

	@Override
	public FreshEntityPolicy getFreshEntityPolicy() {
		return freshEntityPolicy;
	}

	public NCICuratorConfig freshEntityPolicy(final FreshEntityPolicy theFreshEntityPolicy) {
		freshEntityPolicy = theFreshEntityPolicy;
		return this;
	}

	@Override
	public IndividualNodeSetPolicy getIndividualNodeSetPolicy() {
		return individualNodeSetPolicy;
	}

	public NCICuratorConfig individualNodeSetPolicy(final IndividualNodeSetPolicy theIndividualNodeSetPolicy) {
		individualNodeSetPolicy = theIndividualNodeSetPolicy;
		return this;
	}

	@Override
	public long getTimeOut() {
		return timeOut;
	}

	public NCICuratorConfig timeout(final long theTimeOut) {
		timeOut = theTimeOut;
		return this;
	}

	public BufferingMode getBufferingMode() {
		return bufferingMode;
	}

	public NCICuratorConfig buffering(final BufferingMode theBufferingMode) {
		bufferingMode = theBufferingMode;
		return this;
	}

	public NCICuratorConfig buffering(final boolean isBuffering) {
		return buffering(isBuffering ? BufferingMode.BUFFERING : BufferingMode.NON_BUFFERING);
	}

	public OWLOntologyManager getManager() {
		return manager;
	}

	public NCICuratorConfig manager(OWLOntologyManager theManager) {
		manager = theManager;
		return this;
	}

	public boolean isListenChanges() {
		return listenChanges;
	}

	public NCICuratorConfig listenChanges(boolean isListenChanges) {
		listenChanges = isListenChanges;
		return this;
	}

	public NCICurator createReasoner(OWLOntology ont) {
		return new NCICurator(ont, this);
	}
}
