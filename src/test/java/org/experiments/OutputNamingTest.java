package org.experiments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.utility.PacloDataset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PACLO dataset's three configurations are all called expertOntology.owl and
 * differ only by folder, so without a BaseSet tag each run overwrites the last
 * one's saved hypothesis. The tag moved to LaunchLearner on 2026-08-27: the
 * collision belongs to the dataset, so every arm run on that dataset needs it,
 * not just the A-induced one.
 */
public class OutputNamingTest {

    private static final String ADVANCED = "You are an expert in description logic. Answer carefully and precisely.";

    // ---- the folder-name mapping, independent of the filesystem -------------

    @Test
    public void baseSetTagComesFromTheDatasetFolder() {
        // The real data_paclo folder names: the exists_* suffixes must win over
        // the class_names they also contain, or C2 and C3 both read as C1.
        assertEquals("c1", PacloDataset.baseSetTag("data_paclo/owl2bench-1-el-class_names/expertOntology.owl"));
        assertEquals("c2", PacloDataset.baseSetTag("data_paclo/owl2bench-1-el-class_names_exists_thing/expertOntology.owl"));
        assertEquals("c3", PacloDataset.baseSetTag("data_paclo/owl2bench-1-el-class_names_exists_partial/expertOntology.owl"));
    }

    /** A bare filename has no parent folder; this used to throw NullPointerException. */
    @Test
    public void bareFilenameHasNoTag() {
        assertEquals("", PacloDataset.baseSetTag("expertOntology.owl"));
        assertEquals("", PacloDataset.outputTag("expertOntology.owl"));
    }

    // ---- the gate: only a real dataset is tagged ----------------------------

    @Test
    public void anOntologyWithNoBaseSetBesideItIsNotTagged(@TempDir Path tmp) throws IOException {
        Path plain = tmp.resolve("small").resolve("animals.owl");
        Files.createDirectories(plain.getParent());
        Files.createFile(plain);

        assertEquals("", PacloDataset.outputTag(plain.toString()),
                "the small ontologies keep the names they have always had");
    }

    @Test
    public void bothArmsNameADatasetsOutputIdentically(@TempDir Path tmp) throws IOException {
        String c2 = dataset(tmp, "owl2bench-1-el-class_names_exists_thing");

        String uniform = hypothesisFileFor(new LaunchLLMLearner(), c2);
        String aInduced = hypothesisFileFor(new LaunchLLMLearnerAInduced(), c2);

        assertTrue(uniform.contains("expertOntology_c2_deepseek-r1-32b_nlp_advanced.owl"), uniform);
        assertEquals(uniform, aInduced, "the collision is the dataset's, so both arms must tag it");
    }

    @Test
    public void theBaseSetsGetDistinctHypothesisFiles(@TempDir Path tmp) throws IOException {
        String c2 = hypothesisFileFor(new LaunchLLMLearner(), dataset(tmp, "owl2bench-1-el-class_names_exists_thing"));
        String c3 = hypothesisFileFor(new LaunchLLMLearner(), dataset(tmp, "owl2bench-1-el-class_names_exists_partial"));

        assertTrue(c3.contains("expertOntology_c3_"), c3);
        assertNotEquals(c2, c3, "C2 and C3 must not share a hypothesis file");
    }

    /** Writes a dataset folder: expertOntology.owl plus the baseSet that marks it as one. */
    private static String dataset(Path tmp, String folder) throws IOException {
        Path dir = tmp.resolve(folder);
        Files.createDirectories(dir);
        Files.createFile(dir.resolve("baseSet"));
        Path ontology = dir.resolve("expertOntology.owl");
        Files.createFile(ontology);
        return ontology.toString();
    }

    private static String hypothesisFileFor(LaunchLLMLearner launcher, String ontology) {
        launcher.setUpOntologyFolders("nlp", ADVANCED, "deepseek-r1-32b", ontology);
        return launcher.ontologyFolderH;
    }
}
