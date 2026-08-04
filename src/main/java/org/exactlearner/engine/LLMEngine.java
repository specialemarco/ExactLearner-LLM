package org.exactlearner.engine;

import org.exactlearner.parser.OWLParser;
import org.exactlearner.parser.OWLParserImpl;
import org.exactlearner.renderer.AnnotationShorFormProvider;
import org.experiments.workload.WorkloadManager;
import org.experiments.workload.WorkloadManagerImpl;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class LLMEngine implements BaseEngine {

    private final OWLOntology ontology;
    private final OWLOntologyManager manager;
    private final OWLParser parser;
    private final WorkloadManager workloadManager;
    private final OWLObjectRenderer renderer;
    private final AxiomSimplifier simplifier;

    public LLMEngine(OWLOntology ontology, String ontologyName, String model, String system, Integer maxTokens, OWLOntologyManager manager) {
        this.ontology = ontology;
        this.manager = manager;
        this.parser = new OWLParserImpl(ontology);
        String queryFormat = "";
        this.workloadManager = new WorkloadManagerImpl(model, system, maxTokens, queryFormat, ontologyName, null);
        this.renderer = createRenderer(ontology);
        simplifier = null;
    }

    public LLMEngine(OWLOntology ontology, OWLOntologyManager manager, WorkloadManager workloadManager) {
        this(ontology, manager, workloadManager, new OWLParserImpl(ontology), null);
    }

    public LLMEngine(OWLOntology ontology, OWLOntologyManager manager, WorkloadManager workloadManager, OWLParser parser, AxiomSimplifier simplifier) {
        this.ontology = ontology;
        this.manager = manager;
        this.parser = parser;
        this.workloadManager = workloadManager;
        this.renderer = createRenderer(ontology);
        this.simplifier = simplifier;
    }

    private OWLObjectRenderer createRenderer(OWLOntology ontology) {
        ManchesterOWLSyntaxOWLObjectRendererImpl renderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
        renderer.setShortFormProvider(new AnnotationShorFormProvider(ontology));
        return renderer;
    }

    @Override
    public OWLSubClassOfAxiom getSubClassAxiom(OWLClassExpression classA, OWLClassExpression classB) {
        return manager.getOWLDataFactory().getOWLSubClassOfAxiom(classA, classB);
    }


    protected Boolean runTaskAndGetResult(String message) {
        return workloadManager.runWorkload(buildMessage(message));
    }

    /**
     * Turns a rendered axiom into the exact string sent to the model, which is
     * also the cache key. Subclasses override this to add their own phrasing.
     *
     * Split out of runTaskAndGetResult so that the transformation has a single
     * definition. Anything that needs to know a query string WITHOUT issuing it
     * -- batch pre-warming, in particular -- must produce a byte-identical
     * string, or it writes cache entries the learner will never read.
     */
    protected String buildMessage(String rendered) {
        return rendered.replace("  ", " ");
    }

    /**
     * The query string this engine would send for a subclass axiom, without
     * sending it. Mirrors the rendering in entailed(OWLSubClassOfAxiom).
     *
     * Only valid for axioms whose superclass is not an intersection: those are
     * split into several queries when EXACTLEARNER_SPLIT is on, so a single
     * string cannot represent them. Precomputation only ever asks about pairs
     * of atomic classes, which is the intended use.
     */
    public String queryFor(OWLSubClassOfAxiom axiom) {
        return buildMessage(render(axiom));
    }

    /**
     * Every query string entailed(ax) would send, in the order it would send
     * them, without sending any of them.
     *
     * queryFor() is not enough for the decomposition path. It skips two things
     * entailed() does, and each one produces a cache entry the learner would
     * never read:
     *
     *   - the simplifier, which rewrites the axiom (and can answer outright,
     *     in which case no query is issued at all -- an empty list here);
     *   - the intersection split, which turns one axiom with a conjunctive
     *     superclass into one query per conjunct. decompose()'s right-hand scan
     *     asks exactly that shape, since the superclass is a tree node.
     *
     * Both are read off the same helpers entailed() uses, so the two cannot
     * drift apart. Returns an empty list for axiom types entailed() does not
     * support: prefetching something unaskable must be a no-op, not a throw.
     */
    public List<String> queriesFor(OWLAxiom ax) {
        if (ax.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
            List<String> queries = new ArrayList<>();
            for (OWLSubClassOfAxiom sax : ((OWLEquivalentClassesAxiom) ax).asOWLSubClassOfAxioms()) {
                queries.addAll(queriesFor(sax));
            }
            return queries;
        }
        if (!ax.isOfType(AxiomType.SUBCLASS_OF)) {
            return List.of();
        }

        OWLSubClassOfAxiom axiom = (OWLSubClassOfAxiom) ax;
        if (simplifier != null) {
            Optional<OWLSubClassOfAxiom> opt = simplifier.shorten(axiom);
            if (opt.isEmpty()) {
                return List.of();
            }
            axiom = opt.get();
        }
        if (splitEnabled() && axiom.getSuperClass() instanceof OWLObjectIntersectionOf intersection) {
            List<String> queries = new ArrayList<>();
            for (OWLClassExpression sup : intersection.getOperands()) {
                queries.add(buildMessage(render(getSubClassAxiom(axiom.getSubClass(), sup))));
            }
            return queries;
        }
        return List.of(buildMessage(render(axiom)));
    }

    /** The rendered form of an axiom, which is what gets fed to buildMessage. */
    private String render(OWLAxiom axiom) {
        return renderer.render(axiom).replaceAll("\r", " ").replaceAll("\n", " ");
    }

    /** Unset means on, matching the original inline check in entailed(). */
    private static boolean splitEnabled() {
        String split = System.getenv("EXACTLEARNER_SPLIT");
        return split == null || split.equals("true");
    }

    @Override
    public List<OWLClass> getClassesInSignature() {
        return parser.getOrderedClasses();
    }

    @Override
    public OWLEquivalentClassesAxiom getOWLEquivalentClassesAxiom(OWLClassExpression concept1, OWLClassExpression concept2) {
        return manager.getOWLDataFactory().getOWLEquivalentClassesAxiom(concept1, concept2);
    }

    @Override
    public OWLClassExpression getOWLObjectIntersectionOf(Set<OWLClassExpression> mySet) {
        return manager.getOWLDataFactory().getOWLObjectIntersectionOf(mySet);
    }

    @Override
    public Boolean entailed(OWLAxiom ax) {
        if (ax.isOfType(AxiomType.EQUIVALENT_CLASSES)) {
            OWLEquivalentClassesAxiom eax = (OWLEquivalentClassesAxiom) ax;
            for (OWLSubClassOfAxiom sax : eax.asOWLSubClassOfAxioms()) {
                if (!entailed(sax)) {
                    return false;
                }
            }
            return true;
        }

        if (ax.isOfType(AxiomType.SUBCLASS_OF)) {
            return entailed((OWLSubClassOfAxiom) ax);
        }

        throw new RuntimeException("Axiom type not supported " + ax);

    }

    @Override
    public Boolean entailed(Set<OWLAxiom> axioms) {
        for (OWLAxiom ax : axioms) {
            if (!entailed(ax)) {
                return false;
            }
        }
        return true;
    }

    private Boolean entailed(OWLSubClassOfAxiom axiom) {
        if (simplifier != null) {
            Optional<OWLSubClassOfAxiom> opt = simplifier.shorten(axiom);
            if (opt.isEmpty()) {
                return true;
            }
            axiom = opt.get();
        }
        if (splitEnabled() && axiom.getSuperClass() instanceof OWLObjectIntersectionOf intersection) {
            OWLClassExpression expression = axiom.getSubClass();
            for (OWLClassExpression sup : intersection.getOperands()) {
                if (!runTaskAndGetResult(render(getSubClassAxiom(expression, sup)))) {
                    return false;
                }
            }
            return true;
        }
        return runTaskAndGetResult(render(axiom));
    }

    @Override
    public OWLOntology getOntology() {
        return ontology;
    }

    @Override
    public void disposeOfReasoner() {
        System.out.flush();
    }

    @Override
    public void applyChange(OWLOntologyChange change) {
        manager.applyChange(change);
    }
}
