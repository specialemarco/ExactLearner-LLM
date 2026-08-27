package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.pac.Pac;
import org.sampler.ABoxInducedSubsumptionSampler;
import org.utility.PacloDataset;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.exactlearner.engine.LLMEngine;
import org.experiments.logger.Cache;
import org.experiments.workload.BatchPrewarmer;

/**
 * The A-induced arm: the candidate axioms in the equivalence-query loop come
 * from ABoxInducedSubsumptionSampler, which draws them grounded in the ABox of
 * the OWL2Bench ontology, rather than from the uniform PAC sampler. That single
 * substitution is what this class is for; everything downstream of it -- the
 * entailment checks, getCounterExampleSubClassOf(), Learner.decompose() -- is
 * inherited untouched, and so are run(), the loop itself and the resume path.
 *
 * What else lives here: the counterexample bookkeeping the evaluation reports,
 * and the batched candidate prefetch. The PACLO dataset the sampler draws from is
 * the parent's, so the evaluation reuses it rather than rebuilding it, and the
 * BaseSet-aware output filenames are the parent's too -- that collision belongs
 * to the dataset, not to this arm. Precomputation and evaluation are flags on
 * LaunchLLMLearner, not subclasses -- see the axes comment there.
 */
public class LaunchLLMLearnerAInduced extends LaunchLLMLearner {

    // The ABox-induced sampler. Left null until the first call to
    // getCounterExample(), then lazily initialized by initAboxSampler()
    // (because it depends on files — initialOntology.owl, baseSet — that
    // live alongside the target ontology, only known once setup() has run).
    private ABoxInducedSubsumptionSampler aboxSampler = null;

    // Counterexamples accepted during this (ontology, model) run; reset in
    // beforeModelRun() and reported at evaluation time.
    private int counterExampleCount = 0;

    // PAC sample index of the previous counterexample, so each one can report
    // what it cost in candidates rather than only that it happened.
    private long samplesAtLastCounterExample = 0;

    /**
     * Position of the sampler's random stream, recorded in every checkpoint so
     * a resumed run can replay to it. See LaunchLearner.RunState.
     */
    @Override
    protected long samplerDraws() {
        return aboxSampler == null ? 0L : aboxSampler.getDraws();
    }

    // Precomputation absorbs the easy atomic subsumptions before the loop starts,
    // so turning it off (args[3], skipPrecomputation on LaunchLLMLearner) is what
    // isolates what the A-induced sampler finds by itself.

    // Batched candidate evaluation. Turned off for the rest of the model's run
    // if anything about the batch path is unavailable, so a broken batch
    // endpoint degrades to the one-at-a-time loop rather than failing a run
    // that would otherwise have completed.
    private boolean batchedLoopDisabled = false;

    /**
     * Seed for the A-induced sampler, from the environment. Fixed by default so
     * a rerun repeats the previous run's draws exactly; override it to get an
     * independent repeat of the same experiment.
     *
     * Note this is NOT the `seed` local in runLearner, which belongs to Pac and
     * has never governed A-induced sampling.
     */
    public static final String SAMPLER_SEED_ENV = "EXACTLEARNER_SAMPLER_SEED";

    private long samplerSeed() {
        String raw = System.getenv(SAMPLER_SEED_ENV);
        if (raw == null || raw.isBlank()) {
            return ABoxInducedSubsumptionSampler.DEFAULT_SEED;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("Ignoring " + SAMPLER_SEED_ENV + "=" + raw + " (not a number), using "
                    + ABoxInducedSubsumptionSampler.DEFAULT_SEED);
            return ABoxInducedSubsumptionSampler.DEFAULT_SEED;
        }
    }

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchLLMLearnerAInduced().run(args);
    }

    // ---- Arm configuration ------------------------------------------------
    // The loop, the resume path and the run() scaffolding all live in
    // LaunchLLMLearner now. This class supplies only what makes the arm
    // A-induced: the sampler (getCounterExample below), the sampler's stream
    // position on resume, and these hooks.

    /** Evaluation defaults on for this arm; args[4]="false" still turns it off. */
    {
        evaluateAfterRun = true;
    }

    @Override
    protected String experimentLabel() {
        return " (A-induced)";
    }

    /**
     * Drops everything held over from the previous iteration of run()'s loops.
     * The batch flag belongs here too: a batch path that was unavailable for one
     * model says nothing about the next.
     */
    @Override
    protected void beforeModelRun() {
        aboxSampler = null;
        counterExampleCount = 0;
        samplesAtLastCounterExample = 0;
        batchedLoopDisabled = false;
    }

    /** This arm has never printed average stats; pinned by LauncherFlagMatrixTest. */
    @Override
    protected boolean shouldPrintAverageStats() {
        return false;
    }

    /** Replays the sampler's draw sequence to the checkpointed position. */
    @Override
    protected void restoreSamplerPosition(long samplerDraws) throws Exception {
        if (aboxSampler == null) {
            initAboxSampler();
        }
        if (aboxSampler != null) {
            aboxSampler.fastForwardTo(samplerDraws);
        }
    }

    /** Additionally restores this arm's own per-counterexample bookkeeping. */
    @Override
    protected int restoreFromCheckpoint(Pac pac) throws Exception {
        int resumed = super.restoreFromCheckpoint(pac);
        if (resumed > 0) {
            samplesAtLastCounterExample = (long) pac.getNumberOfProvidedSamples();
            counterExampleCount = resumed;
        }
        return resumed;
    }

    /**
     * Where the sampler substitution happens. Against the inherited uniform loop:
     *
     *   BEFORE (uniform PAC): OWLSubClassOfAxiom ax = pac.getRandomStatement();
     *   AFTER  (A-induced):   OWLSubClassOfAxiom ax = aboxSampler.sample();
     *
     * Everything else -- spending the budget, entH/entT, calling
     * getCounterExampleSubClassOf() on a genuine counterexample -- is unchanged.
     *
     * The sampler is built on first call. When initialOntology.owl and baseSet
     * are not beside the target ontology this is not a PACLO dataset, and the
     * loop falls back to uniform PAC sampling, so the class stays usable on the
     * small ontologies too.
     */
    @Override
    protected OWLSubClassOfAxiom getCounterExample(Pac pac) throws Exception {
        if (aboxSampler == null) {
            initAboxSampler();
            if (aboxSampler == null) {
                System.out.println("A-induced setup not available for this ontology \u2014 falling back to uniform PAC");
                return super.getCounterExample(pac);
            }
        }


        // ------------------------------------------------------------------
        // The budget this loop spends is Pac's, and which one it is depends on
        // Pac.BudgetMode: GLOBAL by default (one pot for the whole run),
        // PER_ROUND under EXACTLEARNER_BUDGET_MODE=per-round (a fresh full
        // budget per equivalence query, which is what debug-verify-v2 does).
        // Nothing here needs to know which -- hasBudgetLeft() answers for both.
        //
        // STILL NOT ADOPTED from debug-verify-v2: the other half of that
        // branch, which on exhausting a round rebuilds an ELK reasoner over
        // hypothesisOntology, calls update_sampler(reasoner, false) to refresh
        // the premise-side types against the GROWN hypothesis, and retries the
        // round once. The refresh is worth having on its own -- the sampler
        // here is built once against the initial ontology and never updated, so
        // as the hypothesis grows it keeps proposing candidates the hypothesis
        // already entails, and each of those still costs a model query (entT
        // below is computed whether or not entH is true).
        //
        // What blocks it is resume, not the budget: fastForwardTo() replays
        // sample() calls against the live weight table, and update_sampler
        // rebuilds that table, so a resumed run would replay the early draws
        // against post-refresh weights and reconstruct a different stream.
        // Refreshing at each accepted counterexample -- the point where the
        // hypothesis actually changes -- is the cheaper version of this and
        // works under either budget mode; it needs the refresh points recorded
        // in the checkpoint, or an explicit fallback to a fresh stream on
        // resume. See MEETING-2026-08-18.md section 8.
        // ------------------------------------------------------------------
        int batchSize = batchedLoopSize();

        while (pac.hasBudgetLeft()) {
            // Draw a block of candidates and fetch their answers in ONE call, so
            // the GPU sees batchSize prompts instead of one. Purely a transport
            // change: the examination loop below is the original sequential one,
            // and every answer it needs was just written into the cache the
            // engine reads. C2's measured hit rate is ~1 in 32 -- about one
            // block -- so a block is usually, not almost always, fully spent.
            // (The older 1-in-264 figure divided by total requests, which
            // counted decompose()'s membership queries; see MEETING section 8.)
            List<OWLSubClassOfAxiom> block = drawBlock(batchSize, pac);
            if (block.isEmpty()) return null;
            prefetchAnswers(block);

            for (OWLSubClassOfAxiom selectedAxiom : block) {
                if (!pac.hasBudgetLeft()) {
                    return null;
                }
                // getRandomStatement() advances the budget internally; this loop
                // bypasses it for aboxSampler.sample(), so it has to spend the
                // budget itself or the loop would never terminate.
                //
                // Only candidates actually EXAMINED count. Whatever is left in
                // the block after a counterexample is discarded unexamined, so
                // batched and sequential runs spend the budget identically and
                // stay comparable; the discarded answers stay cached anyway.
                pac.incrementProvidedSamples();
                boolean entH = elQueryEngineForH.entailed(selectedAxiom);
                boolean entT = llmQueryEngineForT.entailed(selectedAxiom);
                if (!entH && entT) {
                    counterExampleCount++;
                    long samples = (long) pac.getNumberOfProvidedSamples();
                    // The speculation figures are cumulative and go on this line
                    // because a timed-out job never reaches an end-of-run
                    // report -- four runs in a row have died at walltime.
                    System.out.println("Counterexample " + counterExampleCount
                            + " at sample " + samples
                            + " (+" + (samples - samplesAtLastCounterExample) + " since the last one), "
                            + learner.speculationSummary()
                            + " wall=" + wallClock());
                    samplesAtLastCounterExample = samples;
                    return getCounterExampleSubClassOf(selectedAxiom);
                }
            }
        }
        return null;
    }

    /**
     * Batch size for the loop above, from EXACTLEARNER_BATCH_SIZE. 1 means the
     * loop behaves exactly as before, one candidate and one query at a time.
     */
    private int batchedLoopSize() {
        if (batchedLoopDisabled) {
            return 1;
        }
        int configured = BatchPrewarmer.batchSizeFromEnv();
        return configured > 1 ? configured : 1;
    }

    /** Draws up to batchSize candidates, never more than the remaining PAC budget. */
    private List<OWLSubClassOfAxiom> drawBlock(int batchSize, Pac pac) {
        // Whichever budget is in force: the run-long pot under GLOBAL, this
        // equivalence query's own under PER_ROUND. Never overdraw it, or a
        // block would examine candidates the loop is no longer allowed to.
        long remaining = pac.getRemainingSamples();
        int want = (int) Math.min(Math.max(batchSize, 1), remaining);
        List<OWLSubClassOfAxiom> block = new ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            OWLSubClassOfAxiom axiom = aboxSampler.sample();
            //if (axiom == null) break;   // sampler exhausted
            block.add(axiom);
        }
        return block;
    }

    /**
     * Asks the batch endpoint for every answer in the block that is not cached
     * yet, and stores the results. Deliberately best-effort: on any failure the
     * loop simply queries one at a time, which is correct, only slower.
     *
     * queryFor() is valid here because A-induced conclusions are single base-set
     * concepts, never intersections. If the engine did ask something different,
     * that query would just miss the cache and be issued normally.
     */
    private void prefetchAnswers(List<OWLSubClassOfAxiom> block) {
        if (batchedLoopDisabled || block.size() < 2) return;
        if (!(llmQueryEngineForT instanceof LLMEngine engine)) {
            batchedLoopDisabled = true;
            return;
        }
        Cache cache = currentCache();
        if (cache == null) {
            batchedLoopDisabled = true;
            return;
        }
        try {
            // LinkedHashSet: a block can draw the same axiom twice, and asking
            // for it twice in one batch would waste a slot.
            LinkedHashSet<String> pending = new LinkedHashSet<>();
            for (OWLSubClassOfAxiom axiom : block) {
                String query = engine.queryFor(axiom);
                if (cache.resultString(query) == null) {
                    pending.add(query);
                }
            }
            if (!pending.isEmpty()) {
                BatchPrewarmer.fetchAndCache(cache, system, new ArrayList<>(pending), pending.size());
            }
        } catch (Throwable t) {
            System.out.println("A-induced batch prefetch failed, continuing sequentially: " + t);
            batchedLoopDisabled = true;
        }
    }

    /**
     * Builds the sampler for the current target ontology, once per run, on the
     * first getCounterExample(). Leaves it null when loadBeside() finds no
     * PACLO dataset beside the target, which is the fallback signal.
     *
     * Throws, rather than falling back, when the dataset is there but no
     * individual carries a base-set type -- see checkSamplerIsUsable().
     */
    private void initAboxSampler() throws Exception {
        PacloDataset dataset = pacloDataset();
        if (dataset == null) {
            return;
        }
        long seed = samplerSeed();
        aboxSampler = new ABoxInducedSubsumptionSampler(
                dataset.baseSet(), dataset.initialReasoner(), OWLManager.getOWLDataFactory(), seed);
        checkSamplerIsUsable(dataset);
        // One line that answers both "which arm ran" and "which base set", so
        // neither has to be inferred from where other lines sit in the log. The
        // "PACLO dataset" line above is printed by whichever arm loads the
        // dataset; this one prints only for A-induced, and only once the sampler
        // is built and has passed the check above.
        System.out.println("A-induced sampler ready: baseSet " + dataset.baseSet().size()
                + ", " + aboxSampler.typedIndividualCount() + " typed individuals, seed " + seed
                + " (set " + SAMPLER_SEED_ENV + " to vary it across repeats)");
    }

    /**
     * Refuses to run A-induced sampling that cannot be A-induced.
     *
     * With no individual carrying a base-set type, samplePremise() has nothing to
     * draw from and returns the empty premise, so every single candidate becomes
     * `owl:Thing SubClassOf X`. That failure is silent and it does not look like
     * a failure: a few hundred distinct queries, everything after them a cache
     * hit, the whole budget spent in minutes, no counterexamples, and a clean
     * "No counterexample found, closing..." at the end. It reads exactly like a
     * hypothesis that converged.
     *
     * Deliberately NOT the uniform-PAC fallback that a missing dataset takes.
     * The dataset being present means A-induced sampling was what was asked for,
     * and quietly running a different experiment for 24 hours is the specific
     * outcome HPC-RUNBOOK.md warns about. Better to lose the allocation in the
     * first minute with a message naming the cause.
     */
    private void checkSamplerIsUsable(PacloDataset dataset) {
        if (aboxSampler.hasIndividuals()) {
            return;
        }
        aboxSampler = null;
        throw new IllegalStateException(
                "A-induced sampling is impossible for " + dataset.directory() + ": no individual has"
                + " a base-set type, so every candidate would be owl:Thing SubClassOf X."
                + " The \"PACLO dataset\" line above reports how many individuals were typed --"
                + " 0 there means the ABox injection or the baseSet is wrong for this dataset,"
                + " not that the run is finished. Fix the dataset rather than rerunning.");
    }

    /** Reports what the loop cost before the inherited evaluation runs. */
    @Override
    protected void evaluateWithBaris() throws Exception {
        System.out.println("Counterexamples found during this run: " + counterExampleCount);
        super.evaluateWithBaris();
    }
}
