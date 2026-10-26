package gov.nih.nci.curator.owlapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyExpression;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeVisitor;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.ReplaceOntologyPrefixMappingChange;
import org.semanticweb.owlapi.model.SetOntologyID;
import org.semanticweb.owlapi.reasoner.AxiomNotInProfileException;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.ClassExpressionNotInProfileException;
import org.semanticweb.owlapi.reasoner.FreshEntityPolicy;
import org.semanticweb.owlapi.reasoner.IllegalConfigurationException;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.IndividualNodeSetPolicy;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.semanticweb.owlapi.reasoner.ReasonerProgressMonitor;
import org.semanticweb.owlapi.reasoner.TimeOutException;
import org.semanticweb.owlapi.reasoner.UnsupportedEntailmentTypeException;
import org.semanticweb.owlapi.reasoner.impl.NodeFactory;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNode;
import org.semanticweb.owlapi.reasoner.impl.OWLClassNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLDataPropertyNode;
import org.semanticweb.owlapi.reasoner.impl.OWLDataPropertyNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLNamedIndividualNode;
import org.semanticweb.owlapi.reasoner.impl.OWLNamedIndividualNodeSet;
import org.semanticweb.owlapi.reasoner.impl.OWLObjectPropertyNode;
import org.semanticweb.owlapi.reasoner.impl.OWLObjectPropertyNodeSet;
import org.semanticweb.owlapi.util.Version;

import gov.nih.nci.curator.utils.ReasonerProgressUI;
import gov.nih.nci.curator.utils.VersionInfo;


public class NCICurator extends AbstractOWLListeningReasoner {
	public static final Logger log = Logger.getLogger( NCICurator.class.getName() );

	private static final Set<InferenceType> PRECOMPUTABLE_INFERENCES = EnumSet.of(InferenceType.CLASS_HIERARCHY);
	
	private static final Version VERSION = createVersion();
	
	public Map<OWLClass, Set<OWLAxiom>> getBadConstructs() {
		return kb.getBadConstructs();
		
	}
	
	public Map<OWLClass, Set<OWLAxiom>> getBadRoles() {
		return kb.getBadRoles();
		
	}
	
	private static Version createVersion() {
		String versionString = VersionInfo.getInstance().getVersionString();
		String[] versionNumbers = versionString.split( "\\." );
		
		int major = parseNumberIfExists( versionNumbers, 0 );
		int minor = parseNumberIfExists( versionNumbers, 1 );
		int patch = parseNumberIfExists( versionNumbers, 2 );
		int build = parseNumberIfExists( versionNumbers, 3 );
		
		return new Version( major, minor, patch, build );
	}
	
	private static int parseNumberIfExists(String[] numbers, int index) {
		try {
			if( 0 <= index && index < numbers.length )
				return Integer.parseInt( numbers[index] );
		} catch( NumberFormatException e ) {
			log.log( Level.FINE, "Invalid number in version identifier: " + numbers[index], e );
		}
		
		return 0;
	}

	public static NCICuratorConfig config() {
		return new NCICuratorConfig();
	}

	private class ChangeVisitor implements OWLOntologyChangeVisitor {

		private boolean	reloadRequired;

		public boolean isReloadRequired() {
			return reloadRequired;
		}

		/**
		 * Process a change, providing a single call for common
		 * reset,accept,isReloadRequired pattern.
		 * 
		 * @param change
		 *            the {@link OWLOntologyChange} to process
		 * @return <code>true</code> if change is handled, <code>false</code> if
		 *         a reload is required
		 */
		public boolean process(OWLOntologyChange change) {
			this.reset();
			change.accept( this );
			return !isReloadRequired();
		}

		public void reset() {
			reloadRequired = false;
		}

		public void visit(AddAxiom change) {
			kb.processChange(change);
			reloadRequired = kb.isReloadRequired();
		}

		public void visit(RemoveAxiom change) {
			kb.processChange(change);
			reloadRequired = kb.isReloadRequired();
		}

		public void visit(AddImport change) {
			reloadRequired = true;
		}

		public void visit(AddOntologyAnnotation change) {

		}

		public void visit(RemoveImport change) {
			reloadRequired = true;
		}

		public void visit(RemoveOntologyAnnotation change) {
			
		}

		public void visit(SetOntologyID change) {
			
		}

		@Override
		public void visit(ReplaceOntologyPrefixMappingChange change) {
			// TODO Auto-generated method stub
			
		}

	}
	
	

	private final OWLDataFactory			factory;
	private KnowledgeBase			kb;
	private final OWLOntologyManager		manager;
	private final ReasonerProgressMonitor	monitor;
	/**
	 * Main ontology for reasoner
	 */
	private final OWLOntology				ontology;
	/**
	 * Imports closure for ontology
	 */
	private Set<OWLOntology>		importsClosure;
	private boolean 				shouldRefresh;
	
	private final BufferingMode			bufferingMode;
	private final List<OWLOntologyChange>	pendingChanges;

	private final ChangeVisitor						changeVisitor 	= new ChangeVisitor();
	
	private final OWLClass OWL_THING;

    private final OWLClass OWL_NOTHING;

    private final OWLObjectProperty OWL_TOP_OBJECT_PROPERTY;

    private final OWLObjectProperty OWL_BOTTOM_OBJECT_PROPERTY;

    private final OWLDataProperty OWL_TOP_DATA_PROPERTY;

    private final OWLDataProperty OWL_BOTTOM_DATA_PROPERTY;
	
	
	
	/**
	 * Create a reasoner for the given ontology and configuration.
	 */
	public NCICurator(OWLOntology ont, NCICuratorConfig config) throws IllegalConfigurationException {
		
		if( !getFreshEntityPolicy().equals( config.getFreshEntityPolicy() ) ) {
			throw new IllegalConfigurationException(
					"PelletOptions.SILENT_UNDEFINED_ENTITY_HANDLING conflicts with reasoner configuration",
					config );
		}

		ontology = ont;

		manager = ontology.getOWLOntologyManager();
	

		setListenChanges(config.isListenChanges());

		//monitor = config.getProgressMonitor();
		monitor = new ReasonerProgressUI();
		
		kb = new KnowledgeBase(ontology);
		kb.setTaxonomyBuilderProgressMonitor( new ProgressAdapter( monitor ) );
		if( config.getTimeOut() > 0 ) {
			kb.timers.mainTimer.setTimeout( config.getTimeOut() );
		}

		factory = manager.getOWLDataFactory();
		
		OWL_THING = factory.getOWLThing();
        OWL_NOTHING = factory.getOWLNothing();
        OWL_TOP_OBJECT_PROPERTY = factory.getOWLTopObjectProperty();
        OWL_BOTTOM_OBJECT_PROPERTY = factory.getOWLBottomObjectProperty();
        OWL_TOP_DATA_PROPERTY = factory.getOWLTopDataProperty();
        OWL_BOTTOM_DATA_PROPERTY = factory.getOWLBottomDataProperty();
		
		 
		
		bufferingMode = config.getBufferingMode();
		
		shouldRefresh = true;
		pendingChanges = new ArrayList<OWLOntologyChange>();
		
		refresh();
	}
	

	public void dispose() {
		setListenChanges(false);
		kb = null;
	}
	
	public boolean isClassified() {
		return kb.isClassified();
	}

	/**
	 * {@inheritDoc}
	 */
	public void flush() {
		shouldRefresh = !processChanges( pendingChanges );
		pendingChanges.clear();
		refreshCheck();
	}
	
	/**
	 * Return the underlying Pellet knowledge base.
	 * 
	 * @return the underlying Pellet knowledge base
	 */
	public KnowledgeBase getKB() {
		return kb;
	}

	public OWLOntologyManager getManager() {
		return manager;
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public BufferingMode getBufferingMode() {
		return bufferingMode;
	}
	

	

	public List<OWLOntologyChange> getPendingChanges() {
		return pendingChanges;
	}

	public String getReasonerName() {
		return NCICuratorFactory.getInstance().getReasonerName();
	}

	/**
	 * {@inheritDoc}
	 */
	public Version getReasonerVersion() {
		return VERSION;
	}

	public OWLOntology getRootOntology() {
		return ontology;
	}


	
	public long getTimeOut() {
		return kb.timers.mainTimer.getTimeout();
	}
	
	
	public void interrupt() {
		kb.timers.interrupt();
	}
	

    @Nonnull
    public Set<OWLAxiom> getPendingAxiomAdditions() {
        return Collections.emptySet();
    }

    @Nonnull
    public Set<OWLAxiom> getPendingAxiomRemovals() {
        return Collections.emptySet();
    }  

    

    public boolean isConsistent() throws ReasonerInterruptedException, TimeOutException {
        return true;
    }

    @Nonnull
    public NodeSet<OWLClass> getDataPropertyDomains(@Nonnull OWLDataProperty pe, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLClassNodeSet();
    }

    @Nonnull
    public Set<OWLLiteral> getDataPropertyValues(@Nonnull OWLNamedIndividual ind, @Nonnull OWLDataProperty pe) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return Collections.emptySet();
    }

    @Nonnull
    public Node<OWLClass> getEquivalentClasses(@Nonnull OWLClassExpression ce) throws InconsistentOntologyException, ClassExpressionNotInProfileException, ReasonerInterruptedException, TimeOutException {
        if (ce.isAnonymous()) {
            return new OWLClassNode();
        }
        else {
        	Set<OWLClass> equivs = 
        			kb.getTaxonomyBuilder().getTaxonomy().getAllEquivalents(ce.asOWLClass());
        	
            return new OWLClassNode(equivs);
        }
    }

    @Nonnull
    public Node<OWLDataProperty> getEquivalentDataProperties(@Nonnull OWLDataProperty pe) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        if (pe.isAnonymous()) {
            return new OWLDataPropertyNode();
        }
        else {
            return new OWLDataPropertyNode(pe.asOWLDataProperty());
        }
    }

    @Nonnull
    public Node<OWLObjectPropertyExpression> getEquivalentObjectProperties(@Nonnull OWLObjectPropertyExpression pe) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        if (pe.isAnonymous()) {
            return new OWLObjectPropertyNode();
        }
        else {
            return new OWLObjectPropertyNode(pe.asOWLObjectProperty());
        }
    }

    @Nonnull
    public NodeSet<OWLNamedIndividual> getInstances(@Nonnull OWLClassExpression ce, boolean direct) throws InconsistentOntologyException, ClassExpressionNotInProfileException, ReasonerInterruptedException, TimeOutException {
        return new OWLNamedIndividualNodeSet();
    }

    @Nonnull
    public Node<OWLObjectPropertyExpression> getInverseObjectProperties(@Nonnull OWLObjectPropertyExpression pe) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLObjectPropertyNode();
    }

    @Nonnull
    public NodeSet<OWLClass> getObjectPropertyDomains(@Nonnull OWLObjectPropertyExpression pe, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLClassNodeSet();
    }

    @Nonnull
    public NodeSet<OWLClass> getObjectPropertyRanges(@Nonnull OWLObjectPropertyExpression pe, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLClassNodeSet();
    }

    @Nonnull
    public NodeSet<OWLNamedIndividual> getObjectPropertyValues(@Nonnull OWLNamedIndividual ind, @Nonnull OWLObjectPropertyExpression pe) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLNamedIndividualNodeSet();
    }


    @Nonnull
    public Node<OWLNamedIndividual> getSameIndividuals(@Nonnull OWLNamedIndividual ind) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLNamedIndividualNode(ind);
    }

    @Nonnull
    public NodeSet<OWLClass> getSubClasses(@Nonnull OWLClassExpression ce, boolean direct) throws InconsistentOntologyException, ClassExpressionNotInProfileException, ReasonerInterruptedException, TimeOutException {
        Set<Set<OWLClass>> subs = kb.getTaxonomyBuilder().getTaxonomy().getSubs(ce.asOWLClass(), true);
        Set<Node<OWLClass>> nodes = new HashSet<Node<OWLClass>>();
        for (Set<OWLClass> oc : subs) {
        	nodes.add(NodeFactory.getOWLClassNode(oc));
        }

        return new OWLClassNodeSet(nodes);
    }

    @Nonnull
    public NodeSet<OWLDataProperty> getSubDataProperties(@Nonnull OWLDataProperty pe, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLDataPropertyNodeSet();
    }

    @Nonnull
    public NodeSet<OWLObjectPropertyExpression> getSubObjectProperties(@Nonnull OWLObjectPropertyExpression pe, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLObjectPropertyNodeSet();
    }

    @Nonnull
    public NodeSet<OWLClass> getSuperClasses(@Nonnull OWLClassExpression ce, boolean direct) throws InconsistentOntologyException, ClassExpressionNotInProfileException, ReasonerInterruptedException, TimeOutException {
        return new OWLClassNodeSet();
    }

    @Nonnull
    public Set<OWLSubClassOfAxiom> getAllInferredSuperClasses() {
        return new HashSet<>();
    }

    @Nonnull
    public NodeSet<OWLDataProperty> getSuperDataProperties(@Nonnull OWLDataProperty pe, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLDataPropertyNodeSet();
    }

    @Nonnull
    public NodeSet<OWLObjectPropertyExpression> getSuperObjectProperties(@Nonnull OWLObjectPropertyExpression pe, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLObjectPropertyNodeSet();
    }

    @Nonnull
    public NodeSet<OWLClass> getTypes(@Nonnull OWLNamedIndividual ind, boolean direct) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLClassNodeSet();
    }

    @Nonnull
    public Node<OWLClass> getUnsatisfiableClasses() throws ReasonerInterruptedException, TimeOutException {
        return new OWLClassNode();
    }

    public boolean isEntailed(@Nonnull OWLAxiom axiom) throws ReasonerInterruptedException, UnsupportedEntailmentTypeException, TimeOutException, AxiomNotInProfileException, InconsistentOntologyException {
        return false;
    }

    public boolean isEntailed(@Nonnull Set<? extends OWLAxiom> axioms) throws ReasonerInterruptedException, UnsupportedEntailmentTypeException, TimeOutException, AxiomNotInProfileException, InconsistentOntologyException {
        return false;
    }

    public boolean isEntailmentCheckingSupported(@Nonnull AxiomType<?> axiomType) {
        return false;
    }

    public boolean isSatisfiable(@Nonnull OWLClassExpression classExpression) throws ReasonerInterruptedException, TimeOutException, ClassExpressionNotInProfileException, InconsistentOntologyException {
        return true;
    }

    @Nonnull
    public Node<OWLClass> getBottomClassNode() {
        return new OWLClassNode(OWL_NOTHING);
    }

    @Nonnull
    public Node<OWLDataProperty> getBottomDataPropertyNode() {
        return new OWLDataPropertyNode(OWL_BOTTOM_DATA_PROPERTY);
    }

    @Nonnull
    public Node<OWLObjectPropertyExpression> getBottomObjectPropertyNode() {
        return new OWLObjectPropertyNode(OWL_BOTTOM_OBJECT_PROPERTY);
    }

    @Nonnull
    public NodeSet<OWLNamedIndividual> getDifferentIndividuals(@Nonnull OWLNamedIndividual ind) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLNamedIndividualNodeSet();
    }

    @Nonnull
    public NodeSet<OWLClass> getDisjointClasses(@Nonnull OWLClassExpression ce) {
        return new OWLClassNodeSet();
    }

    @Nonnull
    public NodeSet<OWLDataProperty> getDisjointDataProperties(@Nonnull OWLDataPropertyExpression pe) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLDataPropertyNodeSet();
    }

    @Nonnull
    public NodeSet<OWLObjectPropertyExpression> getDisjointObjectProperties(@Nonnull OWLObjectPropertyExpression pe) throws InconsistentOntologyException, ReasonerInterruptedException, TimeOutException {
        return new OWLObjectPropertyNodeSet();
    }

    @Nonnull
    public IndividualNodeSetPolicy getIndividualNodeSetPolicy() {
        return IndividualNodeSetPolicy.BY_SAME_AS;
    }

    

    @Nonnull
    public Node<OWLClass> getTopClassNode() {
        return new OWLClassNode(OWL_THING);
    }

    @Nonnull
    public Node<OWLDataProperty> getTopDataPropertyNode() {
        return new OWLDataPropertyNode(OWL_TOP_DATA_PROPERTY);
    }

    @Nonnull
    public Node<OWLObjectPropertyExpression> getTopObjectPropertyNode() {
        return new OWLObjectPropertyNode(OWL_TOP_OBJECT_PROPERTY);
    }

    @Nonnull
    public FreshEntityPolicy getFreshEntityPolicy() {
        return FreshEntityPolicy.ALLOW;
    }

	/**
	 * {@inheritDoc}
	 */
	public void ontologiesChanged(List<? extends OWLOntologyChange> changes) {
		switch( bufferingMode ) {
		case BUFFERING:
			pendingChanges.addAll( changes );
			break;
		case NON_BUFFERING:
			processChanges( changes );
			break;
		default:
			throw new AssertionError( "Unexpected buffering mode: " + bufferingMode );
		}
	}

	/**
	 * Process all the given changes in an incremental fashion. Processing will
	 * stop if a change cannot be handled incrementally and requires a reload.
	 * The reload will not be done as part of processing.
	 * 
	 * @param changes
	 *            the changes to be applied to the reasoner
	 * @return <code>true</code> if all changes have been processed
	 *         successfully, <code>false</code> otherwise (indicates reasoner
	 *         will reload the whole ontology next time it needs to do
	 *         reasoning)
	 */
	public boolean processChanges(List<? extends OWLOntologyChange> changes) {
		if( shouldRefresh )
			return false;
		
		for( OWLOntologyChange change : changes ) {
			
			if( log.isLoggable( Level.FINER ) ) 
				log.fine( "Changed: " + change + " in " + change.getOntology() );
			
			if( !importsClosure.contains( change.getOntology() ) )
				continue;

			if( !changeVisitor.process( change ) ) {
				if( log.isLoggable( Level.FINE ) )
					log.fine( "Reload required by ontology change " + change );

				shouldRefresh = true;
				break;
			}
		}
		
		return !shouldRefresh;
	}

	/**
	 * {@inheritDoc}
	 */
	public void prepareReasoner() throws ReasonerInterruptedException, TimeOutException {
		
	}

	/**
	 * Clears the reasoner and reloads all the axioms in the imports closure.
	 */
	public void refresh() {
		
		kb.clear();

		

		importsClosure = ontology.getImportsClosure();
		
		
		
		shouldRefresh = false;
	}
	
	/**
	 * Make sure the reasoner is ready to answer queries. This function does 
	 * not process changes but if changes processed earlier required a refresh
	 * this funciton will call {@link #refresh()}.
	 */
	private void refreshCheck() {
		if( kb == null )
			throw new OWLRuntimeException( "Trying to use a disposed reasoner" );

		if( shouldRefresh )
			refresh();
	}
	
	
	
	

	/**
     * {@inheritDoc}
     */
    public Set<InferenceType> getPrecomputableInferenceTypes() {
	    return PRECOMPUTABLE_INFERENCES;
    }

	/**
     * {@inheritDoc}
     */
    public boolean isPrecomputed(InferenceType inferenceType) {
		switch (inferenceType) {
			case CLASS_HIERARCHY:
				return kb.isClassified();
			default:
				return false;
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
    				kb.classify();
    			default:
    				break;
    		}
        }
    }
}
