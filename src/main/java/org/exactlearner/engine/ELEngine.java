package org.exactlearner.engine;

import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class ELEngine implements BaseEngine {
    private final OWLReasoner myReasoner;
    private final OWLOntology myOntology;
    private final OWLOntologyManager myManager;
    private final ElkQueryStateUnlocker unlocker;
    private static final Logger LOGGER_ = LoggerFactory
            .getLogger(ELEngine.class);

    /**
     * Constructs a ELQueryEngine. This will answer "DL queries" using the
     * specified myReasoner. A short form provider specifies how entities are
     * rendered.
     *
     * @param ontology reasoning engine for the given ontology
     */
    public ELEngine(OWLOntology ontology) {
        myOntology = ontology;
        myManager = myOntology.getOWLOntologyManager();
        myReasoner = createReasoner(ontology);
        unlocker = ElkQueryStateUnlocker.forReasoner(myReasoner);
    }

    @Override
    public OWLOntology getOntology() {
        return myOntology;
    }

    public List<OWLClass> getClassesInSignature() {
        return myOntology.getClassesInSignature().stream().toList();
    }

    public OWLSubClassOfAxiom getSubClassAxiom(OWLClassExpression concept1, OWLClassExpression concept2) {
        return myManager.getOWLDataFactory().getOWLSubClassOfAxiom(concept1, concept2);
    }

    public OWLEquivalentClassesAxiom getOWLEquivalentClassesAxiom(OWLClassExpression concept1, OWLClassExpression concept2) {
        return myManager.getOWLDataFactory().getOWLEquivalentClassesAxiom(concept1, concept2);
    }

    public OWLClassExpression getOWLObjectIntersectionOf(Set<OWLClassExpression> mySet) {
        return myManager.getOWLDataFactory().getOWLObjectIntersectionOf(mySet);
    }

    // PARKED, NOT DEAD -- flagged "never used locally" because its only two call sites
    // (in entailed(OWLAxiom) below) sit inside that method's commented-out block. Keep
    // this and the commented block together: they are the two halves of the original
    // EQ-decomposition path, superseded by a direct myReasoner.isEntailed(ax) call.
    private Boolean entailedEQ(OWLSubClassOfAxiom subclassAxiom) {
        Boolean result = myReasoner.isEntailed(subclassAxiom);
        unlocker.afterQueries(1);
        return result;
        /*
        OWLClassExpression left = subclassAxiom.getSubClass();
        OWLClassExpression right = subclassAxiom.getSuperClass();

        Boolean workaround = false;

        OWLDataFactory dataFactory = myManager.getOWLDataFactory();

        OWLClass leftName;
        OWLAxiom leftDefinition = null;

        if (left.isAnonymous()) {
            leftName = dataFactory.getOWLClass(IRI.create("#temp001"));
            leftDefinition = dataFactory.getOWLSubClassOfAxiom(leftName, left);
            myManager.addAxiom(myReasoner.getRootOntology(), leftDefinition);
        } else {
            leftName = left.asOWLClass();
        }


        OWLClass rightName;
        OWLAxiom rightDefinition = null;
        if (right.isAnonymous()) {
            rightName = dataFactory.getOWLClass(IRI.create("#temp002"));
            rightDefinition = dataFactory.getOWLSubClassOfAxiom(right, rightName);
            myManager.addAxiom(myReasoner.getRootOntology(), rightDefinition);
        } else {
            rightName = right.asOWLClass();
        }


        myReasoner.flush();

        NodeSet<OWLClass> superClasses = myReasoner.getSuperClasses(leftName, false);


        if (!superClasses.isEmpty() && superClasses.containsEntity(rightName)) {
            workaround = true;
        } else {
            Node<OWLClass> equivClasses = myReasoner.getEquivalentClasses(leftName);
            if (!equivClasses.getEntities().isEmpty() && equivClasses.getEntities().contains(rightName)) {
                workaround = true;
            }
        }


        if (leftDefinition != null) {
            myManager.removeAxiom(myReasoner.getRootOntology(), leftDefinition);
        }

        if (rightDefinition != null) {
            myManager.removeAxiom(myReasoner.getRootOntology(), rightDefinition);
        }

        LOGGER_.trace("returning " + workaround);
        return workaround;

         */
    }

    public Boolean entailed(OWLAxiom ax) {
        Boolean result = myReasoner.isEntailed(ax);
        unlocker.afterQueries(1);
        return result;
       /*
        LOGGER_.trace("InputAx: {}", ax.toString());
        if (ax.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
            OWLEquivalentClassesAxiom eax = (OWLEquivalentClassesAxiom) ax;
            for (OWLSubClassOfAxiom sax : eax.asOWLSubClassOfAxioms()) {
                if (!entailedEQ(sax)) {
                    return false;
                }
            }
            return true;
        }

        if (ax.isOfType(AxiomType.SUBCLASS_OF)) {
            return entailedEQ((OWLSubClassOfAxiom) ax);
        }


        throw new RuntimeException("Axiom type not supported " + ax.toString());
         */
    }

    public Boolean entailed(Set<OWLAxiom> axioms) {
        Boolean result = myReasoner.isEntailed(axioms);
        unlocker.afterQueries(axioms.size());
        return result;
        /*
        for (OWLAxiom ax : axioms) {
            if (!entailed(ax)) {

                return false;
            }
        }
        return true;
         */
    }

    /**
     * Gets the superclasses of a class expression parsed from a string.
     *
     * @param superclass The string from which the class expression will be parsed.
     * @param direct     Specifies whether direct superclasses should be returned or
     *                   not.
     * @return The superclasses of the specified class expression If there was a
     * problem parsing the class expression.
     */
    public Set<OWLClass> getSuperClasses(OWLClassExpression superclass, boolean direct) {
        NodeSet<OWLClass> superClasses = myReasoner.getSuperClasses(superclass, direct);
        return superClasses.getFlattened();
    }

// --Commented out by Inspection START (30/04/2018, 15:29):
//    /** Gets the subclasses of a class expression parsed from a string.
//     *
//     * @param subclass
//     *            The string from which the class expression will be parsed.
//     * @param direct
//     *            Specifies whether direct subclasses should be returned or not.
//     * @return The subclasses of the specified class expression If there was a
//     *         problem parsing the class expression. */
//    public Set<OWLClass> getSubClasses(OWLClassExpression subclass, boolean direct) {
//        NodeSet<OWLClass> subClasses = myReasoner.getSubClasses(subclass, direct);
//        return subClasses.getFlattened();
//    }
// --Commented out by Inspection STOP (30/04/2018, 15:29)

    private OWLReasoner createReasoner(final OWLOntology rootOntology) {
        LOGGER_.trace("Reasoner created");

        System.out.flush();
        ElkReasonerFactory reasoningFactory = new ElkReasonerFactory();
        return reasoningFactory.createNonBufferingReasoner(rootOntology);
    }

    public void disposeOfReasoner() {
        LOGGER_.trace("Reasoner " + " disposed of");

        System.out.flush();
        myReasoner.dispose();
    }

    public void applyChange(OWLOntologyChange change) {
        myManager.applyChange(change);
    }

    /**
     * Releases ELK's per-query locks so ELK's own evictor can trim its query state.
     *
     * ELK registers a QueryState for every isEntailed() call in
     * EntailmentQueryState.queried_ and does try to evict from it on each later
     * query, but the guard is
     *
     *     doNotEvict_ = queryState.isLocked() || lastQueries_.contains(getQuery())
     *
     * and ElkReasoner.isEntailed() locks each state without ever unlocking it. So
     * the evictor rescans an ever-growing candidate set and rejects all of it:
     * both the map and the per-query cost grow without bound. JFR on job 4059565
     * measured 86% of the learner's Java CPU inside that scan -- with
     * EntailmentQueryState$1.apply(), the guard itself, at 8.69% -- and ~50 GB of
     * live heap held by the retained states.
     *
     * Unlocking after the result has been read lets the configured evictor
     * (RecencyEvictor(512,0.75) by default) do what it was built for. Measured by
     * org.experiments.TestEntailmentQueryEvictor on expertOntology.owl over 20,000
     * distinct queries: per-query cost stays flat at 0.23 ms instead of climbing
     * past 1.07 ms, for 0-4 ms of unlocking per 2,000 queries. Note that setting
     * elk.reasoner.entailmentquery.evictor does NOT help -- capacity 16 and 512
     * behave identically, because the guard rejects every candidate regardless.
     *
     * Re-asking an evicted query costs only recomputation, never correctness, and
     * decompose()'s queries are novel by construction, so re-asks are rare.
     *
     * This uses reflection because QueryState is package-private and ELK exposes no
     * public unlock path. It is pinned to ELK 0.6.0 and self-disables if the fields
     * move, so a version bump degrades to the old behaviour instead of failing.
     */
    private static final class ElkQueryStateUnlocker {

        private static final String ENABLED_VAR = "EXACTLEARNER_ELK_UNLOCK";
        private static final String INTERVAL_VAR = "EXACTLEARNER_ELK_UNLOCK_INTERVAL";
        private static final int DEFAULT_INTERVAL = 2000;
        /** A state locked more often than this is not something we understand; leave it. */
        private static final int MAX_UNLOCKS_PER_STATE = 64;

        private final Map<?, ?> queried;
        private final int interval;
        private int sinceLastSweep;
        private boolean disabled;
        private Method unlock;
        private Field lockedCount;

        private ElkQueryStateUnlocker(Map<?, ?> queried, int interval) {
            this.queried = queried;
            this.interval = interval;
            this.disabled = queried == null;
        }

        /**
         * Resolves ElkReasoner.reasoner_ -> AbstractReasonerState.entailmentQueryState
         * -> queried_. Reports whether it is on either way: a silently inactive flag
         * once cost this project a 24 h job.
         *
         * Deliberately System.out, not LOGGER_. The classpath carries slf4j-api with no
         * binding, so SLF4J falls back to a NOP logger and anything logged here would
         * never reach the job log -- which is the exact failure this message exists to
         * make impossible.
         */
        static ElkQueryStateUnlocker forReasoner(OWLReasoner reasoner) {
            if (!Boolean.parseBoolean(envOrDefault(ENABLED_VAR, "true"))) {
                System.out.println("ELK query-state unlocking OFF (" + ENABLED_VAR + "=false)");
                return new ElkQueryStateUnlocker(null, 0);
            }
            int interval = DEFAULT_INTERVAL;
            try {
                interval = Integer.parseInt(envOrDefault(INTERVAL_VAR,
                        String.valueOf(DEFAULT_INTERVAL)));
            } catch (NumberFormatException notANumber) {
                System.out.println(INTERVAL_VAR + " is not a number, using " + DEFAULT_INTERVAL);
            }
            try {
                Object internal = field(reasoner, "reasoner_");
                Object queryState = field(internal, "entailmentQueryState");
                Map<?, ?> queried = (Map<?, ?>) field(queryState, "queried_");
                System.out.println("ELK query-state unlocking ON, every " + interval + " queries");
                return new ElkQueryStateUnlocker(queried, interval);
            } catch (Exception elkInternalsMoved) {
                System.out.println("ELK query-state unlocking OFF: cannot reach queried_ "
                        + "(expected ELK 0.6.0). Runs stay correct but keep the old "
                        + "quadratic query-state growth. " + elkInternalsMoved);
                return new ElkQueryStateUnlocker(null, 0);
            }
        }

        /** Call after the query result has been read, never before. */
        void afterQueries(int count) {
            if (disabled) {
                return;
            }
            sinceLastSweep += count;
            if (sinceLastSweep < interval) {
                return;
            }
            sinceLastSweep = 0;
            try {
                for (Object state : queried.values()) {
                    if (unlock == null) {
                        unlock = state.getClass().getDeclaredMethod("unlock");
                        unlock.setAccessible(true);
                        lockedCount = state.getClass().getDeclaredField("lockedCount");
                        lockedCount.setAccessible(true);
                    }
                    int unlocks = 0;
                    while (lockedCount.getInt(state) > 0 && unlocks++ < MAX_UNLOCKS_PER_STATE) {
                        unlock.invoke(state);
                    }
                }
            } catch (Exception elkInternalsMoved) {
                System.out.println("ELK query-state unlocking failed; disabling for this "
                        + "engine. " + elkInternalsMoved);
                disabled = true;
            }
        }

        private static String envOrDefault(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isEmpty() ? fallback : value;
        }

        private static Object field(Object target, String name) throws Exception {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException declaredOnASuperclass) {
                    // keep walking up
                }
            }
            throw new NoSuchFieldException(name + " not found on " + target.getClass().getName());
        }
    }
}
