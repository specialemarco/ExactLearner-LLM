package org.exactlearner.engine;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.ShortFormProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * The name used for each class and property when an axiom is rendered: its
 * rdfs:label, or else its IRI fragment. This sets the vocabulary of every LLM
 * prompt, and so every cache key. The OWL2Bench targets and the small
 * ontologies have no labels, so prompts carry raw fragments such as
 * "AssociateProfessor" or "has_part"; changing this is a new experimental
 * condition, not a fix, since no existing cache entry would match again.
 */
public class AnnotationShortFormProvider implements ShortFormProvider {

    private final OWLOntology ontology;
    private final Map<IRI, String> labelMap = new HashMap<>();

    public AnnotationShortFormProvider(OWLOntology ontology) {
        this.ontology = ontology;
    }

    @Override
    public String getShortForm(OWLEntity owlEntity) {
        return render(owlEntity.getIRI());
    }

    public String render(IRI iri) {
        if (labelMap.containsKey(iri)) {
            return labelMap.get(iri);
        }
        for (OWLAnnotationAssertionAxiom a : ontology.getAnnotationAssertionAxioms(iri)) {
            if (a != null && a.getProperty().isLabel() && a.getValue() instanceof OWLLiteral val) {
                labelMap.put(iri, val.getLiteral());
                return val.getLiteral();
            }
        }
        return iri.getFragment();
    }

    @Override
    public void dispose() {
    }
}
