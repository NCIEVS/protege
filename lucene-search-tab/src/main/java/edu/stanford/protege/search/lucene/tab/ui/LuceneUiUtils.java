package edu.stanford.protege.search.lucene.tab.ui;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field.Store;
import org.imgscalr.Scalr;
import org.protege.editor.core.ui.error.ErrorLogPanel;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.ui.renderer.OWLModelManagerEntityRenderer;
import org.protege.editor.search.lucene.IndexField;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLProperty;
import org.semanticweb.owlapi.vocab.XSDVocabulary;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Rafael Gonçalves <br>
 * Center for Biomedical Informatics Research <br>
 * Stanford University
 */
public class LuceneUiUtils {

    public static Color
            MATTE_BORDER_COLOR = new Color(220, 220, 220);

    public static final Border
            MATTE_BORDER = new MatteBorder(1, 1, 1, 1, MATTE_BORDER_COLOR),
            EMPTY_BORDER = new EmptyBorder(0, 0, 0, 0);

    public static final String
            CLOSE_ICON_FILENAME = "close.png",
            BACK_ICON_FILENAME = "back.png",
            FORWARD_ICON_FILENAME = "forward.png",
            SEARCH_ICON_FILENAME = "search.png";

    public static Icon getIcon(String filename, int width, int height) {
        BufferedImage icon = null;
        ClassLoader classLoader = LuceneUiUtils.class.getClassLoader();
        try {
            icon = ImageIO.read(checkNotNull(classLoader.getResource(filename)));
        } catch (IOException e) {
            ErrorLogPanel.showErrorDialog(e);
        }
        Image img = Scalr.resize(icon, Scalr.Method.ULTRA_QUALITY, width, height);
        return new ImageIcon(img);
    }

    public static List<OWLEntity> getProperties(OWLEditorKit editorKit) {
        List<OWLEntity> entities = new ArrayList<>();
        OWLOntology ont = editorKit.getModelManager().getActiveOntology();
        entities.addAll(ont.getAnnotationPropertiesInSignature());
        entities.addAll(ont.getObjectPropertiesInSignature());
        entities.addAll(ont.getDataPropertiesInSignature());
        return entities;
    }

    public static int getWidestEntityStringRendering(OWLEditorKit editorKit, Collection<? extends OWLEntity> entities, FontMetrics fontMetrics) {
        int widest = 0;
        OWLModelManagerEntityRenderer renderer = editorKit.getModelManager().getOWLEntityRenderer();
        for(OWLEntity e : entities) {
            String str = renderer.render(e);
            int lineWidth = fontMetrics.stringWidth(str);
            widest = Math.max(widest, lineWidth);
        }
        return widest+60;
    }

    public static Optional<OWLProperty> getPropertyForIri(OWLEditorKit editorKit, IRI iri) {
        List<OWLEntity> properties = getProperties(editorKit);
        for(OWLEntity e : properties) {
            if(e.getIRI().equals(iri)) {
                if(e.isOWLAnnotationProperty()) {
                    return Optional.of(e.asOWLAnnotationProperty());
                } else if(e.isOWLDataProperty()) {
                    return Optional.of(e.asOWLDataProperty());
                } else if(e.isOWLObjectProperty()) {
                    return Optional.of(e.asOWLObjectProperty());
                }
            }
        }
        return Optional.empty();
    }
    
    public static String unescape(String s) {
    	if (s.startsWith("'") &&
    			s.endsWith("'")) {
    		return s.substring(1, s.length() - 1);
    	}
    	return s;
    }
    
    public static Document addPropValToDoc(Document doc, OWLAnnotationValue value) {
    	if (value instanceof OWLLiteral) {
            OWLLiteral literal = (OWLLiteral) value;
            if (literal.getDatatype().getIRI().equals(XSDVocabulary.ANY_URI.getIRI())) {
                doc.add(new StringField(IndexField.ANNOTATION_VALUE_IRI, literal.getLiteral(), Store.YES));
            }
            else {
            	String s = literal.getLiteral();
                doc.add(new TextField(IndexField.ANNOTATION_TEXT, strip(s), Store.YES));
                doc.add(new StringField(IndexField.ANNOTATION_FULL_TEXT, 
                		s.trim().toLowerCase(), Store.YES));
            }
        }
        else if (value instanceof IRI) {
            IRI iri = (IRI) value;
            doc.add(new StringField(IndexField.ANNOTATION_VALUE_IRI, iri.toString(), Store.YES));
        }
    	return doc;
    }

    // Reconstruct a search hit's entity straight from the index when it is not in the loaded
    // signature (lazy model: classes are materialised only on demand). The stored ENTITY_TYPE picks
    // the entity kind; annotation-value hits carry no type, but the only entities not eagerly loaded
    // are classes, so a null/unknown type defaults to a class.
    public static Optional<OWLEntity> entityFromIndex(OWLDataFactory df, String iri, String entityType) {
        if (iri == null) {
            return Optional.empty();
        }
        IRI entityIri = IRI.create(iri);
        if (entityType != null) {
            switch (entityType) {
                case "ObjectProperty":     return Optional.of(df.getOWLObjectProperty(entityIri));
                case "DataProperty":       return Optional.of(df.getOWLDataProperty(entityIri));
                case "AnnotationProperty": return Optional.of(df.getOWLAnnotationProperty(entityIri));
                case "NamedIndividual":    return Optional.of(df.getOWLNamedIndividual(entityIri));
                case "Datatype":           return Optional.of(df.getOWLDatatype(entityIri));
                default:                   break;
            }
        }
        return Optional.of(df.getOWLClass(entityIri));
    }
    
    private static String strip(String s) {
        return s.replaceAll("\\^\\^.*$", "") // remove datatype ending
                .replaceAll("^\"|\"$", "") // remove enclosed quotes
                .replaceAll("<[^>]+>", " ") // trim XML tags
                .replaceAll("\\s+", " ") // trim excessive white spaces
                .replaceAll("\\p{P}", "") // remove punctuation
                .trim();
    }
}
