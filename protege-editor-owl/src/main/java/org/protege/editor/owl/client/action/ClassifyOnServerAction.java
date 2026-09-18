package org.protege.editor.owl.client.action;

import java.awt.event.ActionEvent;

import javax.swing.SwingUtilities;

import org.protege.editor.owl.client.ClientSession;
import org.protege.editor.owl.client.LocalHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stanford.protege.metaproject.api.ProjectId;

/**
 * Runs the server-side classifier for the current project: the server classifies HEAD and writes the
 * inferred hierarchy into the project's separate {@code /inferred} graph. Nothing is applied to the
 * asserted model -- the modeler views the inferred hierarchy (and selectively promotes edges) apart
 * from this action.
 */
public class ClassifyOnServerAction extends AbstractClientAction {

    private static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(ClassifyOnServerAction.class);

    @Override
    public void actionPerformed(ActionEvent e) {
        ClientSession session = getClientSession();
        if (session == null || !(session.getActiveClient() instanceof LocalHttpClient)
                || session.getActiveProject() == null) {
            showWarningDialog("Classify on server", "Open a server project first.");
            return;
        }
        final LocalHttpClient client = (LocalHttpClient) session.getActiveClient();
        final ProjectId pid = session.getActiveProject();

        // Classification of a large project can take a while server-side; run off the EDT.
        submit(() -> {
            final String status = client.classifyProject(pid);
            SwingUtilities.invokeLater(() -> reportStatus(status));
        });
    }

    private void reportStatus(String status) {
        switch (status) {
            case "classified":
                showInfoDialog("Classify on server",
                        "Classification complete. The inferred hierarchy graph was rebuilt on the server.");
                break;
            case "rejected":
                showWarningDialog("Classify on server",
                        "The curator could not classify: some roles are used outside their declared "
                                + "domain/range. Fix those, then classify again.");
                break;
            case "no-classifier":
                showWarningDialog("Classify on server", "The server has no classifier installed.");
                break;
            case "triplestore-disabled":
                showWarningDialog("Classify on server",
                        "The server is not configured with a triple store to hold the inferred graph.");
                break;
            default:
                logger.warn("Server classification returned status: {}", status);
                showErrorDialog("Classify on server", "Classification failed on the server.", null);
        }
    }
}
