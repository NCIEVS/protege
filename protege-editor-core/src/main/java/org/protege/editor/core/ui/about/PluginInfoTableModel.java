package org.protege.editor.core.ui.about;


import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import javax.swing.table.AbstractTableModel;

import org.protege.editor.core.ProtegeApplication;
import org.protege.editor.core.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Author: Matthew Horridge<br>
 * The University Of Manchester<br>
 * Medical Informatics Group<br>
 * Date: 01-Sep-2006<br><br>
 * <p/>
 * matthew.horridge@cs.man.ac.uk<br>
 * www.cs.man.ac.uk/~horridgm<br><br>
 */
public class PluginInfoTableModel extends AbstractTableModel {

    /**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private static final Logger logger = LoggerFactory.getLogger(PluginInfoTableModel.class);
	
	private List<PluginVersion> bundles;
    public enum Columns {
        NAME("Name/ID"), VERSION("Version"), QUALIFIER("Qualifier");

        private String name;

        private Columns(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }


    public PluginInfoTableModel() {
    	bundles = getPluginVersions();
    }

    public int getRowCount() {
        return bundles.size();
    }


    public int getColumnCount() {
        return Columns.values().length;
    }


    public Object getValueAt(int rowIndex, int columnIndex) {
    	PluginVersion pluginversion = bundles.get(rowIndex);
        Version v = pluginversion.getVersion();
        switch (Columns.values()[columnIndex]) {
            case NAME:
            	return pluginversion.getName();
            case VERSION:
                return v == null ? "" : "" + v.getMajor() + "." + v.getMinor() + "." + v.getMicro();
            case QUALIFIER:
                return v == null ? "" : v.getQualifier();
            default:
                throw new RuntimeException("Programmer error - missed a case");
        }
    }


    public String getColumnName(int column) {
        return Columns.values()[column].getName();
    }
    
    List<PluginVersion> getPluginVersions(){
    	
    	List<PluginVersion> list = new ArrayList<PluginVersion>();
    	
    	File pluginsFolder = new File(System.getProperty(ProtegeApplication.PLUGIN_DIR_PROP));
				
		
			if (pluginsFolder.isDirectory()) {
				File[] files = pluginsFolder.listFiles((dir, name) -> {
					if (name.toLowerCase().endsWith(".jar")) {
						return true;
					}
					return false;

				});
				for (File f : files) {
					try (JarInputStream is = new JarInputStream(new FileInputStream(f))) {
			            Manifest mf = is.getManifest();
			            if(mf == null) {
			            	logger.warn("Skipping plugin jar without a manifest: {}", f.getName());
			            	continue;
			            }
			            Attributes attributes = mf.getMainAttributes();
			            String name = attributes.getValue("Bundle-Name");
			            if (name == null) {
			            	name = attributes.getValue("Bundle-SymbolicName");
			            }
			            if (name == null) {
			            	name = f.getName();
			            }
			            String versionString = attributes.getValue("Bundle-Version");
			            Version version = null;
			            if (versionString != null) {
			            	try {
			            		version = new Version(versionString);
			            	} catch (Exception ve) {
			            		logger.warn("Plugin {} has an unparseable Bundle-Version '{}'", f.getName(), versionString);
			            	}
			            }
			            list.add(new PluginVersion(name, version));
			        } catch (Exception e) {
			        	// A single malformed plugin jar must not break the whole plugins panel.
			        	logger.warn("Skipping plugin jar {} (could not read manifest)", f.getName(), e);
			        }
				}

			}
		Collections.sort(list,(a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
    	return list;
    	
    }
    
	class PluginVersion {
		String name;
		Version version;

		public PluginVersion(String n, Version v) {
			this.name = n;
			this.version = v;
		}
		
		String getName() {
			return name;
		}
		
		Version getVersion() {
			return version;
		}
	}
    }
    
