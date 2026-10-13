package org.protege.editor.owl.model.triplestore;

/**
 * Gate for the stock Protégé entity-editing actions under the lazy/server model (the "two-mode"
 * design). When a Virtuoso-backed project is open the generic Classes/Properties/Individuals/
 * Datatypes create and delete actions are disabled: they compute over the sparse lazy active
 * ontology, so a delete would orphan references that live only in Virtuoso and a create would
 * bypass the NCI editing workflow. Editing in that mode goes through the NCI EditTab instead.
 *
 * <p>A locally file-opened ontology has no triple-store context, so {@link #stockEditingDisabled()}
 * is false and full stock editing is retained.
 */
public final class LazyEditGuard {

    private LazyEditGuard() {
    }

    public static boolean stockEditingDisabled() {
        return TripleStoreContext.getInstance().isActive();
    }
}
