package org.experiments;

import org.evaluation.Evaluation;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.utility.PacloDataset;

import java.io.File;

/**
 * Scores an empty TBox with the Baris evaluation, no LLM involved. The
 * evaluation injects the expert ABox, so an empty hypothesis still recovers
 * every asserted type: this is the floor a learned TBox is measured against.
 *
 *   EvaluateEmptyTBox <expertOntology.owl> [...]
 */
public class EvaluateEmptyTBox {

    record Baseline(File target, OWLOntology groundTruth, PacloDataset dataset, OWLReasoner expert) {

        static Baseline load(String path) throws Exception {
            File target = new File(PacloDataset.resolve(path));
            OWLOntology groundTruth = OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(target);
            PacloDataset dataset = PacloDataset.loadBeside(target, groundTruth);
            if (dataset == null) {
                throw new IllegalArgumentException("initialOntology.owl or baseSet not found beside " + target);
            }
            // As LaunchLLMLearner.expertReasoner(): C2/C3 need the property inferences.
            OWLReasoner expert = new ElkReasonerFactory().createReasoner(groundTruth);
            expert.precomputeInferences(
                    InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS,
                    InferenceType.OBJECT_PROPERTY_HIERARCHY, InferenceType.OBJECT_PROPERTY_ASSERTIONS);
            return new Baseline(target, groundTruth, dataset, expert);
        }

        /** Mutates hypothesis, as Evaluation.evaluate() adds the expert ABox to it. */
        void evaluate(String label, OWLOntology hypothesis) {
            System.out.println("=== BARIS EVALUATION (Macro/Micro Precision/Recall) " + label + ", "
                    + target + ", hypothesis has " + hypothesis.getLogicalAxiomCount() + " logical axioms ===");
            new Evaluation().evaluate(hypothesis, expert, dataset.baseSet(), dataset.initialReasoner());
        }
    }

    public static void main(String[] args) throws Exception {
        for (String path : args) {
            Baseline.load(path).evaluate("empty TBox", OWLManager.createOWLOntologyManager().createOntology());
        }
    }
}
