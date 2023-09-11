package org.protege.editor.core;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.BorderFactory;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.border.MatteBorder;
import javax.xml.parsers.ParserConfigurationException;

import org.protege.editor.core.editorkit.EditorKit;
import org.protege.editor.core.editorkit.EditorKitFactoryPlugin;
import org.protege.editor.core.editorkit.EditorKitManager;
import org.protege.editor.core.editorkit.RecentEditorKitManager;
import org.protege.editor.core.launcher.Parser;
import org.protege.editor.core.log.LogBanner;
import org.protege.editor.core.log.LogManager;
import org.protege.editor.core.log.LogViewImpl;
import org.protege.editor.core.platform.OSUtils;
import org.protege.editor.core.platform.PlatformArguments;
import org.protege.editor.core.platform.apple.ProtegeAppleApplication;
import org.protege.editor.core.plugin.JPFUtil;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;
import org.protege.editor.core.ui.error.ErrorLogPanel;
import org.protege.editor.core.ui.progress.BackgroundTaskManager;
import org.protege.editor.core.ui.tabbedpane.CloseableTabbedPaneUI;
import org.protege.editor.core.ui.util.ErrorMessage;
import org.protege.editor.core.ui.util.ProtegePlasticTheme;
import org.protege.editor.core.ui.workspace.Workspace;
import org.protege.editor.core.update.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import com.jgoodies.looks.plastic.PlasticLookAndFeel;

/*
 * Copyright (C) 2007, University of Manchester
 *
 *
 */

/**
 * Author: Matthew Horridge<br>
 * The University Of Manchester<br>
 * Medical Informatics Group<br>
 * Date: Mar 15, 2006<br><br>

 * matthew.horridge@cs.man.ac.uk<br>
 * www.cs.man.ac.uk/~horridgm<br><br>


 * The ProtegeApplication is the main entry point to Protege.  The application
 * is actually a plugin to the Java Plugin Framework
 */
public class ProtegeApplication {

    private final static Logger logger = LoggerFactory.getLogger(ProtegeApplication.class);

    public static final String BUNDLE_WITHOUT_PLUGIN_XML = "No-Plugin-XML";
    
    public static final String PLUGIN_DIR_PROP = "org.protege.plugin.dir";

    public static final String RUN_ONCE = "PROTEGE_OSGI_RUN_ONCE";

    public static final String ID = "org.protege.editor.core.application";

    public static final String LOOK_AND_FEEL_KEY = "LOOK_AND_FEEL_KEY";

    public static final String LOOK_AND_FEEL_CLASS_NAME = "LOOK_AND_FEEL_CLASS_NAME";
    
    public static final String ARG_PROPERTY = "command.line.arg.";

	public static final String LAUNCH_LOCATION_PROPERTY = "org.protege.launch.config";

	public static final String PROTEGE_DIR_PROPERTY = "protege.dir";

	public static final String DEFAULT_CONFIG_XML_FILE_PATH_NAME = "conf/config.xml";

	public static String PROTEGE_DIR = System.getProperty(PROTEGE_DIR_PROPERTY);
	
	private static final Map<String, String> frameworkProperties = new HashMap<>();

	private static final List<org.protege.editor.core.launcher.BundleSearchPath> searchPaths = new ArrayList<>();

    private List<URI> commandLineURIs;

    private static BackgroundTaskManager backgroundTaskManager = new BackgroundTaskManager();

    private static boolean quitting = false;

    private static LogManager logManager = new LogManager(new LogViewImpl());

    private boolean initialized = false;

    public void startapp() {
    	logManager.bind();
    	try {
    	displayPlatform();
        initApplication();


        if (OSUtils.isOSX()) {
            ProtegeAppleApplication.getInstance();
        }

        ProtegeManager.getInstance().initialise(this);
        startApplication();
    	} catch (Throwable t) {
            logger.error("Exception caught starting Protege", t);
        }
    
    }
    
    public static void main(String[] args) throws Exception {
    	
    	setArguments(args);
		String config = System.getProperty(LAUNCH_LOCATION_PROPERTY, DEFAULT_CONFIG_XML_FILE_PATH_NAME);
		File configFile;
		if (PROTEGE_DIR != null) {
			configFile = new File(PROTEGE_DIR, config);
		} else {
			configFile = new File(config);
		}
		java.util.prefs.Preferences.userRoot();
		parseConfig(configFile);
		
		JPFUtil.setClasspathForPlugins();
		
		ProtegeApplication protegeApp = new ProtegeApplication();
		protegeApp.startapp();
    	
    }
    
    private static void setSystemProperties(Parser p) {
		Map<String, String> systemProperties = p.getSystemProperties();
		System.setProperty("org.protege.osgi.launcherHandlesExit", "True");
		for (Entry<String, String> entry : systemProperties.entrySet()) {
			System.setProperty(entry.getKey(), entry.getValue());
		}
	}
    
    @SuppressWarnings("unchecked")
	private static void setLogger(Map configurationMap) {
		configurationMap.put("felix.log.logger", logger);
	}
    
    private static void parseConfig(File config) throws ParserConfigurationException, SAXException, IOException {
		Parser p = new Parser();
		p.parse(config);
		setSystemProperties(p);
		setLogger(frameworkProperties);
		searchPaths.addAll(p.getSearchPaths());
	}

    
    public static void setArguments(String... args) {
		if (args != null) {
			int counter = 0;
			for (String arg : args) {
				System.setProperty(ARG_PROPERTY + (counter++), arg);
			}
		}
	}

    


    /////////////////////////////////////////////////////////////////////////////////
    //
    //  Implementation of ApplicationPlugin
    //
    /////////////////////////////////////////////////////////////////////////////////


    // If this isn't liked info can be replaced with debug.
    // It helps with diagnosing problems with the FaCT++ plugin.
    private void displayPlatform() {
        logger.info(LogBanner.start("Protege"));
        logger.info("Protege Desktop");logger.info("");
        logger.info("");
        logger.info(LogBanner.start("Platform"));
        logger.info("Java: JVM {}  Memory: {}M", System.getProperty("java.runtime.version"), getMaxMemoryInMegaBytes());
        logger.info("Language: {}, Country: {}", getLang(), getCountry());
       
        logger.info(LogBanner.end());
        logger.info(LogBanner.start("Plugins"));
        logger.info(LogBanner.end());
    }

    private static String getCountry() {
        return Locale.getDefault().getCountry();
    }

    private static String getLang() {
        return Locale.getDefault().getLanguage();
    }

    private static long getMaxMemoryInMegaBytes() {
        return (Runtime.getRuntime().maxMemory() / 1000000);
    }



    protected ProtegeApplication initApplication() throws Exception {
        try {
            //PluginUtilities.getInstance().initialise(context);
            loadDefaults();
            initializeLookAndFeel();
            setupExceptionHandler();
            processCommandLineURIs();  // plugins may set arguments
            loadRecentEditorKits();
        } finally {
            initialized = true;
        }
        return this;
    }


    private static void loadDefaults() {
        ProtegeProperties.getInstance().put(ProtegeProperties.CLASS_COLOR_KEY, "CFA500"); //"CCA33D"
        ProtegeProperties.getInstance().put(ProtegeProperties.PROPERTY_COLOR_KEY, "0079BA");
        ProtegeProperties.getInstance().put(ProtegeProperties.OBJECT_PROPERTY_COLOR_KEY, "0079BA");
        ProtegeProperties.getInstance().put(ProtegeProperties.DATA_PROPERTY_COLOR_KEY, "38A14A");
        ProtegeProperties.getInstance().put(ProtegeProperties.INDIVIDUAL_COLOR_KEY, "874B82");
        ProtegeProperties.getInstance().put(ProtegeProperties.ONTOLOGY_COLOR_KEY, "6B47A2");
        ProtegeProperties.getInstance().put(ProtegeProperties.ANNOTATION_PROPERTY_COLOR_KEY, "D17A00");
        ProtegeProperties.getInstance().put(ProtegeProperties.DATATYPE_COLOR_KEY, "AD3B45");

        ProtegeProperties.getInstance().put(ProtegeProperties.CLASS_VIEW_CATEGORY, "Class");
        ProtegeProperties.getInstance().put(ProtegeProperties.OBJECT_PROPERTY_VIEW_CATEGORY, "Object property");
        ProtegeProperties.getInstance().put(ProtegeProperties.DATA_PROPERTY_VIEW_CATEGORY, "Data property");
        ProtegeProperties.getInstance().put(ProtegeProperties.ANNOTATION_PROPERTY_VIEW_CATEGORY, "Annotation property");
        ProtegeProperties.getInstance().put(ProtegeProperties.INDIVIDUAL_VIEW_CATEGORY, "Individual");
        ProtegeProperties.getInstance().put(ProtegeProperties.DATATYPE_VIEW_CATEGORY, "Datatype");
        ProtegeProperties.getInstance().put(ProtegeProperties.ONTOLOGY_VIEW_CATEGORY, "Ontology");
        ProtegeProperties.getInstance().put(ProtegeProperties.QUERY_VIEW_CATEGORY, "Query");
        ProtegeProperties.getInstance().put(ProtegeProperties.DIFF_VIEW_CATEGORY, "Ontology comparison");
    }


    private static void loadRecentEditorKits() {
        RecentEditorKitManager.getInstance().load();
    }


    private void initializeLookAndFeel() {
        // Just the look and feel

        // command line look and feel overrides the protege-specific one

        if (System.getProperty("swing.defaultlaf") == null) {
            // If the OS is a Mac then the Mac L&F is set by default.  I've had too many complaints
            // from Mac users that the first thing they do is switch the L&F over to OS X - the Protege
            // L&F might be nicer on other platforms with L&Fs like motif, but the OS X L&F looks much better
            // than the Protege L&F and, moreover, the keybindings for keys such as copy&paste in the
            // Protege L&F are hardcoded to be windows key bindings e.g. Copy is CTRL+C (where as on a
            // Mac it should be CMD+C)
            // I don't know if Windows users would prefer the Windows L&F to be the default one - although
            // the Windows L&F keybindings are the same as the Protege L&F keybindings.
            String defaultLAFClassName;
            if (OSUtils.isOSX()) {
                defaultLAFClassName = UIManager.getSystemLookAndFeelClassName();
            }
            else {
                defaultLAFClassName = ProtegeProperties.PLASTIC_LAF_NAME;
            }

            Preferences p = PreferencesManager.getInstance().getApplicationPreferences(LOOK_AND_FEEL_KEY);
            String lafClsName = p.getString(LOOK_AND_FEEL_CLASS_NAME, defaultLAFClassName);

            try {
                // This is a workaround for some OSGi "feature".  From here http://adamish.com/blog/archives/156.
                // Force the Look & Feel to be instantiated.
                UIDefaults defaults = UIManager.getDefaults();
                if (lafClsName.equals(PlasticLookAndFeel.class.getName())) {
                    // Truly strange.  If we don't do this then the LAF cannot be found.
                    PlasticLookAndFeel.setCurrentTheme(new ProtegePlasticTheme());
                    UIManager.put("ClassLoader", PlasticLookAndFeel.class.getClassLoader());
                    // For plastic this needs to be instantiated here - otherwise SwingUtilities uses the wrong class
                    // loaded.
                    LookAndFeel lookAndFeel = (LookAndFeel) Class.forName(lafClsName).newInstance();
                    UIManager.setLookAndFeel(lookAndFeel);
                }
                else {
                    // Now set the class loader for Component UI loading to this one.  Works for non Plastic LAFs.
                    UIManager.put("ClassLoader", this.getClass().getClassLoader());
                    UIManager.setLookAndFeel(lafClsName);
                }
                setupDefaults(defaults, lafClsName);

            } catch (Exception e) {
                logger.error("An error occurred during Look&Feel initialization", e);
            }
        }
    }

    private void setupDefaults(UIDefaults defaults, String lafClassName) {
        // TODO: Move this to somewhere more sensible

        defaults.put("TabbedPaneUI", CloseableTabbedPaneUI.class.getName());

        Color borderColor = new Color(220, 220, 220);

        MatteBorder commonBorder = BorderFactory.createMatteBorder(1, 1, 1, 1, borderColor);

        defaults.put("ScrollPane.border", BorderFactory.createCompoundBorder(commonBorder,
                BorderFactory.createEmptyBorder(1, 1, 1, 1)));
        defaults.put("TextArea.border", commonBorder);
        defaults.put("Spinner.border", commonBorder);

        defaults.put("TextField.border", BorderFactory.createCompoundBorder(commonBorder,
                BorderFactory.createEmptyBorder(2, 2, 2, 2)));

        defaults.put("SplitPane.border",
                BorderFactory.createEmptyBorder(0, 0, 0, 0));

        defaults.put("TitledBorder.border", commonBorder);

        Preferences rendererPrefs = PreferencesManager.getInstance().getApplicationPreferences(
                "org.protege.editor.owl.ui.renderer.OWLRendererPreferences"
        );

        defaults.put("Tree.paintLines", true);
        defaults.put("Tree.drawVerticalLines", true);
        defaults.put("Tree.drawHorizontalLines", true);
        // Set the color for non-Protege LAFS - the line color is too light for the dotted LAF.
        if (!ProtegeProperties.PLASTIC_LAF_NAME.equals(lafClassName)) {
            defaults.put("Tree.hash", new Color(230, 230, 230));
        }


        int fontSize = rendererPrefs.getInt("FONT_SIZE", 12);
        Fonts.updateUIDefaultsFontSize(fontSize);
    }

    private void setupExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logger.error("Uncaught Exception in thread '{}'", t.getName(), e);
        });
    }


    private void processCommandLineURIs() {
        try {
            commandLineURIs = new ArrayList<>();
            for (String arg : PlatformArguments.getArguments()) {
                logger.info("Processing command line argument: {}", arg);
                File f = new File(arg);
                if (f.exists()) {
                    logger.debug("Command line argument is a file");
                    commandLineURIs.add(f.toURI());
                }
                else {
                    try {
                        URI uri = new URI(arg);
                        commandLineURIs.add(uri);
                    } catch (URISyntaxException e) {
                        logger.error("An error occurred when processing the command line argument: {}", arg, e);
                    }
                }
            }
        } catch (Throwable t) { // it is not important enough to stop anything.
            logger.warn("Error processing command line arguments " + t);
        }
    }

    /////////////////////////////////////////////////////////////////////////////////
    //
    //  Implementation of Application
    //
    /////////////////////////////////////////////////////////////////////////////////


    private void startApplication() throws Exception {
        logger.debug("Starting application");
        if (commandLineURIs != null && !commandLineURIs.isEmpty()) {
            // Open any command line URIs
            for (URI uri : commandLineURIs) {
                logger.debug("Opening command line argument: {}", uri);
                createAndSetupDefaultEditorKit(uri);
            }
        }
        else {
            createAndSetupDefaultEditorKit();
        }
        //checkForUpdates();
    }

    private void checkForUpdates() {
        try {
            logger.info(LogBanner.start("Auto-update Check"));
            if (!PluginManager.getInstance().isAutoUpdateEnabled()) {
                logger.info("Auto-update is disabled");
                return;
            }
            if (hasAutoUpdateBeenRunToday()) {
                logger.info("Auto-update has been performed today.  Not performing it again.");
                return;
            }
            logger.info("Auto-update has not been performed today.  Running it.");
            PluginManager.getInstance().runAutoUpdate();
          
        } finally {
            logger.info(LogBanner.end());
        }

    }

    private boolean hasAutoUpdateBeenRunToday() {
        Date lastRun = PluginManager.getInstance().getLastAutoUpdateDate();
        logger.info("Auto-update last performed: {}", lastRun);
        Date startOfToday = getStartOfToday();
        return lastRun.after(startOfToday);
        
        //Temporarily return false for testing
       //return false;
    }

    private static Date getStartOfToday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        return calendar.getTime();
    }

    /**
     * Gets the default (first) editor kit factory plugin and uses it to create and setup and empty editor kit.
     */
    private void createAndSetupDefaultEditorKit() {
        try {
            logger.info("Creating and setting up empty (default) editor kit");
            ProtegeManager pm = ProtegeManager.getInstance();
            List<EditorKitFactoryPlugin> editorKitFactoryPlugins = pm.getEditorKitFactoryPlugins();
            if (!editorKitFactoryPlugins.isEmpty()) {
                EditorKitFactoryPlugin defaultPlugin = editorKitFactoryPlugins.get(0);
                pm.createAndSetupNewEditorKit(defaultPlugin);
            }
            else {
                ErrorMessage.showErrorMessage("Fatal Error", "An error occurred that prevented Protégé from starting");
            }
        } catch (Exception e) {
            ErrorLogPanel.showErrorDialog(e);
        }
    }


    private void createAndSetupDefaultEditorKit(final URI uri) {
        Runnable runnable = () -> {
            try {
                logger.info("Creating and setting up (default) editor kit for {}", uri);
                ProtegeManager pm = ProtegeManager.getInstance();
                List<EditorKitFactoryPlugin> editorKitFactoryPlugins = pm.getEditorKitFactoryPlugins();
                if (!editorKitFactoryPlugins.isEmpty()) {
                    EditorKitFactoryPlugin defaultPlugin = editorKitFactoryPlugins.get(0);
                    pm.createAndSetupNewEditorKit(defaultPlugin, uri);
                }
                else {
                    ErrorMessage.showErrorMessage("Fatal Error", "An error occurred that prevented Protégé from starting");
                }
            } catch (Exception e) {
                ErrorLogPanel.showErrorDialog(e);
            }
        };
        if(SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        }
        else {
            logger.debug("Current thread is not Event Dispatch Thread.  " +
                                 "Invoking later in the EDT (Current Thread: {})", Thread.currentThread());
            SwingUtilities.invokeLater(runnable);
        }
    }


    /////////////////////////////////////////////////////////////////////////////////
    //
    //  Visible Application Interfaces
    //
    /////////////////////////////////////////////////////////////////////////////////

   


    public static BackgroundTaskManager getBackgroundTaskManager() {
        return backgroundTaskManager;
    }


    public static boolean handleQuit() {
        quitting = true;
        final EditorKitManager eKitMngr = ProtegeManager.getInstance().getEditorKitManager();
        for (EditorKit eKit : eKitMngr.getEditorKits()) {
            Workspace wSpace = eKit.getWorkspace();
            if (!eKitMngr.getWorkspaceManager().doClose(wSpace)) {
                return false;
            }
        }
        System.out.println("JVM is exiting!!!!!");
        System.exit(0);
       /* try {
            boolean forceExit = !OSGi.systemExitHandledByLauncher(); // this call fails after context.getBundle(0).stop()
            //context.getBundle(0).stop();
            // Danger, Will Robinson!  Weird territory here - the class loader is no longer working!
            //  java.lang.IllegalStateException: zip file closed
            //     at java.util.zip.ZipFile.ensureOpen(ZipFile.java:403)
            //     at java.util.zip.ZipFile.getEntry(ZipFile.java:148)
            //     at org.apache.felix.framework.util.ZipFileX.getEntry(ZipFileX.java:52)
            //     at org.apache.felix.framework.cache.JarContent.getEntryAsBytes(JarContent.java:122)
            //     at org.apache.felix.framework.ModuleImpl$ModuleClassLoader.findClass(ModuleImpl.java:1816)
            //     at org.apache.felix.framework.ModuleImpl.findClassOrResourceByDelegation(ModuleImpl.java:727)
            //     at org.apache.felix.framework.ModuleImpl.access$400(ModuleImpl.java:71)
            //     at org.apache.felix.framework.ModuleImpl$ModuleClassLoader.loadClass(ModuleImpl.java:1768)
            //     at java.lang.ClassLoader.loadClass(ClassLoader.java:248)
            //     at org.protege.editor.core.ProtegeApplication.handleQuit(ProtegeApplication.java:418)
            if (forceExit) {
                Thread.sleep(1000);
                System.exit(0);
            }
        } catch (Throwable t) {
            logger.error("An error occurred when shutting down Protégé.", t);
        }*/
        return true;
    }


    public void handleClose() {
        if (!quitting) {
            final EditorKitManager eKitMngr = ProtegeManager.getInstance().getEditorKitManager();
            if (eKitMngr.getEditorKitCount() == 0) {
                handleQuit();
            }
        }
    }


    public void editURI(URI uri) throws Exception {
        logger.info("Received request to edit document at {}", uri);
        if (initialized) {
            logger.info("Application is initialized.  Opening URI.");
            createAndSetupDefaultEditorKit(uri);
        }
        else {
            logger.info("Application is not initialized.  Adding URI to command line URIs.");
            commandLineURIs.add(uri);
        }
    }


    public static void handleRestart() {

    }

    public static void showLogView() {
        logManager.showLogView();
    }

    public static LogManager getLogManager() {
        return logManager;
    }
}
