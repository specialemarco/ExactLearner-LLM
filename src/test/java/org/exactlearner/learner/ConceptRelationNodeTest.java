package org.exactlearner.learner;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** ConceptRelation, the subsumption graph precomputation builds over class names. */
public class ConceptRelationNodeTest {
    // Ancestors follow edges transitively, and a cycle (E and H above each other)
    // joins its members, so H loses E as an ancestor.
    @Test
    public void addEdges() {
        ConceptRelation<String> s = new ConceptRelation<>();
        s.addEdge("H", "B");
        s.addEdge("H", "E");
        s.addEdge("E", "B");
        s.addEdge("B", "A");

        assertThat(s.getAllAncestors("H"), hasSize(3));
        assertThat(s.getAllAncestors("B"), hasSize(1));

        s.addEdge("E", "H");

        assertThat(s.getAllAncestors("H"), hasSize(2));
    }
}