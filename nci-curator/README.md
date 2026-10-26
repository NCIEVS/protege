# NCI Curator
Enhanced post editing curation for [NCI EditTab][2]

Curator is a plugin that extends NCI EditTab with more curation capabilities. It behaves like a classifier, similar to Pellet, but is also integrated with EditTab so that certain inferences can be handled at edit time.

Increasingly editing of terminology at NCI is quite complicated. Complex operations such as splitting, merging, and cloning classes, involve multiple steps. When concepts are retired they aren't really deleted, but instead are re-treed into an archive branch. Other concepts that refer to them need to have those references repaired. The terminology has some interesting characteristics that have developed over time:

 * A fixed set of object properties (roles), that are used by all modelers. Adding a new role or deleting one, is hardly ever done as it creates a major change in the modeling.
 * A fixed set of root concepts, all of which are pairwise disjoint. These top level nodesin a sense represent things that are very different from one another. In other description logics they'd be called kinds.
 * domains and ranges for object properties. These are also fixed and rarely changed.

These features of the terminology allow assumptions to be made and best practices to be enforced among terminology editors. This is largely what EditTab does. When users are adding object properties, which properties they are allowed to use is based on the concept they are editing and what domain it belongs to. When they select a filler for the property it's also constrained by the range. Additionally EditTab also enforces a number of rules pertaing to annotations.

Curator extends these capabilities by introducing a very lightweight classifier that is highly customized for the Thesaurus, NCI's terminology. This classifier takes a [structural approach][1], which amounts to graph walking. As it only supports a small subset of OWL DL, we feel it will provide a more scalable solution as the terminology grows. It behaves the same way as existing classifiers in that it fits neatly into the defined protege interfaces to OWL reasoners. When configuring it, users can select items that are supported as edit time:

 * check for disjoint primitives
 * check for redundant parents
 * check for unsupported constructs

These checks will prompt the user with a warning and allow them to back out the edits or accept them anyway.





----
[1]: https://github.com/bdionne/nci-curator/blob/master/doc/curator.md
[2]: https://github.com/bdionne/nci-edit-tab
