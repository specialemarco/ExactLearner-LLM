package org.sampler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * Both of Baris Sertkaya's ABox-induced samplers from
 * https://github.com/sertkaya/paclo (src/main/java/ontology/learning/sampler/),
 * in one class, selected by the Weighting enum below:
 *
 *   WEIGHTED    WeightedABoxInducedSubsumptionSampler
 *   UNWEIGHTED  ABoxInducedSubsumptionSampler
 *
 * The upstream sources were re-read on 2026-09-07 when UNWEIGHTED was added, and
 * the Weighting javadoc records what was and was not carried over. Before then
 * this file held only the weighted one, and its header described the plain
 * sampler from memory of an earlier port; every claim there is now either
 * confirmed against upstream or corrected in the enum's javadoc.
 *
 * Key correctness points preserved from the originals:
 * - instanceCounts is a Map keyed by concept identity, so the conclusion weight
 *   applied to a concept is that concept's. Upstream's WEIGHTED class does the
 *   same; its UNWEIGHTED class does not, and that misalignment is deliberately
 *   not reproduced -- see the Weighting javadoc.
 * - Under WEIGHTED, samplePremise() selects individuals with probability
 *   proportional to 2^|C(a,K0)| (Boley et al. two-step method), using BigInteger
 *   cumulative weights to avoid overflow, over individuals carrying at least one
 *   base-set type. Sec. 4 of Obiedkov & Sertkaya (2025).
 * - Under UNWEIGHTED, it selects uniformly over EVERY individual in the
 *   signature, untyped ones included; those yield an empty premise, i.e.
 *   owl:Thing on the left. That is the plain sampler's behaviour.
 *
 * REPRODUCIBILITY
 * ---------------
 * Every draw comes from the seeded Random held here, never from
 * ThreadLocalRandom, so two runs with the same seed sample the same sequence
 * of axioms. A seed alone is not sufficient: the weight arrays are indexed
 * positionally, so the ENUMERATION ORDER of the base set and of the
 * individuals has to be pinned as well, or the same random number would select
 * a different individual from one JVM to the next. That is why the base set is
 * sorted once into orderedBaseSet (OWLObject is Comparable) and every
 * subsequent traversal — of concepts, of instances, of a premise — goes
 * through an order-preserving collection. Reverting any of those to a
 * HashSet/HashMap silently reintroduces run-to-run drift that a fixed seed
 * will not protect you from.
 *
 * The probabilities themselves are untouched: the same weights are computed
 * over the same elements, only enumerated in a defined order.
 */
public class ABoxInducedSubsumptionSampler {

    /**
     * Which of paclo's two ABox-induced samplers this instance is. Checked
     * against https://github.com/sertkaya/paclo/tree/main on 2026-09-07, in
     * src/main/java/ontology/learning/sampler/.
     *
     * WEIGHTED is WeightedABoxInducedSubsumptionSampler. UNWEIGHTED is the plain
     * ABoxInducedSubsumptionSampler. They differ in samplePremise(), in two ways
     * that go together and are NOT separable -- both are one line of upstream:
     *
     *   WEIGHTED    instanceNames, i.e. individuals carrying at least one
     *               base-set type, drawn with probability proportional to
     *               2^|C(a,K0)| (Boley et al. two-step method).
     *   UNWEIGHTED  every individual in the ontology's signature, drawn
     *               uniformly. Upstream's `individuals` array is
     *               getIndividualsInSignature(), and its premise loop is
     *               guarded by individualTypes.containsKey(ind) -- so drawing
     *               an untyped individual yields the EMPTY premise, i.e.
     *               owl:Thing on the left. That is not a defect to be fixed; it
     *               is what the plain sampler does, and the reason the weighted
     *               one exists.
     *
     * So the two arms differ in the population as well as the weights, because
     * upstream does. An earlier version of this enum restricted UNWEIGHTED to
     * typed individuals to keep the comparison single-axis; that was this file's
     * invention, not paclo's, and it is gone.
     *
     * NOT reproduced from the plain sampler: its sampleConclusion() builds
     * `types` from a HashSet difference and then indexes noninstanceCounts by
     * position in THAT array, while the counts are indexed by position in
     * baseConcepts -- so the weight applied to a concept is some other
     * concept's. Both upstream classes compute the same intended quantity
     * (|K0| minus the concept's instance count), and the weighted one keys it
     * correctly, so this is a bug in the plain class rather than a property of
     * the arm. Both modes here use the correctly keyed version. Reproducing the
     * misalignment would make the two arms differ in a second, accidental way.
     *
     * Also NOT ported: upstream's `uniformConclusions` constructor flag, which
     * both classes carry and which replaces the rarity weighting with a uniform
     * pick. It is a third axis, and nothing here asks for it yet.
     */
    public enum Weighting { WEIGHTED, UNWEIGHTED }

    /**
     * Used by the constructor that does not take a seed. Fixed rather than
     * time-based precisely so that forgetting to pass a seed still yields a
     * reproducible run.
     */
    public static final long DEFAULT_SEED = 0L;

    private final Set<OWLClassExpression> baseSet;
    // The base set in a fixed order. Every positional traversal uses this;
    // baseSet itself is kept only for membership tests and its size.
    private final List<OWLClassExpression> orderedBaseSet;
    private final OWLDataFactory factory;
    private final Random random;
    private final Weighting weighting;

    // Key: concept expression from the base set, Value: number of its instances in the current reasoner
    private final Map<OWLClassExpression, Integer> instanceCounts = new LinkedHashMap<>();
    // Key: individual, Value: base-set concepts of which it is an instance
    private Map<OWLNamedIndividual, ArrayList<OWLClassExpression>> instanceTypes;

    private OWLNamedIndividual[] instanceNames;
    // Every individual in the signature, in a fixed order: the population
    // UNWEIGHTED draws from, which is upstream's `individuals` array. Set in the
    // constructor and never refreshed, exactly as upstream sets it there and
    // update_sampler leaves it alone -- and for the same reason numberOfInstances
    // is frozen: it is the instance space K0.
    private final OWLNamedIndividual[] allIndividuals;
    private BigInteger[] instanceWeights;
    private BigInteger cumulativeInstanceWeight = BigInteger.ZERO;
    private long numberOfInstances;

    /**
     * How many times sample() has been called. Each call consumes a variable
     * number of draws from `random` -- samplePremise() rejects and retries --
     * so this count, not a number of nextInt() calls, is the only handle on
     * where the stream is. A resumed run replays exactly this many samples to
     * put `random` back in the state an uninterrupted run would have reached.
     */
    private long draws = 0L;

    public ABoxInducedSubsumptionSampler(Set<OWLClassExpression> baseSet, OWLReasoner reasoner, OWLDataFactory factory) {
        this(baseSet, reasoner, factory, DEFAULT_SEED);
    }

    public ABoxInducedSubsumptionSampler(Set<OWLClassExpression> baseSet, OWLReasoner reasoner,
                                         OWLDataFactory factory, long seed) {
        this(baseSet, reasoner, factory, seed, Weighting.WEIGHTED);
    }

    public ABoxInducedSubsumptionSampler(Set<OWLClassExpression> baseSet, OWLReasoner reasoner,
                                         OWLDataFactory factory, long seed, Weighting weighting) {
        this.weighting = weighting;
        this.baseSet = baseSet;
        this.orderedBaseSet = new ArrayList<>(baseSet);
        // OWLObject implements Comparable, so this is a total order that does
        // not depend on hash codes or on insertion order upstream.
        Collections.sort(this.orderedBaseSet);
        this.factory = factory;
        this.random = new Random(seed);
        // Set here and never again, deliberately. It is the size of the instance
        // space K0, the constant denominator of the conclusion weighting, and it
        // is frozen for the same reason instanceCounts is frozen on a
        // premise-only refresh: total and parts stay consistent because NEITHER
        // moves. Refreshing it in update_sampler would be actively wrong for the
        // one refresh that is planned -- update_sampler(hypothesisReasoner,
        // false), where the hypothesis ontology is TBox-only, so the count would
        // come back 0 and every conclusion weight would go negative.
        List<OWLNamedIndividual> signature =
                new ArrayList<>(reasoner.getRootOntology().getIndividualsInSignature());
        // Upstream leaves this in the reasoner's hash order. Sorting is this
        // file's reproducibility requirement and does not touch the
        // distribution: the draw over it is uniform.
        Collections.sort(signature);
        this.allIndividuals = signature.toArray(new OWLNamedIndividual[0]);
        this.numberOfInstances = allIndividuals.length;
        // Initial setup refreshes both premise (lhs) and conclusion (rhs)
        // weights -- mirrors paclo's constructor call update_sampler(reasoner, true).
        update_sampler(reasoner, true);
    }

    /** Refreshes both premise and conclusion weights. */
    public void update_sampler(OWLReasoner reasoner) {
        update_sampler(reasoner, true);
    }

    /**
     * Ported from paclo's commit "Fixing the distribution of the right handside"
     * (WeightedABoxInducedSubsumptionSampler / formerly WeightedSubsumptionSampler).
     * The updateConclusion flag lets callers refresh only the premise-side
     * instance types on retry (after a failed sampling round), leaving the
     * conclusion (rhs) weights -- instanceCounts, used for rarity-based
     * weighting in sampleConclusion() -- stable. paclo's own retry call in
     * LearningFrameworkSubsumption.getCounterExample() passes false here.
     *
     * NOTE: instanceCounts is deliberately NOT cleared when updateConclusion is
     * false, so it keeps the counts from the last refresh that did update it.
     * Every base-set concept is written on the constructor's pass, so every key
     * sampleConclusion() looks up is always present.
     */
    public void update_sampler(OWLReasoner reasoner, boolean updateConclusion) {
        instanceTypes = new LinkedHashMap<>();

        for (OWLClassExpression ce : orderedBaseSet) {
            Set<OWLNamedIndividual> instances = reasoner.getInstances(ce).getFlattened();
            if (updateConclusion) {
                instanceCounts.put(ce, instances.size());
            }
            // getFlattened() returns a hash-ordered set; sorting it fixes the
            // insertion order of instanceTypes, which becomes the index order
            // of instanceNames/instanceWeights below.
            List<OWLNamedIndividual> orderedInstances = new ArrayList<>(instances);
            Collections.sort(orderedInstances);
            for (OWLNamedIndividual ind : orderedInstances) {
                instanceTypes.computeIfAbsent(ind, k -> new ArrayList<>(Collections.singletonList(ce)));
                if (!instanceTypes.get(ind).contains(ce)) {
                    instanceTypes.get(ind).add(ce);
                }
            }
        }

        instanceNames = new OWLNamedIndividual[instanceTypes.size()];
        instanceWeights = new BigInteger[instanceTypes.size()];

        int i = 0;
        cumulativeInstanceWeight = BigInteger.ZERO;
        for (Map.Entry<OWLNamedIndividual, ArrayList<OWLClassExpression>> entry : instanceTypes.entrySet()) {
            instanceNames[i] = entry.getKey();
            cumulativeInstanceWeight = cumulativeInstanceWeight.add(BigInteger.ONE.shiftLeft(entry.getValue().size()));
            instanceWeights[i++] = cumulativeInstanceWeight;
        }
    }

    public OWLSubClassOfAxiom sample() {
        draws++;
        Set<OWLClassExpression> premise = samplePremise();
        OWLClassExpression conclusion = sampleConclusion(premise);
        OWLClassExpression lhs = premise.isEmpty()
            ? factory.getOWLThing()
            : premise.size() == 1
                ? premise.iterator().next()
                : factory.getOWLObjectIntersectionOf(premise);
        return factory.getOWLSubClassOfAxiom(lhs, conclusion);
    }

    private Set<OWLClassExpression> samplePremise() {
        // LinkedHashSet, not HashSet: the premise is enumerated when it is
        // turned into an intersection, so its order must not vary between runs.
        Set<OWLClassExpression> premise = new LinkedHashSet<>();
        if (premisePopulationSize() == 0) {
            return premise; // nothing to draw from: empty premise, i.e. Top
        }
        do {
            premise.clear();
            // Where the two arms part, and the only place they do. See the
            // Weighting javadoc: the population differs as well as the weights,
            // because it does upstream.
            //
            // The two modes consume different amounts of `random` per sample, so
            // their streams diverge from the first draw even under one seed --
            // which is why fastForwardTo() replays through the mode it was built
            // with, and why a resumed run must not switch modes.
            OWLNamedIndividual ind;
            if (weighting == Weighting.WEIGHTED) {
                ind = instanceNames[randomIndexBig(instanceWeights, cumulativeInstanceWeight)];
            } else {
                ind = allIndividuals[random.nextInt(allIndividuals.length)];
            }
            // null for an individual carrying no base-set type. Reachable only
            // under UNWEIGHTED, and the empty premise it leaves -- owl:Thing on
            // the left -- is the plain sampler's behaviour, not a hole in it:
            // upstream guards the same loop with individualTypes.containsKey().
            List<OWLClassExpression> types = instanceTypes.get(ind);
            if (types != null) {
                for (OWLClassExpression expr : types) {
                    if (random.nextBoolean()) {
                        premise.add(expr);
                    }
                }
            }
            // NOT a cosmetic filter on trivial axioms -- this is what makes
            // sampleConclusion() total, so do not simplify it away.
            //
            // A premise is always a subset of the base set: its elements come
            // from instanceTypes, which update_sampler builds out of
            // orderedBaseSet and nothing else. So equal sizes means equal sets,
            // and retrying here is what guarantees the premise is a STRICT
            // subset -- which is exactly the condition under which
            // sampleConclusion()'s candidate list is non-empty. Drop this and
            // that list can come back empty, and the failure surfaces as
            // IllegalArgumentException from random.nextInt(0), several frames
            // away from the cause.
            //
            // It cannot spin: escaping needs an individual typed with the whole
            // base set AND nextBoolean() true for every one of those types, so
            // the retry probability is at most 2^-|baseSet| per iteration. Under
            // UNWEIGHTED an untyped individual leaves the premise empty, which
            // exits immediately for any non-empty base set.
        } while (premise.size() == baseSet.size());
        return premise;
    }

    /**
     * Picks the right-hand side from the base-set concepts the premise does not
     * already contain, weighted towards the rare ones.
     *
     * Relies on samplePremise() returning a strict subset: with `remaining`
     * empty, randomIndexLong() would reach random.nextInt(0) and throw. See the
     * note on that method's retry loop.
     */
    private OWLClassExpression sampleConclusion(Set<OWLClassExpression> premise) {
        // Built from orderedBaseSet rather than from a HashSet difference, so
        // that index i means the same concept on every run.
        List<OWLClassExpression> remaining = new ArrayList<>(orderedBaseSet.size());
        for (OWLClassExpression ce : orderedBaseSet) {
            if (!premise.contains(ce)) {
                remaining.add(ce);
            }
        }
        OWLClassExpression[] types = remaining.toArray(new OWLClassExpression[0]);

        long[] weights = new long[types.length];
        long total = 0;
        for (int i = 0; i < types.length; ++i) {
            total += (numberOfInstances - instanceCounts.get(types[i]));
            weights[i] = total;
        }
        return types[randomIndexLong(weights, total)];
    }

    private int randomIndexLong(long[] weights, long total) {
        if (total <= 0) {
            return random.nextInt(weights.length);
        }
        // Random implements RandomGenerator as of Java 17, so this is the same
        // unbiased bounded draw ThreadLocalRandom.nextLong(total) performed.
        long r = random.nextLong(total);
        int index = Arrays.binarySearch(weights, r);
        if (index < 0) index = -(index + 1);
        if (index == weights.length) index--;
        while (index > 0 && weights[index] == weights[index - 1]) index--;
        return index;
    }

    private int randomIndexBig(BigInteger[] weights, BigInteger total) {
        BigInteger r;
        do {
            r = new BigInteger(total.bitLength(), random);
        } while (r.compareTo(total) >= 0);
        int index = Arrays.binarySearch(weights, r);
        if (index < 0) index = -(index + 1);
        if (index == weights.length) index--;
        while (index > 0 && weights[index].equals(weights[index - 1])) index--;
        return index;
    }

    /**
     * Individuals carrying at least one base-set type. Under WEIGHTED this is
     * the population samplePremise() draws from; under UNWEIGHTED it is the
     * subset of the population that can produce a non-empty premise. Zero means
     * every premise degenerates to owl:Thing in BOTH modes; see hasIndividuals().
     */
    public int typedIndividualCount() {
        return instanceNames == null ? 0 : instanceNames.length;
    }

    /** How many individuals this mode's premise draw actually chooses among. */
    public int premisePopulationSize() {
        return weighting == Weighting.WEIGHTED
                ? typedIndividualCount()
                : allIndividuals.length;
    }

    /**
     * The denominator of the conclusion weighting: the size of the instance space
     * as it was at construction. Constant for the sampler's lifetime -- see the
     * note on the field's assignment before changing that.
     */
    public long instanceUniverseSize() {
        return numberOfInstances;
    }

    /** Which arm this sampler is; reported in the run log. */
    public Weighting weighting() {
        return weighting;
    }

    public boolean hasIndividuals() {
        return typedIndividualCount() > 0;
    }

    /** How many samples have been drawn. See the `draws` field. */
    public long getDraws() {
        return draws;
    }

    /**
     * Replays `target` samples and throws them away, so that the next sample()
     * is the one an uninterrupted run would have produced. Purely local: no
     * reasoner call, no model call, no cache lookup. A no-op if the stream is
     * already at or past `target`.
     */
    public void fastForwardTo(long target) {
        while (draws < target) {
            sample();
        }
    }
}
