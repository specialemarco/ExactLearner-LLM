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

    public Pac(Set<OWLClass> classes, Set<OWLObjectProperty> objectProperties, Double epsilon, Double delta, Integer hypothesisSize, int seed) {
        // Alphabetically sort classes and then shuffle them using the seed to ensure reproducibility
        this.classes = new ArrayList<>(classes);
        this.objectProperties = new ArrayList<>(objectProperties);
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
     * CURRENTLY UNREFERENCED. The A-induced loop was switched to this budget on
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
