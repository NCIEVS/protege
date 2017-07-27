package org.protege.editor.owl.ui.frame;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.inference.VacuousAxiomVisitor;
import org.protege.editor.owl.ui.editor.OWLObjectEditor;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
//import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
/*
 * Copyright (C) 2007, University of Manchester
 *
 *
 */


/**
 * Author: Matthew Horridge<br>
 * The University Of Manchester<br>
 * Bio-Health Informatics Group<br>
 * Date: 14-Oct-2007<br><br>
 */
public class InferredAxiomsFrameSection extends AbstractOWLFrameSection<OWLOntology, OWLAxiom, OWLAxiom>{

    public InferredAxiomsFrameSection(OWLEditorKit editorKit, OWLFrame<? extends OWLOntology> frame) {
        super(editorKit, "Inferred axioms", "Inferred axiom", frame);
    }


    protected void clear() {

    }


    protected OWLAxiom createAxiom(OWLAxiom object) {
        return object;
    }


    public OWLObjectEditor<OWLAxiom> getObjectEditor() {
    	return null;
        
    }


    protected void refill(OWLOntology ontology) {
    }


    @SuppressWarnings("rawtypes")
	protected void refillInferred() {
    	try {
            OWLOntologyManager man = OWLManager.createOWLOntologyManager();
            OWLOntology inferredOnt = man.createOntology(IRI.create("http://another.com/ontology" + System.currentTimeMillis()));
            InferredOntologyGenerator ontGen = new InferredOntologyGenerator(getOWLModelManager().getReasoner(), new ArrayList<>());
            ontGen.addGenerator(new InferredSubClassAxiomGenerator());
           
            ontGen.fillOntology(man.getOWLDataFactory(), inferredOnt);


            for (OWLAxiom ax : new TreeSet<>(inferredOnt.getAxioms())) {
                boolean add = true;
                if (getOWLModelManager().getActiveOntology().containsAxiom(ax)) {
                	add = false;
                }
                
                if (add) {
                	doctorAndAdd(new InferredAxiomsFrameSectionRow(getOWLEditorKit(), this, null, getRootObject(), ax));
                	
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void doctorAndAdd(InferredAxiomsFrameSectionRow row) {
    	if (row.isInferred() && 
    			(VacuousAxiomVisitor.isVacuousAxiom(row.getAxiom()) || VacuousAxiomVisitor.involvesInverseSquared(row.getAxiom()))) {
    		return;
    	}
    	if (isInconsistent(row.getAxiom())) {
    		row.setEditingHint(" - needs repair");
    		addInferredRowIfNontrivial(row);
    	} else {
    		row.setEditingHint(" - Add Axiom");
    		addInferredRowIfNontrivial(row);
    		// now possibly make a new remove axiom for each redudant link
    		List<OWLAxiom> supsToRemove = findCommonParents(row.getAxiom());
    		for (OWLAxiom ax : supsToRemove) {
    			InferredAxiomsFrameSectionRow remRow = 
    					new InferredAxiomsFrameSectionRow(getOWLEditorKit(), this, null, getRootObject(), ax);
    			remRow.setEditingHint(" - Remove Axiom");
    			addInferredRowIfNontrivial(remRow);
    		}
    	}
    }
    
    private boolean isInconsistent(OWLAxiom ax) {
    	if (ax.isOfType(AxiomType.SUBCLASS_OF)) {
    		OWLSubClassOfAxiom subax = (OWLSubClassOfAxiom) ax;
    		return subax.getSuperClass().isOWLNothing();    
    	}
    	return false;
    }
    
    private List<OWLClass> getAssertedParents(OWLClass cls) {
    	List<OWLClass> parents = new ArrayList<OWLClass>();
    	Set<OWLSubClassOfAxiom> axs = 
    			getOWLModelManager().getActiveOntology().getSubClassAxiomsForSubClass(cls);
    	
    	for(OWLSubClassOfAxiom ax : axs) {
    		if (!ax.getSuperClass().isAnonymous()) {
    			parents.add(ax.getSuperClass().asOWLClass());
    		}
    	}
    	
    	return parents;
    }
    
    private List<OWLAxiom> findCommonParents(OWLAxiom newAxiom) {
    	List<OWLAxiom> results = new ArrayList<OWLAxiom>();
    	if (newAxiom.isOfType(AxiomType.SUBCLASS_OF)) {
    		OWLSubClassOfAxiom subax = (OWLSubClassOfAxiom) newAxiom;
    		OWLClass cls = subax.getSubClass().asOWLClass();
    		if (!subax.getSuperClass().isAnonymous()) {
    			OWLClass newParent = subax.getSuperClass().asOWLClass();
    			List<OWLClass> assertedParents = getAssertedParents(cls);
    			List<OWLClass> newParentAssertedParents = getAssertedParents(newParent);
    			for (OWLClass ap : assertedParents) {
    				if (newParentAssertedParents.contains(ap)) {
    					OWLAxiom newAx = 
    							getOWLModelManager().getOWLDataFactory().getOWLSubClassOfAxiom(cls, ap);
    					results.add(newAx);
    				}
    			}    			
    		}
    	}
    	return results;
    }

    @Override
    protected boolean isResettingChange(OWLOntologyChange change) {
        return false;
    }


    public Comparator<OWLFrameSectionRow<OWLOntology, OWLAxiom, OWLAxiom>> getRowComparator() {
        return (o1, o2) -> {

            int diff = o1.getAxiom().compareTo(o2.getAxiom());
            if(diff != 0) {
                return diff;
            }
            else if (o1.getOntology() == null  && o2.getOntology() == null) {
                return 0;
            }
            else if (o1.getOntology() == null) {
                return -1;
            }
            else if (o2.getOntology() == null) {
                return +1;
            }
            else {
                return o1.getOntology().compareTo(o2.getOntology());
            }
        };
    }
}
