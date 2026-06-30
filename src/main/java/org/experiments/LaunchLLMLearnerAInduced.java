package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.pac.Pac;
import org.sampler.ABoxInducedSubsumptionSampler;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxParserImpl;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.evaluation.Evaluation;

public class LaunchLLMLearnerAInduced extends LaunchLLMLearner {

    private ABoxInducedSubsumptionSampler aboxSampler = null;
    private Set<OWLClassExpression> aInducedBaseSet = null;
    private OWLReasoner aInducedInitialReasoner = null;
    private int counterExampleCount = 0;

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchLLMLearnerAInduced().run(args);
    }

    @Override
    public void run(String[] args) {
        String configurationFile = args[0];
        if (args.length > 1) epsilon = Double.parseDouble(args[1]);
        if (args.length > 2) delta = Double.parseDouble(args[2]);

        org.experiments.logger.SmartLogger.checkCachedFiles();
        loadConfiguration(configurationFile);
        try {
            for (String ontology : ontologies) {
                System.out.println("\nRunning experiment (A-induced) for " + ontology);
                for (String model : models) {
                    System.out.println("\nRunning experiment for " + model + "\n");
                    setup(ontology, model.replace(":", "-"));
                    elQueryEngineForH = new org.exactlearner.engine.ELEngine(hypothesisOntology);
                    String ontologyShortName = ontology.substring(ontology.lastIndexOf("/") + 1, ontology.lastIndexOf("."));
                    createWorkCounter(ontologyShortName, model);
                    conceptRelation = new org.exactlearner.learner.ConceptRelation<>();
                    setLLMEngine(model, ontologyShortName);
                    learner = new org.exactlearner.learner.Learner(llmQueryEngineForT, elQueryEngineForH, myMetrics, conceptRelation);
                    oracle = new org.exactlearner.oracle.Oracle(llmQueryEngineForT, elQueryEngineForH);

                    aboxSampler = null;
                    counterExampleCount = 0;

                    runLearningExperiment(args, hypothesisSizes.get(ontologies.indexOf(ontology)), model);

                    try {
                        evaluateWithBaris();
                    } catch (Exception ex) {
                        System.out.println("Errore durante evaluateWithBaris: " + ex.getMessage());
                        ex.printStackTrace();
                    }

                    if (counter != null) counter.close();
                    cleaningUp();
                }
                System.out.println("\nFinished experiment for " + ontology + "\n");
            }
        } catch (Throwable e) {
            e.printStackTrace();
            System.out.println("error" + e);
        }
    }

    @Override
    protected OWLSubClassOfAxiom getCounterExample(Pac pac) throws Exception {
        if (aboxSampler == null) {
            initAboxSampler();
            if (aboxSampler == null) {
                System.out.println("Setup AInduced non disponibile per questa ontologia — fallback a PAC uniforme");
                return super.getCounterExample(pac);
            }
        }

        while (pac.getNumberOfProvidedSamples() < pac.getNumberOfSamples()) {
            pac.incrementProvidedSamples();
            OWLSubClassOfAxiom selectedAxiom = aboxSampler.sample();
            if (selectedAxiom == null) return null;
            if (pac.getNumberOfProvidedSamples() <= 10) {
                System.out.println("DEBUG sampled: " + selectedAxiom.getSubClass() + " SubClassOf " + selectedAxiom.getSuperClass());
            }
            boolean entH = elQueryEngineForH.entailed(selectedAxiom);
            boolean entT = llmQueryEngineForT.entailed(selectedAxiom);
            if (pac.getNumberOfProvidedSamples() <= 10) {
                System.out.println("DEBUG entH=" + entH + " entT(Mistral)=" + entT);
            }
            if (!entH && entT) {
                counterExampleCount++;
                return getCounterExampleSubClassOf(selectedAxiom);
            }
        }
        return null;
    }

    @Override
    protected void setUpOntologyFolders(String format, String system, String model, String ontology) {
        String name = java.nio.file.Path.of(ontology).getFileName().toString().replace(".owl", "");
        // estraiamo l'identificativo del baseSet (c1/c2/c3) dal nome della cartella padre
        String parentFolder = java.nio.file.Path.of(ontology).getParent().getFileName().toString();
        String baseSetTag;
        if (parentFolder.contains("exists_thing")) baseSetTag = "c2";
        else if (parentFolder.contains("exists_partial")) baseSetTag = "c3";
        else if (parentFolder.contains("class_names")) baseSetTag = "c1";
        else baseSetTag = parentFolder;

        String nameWithBaseSet = name + "_" + baseSetTag;
        ontologyFolder = "results" + java.io.File.separator + "ontologies" + java.io.File.separator + "target_" + nameWithBaseSet + ".owl";
        ontologyFolderH = "results" + java.io.File.separator + "ontologies" + java.io.File.separator + infoString(nameWithBaseSet, model, format, system) + ".owl";
    }

    private void initAboxSampler() throws Exception {
        File targetDir = targetFile.getParentFile();
        File initialOntologyFile = new File(targetDir, "initialOntology.owl");
        File baseSetFile = new File(targetDir, "baseSet");

        if (!initialOntologyFile.exists() || !baseSetFile.exists()) {
            return;
        }

        OWLOntologyManager initManager = OWLManager.createOWLOntologyManager();
        OWLOntology initialOntology = initManager.loadOntologyFromOntologyDocument(initialOntologyFile);

        // Fedele a PACloOracle.java: l'ABox vero viene iniettato a runtime
        // prendendolo da groundTruthOntology (il nostro expertOntology.owl)
        initialOntology.add(groundTruthOntology.getABoxAxioms(org.semanticweb.owlapi.model.parameters.Imports.INCLUDED));

        org.semanticweb.elk.owlapi.ElkReasonerFactory rf = new org.semanticweb.elk.owlapi.ElkReasonerFactory();
        OWLReasoner initialOntologyReasoner = rf.createReasoner(initialOntology);
        initialOntologyReasoner.precomputeInferences(
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);

        Set<OWLClassExpression> baseSet = readBaseSet(baseSetFile, initialOntology);

        OWLDataFactory df = OWLManager.getOWLDataFactory();
        aboxSampler = new ABoxInducedSubsumptionSampler(baseSet, initialOntologyReasoner, df);

        this.aInducedBaseSet = baseSet;
        this.aInducedInitialReasoner = initialOntologyReasoner;

        System.out.println("AInduced sampler pronto. BaseSet: " + baseSet.size() +
                " | Individui in initialOntology: " + initialOntology.getIndividualsInSignature().size());

        int withTypes = 0;
        for (org.semanticweb.owlapi.model.OWLNamedIndividual ind : initialOntology.getIndividualsInSignature()) {
            for (OWLClassExpression ce : baseSet) {
                if (initialOntologyReasoner.getInstances(ce).containsEntity(ind)) {
                    withTypes++;
                    break;
                }
            }
        }
        System.out.println("DEBUG: individui con almeno un tipo nel baseSet: " + withTypes + " su " + initialOntology.getIndividualsInSignature().size());
    }

    private Set<OWLClassExpression> readBaseSet(File f, OWLOntology referenceOntology) throws Exception {
        OWLOntologyManager om = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();

        Set<OWLClassExpression> baseSet = new HashSet<>();
        ManchesterOWLSyntaxParser parser = new ManchesterOWLSyntaxParserImpl(om.getOntologyConfigurator(), df);
        parser.setDefaultOntology(referenceOntology);

        final Map<String, OWLEntity> map = new HashMap<>();
        referenceOntology.signature().forEach(x -> map.put(x.getIRI().getFragment(), x));
        parser.setOWLEntityChecker(new OWLEntityChecker() {
            private <T> T v(String name, Class<T> t) {
                OWLEntity e = map.get(name);
                if (t.isInstance(e)) return t.cast(e);
                return null;
            }
            @Override public OWLObjectProperty getOWLObjectProperty(String name) { return v(name, OWLObjectProperty.class); }
            @Override public OWLNamedIndividual getOWLIndividual(String name) { return v(name, OWLNamedIndividual.class); }
            @Override public OWLDatatype getOWLDatatype(String name) { return v(name, OWLDatatype.class); }
            @Override public OWLDataProperty getOWLDataProperty(String name) { return v(name, OWLDataProperty.class); }
            @Override public OWLClass getOWLClass(String name) { return v(name, OWLClass.class); }
            @Override public OWLAnnotationProperty getOWLAnnotationProperty(String name) { return v(name, OWLAnnotationProperty.class); }
        });

        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line = reader.readLine();
            while (line != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    line = reader.readLine();
                    continue;
                }
                OWLClassExpression clsExpr;
                if (line.equals("owl:Nothing")) {
                    clsExpr = df.getOWLNothing();
                } else {
                    parser.setStringToParse(line);
                    clsExpr = parser.parseClassExpression();
                }
                baseSet.add(clsExpr);
                line = reader.readLine();
            }
        }
        return baseSet;
    }

    /**
     * Valuta la hypothesisOntology imparata usando la stessa logica (Macro/Micro Precision/Recall)
     * della classe Evaluation.java di paclo (Baris). Da chiamare DOPO che il run e' completato,
     * cioe' dopo che hypothesisOntology e' stata popolata e salvata.
     */
    public void evaluateWithBaris() throws Exception {
        if (aInducedBaseSet == null || aInducedInitialReasoner == null) {
            System.out.println("Valutazione Baris non disponibile: sampler AInduced non inizializzato per questo run.");
            return;
        }
        org.semanticweb.elk.owlapi.ElkReasonerFactory rf = new org.semanticweb.elk.owlapi.ElkReasonerFactory();
        OWLReasoner expertReasoner = rf.createReasoner(groundTruthOntology);
        expertReasoner.precomputeInferences(
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS,
            org.semanticweb.owlapi.reasoner.InferenceType.OBJECT_PROPERTY_HIERARCHY,
            org.semanticweb.owlapi.reasoner.InferenceType.OBJECT_PROPERTY_ASSERTIONS);

        System.out.println("=== VALUTAZIONE BARIS (Macro/Micro Precision/Recall) ===");
        System.out.println("Counterexamples trovati durante il run: " + counterExampleCount);
        new Evaluation().evaluate(hypothesisOntology, expertReasoner, aInducedBaseSet, aInducedInitialReasoner);
    }

    public int getCounterExampleCount() {
        return counterExampleCount;
    }
}
