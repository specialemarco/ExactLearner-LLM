package org.experiments;

import org.exactlearner.engine.AxiomSimplifier;
import org.exactlearner.engine.BaseEngine;
import org.exactlearner.learner.ConceptRelation;
import org.exactlearner.learner.Learner;
import org.exactlearner.oracle.Oracle;
import org.exactlearner.parser.OWLParser;
import org.exactlearner.parser.OWLParserImpl;
import org.exactlearner.utils.Metrics;
import org.pac.Pac;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public abstract class LaunchLearner {

    protected int conceptNumber;
    protected int roleNumber;
    protected File targetFile;
    protected String ontologyFolder = "";
    protected BaseEngine llmQueryEngineForT;
    protected BaseEngine elQueryEngineForH;

    protected OWLClassExpression lastExpression;
    protected OWLSubClassOfAxiom counterExample;
    protected OWLOntology groundTruthOntology;
    protected OWLOntology hypothesisOntology;
    protected Learner learner;
    protected File hypoFile;
    protected OWLParser parser;
    protected String ontologyFolderH = "";
    protected Oracle oracle = null;
    protected OWLClass lastName = null;
    protected Set<OWLAxiom> axiomsT = new HashSet<>();

    protected final static OWLOntologyManager myManager = OWLManager.createOWLOntologyManager();
    final static OWLObjectRenderer myRenderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
    protected final static String fileSeparator = System.getProperty("file.separator");
    protected Metrics myMetrics = new Metrics(myRenderer);

    protected ConceptRelation<OWLClass> conceptRelation;

    protected void validation() throws Exception {
        // validateLearnedOntology();
        printVictoryMessage();
    }

    private void validateLearnedOntology() {
        if (!elQueryEngineForH.entailed(axiomsT)) {
            // throw new Exception("Something went horribly wrong!");
            System.out.println("Something went horribly wrong!");
        }
    }

    private void printVictoryMessage() {
        System.out.println("\nOntology learned successfully!");
        System.out.println("Congratulations!");
    }

    void cleaningUp() {
        llmQueryEngineForT.disposeOfReasoner();
        //llmQueryEngineForH.disposeOfReasoner();
        elQueryEngineForH.disposeOfReasoner();
        //llmQueryEngineForH.disposeOfReasoner();
        myManager.removeOntology(hypothesisOntology);
        myManager.removeOntology(groundTruthOntology);
    }

    protected void checkTransformations() throws Exception {
        if (canTransformELrhs()) {
            processRightHandSideTransformations();
        } else if (canTransformELlhs()) {
            processLeftHandSideTransformations();
        } else {
            handleNoTransformation();
        }
    }

    private void processRightHandSideTransformations() throws Exception {
        counterExample = computeEssentialRightCounterexample();
        OWLClass left = (OWLClass) counterExample.getSubClass();
        boolean mergable = false;
        for (OWLSubClassOfAxiom ax : elQueryEngineForH.getOntology().getSubClassAxiomsForSubClass(lastName)) {
            if (ax.getSubClass().getClassExpressionType() == ClassExpressionType.OWL_CLASS &&
                    ax.getSubClass().equals(lastName) && !ax.getSuperClass().isOWLClass()
            ) {
                mergable = true;
                Set<OWLClassExpression> mySet = new HashSet<>(ax.getSuperClass().asConjunctSet());
                mySet.addAll(lastExpression.asConjunctSet());
                lastExpression = llmQueryEngineForT.getOWLObjectIntersectionOf(mySet);
                counterExample = llmQueryEngineForT.getSubClassAxiom(lastName, lastExpression);
            }
        }
        if (mergable) {
            counterExample = computeEssentialRightCounterexample();
            if (!left.equals(counterExample.getSubClass())) {
                System.out.println("Something went horribly wrong!");
            }
        }
        addHypothesis(counterExample);
    }

    private void processLeftHandSideTransformations() throws Exception {
        counterExample = computeEssentialLeftCounterexample();
        addHypothesis(counterExample);
    }

    private void handleNoTransformation() {
        addHypothesis(counterExample);
        System.out.println("Not an EL Terminology:" + counterExample.getSubClass() + " SubclassOf " + counterExample.getSuperClass());
    }

    void addHypothesis(OWLAxiom addedAxiom) {
        if (addedAxiom instanceof OWLSubClassOfAxiom a) {
            Optional<OWLSubClassOfAxiom> axiom = new AxiomSimplifier(elQueryEngineForH, conceptRelation).shorten(a);
            if (axiom.isEmpty()) {
                return;
            }
            addedAxiom = axiom.get();
        }
        System.out.println(new ManchesterOWLSyntaxOWLObjectRendererImpl().render(addedAxiom));
        myManager.addAxiom(hypothesisOntology, addedAxiom);
    }

    private Boolean canTransformELrhs() {
        OWLSubClassOfAxiom counterexample = counterExample;
        OWLClassExpression left = counterexample.getSubClass();
        OWLClassExpression right = counterexample.getSuperClass();
        for (OWLClass cl1 : left.getClassesInSignature()) {
            if (oracle.isCounterExample(cl1, right)) {
                counterExample = llmQueryEngineForT.getSubClassAxiom(cl1, right);
                lastExpression = right;
                lastName = cl1;
                return true;
            }
        }
        return false;
    }

    private Boolean canTransformELlhs() {
        OWLSubClassOfAxiom counterexample = counterExample;
        OWLClassExpression left = counterexample.getSubClass();
        OWLClassExpression right = counterexample.getSuperClass();
        for (OWLClass cl1 : right.getClassesInSignature()) {
            if (oracle.isCounterExample(left, cl1)) {
                counterExample = llmQueryEngineForT.getSubClassAxiom(left, cl1);
                lastExpression = left;
                lastName = cl1;
                return true;
            }
        }
        return false;
    }

    private OWLSubClassOfAxiom computeEssentialLeftCounterexample() throws Exception {
        OWLSubClassOfAxiom axiom = counterExample;

        OWLClass oldClass = null;
        OWLClassExpression oldExpression = null;

        lastExpression = axiom.getSubClass();
        lastName = (OWLClass) axiom.getSuperClass();

        //while (!lastName.equals(oldClass) || !lastExpression.equals(oldExpression)) {
            oldClass = lastName;
            oldExpression = lastExpression;

            axiom = learner.decomposeLeft(lastExpression, lastName);
            lastExpression = axiom.getSubClass();
            lastName = (OWLClass) axiom.getSuperClass();

            axiom = learner.branchLeft(lastExpression, lastName);
            lastExpression = axiom.getSubClass();
            lastName = (OWLClass) axiom.getSuperClass();

            axiom = learner.unsaturateLeft(lastExpression, lastName);
            lastExpression = axiom.getSubClass();
            lastName = (OWLClass) axiom.getSuperClass();
        //}

        return axiom;
    }

    private OWLSubClassOfAxiom computeEssentialRightCounterexample() throws Exception {
        int changed = -1;
        OWLSubClassOfAxiom axiom = counterExample;

        OWLClass oldLeft = null;
        OWLClassExpression oldRight = null;
        lastName = (OWLClass) axiom.getSubClass();
        lastExpression = axiom.getSuperClass();

        axiom = learner.decomposeRight(lastName, lastExpression);
        lastName = (OWLClass) axiom.getSubClass();
        lastExpression = axiom.getSuperClass();

        //while (!lastName.equals(oldLeft) || !lastExpression.equals(oldRight)) {
            changed++;
            oldLeft = lastName;
            oldRight = lastExpression;

            axiom = learner.saturateRight(lastName, lastExpression);
            lastName = (OWLClass) axiom.getSubClass();
            lastExpression = axiom.getSuperClass();

            axiom = learner.decomposeRight(lastName, lastExpression);
            lastName = (OWLClass) axiom.getSubClass();
            lastExpression = axiom.getSuperClass();

            axiom = learner.mergeRight(lastName, lastExpression);
            lastName = (OWLClass) axiom.getSubClass();
            lastExpression = axiom.getSuperClass();
        //}

        if (changed > 0) {
            System.out.println("It did change! Count: " + changed);
        }

        return axiom;
    }

    protected void loadTargetOntology(String ontology) throws OWLOntologyCreationException, IOException {
        targetFile = new File(ontology);
        groundTruthOntology = myManager.loadOntologyFromOntologyDocument(targetFile);
        for (OWLAxiom axe : groundTruthOntology.getLogicalAxioms())
            if (axe.isOfType(AxiomType.SUBCLASS_OF) || axe.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
                axiomsT.add(axe);
            }
        parser = new OWLParserImpl(groundTruthOntology);
    }

    void saveTargetOntology() throws OWLOntologyStorageException, IOException {
        OWLDocumentFormat format = myManager.getOntologyFormat(groundTruthOntology);
        ManchesterSyntaxDocumentFormat manSyntaxFormat = new ManchesterSyntaxDocumentFormat();
        if (format.isPrefixOWLDocumentFormat()) {
            manSyntaxFormat.copyPrefixesFrom(format.asPrefixOWLDocumentFormat());
        }

        File newFile = new File(ontologyFolder);
        if (newFile.exists()) {
            newFile.delete();
        }
        // Create ontologies directory if it does not exist
        if (!newFile.getParentFile().exists()) {
            newFile.getParentFile().mkdirs();
        }
        newFile.createNewFile();
        myManager.saveOntology(groundTruthOntology, manSyntaxFormat, IRI.create(newFile.toURI()));
    }

    protected void loadHypothesisOntology() throws OWLOntologyCreationException, IOException {
        hypoFile = new File(ontologyFolderH);
        if (hypoFile.exists()) {
            hypoFile.delete();
        }
        hypoFile.createNewFile();

        hypothesisOntology = myManager.loadOntologyFromOntologyDocument(hypoFile);
    }

    protected void setUpOntologyFolders(String format, String system, String model, String ontology) {
        String name = Path.of(ontology).getFileName().toString().replace(".owl", "");

        ontologyFolder = "results" + fileSeparator + "ontologies" + fileSeparator + "target_" + name + ".owl";
        ontologyFolderH = "results" + fileSeparator + "ontologies" + fileSeparator + infoString(name, model, format, system) + ".owl";
    }

    protected String infoString(String ontology, String model, String format, String system) {
        String systemType = "advanced";
        if (system.trim().equals("Answer with only True or False.")) {
            systemType = "base";
        }
        return ontology + "_" + model + "_" + format + "_" + systemType;
    }

    protected void computeConceptAndRoleNumbers() throws IOException {
        ArrayList<String> concepts = myMetrics.getSuggestionNames("concept", new File(ontologyFolder));
        ArrayList<String> roles = myMetrics.getSuggestionNames("role", new File(ontologyFolder));

        this.conceptNumber = concepts.size();
        this.roleNumber = roles.size();
    }

    protected void saveOWLFile(OWLOntology ontology, File file) throws Exception {
        //learner.minimiseHypothesis(elQueryEngineForH, hypothesisOntology);
        addLabelsHypothesisOntology();
        OWLDocumentFormat format = myManager.getOntologyFormat(ontology);
        ManchesterSyntaxDocumentFormat manSyntaxFormat = new ManchesterSyntaxDocumentFormat();
        if (format.isPrefixOWLDocumentFormat()) {
            // need to remove prefixes
            manSyntaxFormat.clear();
        }
        myManager.saveOntology(ontology, manSyntaxFormat, IRI.create(file.toURI()));
    }

    protected void addLabelsHypothesisOntology() {
        Set<IRI> iris = hypothesisOntology.getClassesInSignature().stream().map(OWLClass::getIRI).collect(Collectors.toSet());
        iris.addAll(hypothesisOntology.getObjectPropertiesInSignature().stream().map(OWLObjectProperty::getIRI).collect(Collectors.toSet()));

        for (IRI iri : iris) {
            for (OWLAnnotationAssertionAxiom a : groundTruthOntology.getAnnotationAssertionAxioms(iri)) {
                if (a != null && a.getProperty().isLabel() && a.getValue() instanceof OWLLiteral val) {
                    myManager.addAxiom(hypothesisOntology, a);
                }
            }
        }
    }

    protected OWLSubClassOfAxiom getCounterExample(Pac pac) throws Exception {
        while (pac.getNumberOfProvidedSamples() < pac.getNumberOfSamples()) {
            System.out.println("PAC Training sample: " + (int) (pac.getNumberOfProvidedSamples() + 1) + " out of " + pac.getNumberOfSamples());
            // Get the last counterexample
            OWLSubClassOfAxiom selectedAxiom = pac.getRandomStatement();

            if (selectedAxiom == null) {
                System.out.println("PAC Algorithm completed");
                return null;
            }
            if (selectedAxiom.isOfType(AxiomType.SUBCLASS_OF)) {
                if (!elQueryEngineForH.entailed(selectedAxiom) && llmQueryEngineForT.entailed(selectedAxiom)) {
                    return getCounterExampleSubClassOf(selectedAxiom);
                }
            } else if (selectedAxiom.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
                OWLEquivalentClassesAxiom equivCounterexample = (OWLEquivalentClassesAxiom) selectedAxiom;
                Collection<OWLSubClassOfAxiom> eqSubClassAxioms = equivCounterexample.asOWLSubClassOfAxioms();
                for (OWLSubClassOfAxiom subClassAxiom : eqSubClassAxioms) {
                    if (!elQueryEngineForH.entailed(subClassAxiom) && llmQueryEngineForT.entailed(selectedAxiom)) {
                        return getCounterExampleSubClassOf(subClassAxiom);
                    }
                }
            } else {
                throw new Exception("Unknown axiom type: " + selectedAxiom.getAxiomType() + "You must delete unknown axioms FIRST!");
            }
        }
        return null;
    }

    protected OWLSubClassOfAxiom getCounterExampleSubClassOf(OWLSubClassOfAxiom counterexample) throws Exception {
        OWLSubClassOfAxiom newCounterexampleAxiom;
        OWLClassExpression left = counterexample.getSubClass();
        OWLClassExpression right = counterexample.getSuperClass();
        double p = 0;

        newCounterexampleAxiom = oracle.mergeLeft(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.saturateLeft(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.branchRight(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.composeLeft(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.composeRight(left, right, p);
        left = newCounterexampleAxiom.getSubClass();
        right = newCounterexampleAxiom.getSuperClass();

        newCounterexampleAxiom = oracle.unsaturateRight(left, right, p);

        return newCounterexampleAxiom;
    }
}
