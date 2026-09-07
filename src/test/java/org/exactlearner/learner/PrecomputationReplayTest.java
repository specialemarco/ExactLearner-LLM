package org.exactlearner.learner;

import org.exactlearner.engine.ELEngine;
import org.exactlearner.utils.Metrics;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `precomp=reuse` records the first repeat's precomputation and replays it in the
 * rest; these say the replay lands in the same state as the pass.
 *
 * That state is two things. The hypothesis is the obvious half, which a saved
 * .owl would carry. The ConceptRelation is the half that would go missing —
 * nothing but precomputation populates it, and decompose()/AxiomSimplifier read
 * it — so a replay restoring only the hypothesis passes a naive test and quietly
 * changes how the loop decomposes.
 */
public class PrecomputationReplayTest {

    private static final String NS = "http://example.org/";

    private static final OWLObjectRenderer RENDERER =
            new ManchesterOWLSyntaxOWLObjectRendererImpl();

    private static Metrics metrics() {
        return new Metrics(RENDERER);
    }

    private static IRI iri(String fragment) {
        return IRI.create(NS + fragment);
    }

    /** A ⊑ B ⊑ C plus an unrelated D: a chain for the transitive ancestors, and a non-member. */
    private static OWLOntology target() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ont = manager.createOntology(iri("target"));
        OWLClass a = df.getOWLClass(iri("A"));
        OWLClass b = df.getOWLClass(iri("B"));
        OWLClass c = df.getOWLClass(iri("C"));
        manager.addAxiom(ont, df.getOWLSubClassOfAxiom(a, b));
        manager.addAxiom(ont, df.getOWLSubClassOfAxiom(b, c));
        manager.addAxiom(ont, df.getOWLDeclarationAxiom(df.getOWLClass(iri("D"))));
        return ont;
    }

    private static OWLOntology emptyHypothesis() throws Exception {
        return OWLManager.createOWLOntologyManager().createOntology(iri("hypothesis"));
    }

    /** A learner over the shared target, with a hypothesis of its own. */
    private static Learner learner(OWLOntology target, ConceptRelation<OWLClass> relation,
                                   OWLOntology hypothesis) {
        return new Learner(new ELEngine(target), new ELEngine(hypothesis), metrics(), relation);
    }

    private static Set<OWLAxiom> logicalAxioms(OWLOntology ont) {
        return new HashSet<>(ont.getLogicalAxioms());
    }

    /** Every class's ancestor set, which is what decompose() actually asks for. */
    private static List<String> ancestorPicture(ConceptRelation<OWLClass> relation,
                                                OWLOntology target) {
        List<String> out = new ArrayList<>();
        for (OWLClass c : target.getClassesInSignature()) {
            List<OWLClass> ancestors = new ArrayList<>(relation.getAllAncestors(c));
            ancestors.sort(null);
            out.add(c.getIRI().getShortForm() + " -> " + ancestors);
        }
        out.sort(null);
        return out;
    }

    @Test
    public void replayReproducesTheHypothesisAndTheConceptRelation() throws Exception {
        OWLOntology target = target();

        ConceptRelation<OWLClass> computedRelation = new ConceptRelation<>();
        OWLOntology computedHypothesis = emptyHypothesis();
        Learner computed = learner(target, computedRelation, computedHypothesis);
        computed.precomputation();

        List<String> steps = computed.precomputationSteps();
        assertNotNull(steps, "precomputation() must record what it did");
        assertFalse(steps.isEmpty(), "this target has entailed pairs to find");

        ConceptRelation<OWLClass> replayedRelation = new ConceptRelation<>();
        OWLOntology replayedHypothesis = emptyHypothesis();
        Learner replayed = learner(target, replayedRelation, replayedHypothesis);
        replayed.replayPrecomputation(steps, computed.precomputationClassCount());

        assertEquals(logicalAxioms(computedHypothesis), logicalAxioms(replayedHypothesis),
                "the replayed hypothesis must be the computed one");
        // The half a saved hypothesis .owl would silently lose.
        assertEquals(ancestorPicture(computedRelation, target),
                     ancestorPicture(replayedRelation, target),
                     "the replayed ConceptRelation must answer decompose() identically");
        assertTrue(ancestorPicture(computedRelation, target).stream()
                        .anyMatch(line -> line.startsWith("A -> ") && line.contains("C")),
                "the chain A < B < C must actually be in the relation, or this proves nothing");
    }

    /** The membership count precomputation charges is n(n-1) either way. */
    @Test
    public void replayChargesTheSameMembershipQueries() throws Exception {
        OWLOntology target = target();

        Metrics computedMetrics = metrics();
        Learner computed = new Learner(new ELEngine(target), new ELEngine(emptyHypothesis()),
                computedMetrics, new ConceptRelation<>());
        computed.precomputation();

        Metrics replayedMetrics = metrics();
        Learner replayed = new Learner(new ELEngine(target), new ELEngine(emptyHypothesis()),
                replayedMetrics, new ConceptRelation<>());
        replayed.replayPrecomputation(computed.precomputationSteps(),
                computed.precomputationClassCount());

        int n = computed.precomputationClassCount();
        assertEquals(n * (n - 1), computedMetrics.getMembCount());
        assertEquals(computedMetrics.getMembCount(), replayedMetrics.getMembCount(),
                "a replayed run must report the same cost, or the two are not comparable");
    }

    /**
     * The backstop behind the launcher's fingerprint: replaying against the wrong
     * target would build a plausible hypothesis out of the wrong axioms.
     */
    @Test
    public void aRecordNamingAnUnknownClassIsRefused() throws Exception {
        Learner learner = learner(target(), new ConceptRelation<>(), emptyHypothesis());
        assertThrows(IllegalArgumentException.class,
                () -> learner.replayPrecomputation(
                        List.of("T " + NS + "A " + NS + "NotInThisOntology"), 4));
        assertThrows(IllegalArgumentException.class,
                () -> learner.replayPrecomputation(List.of("T only-two-fields"), 4));
    }
}
