package org.pac;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * getRandomStatement() rejects index triples that do not form a statement and
 * retries. For two signatures no triple is ever valid, so that loop never exits
 * -- it does not throw and does not return, it spins at 100% CPU. The constructor
 * refuses them instead, which is the difference between a named error in the
 * first second and a job that looks busy for its whole walltime.
 *
 * The timeouts below are preemptive on purpose: a regression here hangs rather
 * than fails, and a hung build is worse than a red one.
 */
public class PacSignatureGuardTest {

    private static final OWLDataFactory DF = OWLManager.getOWLDataFactory();

    private static Pac pac(int classCount, int roleCount) {
        Set<OWLClass> classes = new LinkedHashSet<>();
        for (int i = 0; i < classCount; i++) {
            classes.add(DF.getOWLClass(IRI.create("http://example.org/C" + i)));
        }
        Set<OWLObjectProperty> roles = new LinkedHashSet<>();
        for (int i = 0; i < roleCount; i++) {
            roles.add(DF.getOWLObjectProperty(IRI.create("http://example.org/r" + i)));
        }
        return new Pac(classes, roles, 0.2, 0.1, 2, 0);
    }

    @Test
    public void noClassesIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> pac(0, 3));
        assertTrue(e.getMessage().contains("never return"), e.getMessage());
    }

    /** The only statement shape left needs C different from A and B, with one class to go round. */
    @Test
    public void oneClassAndNoObjectPropertiesIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> pac(1, 0));
    }

    /** One class is enough as soon as a role can carry the existential shapes. */
    @Test
    public void oneClassWithAnObjectPropertyTerminates() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Pac pac = pac(1, 1);
            for (int i = 0; i < 50; i++) {
                assertNotNull(pac.getRandomStatement());
            }
        });
    }

    /** Two classes are enough with no roles at all: (A n B) SubClassOf C becomes reachable. */
    @Test
    public void twoClassesWithNoObjectPropertiesTerminates() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Pac pac = pac(2, 0);
            for (int i = 0; i < 50; i++) {
                assertNotNull(pac.getRandomStatement());
            }
        });
    }
}
