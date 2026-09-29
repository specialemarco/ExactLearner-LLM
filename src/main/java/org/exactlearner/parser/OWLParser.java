package org.exactlearner.parser;

import org.semanticweb.owlapi.model.*;

import java.io.File;
import java.util.*;

/** The signature of an ontology: its classes and object properties. */
public class OWLParser {
    private final OWLOntology owl;
    private List<OWLClass> orderedClasses;

    public OWLParser(String pathOfFile, OWLOntologyManager manager) {
        System.out.println("Parsing file: " + pathOfFile);
        try {
            owl = manager.loadOntologyFromOntologyDocument(new File(pathOfFile));
        } catch (OWLOntologyCreationException e) {
            throw new RuntimeException(e);
        }
    }

    public OWLParser(OWLOntology owl) {
        this.owl = owl;
    }

    public Optional<Set<OWLClass>> getClasses() {
        return Optional.ofNullable(owl.getClassesInSignature());
    }

    public Set<OWLObjectProperty> getObjectProperties() {
        return owl.getObjectPropertiesInSignature();
    }

    /**
     * The classes sorted by IRI fragment, then shuffled with a fixed seed (42).
     * LLMEngine.getClassesInSignature() returns this list, so it fixes the order
     * the learner visits classes in, e.g. precomputation's class pairs: keep the
     * sort and the seed, or runs stop being reproducible against earlier ones.
     */
    public List<OWLClass> getOrderedClasses() {
        if (orderedClasses == null) {
            orderedClasses = new ArrayList<>(getClasses().get().stream().sorted(Comparator.comparing(c -> c.getIRI().getFragment())).toList());
            Collections.shuffle(orderedClasses, new Random(42));
        }
        return orderedClasses;
    }
}
