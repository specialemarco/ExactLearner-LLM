package org.experiments;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.pac.Pac;
import org.sampler.ABoxInducedSubsumptionSampler;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxParserImpl;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LaunchLLMLearnerAInduced extends LaunchLLMLearner {

    private ABoxInducedSubsumptionSampler aboxSampler = null;

    public static void main(String[] args) {
        LogManager.getRootLogger().atLevel(Level.OFF);
        new LaunchLLMLearnerAInduced().run(args);
    }

    @Override
    protected OWLSubClassOfAxiom getCounterExample(Pac pac) throws Exception {
        if (aboxSampler == null) {
            initAboxSampler();
            if (aboxSampler == null) {
                System.out.println("Setup AInduced non disponibile per questa ontologia — fallback a PAC uniforme");
                return super.getCounterExample(pac);
            }
        }

        while (pac.getNumberOfProvidedSamples() < pac.getNumberOfSamples()) {
            pac.incrementProvidedSamples();
            OWLSubClassOfAxiom selectedAxiom = aboxSampler.sample();
            if (selectedAxiom == null) return null;
            if (pac.getNumberOfProvidedSamples() <= 10) {
                System.out.println("DEBUG sampled: " + selectedAxiom.getSubClass() + " SubClassOf " + selectedAxiom.getSuperClass());
            }
            boolean entH = elQueryEngineForH.entailed(selectedAxiom);
            boolean entT = llmQueryEngineForT.entailed(selectedAxiom);
            if (pac.getNumberOfProvidedSamples() <= 10) {
                System.out.println("DEBUG entH=" + entH + " entT(Mistral)=" + entT);
            }
            if (!entH && entT) {
                return getCounterExampleSubClassOf(selectedAxiom);
            }
        }
        return null;
    }

    private void initAboxSampler() throws Exception {
        File targetDir = targetFile.getParentFile();
        File initialOntologyFile = new File(targetDir, "initialOntology.owl");
        File baseSetFile = new File(targetDir, "baseSet");

        if (!initialOntologyFile.exists() || !baseSetFile.exists()) {
            return;
        }

        OWLOntologyManager initManager = OWLManager.createOWLOntologyManager();
        OWLOntology initialOntology = initManager.loadOntologyFromOntologyDocument(initialOntologyFile);

        // Fedele a PACloOracle.java: l'ABox vero viene iniettato a runtime
        // prendendolo da groundTruthOntology (il nostro expertOntology.owl)
        initialOntology.add(groundTruthOntology.getABoxAxioms(org.semanticweb.owlapi.model.parameters.Imports.INCLUDED));

        org.semanticweb.elk.owlapi.ElkReasonerFactory rf = new org.semanticweb.elk.owlapi.ElkReasonerFactory();
        OWLReasoner initialOntologyReasoner = rf.createReasoner(initialOntology);
        initialOntologyReasoner.precomputeInferences(
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_HIERARCHY,
            org.semanticweb.owlapi.reasoner.InferenceType.CLASS_ASSERTIONS);

        Set<OWLClassExpression> baseSet = readBaseSet(baseSetFile, initialOntology);

        OWLDataFactory df = OWLManager.getOWLDataFactory();
        aboxSampler = new ABoxInducedSubsumptionSampler(baseSet, initialOntologyReasoner, df);

        System.out.println("AInduced sampler pronto. BaseSet: " + baseSet.size() +
                " | Individui in initialOntology: " + initialOntology.getIndividualsInSignature().size());

        int withTypes = 0;
        for (org.semanticweb.owlapi.model.OWLNamedIndividual ind : initialOntology.getIndividualsInSignature()) {
            for (OWLClassExpression ce : baseSet) {
                if (initialOntologyReasoner.getInstances(ce).containsEntity(ind)) {
                    withTypes++;
                    break;
                }
            }
        }
        System.out.println("DEBUG: individui con almeno un tipo nel baseSet: " + withTypes + " su " + initialOntology.getIndividualsInSignature().size());
    }

    private Set<OWLClassExpression> readBaseSet(File f, OWLOntology referenceOntology) throws Exception {
        OWLOntologyManager om = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = om.getOWLDataFactory();

        Set<OWLClassExpression> baseSet = new HashSet<>();
        ManchesterOWLSyntaxParser parser = new ManchesterOWLSyntaxParserImpl(om.getOntologyConfigurator(), df);
        parser.setDefaultOntology(referenceOntology);

        final Map<String, OWLEntity> map = new HashMap<>();
        referenceOntology.signature().forEach(x -> map.put(x.getIRI().getFragment(), x));
        parser.setOWLEntityChecker(new OWLEntityChecker() {
            private <T> T v(String name, Class<T> t) {
                OWLEntity e = map.get(name);
                if (t.isInstance(e)) return t.cast(e);
                return null;
            }
            @Override public OWLObjectProperty getOWLObjectProperty(String name) { return v(name, OWLObjectProperty.class); }
            @Override public OWLNamedIndividual getOWLIndividual(String name) { return v(name, OWLNamedIndividual.class); }
            @Override public OWLDatatype getOWLDatatype(String name) { return v(name, OWLDatatype.class); }
            @Override public OWLDataProperty getOWLDataProperty(String name) { return v(name, OWLDataProperty.class); }
            @Override public OWLClass getOWLClass(String name) { return v(name, OWLClass.class); }
            @Override public OWLAnnotationProperty getOWLAnnotationProperty(String name) { return v(name, OWLAnnotationProperty.class); }
        });

        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            String line = reader.readLine();
            while (line != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    line = reader.readLine();
                    continue;
                }
                OWLClassExpression clsExpr;
                if (line.equals("owl:Nothing")) {
                    clsExpr = df.getOWLNothing();
                } else {
                    parser.setStringToParse(line);
                    clsExpr = parser.parseClassExpression();
                }
                baseSet.add(clsExpr);
                line = reader.readLine();
            }
        }
        return baseSet;
    }
}
