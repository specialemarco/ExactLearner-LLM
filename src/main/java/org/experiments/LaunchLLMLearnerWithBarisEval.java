package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.evaluation.Evaluation;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxParserImpl;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Identica a LaunchLLMLearner (PAC uniforme, sampling invariato).
 * Aggiunge SOLO la valutazione finale con le metriche di Baris (Macro/Micro Precision/Recall),
 * per poter confrontare PAC uniforme e A-induced con lo stesso identico metro di giudizio.
 */
public class LaunchLLMLearnerWithBarisEval extends LaunchLLMLearner {

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchLLMLearnerWithBarisEval().run(args);
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
                System.out.println("\nRunning experiment (PAC uniforme + Baris eval) for " + ontology);
                for (String model : models) {
                    System.out.println("\nRunning experiment for " + model + "\n");
                    setup(ontology, model.replace(":", "-"));
                    elQueryEngineForH = new org.exactlearner.engine.ELEngine(hypothesisOntology);
                    String ontologyShortName = ontology.substring(ontology.lastIndexOf("/") + 1, ontology.lastIndexOf("."));
                    createWorkCounter(ontologyShortName, model);
                    conceptRelation = new org.exactlearner.learner.ConceptRelation<>();
                    setLLMEngine(model, ontologyShortName);
                    learner = new org.exactlearner.learner.Learner(llmQueryEngineForT, elQueryEngineForH, myMetrics, conceptRelation);
                    installDecomposePrefetcher(model);
                    oracle = new org.exactlearner.oracle.Oracle(llmQueryEngineForT, elQueryEngineForH);

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

    /**
     * Costruisce baseSet/initialOntologyReasoner SOLO per la valutazione finale
     * (qui non viene usato per il sampling, che resta quello uniforme di Pac).
     */
    private void evaluateWithBaris() throws Exception {
        File targetDir = targetFile.getParentFile();
        File initialOntologyFile = new File(targetDir, "initialOntology.owl");
        File baseSetFile = new File(targetDir, "baseSet");

        if (!initialOntologyFile.exists() || !baseSetFile.exists()) {
            System.out.println("Valutazione Baris non disponibile: initialOntology.owl o baseSet non trovati in " + targetDir);
            return;
        }

        OWLOntologyManager initManager = OWLManager.createOWLOntologyManager();
        OWLOntology initialOntology = initManager.loadOntologyFromOntologyDocument(initialOntologyFile);
        initialOntology.add(groundTruthOntology.getABoxAxioms(Imports.INCLUDED));

        ElkReasonerFactory rf = new ElkReasonerFactory();
        OWLReasoner initialOntologyReasoner = rf.createReasoner(initialOntology);
        initialOntologyReasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);

        Set<OWLClassExpression> baseSet = readBaseSet(baseSetFile, initialOntology);

        OWLReasoner expertReasoner = rf.createReasoner(groundTruthOntology);
        expertReasoner.precomputeInferences(
            InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS,
            InferenceType.OBJECT_PROPERTY_HIERARCHY, InferenceType.OBJECT_PROPERTY_ASSERTIONS);

        System.out.println("=== VALUTAZIONE BARIS (Macro/Micro Precision/Recall) — PAC uniforme ===");
        new Evaluation().evaluate(hypothesisOntology, expertReasoner, baseSet, initialOntologyReasoner);
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
}
