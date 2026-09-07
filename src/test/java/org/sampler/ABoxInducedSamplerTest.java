package org.sampler;

import org.junit.jupiter.api.Test;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sampler's two silent failure modes, both added 2026-08-27.
 *
 * With no individual carrying a base-set type there is nothing to induce a
 * premise from, and the sampler degrades to owl:Thing on the left of every axiom
 * -- silently, and at cache speed, which reads like a converged run. That is what
 * hasIndividuals() detects and LaunchLLMLearnerAInduced.checkSamplerIsUsable()
 * refuses to run on.
 *
 * The second is what a refresh may and may not touch: update_sampler(_, false)
 * moves the premise side only, and the conclusion weighting's denominator is
 * fixed at construction in both modes.
 */
public class ABoxInducedSamplerTest {

    private static final String NS = "http://example.org/";

    private static IRI iri(String fragment) {
        return IRI.create(NS + fragment);
    }

    /** Two named classes, plus whatever ABox the caller asks for. */
    private static OWLOntology ontology(boolean withIndividual) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ont = manager.createOntology(iri("test"));
        OWLClass a = df.getOWLClass(iri("A"));
        OWLClass b = df.getOWLClass(iri("B"));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(a));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(b));
        if (withIndividual) {
            manager.addAxiom(ont, df.getOWLClassAssertionAxiom(a, df.getOWLNamedIndividual(iri("x"))));
        }
        return ont;
    }

    private static ABoxInducedSubsumptionSampler samplerFor(OWLOntology ont) {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        OWLReasoner reasoner = new ElkReasonerFactory().createReasoner(ont);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);
        Set<OWLClassExpression> baseSet = new LinkedHashSet<>();
        baseSet.add(df.getOWLClass(iri("A")));
        baseSet.add(df.getOWLClass(iri("B")));
        return new ABoxInducedSubsumptionSampler(baseSet, reasoner, df, 0L);
    }

    @Test
    public void anEmptyABoxLeavesNothingToSampleFrom() throws Exception {
        ABoxInducedSubsumptionSampler sampler = samplerFor(ontology(false));
        assertFalse(sampler.hasIndividuals(), "no individual can carry a base-set type");
    }

    /** What the guard prevents: every candidate is owl:Thing SubClassOf X. */
    @Test
    public void withoutIndividualsEveryCandidateIsTopSubsumption() throws Exception {
        ABoxInducedSubsumptionSampler sampler = samplerFor(ontology(false));
        OWLClassExpression top = OWLManager.getOWLDataFactory().getOWLThing();

        for (int i = 0; i < 20; i++) {
            OWLSubClassOfAxiom axiom = sampler.sample();
            assertEquals(top, axiom.getSubClass(), "premise degenerates to Top on draw " + i);
        }
    }

    @Test
    public void oneTypedIndividualIsEnoughToSample() throws Exception {
        ABoxInducedSubsumptionSampler sampler = samplerFor(ontology(true));
        assertTrue(sampler.hasIndividuals());

        OWLSubClassOfAxiom axiom = sampler.sample();
        assertEquals(1, sampler.getDraws());
        assertNotNull(axiom.getSubClass());
    }

    /**
     * The conclusion weighting's denominator is fixed at construction, on purpose.
     *
     * It is the size of the instance space K0. Making update_sampler refresh it
     * was tried on 2026-08-27 and reverted: the only refresh anyone plans to make
     * is update_sampler(hypothesisReasoner, false), and the hypothesis ontology is
     * TBox-only, so a refreshed denominator would come back 0 and every
     * conclusion weight -- numberOfInstances - instanceCounts.get(c) in
     * sampleConclusion() -- would go negative, collapsing the distribution to
     * uniform through randomIndexLong's total <= 0 branch. Frozen is the safe
     * default, and it matches the paclo original.
     */
    @Test
    public void theWeightingDenominatorIsFixedAtConstruction() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ont = manager.createOntology(iri("growing"));
        OWLClass a = df.getOWLClass(iri("A"));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(a));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(df.getOWLClass(iri("B"))));
        manager.addAxiom(ont, df.getOWLClassAssertionAxiom(a, df.getOWLNamedIndividual(iri("x0"))));

        OWLReasoner reasoner = new ElkReasonerFactory().createReasoner(ont);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);
        Set<OWLClassExpression> baseSet = new LinkedHashSet<>();
        baseSet.add(a);
        baseSet.add(df.getOWLClass(iri("B")));
        ABoxInducedSubsumptionSampler sampler = new ABoxInducedSubsumptionSampler(baseSet, reasoner, df, 0L);
        assertEquals(1, sampler.instanceUniverseSize());
        assertEquals(1, sampler.typedIndividualCount());

        for (int i = 1; i <= 10; i++) {
            manager.addAxiom(ont, df.getOWLClassAssertionAxiom(a, df.getOWLNamedIndividual(iri("x" + i))));
        }
        reasoner.flush();
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);
        sampler.update_sampler(reasoner, true);

        assertEquals(11, sampler.typedIndividualCount(), "the refresh saw the new individuals");
        assertEquals(1, sampler.instanceUniverseSize(),
                "the denominator stays at K0's size -- see this test's note before changing it");
    }

    /** A premise-only refresh must leave the conclusion weighting entirely alone. */
    @Test
    public void aPremiseOnlyRefreshLeavesTheWeightingTotalAlone() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ont = manager.createOntology(iri("premiseOnly"));
        OWLClass a = df.getOWLClass(iri("A"));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(a));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(df.getOWLClass(iri("B"))));
        manager.addAxiom(ont, df.getOWLClassAssertionAxiom(a, df.getOWLNamedIndividual(iri("x0"))));

        OWLReasoner reasoner = new ElkReasonerFactory().createReasoner(ont);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);
        Set<OWLClassExpression> baseSet = new LinkedHashSet<>();
        baseSet.add(a);
        baseSet.add(df.getOWLClass(iri("B")));
        ABoxInducedSubsumptionSampler sampler = new ABoxInducedSubsumptionSampler(baseSet, reasoner, df, 0L);

        manager.addAxiom(ont, df.getOWLClassAssertionAxiom(a, df.getOWLNamedIndividual(iri("x1"))));
        reasoner.flush();
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);
        sampler.update_sampler(reasoner, false);

        assertEquals(2, sampler.typedIndividualCount(), "premise side follows the reasoner");
        assertEquals(1, sampler.instanceUniverseSize(),
                "conclusion side untouched: neither its counts nor its denominator move");
    }

    /**
     * Exercises samplePremise()'s retry branch, which is what keeps sample() total.
     *
     * One individual typed with BOTH base-set concepts means the premise comes
     * back as the whole base set roughly a quarter of the time -- two coin flips,
     * both heads -- so the retry at the do-while fires constantly here, where on a
     * 131-concept base set it would essentially never fire. If that retry is ever
     * simplified away, sampleConclusion()'s candidate list goes empty and this
     * test fails with IllegalArgumentException out of random.nextInt(0).
     */
    @Test
    public void theConclusionIsAlwaysOutsideThePremise() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ont = manager.createOntology(iri("bothTypes"));
        OWLClass a = df.getOWLClass(iri("A"));
        OWLClass b = df.getOWLClass(iri("B"));
        OWLNamedIndividual x = df.getOWLNamedIndividual(iri("x"));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(a));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(b));
        manager.addAxiom(ont, df.getOWLClassAssertionAxiom(a, x));
        manager.addAxiom(ont, df.getOWLClassAssertionAxiom(b, x));

        OWLReasoner reasoner = new ElkReasonerFactory().createReasoner(ont);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);
        Set<OWLClassExpression> baseSet = new LinkedHashSet<>();
        baseSet.add(a);
        baseSet.add(b);
        ABoxInducedSubsumptionSampler sampler =
                new ABoxInducedSubsumptionSampler(baseSet, reasoner, df, 0L);

        for (int i = 0; i < 500; i++) {
            OWLSubClassOfAxiom axiom = sampler.sample();
            OWLClassExpression lhs = axiom.getSubClass();
            OWLClassExpression rhs = axiom.getSuperClass();
            assertTrue(baseSet.contains(rhs), "conclusion comes from the base set, draw " + i);
            // A premise of both concepts would leave nothing to conclude, so the
            // left-hand side is Top or exactly one concept, never the conjunction.
            assertTrue(lhs.isOWLThing() || lhs.equals(a) || lhs.equals(b),
                    "premise must be a strict subset, got " + lhs + " on draw " + i);
            assertTrue(!lhs.equals(rhs), "conclusion must not repeat the premise, draw " + i);
        }
    }

    /** sample() never returns null, so nothing downstream may treat null as exhaustion. */
    @Test
    public void theSamplerIsNeverExhausted() throws Exception {
        ABoxInducedSubsumptionSampler sampler = samplerFor(ontology(true));
        for (int i = 0; i < 200; i++) {
            assertNotNull(sampler.sample(), "draw " + i);
        }
        assertEquals(200, sampler.getDraws());
    }

    // ---- the weighting axis, added 2026-09-07 -------------------------------

    /**
     * One individual typed with all eight base-set concepts against eight typed
     * with one each: weighted gives the rich one 2^8 against 2^1 apiece, so
     * 256/272 of draws; uniform gives it 1 in 9. Only it can produce a premise of
     * more than one concept, so the left-hand side reports which was drawn.
     */
    private static ABoxInducedSubsumptionSampler skewedSampler(
            ABoxInducedSubsumptionSampler.Weighting weighting) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ont = manager.createOntology(iri("skewed"));
        OWLNamedIndividual rich = df.getOWLNamedIndividual(iri("rich"));
        Set<OWLClassExpression> baseSet = new LinkedHashSet<>();
        for (int i = 0; i < 8; i++) {
            OWLClass c = df.getOWLClass(iri("C" + i));
            baseSet.add(c);
            manager.addAxiom(ont, df.getOWLDeclarationAxiom(c));
            manager.addAxiom(ont, df.getOWLClassAssertionAxiom(c, rich));
            manager.addAxiom(ont, df.getOWLClassAssertionAxiom(c, df.getOWLNamedIndividual(iri("poor" + i))));
        }
        OWLReasoner reasoner = new ElkReasonerFactory().createReasoner(ont);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);
        return new ABoxInducedSubsumptionSampler(baseSet, reasoner, df, 0L, weighting);
    }

    /** Fraction of draws whose premise is a conjunction, i.e. came from `rich`. */
    private static int conjunctivePremises(ABoxInducedSubsumptionSampler sampler, int draws) {
        int n = 0;
        for (int i = 0; i < draws; i++) {
            if (sampler.sample().getSubClass() instanceof OWLObjectIntersectionOf) {
                n++;
            }
        }
        return n;
    }

    /** Expected ~360 and ~43 of 400; only a mode that stopped weighting trips these. */
    @Test
    public void theTwoModesDrawThePremiseIndividualDifferently() throws Exception {
        int weighted = conjunctivePremises(
                skewedSampler(ABoxInducedSubsumptionSampler.Weighting.WEIGHTED), 400);
        int unweighted = conjunctivePremises(
                skewedSampler(ABoxInducedSubsumptionSampler.Weighting.UNWEIGHTED), 400);

        assertTrue(weighted > 300,
                "weighted should almost always draw the 8-type individual, got " + weighted + "/400");
        assertTrue(unweighted < 150,
                "unweighted draws it 1 time in 9, got " + unweighted + "/400");
    }

    /** Unweighted is still the same seeded stream, not ThreadLocalRandom. */
    @Test
    public void unweightedIsReproducibleUnderOneSeed() throws Exception {
        assertEquals(
                conjunctivePremises(skewedSampler(ABoxInducedSubsumptionSampler.Weighting.UNWEIGHTED), 200),
                conjunctivePremises(skewedSampler(ABoxInducedSubsumptionSampler.Weighting.UNWEIGHTED), 200));
    }

    /** Weighted stays the default, so no existing run changes arm by accident. */
    @Test
    public void theSeededConstructorsStillMeanWeighted() throws Exception {
        assertEquals(ABoxInducedSubsumptionSampler.Weighting.WEIGHTED,
                samplerFor(ontology(true)).weighting());
    }

    /**
     * UNWEIGHTED draws from the whole signature, untyped individuals included,
     * because paclo's plain sampler does — so an untyped draw gives owl:Thing on
     * the left. This test exists because that looks like a bug worth "fixing":
     * an earlier version of this file restricted UNWEIGHTED to typed individuals,
     * making it an arm upstream has no counterpart for.
     */
    @Test
    public void unweightedDrawsUntypedIndividualsJustAsPacloDoes() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ont = manager.createOntology(iri("untyped"));
        OWLNamedIndividual typed = df.getOWLNamedIndividual(iri("typed"));

        // Four types on the one typed individual, so a typed draw empties the
        // premise only 1 time in 16; fewer and the p=0.5 filter blurs the arms.
        Set<OWLClassExpression> baseSet = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            OWLClass c = df.getOWLClass(iri("C" + i));
            baseSet.add(c);
            manager.addAxiom(ont, df.getOWLDeclarationAxiom(c));
            if (i < 4) {
                manager.addAxiom(ont, df.getOWLClassAssertionAxiom(c, typed));
            }
        }
        // 20 individuals with no base-set type at all, in the signature only
        // because a role assertion mentions them.
        OWLObjectProperty r = df.getOWLObjectProperty(iri("r"));
        for (int i = 0; i < 20; i++) {
            manager.addAxiom(ont, df.getOWLObjectPropertyAssertionAxiom(
                    r, typed, df.getOWLNamedIndividual(iri("bare" + i))));
        }

        OWLReasoner reasoner = new ElkReasonerFactory().createReasoner(ont);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);

        ABoxInducedSubsumptionSampler unweighted = new ABoxInducedSubsumptionSampler(
                baseSet, reasoner, df, 0L, ABoxInducedSubsumptionSampler.Weighting.UNWEIGHTED);
        ABoxInducedSubsumptionSampler weighted = new ABoxInducedSubsumptionSampler(
                baseSet, reasoner, df, 0L, ABoxInducedSubsumptionSampler.Weighting.WEIGHTED);

        assertEquals(1, unweighted.typedIndividualCount(), "one individual has base-set types");
        assertEquals(21, unweighted.premisePopulationSize(),
                "the plain sampler draws from the whole signature, untyped included");
        assertEquals(1, weighted.premisePopulationSize(),
                "the weighted one draws only from the typed individuals");

        // ~95% against ~6%: 20 draws in 21 land on an untyped individual.
        assertTrue(tops(unweighted, 400) > 300,
                "unweighted must reach the untyped individuals");
        assertTrue(tops(weighted, 400) < 100,
                "weighted must not: its population is the typed individual alone");
    }

    /** Draws whose left-hand side degenerated to owl:Thing, i.e. an empty premise. */
    private static int tops(ABoxInducedSubsumptionSampler sampler, int draws) {
        int n = 0;
        for (int i = 0; i < draws; i++) {
            if (sampler.sample().getSubClass().isOWLThing()) {
                n++;
            }
        }
        return n;
    }
}
