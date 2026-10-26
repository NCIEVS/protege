package gov.nih.nci.curator.utils;

import java.awt.BorderLayout;
import java.awt.Dialog.ModalityType;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.protege.editor.core.Disposable;
import org.protege.editor.core.ui.util.Resettable;
import org.protege.editor.owl.OWLEditorKit;
import org.semanticweb.owlapi.reasoner.ReasonerProgressMonitor;


/**
 * Author: Matthew Horridge<br>
 * The University Of Manchester<br>
 * Medical Informatics Group<br>
 * Date: 10-Oct-2006<br><br>

 * matthew.horridge@cs.man.ac.uk<br>
 * www.cs.man.ac.uk/~horridgm<br><br>
 */
public class ReasonerProgressUI implements ReasonerProgressMonitor, Disposable, Resettable {
	public static final long CLOSE_PROGRESS_TIMEOUT = 1000;

	public static final int PADDING = 5;

	public static final String DEFAULT_MESSAGE = "Classifying...";

	private JLabel taskLabel;

	private JProgressBar progressBar;

	private JDialog window;

	private boolean taskIsRunning = false;

	private int last_percent = 0;

	private int len = 0;


	public ReasonerProgressUI() {     
		progressBar = new JProgressBar();
		initWindow();
	}

	public void initWindow() {
		if (window != null)
			return;		
		JPanel panel = new JPanel(new BorderLayout(PADDING, PADDING));
		panel.add(progressBar);
		taskLabel = new JLabel(DEFAULT_MESSAGE);
		panel.add(taskLabel, BorderLayout.NORTH);
		Frame parent = (Frame) (SwingUtilities.getAncestorOfClass(Frame.class,
				panel));
		
		window = new JDialog(parent, "Reasoner progress");
		window.setAlwaysOnTop(true);
		window.setModalityType(ModalityType.APPLICATION_MODAL);
	

		JPanel holderPanel = new JPanel(new BorderLayout(PADDING, PADDING));
		holderPanel.add(panel, BorderLayout.NORTH);

		holderPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		window.getContentPane().setLayout(new BorderLayout());
		window.getContentPane().add(holderPanel);
		window.pack();
		Dimension windowSize = window.getPreferredSize();
		window.setSize(400, windowSize.height);
		
	}

	public void reasonerTaskBusy() {
		progressBar.setIndeterminate(true);
	}

	public void reasonerTaskProgressChanged(final int value, final int max) {
		if (max > 0) {
			
			if (len == 0) {
				len = max;
				SwingUtilities.invokeLater(() -> {
					progressBar.setMaximum(len);
				});

			} else if (len != max) {
				len = max;
				SwingUtilities.invokeLater(() -> {
					progressBar.setMaximum(max);
				});



			}
			
			int percent = value * 100 / max;
			if ((percent > 0) && ((percent % 5) == 0)) {
				if (percent != last_percent) {
					last_percent = percent;					
					SwingUtilities.invokeLater(() -> {
						//System.out.print("    ");
						//System.out.print(percent);
						//System.out.println("%");
						progressBar.setValue(value);

					});
				}
			}

		}

	}

	public void reasonerTaskStarted(String taskName) {
		if (taskIsRunning)
			return;
		taskIsRunning = true;
		SwingUtilities.invokeLater(() -> {
			progressBar.setValue(0);
			showWindow(taskName);
			
		});
	}


	public void reasonerTaskStopped() {
		if (!taskIsRunning)
			return;
		taskIsRunning = false; 
		if (taskIsRunning)
			return;
		if (!window.isVisible())
			return;
		taskLabel.setText("");
		window.setVisible(false);
		len = 0;
	}


	private void showWindow(final String message) { 
		if (!taskIsRunning)
			return;
		taskLabel.setText(message);
		if (window.isVisible())
			return;
		//cancelledAction.setEnabled(true);
		window.setLocationRelativeTo(window.getOwner());
		window.setVisible(true);
	}

	public void reset() {
		initWindow();
		//window.dispose();
	}

	public void dispose() throws Exception {
		reset();
	}

}
