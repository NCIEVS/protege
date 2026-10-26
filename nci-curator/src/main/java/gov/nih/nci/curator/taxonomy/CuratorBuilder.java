// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.taxonomy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.nih.nci.curator.CuratorOptions;
import gov.nih.nci.curator.owlapi.KnowledgeBase;
import gov.nih.nci.curator.owlapi.OWL;
import gov.nih.nci.curator.utils.Comparators;
import gov.nih.nci.curator.utils.Timer;
import gov.nih.nci.curator.utils.progress.ProgressMonitor;
import gov.nih.nci.curator.utils.progress.SilentProgressMonitor;

public class CuratorBuilder implements TaxonomyBuilder {
	
	private static final Logger log = LoggerFactory.getLogger(CuratorBuilder.class);

	protected ProgressMonitor monitor = CuratorOptions.USE_CLASSIFICATION_MONITOR.create();

	protected Collection<OWLClass> classes;
	
	private Set<OWLClass> additions = new HashSet<OWLClass>();

	private Map<OWLClass, Set<OWLClass>> toldDisjoints;

	private ObjectPropertyDomainOrder  dom_order;
	
	private List<OWLClass> domains;
	
	private Set<OWLClass> roots;
	
	private MyVisitOrder class_visit_order;

	protected Taxonomy<OWLClass> statedTaxonomy;

	protected Taxonomy<OWLClass> taxonomy;
	
	protected KnowledgeBase kb;
	
	private int cnt = 0;
	
	private Timer tim_lub;
	private Timer tim_glb;
	private Timer glb_red;
	private Timer glb_sub;
	private Timer lub_sub;
	private Timer c_s_info;
	private Timer def_ord;
	private Timer fet_def_ord;

	// Surface the classify phase timers at INFO without a DEBUG log flood: -Dnci.classify.timers=true.
	private static final boolean TIMERS = Boolean.getBoolean("nci.classify.timers");

	// glb diagnostics: how much of the bottom search is frontier fan-out vs real descent.
	private long glbEntries;
	private long glbComputed;
	private long glbSiblings;

	// Per primitive class, the number of already-classified classes that carry it among their
	// primitives (i.e. that it subsumes). A primitive whose count is 0 has an empty bottom search.
	private final HashMap<OWLClass, Integer> subsumeeCount = new HashMap<OWLClass, Integer>();

	private void startTimer(Timer tim) {
		if (log.isDebugEnabled() || TIMERS) {
			tim.start();
		}
	}
	
	private void stopTimer(Timer tim) {
		if (log.isDebugEnabled() || TIMERS) {
			tim.stop();
		}
	}
	
	class MyVisitOrder extends JGraphBasedDefinitionOrder {

		public MyVisitOrder(KnowledgeBase kb, Comparator<OWLClass> comparator) {
			super(kb, comparator);
		}
		
		public List<OWLClass> getDefinitionOrder() {
			List<OWLClass> domains = new ArrayList<OWLClass>();
			for (OWLClass c : definitionOrder) {
				if (c.isOWLThing() || c.isOWLNothing()) {				

				} else {
					domains.add(c);
				}

			}
			return domains;
		}
		
	}

	public CuratorBuilder() {

	}

	public void setKB(KnowledgeBase kb) {
		this.kb = kb;
	}

	public void setProgressMonitor(ProgressMonitor monitor) {
		if (monitor == null) {
			this.monitor = new SilentProgressMonitor();
		} else {
			this.monitor = monitor;
		}
	}

	private boolean prepared = false;

	public Taxonomy<OWLClass> getTaxonomy() {
		return taxonomy;
	}

	public Taxonomy<OWLClass> getToldTaxonomy() {
		if (!prepared) {
			reset();
			computeStatedInformation();
		}

		return statedTaxonomy;
	}
	
	public Map<OWLClass, Set<OWLClass>> getToldDisjoints() {
		if (!prepared) {
			reset();
			computeStatedInformation();
		}

		return toldDisjoints;
	}

	/**
	 * Classify the KB.
	 */
	public boolean classify() {		
		
		monitor.setProgressTitle("Begin classification process....");
		monitor.taskStarted();
		
		if (log.isDebugEnabled() || TIMERS) {
			tim_lub = kb.timers.createTimer("lub");
			tim_glb = kb.timers.createTimer("glb");
			glb_sub = kb.timers.createTimer("glb_sub");
			lub_sub = kb.timers.createTimer("lub_sub");
			glb_red = kb.timers.createTimer("glb_red");
			c_s_info = kb.timers.createTimer("compute stated info");
			def_ord = kb.timers.createTimer("definition order");
			fet_def_ord = kb.timers.createTimer("fetch def order");
		}
		
		
		
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}		
		
		if (!prepared) {
			reset();
			
			monitor.setProgressTitle("Computing asserted information....");
			
			startTimer(c_s_info);
			computeStatedInformation();
			stopTimer(c_s_info);
						
		}
		
		if (!prepared) {
			monitor.setProgressTitle("Issues with role domains and ranges....");
			monitor.taskFinished();
			throw new InconsistentOntologyException("Issues with role domains and ranges");
			
			
		}
		
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		monitor.setProgressTitle("Topologically sort all terms....");
		
		startTimer(def_ord);		
		class_visit_order = new MyVisitOrder(kb, Comparators.termComparator);
		stopTimer(def_ord);
				
		classifyTaxonomy();
				
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}	
		
		monitor.taskFinished();		

		return true;
	}

	public void reset() {

		classes = new ArrayList<OWLClass>(kb.getClasses());
		
		additions = new HashSet<OWLClass>();
		
		dom_order = new ObjectPropertyDomainOrder(kb, Comparators.termComparator);
		
		domains = dom_order.getDefinitionOrder();
		
		roots = kb.roots();

		toldDisjoints = new HashMap<OWLClass, Set<OWLClass>>();

		taxonomy = new Taxonomy<OWLClass>(null, kb.getThing(), kb.getNothing());

		statedTaxonomy = new Taxonomy<OWLClass>(null, kb.getThing(), kb.getNothing());
		
		prim_map = new HashMap<OWLClass, Set<OWLClass>>();
		
		sub_map = new HashMap<OWLClass, HashMap<OWLClass, Boolean>>();
	}
	
	public void addClasses(Set<OWLClass> cls) {
		additions.addAll(cls);
		// process later
		prepared = false;
	}
	
	

	private void computeStatedInformation() {
		
		Collection<OWLClass> all_cls = kb.getClasses();
		
		for (OWLClass c : all_cls) {
			addNode(c);
		}
		for (OWLClass c : all_cls) {
			Set<OWLClass> pars = kb.getStatedParents(c);
			for (OWLClass par : pars) {
				statedTaxonomy.addSuper(c, par);
			}
			
		}
		
		for (OWLClass dom : domains) {
			if (roots.contains(dom)) {

				Iterator<Object> iter = statedTaxonomy.depthFirstNodes(dom);
				while (iter.hasNext()) {
					@SuppressWarnings("unchecked")
					TaxonomyNode<OWLClass> node = (TaxonomyNode<OWLClass>) iter.next();
					if (node.getDatum("domain") != null) {
						
						// if node already has a domain, and it's different (.ie. not a diamond situation), then it has multiple domains
						// which is an error in Thesaurus as domains are top level roots and disjoint by definition
						if (node.getDatum("domain").equals(dom)) {

						} else {
							node.putDatum("multiple_domains", dom);
						}
						
					} else {
						if (!node.isBottom())
							node.putDatum("domain",  dom);
					}
				}
			}

		}
		
		for (OWLClass c : all_cls) {
			kb.checkLocalRoles(c, statedTaxonomy);
			
		}
		
		if (kb.getBadRoles().isEmpty()) {
			prepared = true;
		} else {
			prepared = false;
			
		}	
		
	}
	
	
	
	
	
	
	private void addNode(OWLClass c) {
		TaxonomyNode<OWLClass> node = statedTaxonomy.addNode(c, false);
		
		if (roots.contains(c)) {
			if (domains.contains(c))
				statedTaxonomy.putDatum(c,  "domain", c);
		}
		
		Set<OWLClass> disj = kb.getDisjoints(c);
		if (!disj.isEmpty()) {
			toldDisjoints.put(c, disj);
		}

		if (kb.isPrimitive(c)) {
			node.putDatum("primitive", true);
		}
	}
	
	private void classifyTaxonomy() {
		
		int size = kb.getClasses().size();
		
		monitor.setProgressTitle("Classifying....");
		
		monitor.setProgressLength(size);
		// set to 0 in case it's run repeatedly
		monitor.setProgress(0);
		cnt = 0;
		glbEntries = glbComputed = glbSiblings = 0;
		subsumeeCount.clear();
		
		startTimer(fet_def_ord);
		List<OWLClass> todo = class_visit_order.getDefinitionOrder();
		stopTimer(fet_def_ord);
		

	for (OWLClass c : todo) {
			// Record c as a classified subsumee of each of its primitive ancestors, so a later-ordered
			// primitive ancestor knows whether its bottom search is empty (count 0) or must run.
			for (OWLClass p : getPrimitives(c)) {
				if (!p.equals(c)) {
					Integer sc = subsumeeCount.get(p);
					subsumeeCount.put(p, (sc == null) ? 1 : sc + 1);
				}
			}
			// check consistent parents
			if (inheritsDisjointClasses(c)) {
				taxonomy.addEquivalentNode(c, taxonomy.bottomNode);
				log.info("Class: " + c.getIRI().getFragment() + " inherits multiple disjoint parents");
				continue;			
			}
			taxonomy.addNode(c,  false);
			
			
			if (classify(c)) {
				// all ok
			} else {

				// failed to classify, add stated structure
				Set<OWLClass> stated_sups = statedTaxonomy.getFlattenedSupers(c, true);
				for (OWLClass ss : stated_sups) {
					taxonomy.addSuper(c, ss);
				}
				

			}
			
			cnt++;
			if ((cnt % 100) == 0) {
				monitor.setProgress(cnt);
			}
		}
				
		monitor.setProgress(size);
		
		if (TIMERS) {
			log.info("glb diagnostics: findGlbs entries=" + glbEntries + " computed=" + glbComputed
					+ " (memo hits=" + (glbEntries - glbComputed) + "), total siblings fetched=" + glbSiblings);
		}
		
	}
	
	

	public boolean classify(OWLClass c) {
		
		lubMemo.clear();
		glbMemo.clear();

		OWLClass dom = (OWLClass) statedTaxonomy.getDatum(c, "domain");
		
		if (dom == null) {
			System.out.println("Some bad classes " + c);
		}

		if (c.equals(dom)) {
			// nothing to do just return
			return false;
		}
		
		List<OWLObjectSomeValuesFrom> roles = kb.getRoles(c);

		startTimer(tim_lub);
		Set<OWLClass> lubs = reduce(findLubs(c, dom, getPrimitives(c), roles));
		stopTimer(tim_lub);

		// NOTE: any glb of the new class will by definition of subsumption be a subclass of all the lubs,
		// so we only need conduct the glbs search starting from any one of them

		Set<OWLClass> glbs = new HashSet<OWLClass>();
		
		// A primitive c subsumes only classes that carry c among their primitives; if none are
		// classified yet (subsumeeCount 0) its bottom search is provably empty and can be skipped.
		boolean needGlb = !lubs.isEmpty();
		if (needGlb && isPrim(c)) {
			Integer sc = subsumeeCount.get(c);
			if (sc == null || sc == 0) {
				needGlb = false;
			}
		}
		if (needGlb) {
			OWLClass root = lubs.iterator().next();
			startTimer(tim_glb);
			glbs = glb_reduce(findGlbs(c, root, getPrimitives(root), kb.getRoles(root)));
			stopTimer(tim_glb);
		}
		

		if ((lubs.size() == 1) && (glbs.size() == 1) && lubs.iterator().next().equals(glbs.iterator().next())) {
			// the new class is eq to an existing one
			taxonomy.remove(c);
			taxonomy.addEquivalentNode(c, taxonomy.getNode(lubs.iterator().next()));
			
		} else { 
			for (OWLClass lub : lubs) {
				taxonomy.addSuper(c, lub);
				cacheSupercOf(lub, c);
				
			}
			for (OWLClass glb : glbs) {
				for (OWLClass l : lubs) {
					if (isSupercOf(l, glb)) {
						taxonomy.removeSuper(glb, l);
						removeCacheSupercOf(l,glb);
					}
				}
				taxonomy.addSuper(glb, c);
				cacheSupercOf(c, glb);
			}
		}
		
		
		return true;


	}
	
	private Set<OWLClass> findLubs(OWLClass c, OWLClass root, Set<OWLClass> prims, List<OWLObjectSomeValuesFrom> roles) {

		Set<OWLClass> memo = lubMemo.get(root);
		if (memo != null) {
			return memo;
		}

		Set<OWLClass> lubs = new HashSet<OWLClass>();


		if (root.isOWLNothing()) {
			lubMemo.put(root, lubs);
			return lubs;
		}

		if (isPrim(root)) {
			//any primitive must be contained in the primitives that are supercs of the new concept
			if (!prims.contains(root)) {
				lubMemo.put(root, lubs);
				return lubs;
			}
		}

		startTimer(lub_sub);
		if (subsumes_p(root, c, prims, roles)) {
			stopTimer(lub_sub);
			// continue down
			Set<OWLClass> subs = taxonomy.getFlattenedSubs(root, true);			

			for (OWLClass sub : subs) {					
				Set<OWLClass> lubs_subs = findLubs(c, sub, prims, roles);
				if (lubs_subs.isEmpty()) {

				} else {
					lubs.addAll(lubs_subs);
				}

			}
			// if no further subsumers then root is the LUB, done
			if (lubs.isEmpty()) {
				lubs.add(root);
			}


		} else {
			stopTimer(lub_sub);
		}

		lubMemo.put(root, lubs);
		return lubs;
	}

	private Set<OWLClass> findGlbs(OWLClass c, OWLClass root, 
			Set<OWLClass> prims, List<OWLObjectSomeValuesFrom> roles) {

		glbEntries++;
		Set<OWLClass> memo = glbMemo.get(root);
		if (memo != null) {
			return memo;
		}
		glbComputed++;

		if (root.isOWLNothing()) {
			glbMemo.put(root, Collections.<OWLClass>emptySet());
			return Collections.emptySet();
		}

		startTimer(glb_sub);
		boolean subsumed = subsumes_p(c, root, prims, roles);
		stopTimer(glb_sub);

		Set<OWLClass> glbs;
		if (subsumed) {
			glbs = Collections.singleton(root);
		} else {
			Set<OWLClass> subs = taxonomy.getFlattenedSubs(root, true);
			glbSiblings += subs.size();
			glbs = null;
			for (OWLClass sub : subs) {
				if (sub.isOWLNothing()) {
					continue;
				}
				Set<OWLClass> glbs_subs = findGlbs(c, sub, getPrimitives(sub), kb.getRoles(sub));
				if (!glbs_subs.isEmpty()) {
					if (glbs == null) {
						glbs = new HashSet<OWLClass>();
					}
					glbs.addAll(glbs_subs);
				}
			}
			if (glbs == null) {
				glbs = Collections.emptySet();
			}
		}

		glbMemo.put(root, glbs);
		return glbs;
	}
	
	private Set<OWLClass> getPrimitives(OWLClass c) {
		if (prim_map.containsKey(c)) {
			return prim_map.get(c);
		} else {
			Set<OWLClass> prims = new HashSet<OWLClass>();
			if (statedTaxonomy.getDatum(c, "primitive") == null) {
				// defined
			} else {
				prims.add(c);
			}
			Set<OWLClass> stated_sups = statedTaxonomy.getFlattenedSupers(c, false);
			for(OWLClass s : stated_sups) {
				if (statedTaxonomy.getDatum(s, "primitive") == null) {
					// defined
				} else {
					prims.add(s);
				}

			}
			prim_map.put(c, prims);
			return prims;
		}
	}
	
	private HashMap<OWLClass, Set<OWLClass>> prim_map = new HashMap<OWLClass, Set<OWLClass>>();

	// LUB/GLB results memoized within a single classify(c): in the multiple-inheritance DAG a node is
	// reachable by many parent paths, and within one classify its lub/glb contribution depends only on
	// the node, so it is computed once. Cleared at the top of classify(c).
	private final HashMap<OWLClass, Set<OWLClass>> lubMemo = new HashMap<OWLClass, Set<OWLClass>>();
	private final HashMap<OWLClass, Set<OWLClass>> glbMemo = new HashMap<OWLClass, Set<OWLClass>>();
	
	private boolean isPrim(OWLClass c) {
		return statedTaxonomy.getDatum(c, "primitive") != null;
	}	
	
	private boolean isStatedSuperc(OWLClass sub, OWLClass sup) {
		return statedTaxonomy.isSubNodeOf2(sub, sup).isTrue();
	}
	
	private boolean inheritsDisjointClasses(OWLClass c) {
		if (statedTaxonomy.getDatum(c, "multiple_domains") != null) {

			OWLClass mul_dom = (OWLClass) statedTaxonomy.getDatum(c, "multiple_domains");
			OWLClass dom = (OWLClass) statedTaxonomy.getDatum(c, "domain");
			if (dom_order.getIgnoreRoots().contains(dom) ||
					dom_order.getIgnoreRoots().contains(mul_dom)) {
				return false;
			} else {
				return true;
			}
					
			
			
		}
		return false;
		
		
	}	
	
	private boolean subsumes_p(OWLClass sup, OWLClass sub, Set<OWLClass> prims, List<OWLObjectSomeValuesFrom> roles_sub) {
		
		if (isPrim(sup)) {
			if (prims.contains(sup)) {
				cacheSupercOf(sup, sub);
				return true;
			} else {
				return false;
			}
		} else {
			Set<OWLClass> sup_prims = getPrimitives(sup);
			for (OWLClass sc : sup_prims) {
				if (!prims.contains(sc))
					return false;					
			}
		}
		
		boolean result = true;		
		
		if (result) {

			List<OWLObjectSomeValuesFrom> roles_sup = kb.getRoles(sup);


			if (!roles_sup.isEmpty() && roles_sub.isEmpty()) {
				return isStatedSuperc(sub, sup);
			}

			for (OWLObjectSomeValuesFrom rol_sup : roles_sup) {
				boolean check = false;
				for (OWLObjectSomeValuesFrom rol_sub : roles_sub) {
						if (role_subsumes_p(rol_sup, rol_sub)) {
							check = true;
							break;					
						}
				}
				if (check) {

				} else {
					// couldn't find a subsumer for a role we are done here
					result = false;
					break;
				}

			}
		}
		
		return result;
		
		
	}
	
	private boolean role_subsumes_p(OWLObjectSomeValuesFrom sup, OWLObjectSomeValuesFrom sub) {
		if (sup.getProperty().equals(sub.getProperty())) {
			OWLClass subc = sub.getFiller().asOWLClass();
			OWLClass supc = sup.getFiller().asOWLClass();
			if (taxonomy.contains(subc) &&
					taxonomy.contains(supc)) {
				return isSupercOf(supc, subc);
				
			} else {
				return isStatedSuperc(subc, supc);
			}
		}
		return false;
	}
	
	private boolean isSupercOf(OWLClass supc, OWLClass subc) {
		
		if (sub_map.containsKey(supc)) {
			if (sub_map.get(supc).containsKey(subc)) {
				return sub_map.get(supc).get(subc);						
			} else {
				boolean ans = taxonomy.isSubNodeOf2(subc, supc).isTrue();
				sub_map.get(supc).put(subc, ans);
				return ans;

			}
		} else {
			
			boolean ans = taxonomy.isSubNodeOf2(subc, supc).isTrue();

			HashMap<OWLClass, Boolean> newmap = new HashMap<OWLClass, Boolean>();
			newmap.put(subc, ans);
			sub_map.put(supc, newmap);

			return ans;
		}

	}
	
	private void cacheSupercOf(OWLClass supc, OWLClass subc) {		

		if (sub_map.containsKey(supc)) {
			sub_map.get(supc).put(subc, true);
			
		} else {
			HashMap<OWLClass, Boolean> newmap = new HashMap<OWLClass, Boolean>();
			newmap.put(subc, true);
			sub_map.put(supc, newmap);

		}

	}
	
	private void removeCacheSupercOf(OWLClass supc, OWLClass subc) {

		if (sub_map.containsKey(supc)) {
			if (sub_map.get(supc).containsKey(subc)) {
				sub_map.get(supc).put(subc, false);
			}

		}
	}
	
	private HashMap<OWLClass, HashMap<OWLClass, Boolean>> sub_map = new HashMap<OWLClass, HashMap<OWLClass, Boolean>>();
	
	Set<OWLClass> reduce(Set<OWLClass> lubs) {
		Set<OWLClass> result = new HashSet<OWLClass>();
		for (OWLClass sup : lubs) {
			boolean found = false;
			for (OWLClass sub : lubs) {
				if (sup.equals(sub)) {
					
				} else {
					if (isSupercOf(sup, sub)) {
						found = true;
					}
				}
			}
			if (!found) {
				result.add(sup);
			}
		}
		return result;
	}
	
	Set<OWLClass> glb_reduce(Set<OWLClass> glbs) {
		startTimer(glb_red);
		Set<OWLClass> result = new HashSet<OWLClass>();
		for (OWLClass sub : glbs) {
			boolean found = false;
			for (OWLClass sup : glbs) {
				if (sup.equals(sub)) {					
					
				} else {
					if (isSupercOf(sup, sub)) {
						found = true;
					}
				}
			}
			if (!found) {
				result.add(sub);
			}
		}
		stopTimer(glb_red);
		return result;
	}	
		
}
