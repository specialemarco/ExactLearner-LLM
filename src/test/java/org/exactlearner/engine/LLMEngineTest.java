package org.exactlearner.engine;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * How LLMEngine turns an axiom into LLM queries, with DummyWorkloadManager
 * standing in for the model: it records each query and answers by a rule.
 */
public class LLMEngineTest {
    private final OWLOntologyManager man = OWLManager.createOWLOntologyManager();

    // A EquivalentTo B is asked as two queries, A SubClassOf B and B SubClassOf A.
    @Test
    public void testSplitEquivalentInEntailed() throws OWLOntologyCreationException {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClassExpression a = df.getOWLClass(IRI.create("A"));
        OWLClassExpression b = df.getOWLClass(IRI.create("B"));

        OWLAxiom axiom = df.getOWLEquivalentClassesAxiom(a, b);
        DummyWorkloadManager dummy = new DummyWorkloadManager();

        LLMEngine engine = new LLMEngine(man.createOntology(), man, dummy);
        engine.entailed(axiom);

        assertThat(dummy.getQueries(), hasSize(2));
        assertThat(dummy.getQueries(), contains(
                "A SubClassOf B",
                "B SubClassOf A"
        ));
    }

    // A SubClassOf B and C is asked as A SubClassOf B and A SubClassOf C.
    @Test
    public void testSplitAxiomInEntailed() throws OWLOntologyCreationException {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClassExpression a = df.getOWLClass(IRI.create("A"));
        OWLClassExpression b = df.getOWLClass(IRI.create("B"));
        OWLClassExpression c = df.getOWLClass(IRI.create("C"));

        OWLClassExpression expression = df.getOWLObjectIntersectionOf(b, c);
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(a, expression);
        DummyWorkloadManager dummy = new DummyWorkloadManager();

        LLMEngine engine = new LLMEngine(man.createOntology(), man, dummy);
        engine.entailed(axiom);

        assertThat(dummy.getQueries(), hasSize(2));
        assertThat(dummy.getQueries(), contains(
                "A SubClassOf B",
                "A SubClassOf C"
        ));
    }

    // The split axiom is entailed only if the model says yes to every part.
    @Test
    public void testSplitAxiomResponseEntailed() throws OWLOntologyCreationException {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClassExpression a = df.getOWLClass(IRI.create("A"));
        OWLClassExpression b = df.getOWLClass(IRI.create("B"));
        OWLClassExpression c = df.getOWLClass(IRI.create("C"));

        OWLClassExpression expression = df.getOWLObjectIntersectionOf(b, c);
        OWLAxiom axiom = df.getOWLSubClassOfAxiom(a, expression);

        DummyWorkloadManager dummy = new DummyWorkloadManager(s -> false);
        LLMEngine engine = new LLMEngine(man.createOntology(), man, dummy);

        assertThat(engine.entailed(axiom), is(false));

        dummy = new DummyWorkloadManager(s -> s.equals("A SubClassOf B"));
        engine = new LLMEngine(man.createOntology(), man, dummy);

        assertThat(engine.entailed(axiom), is(false));

        dummy = new DummyWorkloadManager(s -> s.equals("A SubClassOf B") || s.equals("A SubClassOf C"));
        engine = new LLMEngine(man.createOntology(), man, dummy);

        assertThat(engine.entailed(axiom), is(true));
    }
}