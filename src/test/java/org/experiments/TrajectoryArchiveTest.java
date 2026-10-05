package org.experiments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TrajectoryArchiveTest {

    @Test
    public void aFinishedTrajectoryIsPackedAndItsFolderRemoved(@TempDir Path tmp) throws Exception {
        File hypo = tmp.resolve("expertOntology_c2_mistral_nlp_advanced_seed5.owl").toFile();
        Files.writeString(hypo.toPath(), "final");
        Path dir = tmp.resolve("expertOntology_c2_mistral_nlp_advanced_seed5-trajectory");
        Files.createDirectory(dir);
        Files.writeString(dir.resolve("0001.owl"), "first");
        Files.writeString(dir.resolve("0002.owl"), "second");

        LaunchLearner.archiveTrajectory(hypo);

        File archive = tmp.resolve(dir.getFileName() + ".tar.gz").toFile();
        assertTrue(archive.isFile());
        assertFalse(Files.exists(dir));
        assertTrue(hypo.isFile());
        assertEquals(List.of(dir.getFileName() + "/0001.owl", dir.getFileName() + "/0002.owl"), list(archive));
    }

    @Test
    public void aRunWithoutATrajectoryIsLeftAlone(@TempDir Path tmp) throws IOException {
        File hypo = tmp.resolve("h.owl").toFile();
        LaunchLearner.archiveTrajectory(hypo);
        try (var files = Files.list(tmp)) {
            assertEquals(0, files.count());
        }
    }

    private static List<String> list(File archive) throws Exception {
        Process tar = new ProcessBuilder("tar", "tzf", archive.getPath()).start();
        List<String> names = new String(tar.getInputStream().readAllBytes()).lines()
                .filter(n -> !n.endsWith("/")).sorted().toList();
        assertEquals(0, tar.waitFor());
        return names;
    }
}
