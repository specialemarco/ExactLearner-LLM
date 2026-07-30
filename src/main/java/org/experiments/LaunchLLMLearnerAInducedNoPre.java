package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

/**
 * LaunchLLMLearnerAInduced with Ana's precomputation() phase turned off.
 *
 * Precomputation tests every ordered pair of class names before the A-induced
 * loop starts, so it absorbs the atomic subsumptions the LLM finds easy. This
 * launcher exists to measure what the A-induced sampler contributes without
 * that head start -- one axis of the sampler x precomputation x stopping-
 * condition matrix.
 *
 * Everything else, including the batch pre-warm (which becomes a no-op here,
 * since nothing will read those cached answers), is inherited unchanged.
 */
public class LaunchLLMLearnerAInducedNoPre extends LaunchLLMLearnerAInduced {

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        LaunchLLMLearnerAInducedNoPre launcher = new LaunchLLMLearnerAInducedNoPre();
        launcher.setSkipPrecomputation(true);
        launcher.run(args);
    }
}
