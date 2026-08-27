package org.analysis;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.analysis.common.Metrics;
import org.configurations.Configuration;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.utility.OntologyManipulator;
import org.utility.YAMLConfigLoader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NOTE (audited 2026-08-27): this class is NOT dead code -- it is a live analysis entry
 * point whose caller was never rewired after a rename. Do not delete it on the grounds
 * that "nothing calls it": it is a main() class, run from the shell after an experiment,
 * so no Java code is ever expected to reference it.
 *
 * THE REWRITE. Commit ca94e39 ("wip: refactoring", Matteo Magnini, 2025-05-09) reshaped
 * org/analysis. Git recorded this file as a rename with only the package line changed:
 *     org/analysis/{exp2 => }/ResultAnalyzer.java | 3 +-
 * The same commit deleted exp1/ and exp3/ outright (~820 lines). The shell scripts were
 * never updated, so they still name the pre-refactor classes.
 *
 * THE BROKEN LINK. scripts/testSimplification.sh invokes "org.analysis.exp2.ResultAnalyzer"
 * -- this class's old fully-qualified name. The script is therefore trying to run exactly
 * this code and failing to resolve the main class. Repointing that script at
 * org.analysis.ResultAnalyzer is the repair; see the header of the script for the other
 * stale references it needs fixed at the same time.
 *
 * STATUS OF THE REST OF THE PACKAGE. This is the only main() in org/analysis, so the
 * neighbours cannot be script entry points the way this class is: common/Metrics is used
 * only from here, and common/BaseResultReader and common/JenaQueryExecutor are referenced
 * by nothing at all. Note that common/Metrics is NOT the Metrics the rest of the codebase
 * uses -- that is org.exactlearner.utils.Metrics.
 *
 * Until the script is repaired, nothing here has run since May 2025 -- treat the hardcoded
 * paths below as unverified.
 */
public class ResultAnalyzer {

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);

        Configuration config = new YAMLConfigLoader().getConfig(args[0], Configuration.class);
        Boolean synthetic = false;
        if (args.length > 1) {
            synthetic = Boolean.parseBoolean(args[1]);
        }
        Boolean finalSynthetic = synthetic;
        if (synthetic){
            config.getOntologies().forEach(ontology -> {
                try {
                    new ResultAnalyzer("synthetic", ontology.substring(0, ontology.length() - 4), finalSynthetic).run();
                } catch (OWLOntologyCreationException e) {
                    throw new RuntimeException(e);
                }
            });
        } else {
            config.getOntologies().forEach(ontology -> config.getModels().forEach(model -> {
                try {
                    new ResultAnalyzer(model.replace(":", "-"), ontology.substring(0, ontology.length() - 4), finalSynthetic).run();
                } catch (OWLOntologyCreationException e) {
                    throw new RuntimeException(e);
                }
            }));
        }
    }

    private final OWLOntology syntheticOntology;
    private final OWLOntology nlpBaseOntology;
    private final OWLOntology manchesterBaseOntology;
    private final OWLOntology expectedOntology;
    private final OWLOntology manchesterAdvancedOntology;
    private final OWLOntology nlpAdvancedOntology;
    private final List<OWLSubClassOfAxiom> allPossibleAxioms;

    private final List<Boolean> inferredAxiomsByExpectedOntology = new ArrayList<>();

    private final String model;
    private final String ontology;
    private final Boolean synthetic;

    public ResultAnalyzer(String model, String ontology, Boolean synthetic) throws OWLOntologyCreationException {
        this.model = model;
        this.synthetic = synthetic;
        // Keep only the name of the ontology without the path
        this.ontology = ontology;
        this.expectedOntology = loadOntology();
        String shortName = Path.of(ontology).getFileName().toString();
        String sep = FileSystems.getDefault().getSeparator();
        String ontologyResultPath = "results" + sep + "ontologies"; // + sep + "simplify";
        if (synthetic) {
            ontologyResultPath = "results" + sep + "ontologies" + sep + "synthetic";
            String syntheticOntologyPath = ontologyResultPath + sep + shortName + "_synthetic_synthetic_advanced.owl";
            this.syntheticOntology = loadOntology(syntheticOntologyPath);
            this.manchesterAdvancedOntology = null;
            this.nlpAdvancedOntology = null;
            this.nlpBaseOntology = null;
            this.manchesterBaseOntology = null;
        } else {
            String manchesterBaseOntologyPath = ontologyResultPath + sep + shortName + "_" + model + "_" + "manchester_base.owl";
            this.manchesterBaseOntology = loadOntology(manchesterBaseOntologyPath);
            String nlpBaseOntologyPath = ontologyResultPath + sep + shortName + "_" + model + "_" + "nlp_base.owl";
            this.nlpBaseOntology = loadOntology(nlpBaseOntologyPath);
            String manchesterAdvancedOntologyPath = ontologyResultPath + sep + shortName + "_" + model + "_" + "manchester_advanced.owl";
            this.manchesterAdvancedOntology = loadOntology(manchesterAdvancedOntologyPath);
            String nlpAdvancedOntologyPath = ontologyResultPath + sep + shortName + "_" + model + "_" + "nlp_advanced.owl";
            this.nlpAdvancedOntology = loadOntology(nlpAdvancedOntologyPath);
            this.syntheticOntology = null;
        }
        this.allPossibleAxioms = OntologyManipulator.getAllPossibleAxiomsCombinations(expectedOntology).stream().sorted().toList();
    }

    public void run() {
        try {
            if (this.synthetic) {
                compareSyntheticOntologies();
            } else {
                compareOntologies();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void compareSyntheticOntologies() throws InterruptedException {
        System.out.println("Evaluation of " + ontology + " ontology using synthetic learner...");
        long startingTime = System.currentTimeMillis();

        // Inizializzazione delle matrici di confusione
        int[][] confusionMatrix = new int[3][3];

        // Creazione e gestione dei Reasoner con controllo null
        OWLReasoner expectedReasoner = initializeReasonerOrFillMatrix(expectedOntology, null);
        OWLReasoner predictedReasoner = initializeReasonerOrFillMatrix(syntheticOntology, confusionMatrix);

        if (expectedReasoner != null) {
            allPossibleAxioms.forEach(ax -> {
                inferredAxiomsByExpectedOntology.add(expectedReasoner.isEntailed(ax));
            });
        }

        if (predictedReasoner != null) updateConfusionMatrix(predictedReasoner, confusionMatrix);

        printResult(confusionMatrix);
        System.out.println("Evaluation completed in " + (System.currentTimeMillis() - startingTime) / 1000 + " seconds.");

    }

    private void compareOntologies() throws InterruptedException {
        System.out.println("Evaluation of " + ontology + " ontology using " + model + " as oracle...");
        long startingTime = System.currentTimeMillis();

        // Inizializzazione delle matrici di confusione
        int[][] confusionMatrix = new int[3][3];
        int[][] nlpConfusionMatrix = new int[3][3];
        int[][] enrichedConfusionMatrix = new int[3][3];
        int[][] enrichedNlpConfusionMatrix = new int[3][3];

        // Creazione e gestione dei Reasoner con controllo null
        OWLReasoner expectedReasoner = initializeReasonerOrFillMatrix(expectedOntology, null);
        OWLReasoner predictedReasoner = initializeReasonerOrFillMatrix(manchesterBaseOntology, confusionMatrix);
        OWLReasoner nlpPredictedReasoner = initializeReasonerOrFillMatrix(nlpBaseOntology, nlpConfusionMatrix);
        OWLReasoner enrichedPredictedReasoner = initializeReasonerOrFillMatrix(manchesterAdvancedOntology, enrichedConfusionMatrix);
        OWLReasoner enrichedNlpPredictedReasoner = initializeReasonerOrFillMatrix(nlpAdvancedOntology, enrichedNlpConfusionMatrix);

        if (expectedReasoner != null) {
            allPossibleAxioms.forEach(ax -> {
                inferredAxiomsByExpectedOntology.add(expectedReasoner.isEntailed(ax));
            });
        }

        if (predictedReasoner != null) updateConfusionMatrix(predictedReasoner, confusionMatrix);
        if (enrichedPredictedReasoner != null) updateConfusionMatrix(enrichedPredictedReasoner, enrichedConfusionMatrix);
        if (nlpPredictedReasoner != null) updateConfusionMatrix(nlpPredictedReasoner, nlpConfusionMatrix);
        if (enrichedNlpPredictedReasoner != null) updateConfusionMatrix(enrichedNlpPredictedReasoner, enrichedNlpConfusionMatrix);

        printResults(confusionMatrix, nlpConfusionMatrix, enrichedConfusionMatrix, enrichedNlpConfusionMatrix);
        System.out.println("Evaluation completed in " + (System.currentTimeMillis() - startingTime) / 1000 + " seconds.");
    }

    private OWLReasoner initializeReasonerOrFillMatrix(OWLOntology ontology, int[][] confusionMatrix) {
        if (ontology == null) {
            if (confusionMatrix != null) {
                for (int[] matrix : confusionMatrix) {
                    Arrays.fill(matrix, 0);
                }
            }
            System.out.println("Warning: Ontology is null, confusion matrix filled with zeros.");
            return null;
        }
        ElkReasonerFactory factory = new ElkReasonerFactory();
        return factory.createReasoner(ontology);
    }


    private void updateConfusionMatrix(OWLReasoner predictedReasoner, int[][] confusionMatrix) {
        // If there are no axioms in the ontology, return immediately
        if (predictedReasoner.getRootOntology().axioms().findAny().isEmpty()) {
            System.out.println("Warning: No axioms in the ontology, confusion matrix not updated.");
            return;
        }
        List<Boolean> inferredAxiomsByPredictedOntology = new ArrayList<>();
        allPossibleAxioms.forEach(ax -> {
            inferredAxiomsByPredictedOntology.add(predictedReasoner.isEntailed(ax));
        });
        // Update confusion matrix
        for (int i = 0; i < inferredAxiomsByExpectedOntology.size(); i++) {
            int row = inferredAxiomsByExpectedOntology.get(i) ? 0 : 1;
            int col = inferredAxiomsByPredictedOntology.get(i) ? 0 : 1;
            confusionMatrix[row][col]++;
        }
    }

    private void printResult(int[][] confusionMatrix){
        String ontologyName = Path.of(ontology).getFileName().toString().replaceAll("\\(.*\\)", "");

        System.out.printf("Ontology: %s%n", ontologyName);
        System.out.printf("Model: %s%n", model);
        printMetrics("Synthetic", confusionMatrix);
        System.out.println("##############################################################");
        generateSummaryFileForLatexTable(ontologyName, model, confusionMatrix);
    }

    private void printResults(int[][] confusionMatrix, int[][] nlpConfusionMatrix, int[][] enrichedConfusionMatrix, int[][] enrichedNlpConfusionMatrix) {
        String ontologyName = Path.of(ontology).getFileName().toString().replaceAll("\\(.*\\)", "");

        System.out.printf("Ontology: %s%n", ontologyName);
        System.out.printf("Model: %s%n", model);
        printMetrics("M.Syntax", confusionMatrix);
        printMetrics("NLP", nlpConfusionMatrix);
        printMetrics("Enriched prompt M.Syntax", enrichedConfusionMatrix);
        printMetrics("Enriched prompt NLP", enrichedNlpConfusionMatrix);
        System.out.println("##############################################################");
        generateSummaryFilesForLatexTable(ontologyName, model, confusionMatrix, nlpConfusionMatrix, enrichedConfusionMatrix, enrichedNlpConfusionMatrix);
    }

    private void generateSummaryFileForLatexTable(String ontologyName, String model, int[][] confusionMatrix) {
        FileWriter fw;
        var s = FileSystems.getDefault().getSeparator();
        try {
            File f = new File("analysis" + s + ontologyName + "-synthetic.txt");
            f.createNewFile();
            fw = new FileWriter(f.getPath());
            String result = calculateMetrics(confusionMatrix);
            fw.write(result);
            fw.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void generateSummaryFilesForLatexTable(String ontologyName, String model, int[][] confusionMatrix, int[][] nlpConfusionMatrix, int[][] enrichedConfusionMatrix, int[][] enrichedNlpConfusionMatrix) {
        FileWriter fw;
        var s = FileSystems.getDefault().getSeparator();
        try {
            File f = new File("analysis" + s + ontologyName + "-" + model + ".txt");
            f.createNewFile();
            fw = new FileWriter(f.getPath());
            String result = calculateMetrics(confusionMatrix);
            result = result.concat(" " + calculateMetrics(nlpConfusionMatrix));
            result = result.concat(" " + calculateMetrics(enrichedConfusionMatrix));
            result = result.concat(" " + calculateMetrics(enrichedNlpConfusionMatrix));
            fw.write(result);
            fw.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private String calculateMetrics(int[][] confusionMatrix) {
        return Metrics.calculateAccuracy(confusionMatrix) +
                " " + Metrics.calculateRecall(confusionMatrix) +
                " " + Metrics.calculatePrecision(confusionMatrix) +
                " " + Metrics.calculateF1Score(confusionMatrix) +
                " " + Metrics.calculateChiSquare(confusionMatrix) +
                " " + Metrics.calculateChiSquarePValue(confusionMatrix);
    }

    private void printMetrics(String label, int[][] confusionMatrix) {
        System.out.printf("%s RECALL: %.2f%n", label, Metrics.calculateRecall(confusionMatrix));
        System.out.printf("%s PRECISION: %.2f%n", label, Metrics.calculatePrecision(confusionMatrix));
        System.out.printf("%s F1-Score: %.2f%n", label, Metrics.calculateF1Score(confusionMatrix));
        System.out.printf("%s Accuracy: %.2f%n", label, Metrics.calculateAccuracy(confusionMatrix));
        System.out.printf("%s Chi-Square: %.2f%n", label, Metrics.calculateChiSquare(confusionMatrix));
        System.out.printf("%s Chi-Square P-Value: %.6f%n", label, Metrics.calculateChiSquarePValue(confusionMatrix));
    }

    private String getOntologyStringName() {
        return Path.of(ontology).getFileName().toString().replaceAll("\\(.*\\)", "");
    }

    // DEAD PATH BUILDER -- hardcodes an absolute path into another machine's home
    // directory (/home/martint/onto/ontologies/...), the last "martint" reference left in
    // src/. It cannot resolve anywhere in the current setup. Reachable only from
    // getOntologyPathString and the 3-arg loadOntology below, both themselves uncalled,
    // so all three are flagged "never used locally". Rewrite the path before reusing.
    private Path getOntologyPath(String type, String enginePrefix, String model) {
        return Path.of(String.format("%1$shome%1$smartint%1$sonto%1$sontologies%1$s%2$s%1$s%3$slearned_%4$s_%5$s",
                FileSystems.getDefault().getSeparator(), type, enginePrefix, model.replace(":", "-"), getOntologyStringName()));
    }

    private String getOntologyPathString(String type, String enginePrefix, String model) {
        return getOntologyPath(type, enginePrefix, model).toString();
    }

    private OWLOntology loadOntology(String type, String enginePrefix, String model) {
        var path = getOntologyPath(type, enginePrefix, model);
        if (!path.toFile().exists()) {
            System.err.println("Ontology file not found: " + path);
            return null;
        }
        return loadOntology(getOntologyPath(type, enginePrefix, model).toString());
    }

    private OWLOntology loadOntology() {
        String ontologyName = Path.of(ontology).getFileName().toString().replaceAll("\\(.*\\)", "");
        //String path = String.format("results%1$sontologies%1$ssimplify%1$s%2$s_%3$s",
                //FileSystems.getDefault().getSeparator(), "target", ontologyName);
        String path = String.format("results%1$sontologies%1$ssynthetic%1$s%2$s_%3$s",
                FileSystems.getDefault().getSeparator(), "target", ontologyName);
        return loadOntology(path + ".owl");
    }

    private OWLOntology loadOntology(String path) {
        if (!new File(path).exists()) {
            System.out.println("Ontology file not found: " + path + "\nSkipping...");
            return null;
        }
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try {
            return manager.loadOntologyFromOntologyDocument(new File(path));
        } catch (OWLOntologyCreationException e) {
            System.err.println("Error loading ontology: " + e.getMessage());
            System.exit(1);
            return null;
        }
    }
}