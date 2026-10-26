// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public
// License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of
// proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package gov.nih.nci.curator.cli;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import org.semanticweb.owlapi.model.OWLClass;

import gov.nih.nci.curator.owlapi.KnowledgeBase;
import gov.nih.nci.curator.taxonomy.printer.ClassTreePrinter;
import gov.nih.nci.curator.taxonomy.printer.TaxonomyPrinter;

/**
 * <p>
 * Title: PelletClassify
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
public class Classify extends Cmd {
	
	

	public Classify() {
	}

	@Override
	public String getAppCmd() {
		return "curator classify " + getMandatoryOptions() + "[options] <file URI>...";
	}

	@Override
	public String getAppId() {
		return "CuratorClassify: Classify the ontology and display the hierarchy";
	}

	@Override
	public CmdOptions getOptions() {
		CmdOptions options = getGlobalOptions();
		
		options.add( getLoaderOption() );
		options.add( getIgnoreImportsOption() );
		options.add( getInputFormatOption() );
		options.add( getClassifyTestOption() );
		
		return options;
	}

	@Override
	public void run() {
		runClassify();
		
	}
	
	/**
	 * Performs classification using the non-incremental (classic) classifier
	 */
	private void runClassify() {
		KnowledgeBase kb = getKB();
		
		startTask( "consistency check" );
		boolean isConsistent = kb.isConsistent();		
		finishTask( "consistency check" );

		if( !isConsistent )
			throw new CmdException( "Ontology is inconsistent, run \"curator explain\" to get the reason" );

		startTask( "classification" );
		long beg = System.currentTimeMillis();
		kb.classify();
		long end = System.currentTimeMillis() - beg;
		
		finishTask( "classification" );
		
		if (options.getOption("validate-classification").getValueAsBoolean()) {
			validateClassification(kb);
			
		}

		//TaxonomyPrinter<OWLClass> printer = new ClassTreePrinter();
		//printer.print( kb.getTaxonomyBuilder().getTaxonomy() );
		System.out.println("time to classify " + end);
	}
	
	private boolean validateClassification(KnowledgeBase kb) {
		String filename = getInputFiles()[0];
		String val_filename = filename.replaceFirst(".owl",  ".val");
		boolean success = true;
		
		try {
			BufferedReader br = new BufferedReader(new FileReader(val_filename));	
			
		    String line = br.readLine();
		    
		    
		    

		    while (line != null) {
		    	String[] entry = line.split(" ");
		    	if (kb.isSubClassOf(kb.getClass(entry[0]), kb.getClass(entry[1]))) {
		    		
		    	} else {
		    		success = false;
		    		System.out.println("Validate failed: \n" + "Class: " + entry[0]
		    				+ " should be a subclass of: " + entry[1]);
		    		
		    	}
		        line = br.readLine();
		    }
		    br.close();
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		return success;
	}
	
	
	
	
	
	
}
