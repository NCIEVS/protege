package org.protege.editor.owl.model.hierarchy;

import org.semanticweb.owlapi.model.OWLClass;

/**
 * A class-hierarchy provider whose single root can be reset, used to scope a selector tree to a
 * range subtree (e.g. the object-property restriction filler picker). Implemented by both the
 * in-RAM {@link AssertedClassSubHierarchyProvider} and the lazy {@link VirtuosoClassSubHierarchyProvider}.
 */
public interface RootableClassHierarchyProvider {

    void setRoot(OWLClass root);
}
