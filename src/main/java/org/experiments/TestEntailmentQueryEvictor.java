package org.experiments;

import org.exactlearner.engine.ELEngine;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Probe for the ELK entailment-query-state growth that JFR found on job 4059565,
 * where 86% of the learner's Java CPU was ELK traversing internal query state:
 * ConcurrentHashMap$Traverser.advance() 28%, EntryCollection$EntryIterator.next()
 * 18%, ArrayList.addAll() 13%, LinearProbing.getPosition() 12%,
 * EntailmentQueryState$1.apply() 9%.
 *
 * ELEngine creates one non-buffering reasoner and disposes it only at run end, so
 * every isEntailed() registers a QueryState in EntailmentQueryState.queried_ (a
 * ConcurrentHashMap). ELK does try to trim it -- each query calls the evictor
 * named by elk.reasoner.entailmentquery.evictor, default RecencyEvictor(512,0.75)
 * -- but the guard is
 *
 *     doNotEvict_ = queryState.isLocked() || lastQueries_.contains(getQuery())
 *
 * and ElkReasoner.isEntailed() locks every state and never unlocks it. So the
 * evictor scans an ever-growing candidate set and rejects all of it, which is
 * both the leak and the quadratic. Measured here as the "locked" column tracking
 * "queried_" exactly.
 *
 *   java -cp target/classes:$(cat cp.txt) org.experiments.TestEntailmentQueryEvictor \
 *        [ontology.owl] [totalQueries] [blockSize] [mode]
 *
 * Modes:
 *   none         baseline; queried_ grows 1:1 with distinct queries
 *   recreate:N   dispose and rebuild the reasoner every N queries
 *   unlock:N     unlock every state every N queries, letting the evictor work
 *   engine       route queries through ELEngine, so the shipped fix is what is
 *                measured; controlled by EXACTLEARNER_ELK_UNLOCK and
 *                EXACTLEARNER_ELK_UNLOCK_INTERVAL rather than by this mode
 *
 * The evictor can also be set on the command line -- ELK's ConfigurationFactory
 * reads elk.properties off the classpath and then copies System.getProperties()
 * over it, so -D wins over the file:
 *
 *   -Delk.reasoner.entailmentquery.evictor=\
 *     'org.semanticweb.elk.util.collections.RecencyEvictor(1024,0.75)'
 */
public class TestEntailmentQueryEvictor {

    private static final String EVICTOR_KEY = "elk.reasoner.entailmentquery.evictor";

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0]
                : "data_paclo/owl2bench-1-el-class_names/expertOntology.owl";
        int total = args.length > 1 ? Integer.parseInt(args[1]) : 20000;
        int block = args.length > 2 ? Integer.parseInt(args[2]) : 2000;
        String mode = args.length > 3 ? args[3] : "none";

        int recreateEvery = intervalFor(mode, "recreate:");
        int unlockEvery = intervalFor(mode, "unlock:");

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new File(path));

        List<OWLClass> classes = new ArrayList<>(ontology.getClassesInSignature());
        List<OWLObjectProperty> props = new ArrayList<>(ontology.getObjectPropertiesInSignature());
        ElkReasonerFactory factory = new ElkReasonerFactory();

        System.out.println("ontology   : " + path);
        System.out.println("axioms     : " + ontology.getAxiomCount()
                + "  classes=" + classes.size() + "  objectProperties=" + props.size());
        System.out.println("mode       : " + mode);
        System.out.println("-D" + EVICTOR_KEY + " = " + System.getProperty(EVICTOR_KEY, "(unset)"));

        boolean viaEngine = "engine".equals(mode);
        ELEngine engine = viaEngine ? new ELEngine(ontology) : null;
        OWLReasoner reasoner = viaEngine
                ? (OWLReasoner) field(engine, "myReasoner")
                : factory.createNonBufferingReasoner(ontology);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
        System.out.println("ELK resolved: " + describe(resolvedEvictor(reasoner)));
        if (viaEngine) {
            System.out.println("EXACTLEARNER_ELK_UNLOCK          = "
                    + System.getenv().getOrDefault("EXACTLEARNER_ELK_UNLOCK", "(unset -> true)"));
            System.out.println("EXACTLEARNER_ELK_UNLOCK_INTERVAL = "
                    + System.getenv().getOrDefault("EXACTLEARNER_ELK_UNLOCK_INTERVAL",
                            "(unset -> 2000)"));
        }

        Map<?, ?> queried = queriedMap(reasoner);
        System.out.println();
        System.out.printf("%10s %12s %12s %12s %12s %10s%n",
                "queries", "queried_", "locked", "block ms", "heap MB", "fix ms");

        int nc = classes.size();
        long answerChecksum = 1L;
        int entailedCount = 0;
        long fixNanos = 0;
        long blockStart = System.nanoTime();

        for (int i = 1; i <= total; i++) {
            OWLClass a = classes.get(i % nc);
            OWLClass b = classes.get((i / nc) % nc);
            OWLClass c = classes.get((i / (nc * nc)) % nc);

            OWLClassExpression lhs = props.isEmpty()
                    ? df.getOWLObjectIntersectionOf(a, b)
                    : df.getOWLObjectIntersectionOf(a,
                            df.getOWLObjectSomeValuesFrom(props.get(i % props.size()), b));

            OWLAxiom query = df.getOWLSubClassOfAxiom(lhs, c);
            boolean answer = viaEngine ? engine.entailed(query) : reasoner.isEntailed(query);
            // Order-sensitive so a shifted or dropped answer cannot cancel out.
            answerChecksum = answerChecksum * 31L + (answer ? 1L : 0L);
            if (answer) {
                entailedCount++;
            }

            if (recreateEvery > 0 && i % recreateEvery == 0) {
                long t0 = System.nanoTime();
                reasoner.dispose();
                reasoner = factory.createNonBufferingReasoner(ontology);
                reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
                queried = queriedMap(reasoner);
                fixNanos += System.nanoTime() - t0;
            } else if (unlockEvery > 0 && i % unlockEvery == 0) {
                long t0 = System.nanoTime();
                unlockAll(queried);
                fixNanos += System.nanoTime() - t0;
            }

            if (i % block == 0) {
                long ms = (System.nanoTime() - blockStart) / 1_000_000L;
                Runtime rt = Runtime.getRuntime();
                long heapMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
                System.out.printf("%10d %12d %12d %12d %12d %10d%n",
                        i, queried.size(), lockedCount(queried), ms, heapMb,
                        fixNanos / 1_000_000L);
                fixNanos = 0;
                blockStart = System.nanoTime();
            }
        }
        System.out.println();
        System.out.println("entailed    : " + entailedCount + " of " + total);
        System.out.println("checksum    : " + answerChecksum
                + "   (must match across modes -- eviction may only cost recomputation)");
        reasoner.dispose();
    }

    private static int intervalFor(String mode, String prefix) {
        return mode.startsWith(prefix) ? Integer.parseInt(mode.substring(prefix.length())) : 0;
    }

    /**
     * Releases every lock so the evictor's doNotEvict_ guard stops holding. This
     * reaches into ELK internals to measure whether unlocking is enough on its
     * own; it is a measurement, not a proposed production change.
     */
    private static void unlockAll(Map<?, ?> queried) throws Exception {
        for (Object state : queried.values()) {
            Method unlock = state.getClass().getDeclaredMethod("unlock");
            unlock.setAccessible(true);
            int guard = 0;
            while ((Integer) field(state, "lockedCount") > 0 && guard++ < 64) {
                unlock.invoke(state);
            }
        }
    }

    /** ElkReasoner.config_ is the ReasonerConfiguration the reasoner actually resolved. */
    private static Object resolvedEvictor(OWLReasoner reasoner) throws Exception {
        Object config = field(reasoner, "config_");
        return config.getClass().getMethod("getParameter", String.class)
                .invoke(config, EVICTOR_KEY);
    }

    /** ElkReasoner.reasoner_ -> AbstractReasonerState.entailmentQueryState -> queried_ */
    private static Map<?, ?> queriedMap(OWLReasoner reasoner) throws Exception {
        Object internal = field(reasoner, "reasoner_");
        Object queryState = field(internal, "entailmentQueryState");
        return (Map<?, ?>) field(queryState, "queried_");
    }

    /**
     * A QueryState survives eviction while doNotEvict_ holds, which is
     * isLocked() || lastQueries_.contains(getQuery()). If every state is locked,
     * no evictor capacity can ever trim the map.
     */
    private static int lockedCount(Map<?, ?> queried) throws Exception {
        int locked = 0;
        for (Object state : queried.values()) {
            if ((Integer) field(state, "lockedCount") > 0) {
                locked++;
            }
        }
        return locked;
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException keepWalking) {
                // declared on a superclass
            }
        }
        throw new NoSuchFieldException(name + " not found on " + target.getClass().getName());
    }

    /** Builders print their capacity in toString(), which is the number that matters. */
    private static String describe(Object builder) {
        return builder == null ? "null (no evictor configured)"
                : builder + "  [" + builder.getClass().getName() + "]";
    }
}
