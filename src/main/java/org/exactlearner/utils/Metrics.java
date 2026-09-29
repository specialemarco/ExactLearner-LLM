package org.exactlearner.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Set;

import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

public class Metrics {
    private final OWLObjectRenderer myRenderer;
    private int membCount = 0;
    private int equivCount = 0;
    private int sizeOfTargetLargestConcept = 0;
    private int sizeOfHypothesisLargestConcept = 0;
    private int sizeOfLargestCounterExample = 0;
    private int sizeOfHypothesis = 0;
    private int sizeOfTarget = 0;

    public Metrics(OWLObjectRenderer renderer) {
        this.myRenderer = renderer;
    }

    // Size = the number of concept and role names in the Manchester rendering.
    // Until 2026-09-29 this split on single spaces, so the gap left by the
    // removed SubClassOf/EquivalentTo keyword counted as two extra words (+2 per
    // axiom), and axioms were picked by matching that keyword in the text, which
    // also counted EquivalentObjectProperties. OWL2Bench's Size of T went from
    // 592 to 304 with the fix; sizes in earlier logs and CSVs are not comparable.
    private int sizeOfCIT(Set<OWLLogicalAxiom> axSet) {
        int ontSize = 0;
        for (OWLAxiom axe : axSet) {
            ontSize += getSizeOfCounterexample((OWLLogicalAxiom) axe);
        }
        return ontSize;
    }

    private static int countNames(String rendered) {
        String trimmed = rendered.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    private int sizeOfConcept(Set<OWLLogicalAxiom> axSet) {
        int largestConceptSize = 0;

        for (OWLAxiom axe : axSet) {
            if (axe.isOfType(AxiomType.SUBCLASS_OF)) {
                OWLSubClassOfAxiom axiom = (OWLSubClassOfAxiom) axe;
                axiom.getSubClass();

                String left = myRenderer.render(axiom.getSubClass());
                String right = myRenderer.render(axiom.getSuperClass());

                left = left.replaceAll(" and ", " ");
                left = left.replaceAll(" some ", " ");

                if (countNames(left) > largestConceptSize) {
                    largestConceptSize = countNames(left);
                }

                right = right.replaceAll(" and ", " ");
                right = right.replaceAll(" some ", " ");
                if (countNames(right) > largestConceptSize) {
                    largestConceptSize = countNames(right);
                }

            }
            if (axe.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
                OWLEquivalentClassesAxiom axiom = (OWLEquivalentClassesAxiom) axe;
                String concept;
                for (OWLClassExpression exp : axiom.getClassExpressions()) {
                    concept = myRenderer.render(exp);
                    concept = concept.replaceAll(" and ", " ");
                    concept = concept.replaceAll(" some ", " ");
                    if (countNames(concept) > largestConceptSize)
                        largestConceptSize = countNames(concept);
                }
            }
        }
        return largestConceptSize;

    }

    public ArrayList<String> getSuggestionNames(String s, File newFile) throws IOException {

        ArrayList<String> names = new ArrayList<>();

        FileInputStream in = new FileInputStream(newFile);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        String line = reader.readLine();
        if (s.equals("concept")) {
            while (line != null) {
                if (line.startsWith("Class:")) {
                    String conceptName = line.substring(7);
                    if (!conceptName.equals("owl:Thing")) {
                        names.add(conceptName);
                    }
                }
                line = reader.readLine();
            }
        } else if (s.equals("role")) {
            while (line != null) {
                if (line.startsWith("ObjectProperty:")) {
                    String roleName = line.substring(16);
                    names.add(roleName);

                }

                line = reader.readLine();
            }
        }
        reader.close();
        return names;
    }

    public int getMembCount() {
        return membCount;
    }

    public void setMembCount(int membCount) {
        this.membCount = membCount;
    }

    public int getEquivCount() {
        return equivCount;
    }

    public void setEquivCount(int equivCount) {
        this.equivCount = equivCount;
    }

    public int getSizeOfTargetLargestConcept() {
        return sizeOfTargetLargestConcept;
    }

    private void setSizeOfTargetLargestConcept(int sizeOfLargestConcept) {
        this.sizeOfTargetLargestConcept = sizeOfLargestConcept;
    }

    public int getSizeOfHypothesisLargestConcept() {
        return sizeOfHypothesisLargestConcept;
    }

    private void setSizeOfHypothesisLargestConcept(int sizeOfLargestConcept) {
        this.sizeOfHypothesisLargestConcept = sizeOfLargestConcept;
    }

    public int getSizeOfHypothesis() {
        return sizeOfHypothesis;
    }

    public int getSizeOfTarget() {
        return sizeOfTarget;
    }

    private void setSizeOfTarget(int sizeOfTarget) {
        this.sizeOfTarget = sizeOfTarget;
    }

    public void computeTargetSizes(OWLOntology ontology) {
        Set<OWLLogicalAxiom> logicalAxioms = ontology.getLogicalAxioms();
        this.setSizeOfTarget(sizeOfCIT(logicalAxioms));
        this.setSizeOfTargetLargestConcept(sizeOfConcept(logicalAxioms));
    }

    public void computeHypothesisSizes(OWLOntology ontology) {
        Set<OWLLogicalAxiom> logicalAxioms = ontology.getLogicalAxioms();
        this.sizeOfHypothesis = sizeOfCIT(logicalAxioms);
        this.setSizeOfHypothesisLargestConcept(sizeOfConcept(logicalAxioms));
    }


    public int getSizeOfLargestCounterExample() {
        return sizeOfLargestCounterExample;
    }

    public void setSizeOfLargestCounterExample(int sizeOfLargestCounterExample) {
        this.sizeOfLargestCounterExample = sizeOfLargestCounterExample;
    }

    /** The size of a SubClassOf or EquivalentClasses axiom; 0 for any other type. */
    public int getSizeOfCounterexample(OWLLogicalAxiom axe) {
        if (!axe.isOfType(AxiomType.SUBCLASS_OF) && !axe.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
            return 0;
        }
        String inclusion = myRenderer.render(axe);
        inclusion = inclusion.replaceAll(" and ", " ");
        inclusion = inclusion.replaceAll(" some ", " ");
        inclusion = inclusion.replaceAll("SubClassOf", " ");
        inclusion = inclusion.replaceAll("EquivalentTo", " ");
        return countNames(inclusion);
    }
}
