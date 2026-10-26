// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com
package gov.nih.nci.curator.exceptions;

public class TimerInterruptedException extends CuratorRuntimeException {

	private static final long	serialVersionUID	= 2528189321875269169L;

	public TimerInterruptedException() {
		super();
	}

	public TimerInterruptedException(String s) {
		super( s );
	}

	public TimerInterruptedException(Throwable e) {
		super( e );
	}
}
