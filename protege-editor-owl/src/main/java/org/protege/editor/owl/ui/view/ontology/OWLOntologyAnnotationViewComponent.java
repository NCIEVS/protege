package org.protege.editor.owl.ui.view.ontology;

import org.protege.editor.core.ui.error.ErrorLogPanel;
import org.protege.editor.core.ui.util.AugmentedJTextField;
import org.protege.editor.core.ui.util.JOptionPaneEx;
import org.protege.editor.core.ui.util.LinkLabel;
import org.protege.editor.core.ui.workspace.TabbedWorkspace;
import org.protege.editor.owl.client.ClientSession;
import org.protege.editor.owl.client.SessionRecorder;
import org.protege.editor.owl.client.event.CommitOperationEvent;
import org.protege.editor.owl.client.ui.CommitDialogPanel;
import org.protege.editor.owl.client.util.ClientUtils;
import org.protege.editor.owl.model.OntologyAnnotationContainer;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.event.OWLModelManagerChangeEvent;
import org.protege.editor.owl.model.event.OWLModelManagerListener;
import org.protege.editor.owl.model.refactor.ontology.EntityIRIUpdaterOntologyChangeStrategy;
import org.protege.editor.owl.server.api.CommitBundle;
import org.protege.editor.owl.server.policy.CommitBundleImpl;
import org.protege.editor.owl.server.versioning.Commit;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.VersionedOWLOntology;
import org.protege.editor.owl.ui.UIHelper;
import org.protege.editor.owl.ui.ontology.annotation.OWLOntologyAnnotationList;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;


/**
 * Author: Matthew Horridge<br>
 * The University Of Manchester<br>
 * Bio-Health Informatics Group<br>
 * Date: 04-Feb-2007<br><br>
 */
public class OWLOntologyAnnotationViewComponent extends AbstractOWLViewComponent {


    private static final long serialVersionUID = 1252038674995535772L;

    public static final String ONTOLOGY_IRI_FIELD_LABEL = "Ontology IRI";

    public static final String ONTOLOGY_VERSION_IRI_FIELD_LABEL = "Ontology Version IRI";


    public static final URI ONTOLOGY_IRI_DOCUMENTATION = URI.create("http://www.w3.org/TR/2009/REC-owl2-syntax-20091027/#Ontology_IRI_and_Version_IRI");

    public static final URI VERSION_IRI_DOCUMENTATION = URI.create("http://www.w3.org/TR/2009/REC-owl2-syntax-20091027/#Versioning_of_OWL_2_Ontologies");


    private OWLModelManagerListener listener;

    private OWLOntologyAnnotationList list;

    private final AugmentedJTextField ontologyIRIField = new AugmentedJTextField("e.g http://www.example.com/ontologies/myontology");

    private final AugmentedJTextField ontologyVersionIRIField = new AugmentedJTextField("e.g. http://www.example.com/ontologies/myontology/1.0.0");

    private JButton commitBtn = new JButton("Commit");
    
    private boolean updatingViewFromModel = false;

    private boolean updatingModelFromView = false;

    /**
     * The IRI of the ontology when the ontology IRI field gets the focus.
     */
    private OWLOntologyID initialOntologyID = null;

    private boolean ontologyIRIShowing = false;
    
    private boolean read_only = false;


    private final OWLOntologyChangeListener ontologyChangeListener = owlOntologyChanges -> handleOntologyChanges(owlOntologyChanges);

    private Optional<VersionedOWLOntology> activeVersionOntology = Optional.empty();
    
    protected void initialiseOWLView() throws Exception {
    	if (((TabbedWorkspace) getWorkspace()).isReadOnly(this.getView().getPlugin())) {
    		read_only = true;
    	}
        setLayout(new BorderLayout());

        setLayout(new BorderLayout());
        JPanel ontologyIRIPanel = new JPanel(new GridBagLayout());
        add(ontologyIRIPanel, BorderLayout.NORTH);
        Insets insets = new Insets(0, 4, 2, 0);
        ontologyIRIPanel.add(new LinkLabel(ONTOLOGY_IRI_FIELD_LABEL, e -> {
            showOntologyIRIDocumentation();
        }), new GridBagConstraints(0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.BASELINE_TRAILING, GridBagConstraints.NONE, insets, 0, 0));
        ontologyIRIPanel.add(ontologyIRIField, new GridBagConstraints(1, 0, 1, 1, 100.0, 0.0, GridBagConstraints.BASELINE_LEADING, GridBagConstraints.HORIZONTAL, insets, 0, 0));
        if (!read_only) {
        	//Fix issue #574 - versionIRI in client-server mode
        	ontologyIRIField.getDocument().addDocumentListener(new DocumentListener() {
        		public void insertUpdate(DocumentEvent e) {
        			//updateModelFromView();
        			buttonAlter();
        		}

        		public void removeUpdate(DocumentEvent e) {
        			//updateModelFromView();
        			buttonAlter();
        		}

        		public void changedUpdate(DocumentEvent e) {

        		}
        	});
        	ontologyIRIField.addFocusListener(new FocusAdapter() {
        		@Override
        		public void focusLost(FocusEvent e) {
        			handleOntologyIRIFieldFocusLost();
        		}

        		@Override
        		public void focusGained(FocusEvent e) {
        			handleOntologyIRIFieldFocusGained();
        		}
        	});
        } else {
        	this.ontologyIRIField.setEditable(false);
        	this.ontologyVersionIRIField.setEditable(false);
        }
        ontologyIRIShowing = ontologyIRIField.isShowing();
        ontologyIRIField.addHierarchyListener(e -> {
            handleComponentHierarchyChanged();
        });

        ontologyIRIPanel.add(new LinkLabel(ONTOLOGY_VERSION_IRI_FIELD_LABEL, e -> {
            showVersionIRIDocumentation();
        }), new GridBagConstraints(0, 1, 1, 1, 0.0, 0.0, GridBagConstraints.BASELINE_TRAILING, GridBagConstraints.NONE, insets, 0, 0));

        ontologyIRIPanel.add(ontologyVersionIRIField, new GridBagConstraints(1, 1, 1, 1, 95.0, 0.0, GridBagConstraints.BASELINE_LEADING, GridBagConstraints.HORIZONTAL, insets, 0, 0));

        //Fix issue #574 - versionIRI in client-server mode
        ontologyVersionIRIField.getDocument().addDocumentListener(new DocumentListener() {
	        public void insertUpdate(DocumentEvent e) {
	            //updateModelFromView();
	        	buttonAlter();
	        }
	
	        public void removeUpdate(DocumentEvent e) {
	            //updateModelFromView();
	        	buttonAlter();
	        }
	
	        public void changedUpdate(DocumentEvent e) {
	        }
        });
        commitBtn.setEnabled(false);
        ontologyIRIPanel.add(commitBtn, new GridBagConstraints(2, 1, 1, 1, 5.0, 0.0, GridBagConstraints.BASELINE_LEADING, GridBagConstraints.HORIZONTAL, insets, 0, 0));

        commitBtn.addActionListener(new ActionListener() {
        	public void actionPerformed(ActionEvent e)
            {
        		updateModelFromView();
        		//TODO: Commit to server
        		commitToServer();
        		buttonAlter();
            }
        });
        
        ontologyIRIPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));

        list = new OWLOntologyAnnotationList(getOWLEditorKit(), read_only);

        add(new JScrollPane(list));
        list.setRootObject(new OntologyAnnotationContainer(activeOntology()));
        listener = event -> handleModelManagerChangeEvent(event);
        getOWLModelManager().addListener(listener);

        getOWLModelManager().addOntologyChangeListener(ontologyChangeListener);
        updateView();
    }

    //Fix issue #574 - versionIRI in client-server mode
    private void buttonAlter() {
    	OWLOntology activeOntology = getOWLEditorKit().getOWLModelManager().getActiveOntology();
    	
        Optional<IRI> ontologyIRI = activeOntology.getOntologyID().getOntologyIRI();
        String ontologyIRIString = ontologyIRI.get().toString();
        Optional<IRI> ontologyVersionIRI = activeOntology.getOntologyID().getVersionIRI();
        String ontologyVersionIRIString = null;
        if (ontologyVersionIRI.isPresent()) {
        	ontologyVersionIRIString = ontologyVersionIRI.get().toString();
        }
        List<OWLOntologyChange> localChanges = getUncommittedLocalChanges();
        
        if (localChanges.size() > 0) {
        	commitBtn.setEnabled(true);
        }
        else if (!ontologyIRIField.getText().equals(ontologyIRIString)) {
        	commitBtn.setEnabled(true);
        }
        else { 
        	if (ontologyVersionIRIField.getText().isEmpty()) {
        		commitBtn.setEnabled(false);
        	}
        	else if (!(ontologyVersionIRIField.getText().equals(ontologyVersionIRIString))) {
        		commitBtn.setEnabled(true);
        	} else {
        		commitBtn.setEnabled(false);
        	}
        }
    }
    
    //Fix issue #574 - versionIRI in client-server mode
    private void commitToServer() {
    	Optional<VersionedOWLOntology> activeVersionOntology = Optional.empty();
    	CommitDialogPanel commitPanel = new CommitDialogPanel();
        int option = new UIHelper(getOWLEditorKit()).showValidatingDialog("Commit changes", commitPanel, null);
        if (option == JOptionPane.OK_OPTION) {
            String comment = commitPanel.getTextArea().getText().trim();
            activeVersionOntology = Optional.ofNullable(getClientSession().getActiveVersionOntology());
            if (!comment.isEmpty()) {
                performCommit(activeVersionOntology.get(), comment);
            }
        }
    }
    
    //Fix issue #574 - versionIRI in client-server mode
    private List<OWLOntologyChange> getUncommittedLocalChanges() {
    	List<OWLOntologyChange> localChanges = new ArrayList<OWLOntologyChange>();
    	Optional<VersionedOWLOntology> activeVersionOntology = Optional.empty();
    	activeVersionOntology = Optional.ofNullable(getClientSession().getActiveVersionOntology());
    	if (activeVersionOntology.isPresent()) {
	    	ChangeHistory baseline = activeVersionOntology.get().getChangeHistory();
	    	SessionRecorder sessionRecorder = SessionRecorder.getInstance(getOWLEditorKit());
	    	localChanges = ClientUtils.getUncommittedChanges(sessionRecorder, activeOntology(), baseline); 	
    	}
    	return localChanges;
    }
    
    //Fix issue #574 - versionIRI in client-server mode
    private void performCommit(VersionedOWLOntology vont, String comment) {
    	try {
        	ChangeHistory baseline = vont.getChangeHistory();
        	SessionRecorder sessionRecorder = SessionRecorder.getInstance(getOWLEditorKit());
        	List<OWLOntologyChange> localChanges = ClientUtils.getUncommittedChanges(sessionRecorder, activeOntology(), baseline);
    		
    		if (localChanges.size() > 0) {
        		Commit commit = ClientUtils.createCommit(getClientSession().getActiveClient(), comment, localChanges);
        		DocumentRevision base = vont.getHeadRevision();
        		CommitBundle commitBundle = new CommitBundleImpl(base, commit);
        		ChangeHistory hist = getClientSession().getActiveClient().commit(getClientSession().getActiveProject(), commitBundle);
        		vont.update(hist);
        		//setEnabled(false); // disable the commit action after the changes got committed successfully
                sessionRecorder.reset();
                getClientSession().fireCommitPerformedEvent(new CommitOperationEvent(
                		hist.getHeadRevision(),
                		hist.getMetadataForRevision(hist.getHeadRevision()),
                		hist.getChangesForRevision(hist.getHeadRevision())));
                
                JOptionPaneEx.showConfirmDialog(getOWLEditorKit().getWorkspace(), "Commit", new JLabel("Commit success (uploaded as revision " + hist.getHeadRevision() + ")"),
                        JOptionPane.INFORMATION_MESSAGE, JOptionPane.DEFAULT_OPTION, null);
        	}
        	
    	} catch (Exception e) {
            
            JOptionPaneEx.showConfirmDialog(getOWLEditorKit().getWorkspace(), "Commit error", new JLabel("Internal error: " + e.getMessage()),
                    JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION, null);
        }
    }

    private ClientSession getClientSession() {
        return ClientSession.getInstance(getOWLEditorKit());
    }
    
    private void handleComponentHierarchyChanged() {
        if (ontologyIRIShowing != ontologyIRIField.isShowing()) {
            ontologyIRIShowing = ontologyIRIField.isShowing();
            if (!ontologyIRIField.isShowing()) {
                handlePossibleOntologyIdUpdate();
            }
            else {
                handleOntologyIRIFieldActivated();
            }
        }
    }

    private void handleOntologyIRIFieldFocusGained() {
        handleOntologyIRIFieldActivated();
    }

    private void handleOntologyIRIFieldActivated() {
        initialOntologyID = getOWLModelManager().getActiveOntology().getOntologyID();
    }

    private void handleOntologyIRIFieldFocusLost() {
        handlePossibleOntologyIdUpdate();
    }

    private void handlePossibleOntologyIdUpdate() {
        OWLOntologyID id = createOWLOntologyIDFromView();
        if (isOntologyIRIChange(id)) {
            EntityIRIUpdaterOntologyChangeStrategy changeStrategy = new EntityIRIUpdaterOntologyChangeStrategy();
            Set<OWLEntity> entities = changeStrategy.getEntitiesToRename(activeOntology(), initialOntologyID, id);
            if (!entities.isEmpty()) {
                boolean rename = showConfirmRenameDialog(id, entities);
                if (rename) {
                    List<OWLOntologyChange> changes = changeStrategy.getChangesForRename(activeOntology(), initialOntologyID, id);
                    getOWLModelManager().applyChanges(changes);
                    initialOntologyID = id;
                }
            }


        }
    }

    private boolean showConfirmRenameDialog(OWLOntologyID id, Set<OWLEntity> entities) {
        String msg = getChangeEntityIRIsConfirmationMessage(id, entities);
        int ret = JOptionPane.showConfirmDialog(this, msg, "Rename entities as well as ontology?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        return ret == JOptionPane.YES_OPTION;
    }

    private boolean isOntologyIRIChange(OWLOntologyID id) {
        return initialOntologyID != null && id != null && !id.equals(initialOntologyID) && !initialOntologyID.isAnonymous() && !id.isAnonymous();
    }

    private String getChangeEntityIRIsConfirmationMessage(OWLOntologyID id, Set<OWLEntity> entities) {
        return "<html><body>You have renamed the ontology from<br>" +
                "" + initialOntologyID.getOntologyIRI().get().toString() + "<br>" +
                "to<br>" +
                "" + id.getOntologyIRI().get().toString() + ".<br>" +
                "<br>" +
                "<b>There are " + NumberFormat.getIntegerInstance().format(entities.size()) + " entities whose IRIs start with the original ontology IRI. Would you also like to rename these entities<br>" +
                "so that their IRIs start with the new ontology IRI?</b></body></html>";
    }


    private void handleModelManagerChangeEvent(OWLModelManagerChangeEvent event) {
        if (isUpdateTriggeringEvent(event)) {
            updateView();
        }
    }

    private boolean isUpdateTriggeringEvent(OWLModelManagerChangeEvent event) {
        return event.isType(EventType.ACTIVE_ONTOLOGY_CHANGED) || event.isType(EventType.ONTOLOGY_LOADED) || event.isType(EventType.ONTOLOGY_RELOADED) || event.isType(EventType.ONTOLOGY_SAVED);
    }

    private void showVersionIRIDocumentation() {
        try {
            Desktop.getDesktop().browse(VERSION_IRI_DOCUMENTATION);
        }
        catch (IOException ex) {
            ErrorLogPanel.showErrorDialog(ex);
        }
    }

    private void showOntologyIRIDocumentation() {
        try {
            Desktop.getDesktop().browse(ONTOLOGY_IRI_DOCUMENTATION);
        }
        catch (IOException ex) {
            ErrorLogPanel.showErrorDialog(ex);
        }
    }

    /**
     * Updates the view from the model - unless the changes were triggered by changes in the view.
     */
    private void updateViewFromModel() {
        updatingViewFromModel = true;
        try {
            OWLOntology activeOntology = getOWLEditorKit().getOWLModelManager().getActiveOntology();
            if (activeOntology.isAnonymous()) {
                if (!ontologyIRIField.getText().isEmpty()) {
                    ontologyIRIField.setText("");
                    if (ontologyVersionIRIField.getText().isEmpty()) {
                        ontologyVersionIRIField.setText("");
                    }
                }
            }
            else {
                OWLOntologyID id = activeOntology.getOntologyID();

                Optional<IRI> ontologyIRI = id.getOntologyIRI();
                String ontologyIRIString = ontologyIRI.get().toString();
                if (ontologyIRI.isPresent()) {
                    if (!ontologyIRIField.getText().equals(ontologyIRIString)) {
                        ontologyIRIField.setText(ontologyIRIString);
                    }
                }

                Optional<IRI> versionIRI = id.getVersionIRI();
                if (versionIRI.isPresent()) {
                    String versionIRIString = versionIRI.get().toString();
                    if (!ontologyVersionIRIField.getText().equals(versionIRIString)) {
                        ontologyVersionIRIField.setText(versionIRIString);
                    }
                }
                else {
                    ontologyVersionIRIField.setText("");
                    if (ontologyIRI.isPresent()) {
                        ontologyVersionIRIField.setGhostText("e.g. " + ontologyIRIString + (ontologyIRIString.endsWith("/") ? "1.0.0" : "/1.0.0"));
                    }
                }
            }
        }
        finally {
            updatingViewFromModel = false;
        }
    }

    /**
     * Updates the model from the view - unless the changes in the view were triggered by changes in the model.
     */
    private void updateModelFromView() {
        if (updatingViewFromModel) {
            return;
        }
        try {
            updatingModelFromView = true;
            OWLOntologyID id = createOWLOntologyIDFromView();
            if (id != null && !activeOntology().getOntologyID().equals(id)) {
                getOWLModelManager().applyChange(new SetOntologyID(activeOntology(), id));
            }
        }
        finally {
            updatingModelFromView = false;
        }

    }

    private OWLOntology activeOntology() {
        return getOWLModelManager().getActiveOntology();
    }


    private OWLOntologyID createOWLOntologyIDFromView() {
        try {
            ontologyIRIField.clearErrorMessage();
            ontologyIRIField.clearErrorLocation();
            String ontologyIRIString = ontologyIRIField.getText().trim();
            if (ontologyIRIString.isEmpty()) {
                return new OWLOntologyID();
            }
            URI ontURI = new URI(ontologyIRIString);
            IRI ontologyIRI = IRI.create(ontURI);
            String versionIRIString = ontologyVersionIRIField.getText().trim();
            if (versionIRIString.isEmpty()) {
                return new OWLOntologyID(Optional.of(ontologyIRI), Optional.<IRI>empty());
            }

            URI verURI = new URI(versionIRIString);
            IRI versionIRI = IRI.create(verURI);
            return new OWLOntologyID(Optional.of(ontologyIRI), Optional.of(versionIRI));
        }
        catch (URISyntaxException e) {
            ontologyIRIField.setErrorMessage(e.getReason());
            ontologyIRIField.setErrorLocation(e.getIndex());
            return null;
        }
    }


    private void handleOntologyChanges(List<? extends OWLOntologyChange> changes) {
        for (OWLOntologyChange change : changes) {
        	//Fix issue #574 - versionIRI in client-server mode
            buttonAlter();
        	change.accept(new OWLOntologyChangeVisitor() {
                @Override
                public void visit(SetOntologyID change) {
                    updateView();
                }

				@Override
				public void visit(ReplaceOntologyPrefixMappingChange replaceOntologyPrefixMappingChange) {
					// TODO Auto-generated method stub
					
				}
            });
        }
    }

    private void updateView() {
        list.setRootObject(new OntologyAnnotationContainer(activeOntology()));
        updateViewFromModel();
    }


    protected void disposeOWLView() {
        list.dispose();
        getOWLModelManager().removeListener(listener);
        getOWLModelManager().removeOntologyChangeListener(ontologyChangeListener);
    }

}
