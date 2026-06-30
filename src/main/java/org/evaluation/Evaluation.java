package org.evaluation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class Evaluation {
    private Logger logger = LogManager.getLogger("OracleEvaluation");

    public void evaluate(OWLOntology resultOntology, OWLReasoner expertReasoner, Set<OWLClassExpression> baseSet, OWLReasoner initialOntologyReasoner) {
        Instant start = Instant.now();

        OWLOntologyManager manager = resultOntology.getOWLOntologyManager();
        OWLClass owlNothing = manager.getOWLDataFactory().getOWLNothing();
        Set<OWLSubClassOfAxiom> disjointnessAxioms = resultOntology.subClassAxiomsForSuperClass(owlNothing)
                    .collect(Collectors.toSet());
        System.out.println(disjointnessAxioms.size() + " disjointness axioms");
        for (OWLSubClassOfAxiom a : disjointnessAxioms) {
            System.out.println(a);
        }
        manager.removeAxioms(resultOntology, disjointnessAxioms);

        resultOntology.add(expertReasoner.getRootOntology().getABoxAxioms(Imports.INCLUDED));

        OWLReasonerFactory rf = new ElkReasonerFactory();
        OWLReasoner resultReasoner = rf.createReasoner(resultOntology);
        resultReasoner.precomputeInferences(InferenceType.OBJECT_PROPERTY_HIERARCHY);
        resultReasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
        resultReasoner.precomputeInferences(InferenceType.CLASS_ASSERTIONS);
        resultReasoner.precomputeInferences(InferenceType.OBJECT_PROPERTY_ASSERTIONS);

        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        System.out.println("Result ontology classified: " + timeElapsed + " ms");

        start = Instant.now();

        for (OWLSubClassOfAxiom axiom : resultOntology.getAxioms(AxiomType.SUBCLASS_OF)) {
            System.out.println("***Subclass Axiom***");
            OWLClassExpression subClass = axiom.getSubClass();
            System.out.println("Subclass: " + subClass);
            Set<OWLNamedIndividual> subClassIndividuals = expertReasoner.getInstances(subClass, false).getFlattened();
            OWLClassExpression superClass = axiom.getSuperClass();
            System.out.println("Superclass: " + superClass);
            Set<OWLNamedIndividual> superClassIndividuals = expertReasoner.getInstances(superClass, false).getFlattened();
            Set<OWLNamedIndividual> intersection = new HashSet<>(subClassIndividuals);
            intersection.retainAll(superClassIndividuals);
            float confidence = intersection.size() > 0 ? (float) intersection.size() / subClassIndividuals.size() : 1;
            System.out.println("Confidence = " + intersection.size() + "/" + subClassIndividuals.size() + " = " + confidence);
        }
        System.out.println("*********\n");

        float sum_of_precisions = 0;
        float sum_of_recalls = 0;
        int counter = 0;
        int allInferred = 0;
        int allInferredResult = 0;
        int allShared = 0;
        for (OWLClassExpression ce : baseSet) {
            Set<OWLNamedIndividual> instancesInitialOntology = initialOntologyReasoner.getInstances(ce).getFlattened();
            Set<OWLNamedIndividual> inferredIndividuals = expertReasoner.getInstances(ce, false).getFlattened();
            Set<OWLNamedIndividual> inferredIndividualsResult = resultReasoner.getInstances(ce, false).getFlattened();
            if (!inferredIndividuals.isEmpty() || !inferredIndividualsResult.isEmpty()) {
                System.out.println("ce:" + ce);
                allInferred += inferredIndividuals.size();
                allInferredResult += inferredIndividualsResult.size();
                Set<OWLNamedIndividual> shared = new HashSet<OWLNamedIndividual>(inferredIndividuals);
                shared.retainAll(inferredIndividualsResult);
                allShared += shared.size();
                float precision = inferredIndividualsResult.size() > 0 ? (float) shared.size() / inferredIndividualsResult.size() : 1;
                System.out.println("Precision = " + shared.size() + "/" + inferredIndividualsResult.size() + " = " + precision);
                sum_of_precisions += precision;
                float recall = inferredIndividuals.size() > 0 ? (float) shared.size() / inferredIndividuals.size() : 1;
                System.out.println("Recall = " + shared.size() + "/" + inferredIndividuals.size() + " = " + recall + '\n');
                sum_of_recalls += recall;
                ++counter;
            }
        }
        System.out.println("Classes with inferred instances: " + counter + '\n');
        if (counter > 0) {
            System.out.println("Macro precision: " + (sum_of_precisions / counter));
            System.out.println("Macro recall: " + (sum_of_recalls / counter) + '\n');
            System.out.println("Micro precision: " + ((float) allShared / allInferredResult));
            System.out.println("= " + allShared + "/" + allInferredResult);
            System.out.println("Micro recall: " + ((float) allShared / allInferred));
            System.out.println("= " + allShared + "/" + allInferred + '\n');
        }
        System.out.println("Classes without inferred instances: " + (baseSet.size() - counter) + '\n');

        finish = Instant.now();
        timeElapsed = Duration.between(start, finish).toMillis();
        System.out.println("Evaluation time: " + timeElapsed + " ms");
    }
}
