// Copyright (c) 2006 - 2010, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.taxonomy;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.exceptions.InternalReasonerException;
import gov.nih.nci.curator.owlapi.KnowledgeBase;
import gov.nih.nci.curator.owlapi.OWL;



/**
 * 
 * @author Evren Sirin
 */
public class TaxonomyBasedDefinitionOrder extends AbstractDefinitionOrder {
	protected Taxonomy<OWLClass> definitionOrderTaxonomy;
	
	protected Set<OWLClass> ignore_roots;
	
	public Set<OWLClass> getIgnoreRoots() { return ignore_roots ;}

	public TaxonomyBasedDefinitionOrder(KnowledgeBase kb, Comparator<OWLClass> comparator) {
		super( kb, comparator );
	}
	
	protected void initialize() {
		Collection<OWLClass> classes = kb.getClasses();
		definitionOrderTaxonomy = new Taxonomy<OWLClass>( classes,
				OWL.Thing, OWL.Nothing );
		
		for (OWLClass c : classes) {
			
			Set<OWLClass> uses = kb.getUses(c);
			for (OWLClass d : uses) {
				addUses(c, d);
			}
		}
	}

	@Override
	protected void addUses(OWLClass c, OWLClass d) {		
		if( definitionOrderTaxonomy.isEquivalent( c, d ).isTrue() )
			return;
	
		TaxonomyNode<OWLClass> cNode = definitionOrderTaxonomy.getNode( c );
		TaxonomyNode<OWLClass> dNode = definitionOrderTaxonomy.getNode( d );
		if( cNode == null )
			throw new InternalReasonerException( c + " is not in the definition order" );
		else if( cNode.equals( definitionOrderTaxonomy.getTop() ) )
			definitionOrderTaxonomy.merge( cNode, dNode );
		else {
			definitionOrderTaxonomy.addSuper( c, d );
			definitionOrderTaxonomy.removeCycles( cNode );
		}
	}

	protected Set<OWLClass> computeCycles() {
		Set<OWLClass> cyclicConcepts = new HashSet<OWLClass>();
		for( TaxonomyNode<OWLClass> node : definitionOrderTaxonomy.getNodes() ) {
			Set<OWLClass> names = node.getEquivalents();
			if( names.size() > 1 )
				cyclicConcepts.addAll( names );
		}
		
		return cyclicConcepts;
	}
	
	protected List<OWLClass> computeDefinitionOrder() {
		definitionOrderTaxonomy.assertValid();

		return definitionOrderTaxonomy.topologocialSort( true, comparator );
	}
}
