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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.evaluation.Evaluation;

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

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchLLMLearnerAInduced().run(args);
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
        if (args.length > 3) skipPrecomputation = Boolean.parseBoolean(args[3]);
        System.out.println("skipPrecomputation = " + skipPrecomputation);

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
                System.out.println("Setup AInduced non disponibile per questa ontologia — fallback a PAC uniforme");
                return super.getCounterExample(pac);
            }
        }

        // Flat Occam-bound budget (pac.getNumberOfSamples()), validated to
        // find real counterexamples on C3 (1->4) and C2 (48->186) when
        // combined with the corrected weighted sampler. paclo's per-round
        // growing budget (Pac.callsToSamplingOracle) was tried and found
        // structurally unable to provide a sufficient budget given the
        // observed ~1/275 LLM success rate on sampled candidates — see the
        // paclo-stopping-condition-experiment branch for that negative
        // result, kept as documentation.
        long localBudget = pac.getNumberOfSamples();

        OWLSubClassOfAxiom found = searchForCounterExample(pac, localBudget);
        if (found != null) {
            return found;
        }

        System.out.println("RETRY-DEBUG: budget locale (" + localBudget + ") esaurito senza controesempio, aggiorno sampler su hypothesisOntology e riprovo");

        org.semanticweb.elk.owlapi.ElkReasonerFactory rf = new org.semanticweb.elk.owlapi.ElkReasonerFactory();
        org.semanticweb.owlapi.reasoner.OWLReasoner hypothesisReasoner = rf.createReasoner(hypothesisOntology);
        hypothesisReasoner.precomputeInferences(
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);
        // updateConclusion=false: on retry, refresh only the premise-side
        // instance types, not the conclusion (rhs) weights — matches paclo's
        // own retry call (LearningFrameworkSubsumption.getCounterExample()),
        // fixed in Baris Sertkaya's "Fixing the distribution of the right
        // handside" commit.
        aboxSampler.update_sampler(hypothesisReasoner, false);

        found = searchForCounterExample(pac, localBudget);
        System.out.println("RETRY-DEBUG: secondo tentativo " + (found != null ? "ha trovato un controesempio" : "esaurito anch'esso, chiudo il round"));
        return found;
    }

    private OWLSubClassOfAxiom searchForCounterExample(Pac pac, long localBudget) throws Exception {
        for (long attempt = 0; attempt < localBudget; ++attempt) {
            pac.incrementProvidedSamples();
            OWLSubClassOfAxiom selectedAxiom = aboxSampler.sample();
            if (selectedAxiom == null) return null;
            if (attempt < 10) {
                System.out.println("DEBUG sampled: " + selectedAxiom.getSubClass() + " SubClassOf " + selectedAxiom.getSuperClass());
            }
            boolean entH = elQueryEngineForH.entailed(selectedAxiom);
            boolean entT = llmQueryEngineForT.entailed(selectedAxiom);
            if (attempt < 10) {
                System.out.println("DEBUG entH=" + entH + " entT(Mistral)=" + entT);
            }
            if (!entH && entT) {
                counterExampleCount++;
                return getCounterExampleSubClassOf(selectedAxiom);
            }
        }
        return null;
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
        aboxSampler = new ABoxInducedSubsumptionSampler(baseSet, initialOntologyReasoner, df);

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
     */
    private Set<OWLClassExpression> readBaseSet(File f, OWLOntology referenceOntology) throws Exception {
        OWLOntologyManager om = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();

        Set<OWLClassExpression> baseSet = new HashSet<>();
        ManchesterOWLSyntaxParser parser = new ManchesterOWLSyntaxParserImpl(om.getOntologyConfigurator(), df);
        parser.setDefaultOntology(referenceOntology);

        final Map<String, OWLEntity> map = new HashMap<>();
        referenceOntology.signature().forEach(x -> map.put(x.getIRI().getFragment(), x));
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
