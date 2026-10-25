package org.protege.editor.owl.client.action;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ScheduledFuture;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import edu.stanford.protege.metaproject.api.ProjectId;
import uk.ac.manchester.cs.owl.owlapi.OWLOntologyManagerImpl;

import org.protege.editor.owl.client.LocalHttpClient;
import org.protege.editor.owl.client.api.exception.ServiceUnavailableException;
import org.protege.editor.owl.client.api.exception.SynchronizationException;
import org.protege.editor.owl.client.event.ClientSessionChangeEvent;
import org.protege.editor.owl.client.event.ClientSessionChangeEvent.EventCategory;
import org.protege.editor.owl.client.event.ClientSessionListener;
import org.protege.editor.owl.client.util.ClientUtils;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.OWLModelManagerImpl;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerChangeEvent;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.server.versioning.ChangeHistoryUtils;
import org.protege.editor.owl.server.versioning.CollectingChangeVisitor;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.VersionedOWLOntology;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AnnotationChange;
import org.semanticweb.owlapi.model.ImportChange;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.MissingOntologyHeaderStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAxiomChange;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
//import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration.MissingOntologyHeaderStrategy;
import org.semanticweb.owlapi.model.UnloadableImportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Josef Hardi <johardi@stanford.edu> <br>
 * @author Timothy Redmond <tredmond@stanford.edu> <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class EnableAutoUpdateAction extends AbstractClientAction implements ClientSessionListener {

    private static final long serialVersionUID = 1098490684799516207L;
    
    private static final Logger log = LoggerFactory.getLogger(EnableAutoUpdateAction.class);

    private Optional<VersionedOWLOntology> activeVersionOntology = Optional.empty();

    private ScheduledFuture<?> autoUpdate;
    private JCheckBoxMenuItem checkBoxMenuItem;

    private OWLModelManagerListener pauseListener = new OWLModelManagerListener() {
        @Override
        public void handleChange(OWLModelManagerChangeEvent event) {
            if (event.isType(EventType.SERVER_PAUSED)) {
                setEnabled(false);
                killAutoUpdate();
            }
            else if (event.isType(EventType.SERVER_RESUMED)) {
                setEnabled(activeVersionOntology.isPresent());
                killAutoUpdate();
                possiblyStartAutoUpdater();
            }
        }
    };

    @Override
    public void initialise() throws Exception {
        super.initialise();
        getClientSession().addListener(this);
        getOWLModelManager().addListener(pauseListener);
    }

    @Override
    public void dispose() throws Exception {
        super.dispose();
        getOWLModelManager().removeListener(pauseListener);
    }

    @Override
    public void handleChange(ClientSessionChangeEvent event) {
        if (event.hasCategory(EventCategory.SWITCH_ONTOLOGY)) {
            activeVersionOntology = Optional.ofNullable(event.getSource().getActiveVersionOntology());
            setEnabled(activeVersionOntology.isPresent());
            // handle case where it's enabled by default
            killAutoUpdate();
            possiblyStartAutoUpdater();
        } else if (event.hasCategory(EventCategory.USER_LOGOUT)) {
            setEnabled(false);
            killAutoUpdate();
        }
    }

    public void setMenuItem(JMenuItem menu) {
        checkBoxMenuItem = (JCheckBoxMenuItem) menu;
        checkBoxMenuItem.setSelected(true);
    }
    
    private void possiblyStartAutoUpdater() {
    	if (checkBoxMenuItem.isSelected()) {
    		if (activeVersionOntology.isPresent()) {
    			// need to check ontology present as sometimes SWITCH_ONTOLOGY event gets here quicker than
    			// the USER_LOGOUT event and the ontolgoy is already gone
    			final VersionedOWLOntology vont = activeVersionOntology.get();
    			String int_s = getClientSession().getActiveClient().getConfig().getServerProperties().get("autoupdate_interval");

    			long interval = 60;
    			if (int_s != null) {
    				interval = Long.parseLong(int_s);    			
    			}
    			autoUpdate = submitPeriodic(new AutoUpdate(getOWLModelManager(), vont), interval);
    		}
    	}
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        killAutoUpdate();
        possiblyStartAutoUpdater();
        
    }

    private void killAutoUpdate() {
        if (autoUpdate != null) {
            autoUpdate.cancel(false);
            autoUpdate = null;            
        }
    }
    

    private class AutoUpdate implements Runnable {

    	private VersionedOWLOntology vont;
    	private OWLOntology ontology;
    	
    	private OWLModelManagerImpl modMan;

    	public AutoUpdate(OWLModelManager modMan, VersionedOWLOntology vont) {
    		this.vont = vont;
    		ontology = vont.getOntology();
    		this.modMan = (OWLModelManagerImpl) modMan;
    	}

    	@Override
    	public void run() {
    		try {
    			OWLOntologyManagerImpl imp = (OWLOntologyManagerImpl) modMan.getOWLOntologyManager();

    			if (!imp.broadcastChanges.get()) {
    				return;
    			}
    			if (modMan.getExplanationManager().getIsRunning()) {
    				return;
    			}
    			log.info("Checking for updates");
    			if (behindServer()) {
    				// Dispatch on the EDT, NOT on this shared single-thread executor: firing
    				// UPDATE_ONTOLOGY runs performUpdate -> submit(DoUpdate).get(), and DoUpdate
    				// is queued on this same thread, so applying it here would self-deadlock.
    				// Conflicts are caught per-entity at commit, so skip while the user has
    				// uncommitted edits -- never replay remote changes onto in-progress work.
    				SwingUtilities.invokeLater(() -> {
    					if (ClientUtils.getUncommittedChanges(getSessionRecorder(),
    							vont.getOntology(), vont.getChangeHistory()).isEmpty()) {
    						getClientSession().fireChangeEvent(EventCategory.UPDATE_ONTOLOGY);
    					}
    				});
    			}
    		} catch (Throwable t) {
    			autoUpdate.cancel(false);
    			autoUpdate = null;
    			checkBoxMenuItem.setSelected(false);
    			getSessionRecorder().startRecording();
    		}
    	}

        private boolean behindServer() {
            try {
            	ProjectId projectId = getClientSession().getActiveProject();
                DocumentRevision remoteHead = LocalHttpClient.current_user().getRemoteHeadRevision(vont, projectId);
                vont.setRemoteHeadRevision(remoteHead);
                return true;
            }
            catch (Exception e) {
                showErrorDialog("Update error", "Error while fetching the remote head revision\n" + e.getMessage(), e);
                return false;
            }
        }

		
    }

    
}
