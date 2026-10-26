// Portions Copyright (c) 2006 - 2008, Clark & Parsia, LLC.
// <http://www.clarkparsia.com>
// Clark & Parsia, LLC parts of this source code are available under the terms
// of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com
//
// ---
// Portions Copyright (c) 2003 Ron Alford, Mike Grove, Bijan Parsia, Evren Sirin
// Alford, Grove, Parsia, Sirin parts of this source code are available under
// the terms of the MIT License.
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

package gov.nih.nci.curator.owlapi;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAxiomChange;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.nih.nci.curator.constraints.TaxonomyDomainResolver;
//import gov.nih.nci.curator.taxonomy.CuratorBuilder;
//import gov.nih.nci.curator.taxonomy.CuratorBuilder2;
import gov.nih.nci.curator.taxonomy.CuratorBuilder;
import gov.nih.nci.curator.taxonomy.Taxonomy;
import gov.nih.nci.curator.taxonomy.TaxonomyBuilder;
import gov.nih.nci.curator.utils.EquStatedVisitor;
import gov.nih.nci.curator.utils.RolesVisitor;
import gov.nih.nci.curator.utils.RootVisitor;
import gov.nih.nci.curator.utils.StatedParentVisitor;
import gov.nih.nci.curator.utils.Timer;
import gov.nih.nci.curator.utils.Timers;
import gov.nih.nci.curator.utils.UsagesVisitor;
import gov.nih.nci.curator.utils.progress.ProgressMonitor;
/**
 * @author Evren Sirin
 */
public class KnowledgeBase {
	public final static Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

	// Surface the classify phase timers at INFO without a DEBUG log flood: -Dnci.classify.timers=true.
	private static final boolean TIMERS = Boolean.getBoolean("nci.classify.timers");

	protected TaxonomyBuilder builder;
	private ProgressMonitor builderProgressMonitor;

	private OWLOntology ont = null;

	protected Set<OWLAxiomChange> changes;
	
	private boolean reloadRequired = false;
	
	private HashMap<OWLClass, List<OWLObjectSomeValuesFrom>> role_map;
	
	private HashMap<OWLClass, Set<OWLAxiom>> bad_constructs;
	
	private HashMap<OWLClass, Set<OWLAxiom>> bad_roles;
	
	public Timers timers = new Timers();
	
	public Map<OWLClass, Set<OWLAxiom>> getBadConstructs() {
		return bad_constructs;
		
	}
	
	public Map<OWLClass, Set<OWLAxiom>> getBadRoles() {
		return bad_roles;
		
	}
	
	private boolean role_errors = false;
	
	

	protected enum ReasoningState {
		CONSISTENCY, CLASSIFY
	}

	protected EnumSet<ReasoningState> state = EnumSet.noneOf(ReasoningState.class);

	

	public boolean isClassified() {
		return !isChanged() && state.contains(ReasoningState.CLASSIFY);
	}

	public boolean isChanged() {
		return !changes.isEmpty();
	}

	public boolean isReloadRequired() {
		// TODO Auto-generated method stub
		return reloadRequired;
	}
	
	public boolean hasBadConstructs() {
		return role_errors;
	}

	/**
	 * 
	 */
	public KnowledgeBase(OWLOntology o) {
		
		clear();
		state = EnumSet.noneOf(ReasoningState.class);

		ont = o;
		
		visitor = new UsagesVisitor(null, ont);
		roles_visitor = new RolesVisitor(null, ont, this);
		eqv_visitor = new EquStatedVisitor(null, ont);
		root_visitor = new RootVisitor(null, ont);
		spar_visitor = new StatedParentVisitor(null, ont);
		
		role_map = new HashMap<OWLClass, List<OWLObjectSomeValuesFrom>>();		
		new HashMap<OWLClass, List<OWLObjectSomeValuesFrom>>();		
		bad_constructs = new HashMap<OWLClass, Set<OWLAxiom>>();
		bad_roles = new HashMap<OWLClass, Set<OWLAxiom>>();

	}

	public void clear() {

		builder = null;
		
		timers = new Timers();

		state.clear();
		changes = new HashSet<OWLAxiomChange>();
		
		role_errors = false;
		
		role_map = new HashMap<OWLClass, List<OWLObjectSomeValuesFrom>>();		
		new HashMap<OWLClass, List<OWLObjectSomeValuesFrom>>();		
		bad_constructs = new HashMap<OWLClass, Set<OWLAxiom>>();
		bad_roles = new HashMap<OWLClass, Set<OWLAxiom>>();
	}

	public void classify() {

		if (isClassified())
			return;
		
		Timer timer = null;
		
		if (log.isDebugEnabled() || TIMERS) {

			timer = timers.startTimer("classify");
		}

		builder = getTaxonomyBuilder();
		
		if (!changes.isEmpty()) {
			// process the case where class is added
			builder.addClasses(extractClasses(changes));
			
			changes.clear();
		}

		try {

			boolean isClassified = builder.classify();

			if (log.isDebugEnabled() || TIMERS) {
				
				timer.stop();
				StringWriter sw = new StringWriter();
				timers.print( sw, true, null );
				log.info(sw.toString());
			}
			
			

			if (!isClassified)
				return;

			state.add(ReasoningState.CLASSIFY);
			
			
			
			

		} catch (AssertionError err) {
			err.printStackTrace();
		}
	}
	
	private Set<OWLClass> extractClasses(Set<OWLAxiomChange> s) {
		Set<OWLClass> result = new HashSet<OWLClass>();
		
		
		
		return result;
	}

	public TaxonomyBuilder getTaxonomyBuilder() {
		if (builder == null) {

			builder = new CuratorBuilder();

			builder.setKB(this);

			if (builderProgressMonitor != null) {
				builder.setProgressMonitor(builderProgressMonitor);
			}
		}

		return builder;
	}
	
	public ArrayList<ArrayList<OWLClass>> getToldSupercs() {
		Set<OWLAxiom> axioms = ont.getAxioms();
		ArrayList<ArrayList<OWLClass>> result = new ArrayList<ArrayList<OWLClass>>();
		for (OWLAxiom ax : axioms) {
			if (ax instanceof OWLSubClassOfAxiom) {
				
				OWLClassExpression sub = ((OWLSubClassOfAxiom) ax).getSubClass();
				OWLClassExpression sup = ((OWLSubClassOfAxiom) ax).getSuperClass();
				
				if (sub.isOWLClass() && sup.isOWLClass()) {
					ArrayList<OWLClass> l = new ArrayList<OWLClass>();
					l.add(sub.asOWLClass());
					l.add(sup.asOWLClass());
					result.add(l);
				}	
				
			} else if (ax instanceof OWLEquivalentClassesAxiom) {
				Set<OWLClassExpression> equivs = 
						((OWLEquivalentClassesAxiom) ax).getClassExpressions();
				if (equivs.isEmpty()) {
					
				}
			} else if (ax instanceof OWLDisjointClassesAxiom) {
				Set<OWLClassExpression> classes = ((OWLDisjointClassesAxiom) ax).getClassExpressions();
				if (classes.isEmpty()) {
					
				}
			}
		}
		return result;
	}

	public Collection<OWLClass> getClasses() {

		return ont.getClassesInSignature();
	}
	
	public OWLClass getClass(String name) {
		for (OWLClass c : getClasses()) {
			if (name.equals(c.getIRI().getFragment())) {
				return c;
			}
		}
		return null;
	}
	
	public Set<OWLObjectProperty> getObjectProperties() {
		return ont.getObjectPropertiesInSignature();
	}
	
	public OWLClass getDomain(OWLObjectProperty p) {
		Set<OWLObjectPropertyDomainAxiom> doms = ont.getObjectPropertyDomainAxioms(p);
		if (doms.isEmpty()) {
			Set<OWLSubObjectPropertyOfAxiom> subs = ont.getObjectSubPropertyAxiomsForSubProperty(p);
			for (OWLSubObjectPropertyOfAxiom ax : subs) {
				OWLClass ocls = getDomain(ax.getSuperProperty().asOWLObjectProperty());
				if (ocls != null) {
					return ocls;
				}
			}
			return null;
		}
		for (OWLObjectPropertyDomainAxiom dax : doms) {			
			return dax.getDomain().asOWLClass();
			
		}
		return null;		
	}
	
	
	
	public OWLClass getRange(OWLObjectProperty p) {

		Set<OWLObjectPropertyRangeAxiom> raxs = ont.getObjectPropertyRangeAxioms(p);
		if (raxs.isEmpty()) {
			Set<OWLSubObjectPropertyOfAxiom> subs = ont.getObjectSubPropertyAxiomsForSubProperty(p);
			for (OWLSubObjectPropertyOfAxiom ax : subs) {
				OWLClass ocls = getRange(ax.getSuperProperty().asOWLObjectProperty());
				if (ocls != null) {
					return ocls;
				}
			}
		} else {
			for (OWLObjectPropertyRangeAxiom ra : raxs) {
				if (!ra.getRange().isAnonymous()) {
					return ra.getRange().asOWLClass();   			
				}    		
			}
		}
		return null;

	}
	
	public Set<OWLClass> getDisjoints(OWLClass c) {
		Set<OWLClass> disj = new HashSet<OWLClass>();
		Set<OWLDisjointClassesAxiom> setax = ont.getDisjointClassesAxioms(c);
		for (OWLDisjointClassesAxiom ax : setax) {
			Set<OWLClassExpression> exps = ax.getClassExpressions();
			for (OWLClassExpression exp : exps) {
				if (!exp.isAnonymous() && !exp.asOWLClass().equals(c)) {
					disj.add(exp.asOWLClass());
				}
			}
		}
		return disj;
	}
	
	public List<OWLObjectSomeValuesFrom> getRoles(OWLClass c) {
		if (role_map.containsKey(c)) {
			return role_map.get(c);
		} else {
			roles_visitor.setEntity(c, false, null);
			c.accept(roles_visitor);
			role_map.put(c, roles_visitor.roles);
			if (roles_visitor.errors) {
				role_errors = true;
				bad_constructs.put(c, roles_visitor.bad_constructs);
				
			};
			return roles_visitor.roles;
		}
	}
	
	public void checkLocalRoles(OWLClass c, Taxonomy<OWLClass> stated) {
		
			roles_visitor.setEntity(c, true, new TaxonomyDomainResolver(stated));
			c.accept(roles_visitor);
			if (roles_visitor.stated_errors) {
				role_errors = true;
				bad_roles.put(c, roles_visitor.bad_roles);
				
			};
			if (roles_visitor.errors) {
				role_errors = true;
				bad_constructs.put(c, roles_visitor.bad_constructs);
				
			};
			
	}
	
	public Set<OWLClass> getStatedEquivParents(OWLClass c) {
		eqv_visitor.setEntity(c);
		c.accept(eqv_visitor);
		return eqv_visitor.parents;
		
	}
		
	
	public boolean isRoot(OWLClass c) {
		return getStatedParents(c).isEmpty();		
	}
	
	public Set<OWLClass> roots() {
		Set<OWLClass> roots = new HashSet<OWLClass>();
		Collection<OWLClass> all_cls = getClasses();
		for (OWLClass c : all_cls) {

			if (isRoot(c)) {
				roots.add(c);
			}

		}
		return roots;
	}
	
	public OWLClass getClassDomain(OWLClass c) {
		root_visitor.setEntity(c);
		c.accept(root_visitor);
		return root_visitor.root;
	}
	 
	private OWLClass findRoot(OWLClass cls, OWLOntology ont, Set<OWLClass> res) {
		

		List<OWLClass> assertedParents = getAssertedParents(cls, ont);

		if (assertedParents.isEmpty()) {
			return cls;        	
		} else {
			for (OWLClass ap : assertedParents) {
				OWLClass rp = findRoot(ap, ont, res);
				if (res.contains(rp)) {

				} else {
					res.add(rp);
				}
			}

		}
		return res.iterator().next();
	}
	
	private boolean findPath(OWLClass src, OWLClass target, OWLOntology ont) throws Exception {
    	List<OWLClass> assertedParents = getAssertedParents(src, ont);
    	
		for (OWLClass ap : assertedParents) {
			if (ap.equals(target)) {
				throw new Exception();
			} else {
				List<OWLClass> app = getAssertedParents(ap, ont);
				if (app.contains(target)) {
					throw new Exception();				
				} else {
					findPath(ap, target, ont);
				}
			}
		}
		return false;
    	
    }

	private List<OWLClass> getAssertedParents(OWLClass cls, OWLOntology ont) {
		List<OWLClass> parents = new ArrayList<OWLClass>();
		Set<OWLSubClassOfAxiom> axs = 
				ont.getSubClassAxiomsForSubClass(cls);

		for(OWLSubClassOfAxiom ax : axs) {
			if (!ax.getSuperClass().isAnonymous()) {
				parents.add(ax.getSuperClass().asOWLClass());
			}
		}
		
		Set<OWLEquivalentClassesAxiom> eqs = ont.getEquivalentClassesAxioms(cls);
		
		for(OWLEquivalentClassesAxiom ax : eqs) {
			for (OWLClassExpression exp : ax.getClassExpressions()) {
				if (exp.isOWLClass() && !(exp.asOWLClass().equals(cls))) {
					parents.add(exp.asOWLClass());
				}
			}
		}

		return parents;
	}
	
	public Set<OWLClass> getStatedSubs(OWLClass c) {
		Set<OWLClass> subs = new HashSet<OWLClass>();
		Set<OWLSubClassOfAxiom> subaxs = ont.getSubClassAxiomsForSuperClass(c);
		for (OWLSubClassOfAxiom ax : subaxs) {
			subs.add(ax.getSubClass().asOWLClass());			
		}
		return subs;
	}
	
	public Set<OWLClass> getStatedParents(OWLClass c) {
		spar_visitor.setEntity(c);
		c.accept(spar_visitor);
		return spar_visitor.parents;
		
	}

	 private UsagesVisitor visitor;
	 
	 private RolesVisitor roles_visitor;
	 
	 private EquStatedVisitor eqv_visitor;
	 
	 private StatedParentVisitor spar_visitor;
	 
	 private RootVisitor root_visitor;
	 
	 public Set<OWLClass> getUses(OWLClass entity) {
		 visitor.setEntity(entity);
		 entity.accept(visitor);
		 return visitor.references;
	 }
	
	
	public OWLClass getThing() {
		return ont.getOWLOntologyManager().getOWLDataFactory().getOWLThing();
	}

	public OWLClass getNothing() {
		return ont.getOWLOntologyManager().getOWLDataFactory().getOWLNothing();
	}

	public void setTaxonomyBuilderProgressMonitor(ProgressMonitor progressAdapter) {
		builderProgressMonitor = progressAdapter;
	}

	public void processChange(OWLAxiomChange change) {
		if (change.isAddAxiom()) {
			changes.add(change);
			reloadRequired = false;
			
		} else {
			reloadRequired = true;
		}
		// TODO Auto-generated method stub

	}
	
	public boolean isPrimitive(OWLClass c) {
		return ont.getEquivalentClassesAxioms(c).isEmpty();
	}

	public boolean isConsistent() {
		// TODO Auto-generated method stub
		return true;
	}

	public boolean isSubClassOf(OWLClass a, OWLClass b) {
		
		return getTaxonomyBuilder().getTaxonomy().isSubNodeOf2(a, b).isTrue();
		
	}

}
