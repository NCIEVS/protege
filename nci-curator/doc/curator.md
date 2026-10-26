# Curation of NCI Thesaurus

## Introduction
NCI Thesaurus is now very large, as modelers have been adding very complex terms for many years now. It's roughly a million axioms or about 150K classes. Curation of the modeler's work is assisted using the [`NCI-EditTab`](https://github.com/NCIEVS/nci-edit-tab), a Protege plugin that provides a number of feature such as splits, merges, clones, etc.. as well as enforcing certain [edit rules](https://github.com/NCIEVS/nci-protege5/wiki/editing-rules-enforced-by-nci-protege). 

Additionally a description logic classifier, [pellet](https://github.com/NCIEVS/pellet), provides OWL reasoning over the ontology. As the Thesaurus has grown it's evolved in such a way that only a small subset of the features provided by pellet are used daily. Moreover the sheer size of the Thesaurus and the number of relationships is pushing the limits of what this style of classifier is capable of.

As a result we've decided to implement a lighter weight classifier that ony supports the features needed for the Thesaurus work. This classifier will take the approach of walking the graph of the ontology, making comparisons of terms to compute the subsumption relation. Since the Thesaurus doesn't have instances there is no need to distinguish a T-Box from an A-Box, and many of the features such as negation are also not required.

This lighter weight classifier is intended to act more as a curation tool, to be used as an extension of the NCI-EditTab plugin. In a typical workflow, a modeler might add a new class, or edit and existing one, adding new object properties that make up the definition. After saving the terms, they classify the content to see if any inconsistencies arise, .eg. adding new parents that are disjoint. If the classifier find new relationships, the modeler often will just add these inferences as stated assertions about the class. When the classifier is run again, they expect it to then find nothing new as everything inferred is now asserted.

So we view this classifier as more of a curator than a classifier.


## Overview of Curator algorithms

The approach is based on structural comparison of terms and graph walking on the ontology.This is quite different from the tableaux method used by Pellet, which involves building models.

The basic idea is to view the ontology as one large graph where the connections are given by the subsumption relation. Subsumption is an ordering relation, we say `A subsume B` and mean that `A > B` in the ordering. When a new class is introduced, classification is the process of determining where in the ordering to place it, .ie. which classes `subsume` the new class and which are `subsumed`

````
Classify(Kb) ->
  Kb = topologically_sort(Kb); 
  for (each Class in Kb) {
    LUBs = find_lubs(Class, Top);
    GLBs = find_glbs(Class, LUBs);
    if (size(LUBs) == 1 and size(GLBs) == 1 and LUBS[0] == GLBs[0]) {
      (Class isEquivTo LUBs[0])
    }
    for (each Class_s in LUBs) {
      add_superc(Class, Class_s);
    }
    for (each Class_s in GLBs) {
      add_superc(Class_s, Class)
    }
  }
````
Both `find_lubs` and `find_glbs` are recursive, the collection of classes discovered is passed along through the recursion as the computation proceeds.

````
;; depth first walk of the Kb checking which classes subsume the given class
;; walk stops when a class subsumes the new class but none of it's children do

find_lubs(Class, RootClass) ->
  LUBs = EmptySet;
  for (each Class_c in children(RootClass)) {
    if subsumes(Class_c, Class) {
      NewLUBs = find_lubs(Class, Class_c);
      if isEmpty(NewLUBs) {
        add(Class_c, LUBs);
      } else {
        add(filterSups(NewLUBs), LUBS);
      }
    }
  }
  
;; continue the depth firat walk of the Kb, but beginning with the LUBs 
;; the first part of the algorithm
  
find_glbs(Class, LUBs) ->
  GLBs = EmptySet;
  for (each Class_i in LUBs) {
    NewGLBs = find_glbs1(Class, LUBs, Class_i);
    add(filterSups(NewGLBs), GLBs);
  }
  return GLBs;

find_glbs1(Class, LUBs, ClassRoot) ->
   GLBs = EmptySet;
   for (each Class_c in children(ClassRoot)) {
      boolean ok = true;
      for (each Class_g in LUBs) {
        if subsumes(Class_g, Class_c) {
        } else {
          ok = false;
        }
      }
      if ok {
        if subsumes(Class, Class_c) {
          add(Class_c, GLBs);
        } else {
          NewGLBs = find_glbs1(Class, LUBs, Class_c);
          add(filterSups(NewGLBs), GLBs);
        }
      }
    }
    return GLBs;
````

Subsumption checking is a pairwise operation. It is done only using local information on the classes. The assumption is that when we want ask if `A subsumes B` we already know all of `A`'s parents do, those tests have already occured as the `find_lubs` recursion proceeds

````
subsumes(A, B) ->
  for (each PrimClass_i in parents(A)) {
    isSubClass(B, PrimClass_i);
  }
  
  for (each role_i in localRoles(A)) {
    hasRole(B, role_j) && roleSubsumes(role_i, role_j);
  }
  
````
where `roleSubsumes` is a function that looks at modifiers, cardinalities, and whether fillers are in the subsumption relation recursively.



