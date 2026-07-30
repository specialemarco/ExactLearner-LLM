package org.sampler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

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
 */
public class ABoxInducedSubsumptionSampler {

    private final Set<OWLClassExpression> baseSet;
    private final OWLDataFactory factory;

    // Key: concept expression from the base set, Value: number of its instances in the current reasoner
    private final Map<OWLClassExpression, Integer> instanceCounts = new HashMap<>();
    // Key: individual, Value: base-set concepts of which it is an instance
    private Map<OWLNamedIndividual, ArrayList<OWLClassExpression>> instanceTypes;

    private OWLNamedIndividual[] instanceNames;
    private BigInteger[] instanceWeights;
    private BigInteger cumulativeInstanceWeight = BigInteger.ZERO;
    private long numberOfInstances;

    public ABoxInducedSubsumptionSampler(Set<OWLClassExpression> baseSet, OWLReasoner reasoner, OWLDataFactory factory) {
        this.baseSet = baseSet;
        this.factory = factory;
        this.numberOfInstances = reasoner.getRootOntology().getIndividualsInSignature().size();
        update_sampler(reasoner);
    }

    public void update_sampler(OWLReasoner reasoner) {
        instanceTypes = new HashMap<>();

        for (OWLClassExpression ce : baseSet) {
            Set<OWLNamedIndividual> instances = reasoner.getInstances(ce).getFlattened();
            instanceCounts.put(ce, instances.size());
            for (OWLNamedIndividual ind : instances) {
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
        Set<OWLClassExpression> premise = new HashSet<>();
        if (instanceNames.length == 0) {
            return premise; // no typed individuals: empty premise, i.e. Top
        }
        do {
            premise.clear();
            int idx = randomIndexBig(instanceWeights, cumulativeInstanceWeight);
            OWLNamedIndividual ind = instanceNames[idx];
            for (OWLClassExpression expr : instanceTypes.get(ind)) {
                if (ThreadLocalRandom.current().nextBoolean()) {
                    premise.add(expr);
                }
            }
        } while (premise.size() == baseSet.size());
        return premise;
    }

    private OWLClassExpression sampleConclusion(Set<OWLClassExpression> premise) {
        Set<OWLClassExpression> remaining = new HashSet<>(baseSet);
        remaining.removeAll(premise);
        OWLClassExpression[] types = remaining.toArray(new OWLClassExpression[0]);

        long[] weights = new long[types.length];
        long total = 0;
        for (int i = 0; i < types.length; ++i) {
            total += (numberOfInstances - instanceCounts.get(types[i]));
            weights[i] = total;
        }
        return types[randomIndexLong(weights, total)];
    }

    private static int randomIndexLong(long[] weights, long total) {
        if (total <= 0) {
            return ThreadLocalRandom.current().nextInt(weights.length);
        }
        long r = ThreadLocalRandom.current().nextLong(total);
        int index = Arrays.binarySearch(weights, r);
        if (index < 0) index = -(index + 1);
        if (index == weights.length) index--;
        while (index > 0 && weights[index] == weights[index - 1]) index--;
        return index;
    }

    private static int randomIndexBig(BigInteger[] weights, BigInteger total) {
        BigInteger r;
        do {
            r = new BigInteger(total.bitLength(), ThreadLocalRandom.current());
        } while (r.compareTo(total) >= 0);
        int index = Arrays.binarySearch(weights, r);
        if (index < 0) index = -(index + 1);
        if (index == weights.length) index--;
        while (index > 0 && weights[index].equals(weights[index - 1])) index--;
        return index;
    }

    public boolean hasIndividuals() {
        return instanceNames != null && instanceNames.length > 0;
    }
}
