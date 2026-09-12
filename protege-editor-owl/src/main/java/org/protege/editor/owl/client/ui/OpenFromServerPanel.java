package org.protege.editor.owl.client.ui;

import edu.stanford.protege.metaproject.api.AuthToken;
import edu.stanford.protege.metaproject.api.Project;
import edu.stanford.protege.metaproject.api.ProjectId;
import edu.stanford.protege.metaproject.api.ServerConfiguration;
import org.protege.editor.core.ui.util.JOptionPaneEx;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.client.ClientPreferences;
import org.protege.editor.owl.client.ClientSession;
import org.protege.editor.owl.client.LocalHttpClient;
import org.protege.editor.owl.client.SessionRecorder;
import org.protege.editor.owl.client.api.Client;
import org.protege.editor.owl.client.api.OpenProjectResult;
import org.protege.editor.owl.client.api.exception.LoginTimeoutException;
import org.protege.editor.owl.client.api.exception.OWLClientException;
import org.protege.editor.owl.model.OWLWorkspace;
import org.protege.editor.owl.model.triplestore.TripleStoreContext;
import org.protege.editor.owl.server.util.SnapShot;
import org.protege.editor.owl.server.versioning.ChangeHistoryUtils;
import org.protege.editor.owl.server.versioning.ReplaceChangedOntologyVisitor;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.ServerDocument;
import org.protege.editor.owl.server.versioning.api.VersionedOWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * @author Josef Hardi <johardi@stanford.edu> <br>
 * @author Timothy Redmond <tredmond@stanford.edu> <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class OpenFromServerPanel extends JPanel {

    private static final long serialVersionUID = -6710802337675443598L;

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(OpenFromServerPanel.class);

    private ClientSession clientSession;

    private OWLEditorKit editorKit;
    private OWLOntologyManager owlManager;

    private JButton btnOpenProject;
    private JButton btnCancel;

    private JTable tblRemoteProjects;
    private ServerTableModel remoteProjectModel;
    
    private JProgressBar progressBar;
    private JDialog dialog;
    private boolean fromImport;

    public OpenFromServerPanel(ClientSession clientSession, OWLEditorKit editorKit) {
        this.clientSession = clientSession;
        this.editorKit = editorKit;
        owlManager = editorKit.getOWLModelManager().getOWLOntologyManager();

        addFocusListener(new FocusListener() {
            @Override
            public void focusLost(FocusEvent e) {
                // NO-OP
            }
            @Override
            public void focusGained(FocusEvent e) {
                showLoginWhenNecessary();
            }
        });

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));

        add(getRemoteProjectsPanel(), BorderLayout.CENTER);

        JPanel pnlButtons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        pnlButtons.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0)); // padding-top

        btnOpenProject = new JButton("Open Project");
        btnOpenProject.setSelected(true);
        btnOpenProject.addActionListener(new OpenActionListener());
        pnlButtons.add(btnOpenProject);

        btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(e -> {
            closeDialog();
        });
        pnlButtons.add(btnCancel);

        add(pnlButtons, BorderLayout.SOUTH);

        setFocusable(true);
    }

    public void setButtonsEnable(boolean enable) {
    	btnOpenProject.setEnabled(enable);
    	btnCancel.setEnabled(enable);
    }
    
    private JPanel getRemoteProjectsPanel() {
        JPanel pnlRemoteProjects = new JPanel(new BorderLayout());
        
        remoteProjectModel = new ServerTableModel();
        tblRemoteProjects = new JTable(remoteProjectModel);
        tblRemoteProjects.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = tblRemoteProjects.getSelectedRow();
                    btnOpenProject.doClick();
                }
            }
        });
        JScrollPane scrollPane = new JScrollPane(tblRemoteProjects);
        pnlRemoteProjects.add(scrollPane, BorderLayout.CENTER);
        return pnlRemoteProjects;
    }
    
    private void showLoginWhenNecessary() {
        if (!clientSession.hasActiveClient()) {
            Optional<AuthToken> authToken = UserLoginPanel.showDialog(editorKit, OpenFromServerPanel.this);
            if (!authToken.isPresent()) {
                closeDialog();
            } else if(authToken.isPresent() && clientSession.hasActiveClient()) {
                if(((LocalHttpClient) clientSession.getActiveClient()).getClientType() == LocalHttpClient.UserType.ADMIN) {
                    closeDialog();
                }
            }
        }
        else {
            if(((LocalHttpClient) clientSession.getActiveClient()).getClientType() == LocalHttpClient.UserType.NON_ADMIN) {
                loadProjectList();
            }
        }
    }

    
    public void loadProjectList() {
        try {
            Client client = clientSession.getActiveClient();
            remoteProjectModel.initialize(client);
            if (getFromImport()) {
            	OWLOntologyID ontologyID = this.editorKit.getModelManager().getActiveOntology().getOntologyID();
            	remoteProjectModel.removeRemoteProject(ontologyID);
            }
            tblRemoteProjects.changeSelection(0, 0, false, false); // select the first item as default
        }
        catch (OWLClientException e) {
            JOptionPaneEx.showConfirmDialog(editorKit.getWorkspace(), "Error opening project",
                    new JLabel("Open project failed: " + e.getMessage()),
                    JOptionPane.ERROR_MESSAGE, JOptionPane.DEFAULT_OPTION, null);
        }
    }
    
    public ServerTableModel getRemoteProjectModel() {
    	return this.remoteProjectModel;
    }

    public int getSelectedRow() {
    	return tblRemoteProjects.getSelectedRow();
    }
    
    private class OpenActionListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            int row = tblRemoteProjects.getSelectedRow();
            
            progressBar = new JProgressBar();
            progressBar.setPreferredSize(new Dimension(500, 50));
            
            
            Window parentWindow = SwingUtilities.windowForComponent(tblRemoteProjects);
            dialog = new JDialog(parentWindow);
            dialog.setLocationRelativeTo(tblRemoteProjects);
            
            dialog.add(progressBar);
            dialog.pack();
            dialog.setTitle("Opening project...");
           
            class LoadDBWorker extends SwingWorker<String, Void> {
               protected String doInBackground() {
            	   //progressBar.setIndeterminate(true);
            	   progressBar.setMinimum(0);
            	   progressBar.setMaximum(100);
            	   progressBar.setVisible(true);
                   
                   dialog.setVisible(true);
                   
                   openOntologyDocument(row);
            	   return "Done.";
               }
            
               protected void done() {
            	   progressBar.setVisible(false);
                   dialog.setVisible(false);            	   
               }
            }
            
            new LoadDBWorker().execute();
        }
    }

    public void openOntologyDocument(int row) {
        ProjectId pid = remoteProjectModel.getValueAt(row);
        Object pobj = remoteProjectModel.getValueAt(row, 0);
        try {
            LocalHttpClient httpClient = (LocalHttpClient) clientSession.getActiveClient();
            
            dialog.setTitle("Opening project on server...");
            Thread.sleep(1000);
            OpenProjectResult openProjectResult = httpClient.openProject(pid);
            ServerDocument serverDocument = openProjectResult.serverDocument;

            // Gate the lazy read model on a real project: learn the triple store (Virtuoso) endpoint
            // and named graph from the opened project the same way the server derives its write graph
            // (project namespace + "/" + name), so the client never connects before a project is open.
            configureTripleStore(httpClient, pid);

            progressBar.setValue(10);            
            dialog.setTitle("Checking Snapshot checksum....");
            Thread.sleep(1000);
            Optional<String> clientChecksum = httpClient.getSnapshotChecksum(pid);
            if (clientChecksum.isPresent() &&
                openProjectResult.snapshotChecksum.isPresent() &&
                !clientChecksum.get().equals(openProjectResult.snapshotChecksum.get())) {            	
            	progressBar.setValue(20);
            	dialog.setTitle("Retrieving new snapshot....");
            	Thread.sleep(1000);
                SnapShot snapshot = httpClient.getSnapShot(pid);
                httpClient.createLocalSnapShot(snapshot.getOntology(), pid);
            }
            
            if (serverDocument != null && pobj != null) {
            	String serverConnection = "Server: " + serverDocument.getServerAddress().toString() + " | User: " + httpClient.getUserInfo().getId() 
            			+ " | Project: " + pobj.toString();
            
            	editorKit.getOWLModelManager().setServerConnectionData(serverConnection);
            }
            
            SessionRecorder.getInstance(this.editorKit).stopRecording();
            progressBar.setValue(30);
            dialog.setTitle("Building versioned ontology...");
            Thread.sleep(1000);
            editorKit.getSearchManager().disableIncrementalIndexing();
            VersionedOWLOntology vont = httpClient.buildVersionedOntology(serverDocument, owlManager, 
            		pid, editorKit);
            
            progressBar.setValue(80);
            dialog.setTitle("Updating menus and components...."); 
            Thread.sleep(1000);
            long beg = System.currentTimeMillis();
            clientSession.setActiveProject(pid, vont);
            System.out.println("It took; " +
            		(System.currentTimeMillis() - beg) + " secs");
            Thread.sleep(1000);
            
            progressBar.setValue(90);
            dialog.setTitle("Updating search indices...");
            
            // let's store the revision number rather than changes number
            int rev_no_processed = ClientPreferences.getInstance().getNoServerRevisionsIndexed();
        	
            if (!vont.getChangeHistory().isEmpty()) {
                for (DocumentRevision rev : vont.getChangeHistory().getRevisions().keySet()) {
                	if (rev.getRevisionNumber() > rev_no_processed) {
                		editorKit
                		.getSearchManager()
                		.updateIndex(vont.getChangeHistory().getChangesForRevision(rev));
                	}
                }
            }
            
            
            ClientPreferences.getInstance().setNoServerRevisionsIndexed(vont.getChangeHistory().getHeadRevision().getRevisionNumber());
            
            
            
            SessionRecorder.getInstance(this.editorKit).startRecording();
            editorKit.getSearchManager().enableIncrementalIndexing();
            
            progressBar.setValue(100);
            dialog.setTitle("Operations complete...");
            Thread.sleep(1000);
            
            
            
            closeDialog();
        }
        catch (LoginTimeoutException e) {
            JOptionPaneEx.showConfirmDialog(editorKit.getWorkspace(), "Open project error",
                    new JLabel(e.getMessage()), JOptionPane.ERROR_MESSAGE,
                    JOptionPane.DEFAULT_OPTION, null);
            Optional<AuthToken> authToken = UserLoginPanel.showDialog(editorKit, this);
            if (authToken.isPresent() && authToken.get().isAuthorized()) {
                loadProjectList();
            }
        }
        catch (Exception e) {
            JOptionPaneEx.showConfirmDialog(editorKit.getWorkspace(), "Open project error",
                    new JLabel(e.getMessage()), JOptionPane.ERROR_MESSAGE,
                    JOptionPane.DEFAULT_OPTION, null);
        }
    }

    private void closeDialog() {
        Window window = SwingUtilities.getWindowAncestor(OpenFromServerPanel.this);
        window.setVisible(false);
        window.dispose();
    }

    // Point the lazy read model at the opened project's Virtuoso graph, mirroring how the server
    // derives its write graph (Project.namespace() + "/" + name) and reads the endpoint from the
    // shared triple_store_url config property. Best-effort: on any failure the context stays
    // unconfigured and the lazy read model simply remains dormant.
    private void configureTripleStore(LocalHttpClient httpClient, ProjectId pid) {
        try {
            ServerConfiguration cfg = httpClient.getCurrentConfig();
            String endpoint = cfg.getProperty("triple_store_url");
            Project project = cfg.getProject(pid);
            String graph = project.namespace() + "/" + project.getName().get();
            TripleStoreContext.getInstance().configure(endpoint, graph);
            logger.info("Lazy read model targeting triple store {} graph <{}>", endpoint, graph);
        }
        catch (Exception e) {
            logger.warn("Could not configure triple store for project {}; lazy read model stays dormant", pid, e);
        }
    }
    
    public boolean getFromImport() {
    	return this.fromImport;
    }
    
    public void setFromImport(boolean fimport) {
    	this.fromImport = fimport;
    }
}
