package org.protege.editor.owl.client.action;

import java.awt.event.ActionEvent;
import java.io.File;

import javax.swing.SwingUtilities;

import org.protege.editor.owl.client.ClientSession;
import org.protege.editor.owl.client.LocalHttpClient;
import org.protege.editor.owl.ui.UIHelper;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stanford.protege.metaproject.api.ProjectId;

/**
 * Exports the current server project's ontology at HEAD to an OWL file. Under the lazy read model the
 * client's in-memory ontology holds only fetched fragments, so the full ontology is fetched from the
 * server (snapshot + replay) rather than saved from memory.
 */
public class ExportFromServerAction extends AbstractClientAction {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(ExportFromServerAction.class);

    @Override
    public void actionPerformed(ActionEvent e) {
        ClientSession session = getClientSession();
        if (session == null || !(session.getActiveClient() instanceof LocalHttpClient)
                || session.getActiveProject() == null) {
            showWarningDialog("Export from server", "Open a server project first.");
            return;
        }
        final LocalHttpClient client = (LocalHttpClient) session.getActiveClient();
        final ProjectId pid = session.getActiveProject();

        File chosen = new UIHelper(getOWLEditorKit()).saveOWLFile(
                "Export the server project ontology to an OWL file");
        if (chosen == null) {
            return;
        }
        final File target = ensureOwlExtension(chosen);

        // Fetch + write off the EDT: pulling the full ontology from the server and serialising it are
        // both potentially long for a large ontology.
        submit(() -> {
            try {
                OWLOntology ontology = client.getProjectExport(pid);
                OWLOntologyManager manager = ontology.getOWLOntologyManager();
                manager.saveOntology(ontology, new RDFXMLDocumentFormat(), IRI.create(target));
                SwingUtilities.invokeLater(() ->
                        showInfoDialog("Export from server", "Exported to " + target.getAbsolutePath()));
            }
            catch (Exception ex) {
                logger.error("Export from server failed", ex);
                SwingUtilities.invokeLater(() ->
                        showErrorDialog("Export from server", "Export failed: " + ex.getMessage(), ex));
            }
        });
    }

    private static File ensureOwlExtension(File file) {
        String path = file.toString();
        int dot = path.lastIndexOf('.');
        if (dot == -1 || dot != path.length() - 4) {
            return new File(path + ".owl");
        }
        return file;
    }
}
