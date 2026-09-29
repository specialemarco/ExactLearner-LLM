package org.exactlearner.parser;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;

public class OWLParserTest {
    OWLParser parser;
    private final static int ANIMAL_CLASSES_NUMBER = 17;

    @Before
    public void setUp() throws OWLOntologyCreationException {
        parser = new OWLParser("data/ontologies/small/animals.owl", OWLManager.createOWLOntologyManager());
        if (parser.getClasses().isEmpty()) {
            Assert.fail("FAILED TO LOAD ANIMAL.OWL");
        }
    }

    @Test
    public void getClassesTest() {
        Assert.assertEquals(ANIMAL_CLASSES_NUMBER, parser.getClasses().get().size());
        System.out.println(parser.getClasses().get());
    }
}
