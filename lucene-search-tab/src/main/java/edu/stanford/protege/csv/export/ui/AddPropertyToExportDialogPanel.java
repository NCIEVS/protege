package edu.stanford.protege.csv.export.ui;

import org.protege.editor.core.ui.util.InputVerificationStatusChangedListener;
import org.protege.editor.core.ui.util.JOptionPaneEx;
import org.protege.editor.core.ui.util.VerifiedInputEditor;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.client.ClientSession;
import org.protege.editor.owl.client.LocalHttpClient;
import org.protege.editor.owl.model.find.OWLEntityFinder;
import org.protege.editor.owl.ui.renderer.OWLCellRenderer;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;

import edu.stanford.protege.metaproject.api.Project;
import edu.stanford.protege.metaproject.api.ProjectOptions;
import edu.stanford.protege.metaproject.api.exception.UnknownProjectIdException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.util.*;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Rafael Gonçalves <br>
 * Center for Biomedical Informatics Research <br>
 * Stanford University
 */
public class AddPropertyToExportDialogPanel extends JPanel implements VerifiedInputEditor {
    private static final long serialVersionUID = 1338817119909899530L;
    private List<InputVerificationStatusChangedListener> listeners = new ArrayList<>();
    private OWLEditorKit editorKit;
    private JLabel filterLbl, propertiesLbl, propertySelectionLbl;
    private JTextField filterTextField;
    private JList<OWLEntity> propertiesList;
    private List<OWLEntity> allPropertiesList, filteredPropertiesList;
    private boolean currentlyValid = false;
    private List<OWLEntity> selectedProperties, propertiesToExclude;
    private Map<OWLEntity, List<OWLEntity>> depMap;
    private SortedListModel<OWLEntity> listModel = null;
    public final String COMPLEX_PROPS = "complex_properties";
    private Set<OWLAnnotationProperty> complexProperties = new HashSet<OWLAnnotationProperty>();
    private List<OWLEntity> requiredAnnotationPropertyList = new ArrayList<OWLEntity>();
    private OWLOntology ontology;
    private OWLEntity entity;

    /**
     * Constructor
     *
     * @param editorKit OWL Editor Kit
     * @param propertiesToExclude  List of OWL entities containing properties to exclude as they are already added
     */
    public AddPropertyToExportDialogPanel(OWLEditorKit editorKit, List<OWLEntity> propertiesToExclude) {
    	this(editorKit, propertiesToExclude, new HashMap<OWLEntity, List<OWLEntity>>());
    }
    
    public AddPropertyToExportDialogPanel(OWLEditorKit editorKit, List<OWLEntity> propertiesToExclude,
    		Map<OWLEntity, List<OWLEntity>> depMap) {
        this(editorKit, propertiesToExclude, depMap, null);
    }

    public AddPropertyToExportDialogPanel(OWLEditorKit editorKit, List<OWLEntity> propertiesToExclude,
    		Map<OWLEntity, List<OWLEntity>> depMap, OWLEntity entity) {
        this.editorKit = checkNotNull(editorKit);
        listModel = new SortedListModel<>(editorKit);
        this.propertiesToExclude = checkNotNull(propertiesToExclude);
        this.depMap = depMap;
        this.ontology = editorKit.getModelManager().getActiveOntology();
        this.entity = entity;
        initUi();
    }
    
    private void initUi() {
        setLayout(new GridBagLayout());
        setupList();

        filterLbl = new JLabel("Filter:");
        propertiesLbl = new JLabel("Properties:");
        propertySelectionLbl = new JLabel();        
        filterTextField = new JTextField();
        filterTextField.getDocument().addDocumentListener(filterTextListener);

        JScrollPane propertiesScrollpane = new JScrollPane(propertiesList);
        propertiesScrollpane.setBorder(UiUtils.MATTE_BORDER);
        propertiesScrollpane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        int widest = UiUtils.getWidestEntityStringRendering(editorKit, allPropertiesList, getFontMetrics(getFont()));
        propertiesScrollpane.setPreferredSize(new Dimension(widest, 250));

        Insets insets = new Insets(2, 2, 2, 2);
        int rowIndex = 0;
        add(propertiesLbl, new GridBagConstraints(0, rowIndex, 1, 1, 0.0, 0.0, GridBagConstraints.BASELINE_LEADING, GridBagConstraints.NONE, insets, 0, 0));
        add(propertySelectionLbl, new GridBagConstraints(1, rowIndex, 1, 1, 1.0, 0.0, GridBagConstraints.BASELINE_TRAILING, GridBagConstraints.NONE, insets, 0, 0));
        rowIndex++;
        add(propertiesScrollpane, new GridBagConstraints(0, rowIndex, 2, 1, 1.0, 1.0, GridBagConstraints.BASELINE_LEADING, GridBagConstraints.BOTH, insets, 0, 0));
        rowIndex++;
        add(filterLbl, new GridBagConstraints(0, rowIndex, 1, 1, 0.0, 0.0, GridBagConstraints.BASELINE_LEADING, GridBagConstraints.NONE, new Insets(6, 2, 2, 2), 0, 0));
        add(filterTextField, new GridBagConstraints(1, rowIndex, 1, 1, 1.0, 0.0, GridBagConstraints.BASELINE_LEADING, GridBagConstraints.HORIZONTAL, new Insets(6, 2, 2, 2), 0, 0));
    }

    private void setupList() {
        propertiesList = new JList<>();
        propertiesList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        
        propertiesList.setCellRenderer(new IncQualsOWLCellRenderer(editorKit, depMap));
        propertiesList.setFixedCellHeight(16);
        propertiesList.setModel(listModel);
        propertiesList.setBorder(new EmptyBorder(2, 2, 0, 2));
        
        //if (propertiesToExclude != null && propertiesToExclude.size() > 0) {
        if (this.entity != null) {
	        //OWLEntity propToExclude = propertiesToExclude.get(0);
	        //if (propToExclude instanceof OWLAnnotationProperty && isComplexProperty((OWLAnnotationProperty) propToExclude)) {	
        	if (this.entity instanceof OWLAnnotationProperty && isComplexProperty((OWLAnnotationProperty) this.entity)) {
	        	//allPropertiesList = getRequiredAnnotationList((OWLAnnotationProperty)propToExclude);
        		allPropertiesList = getRequiredAnnotationList((OWLAnnotationProperty)this.entity);
	        } else {
	        	allPropertiesList = UiUtils.getProperties(editorKit);
	        }
        }else {
        	allPropertiesList = UiUtils.getProperties(editorKit);
        }
        if(!propertiesToExclude.isEmpty()) {
            allPropertiesList.removeAll(propertiesToExclude);
        }
       
        
        listModel.addAll(allPropertiesList);        
        propertiesList.addListSelectionListener(listSelectionListener);
        
        
    }

    private ListSelectionListener listSelectionListener = e -> {
        selectedProperties = propertiesList.getSelectedValuesList();
        propertySelectionLbl.setText("(" + selectedProperties.size() + " selected)");
        checkInputs();
    };

    private DocumentListener filterTextListener = new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
            filterTextField();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            filterTextField();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            filterTextField();
        }
    };

    public List<OWLEntity> getSelectedProperties() {
        return selectedProperties;
    }

    private void checkInputs() {
        boolean allValid = true;
        if (selectedProperties == null || selectedProperties.isEmpty()) {
            allValid = false;
        }
        setValid(allValid);
    }

    private void setValid(boolean valid) {
        currentlyValid = valid;
        for (InputVerificationStatusChangedListener l : listeners) {
            l.verifiedStatusChanged(currentlyValid);
        }
    }

    private void filterTextField() {
        String toMatch = filterTextField.getText();
        if(toMatch.isEmpty()) {
            listModel.clear();
            listModel.addAll(allPropertiesList);
            return;
        }
        OWLEntityFinder finder = editorKit.getModelManager().getOWLEntityFinder();
        List<OWLEntity> output = new ArrayList<>();
        Set<OWLObjectProperty> entities = finder.getMatchingOWLObjectProperties(toMatch);
        for(OWLEntity e : entities) {
            if (allPropertiesList.contains(e)) {
                output.add(e);
            }
        }
        
        Set<OWLAnnotationProperty> entities2 = finder.getMatchingOWLAnnotationProperties(toMatch);
        for(OWLEntity e : entities2) {
            if (allPropertiesList.contains(e)) {
                output.add(e);
            }
        }
        
        Set<OWLDataProperty> entities3 = finder.getMatchingOWLDataProperties(toMatch);
        for(OWLEntity e : entities3) {
            if (allPropertiesList.contains(e)) {
                output.add(e);
            }
        }
        
        filteredPropertiesList = new ArrayList<>(output);
        //Collections.sort(filteredPropertiesList);
        
        listModel.clear();
        listModel.addAll(filteredPropertiesList);
    }

    public static Optional<List<OWLEntity>> showDialog(OWLEditorKit editorKit, List<OWLEntity> propertiesToExlude) {
        AddPropertyToExportDialogPanel panel = new AddPropertyToExportDialogPanel(editorKit, propertiesToExlude);
        int response = JOptionPaneEx.showValidatingConfirmDialog(
                editorKit.getOWLWorkspace(), "Choose properties to export", panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null);
        if (response == JOptionPane.OK_OPTION) {
            return Optional.ofNullable(panel.getSelectedProperties());
        }
        return Optional.empty();
    }
    
    public static Optional<List<OWLEntity>> showDialog(OWLEditorKit editorKit, List<OWLEntity> propertiesToExlude,
    		Map<OWLEntity, List<OWLEntity>> depMap, OWLEntity ent) {
        AddPropertyToExportDialogPanel panel = new AddPropertyToExportDialogPanel(editorKit, propertiesToExlude,
        		depMap, ent);
        int response = JOptionPaneEx.showValidatingConfirmDialog(
                editorKit.getOWLWorkspace(), "Choose properties to export", panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null);
        if (response == JOptionPane.OK_OPTION) {
            return Optional.ofNullable(panel.getSelectedProperties());
        }
        return Optional.empty();
    }

    @Override
    public void addStatusChangedListener(InputVerificationStatusChangedListener listener) {
        listeners.add(listener);
        listener.verifiedStatusChanged(currentlyValid);
    }

    @Override
    public void removeStatusChangedListener(InputVerificationStatusChangedListener listener) {
        listeners.remove(listener);
    }
    
    public Set<OWLAnnotationProperty> getComplexProperties() {
		if (complexProperties.size() == 0) {
			loadComplexProperties();
		}
		return complexProperties;
	}
    
    private void loadComplexProperties() {
    	ClientSession clientSession = ClientSession.getInstance(editorKit);
    	LocalHttpClient lhc = (LocalHttpClient) clientSession.getActiveClient();
		if (lhc != null) {
			Project project = null;
			try {
				project = lhc.getCurrentConfig().getProject(clientSession.getActiveProject());
			} catch (UnknownProjectIdException e) {
				e.printStackTrace();
			}

			if (project != null) {
				com.google.common.base.Optional<ProjectOptions> options = project.getOptions();
				
				Set<String> not_found_props = new HashSet<String>();

				if (options.isPresent()) {
					ProjectOptions opts = options.get();
					Set<String> complex_props = opts.getValues(COMPLEX_PROPS);
					if (complex_props != null) {						
						for (String cp : complex_props) {
							OWLAnnotationProperty p = lookUp(cp);
							if (p != null) {
								complexProperties.add(p);
							} else {
								not_found_props.add(cp);
							}
							
						}
						if (not_found_props.size() > 0) {
							String msg = "Missing Properties: \n";
							for (String prop : not_found_props) {
								msg += prop + "\n";
							}
							JOptionPane.showMessageDialog(this, msg, "Warning", JOptionPane.WARNING_MESSAGE);
						}
					}
				}
			}
		}
    }
				
	OWLAnnotationProperty lookUp(String iri) {
		IRI cpIRI = IRI.create(iri);
		return lookUpIri(cpIRI);
	}
	
	OWLAnnotationProperty lookUpIri(IRI cpIRI) {
		Set<OWLAnnotationProperty> annProps = ontology.getAnnotationPropertiesInSignature();
		for (OWLAnnotationProperty ap : annProps) {
			if (ap.getIRI().equals(cpIRI)) {
				IRI dt = getDataType(ap);
				if (dt != null) {
					//System.out.println(cpIRI + " it's type: " + dt);
				} else {
					//System.out.println(cpIRI);

				}
				return ap;	
			}
		}
		return null;
	}

	public IRI getDataType(OWLAnnotationProperty prop) {
		Set<OWLAnnotationPropertyRangeAxiom> types = ontology.getAnnotationPropertyRangeAxioms(prop);
		
		for (OWLAnnotationPropertyRangeAxiom ax : types) {
			return ax.getRange();
		}
		return null;
	}
	
    private boolean isComplexProperty(OWLAnnotationProperty prop) {
    	
		if (complexProperties.size() == 0) {
			getComplexProperties();
		}
		
		if(complexProperties.contains(prop)) {
			return true;
		}
		return false;
	}
    
    private List<OWLEntity> getRequiredAnnotationList(OWLAnnotationProperty prop) {
    	ClientSession clientSession = ClientSession.getInstance(editorKit);
    	LocalHttpClient lhc = (LocalHttpClient) clientSession.getActiveClient();
		if (lhc != null) {
			Project project = null;
			try {
				project = lhc.getCurrentConfig().getProject(clientSession.getActiveProject());
			} catch (UnknownProjectIdException e) {
				e.printStackTrace();
			}

			if (project != null) {
				com.google.common.base.Optional<ProjectOptions> options = project.getOptions();
				if (options.isPresent()) {
					ProjectOptions opts = options.get();
					Set<String> dependents = opts.getValues(prop.getIRI().toString());
					if (dependents != null) {
						for (String dp : dependents) {
							OWLAnnotationProperty dpProp = lookUp(dp);
							requiredAnnotationPropertyList.add(dpProp);
							/*if (dpProp != null) {
								// always add to cprops
								cprops.add(dpProp);
								if (is_required(dpProp)) {
									dprops.add(dpProp);
								} else {
									oprops.add(dpProp);
								}
							} else {
								not_found_props.add(dp);
							}	*/							
						}
					}
				}
			}
		}
		
    	return requiredAnnotationPropertyList;
    }
	
}
