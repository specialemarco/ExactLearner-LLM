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
import java.util.Locale;
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

    /**
     * Where the query cache lives. Defaults to the shared cache.sqlite3 in the
     * working directory, which is the point of it -- an answer paid for once is
     * replayed by every later run of the same (model, system).
     *
     * Point this at a path of its own for a run whose timings have to stand on
     * their own: the cache is keyed by (model, system, query) and NOT by
     * ontology or by run, so a rerun of the same configuration replays the
     * previous run's answers and issues no LLM call for them. A speedup measured
     * against a warm cache is measuring the cache.
     */
    public static final String CACHE_PATH_ENV = "EXACTLEARNER_CACHE";

    protected static String cachePath() {
        String raw = System.getenv(CACHE_PATH_ENV);
        return (raw == null || raw.isBlank()) ? "cache.sqlite3" : raw.trim();
    }

    /**
     * Whether the cache file was already there when this JVM started. Static, so
     * it is evaluated before the CacheManager field below -- constructing that
     * creates the file, after which the question can no longer be asked.
     */
    private static final boolean CACHE_EXISTED = new java.io.File(cachePath()).exists();

    protected final CacheManager cacheManager = new CacheManager(cachePath(), false);

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchLLMLearner().run(args);
    }

    /**
     * The model this run queries, overriding whatever the config names.
     *
     * The model string is a cache key and a label -- it is NOT what selects the
     * weights. llm_server.py serves whatever --model path it was started with and
     * merely echoes this name back, so on the cluster the weights are chosen by
     * MODEL_PATH and the name only decides which cache rows are read and written
     * and what the output file is called. That is why they have to agree, and why
     * having the config state it separately was a standing trap: every
     * mistral-owl2bench-*.yml names deepseek-r1-32b.
     *
     * Set, the config need not name a model at all, so one config serves every
     * model. scripts/run_experiment.sh exports it from MODEL_NAME in
     * scripts/models/<model>.env, which is the same file that supplies the weights,
     * so the name and the weights cannot drift apart.
     */
    public static final String MODEL_ENV = "EXACTLEARNER_MODEL";

    /**
     * Reconciles the config's models: list with the environment.
     *
     * The override collapses the list to one entry, deliberately: run()'s loop over
     * models dates from a shared Ollama server that hosted many, and against a
     * single-model vLLM server the second and later entries would be answered by the
     * first one's weights and filed under their own cache key. Says out loud what it
     * dropped rather than doing it quietly.
     */
    protected List<String> resolveModels(List<String> fromConfig) {
        String raw = System.getenv(MODEL_ENV);
        String override = (raw == null || raw.isBlank()) ? null : raw.trim();

        if (override == null) {
            if (fromConfig == null || fromConfig.isEmpty()) {
                throw new IllegalStateException(
                        "This config names no model and " + MODEL_ENV + " is unset, so there is "
                        + "nothing to query. Submit through scripts/submit.sh, which exports it "
                        + "from scripts/models/<model>.env, or set it yourself: "
                        + MODEL_ENV + "=deepseek-r1-32b");
            }
            System.out.println("models = " + fromConfig + " (from the config; set "
                    + MODEL_ENV + " to override)");
            return fromConfig;
        }

        if (fromConfig == null || fromConfig.isEmpty()) {
            System.out.println("model = " + override + " (from " + MODEL_ENV
                    + "; the config names none)");
        } else if (fromConfig.size() == 1 && fromConfig.get(0).equals(override)) {
            System.out.println("model = " + override + " (from " + MODEL_ENV
                    + ", and the config agrees)");
        } else {
            System.out.println("model = " + override + " (from " + MODEL_ENV
                    + ", OVERRIDING the config's " + fromConfig + ")");
        }
        return List.of(override);
    }

    protected void loadConfiguration(String fileName) {
        Configuration config = new YAMLConfigLoader().getConfig(fileName, Configuration.class);
        //choose configuration from file here:
        models = resolveModels(config.getModels());
        system = config.getSystem();
        queryFormat = config.getQueryFormat();
        ontologies = config.getOntologies();
        maxTokens = config.getMaxTokens();
        // They size the PAC sample budget, which every sampler draws against.
        if (config.getEpsilon() != null) epsilon = config.getEpsilon();
        if (config.getDelta() != null) delta = config.getDelta();
        System.out.println("epsilon = " + epsilon + ", delta = " + delta);
        hypothesisSizes = ontologies.stream().map(OntologyManipulator::computeOntologySize).collect(Collectors.toList());
    }

    // ---- The three experiment axes ---------------------------------------
    //
    // One launcher covers what used to be four classes, because the three
    // things that varied are independent of each other and of the loop:
    //
    //   precomputation  BEFORE the loop  -- skipPrecomputation, args[1]
    //   sampler         INSIDE the loop  -- getCounterExample(), overridden
    //   evaluation      AFTER the loop   -- evaluateAfterRun, args[2]
    //
    // The loop itself is identical in every arm, so it exists once, in
    // runLearner() below. LaunchLLMLearnerAInducedNoPre (which only set
    // skipPrecomputation) and LaunchLLMLearnerWithBarisEval (which only set
    // evaluateAfterRun) were removed on 2026-08-27 in favour of these flags.

    /**
     * Optional 2nd CLI arg. Disables learner.precomputation() in runLearner(),
     * for experiments isolating the sampling loop's contribution from
     * precomputation's.
     */
    protected boolean skipPrecomputation = false;

    /**
     * Optional 3rd CLI arg. Runs Baris's Macro/Micro Precision/Recall evaluation
     * after each model finishes. Off here so the plain PAC arm is unchanged;
     * LaunchLLMLearnerAInduced defaults it on, as it always evaluated.
     */
    protected boolean evaluateAfterRun = false;

    // <config> [skipPrecomputation] [evaluateAfterRun]. Epsilon and delta come
    // from the config (loadConfiguration).
    protected void parseExperimentArgs(String[] args) {
        if (args.length > 1) {
            skipPrecomputation = Boolean.parseBoolean(args[1]);
        }
        if (args.length > 2) {
            evaluateAfterRun = Boolean.parseBoolean(args[2]);
        }
        System.out.println("skipPrecomputation = " + skipPrecomputation);
        System.out.println("evaluateAfterRun = " + evaluateAfterRun);
        // Checked here, not at first use: an arm the launcher class cannot
        // honour must fail before the model loads, not two hours into the loop.
        System.out.println("sampler = " + samplerArm() + " (" + SAMPLER_ENV + ")");
        if (samplerArm() != SamplerArm.PAC && !(this instanceof LaunchLLMLearnerAInduced)) {
            throw new IllegalStateException(SAMPLER_ENV + "=" + samplerArm()
                    + " needs org.experiments.LaunchLLMLearnerAInduced, but this run is "
                    + getClass().getName() + ". run_experiment.sh picks the class from"
                    + " sampler=; a bare java invocation has to pick it too.");
        }
        // Recorded in the log because a warm cache is invisible in the timings
        // otherwise, and it is the first thing to check before believing them.
        System.out.println("cache = " + cachePath() + (CACHE_EXISTED ? " (existing)" : " (new, cold)"));
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

    /**
     * Which candidate sampler the loop draws from: pac
     * (Pac.getRandomStatement(), never looks at the ABox), weighted, or
     * unweighted. The last two are ABoxInducedSubsumptionSampler.Weighting.
     * Unset means the launcher class's own default.
     */
    public static final String SAMPLER_ENV = "EXACTLEARNER_SAMPLER";

    public enum SamplerArm { PAC, WEIGHTED, UNWEIGHTED }

    private SamplerArm samplerArm = null;

    /** The arm this launcher class runs when SAMPLER_ENV is unset. */
    protected SamplerArm defaultSamplerArm() {
        return SamplerArm.PAC;
    }

    /**
     * Throws on an unrecognised value rather than defaulting: run_args.sh
     * validates first, so reaching here means it was set by hand, and the
     * failure mode is a 24 h job running a different arm than the one asked for.
     */
    protected SamplerArm samplerArm() {
        if (samplerArm != null) {
            return samplerArm;
        }
        String raw = System.getenv(SAMPLER_ENV);
        if (raw == null || raw.isBlank()) {
            return samplerArm = defaultSamplerArm();
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "pac": case "uniform":
                return samplerArm = SamplerArm.PAC;
            case "weighted": case "abox": case "abox-weighted":
                return samplerArm = SamplerArm.WEIGHTED;
            case "unweighted": case "abox-unweighted": case "plain":
                return samplerArm = SamplerArm.UNWEIGHTED;
            default:
                throw new IllegalArgumentException(SAMPLER_ENV + "=" + raw
                        + " is not a sampler. Expected pac, weighted or unweighted.");
        }
    }

    /**
     * Reuse one precomputation across the repeats of an experiment. The pass is
     * deterministic and seed-independent -- 17,030 ordered class pairs on the
     * 131-class OWL2Bench targets -- so every repeat recomputes the same thing.
     * Set, the first repeat records it and the rest replay it.
     */
    public static final String PRECOMP_REUSE_ENV = "EXACTLEARNER_PRECOMP_REUSE";

    protected boolean precomputationReuse() {
        String raw = System.getenv(PRECOMP_REUSE_ENV);
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    /** Names the arm in the run banner, e.g. " (A-induced)". */
    protected String experimentLabel() {
        return "";
    }

    /** Per-(ontology, model) reset, after setup and before the learner runs. */
    protected void beforeModelRun() {
    }

    /**
     * Called twice when precomputation is on -- after it and after the loop --
     * which is what separates the exhaustive pass's contribution from the
     * sampler's.
     */
    protected void evaluateIfRequested(String phase) {
        if (!evaluateAfterRun) {
            return;
        }
        try {
            evaluateWithBaris(phase);
        } catch (Exception ex) {
            System.out.println("Error during evaluateWithBaris (" + phase + "): " + ex.getMessage());
        }
    }

    /** Post-run hook; by default the optional Baris evaluation. */
    protected void afterLearningExperiment() {
        evaluateIfRequested("after learning");
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
        evaluateWithBaris("after learning");
    }

    /** `phase` goes in the banner: two evaluations in one log are otherwise indistinguishable. */
    protected void evaluateWithBaris(String phase) throws Exception {
        PacloDataset dataset = pacloDataset();
        if (dataset == null) {
            System.out.println("Baris evaluation unavailable: initialOntology.owl or baseSet not found beside " + targetFile);
            return;
        }
        System.out.println("=== BARIS EVALUATION (Macro/Micro Precision/Recall) " + phase
                + (experimentLabel().isEmpty() ? " \u2014 uniform PAC" : experimentLabel())
                + ", hypothesis has "
                + (hypothesisOntology == null ? 0 : hypothesisOntology.getLogicalAxiomCount())
                + " logical axioms ===");
        new Evaluation().evaluate(hypothesisOntology, expertReasoner(), dataset.baseSet(),
                dataset.initialReasoner());
    }

    /**
     * The classified ground truth, cached per (ontology, model) since
     * evaluateWithBaris() now runs twice. Precomputes the object property
     * hierarchy and assertions too, unlike the dataset's own reasoner: without
     * them the existential-restriction concepts in the C2/C3 baseSets classify
     * wrongly.
     */
    private OWLReasoner expertReasoner;

    protected OWLReasoner expertReasoner() {
        if (expertReasoner == null) {
            expertReasoner = new ElkReasonerFactory().createReasoner(groundTruthOntology);
            expertReasoner.precomputeInferences(
                    InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS,
                    InferenceType.OBJECT_PROPERTY_HIERARCHY, InferenceType.OBJECT_PROPERTY_ASSERTIONS);
        }
        return expertReasoner;
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
            this.expertReasoner = null;
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
    /** Start of the timed span "Total time (ms)" reports; runLearner() splits it. */
    private long learningStartMillis;

    protected void runLearningExperiment(String[] args, int hypothesisSize, String model) throws Throwable {
        long timeStart = System.currentTimeMillis();
        learningStartMillis = timeStart;
        prewarmPrecomputationCache(model);
        runLearner(hypothesisSize);
        long timeEnd = System.currentTimeMillis();
        saveOWLFile(hypothesisOntology, hypoFile);
        validation();
        var systemCode = "simple";
        if (system.length() > 50) {
            systemCode = "advanced";
        }
        // Built here rather than from infoString(), so the run tag has to be
        // appended separately: without it every repeat of an experiment writes
        // its statistics over the previous one's, which is precisely the file
        // the confidence interval is computed from.
        var filename =  targetFile.getName() + "_" + model + "_" + queryFormat + "_" + systemCode;
        if (!runTag().isEmpty()) {
            filename = filename + "_" + runTag();
        }
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
     * Runs precomputation, or replays an earlier identical one. Never fails the
     * run: a missing, stale or unreadable record falls back to computing.
     */
    protected void runPrecomputation() throws Exception {
        if (!precomputationReuse()) {
            learner.precomputation();
            return;
        }
        String fingerprint = precomputationFingerprint();
        List<String> steps = readPrecomputationRecord(fingerprint);
        if (steps != null) {
            System.out.println("Replaying precomputation from " + precomputationRecordPath
                    + " (" + steps.size() + " steps) — set " + PRECOMP_REUSE_ENV
                    + "=false to recompute it.");
            learner.replayPrecomputation(steps, learner.precomputationClassCount());
            return;
        }
        learner.precomputation();
        writePrecomputationRecord(fingerprint);
    }

    /**
     * The filename already carries ontology, model, format and system; this
     * catches what it cannot -- the same dataset edited under a saved record.
     */
    private String precomputationFingerprint() {
        List<String> iris = new ArrayList<>();
        for (OWLClass c : elQueryEngineForH.getClassesInSignature()) {
            iris.add(c.getIRI().toString());
        }
        java.util.Collections.sort(iris);
        return iris.size() + ":" + Integer.toHexString(String.join("\n", iris).hashCode());
    }

    /** The recorded steps, or null when there is nothing usable to replay. */
    private List<String> readPrecomputationRecord(String fingerprint) {
        File file = new File(precomputationRecordPath);
        if (!file.isFile()) {
            return null;
        }
        try {
            List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
            if (lines.isEmpty() || !lines.get(0).equals("# " + fingerprint)) {
                System.out.println("Ignoring " + precomputationRecordPath
                        + ": it was recorded against a different target ontology."
                        + " Recomputing and replacing it.");
                return null;
            }
            return lines.subList(1, lines.size());
        } catch (IOException e) {
            System.out.println("Could not read " + precomputationRecordPath + " (" + e
                    + "); recomputing.");
            return null;
        }
    }

    private void writePrecomputationRecord(String fingerprint) {
        List<String> steps = learner.precomputationSteps();
        if (steps == null) {
            return;
        }
        File file = new File(precomputationRecordPath);
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            List<String> out = new ArrayList<>(steps.size() + 1);
            out.add("# " + fingerprint);
            out.addAll(steps);
            // Temp file then move: a half-written record read by a concurrent
            // repeat would be replayed as if it were the whole pass.
            File tmp = new File(file.getParentFile(),
                    file.getName() + ".tmp-" + ProcessHandle.current().pid());
            java.nio.file.Files.write(tmp.toPath(), out);
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            System.out.println("Recorded precomputation to " + precomputationRecordPath
                    + " (" + steps.size() + " steps); later repeats will replay it.");
        } catch (IOException e) {
            System.out.println("Could not write " + precomputationRecordPath + " (" + e
                    + "); later repeats will recompute.");
        }
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
        // setup() builds a fresh Metrics for every job, so without this the
        // statistics file of a resumed run counts only that job's own queries.
        // Restored here because runLearner() has not entered the loop yet, and
        // the loop is the only thing that reads or advances them.
        if (resumedState.metricsPresent) {
            myMetrics.setMembCount(resumedState.membCount);
            myMetrics.setEquivCount(resumedState.equivCount);
            myMetrics.setSizeOfLargestCounterExample(resumedState.largestCounterExample);
        } else {
            System.out.println("  WARNING: this checkpoint predates metrics carry-over."
                    + " The hypothesis and the sample position resume exactly, but the"
                    + " membership/equivalence counts restart from zero -- this run's"
                    + " query totals will undercount by whatever the earlier job spent.");
        }
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
            runPrecomputation();
            // Both inside "Total time (ms)", so logged for taking back out: the
            // precomputation from the start of that span, pre-warm included.
            long precomputedAt = System.currentTimeMillis();
            System.out.println("Precomputation time (ms): " + (precomputedAt - learningStartMillis));
            // Evaluated here as well as after the loop, so the two figures
            // separate what the exhaustive pass already knew from what the
            // sampling loop went on to add. Same evaluator, same ground truth;
            // only the hypothesis differs, because it is the one at this point.
            evaluateIfRequested("after precomputation");
            System.out.println("Precomputation evaluation time (ms): "
                    + (System.currentTimeMillis() - precomputedAt));
        } else {
            int startingAxioms = hypothesisOntology == null ? 0 : hypothesisOntology.getLogicalAxiomCount();
            System.out.println("SKIPPING precomputation() — the loop starts from "
                    + (startingAxioms == 0
                            ? "an empty hypothesis."
                            : "the resumed hypothesis (" + startingAxioms + " logical axioms)."));
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
