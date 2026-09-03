package org.experiments;

import org.exactlearner.engine.AxiomSimplifier;
import org.exactlearner.engine.BaseEngine;
import org.exactlearner.learner.ConceptRelation;
import org.exactlearner.learner.Learner;
import org.exactlearner.oracle.Oracle;
import org.exactlearner.parser.OWLParser;
import org.exactlearner.parser.OWLParserImpl;
import org.exactlearner.utils.Metrics;
import org.pac.Pac;
import org.utility.PacloDataset;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public abstract class LaunchLearner {

    protected int conceptNumber;
    protected int roleNumber;
    protected File targetFile;
    protected String ontologyFolder = "";
    protected BaseEngine llmQueryEngineForT;
    protected BaseEngine elQueryEngineForH;

    protected OWLClassExpression lastExpression;
    protected OWLSubClassOfAxiom counterExample;
    protected OWLOntology groundTruthOntology;
    protected OWLOntology hypothesisOntology;
    protected Learner learner;
    protected File hypoFile;
    protected OWLParser parser;
    protected String ontologyFolderH = "";
    protected Oracle oracle = null;
    protected OWLClass lastName = null;
    protected Set<OWLAxiom> axiomsT = new HashSet<>();

    /** Opt-in resume; see loadHypothesisOntology() and the RunState class. */
    protected static final String RESUME_ENV = "EXACTLEARNER_RESUME";

    /** State recovered by loadHypothesisOntology(); empty on a fresh run. */
    protected RunState resumedState = RunState.empty();

    /** Live PAC counter at checkpoint time, set by the loop that owns the Pac. */
    protected long providedSamples = 0L;

    protected final static OWLOntologyManager myManager = OWLManager.createOWLOntologyManager();
    final static OWLObjectRenderer myRenderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
    protected final static String fileSeparator = System.getProperty("file.separator");
    protected Metrics myMetrics = new Metrics(myRenderer);

    protected ConceptRelation<OWLClass> conceptRelation;

    protected void validation() throws Exception {
        // validateLearnedOntology();
        printVictoryMessage();
    }

    // PARKED -- sole call site is commented out a few lines above.
    // Uncomment there to re-enable post-run validation of the hypothesis ontology.
    private void validateLearnedOntology() {
        if (!elQueryEngineForH.entailed(axiomsT)) {
            // throw new Exception("Something went horribly wrong!");
            System.out.println("Something went horribly wrong!");
        }
    }

    private void printVictoryMessage() {
        System.out.println("\nOntology learned successfully!");
        System.out.println("Congratulations!");
    }

    void cleaningUp() {
        llmQueryEngineForT.disposeOfReasoner();
        //llmQueryEngineForH.disposeOfReasoner();
        elQueryEngineForH.disposeOfReasoner();
        //llmQueryEngineForH.disposeOfReasoner();
        myManager.removeOntology(hypothesisOntology);
        myManager.removeOntology(groundTruthOntology);
    }

    protected void checkTransformations() throws Exception {
        if (canTransformELrhs()) {
            processRightHandSideTransformations();
        } else if (canTransformELlhs()) {
            processLeftHandSideTransformations();
        } else {
            handleNoTransformation();
        }
    }

    private void processRightHandSideTransformations() throws Exception {
        counterExample = computeEssentialRightCounterexample();
        OWLClass left = (OWLClass) counterExample.getSubClass();
        boolean mergable = false;
        for (OWLSubClassOfAxiom ax : elQueryEngineForH.getOntology().getSubClassAxiomsForSubClass(lastName)) {
            if (ax.getSubClass().getClassExpressionType() == ClassExpressionType.OWL_CLASS &&
                    ax.getSubClass().equals(lastName) && !ax.getSuperClass().isOWLClass()
            ) {
                mergable = true;
                Set<OWLClassExpression> mySet = new HashSet<>(ax.getSuperClass().asConjunctSet());
                mySet.addAll(lastExpression.asConjunctSet());
                lastExpression = llmQueryEngineForT.getOWLObjectIntersectionOf(mySet);
                counterExample = llmQueryEngineForT.getSubClassAxiom(lastName, lastExpression);
            }
        }
        if (mergable) {
            counterExample = computeEssentialRightCounterexample();
            if (!left.equals(counterExample.getSubClass())) {
                System.out.println("Something went horribly wrong!");
            }
        }
        addHypothesis(counterExample);
    }

    private void processLeftHandSideTransformations() throws Exception {
        counterExample = computeEssentialLeftCounterexample();
        addHypothesis(counterExample);
    }

    private void handleNoTransformation() {
        addHypothesis(counterExample);
        //System.out.println("No reduction possible:" + counterExample.getSubClass() + " SubclassOf " + counterExample.getSuperClass());
    }

    void addHypothesis(OWLAxiom addedAxiom) {
        if (addedAxiom instanceof OWLSubClassOfAxiom a) {
            Optional<OWLSubClassOfAxiom> axiom = new AxiomSimplifier(elQueryEngineForH, conceptRelation).shorten(a);
            if (axiom.isEmpty()) {
                return;
            }
            addedAxiom = axiom.get();
        }
        System.out.println(new ManchesterOWLSyntaxOWLObjectRendererImpl().render(addedAxiom));
        myManager.addAxiom(hypothesisOntology, addedAxiom);
    }

    private Boolean canTransformELrhs() {
        OWLSubClassOfAxiom counterexample = counterExample;
        OWLClassExpression left = counterexample.getSubClass();
        OWLClassExpression right = counterexample.getSuperClass();
        for (OWLClass cl1 : left.getClassesInSignature()) {
            if (oracle.isCounterExample(cl1, right)) {
                counterExample = llmQueryEngineForT.getSubClassAxiom(cl1, right);
                lastExpression = right;
                lastName = cl1;
                return true;
            }
        }
        return false;
    }

    private Boolean canTransformELlhs() {
        OWLSubClassOfAxiom counterexample = counterExample;
        OWLClassExpression left = counterexample.getSubClass();
        OWLClassExpression right = counterexample.getSuperClass();
        for (OWLClass cl1 : right.getClassesInSignature()) {
            if (oracle.isCounterExample(left, cl1)) {
                counterExample = llmQueryEngineForT.getSubClassAxiom(left, cl1);
                lastExpression = left;
                lastName = cl1;
                return true;
            }
        }
        return false;
    }

    private OWLSubClassOfAxiom computeEssentialLeftCounterexample() throws Exception {
        OWLSubClassOfAxiom axiom = counterExample;

        OWLClass oldClass = null;
        OWLClassExpression oldExpression = null;

        lastExpression = axiom.getSubClass();
        lastName = (OWLClass) axiom.getSuperClass();

        //while (!lastName.equals(oldClass) || !lastExpression.equals(oldExpression)) {
            oldClass = lastName;
            oldExpression = lastExpression;

            axiom = learner.decomposeLeft(lastExpression, lastName);
            lastExpression = axiom.getSubClass();
            lastName = (OWLClass) axiom.getSuperClass();

            axiom = learner.branchLeft(lastExpression, lastName);
            lastExpression = axiom.getSubClass();
            lastName = (OWLClass) axiom.getSuperClass();

            axiom = learner.unsaturateLeft(lastExpression, lastName);
            lastExpression = axiom.getSubClass();
            lastName = (OWLClass) axiom.getSuperClass();
        //}

        return axiom;
    }

    private OWLSubClassOfAxiom computeEssentialRightCounterexample() throws Exception {
        int changed = -1;
        OWLSubClassOfAxiom axiom = counterExample;

        OWLClass oldLeft = null;
        OWLClassExpression oldRight = null;
        lastName = (OWLClass) axiom.getSubClass();
        lastExpression = axiom.getSuperClass();

        axiom = learner.decomposeRight(lastName, lastExpression);
        lastName = (OWLClass) axiom.getSubClass();
        lastExpression = axiom.getSuperClass();

        //while (!lastName.equals(oldLeft) || !lastExpression.equals(oldRight)) {
            changed++;
            oldLeft = lastName;
            oldRight = lastExpression;

            axiom = learner.saturateRight(lastName, lastExpression);
            lastName = (OWLClass) axiom.getSubClass();
            lastExpression = axiom.getSuperClass();

            axiom = learner.decomposeRight(lastName, lastExpression);
            lastName = (OWLClass) axiom.getSubClass();
            lastExpression = axiom.getSuperClass();

            axiom = learner.mergeRight(lastName, lastExpression);
            lastName = (OWLClass) axiom.getSubClass();
            lastExpression = axiom.getSuperClass();
        //}

        if (changed > 0) {
            System.out.println("It did change! Count: " + changed);
        }

        return axiom;
    }

    protected void loadTargetOntology(String ontology) throws OWLOntologyCreationException, IOException {
        targetFile = new File(ontology);
        groundTruthOntology = myManager.loadOntologyFromOntologyDocument(targetFile);
        for (OWLAxiom axe : groundTruthOntology.getLogicalAxioms())
            if (axe.isOfType(AxiomType.SUBCLASS_OF) || axe.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
                axiomsT.add(axe);
            }
        parser = new OWLParserImpl(groundTruthOntology);
    }

    void saveTargetOntology() throws OWLOntologyStorageException, IOException {
        OWLDocumentFormat format = myManager.getOntologyFormat(groundTruthOntology);
        ManchesterSyntaxDocumentFormat manSyntaxFormat = new ManchesterSyntaxDocumentFormat();
        if (format.isPrefixOWLDocumentFormat()) {
            manSyntaxFormat.copyPrefixesFrom(format.asPrefixOWLDocumentFormat());
        }

        File newFile = new File(ontologyFolder);
        if (newFile.exists()) {
            newFile.delete();
        }
        // Create ontologies directory if it does not exist
        if (!newFile.getParentFile().exists()) {
            newFile.getParentFile().mkdirs();
        }
        newFile.createNewFile();
        myManager.saveOntology(groundTruthOntology, manSyntaxFormat, IRI.create(newFile.toURI()));
    }

    /**
     * Opt-in via EXACTLEARNER_RESUME (scripts: `resume=true`), off by default.
     * Unset, this method is byte-identical to the original.
     *
     * Set, a job continues from the hypothesis the previous one checkpointed
     * instead of starting from empty, and restoreFromCheckpoint() puts the PAC
     * counter, the sampler stream and the metrics totals back where they were.
     *
     * Why it is not optional for C2. Job 4044683 reached 158 counterexamples in
     * 23.6 h and 4110003 reached 190 in 24 h; both discarded everything at the
     * walltime. Resuming through the query cache alone does not rescue that:
     * replay costs ~4.5 min per counterexample against ~7.9 min for a novel one,
     * so a 24 h job that starts with C counterexamples banked can add only
     * (1440 - 4.5C)/7.9 more, which reaches zero at C = 320 -- and 4110003's
     * own sampling rate puts a finished C2 run at ~381. Without this, jobs
     * repeat each other and the run cannot finish however many it gets.
     *
     * NOT YET VERIFIED ON THE CLUSTER. Try a deliberately short job first and
     * check the RESUMING line's axiom count against the last checkpoint of the
     * run being continued.
     */
    protected void loadHypothesisOntology() throws OWLOntologyCreationException, IOException {
        hypoFile = new File(ontologyFolderH);
        // Cleared first: run() calls this once per ontology/model pair, and a
        // pair with nothing to resume from must not inherit the previous one's
        // sample counters.
        resumedState = RunState.empty();

        if (resumeRequested() && hypoFile.isFile() && hypoFile.length() > 0) {
            hypothesisOntology = myManager.loadOntologyFromOntologyDocument(hypoFile);
            resumedState = RunState.read(runStateFile());
            System.out.println("RESUMING from " + hypoFile.getPath()
                    + " (" + hypothesisOntology.getLogicalAxiomCount() + " logical axioms, "
                    + resumedState + ")");
            return;
        }

        if (hypoFile.exists()) {
            hypoFile.delete();
        }
        hypoFile.createNewFile();

        hypothesisOntology = myManager.loadOntologyFromOntologyDocument(hypoFile);
    }

    /** Env flag for the above. Same spelling convention as the batching flags. */
    protected static boolean resumeRequested() {
        return "true".equals(System.getenv(RESUME_ENV));
    }

    /**
     * Wall-clock stamp for the per-counterexample log lines.
     *
     * Job 4044683's counterexample lines carried no time at all, so a 3.5 MB
     * log could not answer whether the run decelerated -- which is exactly what
     * separates GC thrash from steady reasoning work, and what a whole extra
     * job now has to be spent measuring. One field per line fixes that for
     * every future run.
     *
     * Appended to the end of those lines rather than prefixed, deliberately:
     * the analysis commands in the handover notes anchor on
     * `^Counterexample ...` and `^Checkpointed hypothesis ...`, and a leading
     * timestamp would silently break every one of them.
     */
    protected static String wallClock() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * State a resumed run needs beyond the hypothesis itself.
     *
     * The hypothesis is most of it, but not all: the PAC counter is monotone
     * for the whole run and never reset per equivalence query, and the A-induced
     * sampler's Random is a stream whose position matters. Restoring the first
     * and replaying the second is what makes a resumed run continue the original
     * rather than start a correlated new one.
     */
    protected static final class RunState {
        final int counterExamples;
        final long providedSamples;
        final long samplerDraws;

        // Metrics totals. Carried because Metrics is rebuilt by setup() on every
        // job, so without these a resumed run's statistics file reports only the
        // segment that job happened to run -- a two-job C2 run would report the
        // second job's few thousand membership queries and silently drop the
        // first job's 50,000. The hypothesis resumes either way; this is about
        // the numbers being reportable.
        final int membCount;
        final int equivCount;
        final int largestCounterExample;

        // False for a state file written before the three fields above existed
        // -- job 4110003's is one. Such a checkpoint still resumes exactly; only
        // its query totals restart from zero, and restoreFromCheckpoint() says
        // so out loud rather than letting a short count pass for a measurement.
        final boolean metricsPresent;

        RunState(int counterExamples, long providedSamples, long samplerDraws) {
            this(counterExamples, providedSamples, samplerDraws, 0, 0, 0, false);
        }

        RunState(int counterExamples, long providedSamples, long samplerDraws,
                 int membCount, int equivCount, int largestCounterExample) {
            this(counterExamples, providedSamples, samplerDraws,
                    membCount, equivCount, largestCounterExample, true);
        }

        private RunState(int counterExamples, long providedSamples, long samplerDraws,
                         int membCount, int equivCount, int largestCounterExample,
                         boolean metricsPresent) {
            this.counterExamples = counterExamples;
            this.providedSamples = providedSamples;
            this.samplerDraws = samplerDraws;
            this.membCount = membCount;
            this.equivCount = equivCount;
            this.largestCounterExample = largestCounterExample;
            this.metricsPresent = metricsPresent;
        }

        static RunState empty() {
            return new RunState(0, 0L, 0L);
        }

        static RunState read(File file) {
            if (file == null || !file.isFile()) {
                return empty();
            }
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                Properties props = new Properties();
                props.load(in);
                // Read with defaults rather than rejected when the metrics keys
                // are absent, so a checkpoint from an earlier job still resumes.
                boolean hasMetrics = props.getProperty("membCount") != null;
                return new RunState(
                        Integer.parseInt(props.getProperty("counterExamples", "0")),
                        Long.parseLong(props.getProperty("providedSamples", "0")),
                        Long.parseLong(props.getProperty("samplerDraws", "0")),
                        Integer.parseInt(props.getProperty("membCount", "0")),
                        Integer.parseInt(props.getProperty("equivCount", "0")),
                        Integer.parseInt(props.getProperty("largestCounterExample", "0")),
                        hasMetrics);
            } catch (Exception e) {
                // A hypothesis with no readable state file still resumes, it
                // just restarts the sample stream. Say so rather than failing:
                // the hypothesis is the expensive half.
                System.out.println("Run state unreadable (" + e + "), resuming hypothesis only");
                return empty();
            }
        }

        void write(File file) throws IOException {
            if (file == null) {
                return;
            }
            Properties props = new Properties();
            props.setProperty("counterExamples", Integer.toString(counterExamples));
            props.setProperty("providedSamples", Long.toString(providedSamples));
            props.setProperty("samplerDraws", Long.toString(samplerDraws));
            props.setProperty("membCount", Integer.toString(membCount));
            props.setProperty("equivCount", Integer.toString(equivCount));
            props.setProperty("largestCounterExample", Integer.toString(largestCounterExample));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                props.store(out, "ExactLearner run state, written at each checkpoint");
            }
        }

        @Override
        public String toString() {
            return "counterexamples=" + counterExamples
                    + " providedSamples=" + providedSamples
                    + " samplerDraws=" + samplerDraws
                    + " membCount=" + membCount
                    + " equivCount=" + equivCount;
        }
    }

    /**
     * Supplies the live sampler position at checkpoint time. The base class has
     * no sampler, so it reports zero; LaunchLLMLearnerAInduced overrides it.
     */
    protected long samplerDraws() {
        return 0L;
    }

    private File runStateFile() {
        if (hypoFile == null) {
            return null;
        }
        String base = hypoFile.getName().replaceFirst("\\.owl$", "");
        return new File(hypoFile.getParentFile(), base + "-run-state.properties");
    }

    /**
     * Names this run's copy of the target ontology and its learned hypothesis.
     *
     * The BaseSet tag is what keeps a PACLO dataset's three configurations
     * apart: C1, C2 and C3 are all called expertOntology.owl and differ only by
     * folder, so without it every run silently overwrites the previous one's
     * saved hypothesis. It applies to whichever sampler is running -- the
     * collision is a property of the dataset, not of the arm -- and
     * PacloDataset.outputTag() returns "" for everything else, which leaves the
     * small ontologies named exactly as they always were.
     */
    protected void setUpOntologyFolders(String format, String system, String model, String ontology) {
        String name = Path.of(ontology).getFileName().toString().replace(".owl", "");
        String tag = PacloDataset.outputTag(ontology);
        if (!tag.isEmpty()) {
            name = name + "_" + tag;
        }
        // Tagged as well, though every repeat would write identical content: it is
        // deleted and recreated by saveTargetOntology(), and
        // computeConceptAndRoleNumbers() reads it straight back, so two parallel
        // repeats sharing it can have one read the other's half-written file and
        // silently take the wrong concept and role counts into its statistics.
        String targetName = runTag().isEmpty() ? name : name + "_" + runTag();
        ontologyFolder = "results" + fileSeparator + "ontologies" + fileSeparator + "target_" + targetName + ".owl";
        ontologyFolderH = "results" + fileSeparator + "ontologies" + fileSeparator + infoString(name, model, format, system) + ".owl";
    }

    /**
     * Label separating one repeat of an experiment from another, from the
     * environment. Empty by default, which leaves every output named exactly as
     * it always has been.
     *
     * Repeats exist to put a confidence interval on the sampler's randomness:
     * the same configuration is run under different seeds, and the spread across
     * those runs is the measurement. That only works if they do not collide.
     * Every per-run artefact is named from infoString() -- the hypothesis, its
     * -trajectory/ directory, its -run-state.properties -- so tagging here
     * separates all of them at once, and the target copy is tagged alongside in
     * setUpOntologyFolders() because parallel jobs otherwise delete and rewrite
     * one file underneath each other.
     *
     * Sanitised rather than trusted: this reaches a filename, and Slurm exports
     * the whole submitting environment, so a stray value must not be able to
     * write outside results/.
     */
    public static final String RUN_TAG_ENV = "EXACTLEARNER_RUN_TAG";

    protected String runTag() {
        String raw = System.getenv(RUN_TAG_ENV);
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().replaceAll("[^A-Za-z0-9._-]", "-");
    }

    protected String infoString(String ontology, String model, String format, String system) {
        String systemType = "advanced";
        if (system.trim().equals("Answer with only True or False.")) {
            systemType = "base";
        }
        String name = ontology + "_" + model + "_" + format + "_" + systemType;
        String tag = runTag();
        return tag.isEmpty() ? name : name + "_" + tag;
    }

    protected void computeConceptAndRoleNumbers() throws IOException {
        ArrayList<String> concepts = myMetrics.getSuggestionNames("concept", new File(ontologyFolder));
        ArrayList<String> roles = myMetrics.getSuggestionNames("role", new File(ontologyFolder));

        this.conceptNumber = concepts.size();
        this.roleNumber = roles.size();
    }

    /**
     * Writes the hypothesis learned SO FAR to hypoFile, mid-run.
     *
     * Without this the hypothesis exists only in memory until saveOWLFile()
     * runs after the learning loop returns, so a job killed at walltime loses
     * every counterexample it found — a measured 12-hour run produced 22
     * counterexamples and saved none of them. Each counterexample costs on the
     * order of half an hour of LLM queries, while this serialisation of a
     * few dozen axioms costs milliseconds, so it is worth doing on every one.
     *
     * Deliberately does NOT call addLabelsHypothesisOntology(): that mutates
     * hypothesisOntology by adding annotation axioms, and a checkpoint must not
     * be able to change the state of the run it is recording. Labels are still
     * added by the final saveOWLFile(), which overwrites this file on a clean
     * finish. A checkpoint left behind by a killed job is therefore
     * label-free but logically complete.
     *
     * Never throws: losing a checkpoint is bad, but failing a run that would
     * otherwise have completed is worse.
     */
    /**
     * Sibling directory holding one hypothesis per counterexample, named
     * <hypothesis>/0001.owl and so on.
     *
     * Zero-padded so the shell sorts them in the order they were found. Returns
     * null on any failure, which the caller treats as "skip the trajectory copy"
     * -- the checkpoint that the run itself depends on has already been written
     * by then, and must not be put at risk by this.
     */
    private File trajectoryFile(int counterExampleNumber) {
        try {
            String base = hypoFile.getName().replaceFirst("\\.owl$", "");
            File dir = new File(hypoFile.getParentFile(), base + "-trajectory");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            return new File(dir, String.format("%04d.owl", counterExampleNumber));
        } catch (Throwable t) {
            return null;
        }
    }

    protected void checkpointHypothesis(int counterExampleNumber) {
        if (hypoFile == null || hypothesisOntology == null) {
            return;
        }
        try {
            OWLDocumentFormat format = myManager.getOntologyFormat(hypothesisOntology);
            ManchesterSyntaxDocumentFormat manSyntaxFormat = new ManchesterSyntaxDocumentFormat();
            if (format != null && format.isPrefixOWLDocumentFormat()) {
                manSyntaxFormat.clear();
            }
            myManager.saveOntology(hypothesisOntology, manSyntaxFormat, IRI.create(hypoFile.toURI()));

            // Also keep a numbered copy. hypoFile is what the rest of the run
            // reads, so it has to keep being overwritten; but overwriting was
            // all job 4022395 did, and 120 counterexamples collapsed into one
            // final file with no way to see how the hypothesis got there.
            // Convergence behaviour is the open question, and it can only be
            // read off the sequence.
            File trajectory = trajectoryFile(counterExampleNumber);
            if (trajectory != null) {
                myManager.saveOntology(hypothesisOntology, manSyntaxFormat, IRI.create(trajectory.toURI()));
            }

            // Written after the hypothesis, never before: a state file ahead of
            // the hypothesis it describes would make a resumed run skip samples
            // for counterexamples the hypothesis does not actually contain.
            // Behind is harmless -- those samples get examined again, find
            // nothing, and cost only local reasoner time.
            RunState state = new RunState(counterExampleNumber, providedSamples, samplerDraws(),
                    myMetrics.getMembCount(), myMetrics.getEquivCount(),
                    myMetrics.getSizeOfLargestCounterExample());
            state.write(runStateFile());

            System.out.println("Checkpointed hypothesis after counterexample "
                    + counterExampleNumber + " -> " + hypoFile.getPath()
                    + " (" + hypothesisOntology.getLogicalAxiomCount() + " logical axioms, "
                    + state + ") wall=" + wallClock());
        } catch (Throwable t) {
            System.out.println("Hypothesis checkpoint after counterexample "
                    + counterExampleNumber + " failed, continuing: " + t);
        }
    }

    protected void saveOWLFile(OWLOntology ontology, File file) throws Exception {
        //learner.minimiseHypothesis(elQueryEngineForH, hypothesisOntology);
        addLabelsHypothesisOntology();
        OWLDocumentFormat format = myManager.getOntologyFormat(ontology);
        ManchesterSyntaxDocumentFormat manSyntaxFormat = new ManchesterSyntaxDocumentFormat();
        if (format.isPrefixOWLDocumentFormat()) {
            // need to remove prefixes
            manSyntaxFormat.clear();
        }
        myManager.saveOntology(ontology, manSyntaxFormat, IRI.create(file.toURI()));
    }

    protected void addLabelsHypothesisOntology() {
        Set<IRI> iris = hypothesisOntology.getClassesInSignature().stream().map(OWLClass::getIRI).collect(Collectors.toSet());
        iris.addAll(hypothesisOntology.getObjectPropertiesInSignature().stream().map(OWLObjectProperty::getIRI).collect(Collectors.toSet()));

        for (IRI iri : iris) {
            for (OWLAnnotationAssertionAxiom a : groundTruthOntology.getAnnotationAssertionAxioms(iri)) {
                if (a != null && a.getProperty().isLabel() && a.getValue() instanceof OWLLiteral val) {
                    myManager.addAxiom(hypothesisOntology, a);
                }
            }
        }
    }

    protected OWLSubClassOfAxiom getCounterExample(Pac pac) throws Exception {
        while (pac.hasBudgetLeft()) {
            // Counts against whichever budget is in force: the run-long pot
            // under GLOBAL, this equivalence query's own under PER_ROUND.
            System.out.println("PAC Training sample: " + (pac.getBudgetUsed() + 1) + " out of " + pac.getNumberOfSamples());
            // Get the last counterexample
            OWLSubClassOfAxiom selectedAxiom = pac.getRandomStatement();

            if (!elQueryEngineForH.entailed(selectedAxiom) && llmQueryEngineForT.entailed(selectedAxiom)) {
                return getCounterExampleSubClassOf(selectedAxiom);
            }
            
        }
        return null;
    }

    protected OWLSubClassOfAxiom getCounterExampleSubClassOf(OWLSubClassOfAxiom counterexample) throws Exception {
        OWLSubClassOfAxiom newCounterexampleAxiom;
        OWLClassExpression left = counterexample.getSubClass();
        OWLClassExpression right = counterexample.getSuperClass();
        double p = 0;

        newCounterexampleAxiom = oracle.mergeLeft(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.saturateLeft(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.branchRight(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.composeLeft(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.composeRight(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.unsaturateRight(left, right, p);

        return newCounterexampleAxiom;
    }
}
