package org.protege.editor.owl.client.util;


import java.awt.Dialog;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.client.ClientPreferences;
import org.protege.editor.owl.client.ClientSession;
import org.protege.editor.owl.client.LocalHttpClient;
import org.protege.editor.owl.client.SessionRecorder;
import org.protege.editor.owl.client.api.Client;
import org.protege.editor.owl.client.api.OpenProjectResult;
import org.protege.editor.owl.client.api.exception.AuthorizationException;
import org.protege.editor.owl.client.api.exception.ClientRequestException;
import org.protege.editor.owl.client.api.exception.LoginTimeoutException;
import org.protege.editor.owl.client.event.ClientSessionListener;
import org.protege.editor.owl.client.ui.OpenFromServerPanel;
import org.protege.editor.owl.model.ChangeListMinimizer;
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.server.versioning.ChangeHistoryUtils;
import org.protege.editor.owl.server.versioning.Commit;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.RevisionMetadata;
import org.protege.editor.owl.server.versioning.api.ServerDocument;
import org.protege.editor.owl.server.versioning.api.VersionedOWLOntology;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.MissingOntologyHeaderStrategy;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
//import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration.MissingOntologyHeaderStrategy;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import edu.stanford.protege.metaproject.api.Project;
import edu.stanford.protege.metaproject.api.ProjectId;

/**
 * @author Josef Hardi <johardi@stanford.edu> <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class ClientUtils {

    /**
     * Perform logout from the Protege client-server application.
     *
     * @param clientSession
     *          The existing client session
     * @param client
     *          The client to log out
     */
    public static void performLogout(ClientSession clientSession, Client client) throws Exception
    {
        if (client instanceof ClientSessionListener) {
            clientSession.removeListener((ClientSessionListener) client);
        }
        clientSession.clear();
    }

   

    public static List<OWLOntologyChange> getUncommittedChanges(HistoryManager man, OWLOntology ontology, ChangeHistory baseline) {
    	
    	if (man instanceof SessionRecorder) {
    		return ((SessionRecorder) man).getUncommittedChanges();    	
    	} else {
    		// This case is here to support the client/server integration tests
    		// SessionRecorder should be refactored to be independent of OWLEditorKit
    		List<List<OWLOntologyChange>> changes = man.getLoggedChanges();
    		List<OWLOntologyChange> result = new ArrayList<OWLOntologyChange>();
    		for (List<OWLOntologyChange> c : changes) {
    			result.addAll(c);
    		}
    		return result;
    	}
    	
    }

    /**
     * Create a commit object by specifying the <code>author</code>, <code>comment</code> string and
     * the list of <code>changes</code>.
     *
     * @param author
     *          The committer
     * @param comment
     *          The commit comment
     * @param changes
     *          The list of changes inside a commit
     * @return A commit object
     */
    public static Commit createCommit(Client author, String comment, List<OWLOntologyChange> changes) {
        RevisionMetadata metadata = new RevisionMetadata(
                author.getUserInfo().getId(),
                author.getUserInfo().getName(),
                author.getUserInfo().getEmailAddress(), comment);
        return new Commit(metadata, changes);
    }

    
    
   
    /*
     * Private utility methods
     */

    public static void updateOntology(OWLOntology placeholder, ChangeHistory changeHistory, 
    		OWLOntologyManager manager, LocalHttpClient client, OWLEditorKit kit) {        
    	
    	List<OWLOntologyChange> changes = ChangeHistoryUtils.getOntologyChanges(changeHistory, placeholder);
        
        manager.applyChanges(changes);        
       
        fixMissingImports(placeholder, changes, manager, client, kit);
    }

    private static void fixMissingImports(OWLOntology ontology, List<OWLOntologyChange> changes, 
    		OWLOntologyManager manager, LocalHttpClient client, OWLEditorKit kit) {
        OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration();
        configuration = configuration.setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        configuration = configuration.setMissingOntologyHeaderStrategy(MissingOntologyHeaderStrategy.IMPORT_GRAPH);
        
        final Set<OWLImportsDeclaration> declaredImports = ontology.getImportsDeclarations();
        Set<OWLImportsDeclaration> missingImports = new TreeSet<OWLImportsDeclaration>();
        for (OWLOntologyChange change : changes) {
            if (change instanceof AddImport) {
                OWLImportsDeclaration importDecl = ((AddImport) change).getImportDeclaration();
                if (declaredImports.contains(importDecl) && manager.getImportedOntology(importDecl) == null) {
                    missingImports.add(importDecl);
                }
            }
        }
        for (OWLImportsDeclaration importDecl : missingImports) {
            //manager.makeLoadImportRequest(importDecl, configuration);
        	ProjectId pid = client.findProjectId(importDecl.getIRI()).get();
        	OpenProjectResult openProjectResult;
        	try {
				openProjectResult = client.openProject(pid);
				ServerDocument serverDocument = openProjectResult.serverDocument;
	            VersionedOWLOntology vont = client.buildVersionedOntology(serverDocument, manager, pid, kit);
	            ClientSession.getInstance(kit).registerProject(vont.getOntology().getOntologyID(), pid); 
	            ClientSession.getInstance(kit).registerVersionOntology(vont.getOntology().getOntologyID(), vont);            
			} catch (LoginTimeoutException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (AuthorizationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (ClientRequestException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
        }
    }
    
    
    
    public static JDialog createOpenFromServerDialog(ClientSession clientSession, OWLEditorKit editorKit) {
        final JDialog dialog = new JDialog(null, "Open from Protege OWL Server", Dialog.ModalityType.MODELESS);
        OpenFromServerPanel openDialogPanel = new OpenFromServerPanel(clientSession, editorKit);
        openDialogPanel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "CLOSE_DIALOG");
        openDialogPanel.getActionMap().put("CLOSE_DIALOG", new AbstractAction()
        {
           private static final long serialVersionUID = 1L;
           @Override
           public void actionPerformed(ActionEvent e)
           {
               dialog.setVisible(false);
               dialog.dispose();
           }
        });
        dialog.addWindowListener(new WindowAdapter()
        {
           @Override
           public void windowClosing(WindowEvent e)
           {
               dialog.setVisible(false);
               dialog.dispose();
           }
        });
        dialog.setContentPane(openDialogPanel);
        dialog.setSize(650, 650);
        dialog.setResizable(true);
        return dialog;
    }
}
