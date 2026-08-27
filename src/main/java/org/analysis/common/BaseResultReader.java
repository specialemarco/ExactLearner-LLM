package org.analysis.common;

// UNREFERENCED (audited 2026-08-27): nothing in src/ implements or imports this, and it has
// no main(), so unlike its neighbour org.analysis.ResultAnalyzer it cannot be a shell entry
// point either -- there is no route by which it runs. Left over from the ca94e39
// ("wip: refactoring", 2025-05-09) reshuffle of org/analysis; see the note on ResultAnalyzer
// for the full history and the broken script links.
public interface BaseResultReader {
    boolean computeResults();

    String getFileNameToAnalyze();
}
