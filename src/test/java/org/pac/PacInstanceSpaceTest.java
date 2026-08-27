package org.pac;

import org.exactlearner.parser.OWLParser;
import org.exactlearner.parser.OWLParserImpl;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Coverage for Pac.computeInstanceSpaceSize(), which had none.
 *
 * Written against JUnit 5 (Jupiter) deliberately. Surefire auto-selects the JUnitPlatform
 * provider here because junit-jupiter is on the test classpath, and with no vintage engine
 * present every org.junit.Test (JUnit 4) class in src/test is silently skipped -- `mvn test`
 * reports BUILD SUCCESS having run only the Jupiter ones. A JUnit 4 version of this file
 * compiled fine and ran zero tests.
 *
 * This file was repurposed on 2026-08-27 from StatementBuilderTest, whose five tests had been
 * commented out since c69e42e ("code import from previous repo") and could never have run here:
 * they exercised StatementBuilder/StatementBuilderImpl, which are absent from src/ and from the
 * whole git history -- they stayed behind in the previous repo. The original block is recoverable
 * at c69e42e if needed.
 *
 * Its assertions are NOT reused. Those numbers fit cn^3 + 2*cn^2*rn, an instance space that
 * counts the reflexive C subClassOf C statements; the current formula is cn^2*(cn-1) + 2*cn^2*rn,
 * which excludes them and is therefore smaller by exactly cn^2. The old numbers also assumed
 * class counts that today's parser does not produce for two of the five ontologies (see
 * FOOTBALL below). The constants here were measured against the current parser instead.
 */
public class PacInstanceSpaceTest {

    /** None of these affect computeInstanceSpaceSize(); they only satisfy the constructor. */
    private static final double EPSILON = 0.2;
    private static final double DELTA = 0.1;
    private static final int HYPOTHESIS_SIZE = 1;
    private static final int SEED = 0;

    private static final String ONTOLOGY_DIR = "src/main/resources/ontologies/small/";

    // Measured against the current parser on 2026-08-27: {ontology, classes, objectProperties}.
    // FOOTBALL counts 10 classes rather than the 9 named in the file because football.owl is the
    // only one of the five that explicitly declares owl:Thing, and getClasses() keeps it.
    private static final Object[][] ONTOLOGIES = {
            {"animals",     17, 4},
            {"generations", 20, 4},
            {"cl",          22, 0},
            {"university",   7, 3},
            {"football",    10, 3},
    };

    private static Pac pacFor(String ontology) throws Exception {
        OWLParser parser = new OWLParserImpl(ONTOLOGY_DIR + ontology + ".owl",
                OWLManager.createOWLOntologyManager());
        Set<OWLClass> classes = parser.getClasses()
                .orElseThrow(() -> new AssertionError("failed to load " + ontology + ".owl"));
        Set<OWLObjectProperty> properties = parser.getObjectProperties();
        return new Pac(classes, properties, EPSILON, DELTA, HYPOTHESIS_SIZE, SEED);
    }

    /** Guards the ontology fixtures: if these drift, the expected sizes below are meaningless. */
    @Test
    public void ontologiesHaveTheExpectedSignature() throws Exception {
        for (Object[] o : ONTOLOGIES) {
            String name = (String) o[0];
            OWLParser parser = new OWLParserImpl(ONTOLOGY_DIR + name + ".owl",
                    OWLManager.createOWLOntologyManager());
            assertEquals((int) (Integer) o[1], parser.getClasses().orElseThrow().size(),
                    name + ": class count");
            assertEquals((int) (Integer) o[2], parser.getObjectProperties().size(),
                    name + ": object property count");
        }
    }

    /** computeInstanceSpaceSize() must equal cn^2*(cn-1) + 2*cn^2*rn for every fixture. */
    @Test
    public void instanceSpaceMatchesTheSpecifiedFormula() throws Exception {
        for (Object[] o : ONTOLOGIES) {
            String name = (String) o[0];
            long cn = (Integer) o[1];
            long rn = (Integer) o[2];
            long expected = cn * cn * (cn - 1) + 2 * cn * cn * rn;
            assertEquals((double) expected, pacFor(name).computeInstanceSpaceSize(), 0.0, name);
        }
    }

    /** Regression pins: bare numbers, so an accidental formula change cannot pass silently. */
    @Test
    public void instanceSpaceRegressionValues() throws Exception {
        assertEquals(6936.0,  pacFor("animals").computeInstanceSpaceSize(),     0.0);
        assertEquals(10800.0, pacFor("generations").computeInstanceSpaceSize(), 0.0);
        assertEquals(10164.0, pacFor("cl").computeInstanceSpaceSize(),          0.0);
        assertEquals(588.0,   pacFor("university").computeInstanceSpaceSize(),  0.0);
        assertEquals(1500.0,  pacFor("football").computeInstanceSpaceSize(),    0.0);
    }

    /**
     * Pins the semantic difference from the old StatementBuilder instance space: the current
     * formula omits exactly the cn^2 reflexive C subClassOf C statements. If this ever fails,
     * the definition of the instance space has changed and every PAC sample budget shifts with it.
     */
    @Test
    public void instanceSpaceExcludesReflexiveStatements() throws Exception {
        for (Object[] o : ONTOLOGIES) {
            String name = (String) o[0];
            long cn = (Integer) o[1];
            long rn = (Integer) o[2];
            long withReflexive = cn * cn * cn + 2 * cn * cn * rn;
            assertEquals((double) (withReflexive - cn * cn),
                    pacFor(name).computeInstanceSpaceSize(), 0.0,
                    name + ": should differ by cn^2");
        }
    }

    /** An ontology with no object properties must not contribute a role term. */
    @Test
    public void instanceSpaceIgnoresRolesWhenThereAreNone() throws Exception {
        // cl has rn = 0, so the size reduces to cn^2*(cn-1) = 22*22*21.
        assertEquals(22.0 * 22.0 * 21.0, pacFor("cl").computeInstanceSpaceSize(), 0.0);
    }
}
