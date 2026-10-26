// Portions Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// Clark & Parsia, LLC parts of this source code are available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com
//
// ---
// Portions Copyright (c) 2003 Ron Alford, Mike Grove, Bijan Parsia, Evren Sirin
// Alford, Grove, Parsia, Sirin parts of this source code are available under the terms of the MIT License.
//
// The MIT License
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to
// deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or
// sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
// FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
// IN THE SOFTWARE.

package gov.nih.nci.curator.taxonomy.printer;

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.taxonomy.utils.TaxonomyUtils;
import gov.nih.nci.curator.utils.QNameProvider;



/**
 * <p>Title: </p>
 *
 * <p>Description: Specialized tree printer for class hierarchies that prints instaces for each class</p>
 *
 * <p>Copyright: Copyright (c) 2007</p>
 *
 * <p>Company: Clark & Parsia, LLC. <http://www.clarkparsia.com></p>
 *
 * @author Evren Sirin
 */
public class ClassTreePrinter extends TreeTaxonomyPrinter<OWLClass> implements TaxonomyPrinter<OWLClass> {
	private QNameProvider qnames = new QNameProvider();
	
	public ClassTreePrinter() {
	}
	
	@Override
	protected void printNode(Set<OWLClass> set) {
		super.printNode( set );
		
		Set<OWLClass> instances = TaxonomyUtils.getDirectInstances( taxonomy, set.iterator().next() );
		if(instances.size() > 0) {
			out.print(" - (");
			boolean printed = false;
			int anonCount = 0;
			Iterator<OWLClass> ins = instances.iterator();			
			for(int k = 0; ins.hasNext(); k++) {
				OWLClass x = ins.next();
				
				
				    if(printed) 
				        out.print(", ");
				    else
				        printed = true;
				    printURI(out, x);
								
			}
			if(anonCount > 0) {
			    if(printed) out.print(", ");
			    out.print(anonCount + " Anonymous Individual");
			    if(anonCount > 1) out.print("s");
			}
			out.print(")");
		}	
	}

	@Override
	protected void printURI(PrintWriter out, OWLClass c) {
	    String str = null;
	    
		str = qnames.shortForm(c.getIRI().toString());
		
		out.print( str );		
	}
}