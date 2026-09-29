package org.utility;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import java.io.File;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sizes the target for the PAC budget. computeOntologySize() is the h in
 * Pac's round((h·ln|X| − ln δ)/ε), so it sets the sample budget of every run.
 *
 * TODO: an exhaustive enumeration of X would let us check
 * Pac.computeInstanceSpaceSize(). The version removed after 634f414
 * (getAllPossibleAxiomsCombinationsOWL) did not match Pac's space: it took
 * conjunctions over unordered distinct pairs, where Pac counts ordered pairs
 * and allows A = B.
 */
public class OntologyManipulator {

    /**
     * The number of the target's SubClassOf and EquivalentClasses axioms: 138 on
     * the OWL2Bench target (121 + 17), for C1, C2 and C3 alike. It is a count,
     * not a size -- "Size of T" in the run statistics is a different measure --
     * and each EquivalentClasses axiom counts once, however complex its sides.
     */
    public static int computeOntologySize(String ontologyFileName) {
        if (!new File(ontologyFileName).exists()) {
            throw new IllegalArgumentException("Ontology not found: " + ontologyFileName
                    + " -- PACLO datasets live in " + PacloDataset.DATA_DIR
                    + "/<dataset>/, which is gitignored: copy the folder in by hand.");
        }
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = null;
        try {
            ontology = manager.loadOntologyFromOntologyDocument(new File(ontologyFileName));
        } catch (OWLOntologyCreationException e) {
            throw new RuntimeException(e);
        }
        return OntologyManipulator.filterUnusedAxioms(ontology.getAxioms()).size();
    }

    /**
     * Keeps only the axiom types the learner can learn. Property axioms
     * (SubObjectPropertyOf, EquivalentObjectProperties, ObjectPropertyDomain)
     * and DisjointClasses are left out; counting them too gave h = 250 before
     * commit b7b3a90 (2026-08-19).
     */
    public static Set<OWLAxiom> filterUnusedAxioms(Set<OWLAxiom> axioms) {
        return axioms.stream().filter(axiom -> axiom.isOfType(AxiomType.SUBCLASS_OF)
                        || axiom.isOfType(AxiomType.EQUIVALENT_CLASSES))
                .collect(Collectors.toSet());
    }
}
