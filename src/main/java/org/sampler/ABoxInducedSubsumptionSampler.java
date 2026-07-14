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

/**
 * NEW FILE — added for the A-induced sampling integration.
 *
 * This class ports the ABox-induced subsumption sampling algorithm from
 * Baris Sertkaya's paclo repository (Obiedkov & Sertkaya, "PAC learning of
 * concept inclusions for ontology-mediated query answering", 2025) into
 * ExactLearner-LLM.
 *
 * Instead of sampling candidate axioms C ⊑ D uniformly at random from the
 * space of all EL expressions (as Ana's original PAC-based sampler does),
 * this sampler draws candidates that are grounded in the ABox of the
 * ontology: it picks a real individual, looks at which baseSet concepts
 * that individual is known to be an instance of, and builds the premise
 * and conclusion from that evidence. The idea is that axioms derived this
 * way are more likely to be semantically meaningful than purely random
 * combinations of concepts.
 *
 * PORTING NOTES — two deliberate differences from Baris's original class:
 *  1) sample() here returns a fully-built OWLSubClassOfAxiom, not a raw
 *     Pair<Set<OWLClassExpression>, OWLClassExpression> as in paclo. Ana's
 *     learning loop (LaunchLLMLearnerAInduced.getCounterExample) expects a
 *     single finished axiom to test for entailment, so the assembly of the
 *     left-hand side (intersection of the premise concepts) is done here
 *     instead of by the caller.
 *  2) The constructor takes an OWLDataFactory instead of paclo's
 *     "boolean uniformConclusions" flag. We always use the weighted
 *     conclusion-sampling strategy (see sampleConclusion below), which is
 *     the same default Baris uses in his own experiments
 *     (new ABoxInducedSubsumptionSampler(baseSet, reasoner, false) in
 *     PACloOracle.java, where false means "not uniform", i.e. weighted).
 *     The OWLDataFactory is needed here only because this class now builds
 *     the final axiom itself (see point 1).
 *
 * Everything else — the sampling statistics for premises and conclusions —
 * is functionally identical to Baris's original implementation.
 */
public class ABoxInducedSubsumptionSampler {

    // All individuals present in the ontology's ABox (after the caller has
    // injected the real ABox at runtime — see LaunchLLMLearnerAInduced).
    private OWLNamedIndividual[] individuals;

    // The baseSet: the fixed collection of concept expressions (concept
    // names and/or existential restrictions) that premises and conclusions
    // are built from. Read from the external "baseSet" file for the chosen
    // BaseSet configuration (C1/C2/C3).
    private OWLClassExpression[] baseConcepts;

    // For each individual that is an instance of at least one baseSet
    // concept, the list of baseSet concepts it is known to instantiate.
    // Populated by update_sampler() using the reasoner's actual inferred
    // instances (not just asserted ABox facts).
    private Map<OWLNamedIndividual, ArrayList<OWLClassExpression>> individualTypes;

    // For each baseSet concept (by index), how many individuals are NOT
    // known instances of it. Used to weight conclusion sampling towards
    // concepts with fewer known instances (see sampleConclusion).
    private long[] noninstanceCounts;

    // Needed to assemble the final OWLSubClassOfAxiom returned by sample().
    private OWLDataFactory factory;

    public ABoxInducedSubsumptionSampler(Set<OWLClassExpression> baseSet, OWLReasoner reasoner, OWLDataFactory factory) {
        this.baseConcepts = baseSet.toArray(new OWLClassExpression[0]);
        this.factory = factory;
        this.individuals = reasoner.getRootOntology().getIndividualsInSignature().toArray(new OWLNamedIndividual[0]);
        this.individualTypes = new HashMap<>();
        this.noninstanceCounts = new long[baseConcepts.length];
        update_sampler(reasoner);
    }

    /**
     * Recomputes, for every baseSet concept, which individuals are its
     * instances according to the given reasoner, and how many are not.
     * Called once at construction time. (Baris's original class also
     * supports calling this again later to refresh the sampler as the
     * hypothesis grows; that usage is not exercised in this integration
     * since we do not currently re-sample mid-run with an updated
     * hypothesis reasoner.)
     */
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

    /**
     * Draws one candidate subsumption axiom "premise ⊑ conclusion" from the
     * ABox-induced distribution. This is the method LaunchLLMLearnerAInduced
     * calls in place of Ana's original pac.getRandomStatement().
     *
     * The left-hand side is assembled here (not by the caller) because,
     * unlike paclo's original sample(), this method must hand back a
     * complete, ready-to-test OWLSubClassOfAxiom:
     *   - empty premise            -> owl:Thing (⊤) is used as the subject
     *   - premise with one concept -> that concept is used directly
     *   - premise with 2+ concepts -> their conjunction (⊓) is used
     */
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

    /**
     * Builds a candidate premise by picking one random individual from the
     * ABox and, independently for each baseSet concept that individual is
     * known to instantiate, flipping a coin to decide whether that concept
     * joins the premise. This means the premise is always grounded in a
     * real individual's actual (inferred) type set, rather than being an
     * arbitrary combination of baseSet concepts.
     *
     * The retry loop (do/while) guards against the degenerate case where
     * the sampled premise happens to equal the entire baseSet — that would
     * leave sampleConclusion() with no candidate concepts to choose from.
     */
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

    /**
     * Picks a conclusion concept from the baseSet concepts NOT already in
     * the premise, with probability weighted by noninstanceCounts: concepts
     * with fewer known instances (i.e. "rarer" or more specific concepts)
     * are favoured. This is the "weighted" (non-uniform) conclusion
     * strategy — the same default Baris uses in his own experiments.
     * The weights array is a cumulative sum, enabling the binary search in
     * randomIndex() to pick an index in O(log n) proportional to weight.
     */
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

    /**
     * Weighted random index selection via binary search over a cumulative
     * weight array. If all weights are zero (every candidate concept has
     * every individual as an instance, i.e. noninstanceCounts are all 0),
     * falls back to a plain uniform choice to avoid dividing by zero.
     */
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
