// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


import javax.annotation.Nonnull;

import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.ClassExpressionNotInProfileException;
import org.semanticweb.owlapi.reasoner.FreshEntitiesException;
import org.semanticweb.owlapi.reasoner.FreshEntityPolicy;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.IndividualNodeSetPolicy;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.semanticweb.owlapi.reasoner.TimeOutException;
import org.semanticweb.owlapi.reasoner.impl.NodeFactory;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLNamedIndividualNodeSet;
import org.semanticweb.owlapi.util.Version;
import org.slf4j.LoggerFactory;

import gov.nih.nci.curator.owlapi.NCICurator;
import gov.nih.nci.curator.owlapi.OWL;
import gov.nih.nci.curator.taxonomy.Taxonomy;
import gov.nih.nci.curator.taxonomy.printer.TreeTaxonomyPrinter;
import gov.nih.nci.curator.utils.Timer;
import gov.nih.nci.curator.utils.Timers;




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
public class IncrementalReasoner implements OWLReasoner {
	public static final org.slf4j.Logger log = LoggerFactory.getLogger(IncrementalReasoner.class);
	
	public static long TIME_IN_INCR_CLASSIFIER = 0;

	public static IncrementalReasonerConfiguration config() {
		return new IncrementalReasonerConfiguration();
	}

	
	private final NCICurator reasoner;

	private Taxonomy<OWLClass> taxonomy = null;
	
	private Timers timers = new Timers();
	
	public IncrementalReasoner(OWLOntology ontology, IncrementalReasonerConfiguration config) {
		
		reasoner = config.createReasoner(ontology);
		
	}
	
	public OWLOntologyManager getManager() {
		return reasoner.getManager();
	}

	public void classify() {
		
		if( reasoner.isClassified() ) {
			
			return;
		}
		
		regularClassify();		
	}

	public void dispose() {
		reasoner.dispose();
	}

	public Node<OWLClass> getEquivalentClasses(OWLClassExpression clsC) {
		if (taxonomy != null) {
			if( clsC.isAnonymous() ) {
				throw new IllegalArgumentException( "This reasoner only supports named classes" );
			}

			return NodeFactory.getOWLClassNode( taxonomy.getAllEquivalents( (OWLClass) clsC ) );
		}

		return NodeFactory.getOWLClassNode( new HashSet<OWLClass>() );

	}

	
	public NCICurator getReasoner() {
		return reasoner;
	}

	public NodeSet<OWLClass> getSubClasses(OWLClassExpression clsC, boolean direct) {
		if( clsC.isAnonymous() ) {
	        throw new UnsupportedOperationException( "This reasoner only supports named classes" );
        }

		Set<Node<OWLClass>> values = new HashSet<Node<OWLClass>>();
		if ( taxonomy != null ) {
			for( Set<OWLClass> val : taxonomy.getSubs( (OWLClass) clsC, direct ) ) {
				values.add( NodeFactory.getOWLClassNode( val ) );
			}
		}

		return new OWLClassNodeSet( values );
	}

	public boolean isClassified() {
		return reasoner.isClassified();
	}

	

	public boolean isSatisfiable(OWLClassExpression description) {		
        
		return !getUnsatisfiableClasses().contains( (OWLClass) description );
	}

	
	
	private Thread classy = null;

	private void regularClassify() {
		if( log.isTraceEnabled()) {
	        log.trace( "Regular classification starting" );
        }

		classy = new Thread( "classification" ) {
			@Override
            public void run() {
				Timer timer = timers.startTimer( "reasonerClassify" );
				reasoner.flush();
				reasoner.getKB().classify();
				timer.stop();
				log.info("Classification took " + timer.getTotal() + " ms.");

				if( log.isTraceEnabled()) {
					log.trace( "Regular taxonomy:" );

					new TreeTaxonomyPrinter<OWLClass>().print( reasoner.getKB().getTaxonomyBuilder().getTaxonomy(), new PrintWriter( System.err ) );
				}

				taxonomy = reasoner.getKB().getTaxonomyBuilder().getTaxonomy();
				

				if( log.isTraceEnabled()) {
					log.trace( "Copied taxonomy:" );

					new TreeTaxonomyPrinter<OWLClass>().print( taxonomy, new PrintWriter( System.err ) );
				}
			}
		};

		
		try {
			
			classy.run();	
			classy.join();
			
		} catch( InterruptedException e ) {
			throw new RuntimeException( e );
		}

		if( log.isTraceEnabled()) {
	        log.trace( "Regular classification done" );
        }
	}

	

	/**
	 * {@inheritDoc}
	 */
	public void flush() {
		reasoner.flush();
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLClass> getBottomClassNode() {
		return getEquivalentClasses( OWL.Nothing );
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLDataProperty> getBottomDataPropertyNode() {
		return getEquivalentDataProperties( OWL.bottomDataProperty );
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLObjectPropertyExpression> getBottomObjectPropertyNode() {
		return getEquivalentObjectProperties( OWL.bottomObjectProperty );
	}

	/**
	 * {@inheritDoc}
	 */
	public BufferingMode getBufferingMode() {
		return BufferingMode.BUFFERING;
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLClass> getDataPropertyDomains(OWLDataProperty pe, boolean direct)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		
		reasoner.flush();

		return reasoner.getDataPropertyDomains( pe, direct );
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<OWLLiteral> getDataPropertyValues(OWLNamedIndividual ind, OWLDataProperty pe)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		
		reasoner.flush();

		return reasoner.getDataPropertyValues( ind, pe );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLNamedIndividual> getDifferentIndividuals(OWLNamedIndividual ind)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		
		reasoner.flush();

		return reasoner.getDifferentIndividuals( ind );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLClass> getDisjointClasses(OWLClassExpression ce) {
		

		OWLClassNodeSet result = new OWLClassNodeSet();

		

		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLDataProperty> getDisjointDataProperties(OWLDataPropertyExpression pe)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getDisjointDataProperties( pe );

	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLObjectPropertyExpression> getDisjointObjectProperties(OWLObjectPropertyExpression pe)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getDisjointObjectProperties( pe );
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLDataProperty> getEquivalentDataProperties(OWLDataProperty pe)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getEquivalentDataProperties( pe );
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLObjectPropertyExpression> getEquivalentObjectProperties(OWLObjectPropertyExpression pe)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getEquivalentObjectProperties( pe );
	}

	/**
	 * {@inheritDoc}
	 */
	public FreshEntityPolicy getFreshEntityPolicy() {
		return reasoner.getFreshEntityPolicy();
	}

	/**
	 * {@inheritDoc}
	 */
	public IndividualNodeSetPolicy getIndividualNodeSetPolicy() {
		return reasoner.getIndividualNodeSetPolicy();
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLNamedIndividual> getInstances(@Nonnull OWLClassExpression ce, boolean direct) throws InconsistentOntologyException, ClassExpressionNotInProfileException, ReasonerInterruptedException, TimeOutException {
        return new OWLNamedIndividualNodeSet();
    }
	

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLObjectPropertyExpression> getInverseObjectProperties(OWLObjectPropertyExpression pe)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getInverseObjectProperties( pe );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLClass> getObjectPropertyDomains(OWLObjectPropertyExpression pe, boolean direct)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getObjectPropertyDomains( pe, direct );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLClass> getObjectPropertyRanges(OWLObjectPropertyExpression pe, boolean direct)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getObjectPropertyRanges( pe, direct );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLNamedIndividual> getObjectPropertyValues(OWLNamedIndividual ind,
			OWLObjectPropertyExpression pe) throws InconsistentOntologyException,
			FreshEntitiesException, ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getObjectPropertyValues( ind, pe );

	}

	/**
	 * {@inheritDoc}
	 */
	public Set<OWLAxiom> getPendingAxiomAdditions() {
		return reasoner.getPendingAxiomAdditions();
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<OWLAxiom> getPendingAxiomRemovals() {
		return reasoner.getPendingAxiomRemovals();
	}

	/**
	 * {@inheritDoc}
	 */
	public List<OWLOntologyChange> getPendingChanges() {
		return reasoner.getPendingChanges();
	}

	/**
	 * {@inheritDoc}
	 */
	public String getReasonerName() {
		return "NCI Curator";
	}

	/**
	 * {@inheritDoc}
	 */
	public Version getReasonerVersion() {
		return reasoner.getReasonerVersion();
	}

	/**
	 * {@inheritDoc}
	 */
	public OWLOntology getRootOntology() {
		return reasoner.getRootOntology();
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLNamedIndividual> getSameIndividuals(OWLNamedIndividual ind)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getSameIndividuals( ind );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLDataProperty> getSubDataProperties(OWLDataProperty pe, boolean direct)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getSubDataProperties( pe, direct );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLObjectPropertyExpression> getSubObjectProperties(OWLObjectPropertyExpression pe,
			boolean direct) throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getSubObjectProperties( pe, direct );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLClass> getSuperClasses(OWLClassExpression ce, boolean direct)
			throws InconsistentOntologyException, ClassExpressionNotInProfileException,
			FreshEntitiesException, ReasonerInterruptedException, TimeOutException {
		if( ce.isAnonymous() ) {
	        throw new UnsupportedOperationException( "This reasoner only supports named classes" );
        }

		//classify();

		Set<Node<OWLClass>> values = new HashSet<Node<OWLClass>>();
		if (taxonomy != null) {
			for( Set<OWLClass> val : taxonomy.getSupers( (OWLClass) ce, direct ) ) {
				values.add( NodeFactory.getOWLClassNode( val ) );
			}
		}

		return new OWLClassNodeSet( values );
	}

	/**
	 * {@inheritDoc}
	 */
	public Set<OWLSubClassOfAxiom> getAllInferredSuperClasses() {

		final OWLDataFactory factory = getManager().getOWLDataFactory();
		
		if (taxonomy != null) {
			return getInferredClasses(factory, taxonomy.getClasses());
		} else {
			return new HashSet<OWLSubClassOfAxiom>();
		}
	}
	
	

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLDataProperty> getSuperDataProperties(OWLDataProperty pe, boolean direct)
			throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getSuperDataProperties( pe, direct );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLObjectPropertyExpression> getSuperObjectProperties(OWLObjectPropertyExpression pe,
			boolean direct) throws InconsistentOntologyException, FreshEntitiesException,
			ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.getSuperObjectProperties( pe, direct );
	}

	/**
	 * {@inheritDoc}
	 */
	public long getTimeOut() {
		return reasoner.getTimeOut();
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLClass> getTopClassNode() {
		return getEquivalentClasses( OWL.Thing );
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLDataProperty> getTopDataPropertyNode() {
		return getEquivalentDataProperties( OWL.topDataProperty );
	}

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLObjectPropertyExpression> getTopObjectPropertyNode() {
		return getEquivalentObjectProperties( OWL.topObjectProperty );
	}

	/**
	 * {@inheritDoc}
	 */
	public NodeSet<OWLClass> getTypes(@Nonnull OWLNamedIndividual ind, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLClassNodeSet();
    }
	

	/**
	 * {@inheritDoc}
	 */
	public Node<OWLClass> getUnsatisfiableClasses() throws ReasonerInterruptedException,
			TimeOutException {
		//classify();

		return getBottomClassNode();
	}

	/**
	 * {@inheritDoc}
	 */
	public void interrupt() {
		reasoner.dispose();
		if (classy != null) {
			classy.interrupt();
			
		}

	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isConsistent() throws ReasonerInterruptedException, TimeOutException {
		reasoner.flush();

		return reasoner.isConsistent();
	}


	/**
	 * {@inheritDoc}
	 */
	public boolean isEntailmentCheckingSupported(AxiomType<?> axiomType) {
		// the current EntailmentChecker supports the same set of axioms as
		// the underlying reasoner (if it cannot handle any element directly,
		// it forwards the entailment check to the underlying reasoner)
		return this.getReasoner().isEntailmentCheckingSupported( axiomType );
	}

	

	/**
     * {@inheritDoc}
     */
    public Set<InferenceType> getPrecomputableInferenceTypes() {
	    return reasoner.getPrecomputableInferenceTypes();
    }

	/**
     * {@inheritDoc}
     */
    public boolean isPrecomputed(InferenceType inferenceType) {
		switch (inferenceType) {
			case CLASS_HIERARCHY:
				return isClassified();
			default:
				return reasoner.isPrecomputed(inferenceType);
		}
    }

	/**
     * {@inheritDoc}
     */
    public void precomputeInferences(InferenceType... inferenceTypes) throws ReasonerInterruptedException,
                    TimeOutException, InconsistentOntologyException {
    	for (InferenceType inferenceType : inferenceTypes) {
    		switch (inferenceType) {
    			case CLASS_HIERARCHY:
    				classify();
    				break;
    			default:
    				reasoner.precomputeInferences(inferenceTypes);
    		}
        }
    }

	@Override
	public boolean isEntailed(OWLAxiom axiom) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isEntailed(Set<? extends OWLAxiom> axioms) {
		// TODO Auto-generated method stub
		return false;
	}
	
	
}
