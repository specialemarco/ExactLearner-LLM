package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.configurations.Configuration;
import org.exactlearner.engine.ELEngine;
import org.exactlearner.learner.ConceptRelation;
import org.exactlearner.learner.Learner;
import org.exactlearner.oracle.Oracle;
import org.exactlearner.utils.Metrics;
import org.pac.Pac;
import org.semanticweb.owlapi.model.*;
import org.utility.YAMLConfigLoader;
import org.utility.OntologyManipulator;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import static org.utility.StatsPrinter.printAndSaveStats;


public class LaunchExactLearner extends LaunchLearner {

    private List<String> ontologies;
    private double epsilon = 0.2;
    private double delta = 0.1;

    private double totalCE = 0;
    private double totalMembershipQ = 0;
    private double totalEquivalenceQ = 0;

    protected List<Integer> hypothesisSizes;

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchExactLearner().run(args);
    }


    protected void loadConfiguration(String fileName) {
        Configuration config = new YAMLConfigLoader().getConfig(fileName, Configuration.class);
        ontologies = config.getOntologies();
        hypothesisSizes = ontologies.stream()
                .map(OntologyManipulator::computeOntologySize)
                .collect(Collectors.toList());
    }

    public void run(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: LaunchSyntheticLearner <configFile> [epsilon] [delta]");
            return;
        }
        String configFile = args[0];
        if (args.length > 1) epsilon = Double.parseDouble(args[1]);
        if (args.length > 2) delta = Double.parseDouble(args[2]);

        loadConfiguration(configFile);

        try {
            for (String ontologyPath : ontologies) {
                String ontName = Path.of(ontologyPath).getFileName().toString();
                System.out.println("\n--- Synthetic learning for: " + ontName);
                setup(ontologyPath);
                llmQueryEngineForT = new ELEngine(groundTruthOntology);
                elQueryEngineForH = new ELEngine(hypothesisOntology);

                conceptRelation = new ConceptRelation<>();
                learner = new Learner(llmQueryEngineForT, elQueryEngineForH, myMetrics, conceptRelation);
                oracle = new Oracle(llmQueryEngineForT, elQueryEngineForH);

                // Run PAC learning
                runLearningExperiment(args, hypothesisSizes.get(ontologies.indexOf(ontologyPath)));
                // Clean-up
                cleaningUp();
                System.out.println("--- Completed: " + ontName + "\n");
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        int n = ontologies.size();
        System.out.println("Average CE/PAC sample: " + (totalCE / n));
        System.out.println("Average membership Qs/PAC sample: " + (totalMembershipQ / n));
        System.out.println("Average equivalence Qs/PAC sample: " + (totalEquivalenceQ / n));
    }

    protected void setup(String ontologyPath) {

        try {
            myMetrics = new Metrics(myRenderer);
            loadTargetOntology(ontologyPath);
            setUpOntologyFolders("synthetic", "synthetic", "synthetic", ontologyPath);
            saveTargetOntology();
            loadHypothesisOntology();
            computeConceptAndRoleNumbers();
        } catch (OWLOntologyCreationException e) {
            System.out.println("Could not load groundTruthOntology: " + e.getMessage());
        } catch (IOException | OWLException e) {
            e.printStackTrace();
        }
    }

    protected void runLearningExperiment(String[] args, int hypothesisSize) throws Throwable {
        long timeStart = System.currentTimeMillis();
        learner.precomputation();
        Pac pac = new Pac(parser.getClasses().get(), parser.getObjectProperties(), epsilon, delta, hypothesisSize, 0);
        System.out.println("Concept names: " + parser.getClasses().get().size());
        System.out.println("Object properties: " + parser.getObjectProperties().size());
        System.out.println("Logical axioms size: " + hypothesisSize);
        System.out.println("Hypothesis space size: " + pac.numberOfAxioms);
        System.out.println("Number of samples: " + pac.getNumberOfSamples());
        int ceCount = 0;
        long totalSamples = pac.getNumberOfSamples();

        while (true) {
            myMetrics.setEquivCount(myMetrics.getEquivCount() + 1);
             counterExample = getCounterExample(pac);
            if (counterExample == null) break;
            ceCount++;
            counterExample = learner.decompose(counterExample.getSubClass(), counterExample.getSuperClass());
            checkTransformations();
        }

        totalCE += (double) ceCount / totalSamples;
        totalMembershipQ += (double) myMetrics.getMembCount() / totalSamples;
        totalEquivalenceQ += (double) myMetrics.getEquivCount() / totalSamples;

        var filename =  targetFile.getName() + "_synthetic";
        var dir = "statistics/cache/";
        var statFile = new File(dir, filename);
        long timeEnd = System.currentTimeMillis();
        printAndSaveStats(timeStart, timeEnd, args, true,
                targetFile, statFile, myMetrics, learner, oracle, conceptNumber, roleNumber, groundTruthOntology, hypothesisOntology);

        saveOWLFile(hypothesisOntology, new File(ontologyFolderH));
        validation();
    }
}
