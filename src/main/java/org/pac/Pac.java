package org.pac;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.util.*;

public class Pac {

    private List<OWLClass> classes;
    private List<OWLObjectProperty> objectProperties;
    private Double epsilon;
    private Double delta;
    private Long numberOfSamples;
    private int seed = 0;
    public Double numberOfAxioms;
    private OWLDataFactory factory;

    private Long providedSamples = 0L;

    // ---- Sampling budget mode -------------------------------------------
    //
    // GLOBAL (the default, and what every experiment so far has run):
    // numberOfSamples is the total number of candidates the WHOLE run may
    // examine. providedSamples is never reset, so the run ends the first time
    // the pot is empty -- "No counterexample found, closing..." means the
    // budget ran out, not that a clean sweep happened.
    //
    // PER_ROUND: every equivalence query gets a fresh full Occam bound, which
    // is the standard reading of the (epsilon, delta) guarantee -- q i.i.d.
    // candidates drawn against a FIXED hypothesis, none of them a
    // counterexample. Termination then means a full fresh q draws all failed
    // against the current hypothesis, which is a strictly stronger claim and
    // is not guaranteed to be reachable. See MEETING-2026-08-18.md section 8.
    //
    // providedSamples keeps counting globally in both modes: it is what the
    // resume checkpoint stores (LaunchLearner.RunState) and what the
    // statistics divide by.
    public enum BudgetMode { GLOBAL, PER_ROUND }

    /** Selects the mode. Unset or unrecognised means GLOBAL. */
    public static final String BUDGET_MODE_ENV = "EXACTLEARNER_BUDGET_MODE";

    private BudgetMode budgetMode = BudgetMode.GLOBAL;

    // Candidates examined since the last startRound(). Only consulted under
    // PER_ROUND; kept up to date in both modes so the two stay comparable in
    // the logs.
    private long roundSamples = 0L;

    // Equivalence-query index, 1-based after the first startRound(). Unused by
    // the two modes above; it is what a growing per-round budget would need
    // (see callsToSamplingOracle below), so startRound() maintains it now
    // rather than making that a second change later.
    private int round = 0;

    public static BudgetMode budgetModeFromEnv() {
        String raw = System.getenv(BUDGET_MODE_ENV);
        if (raw == null || raw.isBlank()) {
            return BudgetMode.GLOBAL;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (value) {
            case "global" -> BudgetMode.GLOBAL;
            case "per-round", "perround", "round" -> BudgetMode.PER_ROUND;
            default -> {
                System.out.println("Ignoring " + BUDGET_MODE_ENV + "=" + raw
                        + " (expected \"global\" or \"per-round\"), using global");
                yield BudgetMode.GLOBAL;
            }
        };
    }

    public void setBudgetMode(BudgetMode mode) {
        this.budgetMode = mode == null ? BudgetMode.GLOBAL : mode;
    }

    public BudgetMode getBudgetMode() {
        return budgetMode;
    }

    /**
     * Opens a new equivalence-query round. Under PER_ROUND this hands back a
     * full budget; under GLOBAL it only advances the round index, leaving the
     * one shared pot alone.
     */
    public void startRound() {
        round++;
        roundSamples = 0L;
    }

    public int getRound() {
        return round;
    }

    public long getRoundSamples() {
        return roundSamples;
    }

    /** Candidates examined against the budget currently in force. */
    public long getBudgetUsed() {
        return budgetMode == BudgetMode.PER_ROUND ? roundSamples : providedSamples;
    }

    /** Whether one more candidate may be examined. */
    public boolean hasBudgetLeft() {
        return getBudgetUsed() < numberOfSamples;
    }

    /** How many more may be examined before the budget in force runs out. */
    public long getRemainingSamples() {
        return Math.max(numberOfSamples - getBudgetUsed(), 0L);
    }

    public Pac(Set<OWLClass> classes, Set<OWLObjectProperty> objectProperties, Double epsilon, Double delta, Integer hypothesisSize, int seed) {
        // Alphabetically sort classes and then shuffle them using the seed to ensure reproducibility
        this.classes = new ArrayList<>(classes);
        this.objectProperties = new ArrayList<>(objectProperties);
        // getRandomStatement() draws index triples and rejects the ones that do
        // not form a statement. Two signatures make EVERY triple invalid, so that
        // rejection loop never exits: with no classes, nothing can fill the C
        // slot all three statement shapes need; with one class and no object
        // properties, the only shape left needs C different from both A and B
        // when there is a single class to go round. Neither throws or returns --
        // they spin at 100% CPU producing nothing -- so they are refused here,
        // where the numbers that caused it are still in hand.
        if (this.classes.isEmpty()
                || (this.classes.size() == 1 && this.objectProperties.isEmpty())) {
            throw new IllegalArgumentException(
                    "Cannot sample statements from " + this.classes.size() + " class(es) and "
                    + this.objectProperties.size() + " object propert(ies): no statement shape can"
                    + " be built from that signature, and getRandomStatement() would never return."
                    + " Needs at least 2 classes, or 1 class and 1 object property.");
        }
        this.epsilon = epsilon;
        this.delta = delta;
        double x = computeInstanceSpaceSize();
        this.numberOfSamples = Math.round((hypothesisSize*Math.log(x) - Math.log(delta)) / epsilon);
        this.numberOfAxioms = x;
        System.out.println("PAC-SIZE-DEBUG: classes.size()=" + classes.size()
            + " objectProperties.size()=" + objectProperties.size()
            + " hypothesisSize=" + hypothesisSize
            + " epsilon=" + epsilon + " delta=" + delta
            + " x(instanceSpaceSize)=" + x
            + " numberOfSamples=" + this.numberOfSamples);
        this.seed = seed;
        this.factory = OWLManager.getOWLDataFactory();

        Collections.sort(this.classes);
        Collections.sort(this.objectProperties);
        Collections.shuffle(this.classes, new Random(seed));
        Collections.shuffle(this.objectProperties, new Random(seed));
    }

    public double getEpsilon() {
        return epsilon;
    }

    public double getDelta() {
        return delta;
    }

    public long getNumberOfSamples() {
        return numberOfSamples;
    }

    public double getNumberOfProvidedSamples() {
        return providedSamples;
    }

    /**
     * Draws one uniformly random statement and spends one unit of the budget.
     *
     * NEVER RETURNS NULL, and there is no exhaustion state to reach. The method
     * keeps no record of what it has drawn -- `rand` is re-seeded from
     * seed + providedSamples on every call -- so it samples WITH REPLACEMENT from
     * a fixed space of computeInstanceSpaceSize() statements. Drawing the whole
     * budget removes nothing from that space; repeats are expected (a few dozen
     * over 19,304 draws on OWL2Bench) and simply hit the query cache. Callers
     * must not treat a return value as a completion signal: the budget is the
     * only thing that ends the loop.
     *
     * The rejection loop below therefore only ever spins on invalid index
     * triples, never on a shortage of statements. The two signatures for which
     * no triple is ever valid -- no classes, or one class and no object
     * properties -- are refused by the constructor, so the loop is guaranteed to
     * terminate for any Pac that exists.
     */
    public OWLSubClassOfAxiom getRandomStatement() {
        /*
          Pick up a random statement from the list of all possible statements with uniform probability.
          Use the seed and the current number of provided samples to ensure reproducibility.
          There are three types of statements:
          1. (A ∩ B) ⊑ C; (B can be equal to A, but C must be different)
          2. B ⊑ ∃R.A
          3. ∃R.A ⊑ B
          Generate 3 indices each for class and/or object property and use them to create a statement.
          If the indices generated an invalid statement, regenerate them until a valid statement is created.
          Increment the number of provided samples.
          Return the generated statement
         */
        Random rand = new Random(seed + providedSamples);
        int maxRange = classes.size() + objectProperties.size();
        int index1, index2, index3;
        OWLSubClassOfAxiom statement = null;
        while (statement == null) {
            index1 = rand.nextInt(maxRange);
            index2 = rand.nextInt(maxRange);
            index3 = rand.nextInt(maxRange);
            if (index1 < classes.size() && index2 < classes.size() && index3 < classes.size()) {
                if (index3 == index1 || index3 == index2) {
                    continue; // C must be different from A and B
                }
                statement = factory.getOWLSubClassOfAxiom(factory.getOWLObjectIntersectionOf(classes.get(index1), classes.get(index2)), classes.get(index3));
            } else if (index1 < classes.size() && index2 >= classes.size() && index3 < classes.size()) {
                statement = factory.getOWLSubClassOfAxiom(classes.get(index1), factory.getOWLObjectSomeValuesFrom(objectProperties.get(index2-classes.size()), classes.get(index3)));
            } else if (index1 >= classes.size() && index2 < classes.size() && index3 < classes.size()) {
                statement = factory.getOWLSubClassOfAxiom(factory.getOWLObjectSomeValuesFrom(objectProperties.get(index1 - classes.size()), classes.get(index2)), classes.get(index3));
            }
        }
        providedSamples++;
        roundSamples++;
        return statement;
    }

    // NEW METHOD (A-induced integration): exposes a way to advance the
    // providedSamples counter from outside this class. Ana's original design
    // only ever advances this counter internally, inside getRandomStatement()
    // (see "providedSamples++;" a few lines above) — every call to that method
    // draws one uniform-random sample AND consumes one unit of the PAC budget
    // in a single step. LaunchLLMLearnerAInduced.getCounterExample() bypasses
    // getRandomStatement() entirely (it draws candidates from
    // ABoxInducedSubsumptionSampler.sample() instead), so without this method
    // the PAC sample budget (numberOfSamples) would never be consumed and the
    // A-induced sampling loop would never terminate on its own.
    public void incrementProvidedSamples() {
        providedSamples++;
        roundSamples++;
    }

    // NEW METHOD (resume): restores the counter to where an interrupted run
    // left it. providedSamples is monotone for the whole run -- nothing resets
    // it per equivalence query -- so it is the entire PAC-side state a resumed
    // job has to carry over. Deliberately never decreases: a state file that
    // somehow lags the hypothesis must not hand budget back and let the same
    // samples be examined twice.
    public void restoreProvidedSamples(long samples) {
        if (samples > providedSamples) {
            providedSamples = samples;
        }
    }

    /**
     * Ported from paclo's LearningFrameworkSubsumption.callsToSamplingOracle().
     * Per-round sampling budget from Eq. 1 of Obiedkov & Sertkaya (2025):
     * q_i(epsilon,delta) = ceil(log_{1-epsilon}(delta/(i*(i+1))))
     * Grows with i (the round/equivalence-query index), unlike the flat
     * Occam-bound numberOfSamples computed once in the constructor above.
     *
     * CURRENTLY UNREFERENCED -- BudgetMode has no PER_ROUND_GROWING value. It
     * would be one enum constant plus one branch in hasBudgetLeft(), reading
     * callsToSamplingOracle(getRound()) instead of numberOfSamples, now that
     * startRound() maintains the round index.
     *
     * The A-induced loop was switched to this budget on
     * branch paclo-stopping-condition-experiment and switched back to the flat
     * Occam bound on debug-verify-v2, because the growing budget starts far too
     * small (q_1 = 14) to find a counterexample at the observed hit rate. Kept
     * here as the ported reference implementation of Eq. 1 for the stopping-
     * condition work; delete it if that line of work is abandoned.
     */
    public int callsToSamplingOracle(int i) {
        return (int) Math.ceil(Math.log(delta / (i * (i + 1))) / Math.log(1 - epsilon));
    }

    public double computeInstanceSpaceSize() {
        var cn = this.classes.size();
        var rn = this.objectProperties.size();
        return cn*cn*(cn-1) + 2*(cn*cn*rn);
    }

}
