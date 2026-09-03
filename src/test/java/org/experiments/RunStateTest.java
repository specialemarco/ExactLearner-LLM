package org.experiments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the resume checkpoint format.
 *
 * This is the only state that crosses a walltime kill, and it is written once per
 * counterexample by a job that will be killed before it can report anything, so a
 * regression here is invisible until a 24 h run has already been thrown away.
 *
 * The backward-compatibility case is not hypothetical: job 4110003 left a state
 * file with 190 counterexamples and no metrics keys, and that file has to keep
 * resuming.
 *
 * Written against JUnit 5: JUnit 4 tests are silently skipped in this project (no
 * vintage engine on the JUnit Platform provider).
 */
public class RunStateTest {

    @Test
    public void roundTripsEveryField(@TempDir Path dir) throws Exception {
        File f = dir.resolve("h-run-state.properties").toFile();
        new LaunchLearner.RunState(190, 5201L, 7331L, 51536, 191, 12).write(f);

        LaunchLearner.RunState back = LaunchLearner.RunState.read(f);
        assertEquals(190, back.counterExamples);
        assertEquals(5201L, back.providedSamples);
        assertEquals(7331L, back.samplerDraws);
        assertEquals(51536, back.membCount);
        assertEquals(191, back.equivCount);
        assertEquals(12, back.largestCounterExample);
        assertTrue(back.metricsPresent, "a file written with metrics must report them present");
    }

    /**
     * A checkpoint from before the metrics fields existed still resumes: the
     * expensive half is the hypothesis and the sample position, and those are
     * present. Only the query totals restart, which the launcher warns about
     * rather than silently reporting a short count as a measurement.
     */
    @Test
    public void readsAPreMetricsCheckpoint(@TempDir Path dir) throws Exception {
        File f = dir.resolve("h-run-state.properties").toFile();
        Properties old = new Properties();
        old.setProperty("counterExamples", "190");
        old.setProperty("providedSamples", "5201");
        old.setProperty("samplerDraws", "7331");
        try (FileOutputStream out = new FileOutputStream(f)) {
            old.store(out, "as job 4110003 left it");
        }

        LaunchLearner.RunState back = LaunchLearner.RunState.read(f);
        assertEquals(190, back.counterExamples);
        assertEquals(5201L, back.providedSamples);
        assertEquals(7331L, back.samplerDraws);
        assertEquals(0, back.membCount);
        assertEquals(0, back.equivCount);
        assertFalse(back.metricsPresent, "a pre-metrics file must be flagged so the run warns");
    }

    /** A missing file is a fresh run, not a failure: restoreFromCheckpoint returns 0 on it. */
    @Test
    public void absentFileIsAFreshRun(@TempDir Path dir) {
        LaunchLearner.RunState back = LaunchLearner.RunState.read(dir.resolve("nope.properties").toFile());
        assertEquals(0, back.counterExamples);
        assertEquals(0L, back.providedSamples);
        assertFalse(back.metricsPresent);
    }

    /** An unreadable file resumes the hypothesis rather than aborting the job. */
    @Test
    public void unreadableFileDegradesToEmpty(@TempDir Path dir) throws Exception {
        File f = dir.resolve("h-run-state.properties").toFile();
        Properties bad = new Properties();
        bad.setProperty("counterExamples", "not-a-number");
        try (FileOutputStream out = new FileOutputStream(f)) {
            bad.store(out, "corrupt");
        }
        assertEquals(0, LaunchLearner.RunState.read(f).counterExamples);
    }
}
