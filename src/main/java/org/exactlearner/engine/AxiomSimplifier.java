package org.exactlearner.engine;

import org.exactlearner.learner.ConceptRelation;
import org.exactlearner.tree.ELNode;
import org.exactlearner.tree.ELTree;
import org.semanticweb.owlapi.model.*;

import java.util.*;

public class AxiomSimplifier {
    private final BaseEngine engine;
    private final ConceptRelation<OWLClass> classRelation;

    public AxiomSimplifier(BaseEngine engine) {
        this(engine, null);
    }

    public AxiomSimplifier(BaseEngine engine, ConceptRelation<OWLClass> classRelation) {
        this.engine = engine;
        this.classRelation = classRelation;
    }

    public Optional<OWLSubClassOfAxiom> shorten(OWLSubClassOfAxiom axiom) {

        if (engine.entailed(axiom)) {
            return Optional.empty();
        }

        if (System.getenv("EXACTLEARNER_SPLIT") == null || System.getenv("EXACTLEARNER_SPLIT").equals("true")) {
            if (axiom.getSuperClass() instanceof OWLObjectIntersectionOf intersection) {
                Set<OWLClassExpression> unknown = new HashSet<>();
                OWLClassExpression expression = axiom.getSubClass();
                for (OWLClassExpression sup : intersection.getOperands()) {
                    OWLSubClassOfAxiom ax = engine.getSubClassAxiom(expression, sup);
                    if (!engine.entailed(ax)) {
                        unknown.add(sup);
                    }
                }
                if (!unknown.isEmpty()) {
                    axiom = engine.getSubClassAxiom(expression, engine.getOWLObjectIntersectionOf(unknown));
                } else {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(desaturate(axiom));
    }

    private OWLSubClassOfAxiom desaturate(OWLSubClassOfAxiom axiom) {
        if (System.getenv("EXACTLEARNER_DESATURATE") == null || System.getenv("EXACTLEARNER_DESATURATE").equals("true")) {
            ELTree left;
            ELTree right;
            try {
                left = new ELTree(axiom.getSubClass());
                right = new ELTree(axiom.getSuperClass());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            axiom = engine.getSubClassAxiom(
                    removeConcepts(left).transformToClassExpression(),
                    removeConcepts(right).transformToClassExpression());
        }
        return axiom;
    }

    private ELTree removeConcepts(ELTree tree) {
        try {
            ELTree simp = new ELTree(tree);
            for (ELNode node : simp.getNodes()) {
                List<OWLClass> classes =  new ArrayList<>(node.getLabel());
                if (classRelation != null) {
                    classes = removeKnownConcepts(classes);
                    classes = classRelation.topologicalOrder(classes);
                    Collections.reverse(classes);
                }
                for (OWLClass c : classes) {
                    node.remove(c);
                    if (!equivalent(tree, simp)) {
                        node.extendLabel(c);
                    }
                }
            }
            return simp;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<OWLClass> removeKnownConcepts(List<OWLClass> classes) {
        List<OWLClass> result = new LinkedList<>(classes);
        List<OWLClass> untested = new LinkedList<>(classes);
        while (!untested.isEmpty()) {
            OWLClass u = untested.remove(0);
            List<OWLClass> c = classRelation.getAllAncestorsAndEqual(u);
            result.removeAll(c);
            untested.removeAll(c);
        }
        return result;
    }

    private boolean equivalent(ELTree tree1, ELTree tree2) {
        OWLEquivalentClassesAxiom eq = engine.getOWLEquivalentClassesAxiom(
                tree1.transformToClassExpression(),
                tree2.transformToClassExpression());
        return engine.entailed(eq);
    }
}
