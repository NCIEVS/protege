package gov.nih.nci.curator;


import org.protege.editor.owl.model.inference.AbstractProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.reasoner.BufferingMode;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;


/**
 * 
 * @author Evren Sirin
 */
public class CuratorReasonerFactory extends AbstractProtegeOWLReasonerInfo {
	static {
		// true = (default) Non DL axioms will be ignored (eg as use of complex
		// roles in cardinality restrictions)
		// false = pellet will throw an exception if non DL axioms are included
		CuratorOptions.IGNORE_UNSUPPORTED_AXIOMS = false;
		CuratorOptions.SILENT_UNDEFINED_ENTITY_HANDLING = true;
	}

	private final CuratorReasonerPreferences prefs = CuratorReasonerPreferences.getInstance();
	private OWLReasonerFactory factory = null;

	@Override
	public OWLReasonerFactory getReasonerFactory() {
		if (factory != null) {
			return factory;
		}
		// enable/disable tracing based on the preference
		CuratorOptions.USE_TRACING = prefs.getExplanationCount() != 0;
		
		factory = IncrementalReasonerFactory.getInstance();
		
		return factory;
	}

	@Override
	public BufferingMode getRecommendedBuffering() {
				
		// TODO: Make this buffered
		
		return BufferingMode.BUFFERING;
	}

	public void preferencesUpdated() {
		factory = null;
	}
}
