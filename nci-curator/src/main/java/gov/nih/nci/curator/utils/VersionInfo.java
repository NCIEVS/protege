/*
 * Created on Mar 28, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package gov.nih.nci.curator.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * @author ronwalf
 * 
 * Automatic (from ant) version information for Pellet
 */
public class VersionInfo {
    private Properties versionProperties = null;

    private static String UNKNOWN = "(unknown)";

    public VersionInfo() {
        versionProperties = new Properties();
        // System.out.print(VersionInfo.class.getResource(""));

        InputStream vstream = VersionInfo.class
            .getResourceAsStream( "/gov/nih/nci/curator/version.properties" );
        if( vstream != null ) {
            try {
                versionProperties.load( vstream );
            }
            catch( IOException e ) {
                System.err.println( "Could not load version properties:" );
                e.printStackTrace();
            } 
            finally {
            	try {
					vstream.close();
				} catch (IOException e) {
					System.err.println( "Could not close version properties:" );
	                e.printStackTrace();
				}
            }
        }
    }
    
    public static final VersionInfo getInstance() {
    	return new VersionInfo();
    }

    public String getVersionString() {
        return versionProperties.getProperty( "gov.nih.nci.curator.version", "(unreleased)" );
    }

    public String getReleaseDate() {
        return versionProperties.getProperty( "gov.nih.nci.curator.releaseDate", UNKNOWN );
    }
    
    public String toString() {
		return "Version: " + getVersionString()  + " Released: " + getReleaseDate();
    }
}
