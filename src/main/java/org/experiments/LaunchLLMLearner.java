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
import org.experiments.logger.CacheManager;
import org.experiments.logger.SmartLogger;
import org.experiments.workload.BatchPrewarmer;
import org.experiments.workload.WorkLoadCounter;
import org.experiments.workload.WorkloadManager;
import org.experiments.workload.WorkloadManagerImpl;
import org.pac.Pac;
import org.semanticweb.owlapi.model.*;
import org.utility.OntologyManipulator;
import org.utility.YAMLConfigLoader;
import java.io.File;
import java.io.IOException;
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
    // The model currently being run, set by setup(). Needed to resolve the cache.
    protected String currentModel;
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

    public void run(String[] args) {
        String configurationFile = args[0];
        if (args.length > 1) {
            epsilon = Double.parseDouble(args[1]);
        }
        if (args.length > 2) {
            delta = Double.parseDouble(args[2]);
        }
        SmartLogger.checkCachedFiles();
        loadConfiguration(configurationFile);
        try {
            for (String ontology : ontologies) {
                System.out.println("\nRunning experiment for " + ontology);
                for (String model : models) {
                    System.out.println("\nRunning experiment for " + model + "\n");
                    setup(ontology, model.replace(":", "-"));
                    elQueryEngineForH = new ELEngine(hypothesisOntology);
                    String ontologyShortName = ontology.substring(ontology.lastIndexOf("/") + 1, ontology.lastIndexOf("."));
                    createWorkCounter(ontologyShortName, model);
                    conceptRelation = new ConceptRelation<>();
                    setLLMEngine(model, ontologyShortName);
                    learner = new Learner(llmQueryEngineForT, elQueryEngineForH, myMetrics, conceptRelation);
                    oracle = new Oracle(llmQueryEngineForT, elQueryEngineForH);
                    runLearningExperiment(args, hypothesisSizes.get(ontologies.indexOf(ontology)), model);
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
        printAverageStats();
    }

    protected void createWorkCounter(String ontologyShortName, String model) {
        counter = null; //new WorkLoadCounter(infoString(ontologyShortName, model, queryFormat, system));
    }

    protected void setLLMEngine(String model, String ontologyShortName) {
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
            BatchPrewarmer.prewarmPrecomputation(
                    engine, cacheManager.getCache(model, system), system, batchSize);
        } catch (Throwable t) {
            // Deliberately broad: a pre-warm is an optimisation, and must never
            // be able to fail a run that would otherwise have completed.
            System.out.println("Batch pre-warm failed, continuing sequentially: " + t);
        }
    }

    /**
     * Whether Learner.precomputation() will run for this experiment. Always true
     * here; LaunchLLMLearnerAInduced overrides it when skipPrecomputation is set,
     * so the batch pre-warm above can avoid fetching answers nothing will read.
     */
    protected boolean isPrecomputationEnabled() {
        return true;
    }

    // Protected rather than private so LaunchLLMLearnerAInduced can bypass this
    // whole method when running the A-induced loop without precomputation.
    protected void runLearner(int hypothesisSize) throws Throwable {
        int numberOfCounterExamples = 0;
        int seed = 0;
        // Computes inclusions of the form A implies B
        learner.precomputation();
        Pac pac = new Pac(parser.getClasses().get(), parser.getObjectProperties(), epsilon, delta, hypothesisSize, seed);
        long totalPacSamples = pac.getNumberOfSamples();
        while (true) {
            myMetrics.setEquivCount(myMetrics.getEquivCount() + 1);
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
            checkpointHypothesis(numberOfCounterExamples);
        }
        totalCE += (double) numberOfCounterExamples / (double) totalPacSamples;
        totalMembershipQ += (double) myMetrics.getMembCount() / (double) totalPacSamples;
        totalEquivalenceQ += (double) myMetrics.getEquivCount() / (double) totalPacSamples;
    }
}
