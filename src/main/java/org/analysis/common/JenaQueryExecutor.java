package org.analysis.common;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.util.FileManager;

// UNREFERENCED (audited 2026-08-27): nothing in src/ calls this, and it has no main(), so
// unlike its neighbour org.analysis.ResultAnalyzer it cannot be a shell entry point either --
// there is no route by which it runs. Left over from the ca94e39 ("wip: refactoring",
// 2025-05-09) reshuffle of org/analysis; see the note on ResultAnalyzer for the full history
// and the broken script links.
public abstract class JenaQueryExecutor {
    public String executeQuery(String queryS, String ontology) {
        System.out.println("Executing Jena Query: " + queryS + " on ontology: " + ontology);
        OntModel model = ModelFactory.createOntologyModel();
        FileManager.getInternal().readModelInternal(model, ontology);

        String queryString = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n" +
                "ASK WHERE { " + queryS + " . }";

        Query query = QueryFactory.create(queryString);
        // Execute the query and obtain the boolean result
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            boolean isSubclass = qexec.execAsk();
            System.out.println(queryString + isSubclass);
            return "" + isSubclass;
        }
    }
}
