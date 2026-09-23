package org.experiments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the launcher flag matrix created on 2026-08-27, when four launcher classes
 * were merged into two plus flags.
 *
 * The three axes are independent: precomputation runs BEFORE the loop, the sampler
 * is used INSIDE it, and evaluation happens AFTER it. These tests assert the
 * defaults each old class used to hard-code, so the merge stays behaviour-preserving:
 *
 *   LaunchLLMLearnerAInducedNoPre   == AInduced, EXACTLEARNER_PRECOMP unset
 *   LaunchLLMLearnerWithBarisEval   == LaunchLLMLearner + args[1]="true"
 *
 * Written against JUnit 5: JUnit 4 tests are silently skipped in this project (no
 * vintage engine on the JUnit Platform provider).
 */
public class LauncherFlagMatrixTest {

    private static String[] args(String... extra) {
        String[] a = new String[1 + extra.length];
        a[0] = "unused-config.yml";
        System.arraycopy(extra, 0, a, 1, extra.length);
        return a;
    }

    @Test
    public void uniformArmDefaultsAreUnchanged() {
        LaunchLLMLearner launcher = new LaunchLLMLearner();
        assertFalse(launcher.evaluateAfterRun, "uniform arm did not evaluate by default");
        assertTrue(launcher.shouldPrintAverageStats(), "uniform arm printed average stats");
        assertEquals("", launcher.experimentLabel());
    }

    @Test
    public void aInducedArmDefaultsAreUnchanged() {
        LaunchLLMLearnerAInduced launcher = new LaunchLLMLearnerAInduced();
        assertTrue(launcher.evaluateAfterRun, "A-induced always ran evaluateWithBaris()");
        assertFalse(launcher.shouldPrintAverageStats(),
                "A-induced has never printed average stats: printAverageStats() is private "
                        + "to LaunchLLMLearner, so the old copied run() could not call it");
        assertEquals(" (A-induced)", launcher.experimentLabel());
    }

    /** Precomputation runs only when asked for; run_experiment.sh asks by default. */
    @Test
    public void precomputationIsOffWhenTheVariableIsUnset() {
        assumeTrue(System.getenv(LaunchLLMLearner.PRECOMP_ENV) == null,
                LaunchLLMLearner.PRECOMP_ENV + " is exported in this shell");
        assertFalse(new LaunchLLMLearner().isPrecomputationEnabled());
        assertFalse(new LaunchLLMLearnerAInduced().isPrecomputationEnabled());
    }

    /** What LaunchLLMLearnerWithBarisEval used to do, now the 2nd CLI arg. */
    @Test
    public void secondArgReproducesTheBarisEvalSubclass() {
        LaunchLLMLearner launcher = new LaunchLLMLearner();
        launcher.parseExperimentArgs(args("true"));
        assertTrue(launcher.evaluateAfterRun, "Baris evaluation requested");
    }

    /** The config-only invocation keeps each arm's evaluation default. */
    @Test
    public void configOnlyInvocationKeepsProductionDefaults() {
        LaunchLLMLearnerAInduced launcher = new LaunchLLMLearnerAInduced();
        launcher.parseExperimentArgs(args());
        assertTrue(launcher.evaluateAfterRun, "evaluation stays on");
    }

    // ---- the sampler axis --------------------------------------------------
    //
    // Pinned by override rather than by setting EXACTLEARNER_SAMPLER, so these
    // hold whatever the developer's shell exports. The tests above read the
    // environment deliberately: they are the ones asserting the defaults.

    private static LaunchLLMLearner uniformLauncherClaiming(LaunchLLMLearner.SamplerArm arm) {
        return new LaunchLLMLearner() {
            @Override
            protected SamplerArm samplerArm() {
                return arm;
            }
        };
    }

    @Test
    public void eachLauncherClassDefaultsToItsOwnSampler() {
        assertEquals(LaunchLLMLearner.SamplerArm.PAC,
                new LaunchLLMLearner().defaultSamplerArm());
        assertEquals(LaunchLLMLearner.SamplerArm.WEIGHTED,
                new LaunchLLMLearnerAInduced().defaultSamplerArm(),
                "an unset EXACTLEARNER_SAMPLER must keep running what every run so far ran");
    }

    /** Only reachable from a hand-written java invocation, and it must fail early. */
    @Test
    public void anAboxArmOnTheUniformLauncherIsRefused() {
        for (LaunchLLMLearner.SamplerArm arm : new LaunchLLMLearner.SamplerArm[] {
                LaunchLLMLearner.SamplerArm.WEIGHTED, LaunchLLMLearner.SamplerArm.UNWEIGHTED }) {
            LaunchLLMLearner launcher = uniformLauncherClaiming(arm);
            assertThrows(IllegalStateException.class,
                    () -> launcher.parseExperimentArgs(args()),
                    arm + " cannot run on LaunchLLMLearner");
        }
    }

    /** The reverse direction is fine: the A-induced launcher can run uniform PAC. */
    @Test
    public void theAInducedLauncherAcceptsThePacArm() {
        LaunchLLMLearnerAInduced launcher = new LaunchLLMLearnerAInduced() {
            @Override
            protected SamplerArm samplerArm() {
                return SamplerArm.PAC;
            }
        };
        launcher.parseExperimentArgs(args());
        assertEquals(" (uniform PAC, via the A-induced launcher)", launcher.experimentLabel());
    }

    /** The weighted arm keeps the string every log so far carries. */
    @Test
    public void theLabelNamesTheArm() {
        assertEquals(" (A-induced)", new LaunchLLMLearnerAInduced() {
            @Override
            protected SamplerArm samplerArm() {
                return SamplerArm.WEIGHTED;
            }
        }.experimentLabel());

        assertEquals(" (A-induced, unweighted)", new LaunchLLMLearnerAInduced() {
            @Override
            protected SamplerArm samplerArm() {
                return SamplerArm.UNWEIGHTED;
            }
        }.experimentLabel());
    }

    /** The axes must not interfere: precomputation off must not disable evaluation. */
    @Test
    public void axesAreIndependent() {
        LaunchLLMLearnerAInduced launcher = new LaunchLLMLearnerAInduced() {
            @Override
            protected boolean isPrecomputationEnabled() {
                return false;
            }
        };
        launcher.parseExperimentArgs(args());
        assertTrue(launcher.evaluateAfterRun);

        LaunchLLMLearnerAInduced other = new LaunchLLMLearnerAInduced();
        other.parseExperimentArgs(args("false"));
        assertFalse(other.evaluateAfterRun);
    }
}
