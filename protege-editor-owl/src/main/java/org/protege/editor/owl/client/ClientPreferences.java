package org.protege.editor.owl.client;

import java.util.ArrayList;
import java.util.List;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;

/**
 * @author Timothy Redmond <tredmond@stanford.edu> <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class ClientPreferences {

    private static ClientPreferences instance;

    private static final String CLIENT_PREFERENCES = "org.protege.editor.owl.client";

    private static final String CURRENT_USERNAME = "CURRENT_USERNAME";

    private static final String SERVER_LOCATIONS = "SERVER_LOCATIONS";

    private static final String LAST_SERVER_LOCATION = "LAST_SERVER_LOCATION";
    
    private static final String PREF_TAB_NAMES = "PREFERRED_TAB_NAMES";
    
    public static final String SERVER_REVISIONS_PROCESSED = "SERVER_REVISIONS_PROCESSED";

    public static synchronized ClientPreferences getInstance() {
        if (instance == null) {
            instance = new ClientPreferences();
        }
        return instance;
    }

    public String getCurrentUsername() {
        Preferences prefs = getPreferences();
        return prefs.getString(CURRENT_USERNAME, null);
    }

    public void setCurrentUsername(String username) {
        Preferences prefs = getPreferences();
        prefs.putString(CURRENT_USERNAME, username);
    }

    public List<String> getServerLocations() {
        ArrayList<String> serverLocations = new ArrayList<String>();
        serverLocations
                .addAll(getPreferences().getStringList(SERVER_LOCATIONS, new ArrayList<String>()));
        return serverLocations;
    }

    public void setServerLocations(ArrayList<String> serverLocations) {
        Preferences prefs = getPreferences();
        prefs.putStringList(SERVER_LOCATIONS, serverLocations);
    }

    public String getLastServerLocation() {
        Preferences prefs = getPreferences();
        return prefs.getString(LAST_SERVER_LOCATION, null);
    }

    public void setLastServerLocation(String lastServerLocation) {
        Preferences prefs = getPreferences();
        prefs.putString(LAST_SERVER_LOCATION, lastServerLocation);
    }

    public List<String> getPreferredTabNames() {
    	Preferences prefs = getPreferences();
    	return prefs.getStringList(PREF_TAB_NAMES, new ArrayList<String>());
    }
    
    public void setPreferedTabNames(List<String> prefTabNames) {
    	Preferences prefs = getPreferences();
    	prefs.putStringList(PREF_TAB_NAMES, prefTabNames);
    }
    
    
    // The last server revision the local search index reflects, kept per project so switching
    // projects (or a server squash that resets a project's revisions) is tracked independently.
    public int getNoServerRevisionsIndexed(String projectId) {
        return getPreferences().getInt(SERVER_REVISIONS_PROCESSED + ":" + projectId, 0);
    }
    
    public void setNoServerRevisionsIndexed(String projectId, int no_changes) {
        getPreferences().putInt(SERVER_REVISIONS_PROCESSED + ":" + projectId, no_changes);
    }
    
    protected static Preferences getPreferences() {
        return PreferencesManager.getInstance().getApplicationPreferences(CLIENT_PREFERENCES);
    }
}
