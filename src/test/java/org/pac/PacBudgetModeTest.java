package org.pac;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two sampling-budget readings, added 2026-08-27.
 *
 * GLOBAL is the default and is what every experiment so far has run: one pot of
 * numberOfSamples candidates for the whole run. PER_ROUND hands each equivalence
 * query a fresh full budget, which is the standard reading of the (epsilon, delta)
 * guarantee. See MEETING-2026-08-18.md section 8.
 *
 * Written against JUnit 5: JUnit 4 tests are silently skipped in this project.
 */
public class PacBudgetModeTest {

    private static Pac smallPac() {
        OWLDataFactory df = OWLManager.getOWLDataFactory();
        Set<OWLClass> classes = new LinkedHashSet<>();
        for (int i = 0; i < 4; i++) {
            classes.add(df.getOWLClass(IRI.create("http://example.org/C" + i)));
        }
        Set<OWLObjectProperty> roles = new LinkedHashSet<>();
        roles.add(df.getOWLObjectProperty(IRI.create("http://example.org/r")));
        return new Pac(classes, roles, 0.2, 0.1, 2, 0);
    }

    /** Nothing hands budget back: the run ends the first time the pot is empty. */
    @Test
    public void globalBudgetIsOnePotForTheWholeRun() {
        Pac pac = smallPac();
        assertEquals(Pac.BudgetMode.GLOBAL, pac.getBudgetMode(), "global unless asked otherwise");

        long budget = pac.getNumberOfSamples();
        pac.startRound();
        for (long i = 0; i < budget; i++) {
            assertTrue(pac.hasBudgetLeft(), "budget must last exactly numberOfSamples candidates");
            pac.incrementProvidedSamples();
        }
        assertFalse(pac.hasBudgetLeft());
        assertEquals(0, pac.getRemainingSamples());

        // The decisive difference: a new equivalence query does NOT refill it.
        pac.startRound();
        assertFalse(pac.hasBudgetLeft(), "startRound() must not hand budget back under GLOBAL");
    }

    /** Every equivalence query starts from a full budget. */
    @Test
    public void perRoundBudgetRefillsAtEachEquivalenceQuery() {
        Pac pac = smallPac();
        pac.setBudgetMode(Pac.BudgetMode.PER_ROUND);

        long budget = pac.getNumberOfSamples();
        for (int round = 1; round <= 3; round++) {
            pac.startRound();
            assertEquals(budget, pac.getRemainingSamples(), "round " + round + " starts full");
            for (long i = 0; i < budget; i++) {
                assertTrue(pac.hasBudgetLeft());
                pac.incrementProvidedSamples();
            }
            assertFalse(pac.hasBudgetLeft(), "round " + round + " ends exhausted");
        }

        // providedSamples stays global in both modes: it is what the resume
        // checkpoint stores and what the statistics divide by.
        assertEquals(3 * budget, (long) pac.getNumberOfProvidedSamples());
        assertEquals(3, pac.getRound());
    }

    /** getRandomStatement() spends the round budget too, not only the global one. */
    @Test
    public void uniformSamplingSpendsTheRoundBudget() {
        Pac pac = smallPac();
        pac.setBudgetMode(Pac.BudgetMode.PER_ROUND);
        pac.startRound();
        pac.getRandomStatement();
        assertEquals(1, pac.getRoundSamples());
        assertEquals(1, (long) pac.getNumberOfProvidedSamples());
    }

    /** A restored checkpoint moves the global counter, never the round's. */
    @Test
    public void restoringACheckpointDoesNotSpendTheRoundBudget() {
        Pac pac = smallPac();
        pac.setBudgetMode(Pac.BudgetMode.PER_ROUND);
        pac.restoreProvidedSamples(pac.getNumberOfSamples());
        pac.startRound();
        assertTrue(pac.hasBudgetLeft(), "a resumed run gets a fresh round budget");
        assertEquals(pac.getNumberOfSamples(), (long) pac.getNumberOfProvidedSamples());

        Pac global = smallPac();
        global.restoreProvidedSamples(global.getNumberOfSamples());
        global.startRound();
        assertFalse(global.hasBudgetLeft(), "under GLOBAL the restored counter still ends the run");
    }

    @Test
    public void unrecognisedModeFallsBackToGlobal() {
        Pac pac = smallPac();
        pac.setBudgetMode(null);
        assertEquals(Pac.BudgetMode.GLOBAL, pac.getBudgetMode());
    }
}
