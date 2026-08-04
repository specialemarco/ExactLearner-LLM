package org.exactlearner.learner;

import org.semanticweb.owlapi.model.OWLAxiom;

import java.util.List;

/**
 * Warms the answer cache for a group of axioms the learner is about to ask
 * about one at a time.
 *
 * The learner calls this immediately before a scan whose queries are mutually
 * independent, hands over every axiom in that scan, and then runs the scan
 * completely unchanged. Whoever implements this fetches the answers in batches
 * and writes them to the same cache the engine reads, so the scan finds them
 * already there and issues no LLM call of its own.
 *
 * That indirection is the point. The learner keeps its exact control flow --
 * same queries, same order, same first-hit-wins result -- and stays free of any
 * dependency on the cache or the experiment harness. Batching becomes purely a
 * question of when answers are fetched, never of what the algorithm decides.
 *
 * Implementations must not throw and must be safe to skip: an empty or failed
 * prefetch has to leave a run that would have succeeded still succeeding, only
 * slower.
 */
@FunctionalInterface
public interface AxiomPrefetcher {

    void prefetch(List<OWLAxiom> axioms);
}
