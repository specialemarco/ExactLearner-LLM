package org.exactlearner.learner;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.exactlearner.engine.BaseEngine;
import org.exactlearner.engine.ELEngine;
import org.exactlearner.tree.ELTree;
import org.exactlearner.utils.Metrics;
import org.junit.Before;
import org.junit.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;

public class ELLearnerTest {

    private final OWLObjectRenderer myRenderer =  new ManchesterOWLSyntaxOWLObjectRendererImpl();
    private final Metrics metrics = new Metrics(myRenderer);
    private final OWLOntologyManager man = OWLManager.createOWLOntologyManager();
    private OWLOntology targetOntology = null;
    private OWLOntology hypothesisOntology = null;
    private BaseEngine elQueryEngineForT = null;
    private BaseEngine elQueryEngineForH = null;
    private BaseLearner baseLearner = null;
     


    @Before
    public void setUp() throws Exception {
        LogManager.getRootLogger().atLevel(Level.OFF);

        targetOntology = man.createOntology();
        hypothesisOntology = man.createOntology();

        elQueryEngineForH = new ELEngine(hypothesisOntology);
        elQueryEngineForT = new ELEngine(targetOntology);

        baseLearner = new Learner(elQueryEngineForT, elQueryEngineForH, metrics);
        
    }

    @Test
    public void learnerSiblingMerge1() {
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
        try {
            axiom = baseLearner.mergeRight(left, right);
            System.out.println("Merged: " + axiom);
            if(!axiom.equals(mergedAxiom))
                fail("Did not merge.");
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }
    @Test
    public void unsaturateLeft() {
        OWLDataFactory df = man.getOWLDataFactory();
        // Create 6 classes to target ontology
        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLClassExpression ABC = df.getOWLObjectIntersectionOf(A,B,C);

        OWLClass D = df.getOWLClass(IRI.create(":D"));
        OWLClass E = df.getOWLClass(IRI.create(":E"));
        OWLClass F = df.getOWLClass(IRI.create(":F"));
        OWLClassExpression DEF = df.getOWLObjectIntersectionOf(D,E,F);

        // Create and add an axiom to ontology
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(ABC, DEF);
        man.addAxiom(targetOntology, axiom);

        // Add expected axiom to ontology
        axiom = df.getOWLSubClassOfAxiom(B, A);
        man.addAxiom(targetOntology, axiom);

        // DEBUG: Look at classes in ontology
        //System.out.println(targetOntology.getClassesInSignature());

        // Begin unsaturation of inclusion
        // B \sqcap C \sqcap D \sqcap E \sqcap F  \sqsubseteq A
        OWLClassExpression BCDEF = df.getOWLObjectIntersectionOf(B,C,D,E,F);
        axiom = df.getOWLSubClassOfAxiom(BCDEF, A);
        man.addAxiom(targetOntology, axiom);

        baseLearner.precomputation();

        try
        {
            // Why was this changed?
            axiom = baseLearner.unsaturateLeft(BCDEF, A);

            // Expected
            // B \sqsubseteq A
            compare(axiom, df.getOWLSubClassOfAxiom(B, A));
        }
        catch (Exception e) {
            System.out.println("Error in unsaturate left");
            e.printStackTrace();
        }
    }

    @Test
    public void decompose() {
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


        // Left decomposition
        OWLClassExpression ArB = df.getOWLObjectIntersectionOf(A, df.getOWLObjectSomeValuesFrom(R, B));
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(ArB, C);
        man.addAxiom(targetOntology, axiom);
        // Expected axiom
        // B \sqsubseteq D
        axiom = df.getOWLSubClassOfAxiom(B, df.getOWLObjectIntersectionOf(D,E));
        man.addAxiom(targetOntology, axiom);


        // Right decomposition
        OWLClassExpression FrG = df.getOWLObjectIntersectionOf(F, df.getOWLObjectSomeValuesFrom(R, G));
        axiom = df.getOWLSubClassOfAxiom(E, FrG);
        man.addAxiom(targetOntology, axiom);
        // Expected axiom
        // H \sqsubseteq G
        axiom = df.getOWLSubClassOfAxiom(H, G);
        man.addAxiom(targetOntology, axiom);
        try
        {
            // Try two decompositions
            axiom = baseLearner.decomposeLeft(ArB, C);
            System.out.println("Decompose left: " + axiom);

            axiom = baseLearner.decomposeRight(E, FrG);
            System.out.println("Decompose right: " + axiom);
        }
        catch(Exception e)
        {
            System.out.println("Error in decompose");
            e.printStackTrace();
        }

    }

    @Test
    public void saturateWithTreeRight() {

        OWLDataFactory df = man.getOWLDataFactory();
        // Create 6 classes to target ontology
        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLClassExpression ABC = df.getOWLObjectIntersectionOf(A,B,C);

        OWLClass D = df.getOWLClass(IRI.create(":D"));
        OWLClass E = df.getOWLClass(IRI.create(":E"));
        OWLClass F = df.getOWLClass(IRI.create(":F"));
        OWLClassExpression DEF = df.getOWLObjectIntersectionOf(D,E,F);

        // Create and add an axiom to ontology
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(ABC, DEF);
        man.addAxiom(targetOntology, axiom);

        // DEBUG: Look at classes in ontology
        //System.out.println(targetOntology.getClassesInSignature());

        // Begin saturation of inclusion
        // A \sqsubseteq B \sqcap C
        OWLClassExpression BC = df.getOWLObjectIntersectionOf(B,C);
        axiom = df.getOWLSubClassOfAxiom(A, BC);
        man.addAxiom(targetOntology, axiom);

        try
        {
            axiom = baseLearner.saturateRight(A, BC);

            // Expected
            // A \sqsubseteq A \sqcap B \sqcap C \sqcap D \sqcap E \sqcap F
            System.out.println("Saturation: " + axiom);
        }
        catch (Exception e) {
            System.out.println("Error in saturate right");
            e.printStackTrace();
        }

    }


    @Test
    public void learnerSiblingMerge() {
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
        try {
            axiom = baseLearner.mergeRight(left, right);
            System.out.println("Merged: " + axiom);
            if(!axiom.equals(mergedAxiom))
                fail("Did not merge.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void branchLeft() {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass right = A;
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLClass D = df.getOWLClass(IRI.create(":D"));
        // try to branch this expression
        OWLClassExpression left = df.getOWLObjectSomeValuesFrom(R, df.getOWLObjectIntersectionOf(B,C,D));

        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(left, A);
        man.addAxiom(targetOntology, axiom);
        axiom = null;

        // Expected Branched Axiom
        // r.B \sqcap r.C \sqsubseteq A
        OWLSubClassOfAxiom branchedAxiom = df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(df.getOWLObjectSomeValuesFrom(R, D), df.getOWLObjectSomeValuesFrom(R, B), df.getOWLObjectSomeValuesFrom(R, C)), A);
        //System.out.println("Expected: " + branchedAxiom);
        man.addAxiom(targetOntology, branchedAxiom);
        try {
            axiom = baseLearner.branchLeft(left, right);
            System.out.println("Branched: " + axiom);
            if(!axiom.equals(branchedAxiom))
                fail("Did not branch.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void saturateHypothesisRight() {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLClass C = df.getOWLClass(IRI.create(":C"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression right = df.getOWLObjectSomeValuesFrom(R, B);
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(A, df.getOWLObjectIntersectionOf(C, right));
        man.addAxiom(targetOntology, axiom);
        baseLearner.precomputation();

        try {
            OWLSubClassOfAxiom result = baseLearner.saturateRight(A, df.getOWLObjectSomeValuesFrom(R, df.getOWLThing()));
            OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(A, df.getOWLObjectIntersectionOf(C, df.getOWLObjectSomeValuesFrom(R, B)));
            compare(result, target);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @Test
    public void rightDecompositionBecomeEdge() {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression right2 = df.getOWLObjectSomeValuesFrom(R, A);
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(A, right2);
        man.addAxiom(targetOntology, axiom);
        baseLearner.precomputation();

        OWLClassExpression right1 = df.getOWLObjectSomeValuesFrom(R, df.getOWLObjectIntersectionOf(A, right2));
        try {
            OWLSubClassOfAxiom result = baseLearner.decomposeRight(A, df.getOWLObjectIntersectionOf(A, right1));
            OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(A, right2);
            compare(result, target);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    @Test
    public void rightDecompositionNewLeft() {
        OWLDataFactory df = man.getOWLDataFactory();

        OWLClass A = df.getOWLClass(IRI.create(":A"));
        OWLClass B = df.getOWLClass(IRI.create(":B"));
        OWLObjectProperty R = df.getOWLObjectProperty(IRI.create(":r"));

        OWLClassExpression right = df.getOWLObjectSomeValuesFrom(R, B);
        OWLSubClassOfAxiom axiom = df.getOWLSubClassOfAxiom(B, right);
        man.addAxiom(targetOntology, axiom);
        man.addAxiom(targetOntology, df.getOWLSubClassOfAxiom(A, B));
        baseLearner.precomputation();

        try {
            OWLSubClassOfAxiom result = baseLearner.decomposeRight(A, right);
            OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(B, right);
            compare(result, target);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void rightDecompositionDropEdge() {
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

        try {
            OWLSubClassOfAxiom result = baseLearner.decomposeRight(C, df.getOWLObjectIntersectionOf(rA, rB));
            OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(C, df.getOWLObjectIntersectionOf(A, rB));
            compare(result, target);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void saturateHypothesisLeft() {
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

        try {
            OWLSubClassOfAxiom result = baseLearner.decomposeLeft(rB, B);
            OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(C,
                    df.getOWLObjectSomeValuesFrom(R, df.getOWLObjectIntersectionOf(A, B))
            ), B);
            compare(result, target);
        } catch (Exception e) {
            e.printStackTrace();

        }
    }

    @Test
    public void decompositionLeftDropEdge() {
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

        try {
            OWLSubClassOfAxiom result = baseLearner.decomposeLeft(df.getOWLObjectSomeValuesFrom(S,
                    df.getOWLObjectIntersectionOf(A, df.getOWLObjectSomeValuesFrom(R, A))), A);
            OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(sA, A);
            compare(result, target);
        } catch (Exception e) {
            e.printStackTrace();

        }
    }

    @Test
    public void decompositionLeftFindEdge() {
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

        try {
            OWLSubClassOfAxiom result = baseLearner.decomposeLeft(df.getOWLObjectSomeValuesFrom(S,
                    df.getOWLObjectIntersectionOf(A, rA)), A);
            OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(A, rA), B);
            compare(result, target);
        } catch (Exception e) {
            e.printStackTrace();

        }
    }

    @Test
    public void unsaturateLeftExtended() {
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

        try {
            OWLSubClassOfAxiom result = baseLearner.decomposeLeft(df.getOWLObjectSomeValuesFrom(S,
                    df.getOWLObjectIntersectionOf(A, rA)), A);
            OWLSubClassOfAxiom target = df.getOWLSubClassOfAxiom(df.getOWLObjectIntersectionOf(A, rA), B);
            compare(result, target);
        } catch (Exception e) {
            e.printStackTrace();

        }
    }

    private void compare(OWLSubClassOfAxiom value, OWLSubClassOfAxiom expected) {
        compare(value.getSubClass(), expected.getSubClass());
        compare(value.getSuperClass(), expected.getSuperClass());
    }

    private void compare(OWLClassExpression value, OWLClassExpression expected) {
        try {
            assertThat(new ELTree(value).equals(new ELTree(expected)), is(true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}