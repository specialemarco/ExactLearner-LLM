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
 * Ported from Baris Sertkaya's WeightedABoxInducedSubsumptionSampler
 * (https://github.com/sertkaya/paclo), NOT the plain ABoxInducedSubsumptionSampler
 * previously ported here, which had a positional weight-index misalignment in
 * sampleConclusion() and sampled premise individuals uniformly instead of
 * proportionally to 2^|C(a,K0)| as required by Sec. 4 of Obiedkov & Sertkaya (2025).
 *
 * Key correctness points preserved from the original:
 * - instanceCounts is a Map keyed by concept identity (no positional misalignment).
 * - samplePremise() selects individuals with probability proportional to
 *   2^|C(a,K0)| (Boley et al. two-step method), using BigInteger cumulative
 *   weights to avoid overflow.
 * - Only individuals with at least one type in the base set are eligible for
 *   premise sampling (untyped individuals would always yield an empty/Top
 *   premise and waste samples) — this is a behavioural difference from the
 *   previous version, which sampled uniformly over ALL individuals in the
 *   ontology signature.
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

    // Key: concept expression from the base set, Value: number of its instances in the current reasoner
    private final Map<OWLClassExpression, Integer> instanceCounts = new LinkedHashMap<>();
    // Key: individual, Value: base-set concepts of which it is an instance
    private Map<OWLNamedIndividual, ArrayList<OWLClassExpression>> instanceTypes;

    private OWLNamedIndividual[] instanceNames;
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
        this.numberOfInstances = reasoner.getRootOntology().getIndividualsInSignature().size();
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
        if (instanceNames.length == 0) {
            return premise; // no typed individuals: empty premise, i.e. Top
        }
        do {
            premise.clear();
            int idx = randomIndexBig(instanceWeights, cumulativeInstanceWeight);
            OWLNamedIndividual ind = instanceNames[idx];
            for (OWLClassExpression expr : instanceTypes.get(ind)) {
                if (random.nextBoolean()) {
                    premise.add(expr);
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
            // the retry probability is at most 2^-|baseSet| per iteration.
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
     * Individuals carrying at least one base-set type -- the population
     * samplePremise() actually draws from, which is smaller than the ontology's
     * individual count and is the number that matters. Zero means every premise
     * degenerates to owl:Thing; see hasIndividuals().
     */
    public int typedIndividualCount() {
        return instanceNames == null ? 0 : instanceNames.length;
    }

    /**
     * The denominator of the conclusion weighting: the size of the instance space
     * as it was at construction. Constant for the sampler's lifetime -- see the
     * note on the field's assignment before changing that.
     */
    public long instanceUniverseSize() {
        return numberOfInstances;
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
