package edu.stanford.protege.search.lucene.tab.ui;

import org.semanticweb.owlapi.model.*;

public class OwlEntityComboBoxChangeHandler implements OWLOntologyChangeVisitor {

    private OwlEntityComboBox comboBox;

    public OwlEntityComboBoxChangeHandler(OwlEntityComboBox comboBox) {
        this.comboBox = comboBox;
    }

    @Override
    public void visit(RemoveAxiom change) {
        OWLAxiom axiom = change.getAxiom();
        if (axiom instanceof OWLDeclarationAxiom) {
            OWLEntity entity = ((OWLDeclarationAxiom) axiom).getEntity();
            if (entity instanceof OWLProperty) {
                comboBox.removeItem(entity);
            }
        }
    }

    @Override
    public void visit(AddAxiom change) {
        OWLAxiom axiom = change.getAxiom();
        if (axiom instanceof OWLDeclarationAxiom) {
            OWLEntity entity = ((OWLDeclarationAxiom) axiom).getEntity();
            if (entity instanceof OWLProperty) {
                comboBox.addItem(entity);
            }
        }
    }

	@Override
	public void visit(ReplaceOntologyPrefixMappingChange replaceOntologyPrefixMappingChange) {
		// TODO Auto-generated method stub
		
	}
}
