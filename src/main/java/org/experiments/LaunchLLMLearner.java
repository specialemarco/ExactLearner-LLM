package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.configurations.Configuration;
import org.exactlearner.engine.AxiomSimplifier;
import org.exactlearner.engine.ELEngine;
import org.exactlearner.engine.LLMEngine;
import org.exactlearner.engine.NLPLLMEngine;
import org.exactlearner.learner.ConceptRelation;
import org.exactlearner.learner.Learner;
import org.exactlearner.oracle.Oracle;
import org.exactlearner.parser.OWLParserImpl;
import org.exactlearner.utils.Metrics;
import org.experiments.logger.Cache;
import org.experiments.logger.CacheManager;
import org.experiments.logger.SmartLogger;
import org.experiments.workload.BatchPrewarmer;
import org.experiments.workload.WorkLoadCounter;
import org.experiments.workload.WorkloadManager;
import org.experiments.workload.WorkloadManagerImpl;
import org.evaluation.Evaluation;
import org.pac.Pac;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.utility.PacloDataset;
import org.utility.OntologyManipulator;
import org.utility.YAMLConfigLoader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.utility.StatsPrinter.*;

public class LaunchLLMLearner extends LaunchLearner {

    protected List<String> ontologies;
    protected List<String> models;
    protected String system;
    protected WorkLoadCounter counter;

    protected String queryFormat;
    protected Integer maxTokens;
    protected List<Integer> hypothesisSizes;
    // The model currently being run, set by setup(). Filename-safe: ':' is
    // replaced, so this is NOT the string the cache is keyed on.
    protected String currentModel;

    // The model string the engine resolves its cache with -- the raw config
    // name, kept because currentModel has had ':' replaced and "llama2:13b" and
    // "llama2-13b" are two different rows. Set alongside the engine itself, so
    // the two can never disagree.
    private String cacheModel;
    private Cache currentCache;

    // The PACLO dataset beside the target ontology. loadBeside() loads an
    // ontology, builds an ELK reasoner and classifies the whole ABox, so the
    // sampler and the evaluation share one rather than paying for it twice.
    // Cleared per (ontology, model) in setup().
    private PacloDataset pacloDataset;
    private boolean pacloDatasetLoaded;
    // Protected rather than private so LaunchLLMLearnerAInduced can accumulate
    // into them from its own runLearner override (see skipPrecomputation).
    protected double totalCE = 0;
    protected double totalMembershipQ = 0;
    protected double totalEquivalenceQ = 0;


    protected double epsilon = 0.2;
    protected double delta = 0.1;

    protected final CacheManager cacheManager = new CacheManager(false);;

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchLLMLearner().run(args);
    }

    protected void loadConfiguration(String fileName) {
        Configuration config = new YAMLConfigLoader().getConfig(fileName, Configuration.class);
        //choose configuration from file here:
        models = config.getModels();
        system = config.getSystem();
        queryFormat = config.getQueryFormat();
        ontologies = config.getOntologies();
        maxTokens = config.getMaxTokens();
        hypothesisSizes = ontologies.stream().map(OntologyManipulator::computeOntologySize).collect(Collectors.toList());
    }

    // ---- The three experiment axes ---------------------------------------
    //
    // One launcher covers what used to be four classes, because the three
    // things that varied are independent of each other and of the loop:
    //
    //   precomputation  BEFORE the loop  -- skipPrecomputation, args[3]
    //   sampler         INSIDE the loop  -- getCounterExample(), overridden
    //   evaluation      AFTER the loop   -- evaluateAfterRun, args[4]
    //
    // The loop itself is identical in every arm, so it exists once, in
    // runLearner() below. LaunchLLMLearnerAInducedNoPre (which only set
    // skipPrecomputation) and LaunchLLMLearnerWithBarisEval (which only set
    // evaluateAfterRun) were removed on 2026-08-27 in favour of these flags.

    /**
     * Optional 4th CLI arg. Disables learner.precomputation() in runLearner(),
     * for experiments isolating the sampling loop's contribution from
     * precomputation's.
     */
    protected boolean skipPrecomputation = false;

    /**
     * Optional 5th CLI arg. Runs Baris's Macro/Micro Precision/Recall evaluation
     * after each model finishes. Off here so the plain PAC arm is unchanged;
     * LaunchLLMLearnerAInduced defaults it on, as it always evaluated.
     */
    protected boolean evaluateAfterRun = false;

    protected void parseExperimentArgs(String[] args) {
        if (args.length > 1) {
            epsilon = Double.parseDouble(args[1]);
        }
        if (args.length > 2) {
            delta = Double.parseDouble(args[2]);
        }
        if (args.length > 3) {
            skipPrecomputation = Boolean.parseBoolean(args[3]);
        }
        if (args.length > 4) {
            evaluateAfterRun = Boolean.parseBoolean(args[4]);
        }
        System.out.println("skipPrecomputation = " + skipPrecomputation);
        System.out.println("evaluateAfterRun = " + evaluateAfterRun);
    }

    /**
     * The cache this run's engine reads and writes. Resolved once per model:
     * every batch path has to write into the same row the engine reads from, or
     * the answers it paid for are never found.
     */
    protected Cache currentCache() {
        if (currentCache == null && cacheModel != null) {
            currentCache = cacheManager.getCache(cacheModel, system);
        }
        return currentCache;
    }

    /**
     * The PACLO dataset beside the target ontology, loaded at most once per run,
     * or null when there is none -- which is what tells the A-induced sampler to
     * fall back to uniform PAC and the evaluation to skip.
     */
    protected PacloDataset pacloDataset() throws Exception {
        if (!pacloDatasetLoaded) {
            pacloDataset = PacloDataset.loadBeside(targetFile, groundTruthOntology);
            pacloDatasetLoaded = true;
        }
        return pacloDataset;
    }

    /**
     * Seed for the uniform PAC sampler. Fixed at 0 by default, as it always has
     * been, so nothing already measured changes; set it to repeat the uniform arm
     * independently, which is what comparing the two arms on one dataset needs --
     * a single uniform run is one draw from a random process, not a baseline.
     *
     * Separate from EXACTLEARNER_SAMPLER_SEED, which seeds the A-induced sampler.
     * The two samplers have independent streams and neither seed governs the other.
     */
    public static final String PAC_SEED_ENV = "EXACTLEARNER_PAC_SEED";

    protected int pacSeed() {
        String raw = System.getenv(PAC_SEED_ENV);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("Ignoring " + PAC_SEED_ENV + "=" + raw + " (not a number), using 0");
            return 0;
        }
    }

    /** Names the arm in the run banner, e.g. " (A-induced)". */
    protected String experimentLabel() {
        return "";
    }

    /** Per-(ontology, model) reset, after setup and before the learner runs. */
    protected void beforeModelRun() {
    }

    /** Post-run hook; by default the optional Baris evaluation. */
    protected void afterLearningExperiment() {
        if (!evaluateAfterRun) {
            return;
        }
        try {
            evaluateWithBaris();
        } catch (Exception ex) {
            System.out.println("Error during evaluateWithBaris: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * Whether run() ends with printAverageStats().
     *
     * False in the A-induced arm, which has never printed it: printAverageStats()
     * is private here, so the subclass that used to carry its own copy of run()
     * structurally could not call it. Unifying run() would otherwise switch that
     * output on silently, so the existing behaviour is preserved explicitly.
     */
    protected boolean shouldPrintAverageStats() {
        return true;
    }

    public void run(String[] args) {
        String configurationFile = args[0];
        parseExperimentArgs(args);
        SmartLogger.checkCachedFiles();
        loadConfiguration(configurationFile);
        try {
            for (String ontology : ontologies) {
                System.out.println("\nRunning experiment" + experimentLabel() + " for " + ontology);
                for (String model : models) {
                    System.out.println("\nRunning experiment for " + model + "\n");
                    setup(ontology, model.replace(":", "-"));
                    elQueryEngineForH = new ELEngine(hypothesisOntology);
                    String ontologyShortName = ontology.substring(ontology.lastIndexOf("/") + 1, ontology.lastIndexOf("."));
                    createWorkCounter(ontologyShortName, model);
                    conceptRelation = new ConceptRelation<>();
                    setLLMEngine(model, ontologyShortName);
                    learner = new Learner(llmQueryEngineForT, elQueryEngineForH, myMetrics, conceptRelation);
                    installDecomposePrefetcher(model);
                    oracle = new Oracle(llmQueryEngineForT, elQueryEngineForH);
                    beforeModelRun();
                    runLearningExperiment(args, hypothesisSizes.get(ontologies.indexOf(ontology)), model);
                    afterLearningExperiment();
                    if (counter != null) {
                        counter.close();
                    }
                    cleaningUp();
                }
                System.out.println("\nFinished experiment for " + ontology + "\n");
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.out.println("error" + e);
        }
        if (shouldPrintAverageStats()) {
            printAverageStats();
        }
    }

    /**
     * Macro/Micro Precision/Recall against the ground truth, as in Baris's
     * Evaluation.java. Reuses whatever pacloDataset() already built -- in the
     * A-induced arm that is the very baseSet and reasoner the sampler drew from.
     *
     * The expert reasoner precomputes the object property hierarchy and
     * assertions as well as the class ones, unlike the dataset's own: the
     * existential-restriction concepts in the C2/C3 baseSets are not classified
     * correctly without them.
     */
    protected void evaluateWithBaris() throws Exception {
        PacloDataset dataset = pacloDataset();
        if (dataset == null) {
            System.out.println("Baris evaluation unavailable: initialOntology.owl or baseSet not found beside " + targetFile);
            return;
        }
        OWLReasoner expertReasoner = new ElkReasonerFactory().createReasoner(groundTruthOntology);
        expertReasoner.precomputeInferences(
                InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS,
                InferenceType.OBJECT_PROPERTY_HIERARCHY, InferenceType.OBJECT_PROPERTY_ASSERTIONS);
        System.out.println("=== BARIS EVALUATION (Macro/Micro Precision/Recall)"
                + (experimentLabel().isEmpty() ? " \u2014 uniform PAC" : experimentLabel()) + " ===");
        new Evaluation().evaluate(hypothesisOntology, expertReasoner, dataset.baseSet(), dataset.initialReasoner());
    }

    protected void createWorkCounter(String ontologyShortName, String model) {
        counter = null; //new WorkLoadCounter(infoString(ontologyShortName, model, queryFormat, system));
    }

    protected void setLLMEngine(String model, String ontologyShortName) {
        // Bound here, not in setup(), so the cache and the engine can only ever
        // be resolved from one and the same model string.
        this.cacheModel = model;
        this.currentCache = null;
        WorkloadManager workloadManager = new WorkloadManagerImpl(model, system, maxTokens, queryFormat, ontologyShortName, cacheManager, counter);
        switch (queryFormat) {
            case "manchester" ->
                    llmQueryEngineForT = new LLMEngine(groundTruthOntology, myManager, workloadManager,
                            new OWLParserImpl(groundTruthOntology), new AxiomSimplifier(elQueryEngineForH, conceptRelation));
            case "nlp" ->
                    llmQueryEngineForT = new NLPLLMEngine(groundTruthOntology, myManager, workloadManager,
                            new OWLParserImpl(groundTruthOntology), new AxiomSimplifier(elQueryEngineForH, conceptRelation));
            default -> throw new IllegalStateException("Unexpected value: " + queryFormat);
        }
    }

    private void printAverageStats() {
        double divider = 2*ontologies.size() * models.size();
        System.out.println("% of left decompositions: " + 100 * totalLDecomp / divider + "%");
        System.out.println("% of right decompositions: " + 100 * totalRDecomp / divider + "%");
        System.out.println("% of mergings: " + 100 * totalMerge / divider + "%");
        System.out.println("% of branchings: " + 100 * totalBranch / divider + "%");
        System.out.println("% of saturations: " + 100 * totalSat / divider + "%");
        System.out.println("% of unsaturations: " + 100 * totalDesat / divider + "%");

        System.out.println("Average n° membership queries compared to Pac Samples: " + totalMembershipQ / divider);
        System.out.println("Average n° equivalence queries compared to Pac Samples: " + totalEquivalenceQ / divider);
        System.out.println("Average n° CE compared to Pac Samples: " + totalCE / divider);
    }

    protected void setup(String ontology, String model) {
        try {
            // Remembered so subclasses can reach this run's cache
            // (cacheManager.getCache(model, system)) outside of setup.
            this.currentModel = model;
            this.pacloDataset = null;
            this.pacloDatasetLoaded = false;
            myMetrics = new Metrics(myRenderer);
            System.out.println("Trying to load groundTruthOntology");
            loadTargetOntology(ontology);
            setUpOntologyFolders(queryFormat, system, model, ontology);
            saveTargetOntology();
            loadHypothesisOntology();
            System.out.println(groundTruthOntology);
            System.out.println("Loaded successfully.");
            System.out.println();
            System.out.flush();
            computeConceptAndRoleNumbers();
        } catch (OWLOntologyCreationException e) {
            System.out.println("Could not load groundTruthOntology: " + e.getMessage());
        } catch (IOException | OWLException e) {
            e.printStackTrace();
        }
    }

    // MODIFICATION (A-induced integration): visibility changed from private to
    // protected. The method body below is Ana's original training-and-save
    // logic, unchanged. Making it protected allows LaunchLLMLearnerAInduced
    // (a subclass in the same package) to call it directly from its own
    // overridden run() method, instead of duplicating this logic.
    protected void runLearningExperiment(String[] args, int hypothesisSize, String model) throws Throwable {
        long timeStart = System.currentTimeMillis();
        prewarmPrecomputationCache(model);
        runLearner(hypothesisSize);
        long timeEnd = System.currentTimeMillis();
        saveOWLFile(hypothesisOntology, hypoFile);
        validation();
        var systemCode = "simple";
        if (system.length() > 50) {
            systemCode = "advanced";
        }
        var filename =  targetFile.getName() + "_" + model + "_" + queryFormat + "_" + systemCode;
        var dir = "statistics/";
        var statFile = new File(dir, filename);
        printAndSaveStats(timeStart, timeEnd, args, true,
                targetFile, statFile, myMetrics, learner, oracle, conceptNumber, roleNumber, groundTruthOntology, hypothesisOntology);
    }

    /**
     * Optionally fills the cache for precomputation() with batched LLM calls
     * before the learner starts. Off unless EXACTLEARNER_BATCH_SIZE is set.
     *
     * This changes only HOW the precomputation answers are obtained, never
     * which questions are asked or how they are keyed -- precomputation() then
     * runs unmodified and reads them all from the cache. Any failure leaves the
     * cache untouched and the learner queries sequentially as before.
     */
    private void prewarmPrecomputationCache(String model) {
        if (!isPrecomputationEnabled()) {
            // Nothing downstream will read these answers, so fetching 17k of
            // them would be pure waste. See LaunchLLMLearnerAInduced.
            System.out.println("Batch pre-warm skipped: precomputation is disabled for this run.");
            return;
        }
        int batchSize = BatchPrewarmer.batchSizeFromEnv();
        if (batchSize <= 0) {
            return;
        }
        if (!(llmQueryEngineForT instanceof LLMEngine engine)) {
            System.out.println("Batch pre-warm skipped: engine is not an LLMEngine.");
            return;
        }
        try {
            BatchPrewarmer.prewarmPrecomputation(engine, currentCache(), system, batchSize);
        } catch (Throwable t) {
            // Deliberately broad: a pre-warm is an optimisation, and must never
            // be able to fail a run that would otherwise have completed.
            System.out.println("Batch pre-warm failed, continuing sequentially: " + t);
        }
    }

    /** Opt-in switch for batching the decomposition path. Off unless set to "true". */
    public static final String BATCH_DECOMPOSE_ENV = "EXACTLEARNER_BATCH_DECOMPOSE";

    /**
     * Opt-in switch for extending that batching to unsaturateLeft/saturateRight.
     * Requires BATCH_DECOMPOSE_ENV, since it reuses the same prefetcher.
     *
     * Separate from it because the two carry different risk. The decomposition
     * scans are unconditionally independent, so batching them can only change
     * when answers arrive. These sweeps are independent only until a mutation is
     * accepted, so the batch past an acceptance is bought and never used. That
     * is still correct -- the sweep re-asks and the cache simply misses -- but
     * whether it is faster depends on an acceptance rate nobody has measured.
     * Two flags means job 4022395's numbers stay reproducible while this is
     * being answered.
     */
    public static final String BATCH_UNSATURATE_ENV = "EXACTLEARNER_BATCH_UNSATURATE";

    /**
     * Lets the learner fetch each decomposition sweep's answers in batches
     * instead of one at a time.
     *
     * WHY
     * ---
     * Measured: 48 counterexamples in 24 hours, ~235 model
     * queries each, ~30 minutes apiece, and no sign of speeding up over the run.
     * Almost all of that is decompose() and checkTransformations() walking the
     * class signature one query at a time, which runs the model at batch size 1.
     * The same hardware answers 32 prompts at 1.31 s each against 11.5 s for one,
     * so a sweep that takes 20 minutes should take about 3.5.
     *
     * Unlike precomputation, these answers cannot be pre-warmed before the run:
     * the questions depend on counterexamples that do not exist yet. They can
     * only be fetched a sweep at a time, from inside the learner, which is why
     * this goes through a prefetcher rather than a pass like BatchPrewarmer's.
     *
     * WHY IT IS OFF BY DEFAULT
     * ------------------------
     * decompose() is Ana's original algorithm. With no prefetcher installed the
     * learner runs byte-identically to before, so leaving this unset reproduces
     * every earlier result exactly, and turning it on is a change to when
     * answers are fetched rather than to what the algorithm asks or concludes.
     *
     * Protected because LaunchLLMLearnerAInduced overrides run() and builds its
     * own Learner, so it has to install this itself. Anything else that
     * constructs a Learner must call this too, or it silently gets the
     * sequential path -- the only symptom is the absence of one log line.
     */
    protected void installDecomposePrefetcher(String model) {
        if (!"true".equals(System.getenv(BATCH_DECOMPOSE_ENV))) {
            // Says so out loud. This used to be a bare return, and job 4038936
            // spent 24 h on the sequential path because the only evidence was a
            // line that was not printed -- which reads exactly like a log you
            // have not scrolled to yet.
            System.out.println("Batched decomposition OFF: " + BATCH_DECOMPOSE_ENV
                    + " is " + System.getenv(BATCH_DECOMPOSE_ENV)
                    + ", not \"true\". The learner runs one query at a time.");
            return;
        }
        int batchSize = BatchPrewarmer.batchSizeFromEnv();
        if (batchSize <= 0) {
            System.out.println("Batched decomposition requested but " + BatchPrewarmer.BATCH_ENV
                    + " is unset or 0, so it stays off.");
            return;
        }
        if (!(llmQueryEngineForT instanceof LLMEngine engine)) {
            System.out.println("Batched decomposition skipped: engine is not an LLMEngine.");
            return;
        }
        Cache cache = currentCache();
        if (cache == null) {
            System.out.println("Batched decomposition skipped: no cache available.");
            return;
        }

        boolean batchUnsaturate = "true".equals(System.getenv(BATCH_UNSATURATE_ENV));
        learner.setBatchUnsaturation(batchUnsaturate);
        System.out.println("Batched decomposition ON, batch size " + batchSize
                + ", unsaturate/saturate sweeps " + (batchUnsaturate ? "ON" : "OFF") + ".");
        learner.setPrefetcher(axioms -> {
            // A sweep asks about the same axiom more than once -- decompose()
            // rebuilds the node description on every iteration, and split
            // superclasses share conjuncts -- and a duplicate would burn a slot
            // in the batch for an answer already in flight. LinkedHashSet keeps
            // signature order, so the batches follow the order of the sweep and
            // the answers the sweep needs first arrive first.
            LinkedHashSet<String> pending = new LinkedHashSet<>();
            for (OWLAxiom axiom : axioms) {
                for (String query : engine.queriesFor(axiom)) {
                    if (cache.resultString(query) == null) {
                        pending.add(query);
                    }
                }
            }
            if (!pending.isEmpty()) {
                BatchPrewarmer.fetchAndCache(cache, system, new ArrayList<>(pending), batchSize);
            }
        });
    }

    /**
     * Whether Learner.precomputation() will run for this experiment. Drives both
     * runLearner() below and the batch pre-warm above, which skips fetching
     * answers nothing will read when precomputation is off.
     */
    protected boolean isPrecomputationEnabled() {
        return !skipPrecomputation;
    }

    /**
     * Restores loop position from a previous job's checkpoint, returning the
     * counterexample number to continue from (0 for a fresh run).
     *
     * This used to live only in LaunchLLMLearnerAInduced's copy of the loop, so a
     * run WITH precomputation would read its checkpoint and announce the resume,
     * then silently start again from zero with a full budget. There is one loop
     * now, so there is one resume path and both arms honour it.
     */
    protected int restoreFromCheckpoint(Pac pac) throws Exception {
        if (resumedState.counterExamples <= 0) {
            return 0;
        }
        pac.restoreProvidedSamples(resumedState.providedSamples);
        restoreSamplerPosition(resumedState.samplerDraws);
        System.out.println("  resumed at counterexample " + resumedState.counterExamples
                + ", " + (long) pac.getNumberOfProvidedSamples() + "/" + pac.getNumberOfSamples()
                + " of the budget already spent");
        return resumedState.counterExamples;
    }

    /**
     * Replays a sampler's random stream to the checkpointed position. A no-op in
     * the uniform-PAC arm, which draws from Pac itself and has no stream of its
     * own to advance; LaunchLLMLearnerAInduced overrides it.
     */
    protected void restoreSamplerPosition(long samplerDraws) throws Exception {
    }

    /**
     * THE equivalence-query loop -- one copy, shared by every arm.
     *
     * What varies around it is hooked, not forked: precomputation is gated by
     * isPrecomputationEnabled(), the candidate source is getCounterExample()
     * (uniform PAC here, ABox-induced in the subclass), and evaluation runs
     * after the loop via afterLearningExperiment(). The loop body itself was
     * identical in both arms, which is why the second copy that used to live in
     * LaunchLLMLearnerAInduced could be removed outright.
     */
    protected void runLearner(int hypothesisSize) throws Throwable {
        int numberOfCounterExamples = 0;
        int seed = pacSeed();
        if (isPrecomputationEnabled()) {
            // Computes inclusions of the form A implies B
            learner.precomputation();
        } else {
            System.out.println("SKIPPING precomputation() — the loop starts from an empty hypothesis.");
        }
        Pac pac = new Pac(parser.getClasses().get(), parser.getObjectProperties(), epsilon, delta, hypothesisSize, seed);
        pac.setBudgetMode(Pac.budgetModeFromEnv());
        System.out.println("  PAC seed = " + seed + " (set " + PAC_SEED_ENV + " to vary it across repeats)");
        long totalPacSamples = pac.getNumberOfSamples();
        System.out.println("  PAC sample budget (numberOfSamples) = " + totalPacSamples
                + " per " + (pac.getBudgetMode() == Pac.BudgetMode.PER_ROUND ? "equivalence query" : "run")
                + " (" + Pac.BUDGET_MODE_ENV + "=" + pac.getBudgetMode() + ")");
        if (pac.getBudgetMode() == Pac.BudgetMode.PER_ROUND) {
            // Said out loud because it changes what a run means, not how fast
            // it gets there: under PER_ROUND the loop stops only once a full
            // fresh budget of candidates has failed against the hypothesis as
            // it then stands, which may not happen before walltime. Numbers
            // from such a run are not comparable with any global-budget run.
            System.out.println("  PER-ROUND BUDGET: each equivalence query starts from a full budget."
                    + " Termination is no longer guaranteed at " + totalPacSamples + " candidates,"
                    + " and results are NOT comparable with global-budget runs.");
        }
        numberOfCounterExamples = restoreFromCheckpoint(pac);
        while (true) {
            myMetrics.setEquivCount(myMetrics.getEquivCount() + 1);
            // A resumed run restores the global counter but always opens a
            // fresh round here, so under PER_ROUND the interrupted query's
            // partly-spent budget is handed back in full.
            pac.startRound();
            counterExample = getCounterExample(pac);
            if (counterExample == null) {
                System.out.println("No counterexample found, closing...");
                break;
            }
            System.out.println("Counterexample number: " + ++numberOfCounterExamples);
            // Update the total number of counterexamples
            // Add the last counterexample to axiomsT

            // Update size of the largest counterexample
            int size = myMetrics.getSizeOfCounterexample(counterExample);
            if (size > myMetrics.getSizeOfLargestCounterExample()) {
                myMetrics.setSizeOfLargestCounterExample(size);
            }

            // Decompose the last counterexample
            counterExample = learner.decompose(counterExample.getSubClass(), counterExample.getSuperClass());

            // Check if transformation can be applied
            checkTransformations();
            //addHypothesis(counterExample);

            // Persist what has been learned so far. A job killed at walltime
            // otherwise loses every counterexample found up to that point.
            providedSamples = (long) pac.getNumberOfProvidedSamples();
            checkpointHypothesis(numberOfCounterExamples);
        }
        totalCE += (double) numberOfCounterExamples / (double) totalPacSamples;
        totalMembershipQ += (double) myMetrics.getMembCount() / (double) totalPacSamples;
        totalEquivalenceQ += (double) myMetrics.getEquivCount() / (double) totalPacSamples;
    }
}
