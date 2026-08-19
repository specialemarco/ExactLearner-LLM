package org.configurations;
import java.util.List;
import java.util.stream.Collectors;

import org.utility.PacloDataset;

public class Configuration {

    private List<String> models;
    private List<String> ontologies;
    private String system;
    private int maxTokens;
    private String type;
    private List<String> systems;
    private List<QueryFormat> queryFormats;
    private String queryFormat;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }

    public List<String> getOntologies() {
        return ontologies;
    }

    public void setOntologies(List<String> ontologies) {
        // Single choke point for every config: datasets are read from
        // data_paclo/, so a path into someone else's scratch space is
        // rewritten to the local copy. See PacloDataset.resolve.
        this.ontologies = ontologies.stream().map(PacloDataset::resolve).collect(Collectors.toList());
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system.trim();
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getQueryFormat() {
        return queryFormat.trim();
    }

    public void setQueryFormat(String queryFormat) {
        this.queryFormat = queryFormat.trim();
    }

    public String toString() {
        return "Configuration{" +
                "models=" + models +
                ", ontologies=" + ontologies +
                ", system='" + system + '\'' +
                ", maxTokens=" + maxTokens +
                ", queryFormat=" + queryFormat +
                ", type='" + type + '\'' +
                '}';
    }

    public List<String> getSystems() {
        return systems;
    }

    public void setSystems(List<String> systems) {
        this.systems = systems;
    }

    public List<QueryFormat> getQueryFormats() {
        return queryFormats;
    }

    public void setQueryFormats(List<QueryFormat> queryFormats) {
        this.queryFormats = queryFormats;
    }

    public static class QueryFormat {
        private String name;
        private String axiom;
        private String conceptName;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAxiom() {
            return axiom;
        }

        public void setAxiom(String axiom) {
            this.axiom = axiom;
        }

        public String getConceptName() {
            return conceptName;
        }

        public void setConceptName(String conceptName) {
            this.conceptName = conceptName;
        }

    }
}
