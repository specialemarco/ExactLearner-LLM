package org.analysis.common;
import org.apache.commons.math3.stat.inference.ChiSquareTest;
import java.util.Arrays;

// NOTE (audited 2026-08-27): used only by org.analysis.ResultAnalyzer. That class is a live
// analysis entry point that is currently unreachable because scripts/testSimplification.sh
// still invokes its pre-refactor name (org.analysis.exp2.ResultAnalyzer) -- so this class is
// reachable again as soon as that script is repaired. See the note on ResultAnalyzer.
//
// NOT to be confused with org.exactlearner.utils.Metrics, which is the Metrics every other
// call site in the codebase imports. Check the import before editing either one.
public class Metrics {
    public static double calculateAccuracy(int[][] confusionMatrix) {
        int total = Arrays.stream(confusionMatrix).flatMapToInt(Arrays::stream).sum();
        int correct = 0;
        for (int i = 0; i < confusionMatrix.length; i++) {
            correct += confusionMatrix[i][i];
        }
        return total == 0 ? 0.0 : (double) correct / total;
    }

    public static double calculatePrecision(int[][] confusionMatrix) {
        int tp = confusionMatrix[0][0];
        int fp = confusionMatrix[1][0];
        return (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
    }

    public static double calculateRecall(int[][] confusionMatrix) {
        int tp = confusionMatrix[0][0];
        int fn = confusionMatrix[0][1] + confusionMatrix[0][2];
        return (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
    }

    public static double calculateF1Score(int[][] confusionMatrix) {
        double precision = calculatePrecision(confusionMatrix);
        double recall = calculateRecall(confusionMatrix);
        return (precision + recall) > 0 ? 2 * precision * recall / (precision + recall) : 0.0;
    }

    public static double calculateLogLoss(int[][] confusionMatrix) {
        double sum = 0;
        int total = Arrays.stream(confusionMatrix).flatMapToInt(Arrays::stream).sum();
        for (int[] matrix : confusionMatrix) {
            for (int i : matrix) {
                double prob = (double) i / total;
                sum += i == 0 ? 0 : i * Math.log(prob);
            }
        }
        return -sum / total;
    }

    private static long[][] getLongConfusionMatrix(int[][] confusionMatrix) {
        long[][] longConfusionMatrix = new long[2][2];
        longConfusionMatrix[0][0] = confusionMatrix[0][0] == 0 ? 1 : confusionMatrix[0][0];
        longConfusionMatrix[0][1] = confusionMatrix[0][1] == 0 ? 1 : confusionMatrix[0][1];
        longConfusionMatrix[1][0] = confusionMatrix[1][0] == 0 ? 1 : confusionMatrix[1][0];
        longConfusionMatrix[1][1] = confusionMatrix[1][1] == 0 ? 1 : confusionMatrix[1][1];
        return longConfusionMatrix;
    }

    public static double calculateChiSquarePValue(int[][] confusionMatrix) {
        // matrix from 3x3 to 2x2 and from int to long
        long[][] longConfusionMatrix = getLongConfusionMatrix(confusionMatrix);
        return new ChiSquareTest().chiSquareTest(longConfusionMatrix);
    }

    public static double calculateChiSquare(int[][] confusionMatrix) {
        // matrix from 3x3 to 2x2 and from int to long
        long[][] longConfusionMatrix = getLongConfusionMatrix(confusionMatrix);
        return new ChiSquareTest().chiSquare(longConfusionMatrix);
    }

    public static double calculateMatthewsCorrelationCoefficient(int[][] confusionMatrix) {
        int tp = confusionMatrix[0][0];
        int tn = confusionMatrix[1][1] + confusionMatrix[1][2];
        int fp = confusionMatrix[1][0];
        int fn = confusionMatrix[0][1] + confusionMatrix[0][2];
        //      T   F   U
        //  T   TP  FN  FN
        //  F   FP  TN  TN

        double numerator = (tp * tn) - (fp * fn);
        double denominator = Math.sqrt((tp + fp) * (tp + fn) * (tn + fp) * (tn + fn));

        if (denominator == 0) {
            return 0.0; // Avoid division by zero
        } else {
            return numerator / denominator;
        }
    }
}
