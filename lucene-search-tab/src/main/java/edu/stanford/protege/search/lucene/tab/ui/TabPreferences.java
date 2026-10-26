package edu.stanford.protege.search.lucene.tab.ui;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;
import org.protege.editor.owl.OWLEditorKit;
import edu.stanford.protege.search.lucene.tab.engine.QueryType;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLProperty;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Rafael Gonçalves <br>
 * Center for Biomedical Informatics Research <br>
 * Stanford University
 */
public class TabPreferences {
    private static final String PREFERENCES_KEY = "LuceneTabPreferences";
    private static final String OWL_PROPERTY = "defaultProperty";
    private static final String RESULTS_PER_PAGE = "defaultResultsPerPage";
    private static final String QUERY_TYPE = "defaultQueryType";
    private static final String FILTER_QUERY_TYPE = "defaultFilterQueryType";
    
    private static final String DISPLAY_CLASSES = "displayClasses";
    private static final String DISPLAY_PROPERTIES = "displayProperties";
    private static final String DISPLAY_INDIVIDUALS = "displayIndividuals";
    private static final String DISPLAY_DATATYPES = "displayDatatypes";

    private static final int defaultResultsPerPage = 50;
    private static final QueryType defaultQueryType = QueryType.CONTAINS;
    private static final QueryType defaultFilterQueryType = QueryType.CONTAINS;
    private static final OWLProperty defaultProperty = OWLManager.getOWLDataFactory().getRDFSLabel();
    
    private static final boolean defaultDisplayClasses = true;
    private static final boolean defaultDisplayProperties = false;
    private static final boolean defaultDisplayIndividuals = false;
    private static final boolean defaultDisplayDatatypes = false;

    private static Preferences getPreferences() {
        return PreferencesManager.getInstance().getApplicationPreferences(PREFERENCES_KEY);
    }
    
    public static boolean getDefaultDisplayClasses() {
    	return getPreferences().getBoolean(DISPLAY_CLASSES, defaultDisplayClasses);
    }
    
    public static void setDefaultDisplayClasses(boolean b) {
    	getPreferences().putBoolean(DISPLAY_CLASSES, b);
    }
    
    public static boolean getDefaultDisplayProperties() {
    	return getPreferences().getBoolean(DISPLAY_PROPERTIES, defaultDisplayProperties);
    }
    
    public static void setDefaultDisplayProperties(boolean b) {
    	getPreferences().putBoolean(DISPLAY_PROPERTIES, b);
    }
    
    public static boolean getDefaultDisplayIndividuals() {
    	return getPreferences().getBoolean(DISPLAY_INDIVIDUALS, defaultDisplayIndividuals);
    }
    
    public static void setDefaultDisplayIndividuals(boolean b) {
    	getPreferences().putBoolean(DISPLAY_INDIVIDUALS, b);
    }
    
    public static boolean getDefaultDisplayDatatypes() {
    	return getPreferences().getBoolean(DISPLAY_DATATYPES, defaultDisplayDatatypes);
    }
    
    public static void setDefaultDisplayDatatypes(boolean b) {
    	getPreferences().putBoolean(DISPLAY_DATATYPES, b);
    }

    public static QueryType getDefaultQueryType() {
        String type = getPreferences().getString(QUERY_TYPE, defaultQueryType.getName());
        return QueryType.valueOf(type);
    }
    
    public static QueryType getDefaultFilterQueryType() {
        String type = getPreferences().getString(FILTER_QUERY_TYPE, defaultFilterQueryType.getName());
        return QueryType.valueOf(type);
    }

    public static int getMaximumResultsPerPage() {
        return getPreferences().getInt(RESULTS_PER_PAGE, defaultResultsPerPage);
    }

    public static OWLProperty getDefaultProperty(OWLEditorKit editorKit) {
        String propIri = getPreferences().getString(OWL_PROPERTY, defaultProperty.getIRI().toString());
        Optional<OWLProperty> propOpt = LuceneUiUtils.getPropertyForIri(editorKit, IRI.create(propIri));
        if(propOpt.isPresent()) {
            return propOpt.get();
        }
        return defaultProperty;
    }

    public static void setMaximumResultsPerPage(int nrResultsPerPage) {
        getPreferences().putInt(RESULTS_PER_PAGE, checkNotNull(nrResultsPerPage));
    }

    public static void setDefaultProperty(IRI defaultPropertyIri) {
        checkNotNull(defaultPropertyIri);
        getPreferences().putString(OWL_PROPERTY, defaultPropertyIri.toString());
    }

    public static void setDefaultQueryType(QueryType queryType) {
        checkNotNull(queryType);
        getPreferences().putString(QUERY_TYPE, queryType.getName());
    }
    
    public static void setDefaultFilterQueryType(QueryType queryType) {
        checkNotNull(queryType);
        getPreferences().putString(FILTER_QUERY_TYPE, queryType.getName());
    }
}
