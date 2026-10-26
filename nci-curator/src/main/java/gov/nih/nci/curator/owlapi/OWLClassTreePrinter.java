package gov.nih.nci.curator.owlapi;

import java.io.PrintWriter;
import java.util.Set;

import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.taxonomy.printer.TaxonomyPrinter;
import gov.nih.nci.curator.taxonomy.printer.TreeTaxonomyPrinter;
import gov.nih.nci.curator.utils.QNameProvider;

/**
 * TaxonomyPrinter for Taxonomies of OWLClasses (Taxonomy<OWLClass>)
 * 
 * @author Blazej Bulka <blazej@clarkparsia.com>
 */
public class OWLClassTreePrinter extends TreeTaxonomyPrinter<OWLClass> implements TaxonomyPrinter<OWLClass> {
	private QNameProvider qnames = new QNameProvider();

	public OWLClassTreePrinter() {
	}

	@Override
	protected void printNode( Set<OWLClass> set ) {
		super.printNode(set);
		
	}

	@Override
	protected void printURI( PrintWriter out, OWLClass c ) {
		printIRI( out, c.getIRI() );
	}

	private void printIRI( PrintWriter out, IRI iri ) {
		out.print( qnames.shortForm( iri.getFragment() ) );
	}

	
}
