// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.cli;

/**
 * <p>
 * Title: PelletCmdException
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2008
 * </p>
 * <p>
 * Company: Clark & Parsia, LLC. <http://www.clarkparsia.com>
 * </p>
 * 
 * @author Markus Stocker
 */
public class CmdException extends RuntimeException {

	/**
	 * Create an exception with the given error message.
	 * 
	 * @param msg
	 */
	public CmdException(String msg) {
		super( msg );
	}
	
	public CmdException(Throwable cause) {
		super( cause );
	}
}
