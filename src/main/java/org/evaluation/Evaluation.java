package org.evaluation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exactlearner.renderer.AnnotationShorFormProvider;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
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

/**
 * NEW FILE — ported from Baris Sertkaya's paclo repository
 * (ontology.learning.oracle.Evaluation), adapted to package org.evaluation
 * so it can be used from this project.
 *
 * Computes the Macro/Micro Precision/Recall metrics used to evaluate a
 * learned ontology (resultOntology) against a ground-truth ontology
 * (queried via expertReasoner), over a fixed baseSet of concept
 * expressions. This is the exact same metric definition used to validate
 * the original paclo results against the Obiedkov & Sertkaya paper.
 *
 * PORTING NOTE — the only functional change from Baris's original class:
 * every logger.info(...) call has been replaced with System.out.println(...).
 * This project silences log4j globally (LogManager.getRootLogger().atLevel
 * (Level.OFF), set in each launcher's main()) to reduce console noise from
 * OWL-API/ELK, which had the side effect of making Evaluation's logger
 * output completely invisible. Without this change, none of the
 * precision/recall numbers below would ever be printed.
 *
 * IMPORTANT CAVEAT discovered during this project's investigation (July
 * 2026), not present in the original paclo documentation: for baseSet
 * concepts that are pure existential restrictions (e.g. ∃isCrazyAbout.⊤),
 * instance classification depends ONLY on ABox facts (who has that object
 * property asserted), not on any TBox axiom. Since resultReasoner and
 * expertReasoner are built over the SAME injected ABox (see line ~34
 * below), such concepts are classified identically by both reasoners
 * regardless of what the learned TBox actually contains — precision and
 * recall for these concepts are 1.0 essentially by construction. On the
 * OWL2Bench C2 BaseSet, roughly a third of the evaluated concepts (51 of
 * 157 in one measured run) fall into this category, meaning the aggregate
 * Macro/Micro metrics reported by this class are inflated by axioms the
 * learner did not actually need to learn. This affects any BaseSet
 * containing existential restrictions (C2, C3); it does not affect C1
 * (concept names only). Flagged here for future work — e.g. reporting
 * concept-name and existential-restriction concepts as two separate
 * metrics — but not fixed in this version to avoid altering Baris's
 * original evaluation logic.
 */
public class Evaluation {
    private Logger logger = LogManager.getLogger("OracleEvaluation");

    /**
     * @param resultOntology         the learned hypothesis ontology (TBox only)
     * @param expertReasoner         reasoner built on the ground-truth ontology (with its own ABox)
     * @param baseSet                the fixed set of concept expressions to evaluate over
     * @param initialOntologyReasoner reasoner on the initial ontology (with ABox injected); used
     *                                only to print asserted instance counts, not in the P/R formulas
     */
    public void evaluate(OWLOntology resultOntology, OWLReasoner expertReasoner, Set<OWLClassExpression> baseSet, OWLReasoner initialOntologyReasoner) {
        Instant start = Instant.now();

        OWLOntologyManager manager = resultOntology.getOWLOntologyManager();
        OWLClass owlNothing = manager.getOWLDataFactory().getOWLNothing();

        // Any "X SubClassOf owl:Nothing" axioms in the learned ontology are
        // disjointness/inconsistency markers rather than genuine
        // subsumptions; they are logged for visibility and then stripped
        // out before building the result reasoner, so they cannot distort
        // the instance-classification counts below.
        Set<OWLSubClassOfAxiom> disjointnessAxioms = resultOntology.subClassAxiomsForSuperClass(owlNothing)
                    .collect(Collectors.toSet());
        System.out.println(disjointnessAxioms.size() + " disjointness axioms");
        for (OWLSubClassOfAxiom a : disjointnessAxioms) {
            System.out.println(a);
        }
        manager.removeAxioms(resultOntology, disjointnessAxioms);

        // Inject the expert (ground-truth) ontology's ABox into the result
        // ontology. This is required because resultOntology, as produced by
        // the learner, contains only TBox axioms (no individuals) — without
        // this injection, resultReasoner would have no instances to
        // classify at all. See the class-level CAVEAT above for the
        // evaluation consequence of this design choice on existential-
        // restriction concepts.
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

        // Short-form renderer for readable output: reuses the same
        // AnnotationShorFormProvider already used by LLMEngine.createRenderer()
        // (falls back to the IRI fragment, e.g. "Woman", when no rdfs:label
        // exists). Without it every axiom prints as a full IRI.
        ManchesterOWLSyntaxOWLObjectRendererImpl shortRenderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
        shortRenderer.setShortFormProvider(new AnnotationShorFormProvider(resultOntology));

        start = Instant.now();

        // Diagnostic pass: for every learned SubClassOf axiom, print a
        // "confidence" score based on how well the expert's own instance
        // sets for the subclass/superclass overlap. This is informational
        // logging only — it does not feed into the Macro/Micro metrics
        // computed below.
        for (OWLSubClassOfAxiom axiom : resultOntology.getAxioms(AxiomType.SUBCLASS_OF)) {
            System.out.println("***Subclass Axiom***");
            OWLClassExpression subClass = axiom.getSubClass();
            System.out.println("Subclass: " + shortRenderer.render(subClass));
            Set<OWLNamedIndividual> subClassIndividuals = expertReasoner.getInstances(subClass, false).getFlattened();
            OWLClassExpression superClass = axiom.getSuperClass();
            System.out.println("Superclass: " + shortRenderer.render(superClass));
            Set<OWLNamedIndividual> superClassIndividuals = expertReasoner.getInstances(superClass, false).getFlattened();
            Set<OWLNamedIndividual> intersection = new HashSet<>(subClassIndividuals);
            intersection.retainAll(superClassIndividuals);
            float confidence = subClassIndividuals.isEmpty() ? 1
                    : (float) intersection.size() / subClassIndividuals.size();
            System.out.println("Confidence = " + intersection.size() + "/" + subClassIndividuals.size() + " = " + confidence);
        }
        System.out.println("*********\n");

        // Main evaluation loop: for every concept in the baseSet, compare
        // the individuals the EXPERT reasoner infers under it against the
        // individuals the RESULT (learned) reasoner infers under it.
        //   precision (per concept) = |shared| / |inferred by result|
        //   recall    (per concept) = |shared| / |inferred by expert|
        // Concepts with no instances inferred by either reasoner are
        // skipped entirely (not counted in Macro/Micro averages) — this is
        // the "Classes without inferred instances" bucket reported below.
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
                System.out.println("ce:" + shortRenderer.render(ce));
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
            // Macro: simple average of per-concept precision/recall
            // (every concept counts equally regardless of instance count).
            System.out.println("Macro precision: " + (sum_of_precisions / counter));
            System.out.println("Macro recall: " + (sum_of_recalls / counter) + '\n');
            // Micro: aggregated over all individual-level decisions
            // (concepts with more instances have proportionally more
            // influence on the score).
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
