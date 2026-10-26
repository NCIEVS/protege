package gov.nih.nci.curator.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLClassExpressionVisitor;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectComplementOf;
import org.semanticweb.owlapi.model.OWLObjectExactCardinality;
import org.semanticweb.owlapi.model.OWLObjectHasValue;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectMaxCardinality;
import org.semanticweb.owlapi.model.OWLObjectMinCardinality;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.nih.nci.curator.constraints.DomainResolver;
import gov.nih.nci.curator.owlapi.KnowledgeBase;


public class RolesVisitor implements OWLClassExpressionVisitor {
	private OWLClass cls = null;
	private OWLOntology ont = null;
	
	private boolean local = true;
	private int non_local = 0;
	//private boolean first = true;
	
	public boolean errors = false;
	
	public boolean stated_errors = false;
	
	private static final Logger log = LoggerFactory.getLogger(RolesVisitor.class);

	public List<OWLObjectSomeValuesFrom> roles = new ArrayList<OWLObjectSomeValuesFrom>();
	
	private List<OWLClass> visited = new ArrayList<OWLClass>();
	
	private DomainResolver domainResolver = null;
	
	private KnowledgeBase kb = null;
	
	public Set<OWLAxiom> bad_constructs = new HashSet<OWLAxiom>();
	public Set<OWLAxiom> bad_roles = new HashSet<OWLAxiom>();
	
	private OWLAxiom cur_ax = null;

	public RolesVisitor(OWLClass entity, OWLOntology ontology, KnowledgeBase kb) {
		cls = entity;
		ont = ontology;
		this.kb = kb;
	}
	
	public void setEntity(OWLClass e, boolean loc, DomainResolver resolver) {
		this.cls = e;
		this.domainResolver = resolver;
		
		roles = new ArrayList<OWLObjectSomeValuesFrom>();
		visited = new ArrayList<OWLClass>();
		
		local = loc;
		non_local = 0;
		//first = true;
		
		errors = false;
		stated_errors = false;
		bad_constructs = new HashSet<OWLAxiom>();
		bad_roles = new HashSet<OWLAxiom>();
		cur_ax = null;
	}

	@Override
	public void visit(OWLClass ce) {
		if (visited.contains(ce)) {
			
		} else {
			visited.add(ce);
			if (!ce.equals(cls)) {
				non_local++;
			}
			if (ce.equals(cls) || !local) {
				for (OWLSubClassOfAxiom ax : ont.getSubClassAxiomsForSubClass(ce)) {
					OWLClassExpression exp = ax.getSuperClass();
					cur_ax = ax;
					exp.accept(this);				
				}
				for (OWLEquivalentClassesAxiom eax : ont.getEquivalentClassesAxioms(ce)) {
					cur_ax = eax;
					for (OWLClassExpression exp : eax.getClassExpressions()) {
						if (exp.isOWLClass() && exp.asOWLClass().equals(ce)) {

						} else {
							
							exp.accept(this);
						}

					}
				}
			}
			if (!ce.equals(cls)) {
				non_local--;
			}
		}
		
	}

	@Override
	public void visit(OWLObjectIntersectionOf ce) {
		for (OWLClassExpression exp : ce.getOperands()) {
			exp.accept(this);
			
		}
	}

	@Override
	public void visit(@Nonnull OWLObjectUnionOf ce) {
		
		for (OWLClassExpression exp : ce.getOperands()) {
			exp.accept(this);
			
		}
	}
	
private boolean checkDomRan(OWLClass c, OWLObjectSomeValuesFrom role) {
		
		boolean good = true;
		OWLClass range = kb.getRange(role.getProperty().asOWLObjectProperty());
		OWLClass domain = kb.getDomain(role.getProperty().asOWLObjectProperty());
		
		String role_name = role.getProperty().asOWLObjectProperty().getIRI().getFragment();


		OWLClass filler = role.getFiller().asOWLClass();

		OWLClass c_typ = domainResolver.domainOf(c);
		
		OWLClass filler_typ = domainResolver.domainOf(filler);
		
		OWLClass dom_typ = null;
		
		if (domain != null) {
			dom_typ = domainResolver.domainOf(domain);
		} else {
			log.info("Role: " + role_name + " has no domain ");
		}
		
		String dom_name = (domain == null) ? "no domain" : domain.getIRI().getFragment();
		
		OWLClass range_typ = null;
		
		if (range != null) {
			range_typ = domainResolver.domainOf(range);
		} else {
			log.info("Role: " + role_name + " has no range ");
		}
		
		String ran_name = (range == null) ? "no range" : range.getIRI().getFragment();
		
		if (dom_typ != null && c_typ != null && !c_typ.equals(dom_typ)) {
			

			log.info("Role: " + role_name + " has domain " +
					dom_name + " and can't be used on class " + c.getIRI().getFragment());
			good = false;
		}
		
		if (range_typ != null && filler_typ != null && !filler_typ.equals(range_typ)) {
			log.info("Role: " + role_name + " has range " +
					range.getIRI().getFragment() + " and can't be used with filler " + filler.getIRI().getFragment()
					+ " on class: " + c.getIRI());
			good = false;
		}
		
		return good;
		 			
		 			
	
	}

	

	@Override
	public void visit(@Nonnull OWLObjectSomeValuesFrom ce) {
		if (!roles.contains(ce)) {
			if (domainResolver != null) {
				if (checkDomRan(cls, ce)) {
					roles.add(ce);					
				} else {
					stated_errors = true;
					bad_roles.add(cur_ax);
					cur_ax = null;
				}
			} else {
				roles.add(ce);
			}			
		}
	}
		
		
	
	public void visit(OWLObjectComplementOf ce) {
		processUnsupportedExp(ce);
    }
	
	public void visit(OWLObjectAllValuesFrom ce) {
		processUnsupportedExp(ce);
		
    }
	
	/**
     * visit OWLObjectHasValue type
     *
     * @param ce ce to visit
     */
	public void visit(OWLObjectHasValue ce) {
		processUnsupportedExp(ce);
	}

    /**
     * visit OWLObjectMinCardinality type
     *
     * @param ce ce to visit
     */
    public void visit(OWLObjectMinCardinality ce) {
    	processUnsupportedExp(ce);
    }

    /**
     * visit OWLObjectExactCardinality type
     *
     * @param ce ce to visit
     */
    public void visit(OWLObjectExactCardinality ce) {
    	processUnsupportedExp(ce);
    }

    /**
     * visit OWLObjectMaxCardinality type
     *
     * @param ce ce to visit
     */
    public void visit(OWLObjectMaxCardinality ce) {
    	processUnsupportedExp(ce);
    }
    
    private void processUnsupportedExp(OWLClassExpression exp) {
    	if (non_local > 0) { 
    	} else {
    		log.info("Class: {} Expression Not Supported: {}", cls.getIRI().getFragment(), printExpression(exp));
    		errors = true;    		
    		this.bad_constructs.add(cur_ax);
    		cur_ax = null;

    		doDefault(exp);
    	}
    }
	
	private String printExpression(OWLClassExpression exp) {
		if (exp instanceof OWLObjectComplementOf) {
			return "not " + printExpression(((OWLObjectComplementOf) exp).getOperand());
		} else if (exp instanceof OWLObjectAllValuesFrom) {
			OWLObjectAllValuesFrom oavf = (OWLObjectAllValuesFrom) exp;
			return 
					oavf.getProperty().asOWLObjectProperty().getIRI().getFragment() +
					" only " +
					printExpression(oavf.getFiller());
		} else if (exp instanceof OWLObjectMinCardinality) {
			OWLObjectMinCardinality omc = (OWLObjectMinCardinality) exp;
			return "at least " + omc.getCardinality() + " " +
					omc.getProperty().asOWLObjectProperty().getIRI().getFragment();
					

		} else if (exp instanceof OWLClass) {
			return exp.asOWLClass().getIRI().getFragment();
		}
		return exp.toString();

	}
	
	

}
