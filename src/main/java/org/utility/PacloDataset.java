package org.utility;

import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxParserImpl;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One PACLO dataset: a target ontology (expertOntology.owl) sitting beside
 * initialOntology.owl and baseSet.
 *
 * All such data lives in data_paclo/ at the repository root, in the same
 * layout on every machine. The folder is gitignored -- the data is not ours
 * to publish -- so each of us copies an identical tree in by hand, and
 * configs name datasets relative to it:
 *
 *     data_paclo/owl2bench-1-el-class_names/expertOntology.owl
 *
 * A path pointing at someone else's scratch space is rewritten to data_paclo
 * by resolve(), so an old config still runs here instead of failing on a
 * directory nobody else has.
 */
public final class PacloDataset {

    public static final String DATA_DIR = "data_paclo";

    private final File directory;
    private final OWLOntology initialOntology;
    private final OWLReasoner initialReasoner;
    private final Set<OWLClassExpression> baseSet;

    private PacloDataset(File directory, OWLOntology initialOntology,
                         OWLReasoner initialReasoner, Set<OWLClassExpression> baseSet) {
        this.directory = directory;
        this.initialOntology = initialOntology;
        this.initialReasoner = initialReasoner;
        this.baseSet = baseSet;
    }

    public File directory()                    { return directory; }
    public OWLOntology initialOntology()       { return initialOntology; }
    public OWLReasoner initialReasoner()       { return initialReasoner; }
    public Set<OWLClassExpression> baseSet()   { return baseSet; }

    /**
     * Maps a configured ontology path onto the local data_paclo copy. Paths
     * that already resolve are returned untouched, which leaves the small
     * ontologies under src/main/resources alone; only <dataset>/<file> is
     * carried across, since that much is identical everywhere.
     */
    public static String resolve(String configured) {
        if (configured == null || new File(configured).exists()) {
            return configured;
        }
        Path p = Path.of(configured);
        if (p.getNameCount() < 2) {
            return configured;
        }
        Path relocated = Path.of(DATA_DIR).resolve(p.subpath(p.getNameCount() - 2, p.getNameCount()));
        if (!relocated.toFile().exists()) {
            return configured;
        }
        System.out.println("Data: " + configured + " is not on this machine, reading " + relocated + " instead");
        return relocated.toString();
    }

    /**
     * Loads the dataset that targetFile belongs to, or returns null if either
     * companion file is absent -- callers treat that as "this is not a PACLO
     * dataset" and fall back to uniform PAC sampling.
     *
     * initialOntology.owl ships with an empty ABox (3677 individuals in its
     * signature, zero ClassAssertion axioms), so the real ABox is injected
     * from the ground truth before reasoning, exactly as Baris's
     * PACloOracle.java does. Without it every individual is untyped and
     * sampling degenerates silently. ELK, not a structural reasoner, because
     * types have to be derived rather than read back off the file.
     */
    public static PacloDataset loadBeside(File targetFile, OWLOntology groundTruth) throws Exception {
        File directory = targetFile.getParentFile();
        File initialOntologyFile = new File(directory, "initialOntology.owl");
        File baseSetFile = new File(directory, "baseSet");
        if (!initialOntologyFile.exists() || !baseSetFile.exists()) {
            return null;
        }

        OWLOntology initialOntology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(initialOntologyFile);
        initialOntology.add(groundTruth.getABoxAxioms(Imports.INCLUDED));

        OWLReasoner initialReasoner = new ElkReasonerFactory().createReasoner(initialOntology);
        initialReasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY, InferenceType.CLASS_ASSERTIONS);

        Set<OWLClassExpression> baseSet = readBaseSet(baseSetFile, initialOntology);

        // Sanity line, one reasoner call per baseSet concept: if the ABox
        // injection above ever stops working this drops to 0 typed
        // individuals and sampling is degenerate rather than wrong-looking.
        Set<OWLNamedIndividual> typed = new HashSet<>();
        for (OWLClassExpression ce : baseSet) {
            typed.addAll(initialReasoner.getInstances(ce).getFlattened());
        }
        System.out.println("PACLO dataset " + directory + ": baseSet " + baseSet.size()
                + " | individuals " + initialOntology.getIndividualsInSignature().size()
                + " (" + typed.size() + " with a baseSet type)");

        return new PacloDataset(directory, initialOntology, initialReasoner, baseSet);
    }

    /**
     * Parses the baseSet file into concept expressions -- a port of Baris's
     * Utils.readBaseSet. Non-comment lines are a concept IRI, a Manchester
     * existential restriction, or the literal "owl:Nothing" (special-cased
     * because the parser will not take bottom as an expression on its own).
     *
     * The entity checker exists because lines may name entities by their
     * fragment rather than a full IRI. It must also know owl:Thing under its
     * bracketed-IRI spelling: the C2 baseSet writes fillers as
     * <http://www.w3.org/2002/07/owl#Thing> and hands the parser exactly
     * that, and built-ins are not in the reference ontology's signature, so
     * without the extra entries C2 aborts with "Expected Class name".
     */
    public static Set<OWLClassExpression> readBaseSet(File file, OWLOntology referenceOntology) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();

        ManchesterOWLSyntaxParser parser =
                new ManchesterOWLSyntaxParserImpl(manager.getOntologyConfigurator(), df);
        parser.setDefaultOntology(referenceOntology);

        final Map<String, OWLEntity> byName = new HashMap<>();
        referenceOntology.signature().forEach(x -> byName.put(x.getIRI().getFragment(), x));
        for (OWLClass builtin : new OWLClass[]{df.getOWLThing(), df.getOWLNothing()}) {
            byName.put(builtin.getIRI().getFragment(), builtin);          // Thing / Nothing
            byName.put("owl:" + builtin.getIRI().getFragment(), builtin); // owl:Thing / owl:Nothing
            byName.put("<" + builtin.getIRI() + ">", builtin);            // <http://...owl#Thing>
        }
        parser.setOWLEntityChecker(new OWLEntityChecker() {
            private <T> T v(String name, Class<T> t) {
                OWLEntity e = byName.get(name);
                return t.isInstance(e) ? t.cast(e) : null;
            }
            @Override public OWLObjectProperty getOWLObjectProperty(String name)         { return v(name, OWLObjectProperty.class); }
            @Override public OWLNamedIndividual getOWLIndividual(String name)            { return v(name, OWLNamedIndividual.class); }
            @Override public OWLDatatype getOWLDatatype(String name)                     { return v(name, OWLDatatype.class); }
            @Override public OWLDataProperty getOWLDataProperty(String name)             { return v(name, OWLDataProperty.class); }
            @Override public OWLClass getOWLClass(String name)                           { return v(name, OWLClass.class); }
            @Override public OWLAnnotationProperty getOWLAnnotationProperty(String name) { return v(name, OWLAnnotationProperty.class); }
        });

        Set<OWLClassExpression> baseSet = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.equals("owl:Nothing")) {
                    baseSet.add(df.getOWLNothing());
                } else {
                    parser.setStringToParse(line);
                    baseSet.add(parser.parseClassExpression());
                }
            }
        }
        return baseSet;
    }
}
