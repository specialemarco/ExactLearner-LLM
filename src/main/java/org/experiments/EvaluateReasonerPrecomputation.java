package org.experiments;

import org.exactlearner.engine.ELEngine;
import org.exactlearner.learner.Learner;
import org.exactlearner.utils.Metrics;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Learner.precomputation() with ELK on the target as its oracle instead of the
 * LLM, then the Baris evaluation, no LLM involved. A perfect oracle for the
 * class-name pairs, so this is the ceiling an LLM precomputation is compared
 * against. The pairs asked are the LLM run's; only their order differs, which
 * moves the "entailed by H / by T" split but not the axioms added.
 *
 *   EvaluateReasonerPrecomputation <expertOntology.owl> [...]
 */
public class EvaluateReasonerPrecomputation {

    public static void main(String[] args) throws Exception {
        for (String path : args) {
            EvaluateEmptyTBox.Baseline baseline = EvaluateEmptyTBox.Baseline.load(path);
            OWLOntology hypothesis = OWLManager.createOWLOntologyManager().createOntology();
            Learner learner = new Learner(new ELEngine(baseline.groundTruth()), new ELEngine(hypothesis),
                    new Metrics(new ManchesterOWLSyntaxOWLObjectRendererImpl()));

            long start = System.currentTimeMillis();
            learner.precomputation();
            // Same label as the LLM runs log, so the notebook reads both alike.
            System.out.println("Precomputation time (ms): " + (System.currentTimeMillis() - start));
            baseline.evaluate("reasoner precomputation", hypothesis);
        }
    }
}
