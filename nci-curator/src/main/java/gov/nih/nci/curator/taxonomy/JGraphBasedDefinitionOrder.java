// Copyright (c) 2006 - 2010, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.taxonomy;



import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.StrongConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;

import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.owlapi.KnowledgeBase;
import gov.nih.nci.curator.owlapi.OWL;
import gov.nih.nci.curator.utils.CollectionUtils;



/**
 * 
 * @author Evren Sirin
 */
public class JGraphBasedDefinitionOrder extends AbstractDefinitionOrder {
	private Map<OWLClass,Set<OWLClass>> equivalents;
	
	private DirectedGraph<OWLClass,DefaultEdge> graph;	

	public JGraphBasedDefinitionOrder(KnowledgeBase kb, Comparator<OWLClass> comparator) {		
		super( kb, comparator );
	}
	
	private Set<OWLClass> createSet() {
		return comparator != null
	    	? new TreeSet<OWLClass>( comparator )
	    	: CollectionUtils.<OWLClass>makeIdentitySet();
	}
	
	private Queue<OWLClass> createQueue() {
		return comparator != null
	    	? new PriorityQueue<OWLClass>( 10, comparator )
	    	: new LinkedList<OWLClass>();
	}
	
	private boolean addEquivalent(OWLClass key, OWLClass value) {
	    Set<OWLClass> values = equivalents.get( key );
	    if( values == null ) {
	        values = createSet();
	        equivalents.put( key, values );
	    }
	    
	    return values.add( value );
	}
	
	private Set<OWLClass> getAllEquivalents(OWLClass key) {
	    Set<OWLClass> values = equivalents.get( key );
	    
	    if( values != null ) {
	    	values.add( key ); 	
	    }
	    else {
	    	values = Collections.singleton( key );
	    }
	    
	    return values;
	}
	
	private Set<OWLClass> getEquivalents(OWLClass key) {
	    Set<OWLClass> values = equivalents.get( key );
	    return values != null 
	    	? values
	    	: Collections.<OWLClass>emptySet();
	}
	
	protected void initialize() {
		equivalents = CollectionUtils.makeIdentityMap();
		
		graph = new DefaultDirectedGraph<OWLClass, DefaultEdge>( DefaultEdge.class );
		
		graph.addVertex( OWL.Thing );
		Collection<OWLClass> cls = kb.getClasses();
		for( OWLClass c : cls ) {
			graph.addVertex( c );
		}
		for( OWLClass c : cls ) {
			
			for (OWLClass us : kb.getUses(c)) {
				addUses(c, us);
			}
		}
	}

	@Override
	protected void addUses(OWLClass c, OWLClass usedByC) {
		if( c.equals( OWL.Thing ) ) {
			addEquivalent( OWL.Thing, usedByC );
		}
		else if( !c.equals( usedByC ) ) {
			graph.addEdge( c, usedByC );
		}
	}

	protected Set<OWLClass> computeCycles() {
		Set<OWLClass> cyclicConcepts = CollectionUtils.makeIdentitySet();
		
		cyclicConcepts.addAll( getEquivalents( OWL.Thing ) );
				
		StrongConnectivityInspector<OWLClass, DefaultEdge> scInspector = 
			new StrongConnectivityInspector<OWLClass, DefaultEdge>( graph );
		List<Set<OWLClass>> sccList = scInspector.stronglyConnectedSets();
		for( Set<OWLClass> scc : sccList ) {
			if( scc.size() == 1 )
				continue;
			
			cyclicConcepts.addAll( scc );
			
			collapseCycle( scc );
		}
		
		return cyclicConcepts;
	}

	private void collapseCycle(Set<OWLClass> scc) {
		Iterator<OWLClass> i = scc.iterator();
		OWLClass rep = i.next();
		
		while( i.hasNext() ) {				
			OWLClass node = i.next();
				
			addEquivalent( rep, node );
			
			for( DefaultEdge edge : graph.incomingEdgesOf( node ) ) {
				OWLClass incoming = graph.getEdgeSource( edge );
				if( !incoming.equals( rep  ) )
					graph.addEdge( incoming, rep );
			}
			
			for( DefaultEdge edge : graph.outgoingEdgesOf( node ) ) {
				OWLClass outgoing = graph.getEdgeTarget( edge );
				if( !outgoing.equals( rep  ) )
					graph.addEdge( rep, outgoing );
			}
			
			graph.removeVertex( node );
		}
	}
	
	protected List<OWLClass> computeDefinitionOrder() {		
		List<OWLClass> definitionOrder = CollectionUtils.makeList();
		
		definitionOrder.add( OWL.Thing );
		definitionOrder.addAll( getEquivalents( OWL.Thing ) );
		
		graph.removeVertex( OWL.Thing );
		
		destructiveTopologocialSort( definitionOrder );
		
		definitionOrder.add( OWL.Nothing );
		
		return definitionOrder;
	}
	
	public void destructiveTopologocialSort(List<OWLClass> nodesSorted) {
		Queue<OWLClass> nodesPending = createQueue();		

		for( OWLClass node : graph.vertexSet() ) {
			if( graph.outDegreeOf( node ) == 0 )
				nodesPending.add( node );
		}

		while( !nodesPending.isEmpty() ) {
			OWLClass node = nodesPending.remove();
			
			assert graph.outDegreeOf( node ) == 0;
			
			nodesSorted.addAll( getAllEquivalents( node ) );				

			for( DefaultEdge edge : graph.incomingEdgesOf( node ) ) {
				OWLClass source = graph.getEdgeSource( edge );
				if( graph.outDegreeOf( source ) == 1 )
					nodesPending.add( source );
			}
			
			graph.removeVertex( node );
		}

		assert graph.vertexSet().isEmpty() : "Failed to sort elements: " + graph.vertexSet();
	}
}
