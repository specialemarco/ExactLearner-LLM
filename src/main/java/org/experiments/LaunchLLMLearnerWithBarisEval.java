package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.evaluation.Evaluation;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.utility.PacloDataset;

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
        PacloDataset dataset = PacloDataset.loadBeside(targetFile, groundTruthOntology);
        if (dataset == null) {
            System.out.println("Valutazione Baris non disponibile: initialOntology.owl o baseSet non trovati accanto a " + targetFile);
            return;
        }

        OWLReasoner expertReasoner = new ElkReasonerFactory().createReasoner(groundTruthOntology);
        expertReasoner.precomputeInferences(
            InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS,
            InferenceType.OBJECT_PROPERTY_HIERARCHY, InferenceType.OBJECT_PROPERTY_ASSERTIONS);

        System.out.println("=== VALUTAZIONE BARIS (Macro/Micro Precision/Recall) \u2014 PAC uniforme ===");
        new Evaluation().evaluate(hypothesisOntology, expertReasoner, dataset.baseSet(), dataset.initialReasoner());
    }
}
