package org.utility;
import org.exactlearner.learner.Learner;
import org.exactlearner.oracle.Oracle;
import org.exactlearner.utils.Metrics;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLOntology;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class StatsPrinter {
    public static double totalSat = 0;
    public static double totalDesat = 0;
    public static double totalRDecomp = 0;
    public static double totalLDecomp = 0;
    public static double totalMerge = 0;
    public static double totalBranch = 0;

    public static void printStat(String description, String value, boolean verb) {
        if (verb) {
            System.out.print(description);
            System.out.println(value);
        } else {
            System.out.print(", " + value);
        }
    }

    public static void printStat(String description, int value, boolean verb) {
        printStat(description, String.valueOf(value), verb);
    }

    public static void printStat(String description, boolean verb) {
        if (verb) {
            printStat(description, " ", verb);
        }
    }

    public static void printAndSaveStats(long timeStart, long timeEnd, String[] args, boolean verb, File targetFile,
                                         File statsFile,
                                         Metrics myMetrics, Learner baseLearner, Oracle baseOracle,
                                         int conceptNumber, int roleNumber, OWLOntology targetOntology,
                                         OWLOntology hypothesisOntology) {
        if (!verb) {
            System.out.print(targetFile.getName());
            Arrays.stream(args).skip(1).forEach(x -> System.out.print(", " + x));
        }
        printStat("Total time (ms): ", String.valueOf(timeEnd - timeStart), verb);

        printStat("Total membership queries: ", myMetrics.getMembCount(), verb);
        printStat("Total equivalence queries: ", myMetrics.getEquivCount(), verb);

        printLearnerStats(baseLearner, verb);
        String statsFileName = statsFile + "_metrics.csv";
        // Add "_sizes.csv" to the stats file name
        String sizesFileName = statsFile + "_sizes.csv";
        saveLearnerStats(baseLearner, statsFileName);
        // printOracleStats(baseOracle, verb);
        printOntologySizes(targetOntology, hypothesisOntology, myMetrics, verb, conceptNumber, roleNumber);
        saveOntologySizes(targetOntology, hypothesisOntology, myMetrics, sizesFileName, conceptNumber, roleNumber);
    }

    private static void saveLearnerStats(Learner baseLearner, String filename) {
        var lComp = baseLearner.getNumberLeftDecomposition();
        var tComp = baseLearner.getNumberRightDecomposition();
        var merge = baseLearner.getNumberMerging();
        var branch = baseLearner.getNumberBranching();
        var sat = baseLearner.getNumberSaturations();
        var unsat = baseLearner.getNumberUnsaturations();
        var totalOps = lComp + tComp + merge + branch + sat + unsat;
        // Create a csv file and save the stats
        String header = "Decompose Left,Decompose Right,Merging,Branching,Saturation,Desaturation,Total Operations";
        String data = String.format("%d,%d,%d,%d,%d,%d,%d", lComp, tComp, merge, branch, sat, unsat, totalOps);
        final Path filePath = Paths.get(filename);
        try {
            if (!filePath.toFile().exists()) {
                filePath.toFile().createNewFile();
            }
            if (filePath.toFile().length() == 0) {
                java.nio.file.Files.writeString(filePath, header + "\n");
            }
            java.nio.file.Files.writeString(filePath, data + "\n", java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void printLearnerStats(Learner baseLearner, boolean verb) {
        var lComp = baseLearner.getNumberLeftDecomposition();
        var tComp = baseLearner.getNumberRightDecomposition();
        var merge = baseLearner.getNumberMerging();
        var branch = baseLearner.getNumberBranching();
        var sat = baseLearner.getNumberSaturations();
        var unsat = baseLearner.getNumberUnsaturations();
        var totalOps = lComp + tComp + merge + branch + sat + unsat;
        if(totalOps != 0){
            totalLDecomp += (double) lComp / totalOps;
            totalRDecomp += (double) tComp / totalOps;
            totalMerge += (double) merge / totalOps;
            totalBranch += (double) branch / totalOps;
            totalSat += (double) sat / totalOps;
            totalDesat += (double) unsat / totalOps;
        }
        printStat("\nLearner Stats:", verb);
        printStat("Total left decompositions: ", baseLearner.getNumberLeftDecomposition(), verb);
        printStat("Total right decompositions: ", baseLearner.getNumberRightDecomposition(), verb);
        printStat("Total mergings: ", baseLearner.getNumberMerging(), verb);
        printStat("Total branchings: ", baseLearner.getNumberBranching(), verb);
        printStat("Total saturations: ", baseLearner.getNumberSaturations(), verb);
        printStat("Total unsaturations: ", baseLearner.getNumberUnsaturations(), verb);
    }

    // PARKED -- sole call site is commented out above (see "// printOracleStats(...)").
    // Uncomment there to re-enable; kept so that toggle still works.
    private static void printOracleStats(Oracle baseOracle, boolean verb) {
        printStat("\nOracle Stats:", verb);
        printStat("Total left compositions: ", baseOracle.getNumberLeftComposition(), verb);
        printStat("Total right compositions: ", baseOracle.getNumberRightComposition(), verb);
        printStat("Total mergings: ", baseOracle.getNumberMerging(), verb);
        printStat("Total branchings: ", baseOracle.getNumberBranching(), verb);
        printStat("Total saturations: ", baseOracle.getNumberSaturations(), verb);
        printStat("Total unsaturations: ", baseOracle.getNumberUnsaturations(), verb);
    }

    private static void printOntologySizes(OWLOntology targetOntology, OWLOntology hypothesisOntology,
                                           Metrics myMetrics, boolean verb, int conceptNumber, int roleNumber) {
        printStat("\nOntology sizes:", verb);
        printStat("Target TBox logical axioms: ", targetOntology.getAxiomCount(AxiomType.SUBCLASS_OF) +
                targetOntology.getAxiomCount(AxiomType.EQUIVALENT_CLASSES), verb);
        myMetrics.computeTargetSizes(targetOntology);
        myMetrics.computeHypothesisSizes(hypothesisOntology);
        printStat("Size of T: ", myMetrics.getSizeOfTarget(), verb);
        printStat("Hypothesis TBox logical axioms: ", hypothesisOntology.getAxiomCount(AxiomType.SUBCLASS_OF) +
                hypothesisOntology.getAxiomCount(AxiomType.EQUIVALENT_CLASSES), verb);
        printStat("Size of H: ", myMetrics.getSizeOfHypothesis(), verb);
        printStat("Number of concept names: ", conceptNumber, verb);
        printStat("Number of role names: ", roleNumber, verb);
        printStat("Size of largest  concept in T: ", myMetrics.getSizeOfTargetLargestConcept(), verb);
        printStat("Size of largest  concept in H: ", myMetrics.getSizeOfHypothesisLargestConcept(), verb);
        printStat("Size of largest  counterexample: ", myMetrics.getSizeOfLargestCounterExample(), verb);
    }

    private static void saveOntologySizes(OWLOntology targetOntology, OWLOntology hypothesisOntology,
                                          Metrics myMetrics, String filename, int conceptNumber, int roleNumber) {
        String header = "Target TBox logical axioms,Size of T,Hypothesis TBox logical axioms,Size of H,Number of concept names,Number of role names,Size of largest  concept in T,Size of largest  concept in H,Size of largest counterexample, Total membership queries, Total equivalent queries";
        String data = String.format("%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                targetOntology.getAxiomCount(AxiomType.SUBCLASS_OF) +
                        targetOntology.getAxiomCount(AxiomType.EQUIVALENT_CLASSES),
                myMetrics.getSizeOfTarget(),
                hypothesisOntology.getAxiomCount(AxiomType.SUBCLASS_OF) +
                        hypothesisOntology.getAxiomCount(AxiomType.EQUIVALENT_CLASSES),
                myMetrics.getSizeOfHypothesis(),
                conceptNumber,
                roleNumber,
                myMetrics.getSizeOfTargetLargestConcept(),
                myMetrics.getSizeOfHypothesisLargestConcept(),
                myMetrics.getSizeOfLargestCounterExample(),
                myMetrics.getMembCount(),
                myMetrics.getEquivCount()
        );
        final Path filePath = Paths.get(filename);
        try {
            if (!filePath.toFile().exists()) {
                filePath.toFile().createNewFile();
            }
            if (filePath.toFile().length() == 0) {
                java.nio.file.Files.writeString(filePath, header + "\n");
            }
            java.nio.file.Files.writeString(filePath, data + "\n", java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}