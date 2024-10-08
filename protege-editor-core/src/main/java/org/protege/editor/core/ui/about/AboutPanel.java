package org.protege.editor.core.ui.about;


import org.protege.editor.core.plugin.PluginUtilities;
import org.protege.editor.core.ui.preferences.PreferencesLayoutPanel;
import org.protege.editor.core.util.Version;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;


/**
 * Author: Matthew Horridge<br>
 * The University Of Manchester<br>
 * Medical Informatics Group<br>
 * Date: 01-Sep-2006<br><br>

 * matthew.horridge@cs.man.ac.uk<br>
 * www.cs.man.ac.uk/~horridgm<br><br>
 */
public class AboutPanel extends JPanel {



    public AboutPanel() {
        setLayout(new BorderLayout());

       // BundleContext applicationContext = PluginUtilities.getInstance().getApplicationContext();
       // Bundle application = applicationContext.getBundle();
       // Version v = PluginUtilities.getBundleVersion(application);
        Version v = PluginUtilities.getApplicationVersion();
        String versionString = "";
		if (v != null) {
			versionString = String.format("%d.%d.%d", v.getMajor(), v.getMinor(), v.getMicro());

			if (!v.getQualifier().isEmpty()) {
				versionString += " " + v.getQualifier()
				+ " " + "Build: " + getBuild();
			}
		}

        Runtime runtime = Runtime.getRuntime();
        long maxMemMB = runtime.maxMemory() / (1024 * 1024);
        long usedMemMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);

        PreferencesLayoutPanel panel = new PreferencesLayoutPanel();

        panel.addGroup("Version");
        panel.addGroupComponent(new JLabel(versionString, SwingConstants.CENTER));
        panel.addVerticalPadding();
        panel.addGroup("Memory settings");
        panel.addGroupComponent(new JLabel(String.format("<html><body>Max memory set to %d MB    <span style='color: #707070;'>(via Java -Xmx setting)</span></body></html>", maxMemMB)));
        panel.addGroupComponent(new JLabel(String.format("Currently using %d MB", usedMemMB)));

        panel.addVerticalPadding();
        panel.addGroup("Installed Plugins");

        JScrollPane sp = new JScrollPane(new PluginInfoTable());
        sp.setPreferredSize(new Dimension(500, 200));
        sp.setMinimumSize(new Dimension(500, 200));
        panel.addGroupComponent(sp);

        panel.addVerticalPadding();
        panel.addGroup("About");

        JLabel copy = new JLabel(
                "<html><body>Prot\u00E9g\u00E9 is developed by the Stanford Center for Biomedical Informatics Research.<br>" +
                        "Prot\u00E9g\u00E9 is a national resource for biomedical ontologies and knowledge bases<br>" +
                        "supported by the National Institute of General Medical Sciences.<br><br>" +
                        "Previous versions of the Prot\u00E9g\u00E9 series were developed in collaboration with the<br>" +
                        "Bio-Health Informatics Group in the School of Computer Science at<br>" +
                        "The University of Manchester.");
        copy.setMinimumSize(copy.getPreferredSize());
        panel.addGroupComponent(copy);
        add(panel, BorderLayout.NORTH);
    }
    
    private String getBuild() {
    	try {
			BufferedReader r = new BufferedReader(new FileReader(new File(".build_version")));
			return r.readLine();
		} catch (IOException e) {
			return "no version no.";
		}
    }





    public static void showDialog() {
        AboutPanel about = new AboutPanel();
        JOptionPane op = new JOptionPane(about, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION);
        JDialog dlg = op.createDialog(null, "About");
        dlg.pack();
        dlg.setVisible(true);

    }
}
