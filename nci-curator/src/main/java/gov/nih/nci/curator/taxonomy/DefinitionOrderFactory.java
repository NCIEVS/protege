// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.taxonomy;

import java.util.Comparator;

import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.CuratorOptions;
import gov.nih.nci.curator.CuratorOptions.OrderedClassification;
import gov.nih.nci.curator.owlapi.KnowledgeBase;
import gov.nih.nci.curator.utils.Comparators;


/**
 * Creates a definition order based on the configuration options defined in {@link CuratorOptions}.
 * 
 * @author Evren Sirin
 */
public class DefinitionOrderFactory {
	public static DefinitionOrder createDefinitionOrder(KnowledgeBase kb) {
		Comparator<OWLClass> comparator = CuratorOptions.ORDERED_CLASSIFICATION != OrderedClassification.DISABLED
				? Comparators.termComparator
			: null;
		
		return CuratorOptions.ORDERED_CLASSIFICATION == OrderedClassification.ENABLED_LEGACY_ORDERING
			? new TaxonomyBasedDefinitionOrder( kb, comparator )
			: new TaxonomyBasedDefinitionOrder( kb, comparator );
	}
}
