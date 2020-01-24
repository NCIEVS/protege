package org.protege.editor.owl.server.versioning;

import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.AnnotationChange;
import org.semanticweb.owlapi.model.ImportChange;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAxiomChange;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeVisitor;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.SetOntologyID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class CollectingChangeVisitor implements OWLOntologyChangeVisitor {

    private SetOntologyID lastOntologyIDChange;
    private Map<OWLImportsDeclaration, ImportChange> lastImportChangeMap;
    private Map<OWLAnnotation, AnnotationChange> lastOntologyAnnotationChangeMap;
    private Map<OWLAxiom, OWLAxiomChange> lastAxiomChangeMap;

    public static CollectingChangeVisitor collectChanges(List<OWLOntologyChange> changes) {
        CollectingChangeVisitor visitor = new CollectingChangeVisitor();
        for (OWLOntologyChange change : changes) {
            change.accept(visitor);
        }
        return visitor;
    }

    private CollectingChangeVisitor() {
        lastImportChangeMap = new TreeMap<>();
        lastOntologyAnnotationChangeMap = new TreeMap<>();
        lastAxiomChangeMap = new HashMap<>();
    }

    public SetOntologyID getLastOntologyIDChange() {
        return lastOntologyIDChange;
    }

    public Map<OWLImportsDeclaration, ImportChange> getLastImportChangeMap() {
        return lastImportChangeMap;
    }

    public Map<OWLAnnotation, AnnotationChange> getLastOntologyAnnotationChangeMap() {
        return lastOntologyAnnotationChangeMap;
    }

    public Map<OWLAxiom, OWLAxiomChange> getLastAxiomChangeMap() {
        return lastAxiomChangeMap;
    }

    @Override
    public void visit(AddAxiom change) {
        lastAxiomChangeMap.put(change.getAxiom(), change);
    }

    @Override
    public void visit(RemoveAxiom change) {
        lastAxiomChangeMap.put(change.getAxiom(), change);
    }

    @Override
    public void visit(SetOntologyID change) {
        lastOntologyIDChange = change;
    }

    @Override
    public void visit(AddImport change) {
        lastImportChangeMap.put(change.getImportDeclaration(), change);
    }

    @Override
    public void visit(RemoveImport change) {
        lastImportChangeMap.put(change.getImportDeclaration(), change);
    }

    @Override
    public void visit(AddOntologyAnnotation change) {
        lastOntologyAnnotationChangeMap.put(change.getAnnotation(), change);
    }

    @Override
    public void visit(RemoveOntologyAnnotation change) {
        lastOntologyAnnotationChangeMap.put(change.getAnnotation(), change);
    }
}