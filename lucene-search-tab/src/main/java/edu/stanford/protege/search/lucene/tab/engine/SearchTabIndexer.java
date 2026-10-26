package edu.stanford.protege.search.lucene.tab.engine;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.protege.editor.search.lucene.AbstractLuceneIndexer;
import org.protege.editor.search.lucene.IndexDelegator;
import org.protege.editor.search.lucene.IndexField;
import org.protege.editor.search.lucene.IndexItemsCollector;
import org.protege.editor.search.lucene.AbstractLuceneIndexer.IndexProgressListener;
import org.semanticweb.owlapi.model.HasFiller;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAsymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLBooleanClassExpression;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLDataPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDifferentIndividualsAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalDataPropertyAxiom;
import org.semanticweb.owlapi.model.OWLFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLHasKeyAxiom;
import org.semanticweb.owlapi.model.OWLInverseFunctionalObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLInverseObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLIrreflexiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLNegativeDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLNegativeObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectComplementOf;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLProperty;
import org.semanticweb.owlapi.model.OWLReflexiveObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLRestriction;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubPropertyChainOfAxiom;
import org.semanticweb.owlapi.model.OWLSymmetricObjectPropertyAxiom;
import org.semanticweb.owlapi.model.OWLTransitiveObjectPropertyAxiom;
import org.semanticweb.owlapi.vocab.XSDVocabulary;

import edu.stanford.protege.search.lucene.tab.ui.LuceneUiUtils;

/**
 * Author: Josef Hardi <josef.hardi@stanford.edu><br>
 * Stanford University<br>
 * Bio-Medical Informatics Research Group<br>
 * Date: 04/11/2015
 */
public class SearchTabIndexer extends AbstractLuceneIndexer {

    private final IndexRenderingContext context;
    
    

    public SearchTabIndexer(OWLEditorKit editorKit) {
        this(fromEditorKit(editorKit));
    }

    public SearchTabIndexer(IndexRenderingContext context) {
        super(new ClassicWhitespaceAnalyzer());
        this.context = context;
    }

    // Adapt the editor kit to the two hooks the indexer needs (client side).
    private static IndexRenderingContext fromEditorKit(OWLEditorKit editorKit) {
        final OWLEntityFinder entityFinder = editorKit.getOWLModelManager().getOWLEntityFinder();
        final OWLModelManager objectRenderer = editorKit.getOWLModelManager();
        return new IndexRenderingContext() {
            @Override
            public String getRendering(OWLObject object) {
                return objectRenderer.getRendering(object);
            }
            @Override
            public Optional<OWLEntity> getEntity(IRI iri) {
                return entityFinder.getEntities(iri).stream().findFirst();
            }
        };
    }

    @Override
    public IndexItemsCollector getIndexItemsCollector(IndexDelegator delegator, IndexProgressListener listener) {
    	

        return new IndexItemsCollector() {

            private Document doc = new Document();
            
            private IndexDelegator delg = delegator;
            private IndexProgressListener listen = listener;

            private void visAnnAxes(OWLEntity ent, OWLOntology ont) {
            	ent.accept(this);
            	ont.annotationAssertionAxioms(ent.getIRI()).forEach(ax ->
            	ax.accept(this));
            	
            }

            @Override
            public void visit(OWLOntology ontology) {
            	
            	// estimate for current thesaurus, don't know the total docs offhand
            	// this is only needed to accomodate current progress bar semantics
            	delg.setTotalDocsSize(4500000);
            	ontology.unsortedSignature().forEach(e -> visAnnAxes(e, ontology)); 
            		
            	ontology.logicalAxioms().forEach(ax -> ax.accept(this));
                
            }

            @Override
            public void visit(OWLClass cls) {
            	doc = new Document();
                doc.add(new TextField(IndexField.ENTITY_IRI, getEntityId(cls), Store.YES));
                doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(cls), Store.YES));
                doc.add(new StringField(IndexField.ENTITY_TYPE, getType(cls), Store.YES));
                
                delg.buildIndex(doc, listen);
            }

            @Override
            public void visit(OWLObjectProperty property) {
                doc = new Document();
                doc.add(new TextField(IndexField.ENTITY_IRI, getEntityId(property), Store.YES));
                doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(property), Store.YES));
                doc.add(new StringField(IndexField.ENTITY_TYPE, getType(property), Store.YES));
               
                delg.buildIndex(doc, listen);
            }

            public void visit(OWLDataProperty property) {
                doc = new Document();
                doc.add(new TextField(IndexField.ENTITY_IRI, getEntityId(property), Store.YES));
                doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(property), Store.YES));
                doc.add(new StringField(IndexField.ENTITY_TYPE, getType(property), Store.YES));
                
                delg.buildIndex(doc, listen);
            }

            public void visit(OWLNamedIndividual individual) {
                doc = new Document();
                doc.add(new TextField(IndexField.ENTITY_IRI, getEntityId(individual), Store.YES));
                doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(individual), Store.YES));
                doc.add(new StringField(IndexField.ENTITY_TYPE, getType(individual), Store.YES));
                
                delg.buildIndex(doc, listen);
            }

            public void visit(OWLAnnotationProperty property) {
                doc = new Document();
                doc.add(new TextField(IndexField.ENTITY_IRI, getEntityId(property), Store.YES));
                doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(property), Store.YES));
                doc.add(new StringField(IndexField.ENTITY_TYPE, getType(property), Store.YES));
                
                delg.buildIndex(doc, listen);
            }

            @Override
            public void visit(OWLAnnotationAssertionAxiom axiom) {
            	if (axiom.getSubject() instanceof IRI) {
            		doc = new Document();
            		Optional<OWLEntity> opt_entity = getOWLEntity((IRI) axiom.getSubject());
            		if (opt_entity.isPresent()) {
            			OWLEntity entity = opt_entity.get();
            			doc.add(new StringField(IndexField.ENTITY_IRI, getEntityId(entity), Store.YES));
            			//doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(entity), Store.YES));
            			doc.add(new StringField(IndexField.ANNOTATION_IRI, getEntityId(axiom.getProperty()), Store.YES));
            			//doc.add(new StringField(IndexField.ANNOTATION_DISPLAY_NAME, getDisplayName(axiom.getProperty()), Store.YES));
            			OWLAnnotationValue value = axiom.getAnnotation().getValue();

            			doc = LuceneUiUtils.addPropValToDoc(doc, value);

            			
            			delg.buildIndex(doc, listen);
            			// add annotations on annotations
            			for (OWLAnnotation ann : axiom.getAnnotations()) {
            				doc = new Document();
            				doc.add(new StringField(IndexField.ENTITY_IRI, getEntityId(entity), Store.YES));
            				//doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(entity), Store.YES));

            				doc.add(new StringField(IndexField.ANNOTATION_IRI, getEntityId(ann.getProperty()), Store.YES));
            				//doc.add(new TextField(IndexField.ANNOTATION_DISPLAY_NAME, getDisplayName(ann.getProperty()), Store.YES));

            				value = ann.getValue();
            				doc = LuceneUiUtils.addPropValToDoc(doc, value);

            				
            				delg.buildIndex(doc, listen);

            			}
            		} else {
            			System.out.println("The bad entity: " + axiom);
            		}
            	}
            }
            
            

            @Override
            public void visit(OWLSubClassOfAxiom axiom) {
                visitLogicalAxiom(axiom);
                if (!(axiom.getSubClass() instanceof OWLClass)) {
                    return;
                }
                OWLClass cls = axiom.getSubClass().asOWLClass();
                if (axiom.getSuperClass() instanceof OWLRestriction) {
                    OWLRestriction restriction = (OWLRestriction) axiom.getSuperClass();
                    visitObjectRestriction(cls, restriction);
                }
                else if (axiom.getSuperClass() instanceof OWLBooleanClassExpression) {
                    OWLBooleanClassExpression expr = (OWLBooleanClassExpression) axiom.getSuperClass();
                    if (expr instanceof OWLObjectIntersectionOf) {
                        for (OWLClassExpression ce : expr.asConjunctSet()) {
                            if (ce instanceof OWLRestriction) {
                                visitObjectRestriction(cls, (OWLRestriction) ce);
                            }
                        }
                    }
                    else if (expr instanceof OWLObjectUnionOf) {
                        for (OWLClassExpression ce : expr.asDisjunctSet()) {
                            if (ce instanceof OWLRestriction) {
                                visitObjectRestriction(cls, (OWLRestriction) ce);
                            }
                        }
                    }
                    else if (expr instanceof OWLObjectComplementOf) {
                        OWLClassExpression ce = ((OWLObjectComplementOf) expr).getObjectComplementOf();
                        if (ce instanceof OWLRestriction) {
                            visitObjectRestriction(cls, (OWLRestriction) ce);
                        }
                    }
                }
            }

            @Override
            public void visit(OWLEquivalentClassesAxiom axiom) {
                visitLogicalAxiom(axiom);
                Collection<OWLSubClassOfAxiom> subClassAxioms = axiom.asOWLSubClassOfAxioms();
                for (OWLSubClassOfAxiom sc : subClassAxioms) {
                    sc.accept(this);
                }
            }

            private void visitObjectRestriction(OWLClass subclass, OWLRestriction restriction) {
                if (restriction.getProperty() instanceof OWLProperty) {
                    OWLProperty property = (OWLProperty) restriction.getProperty();
                    if (restriction instanceof HasFiller<?>) {
                        HasFiller<?> restrictionWithFiller = (HasFiller<?>) restriction;
                        doc = new Document();
                        if (restrictionWithFiller.getFiller() instanceof OWLClass) {
                            OWLClass filler = (OWLClass) restrictionWithFiller.getFiller();
                            doc.add(new StringField(IndexField.ENTITY_IRI, getEntityId(subclass), Store.YES));
                            doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(subclass), Store.YES));
                            doc.add(new StringField(IndexField.OBJECT_PROPERTY_IRI, getEntityId(property), Store.YES));
                            doc.add(new TextField(IndexField.OBJECT_PROPERTY_DISPLAY_NAME, getDisplayName(property), Store.YES));
                            doc.add(new StringField(IndexField.FILLER_IRI, getEntityId(filler), Store.YES));
                            doc.add(new TextField(IndexField.FILLER_DISPLAY_NAME, getDisplayName(filler), Store.YES));
                        }
                        else {
                            doc.add(new StringField(IndexField.ENTITY_IRI, getEntityId(subclass), Store.YES));
                            doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(subclass), Store.YES));
                            doc.add(new StringField(IndexField.OBJECT_PROPERTY_IRI, getEntityId(property), Store.YES));
                            doc.add(new TextField(IndexField.OBJECT_PROPERTY_DISPLAY_NAME, getDisplayName(property), Store.YES));
                            doc.add(new StringField(IndexField.FILLER_IRI, "", Store.NO));
                            doc.add(new TextField(IndexField.FILLER_DISPLAY_NAME, "", Store.NO));
                        }
                        delg.buildIndex(doc, listen);
                    }
                }
            }

            //@formatter:off
            @Override public void visit(OWLNegativeObjectPropertyAssertionAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLAsymmetricObjectPropertyAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLReflexiveObjectPropertyAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLDisjointClassesAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLDataPropertyDomainAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLObjectPropertyDomainAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLEquivalentObjectPropertiesAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLNegativeDataPropertyAssertionAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLDifferentIndividualsAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLDisjointDataPropertiesAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLDisjointObjectPropertiesAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLObjectPropertyRangeAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLObjectPropertyAssertionAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLFunctionalObjectPropertyAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLSubObjectPropertyOfAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLDisjointUnionAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLSymmetricObjectPropertyAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLDataPropertyRangeAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLFunctionalDataPropertyAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLEquivalentDataPropertiesAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLClassAssertionAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLDataPropertyAssertionAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLTransitiveObjectPropertyAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLIrreflexiveObjectPropertyAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLSubDataPropertyOfAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLInverseFunctionalObjectPropertyAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLSameIndividualAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLSubPropertyChainOfAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLInverseObjectPropertiesAxiom axiom) { visitLogicalAxiom(axiom); }
            @Override public void visit(OWLHasKeyAxiom axiom) { visitLogicalAxiom(axiom); }

            //@formatter:on
            private void visitLogicalAxiom(OWLAxiom axiom) {
                doc = new Document();
                OWLObject subject = new org.semanticweb.owlapi.util.AxiomSubjectProviderEx().getSubject(axiom);
                if (subject instanceof OWLEntity) {
                    OWLEntity entity = (OWLEntity) subject;
                    doc.add(new StringField(IndexField.ENTITY_IRI, getEntityId(entity), Store.YES));
                    doc.add(new TextField(IndexField.DISPLAY_NAME, getDisplayName(entity), Store.YES));
                    doc.add(new TextField(IndexField.AXIOM_DISPLAY_NAME, getDisplayName(axiom), Store.YES));
                    doc.add(new StringField(IndexField.AXIOM_TYPE, getType(axiom), Store.YES));
                    
                    delg.buildIndex(doc, listen);
                }
            }

            /*
             * Utility methods
             */

            private Optional<OWLEntity> getOWLEntity(IRI identifier) {
                return context.getEntity(identifier);
            }

            private String getEntityId(OWLEntity entity) {
                return entity.getIRI().toString();
            }

            private String getType(OWLObject object) {
                if (object instanceof OWLEntity) {
                    return ((OWLEntity) object).getEntityType().getName();
                }
                else if (object instanceof OWLAxiom) {
                    return ((OWLAxiom) object).getAxiomType().getName();
                }
                return "(Unknown type)";
            }

            private String getDisplayName(OWLObject object) {
                return context.getRendering(object);
            }

            
        };
    }
}
