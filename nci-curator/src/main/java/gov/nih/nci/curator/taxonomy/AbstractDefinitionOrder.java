// Copyright (c) 2006 - 2010, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.taxonomy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.owlapi.KnowledgeBase;



/**
 * 
 * @author Evren Sirin
 */
public abstract class AbstractDefinitionOrder implements DefinitionOrder {
	protected KnowledgeBase kb;
	protected Comparator<OWLClass>	comparator;

	private Set<OWLClass> cyclicConcepts;
	protected List<OWLClass> definitionOrder;
	
	public AbstractDefinitionOrder(KnowledgeBase kb, Comparator<OWLClass>	comparator) {
		this.kb = kb;
		this.comparator = comparator;
		
		cyclicConcepts = new HashSet<OWLClass>();		
		definitionOrder = new ArrayList<OWLClass>( kb.getClasses().size() + 2 );				

		initialize();
		
		processDefinitions();
		
		cyclicConcepts = computeCycles();
		
		definitionOrder = computeDefinitionOrder();
	}

	protected abstract void initialize();
	
	protected abstract Set<OWLClass> computeCycles();
	
	protected abstract List<OWLClass> computeDefinitionOrder();
	
	protected void processDefinitions() {
		
	}
	
	protected abstract void addUses(OWLClass c, OWLClass usedByC);
	
	/**
	 * {@inheritDoc}
	 */
	public boolean isCyclic(OWLClass concept) {
		return cyclicConcepts.contains( concept );
	}

	/**
	 * {@inheritDoc}
	 */
	public Iterator<OWLClass> iterator() {
		return definitionOrder.iterator();
	}
}
