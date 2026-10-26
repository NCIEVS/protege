// Copyright (c) 2006 - 2010, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.taxonomy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import gov.nih.nci.curator.owlapi.KnowledgeBase;
import gov.nih.nci.curator.owlapi.OWL;



/**
 * 
 * @author Evren Sirin
 */
public class ObjectPropertyDomainOrder extends TaxonomyBasedDefinitionOrder {
	
	
	
	
	

	public ObjectPropertyDomainOrder(KnowledgeBase kb, Comparator<OWLClass> comparator) {
		super( kb, comparator );
		
	}
	
	@Override
	protected void initialize() {
		ignore_roots = new HashSet<OWLClass>();
		Set<OWLObjectProperty> obj_props = kb.getObjectProperties();
		definitionOrderTaxonomy = new Taxonomy<OWLClass>( null,
				OWL.Thing, OWL.Nothing );
		for (OWLObjectProperty op : obj_props) {
			
			OWLClass dom = kb.getDomain(op);
			OWLClass range = kb.getRange(op);
			if (dom == null || range == null) {

			} else {
				if (definitionOrderTaxonomy.contains(dom)) {

				} else {
					definitionOrderTaxonomy.addNode(dom, false);

				}

				if (this.definitionOrderTaxonomy.contains(range)) {

				} else {
					definitionOrderTaxonomy.addNode(range, false);

				}

				super.addUses(dom, range);
			}
		}
		
		for (OWLClass r : kb.roots()) {
			if (definitionOrderTaxonomy.contains(r)) {
				
			} else {
				ignore_roots.add(r);
				definitionOrderTaxonomy.addNode(r, false);
			}
		}
		
	
		
		
		
	}
	
	public List<OWLClass> getDefinitionOrder() {
		List<OWLClass> domains = new ArrayList<OWLClass>();
		for (OWLClass c : definitionOrder) {
			if (c.equals(OWL.Thing) || c.equals(OWL.Nothing)) {				

			} else {
				domains.add(c);
			}

		}
		return domains;
	}

	
}
