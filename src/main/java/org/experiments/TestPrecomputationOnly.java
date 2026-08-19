package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

/**
 * Runs ONLY the precomputation() phase and saves the resulting hypothesis,
 * without ever entering the equivalence-query loop.
 *
 * This is the complement of {@link LaunchLLMLearnerAInducedNoPre}: that one runs
 * the A-induced loop with precomputation off, this one runs precomputation with
 * the loop off. Together they decompose the learned ontology into the part the
 * exhaustive O(n^2) class-pair pass is responsible for and the part the sampler
 * actually contributes -- a split that is otherwise unrecoverable from a normal
 * run, where the two are interleaved into one hypothesis.
 *
 * Salvaged from the stopping-condition-fix branch, which was otherwise dropped.
 */
public class TestPrecomputationOnly extends LaunchLLMLearnerAInduced {

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        TestPrecomputationOnly launcher = new TestPrecomputationOnly();
        launcher.run(args);
    }

    @Override
    protected void runLearner(int hypothesisSize) throws Throwable {
        System.out.println("=== ISOLATED TEST: precomputation ONLY, no equivalence query loop ===");
        learner.precomputation();
        System.out.println("=== Precomputation finished. Skipping the sampling loop entirely. ===");
        // Deliberately no while(true) equivalence-query loop: the hypothesis at
        // this point contains ONLY what precomputation added.
    }
}
