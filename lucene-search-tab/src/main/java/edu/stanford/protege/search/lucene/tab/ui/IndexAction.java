package edu.stanford.protege.search.lucene.tab.ui;

import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.client.ClientSession;
import org.protege.editor.owl.client.LocalHttpClient;
import org.protege.editor.owl.ui.action.ProtegeOWLAction;

import edu.stanford.protege.search.lucene.tab.engine.SearchTabManager;

import javax.swing.*;
import java.awt.event.ActionEvent;

/**
 * @author Rafael Gonçalves <br>
 * Center for Biomedical Informatics Research <br>
 * Stanford University
 */
public class IndexAction extends ProtegeOWLAction {

    private OWLEditorKit editorKit;

    @Override
    public void initialise() throws Exception {
        this.editorKit = getOWLEditorKit();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        SearchTabManager searchManager = getSearchManager();
        if (searchManager == null) {
            JOptionPane.showMessageDialog(editorKit.getOWLWorkspace(), new JLabel("Unable to index ontology. Ensure that" +
                            " 'Lucene search' is selected in the Protégé preferences (under the 'General' tab, in the 'Search type' option)."),
                    "Invalid search manager", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ClientSession session = ClientSession.getInstance(editorKit);
        if (session != null && session.getActiveClient() instanceof LocalHttpClient
                && session.getActiveProject() != null) {
            // Server project: re-derive the index server-side (snapshot + replay) and re-seed it.
            searchManager.reindexFromServer((LocalHttpClient) session.getActiveClient(), session.getActiveProject());
        } else {
            // Non-server ontology: rebuild locally from the in-memory ontology (backgrounds itself).
            searchManager.rebuildIndex();
        }
    }

    private SearchTabManager getSearchManager() {
        SearchTabManager searchManager = null;
        if (editorKit.getSearchManager() instanceof SearchTabManager) {
            searchManager = (SearchTabManager) editorKit.getSearchManager();
        }
        return searchManager;
    }

    @Override
    public void dispose() throws Exception {
        /* do nothing */
    }
}
