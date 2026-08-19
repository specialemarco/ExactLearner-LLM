package org.utility;

import org.apache.commons.collections4.IteratorUtils;
import org.apache.jena.atlas.lib.Pair;
import org.coode.owlapi.manchesterowlsyntax.ManchesterOWLSyntaxEditorParser;
import org.exactlearner.parser.OWLParserImpl;
import org.jetbrains.annotations.NotNull;
import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ParserException;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.util.*;
import uk.ac.manchester.cs.owl.owlapi.OWLDataFactoryImpl;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;

import java.io.File;
import java.nio.file.FileSystems;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class OntologyManipulator {
    public static int computeOntologySize(String ontologyFileName) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = null;
        try {
            ontology = manager.loadOntologyFromOntologyDocument(new File(ontologyFileName));
        } catch (OWLOntologyCreationException e) {
            throw new RuntimeException(e);
        }
        return OntologyManipulator.filterUnusedAxioms(ontology.getAxioms()).size();
    }

    public static OWLOntology getInferredOntology(String ontologyName) throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new File(ontologyName));
        OWLReasonerFactory reasonerFactory = new Reasoner.ReasonerFactory();
        OWLReasoner reasoner = reasonerFactory.createReasoner(ontology);
        // Precompute inferences
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.OBJECT_PROPERTY_HIERARCHY);
        // Generate inferred axioms
        List<InferredAxiomGenerator<? extends OWLAxiom>> generators = new ArrayList<>();
        generators.add(new InferredSubClassAxiomGenerator());
        generators.add(new InferredEquivalentClassAxiomGenerator());
        // Create the inferred ontology
        InferredOntologyGenerator iog = new InferredOntologyGenerator(reasoner, generators);
        OWLOntology inferredOntology = manager.createOntology();
        iog.fillOntology(manager.getOWLDataFactory(), inferredOntology);
        manager.addAxioms(inferredOntology, ontology.getAxioms());
        return inferredOntology;
    }

    public static OWLAxiom createAxiomFromString(String query, OWLOntology ontology) {
        //query = query.replace("SubClassOf", "SubClassOf:");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        ManchesterOWLSyntaxEditorParser parser = getManchesterOWLSyntaxEditorParser(ontology, manager, query);
        OWLAxiom axiom = null;
        try {
            axiom = parser.parseAxiom();
        } catch (ParserException e) {
            System.err.println("Error parsing axiom: " + e.getMessage());
        }
        return axiom;
    }

    public static Set<String> parseAxioms(Set<OWLAxiom> axioms) {
        return axioms.stream().map(new ManchesterOWLSyntaxOWLObjectRendererImpl()::render).collect(Collectors.toSet());
    }

    public static ManchesterOWLSyntaxEditorParser getManchesterOWLSyntaxEditorParser(OWLOntology rootOntology, OWLOntologyManager manager, String axiomResult) {
        Set<OWLOntology> importsClosure = rootOntology.getImportsClosure();
        OWLEntityChecker entityChecker = new ShortFormEntityChecker(
                new BidirectionalShortFormProviderAdapter(manager, importsClosure,
                        new SimpleShortFormProvider()));

        ManchesterOWLSyntaxEditorParser parser = new ManchesterOWLSyntaxEditorParser(manager.getOWLDataFactory(), axiomResult);
        parser.setDefaultOntology(rootOntology);
        parser.setOWLEntityChecker(entityChecker);
        return parser;
    }

    private static Set<String> checkClosure(Set<String> trueAnswers, Set<String> notTrueAnswers) {
        Set<Pair<String, String>> truePairs = getPair(trueAnswers);
        Set<Pair<String, String>> notTruePairs = getPair(notTrueAnswers);

        var result = notTruePairs.stream().filter(pair ->
                        truePairs.stream()
                                .anyMatch(first -> first.getLeft().equals(pair.getLeft()) &&
                                        truePairs.stream()
                                                .anyMatch(second -> second.getRight().equals(pair.getRight()) &&
                                                        first.getRight().equals(second.getLeft()))))
                .map(pair -> pair.getLeft() + " SubClassOf " + pair.getRight())
                .collect(Collectors.toSet());
        System.out.println(result);
        return result;
    }

    private static Set<Pair<String, String>> getPair(Set<String> answers) {
        try {
            return answers.stream().map(a -> {
                String[] split = a.split(" SubClassOf ");
                return new Pair<>(split[0].trim(), split[1].trim());
            }).collect(Collectors.toSet());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Set<OWLAxiom> filterUnusedAxioms(Set<OWLAxiom> axioms) {
        return axioms.stream().filter(axiom -> axiom.isOfType(AxiomType.SUBCLASS_OF)
                        || axiom.isOfType(AxiomType.EQUIVALENT_CLASSES))
                        // The following axioms cannot be learned by the current implementation of the ExactLearner, so we filter them out
                        //|| axiom.isOfType(AxiomType.SUB_OBJECT_PROPERTY)
                        //|| axiom.isOfType(AxiomType.EQUIVALENT_OBJECT_PROPERTIES)
                        //|| axiom.isOfType(AxiomType.OBJECT_PROPERTY_DOMAIN)
                        //|| axiom.isOfType(AxiomType.DISJOINT_CLASSES)
                        //|| axiom.isOfType(AxiomType.getAxiomType("ObjectOneOf")))
                .collect(Collectors.toSet());
    }

    public static String getOntologyShortName(String model, String ontology) {
        String separator = FileSystems.getDefault().getSeparator();
        // Check if the results directory exists
        if (!new File("results").exists()) {
            new File("results").mkdir();
        }
        if (!new File("results" + separator + "axiomsQuerying").exists()) {
            new File("results" + separator + "axiomsQuerying").mkdir();
        }
        String shortOntology = ontology.substring(ontology.lastIndexOf(separator) + 1);
        shortOntology = shortOntology.substring(0, shortOntology.lastIndexOf('.'));
        return "results" + separator + "axiomsQuerying" + separator + model.replace(":", "-") + '_' + shortOntology;
    }

    public static List<OWLSubClassOfAxiom> getAllPossibleAxiomsCombinations(OWLOntology expectedOntology) {
        var parser = new OWLParserImpl(expectedOntology);
        return getAllPossibleAxiomsCombinationsOWL(parser.getClasses().get(), parser.getObjectProperties()).stream().flatMap(i -> IteratorUtils.toList(i.iterator()).stream()).toList();
    }

    public static Set<String> getAllPossibleAxiomsCombinations(Collection<String> classes, Collection<String> properties) {
        /*There are three types of statements:
        1. (A ∩ B) ⊑ C
        2. B ⊑ ∃R.A
        3. ∃R.A ⊑ B
        * Generate all possible combinations of these statements
         */
        var statement1 = classes.stream().flatMap(c1 -> classes.stream().flatMap(c2 -> classes.stream()
                        .filter(c3 -> !c3.equals(c1) && !c3.equals(c2))
                        .map(c3 -> "( " + c1 + " and " + c2 + " ) SubClassOf: " + c3)))
                .collect(Collectors.toSet());
        var statement2 = classes.stream().flatMap(c1 -> properties.stream().flatMap(p -> classes.stream().map(c2 -> c1 + " SubClassOf: " + p + " some " + c2)))
                .collect(Collectors.toSet());
        var statement3 = classes.stream().flatMap(c1 -> properties.stream().flatMap(p -> classes.stream().map(c2 -> p + " some " + c1 + " SubClassOf: " + c2)))
                .collect(Collectors.toSet());
        //check empty set
        statement1.addAll(statement2);
        statement1.addAll(statement3);
        return statement1;
    }

    public static List<Iterable<OWLSubClassOfAxiom>> getAllLimitedAxiomCombinationOWL(Collection<OWLClass> classes, Collection<OWLObjectProperty> properties, int limit) {
        List<Iterable<OWLSubClassOfAxiom>> iters = getAllPossibleAxiomsCombinationsOWL(classes, properties);
        List<Iterable<OWLSubClassOfAxiom>> limited = new ArrayList<>();
        limited.add(() -> new LimitedIterator(iters.get(0), limit, (classes.size()*(classes.size()-1))));
        limited.add(() -> new LimitedIterator(iters.get(1), limit, (classes.size()*(classes.size()-1)*(classes.size()-2)/2)));
        limited.add(() -> new LimitedIterator(iters.get(2), limit, (classes.size()*classes.size()*properties.size())));
        limited.add(() -> new LimitedIterator(iters.get(3), limit, (classes.size()*classes.size()*properties.size())));
        return limited;
    }

    public static List<Iterable<OWLSubClassOfAxiom>> getAllPossibleAxiomsCombinationsOWL(Collection<OWLClass> classes, Collection<OWLObjectProperty> properties) {
        /*There are four types of axioms:
        1. A ⊑ B
        2. (A ∩ B) ⊑ C
        3. B ⊑ ∃R.A
        4. ∃R.A ⊑ B
        * Generate all possible combinations of these statements
         */
        OWLDataFactory factory = OWLManager.createOWLOntologyManager().getOWLDataFactory();

        List<Iterable<OWLSubClassOfAxiom>> statements = new ArrayList<>();

        // All A ⊑ B where A and B are unique
        statements.add(() -> classes.stream()
                        .flatMap(c1 -> classes.stream()
                                .filter(c2 -> c2 != c1)
                                .map(c2 -> factory.getOWLSubClassOfAxiom(c1, c2))).iterator());

        // All (A ∩ B) ⊑ C where A, B and C are unique
        statements.add(() -> new ComplexIterator(new ArrayList<>(classes), factory));

        // B ⊑ ∃R.A
        statements.add(() -> classes.stream().flatMap(c1 -> properties.stream().flatMap(p -> classes.stream().map(c2 ->
                        factory.getOWLSubClassOfAxiom(c1, factory.getOWLObjectSomeValuesFrom(p,c2))
                ))).iterator());

        // ∃R.A ⊑ B
        statements.add(() -> classes.stream().flatMap(c1 -> properties.stream().flatMap(p -> classes.stream().map(c2 ->
                        factory.getOWLSubClassOfAxiom(factory.getOWLObjectSomeValuesFrom(p,c1), c2)
                ))).iterator());
        return statements;
    }

    public static class ComplexIterator implements Iterator<OWLSubClassOfAxiom> {
        private final List<OWLClass> classes;
        private int i1;
        private int i2;
        private int i3;
        private final OWLDataFactory factory;

        public ComplexIterator(List<OWLClass> classes, OWLDataFactory factory) {
            this.classes = classes;
            this.factory = factory;
            i1 = 0;
            i2 = 1;
            i3 = 0;
        }

        @Override
        public boolean hasNext() {
            if (i3 == i1 || i3 == i2) {
                i3++;
                return hasNext();
            }
            if (i3 >= classes.size()) {
                i2++;
                i3 = 0;
                if (i2 >= classes.size()) {
                    i1++;
                    i2 = i1 + 1;
                }
            }
            return !(i1 + 1 >= classes.size());
        }

        @Override
        public OWLSubClassOfAxiom next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            OWLSubClassOfAxiom axiom = factory.getOWLSubClassOfAxiom(
                    factory.getOWLObjectIntersectionOf(classes.get(i1), classes.get(i2)), classes.get(i3));
            i3++;
            return axiom;
        }
    }

        public static class LimitedIterator implements Iterator<OWLSubClassOfAxiom> {
            private OWLSubClassOfAxiom current;
            private final Iterator<OWLSubClassOfAxiom> iterator;
            private final int limit;
            private final int length;
            private final Random random;

            public LimitedIterator(Iterable<OWLSubClassOfAxiom> iterable, int limit, int length) {
                this(iterable.iterator(), limit, length);
            }

            public LimitedIterator(Iterator<OWLSubClassOfAxiom> iterator, int limit, int length) {
                this.iterator = iterator;
                this.limit = limit;
                this.length = length;
                this.random = new Random(24);
                current = null;
            }


            @Override
            public boolean hasNext() {
                if (current != null) {
                    return true;
                }
                while (iterator.hasNext()) {
                    OWLSubClassOfAxiom possible = iterator.next();
                    if (random.nextInt(length) < limit) {
                        current = possible;
                        return true;
                    }
                }
                return false;
            }

            @Override
            public OWLSubClassOfAxiom next() {
                if (this.hasNext()) {
                    OWLSubClassOfAxiom axiom = current;
                    current = null;
                    return axiom;
                }
                throw new NoSuchElementException();
            }

    }
}
