package gov.nih.nci.curator;

import java.util.EnumSet;
import java.util.Objects;

import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;
import org.protege.editor.owl.model.inference.ReasonerPreferences.OptionalInferenceTask;
import org.semanticweb.owlapi.reasoner.InferenceType;

/**
 *
 * @author Evren Sirin
 */
public class CuratorReasonerPreferences {
    private static String KEY = "gov.nih.nci.curator";
    private static CuratorReasonerPreferences INSTANCE;

    public static synchronized CuratorReasonerPreferences getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CuratorReasonerPreferences();
        }
        return INSTANCE;
    }
    
    public enum OptionalEditChecksTask {
        // Class Property Inferences
        CHECK_REDUNDANT_PARENT(false),
        CHECK_DISJOINT_CLASSES(false),
    	CHECK_UNSUPPORTED_CONSTRUCTS(false),
    	CHECK_SUBCLASS_FULL_DEFINED(false),
    	CHECK_BAD_RETREE(false);

        private boolean enabledByDefault;

        private OptionalEditChecksTask(boolean enabledByDefault) {
            this.enabledByDefault = enabledByDefault;
        }

        public String getKey() {
            return toString();
        }

        public boolean getEnabledByDefault() {
            return enabledByDefault;
        }

    }


    private final Preferences prefs = PreferencesManager.getInstance().getApplicationPreferences(KEY);

    private int explanationCount;

    private boolean updated = false;
    
    private EnumSet<OptionalEditChecksTask>           enabled       = EnumSet.noneOf(OptionalEditChecksTask.class);

    private CuratorReasonerPreferences() {
			explanationCount = prefs.getInt("explanationCount", 0);
			
			for (OptionalEditChecksTask task : OptionalEditChecksTask.values()) {
	        	if (prefs.getBoolean(task.getKey(), task.getEnabledByDefault())) {
	        		enabled.add(task);
	        	}
	        }
		}

	public boolean save() {
		if (!updated) {
			return false;
		}

		updated = false;
		
		prefs.putInt("explanationCount", explanationCount);
		
		for (OptionalEditChecksTask task : OptionalEditChecksTask.values()) {
            prefs.putBoolean(task.getKey(), enabled.contains(task));
        }

		return true;
	}

    private void update(Object oldValue, Object newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            updated = true;
        }
    }


    public int getExplanationCount() {
        return explanationCount;
    }

    public void setExplanationCount(int theExplanationCount) {
        update(explanationCount, theExplanationCount);
        explanationCount = theExplanationCount;
    }
    
    public void setEnabled(OptionalEditChecksTask task, boolean isEnabled) {
    	update(isEnabled, enabled.contains(task));
    	if (isEnabled) {
    		enabled.add(task);
    	}
    	else {
    		enabled.remove(task);
    	}
    }
    
    public boolean isEnabled(OptionalEditChecksTask task) {
        return enabled.contains(task);
    }
    
    
}