package org.protege.editor.owl.client.action;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.protege.editor.owl.client.ClientSession;
import org.protege.editor.owl.client.LocalHttpClient;
import org.protege.editor.owl.model.event.EventType;
import org.protege.editor.owl.model.hierarchy.InferredVirtuosoClassHierarchyProvider;
import org.protege.editor.owl.model.hierarchy.OWLObjectHierarchyProvider;
import org.semanticweb.owlapi.model.OWLClass;
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

        // Modeless so the EDT main loop stays free; we drive the bar ourselves (the native
        // indeterminate animation does not run on this macOS/LnF) by bouncing a determinate value.
        final JProgressBar bar = new JProgressBar(0, 100);
        final JDialog progress = createProgressDialog(bar);
        final Timer sweep = new Timer(40, new ActionListener() {
            private int value = 0;
            private int step = 3;

            @Override
            public void actionPerformed(ActionEvent ev) {
                value += step;
                if (value >= 100) {
                    value = 100;
                    step = -step;
                } else if (value <= 0) {
                    value = 0;
                    step = -step;
                }
                bar.setValue(value);
            }
        });
        progress.setVisible(true);
        sweep.start();
        submit(() -> {
            String status;
            try {
                status = client.classifyProject(pid);
                if (status == null) {
                    status = "error";
                }
            } catch (Exception ex) {
                logger.error("Server classification failed", ex);
                status = "error";
            }
            final String outcome = status;
            SwingUtilities.invokeLater(() -> {
                sweep.stop();
                progress.dispose();
                reportStatus(outcome);
            });
        });
    }

    // A non-cancellable, always-on-top modeless dialog holding the given progress bar: the server
    // cannot be stopped mid-run so there is nothing to cancel; it clears when classifyProject returns.
    private JDialog createProgressDialog(JProgressBar bar) {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(new JLabel("Classifying on server \u2014 this can take several minutes\u2026"),
                BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        Window parent = SwingUtilities.getWindowAncestor(getOWLEditorKit().getWorkspace());
        JDialog dialog = new JDialog(parent, "Classify on server", Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setAlwaysOnTop(true);
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(parent);
        return dialog;
    }

    private void reportStatus(String status) {
        switch (status) {
            case "classified":
                refreshInferredTree();
                // Refresh the "Curator Classification results" panel (and other classification views),
                // which key off this event; the client itself did not run a reasoner.
                getOWLModelManager().fireEvent(EventType.ONTOLOGY_CLASSIFIED);
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

    // Refresh the inferred navigation tree so it shows the just-computed hierarchy (only meaningful
    // under the lazy read model, where the inferred view is backed by the /inferred graph).
    private void refreshInferredTree() {
        OWLObjectHierarchyProvider<OWLClass> provider =
                getOWLModelManager().getOWLHierarchyManager().getInferredOWLClassHierarchyProvider();
        if (provider instanceof InferredVirtuosoClassHierarchyProvider) {
            ((InferredVirtuosoClassHierarchyProvider) provider).refresh();
        }
    }
}
