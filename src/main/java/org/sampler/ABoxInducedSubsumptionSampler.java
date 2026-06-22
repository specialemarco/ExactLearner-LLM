package org.sampler;

import java.util.ArrayList;
import java.util.Arrays;
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

public class ABoxInducedSubsumptionSampler {

    private OWLNamedIndividual[] individuals;
    private OWLClassExpression[] baseConcepts;
    private Map<OWLNamedIndividual, ArrayList<OWLClassExpression>> individualTypes;
    private long[] noninstanceCounts;
    private OWLDataFactory factory;

    public ABoxInducedSubsumptionSampler(Set<OWLClassExpression> baseSet, OWLReasoner reasoner, OWLDataFactory factory) {
        this.baseConcepts = baseSet.toArray(new OWLClassExpression[0]);
        this.factory = factory;
        this.individuals = reasoner.getRootOntology().getIndividualsInSignature().toArray(new OWLNamedIndividual[0]);
        this.individualTypes = new HashMap<>();
        this.noninstanceCounts = new long[baseConcepts.length];
        update_sampler(reasoner);
    }

    public void update_sampler(OWLReasoner reasoner) {
        individualTypes.clear();
        for (int i = 0; i < baseConcepts.length; ++i) {
            OWLClassExpression ce = baseConcepts[i];
            Set<OWLNamedIndividual> instances = reasoner.getInstances(ce).getFlattened();
            noninstanceCounts[i] = individuals.length - instances.size();
            for (OWLNamedIndividual ind : instances) {
                individualTypes.computeIfAbsent(ind, k -> new ArrayList<>()).add(ce);
            }
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
        do {
            premise.clear();
            OWLNamedIndividual ind = individuals[ThreadLocalRandom.current().nextInt(individuals.length)];
            if (individualTypes.containsKey(ind)) {
                for (OWLClassExpression expr : individualTypes.get(ind)) {
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        premise.add(expr);
                    }
                }
            }
        } while (premise.size() == baseConcepts.length);
        return premise;
    }

    private OWLClassExpression sampleConclusion(Set<OWLClassExpression> premise) {
        Set<OWLClassExpression> remaining = new HashSet<>(Arrays.asList(baseConcepts));
        remaining.removeAll(premise);
        OWLClassExpression[] types = remaining.toArray(new OWLClassExpression[0]);
        long[] weights = new long[types.length];
        weights[0] = noninstanceCounts[0];
        for (int i = 1; i < types.length; ++i) {
            weights[i] = weights[i - 1] + noninstanceCounts[i];
        }
        return types[randomIndex(weights)];
    }

    private int randomIndex(long[] weights) {
        int low = 0;
        int high = weights.length - 1;
        if (weights[high] == 0) return ThreadLocalRandom.current().nextInt(weights.length);
        long r = ThreadLocalRandom.current().nextLong(weights[high]);
        while (low < high) {
            int mid = (low + high) / 2;
            if (r < weights[mid]) high = mid;
            else low = mid + 1;
        }
        return low;
    }

    public boolean hasIndividuals() {
        return individuals.length > 0;
    }
}
