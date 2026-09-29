package org.exactlearner.learner;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.exactlearner.engine.BaseEngine;
import org.exactlearner.engine.ELEngine;
import org.exactlearner.tree.ELTree;
import org.exactlearner.utils.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.*;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Learner operations with ELK on both sides: T is the target, H the hypothesis,
 * both empty at the start of each test. compare() matches results up to the
 * order of conjuncts.
 */
public class ELLearnerTest {

    private final OWLObjectRenderer myRenderer =  new ManchesterOWLSyntaxOWLObjectRendererImpl();
    private final Metrics metrics = new Metrics(myRenderer);
    private final OWLOntologyManager man = OWLManager.createOWLOntologyManager();
    private OWLOntology targetOntology = null;
    private OWLOntology hypothesisOntology = null;
    private BaseEngine elQueryEngineForT = null;
    private BaseEngine elQueryEngineForH = null;
    private BaseLearner baseLearner = null;

    @BeforeEach
    public void setUp() throws Exception {
        LogManager.getRootLogger().atLevel(Level.OFF);

        targetOntology = man.createOntology();
        hypothesisOntology = man.createOntology();

        elQueryEngineForH = new ELEngine(hypothesisOntology);
        elQueryEngineForT = new ELEngine(targetOntology);

        baseLearner = new Learner(elQueryEngineForT, elQueryEngineForH, metrics);
        
    }

    // mergeRight joins sibling edges T entails together: A SubClassOf r some C and
    // r some B and r some A becomes A SubClassOf r some (B and C) and r some A.
    @Test
    public void learnerSiblingMerge1() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));
        OWLClass left = A;
        OWLClassExpression right = df.getOWLObjectIntersectionOf(df.getOWLObjectSomeValuesFrom(R, C), df.getOWLObjectSomeValuesFrom(R, B), df.getOWLObjectSomeValuesFrom(R,A));
        OWLSubClassOfAxiom axiom;
        OWLSubClassOfAxiom mergedAxiom= df.getOWLSubClassOfAxiom(A, df.getOWLObjectIntersectionOf(df.getOWLObjectSomeValuesFrom(R, df.getOWLObjectIntersectionOf(B,C)),df.getOWLObjectSomeValuesFrom(R,A)));
        man.addAxiom(targetOntology, mergedAxiom);
        axiom = baseLearner.mergeRight(left, right);
        System.out.println("Merged: " + axiom);
        if(!axiom.equals(mergedAxiom))
            fail("Did not merge.");
    }
    // unsaturateLeft drops left-hand names T does not need: with B SubClassOf A in T,
    // B and C and D and E and F SubClassOf A shrinks to B SubClassOf A.
    @Test
    public void unsaturateLeft() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();
        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLClassExpression ABC = df.getOWLObjectIntersectionOf(A,B,C);

        OWLClass D = df.getOWLClass(IRI.create(":D"));
        OWLClass E = df.getOWLClass(IRI.create(":E"));
        OWLClass F = df.getOWLClass(IRI.create(":F"));
        OWLClassExpression DEF = df.getOWLObjectIntersectionOf(D,E,F);

        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(ABC, DEF);
        man.addAxiom(targetOntology, axiom);

        axiom = df.getOWLSubClassOfAxiom(B, A);
        man.addAxiom(targetOntology, axiom);

        OWLClassExpression BCDEF = df.getOWLObjectIntersectionOf(B,C,D,E,F);
        axiom = df.getOWLSubClassOfAxiom(BCDEF, A);
        man.addAxiom(targetOntology, axiom);

        baseLearner.precomputation();

        axiom = baseLearner.unsaturateLeft(BCDEF, A);

        compare(axiom, df.getOWLSubClassOfAxiom(B, A));
    }

    // T has A and r some B SubClassOf C, B SubClassOf D and E, E SubClassOf F and r some G,
    // H SubClassOf G. decomposeLeft shrinks the first to B SubClassOf D or E; which one is a
    // tie-break on class order, both are correct. decomposeRight returns E SubClassOf F and
    // r some G unchanged: its one candidate, F SubClassOf r some G, is not in T, and it only
    // uses names already on the right, so H SubClassOf G cannot come out of it.
    @Test
    public void decompose() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLClass D = df.getOWLClass(IRI.create(":D"));
        OWLClass E = df.getOWLClass(IRI.create(":E"));

        OWLClass F = df.getOWLClass(IRI.create(":F"));
        OWLClass G = df.getOWLClass(IRI.create(":G"));
        OWLClass H = df.getOWLClass(IRI.create(":H"));

        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression ArB = df.getOWLObjectIntersectionOf(A, df.getOWLObjectSomeValuesFrom(R, B));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(ArB, C));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(B, df.getOWLObjectIntersectionOf(D, E)));
        OWLClassExpression FrG = df.getOWLObjectIntersectionOf(F, df.getOWLObjectSomeValuesFrom(R, G));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(E, FrG));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(H, G));

        OWLSubClassOfAxiom left = baseLearner.decomposeLeft(ArB, C);
        assertThat(left.getSubClass(), is(B));
        assertThat(List.of(D, E).contains(left.getSuperClass()), is(true));

        OWLSubClassOfAxiom right = baseLearner.decomposeRight(E, FrG);
        assertThat(right, is(df.getOWLSubClassOfAxiom(E, FrG)));
    }

    // saturateRight on A SubClassOf B and C, where T also entails D, E and F for A.
    // No assertion: only checks that it completes. The old comment expected
    // A SubClassOf A and B and C and D and E and F (unchecked).
    @Test
    public void saturateWithTreeRight() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();
        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLClassExpression ABC = df.getOWLObjectIntersectionOf(A,B,C);

        OWLClass D = df.getOWLClass(IRI.create(":D"));
        OWLClass E = df.getOWLClass(IRI.create(":E"));
        OWLClass F = df.getOWLClass(IRI.create(":F"));
        OWLClassExpression DEF = df.getOWLObjectIntersectionOf(D,E,F);

        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(ABC, DEF);
        man.addAxiom(targetOntology, axiom);

        OWLClassExpression BC = df.getOWLObjectIntersectionOf(B,C);
        axiom = df.getOWLSubClassOfAxiom(A, BC);
        man.addAxiom(targetOntology, axiom);

        axiom = baseLearner.saturateRight(A, BC);

        System.out.println("Saturation: " + axiom);

    }

    // Identical to learnerSiblingMerge1.
    @Test
    public void learnerSiblingMerge() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));
        OWLClass left = A;
        OWLClassExpression right = df.getOWLObjectIntersectionOf(df.getOWLObjectSomeValuesFrom(R, C), df.getOWLObjectSomeValuesFrom(R, B), df.getOWLObjectSomeValuesFrom(R,A));
        OWLSubClassOfAxiom axiom;
        OWLSubClassOfAxiom mergedAxiom= df.getOWLSubClassOfAxiom(A, df.getOWLObjectIntersectionOf(df.getOWLObjectSomeValuesFrom(R, df.getOWLObjectIntersectionOf(B,C)),df.getOWLObjectSomeValuesFrom(R,A)));
        man.addAxiom(targetOntology, mergedAxiom);
        axiom = baseLearner.mergeRight(left, right);
        System.out.println("Merged: " + axiom);
        if(!axiom.equals(mergedAxiom))
            fail("Did not merge.");
    }

    // branchLeft splits a conjunction under one edge into separate edges:
    // r some (B and C and D) SubClassOf A becomes r some D and r some B and r some C
    // SubClassOf A, which T contains.
    @Test
    public void branchLeft() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass right = A;
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLClass D = df.getOWLClass(IRI.create(":D"));
        OWLClassExpression left = df.getOWLObjectSomeValuesFrom(R, df.getOWLObjectIntersectionOf(B,C,D));

        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(left, A);
        man.addAxiom(targetOntology, axiom);
        axiom = null;

        OWLSubClassOfAxiom branchedAxiom = df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(df.getOWLObjectSomeValuesFrom(R, D), df.getOWLObjectSomeValuesFrom(R, B), df.getOWLObjectSomeValuesFrom(R, C)), A);
        man.addAxiom(targetOntology, branchedAxiom);
        axiom = baseLearner.branchLeft(left, right);
        System.out.println("Branched: " + axiom);
        if(!axiom.equals(branchedAxiom))
            fail("Did not branch.");
    }

    // saturateRight fills in what T entails: A SubClassOf r some Thing becomes
    // A SubClassOf C and r some B.
    @Test
    public void saturateHypothesisRight() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression right = df.getOWLObjectSomeValuesFrom(R, B);
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(A, df.getOWLObjectIntersectionOf(C, right));
        man.addAxiom(targetOntology, axiom);
        baseLearner.precomputation();

        OWLSubClassOfAxiom result = baseLearner.saturateRight(A, df.getOWLObjectSomeValuesFrom(R, df.getOWLThing()));
        OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(A, df.getOWLObjectIntersectionOf(C, df.getOWLObjectSomeValuesFrom(R, B)));
        compare(result, target);
    }

    // decomposeRight with A SubClassOf r some A in T: A SubClassOf A and
    // r some (A and r some A) reduces to A SubClassOf r some A.
    @Test
    public void rightDecompositionBecomeEdge() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression right2 = df.getOWLObjectSomeValuesFrom(R, A);
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(A, right2);
        man.addAxiom(targetOntology, axiom);
        baseLearner.precomputation();

        OWLClassExpression right1 = df.getOWLObjectSomeValuesFrom(R, df.getOWLObjectIntersectionOf(A, right2));
        OWLSubClassOfAxiom result = baseLearner.decomposeRight(A, df.getOWLObjectIntersectionOf(A, right1));
        OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(A, right2);
        compare(result, target);

    }

    // decomposeRight moves to a new left-hand name: with A SubClassOf B and
    // B SubClassOf r some B in T, A SubClassOf r some B becomes B SubClassOf r some B.
    @Test
    public void rightDecompositionNewLeft() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression right = df.getOWLObjectSomeValuesFrom(R, B);
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(B, right);
        man.addAxiom(targetOntology, axiom);
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(A, B));
        baseLearner.precomputation();

        OWLSubClassOfAxiom result = baseLearner.decomposeRight(A, right);
        OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(B, right);
        compare(result, target);
    }

    // decomposeRight of C SubClassOf r some A and r some B, with H already holding
    // A SubClassOf r some A and T holding C SubClassOf A; expects C SubClassOf A and r some B.
    @Test
    public void rightDecompositionDropEdge() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression rA = df.getOWLObjectSomeValuesFrom(R, A);
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(A, rA));
        OWLClassExpression rB = df.getOWLObjectSomeValuesFrom(R, B);
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(C, rB));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(C, A));
        baseLearner.precomputation();

        man.addAxiom(hypothesisOntology, df.getOWLSubClassOfAxiom(A, rA));

        OWLSubClassOfAxiom result = baseLearner.decomposeRight(C, df.getOWLObjectIntersectionOf(rA, rB));
        OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(C, df.getOWLObjectIntersectionOf(A, rB));
        compare(result, target);
    }

    // decomposeLeft of r some B SubClassOf B, with H holding r some B SubClassOf C
    // and T also B SubClassOf A; expects C and r some (A and B) SubClassOf B.
    @Test
    public void saturateHypothesisLeft() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression rB = df.getOWLObjectSomeValuesFrom(R, B);
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(rB, B));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(rB, C));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(B, A));
        baseLearner.precomputation();

        man.addAxiom(hypothesisOntology, df.getOWLSubClassOfAxiom(rB, C));

        OWLSubClassOfAxiom result = baseLearner.decomposeLeft(rB, B);
        OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(C,
                df.getOWLObjectSomeValuesFrom(R, df.getOWLObjectIntersectionOf(A, B))
        ), B);
        compare(result, target);
    }

    // decomposeLeft of s some (A and r some A) SubClassOf A, with H already holding
    // r some Thing SubClassOf A; expects the r edge dropped: s some A SubClassOf A.
    @Test
    public void decompositionLeftDropEdge() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));
        OWLObjectProperty S = df.getOWLObjectProperty(IRI.create(":s"));

        OWLClassExpression sA = df.getOWLObjectSomeValuesFrom(S, A);
        OWLClassExpression rT = df.getOWLObjectSomeValuesFrom(R, df.getOWLThing());
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(sA, A));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(rT, A));
        baseLearner.precomputation();

        man.addAxiom(hypothesisOntology, df.getOWLSubClassOfAxiom(rT, A));

        OWLSubClassOfAxiom result = baseLearner.decomposeLeft(df.getOWLObjectSomeValuesFrom(S,
                df.getOWLObjectIntersectionOf(A, df.getOWLObjectSomeValuesFrom(R, A))), A);
        OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(sA, A);
        compare(result, target);
    }

    // decomposeLeft finds the inner counterexample: with H holding
    // s some A SubClassOf A and T also r some A SubClassOf B,
    // s some (A and r some A) SubClassOf A yields A and r some A SubClassOf B.
    @Test
    public void decompositionLeftFindEdge() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));
        OWLObjectProperty S = df.getOWLObjectProperty(IRI.create(":s"));

        OWLClassExpression sA = df.getOWLObjectSomeValuesFrom(S, A);
        OWLClassExpression rA = df.getOWLObjectSomeValuesFrom(R, A);
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(sA, A));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(rA, B));
        baseLearner.precomputation();

        man.addAxiom(hypothesisOntology, df.getOWLSubClassOfAxiom(sA, A));

        OWLSubClassOfAxiom result = baseLearner.decomposeLeft(df.getOWLObjectSomeValuesFrom(S,
                df.getOWLObjectIntersectionOf(A, rA)), A);
        OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(A, rA), B);
        compare(result, target);
    }

    // Identical to decompositionLeftFindEdge, despite its name: it calls decomposeLeft.
    @Test
    public void unsaturateLeftExtended() throws Exception {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));
        OWLObjectProperty S = df.getOWLObjectProperty(IRI.create(":s"));

        OWLClassExpression sA = df.getOWLObjectSomeValuesFrom(S, A);
        OWLClassExpression rA = df.getOWLObjectSomeValuesFrom(R, A);
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(sA, A));
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(rA, B));
        baseLearner.precomputation();

        man.addAxiom(hypothesisOntology, df.getOWLSubClassOfAxiom(sA, A));

        OWLSubClassOfAxiom result = baseLearner.decomposeLeft(df.getOWLObjectSomeValuesFrom(S,
                df.getOWLObjectIntersectionOf(A, rA)), A);
        OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(A, rA), B);
        compare(result, target);
    }

    private void compare(OWLSubClassOfAxiom value, OWLSubClassOfAxiom expected) throws Exception {
        compare(value.getSubClass(), expected.getSubClass());
        compare(value.getSuperClass(), expected.getSuperClass());
    }

    private void compare(OWLClassExpression value, OWLClassExpression expected) throws Exception {
        assertThat(new ELTree(value).equals(new ELTree(expected)), is(true));
    }
}