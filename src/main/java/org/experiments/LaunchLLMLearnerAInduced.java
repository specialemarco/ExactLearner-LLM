package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.pac.Pac;
import org.sampler.ABoxInducedSubsumptionSampler;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxParserImpl;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.evaluation.Evaluation;
import org.exactlearner.engine.LLMEngine;
import org.experiments.logger.Cache;
import org.experiments.workload.BatchPrewarmer;

/**
 * NEW FILE — added for the A-induced sampling integration.
 *
 * Extends Ana's LaunchLLMLearner (the Mistral-based launcher) and replaces
 * the source of candidate axioms in the equivalence-query loop: instead of
 * Ana's uniform PAC sampler (pac.getRandomStatement()), this class uses
 * ABoxInducedSubsumptionSampler, which draws candidates grounded in the
 * ABox of the OWL2Bench ontology. Everything downstream of that single
 * substitution — checking entailment against the hypothesis, checking
 * entailment against Mistral, refining the counterexample via
 * getCounterExampleSubClassOf(), and the structural decomposition in
 * Learner.decompose() — is entirely Ana's original machinery, reused
 * unmodified.
 *
 * This class also adds the evaluation step (evaluateWithBaris, using
 * Baris's Macro/Micro Precision/Recall metrics) and a small number of
 * bookkeeping features (BaseSet-aware file naming, precomputation
 * skip flag) needed to run and compare the OWL2Bench experiments.
 */
public class LaunchLLMLearnerAInduced extends LaunchLLMLearner {

    // The ABox-induced sampler. Left null until the first call to
    // getCounterExample(), then lazily initialized by initAboxSampler()
    // (because it depends on files — initialOntology.owl, baseSet — that
    // live alongside the target ontology, only known once setup() has run).
    private ABoxInducedSubsumptionSampler aboxSampler = null;

    // Kept alongside aboxSampler so that evaluateWithBaris() can reuse the
    // exact same baseSet and reasoner used for sampling, without having to
    // reload/rebuild them a second time after the run finishes.
    private Set<OWLClassExpression> aInducedBaseSet = null;
    private OWLReasoner aInducedInitialReasoner = null;

    // Counts how many counterexamples were accepted during the A-induced
    // equivalence-query loop for the current run (reset per ontology/model
    // combination in run()). Reported at evaluation time.
    private int counterExampleCount = 0;

    // If true, Ana's precomputation() phase is skipped entirely and the run
    // measures the A-induced loop's contribution on its own. Precomputation
    // tests every ordered pair of class names before the loop starts, so it
    // absorbs the "easy" atomic subsumptions; turning it off is what isolates
    // how much the A-induced sampler finds by itself. Set via
    // LaunchLLMLearnerAInducedNoPre, not by editing this default.
    private boolean skipPrecomputation = false;

    // Batched candidate evaluation. Resolved lazily and turned off permanently
    // for the run if anything about the batch path is unavailable, so a broken
    // batch endpoint degrades to the original one-at-a-time loop rather than
    // failing a run that would otherwise have completed.
    private boolean batchedLoopDisabled = false;
    private Cache loopCache = null;

    /**
     * Seed for the A-induced sampler, from the environment. Fixed by default so
     * a rerun repeats the previous run's draws exactly; override it to get an
     * independent repeat of the same experiment.
     *
     * Note this is NOT the `seed` local in runLearner, which belongs to Pac and
     * has never governed A-induced sampling.
     */
    public static final String SAMPLER_SEED_ENV = "EXACTLEARNER_SAMPLER_SEED";

    public void setSkipPrecomputation(boolean skip) {
        this.skipPrecomputation = skip;
    }

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

    @Override
    protected boolean isPrecomputationEnabled() {
        return !skipPrecomputation;
    }

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchLLMLearnerAInduced().run(args);
    }

    /**
     * Runs the A-induced equivalence-query loop WITHOUT Ana's precomputation()
     * when skipPrecomputation is set; otherwise defers to the parent, which
     * calls precomputation() first.
     *
     * The loop body below is the parent's, minus the precomputation call: build
     * a Pac budget, then repeatedly ask for a counterexample until the sampler
     * exhausts it. The per-sample normalisation of the totals at the end
     * matches the parent so the two arms stay directly comparable.
     */
    @Override
    protected void runLearner(int hypothesisSize) throws Throwable {
        if (!skipPrecomputation) {
            super.runLearner(hypothesisSize);
            return;
        }

        int numberOfCounterExamples = 0;
        int seed = 0;
        Pac pac = new Pac(
                parser.getClasses().get(),
                parser.getObjectProperties(),
                epsilon, delta, hypothesisSize, seed);
        long totalPacSamples = pac.getNumberOfSamples();
        System.out.println("SKIP PRECOMPUTATION: pure A-induced loop");
        System.out.println("  PAC sample budget (numberOfSamples) = " + totalPacSamples);

        while (true) {
            myMetrics.setEquivCount(myMetrics.getEquivCount() + 1);
            counterExample = getCounterExample(pac);
            if (counterExample == null) {
                System.out.println("No counterexample found, closing...");
                break;
            }
            System.out.println("Counterexample number: " + ++numberOfCounterExamples);
            int size = myMetrics.getSizeOfCounterexample(counterExample);
            if (size > myMetrics.getSizeOfLargestCounterExample()) {
                myMetrics.setSizeOfLargestCounterExample(size);
            }
            counterExample = learner.decompose(counterExample.getSubClass(), counterExample.getSuperClass());
            checkTransformations();

            // See LaunchLearner.checkpointHypothesis: without this a walltime
            // kill discards every counterexample this loop has found.
            checkpointHypothesis(numberOfCounterExamples);
        }

        totalCE += (double) numberOfCounterExamples / (double) totalPacSamples;
        totalMembershipQ += (double) myMetrics.getMembCount() / (double) totalPacSamples;
        totalEquivalenceQ += (double) myMetrics.getEquivCount() / (double) totalPacSamples;
    }

    /**
     * Overrides LaunchLLMLearner.run(). The overall structure (loop over
     * ontologies, loop over models, setup/train/evaluate/cleanup) mirrors
     * Ana's original run() closely; the two additions are:
     *   - resetting aboxSampler/counterExampleCount before each run, so a
     *     fresh sampler is built for every ontology/model combination
     *     instead of reusing one left over from a previous iteration;
     *   - calling evaluateWithBaris() after runLearningExperiment(), to
     *     compute and print Macro/Micro Precision/Recall against the
     *     OWL2Bench ground truth once the hypothesis has been learned and
     *     saved.
     */
    @Override
    public void run(String[] args) {
        String configurationFile = args[0];
        if (args.length > 1) epsilon = Double.parseDouble(args[1]);
        if (args.length > 2) delta = Double.parseDouble(args[2]);

        org.experiments.logger.SmartLogger.checkCachedFiles();
        loadConfiguration(configurationFile);
        try {
            for (String ontology : ontologies) {
                System.out.println("\nRunning experiment (A-induced) for " + ontology);
                for (String model : models) {
                    System.out.println("\nRunning experiment for " + model + "\n");
                    setup(ontology, model.replace(":", "-"));
                    elQueryEngineForH = new org.exactlearner.engine.ELEngine(hypothesisOntology);
                    String ontologyShortName = ontology.substring(ontology.lastIndexOf("/") + 1, ontology.lastIndexOf("."));
                    createWorkCounter(ontologyShortName, model);
                    conceptRelation = new org.exactlearner.learner.ConceptRelation<>();
                    setLLMEngine(model, ontologyShortName);
                    learner = new org.exactlearner.learner.Learner(llmQueryEngineForT, elQueryEngineForH, myMetrics, conceptRelation);
                    oracle = new org.exactlearner.oracle.Oracle(llmQueryEngineForT, elQueryEngineForH);

                    // Force a fresh A-induced sampler for this ontology/model run.
                    aboxSampler = null;
                    counterExampleCount = 0;

                    runLearningExperiment(args, hypothesisSizes.get(ontologies.indexOf(ontology)), model);

                    // Baris-style evaluation (Macro/Micro Precision/Recall) after training.
                    try {
                        evaluateWithBaris();
                    } catch (Exception ex) {
                        System.out.println("Error during evaluateWithBaris: " + ex.getMessage());
                        ex.printStackTrace();
                    }

                    if (counter != null) counter.close();
                    cleaningUp();
                }
                System.out.println("\nFinished experiment for " + ontology + "\n");
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.out.println("error" + e);
        }
    }

    /**
     * Overrides LaunchLearner.getCounterExample(). This is the single most
     * important method in the whole integration: it is where Ana's uniform
     * PAC sampling is swapped out for A-induced sampling.
     *
     * On first call, lazily builds the A-induced sampler via
     * initAboxSampler(). If the required files (initialOntology.owl,
     * baseSet) are not found next to the target ontology — e.g. when this
     * launcher is pointed at one of Ana's original small ontologies rather
     * than an OWL2Bench BaseSet — it falls back to the parent class's
     * uniform PAC sampling (super.getCounterExample), so this class remains
     * safely usable on any ontology, not only OWL2Bench.
     *
     * The core substitution vs. Ana's original getCounterExample:
     *   BEFORE (uniform PAC): OWLSubClassOfAxiom ax = pac.getRandomStatement();
     *   AFTER  (A-induced):   OWLSubClassOfAxiom ax = aboxSampler.sample();
     *
     * Everything else in this loop — advancing the PAC sample counter,
     * checking entailment against the hypothesis (entH) and against
     * Mistral (entT), and calling getCounterExampleSubClassOf() once a
     * genuine counterexample (!entH && entT) is found — reuses Ana's
     * existing logic unchanged.
     *
     * NOTE: the DEBUG print statements below (for the first 10 samples of
     * each run) are temporary instrumentation added during development to
     * verify the sampler was producing sensible axioms and that entailment
     * checks behaved as expected. They are not part of the core algorithm.
     */
    @Override
    protected OWLSubClassOfAxiom getCounterExample(Pac pac) throws Exception {
        if (aboxSampler == null) {
            initAboxSampler();
            if (aboxSampler == null) {
                System.out.println("A-induced setup not available for this ontology — falling back to uniform PAC");
                return super.getCounterExample(pac);
            }
        }

        int batchSize = batchedLoopSize();

        while (pac.getNumberOfProvidedSamples() < pac.getNumberOfSamples()) {
            // Draw a block of candidates and fetch their answers in ONE call, so
            // the GPU sees batchSize prompts instead of one. This is purely a
            // transport change: the examination loop below is byte-for-byte the
            // original sequential one, and every answer it needs was just written
            // into the cache the engine reads. With a measured 1-in-264 hit rate,
            // almost every block is all-negative, so the speculation is nearly
            // always fully used.
            List<OWLSubClassOfAxiom> block = drawBlock(batchSize, pac);
            if (block.isEmpty()) return null;
            if (batchSize > 1) {
                prefetchAnswers(block, batchSize);
            }

            for (OWLSubClassOfAxiom selectedAxiom : block) {
                if (pac.getNumberOfProvidedSamples() >= pac.getNumberOfSamples()) {
                    return null;
                }
                // NOTE: incrementProvidedSamples() is a small addition to Ana's
                // Pac class (see Pac.java). It exists because pac.getRandomStatement()
                // normally advances this counter internally; since we bypass that
                // method entirely and call aboxSampler.sample() directly, we must
                // advance the counter here ourselves, or the PAC sample budget
                // would never be consumed and this loop would run forever.
                //
                // Only candidates actually EXAMINED consume budget. Anything left
                // in the block after a counterexample is discarded unexamined, so
                // the budget is spent exactly as it would be sequentially and the
                // two modes stay directly comparable. Their answers stay in the
                // cache, so redrawing them later costs nothing.
                pac.incrementProvidedSamples();
                if (pac.getNumberOfProvidedSamples() <= 10) {
                    System.out.println("DEBUG sampled: " + selectedAxiom.getSubClass() + " SubClassOf " + selectedAxiom.getSuperClass());
                }
                boolean entH = elQueryEngineForH.entailed(selectedAxiom);
                boolean entT = llmQueryEngineForT.entailed(selectedAxiom);
                if (pac.getNumberOfProvidedSamples() <= 10) {
                    System.out.println("DEBUG entH=" + entH + " entT=" + entT);
                }
                if (!entH && entT) {
                    counterExampleCount++;
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
        // getNumberOfProvidedSamples() returns double (it is a counter Ana exposes
        // for the statistics), so narrow it explicitly rather than letting the
        // subtraction promote to double.
        long remaining = pac.getNumberOfSamples() - (long) pac.getNumberOfProvidedSamples();
        int want = (int) Math.min(Math.max(batchSize, 1), Math.max(remaining, 0));
        List<OWLSubClassOfAxiom> block = new ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            OWLSubClassOfAxiom axiom = aboxSampler.sample();
            if (axiom == null) break;   // sampler exhausted
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
    private void prefetchAnswers(List<OWLSubClassOfAxiom> block, int batchSize) {
        if (batchedLoopDisabled) return;
        if (!(llmQueryEngineForT instanceof LLMEngine engine)) {
            batchedLoopDisabled = true;
            return;
        }
        Cache cache = loopCache();
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
                BatchPrewarmer.fetchAndCache(cache, system, new ArrayList<>(pending), batchSize);
            }
        } catch (Throwable t) {
            System.out.println("A-induced batch prefetch failed, continuing sequentially: " + t);
            batchedLoopDisabled = true;
        }
    }

    private Cache loopCache() {
        if (loopCache == null && currentModel != null) {
            loopCache = cacheManager.getCache(currentModel, system);
        }
        return loopCache;
    }

    /**
     * Overrides LaunchLearner.setUpOntologyFolders(). Ana's original method
     * derives the saved hypothesis filename only from the ontology's file
     * name (e.g. "expertOntology"), which is fine for her small ontologies
     * (each has a unique name) but is NOT fine here: OWL2Bench's C1, C2 and
     * C3 BaseSet configurations all point to files also named
     * "expertOntology.owl" (just in different folders — see the BaseSet tag
     * detection below), so without this override every C1/C2/C3 run would
     * silently overwrite the previous one's saved learned ontology.
     *
     * This override extracts a BaseSet tag (c1/c2/c3) from the parent
     * folder name of the target ontology file and injects it into both the
     * target-copy filename and the learned-hypothesis filename, e.g.
     * "expertOntology_c2_mistral_nlp_advanced.owl" instead of the
     * ambiguous "expertOntology_mistral_nlp_advanced.owl".
     */
    @Override
    protected void setUpOntologyFolders(String format, String system, String model, String ontology) {
        String name = java.nio.file.Path.of(ontology).getFileName().toString().replace(".owl", "");
        // Extract the BaseSet identifier (c1/c2/c3) from the parent folder name.
        String parentFolder = java.nio.file.Path.of(ontology).getParent().getFileName().toString();
        String baseSetTag;
        if (parentFolder.contains("exists_thing")) baseSetTag = "c2";
        else if (parentFolder.contains("exists_partial")) baseSetTag = "c3";
        else if (parentFolder.contains("class_names")) baseSetTag = "c1";
        else baseSetTag = parentFolder;

        String nameWithBaseSet = name + "_" + baseSetTag;
        ontologyFolder = "results" + java.io.File.separator + "ontologies" + java.io.File.separator + "target_" + nameWithBaseSet + ".owl";
        ontologyFolderH = "results" + java.io.File.separator + "ontologies" + java.io.File.separator + infoString(nameWithBaseSet, model, format, system) + ".owl";
    }

    /**
     * Lazily builds the A-induced sampler for the current target ontology.
     * Called once per run, on the first invocation of getCounterExample().
     *
     * Expects two files to exist alongside the target ontology (targetFile,
     * i.e. expertOntology.owl for OWL2Bench): "initialOntology.owl" and
     * "baseSet". If either is missing, this method returns without setting
     * aboxSampler, and the caller (getCounterExample) falls back to uniform
     * PAC sampling.
     *
     * CRITICAL FIX — ABox injection:
     * initialOntology.owl, as shipped, has 3677 individuals in its
     * signature but ZERO ClassAssertion axioms — its ABox is empty on
     * disk. Without injecting the real ABox, the sampler would find 0 of
     * 3677 individuals with any recognized type, making sampling
     * completely degenerate. Baris's own PACloOracle.java performs this
     * same injection at runtime (copying the ABox from the expert/ground-
     * truth ontology into the initial ontology before building the
     * reasoner), and this method replicates that behaviour exactly:
     *   initialOntology.add(groundTruthOntology.getABoxAxioms(...))
     * After this fix: 3668 of 3677 individuals receive at least one
     * recognized type from the baseSet (verified empirically).
     *
     * Also note precomputeInferences() only requests CLASS_HIERARCHY and
     * CLASS_ASSERTIONS — a plain structural reasoner would not be enough
     * here, since it only repeats facts asserted literally in the file; ELK
     * is used specifically because it performs the classification needed
     * to derive new type assignments beyond what is explicitly asserted.
     */
    private void initAboxSampler() throws Exception {
        File targetDir = targetFile.getParentFile();
        File initialOntologyFile = new File(targetDir, "initialOntology.owl");
        File baseSetFile = new File(targetDir, "baseSet");

        if (!initialOntologyFile.exists() || !baseSetFile.exists()) {
            return;
        }

        OWLOntologyManager initManager = OWLManager.createOWLOntologyManager();
        OWLOntology initialOntology = initManager.loadOntologyFromOntologyDocument(initialOntologyFile);

        // Faithful to PACloOracle.java: the real ABox is injected at runtime,
        // taken from groundTruthOntology (our expertOntology.owl).
        initialOntology.add(groundTruthOntology.getABoxAxioms(org.semanticweb.owlapi.model.parameters.Imports.INCLUDED));

        org.semanticweb.elk.owlapi.ElkReasonerFactory rf = new org.semanticweb.elk.owlapi.ElkReasonerFactory();
        OWLReasoner initialOntologyReasoner = rf.createReasoner(initialOntology);
        initialOntologyReasoner.precomputeInferences(
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);

        Set<OWLClassExpression> baseSet = readBaseSet(baseSetFile, initialOntology);

        OWLDataFactory df = OWLManager.getOWLDataFactory();
        long seed = samplerSeed();
        aboxSampler = new ABoxInducedSubsumptionSampler(baseSet, initialOntologyReasoner, df, seed);
        System.out.println("A-induced sampler seed: " + seed
                + " (set " + SAMPLER_SEED_ENV + " to vary it across repeats)");

        this.aInducedBaseSet = baseSet;
        this.aInducedInitialReasoner = initialOntologyReasoner;

        System.out.println("AInduced sampler ready. BaseSet: " + baseSet.size() +
                " | Individuals in initialOntology: " + initialOntology.getIndividualsInSignature().size());

        // DEBUG diagnostic: counts how many individuals ended up with at
        // least one recognized baseSet type after the ABox injection above.
        // Added during debugging of the injection fix described above, to
        // confirm the fix was actually working (before the fix this printed
        // 0; after, ~99.7% of individuals — 3668/3677 — were typed).
        int withTypes = 0;
        for (org.semanticweb.owlapi.model.OWLNamedIndividual ind : initialOntology.getIndividualsInSignature()) {
            for (OWLClassExpression ce : baseSet) {
                if (initialOntologyReasoner.getInstances(ce).containsEntity(ind)) {
                    withTypes++;
                    break;
                }
            }
        }
        System.out.println("DEBUG: individuals with at least one baseSet type: " + withTypes + " out of " + initialOntology.getIndividualsInSignature().size());
    }

    /**
     * Parses the external "baseSet" file into a set of OWLClassExpression
     * objects. This is a faithful line-by-line port of Baris's
     * Utils.readBaseSet from paclo, adapted to live inside this class.
     *
     * The file format allows three kinds of non-comment lines:
     *   - a plain concept IRI, e.g. <http://benchmark/OWL2Bench#Faculty>
     *   - an existential restriction in Manchester syntax, e.g.
     *     (<http://benchmark/OWL2Bench#advises> some owl:Thing)
     *   - the literal string "owl:Nothing" (bottom concept, special-cased
     *     because the Manchester parser does not accept it directly as an
     *     expression on its own in this context)
     * Lines that are empty or start with "#" are treated as comments and
     * skipped.
     *
     * A custom OWLEntityChecker is required because baseSet lines may
     * reference entities by their short name (fragment after "#") rather
     * than a full IRI; the checker resolves short names back to the actual
     * OWLEntity objects declared in the reference ontology's signature.
     *
     * The checker must also know owl:Thing explicitly. The C2 baseSet
     * (class_names_exists_thing) writes its fillers as the full IRI
     * <http://www.w3.org/2002/07/owl#Thing>, and the Manchester parser
     * hands exactly that bracketed string to the entity checker. Built-in
     * entities are not part of referenceOntology.signature(), so the
     * fragment-only map above resolves nothing for it and the parser
     * aborts with "Encountered <...owl#Thing> ... Expected Class name".
     * Registering owl:Thing/owl:Nothing under both their bracketed-IRI
     * and short forms fixes C2; C1 and C3 are unaffected (their fillers
     * are ordinary named classes that the parser resolves from the
     * default ontology).
     */
    private Set<OWLClassExpression> readBaseSet(File f, OWLOntology referenceOntology) throws Exception {
        OWLOntologyManager om = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();

        Set<OWLClassExpression> baseSet = new HashSet<>();
        ManchesterOWLSyntaxParser parser = new ManchesterOWLSyntaxParserImpl(om.getOntologyConfigurator(), df);
        parser.setDefaultOntology(referenceOntology);

        final Map<String, OWLEntity> map = new HashMap<>();
        referenceOntology.signature().forEach(x -> map.put(x.getIRI().getFragment(), x));
        for (OWLClass builtin : new OWLClass[]{df.getOWLThing(), df.getOWLNothing()}) {
            map.put(builtin.getIRI().getFragment(), builtin);            // Thing / Nothing
            map.put("owl:" + builtin.getIRI().getFragment(), builtin);   // owl:Thing / owl:Nothing
            map.put("<" + builtin.getIRI() + ">", builtin);              // <http://...owl#Thing>
        }
        parser.setOWLEntityChecker(new OWLEntityChecker() {
            private <T> T v(String name, Class<T> t) {
                OWLEntity e = map.get(name);
                if (t.isInstance(e)) return t.cast(e);
                return null;
            }
            @Override public OWLObjectProperty getOWLObjectProperty(String name) { return v(name, OWLObjectProperty.class); }
            @Override public OWLNamedIndividual getOWLIndividual(String name) { return v(name, OWLNamedIndividual.class); }
            @Override public OWLDatatype getOWLDatatype(String name) { return v(name, OWLDatatype.class); }
            @Override public OWLDataProperty getOWLDataProperty(String name) { return v(name, OWLDataProperty.class); }
            @Override public OWLClass getOWLClass(String name) { return v(name, OWLClass.class); }
            @Override public OWLAnnotationProperty getOWLAnnotationProperty(String name) { return v(name, OWLAnnotationProperty.class); }
        });

        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line = reader.readLine();
            while (line != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    line = reader.readLine();
                    continue;
                }
                OWLClassExpression clsExpr;
                if (line.equals("owl:Nothing")) {
                    clsExpr = df.getOWLNothing();
                } else {
                    parser.setStringToParse(line);
                    clsExpr = parser.parseClassExpression();
                }
                baseSet.add(clsExpr);
                line = reader.readLine();
            }
        }
        return baseSet;
    }

    /**
     * Evaluates the learned hypothesisOntology using the same logic
     * (Macro/Micro Precision/Recall) as Baris's Evaluation.java from
     * paclo. Must be called AFTER the run has completed, i.e. after
     * hypothesisOntology has been populated and saved (see run()).
     *
     * Builds a fresh ELK reasoner directly on groundTruthOntology (the
     * OWL2Bench expert ontology) to serve as the "ground truth" reasoner
     * that Evaluation.evaluate() compares the learned hypothesis against.
     * Unlike initAboxSampler()'s reasoner, this one requests full
     * precomputation including object property hierarchy/assertions,
     * since existential-restriction concepts in the baseSet (present in
     * C2/C3) need those inferences to be classified correctly.
     */
    public void evaluateWithBaris() throws Exception {
        if (aInducedBaseSet == null || aInducedInitialReasoner == null) {
            System.out.println("Baris evaluation not available: A-induced sampler was not initialized for this run.");
            return;
        }
        org.semanticweb.elk.owlapi.ElkReasonerFactory rf = new org.semanticweb.elk.owlapi.ElkReasonerFactory();
        OWLReasoner expertReasoner = rf.createReasoner(groundTruthOntology);
        expertReasoner.precomputeInferences(
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS,
            org.semanticweb.owlapi.reasoner.InferenceType.OBJECT_PROPERTY_HIERARCHY,
            org.semanticweb.owlapi.reasoner.InferenceType.OBJECT_PROPERTY_ASSERTIONS);

        System.out.println("=== BARIS EVALUATION (Macro/Micro Precision/Recall) ===");
        System.out.println("Counterexamples found during this run: " + counterExampleCount);
        new Evaluation().evaluate(hypothesisOntology, expertReasoner, aInducedBaseSet, aInducedInitialReasoner);
    }

    public int getCounterExampleCount() {
        return counterExampleCount;
    }
}
