package gov.nih.nci.curator.utils;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLClassExpressionVisitor;
import org.semanticweb.owlapi.model.OWLDataAllValuesFrom;
import org.semanticweb.owlapi.model.OWLDataExactCardinality;
import org.semanticweb.owlapi.model.OWLDataHasValue;
import org.semanticweb.owlapi.model.OWLDataMaxCardinality;
import org.semanticweb.owlapi.model.OWLDataMinCardinality;
import org.semanticweb.owlapi.model.OWLDataSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectComplementOf;
import org.semanticweb.owlapi.model.OWLObjectExactCardinality;
import org.semanticweb.owlapi.model.OWLObjectHasSelf;
import org.semanticweb.owlapi.model.OWLObjectHasValue;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectMaxCardinality;
import org.semanticweb.owlapi.model.OWLObjectMinCardinality;
import org.semanticweb.owlapi.model.OWLObjectOneOf;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;


public class RootVisitor implements OWLClassExpressionVisitor {
	private OWLClass cls = null;
	public OWLClass root = null;
	private OWLOntology ont = null;

	private Set<OWLClass> visited = new HashSet<>();

	public RootVisitor(OWLClass entity, OWLOntology ontology) {
		cls = entity;
		ont = ontology;
	}
	
	public void setEntity(OWLClass e) {
		this.cls = e;
		this.root = null;
		visited.clear();
	}

	@Override
	public void visit(OWLClass ce) {
		if (!ce.equals(cls) && !visited.contains(ce)) {
			visited.add(ce);
			if (ont.getSubClassAxiomsForSubClass(ce).isEmpty()) {
				root = ce;
				return;
			}
		} else {
			for (OWLSubClassOfAxiom ax : ont.getSubClassAxiomsForSubClass(cls)) {
				OWLClassExpression exp = ax.getSuperClass();
				exp.accept(this);				
			}
			for (OWLEquivalentClassesAxiom eax : ont.getEquivalentClassesAxioms(cls)) {
				for (OWLClassExpression exp : eax.getClassExpressions()) {
					if (exp.isOWLClass() && exp.asOWLClass().equals(cls)) {

					} else {
						exp.accept(this);
					}

				}
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

	

}
