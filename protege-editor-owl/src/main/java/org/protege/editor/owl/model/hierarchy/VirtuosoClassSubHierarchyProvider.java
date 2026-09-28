package org.protege.editor.owl.model.hierarchy;

import java.util.Collections;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A {@link VirtuosoClassHierarchyProvider} scoped to a single settable root, for the object-property
 * restriction filler picker: the tree shows the range class and its lazily-fetched subtree from
 * Virtuoso, so it populates on demand rather than showing only the classes already materialised in
 * the sparse in-RAM ontology (which is what {@link AssertedClassSubHierarchyProvider} yields under
 * the lazy model). The open-time warm-up is disabled: this provider is transient and must not
 * prefetch owl:Thing's roots or accumulate configure callbacks.
 */
public class VirtuosoClassSubHierarchyProvider extends VirtuosoClassHierarchyProvider
        implements RootableClassHierarchyProvider {

    private volatile OWLClass root;

    public VirtuosoClassSubHierarchyProvider(OWLOntologyManager manager) {
        super(manager);
    }

    @Override
    protected void registerWarmUp() {
        // transient picker provider: no open-time warm-up
    }

    @Override
    public Set<OWLClass> getRoots() {
        return root != null ? Collections.singleton(root) : Collections.<OWLClass>emptySet();
    }

    @Override
    public void setRoot(OWLClass r) {
        this.root = r;
        clearCaches();
        fireHierarchyChanged();
    }
}
