package org.configurations;

import java.util.List;
import java.util.stream.Collectors;

import org.utility.PacloDataset;

/**
 * The YAML schema, bound by SnakeYAML through the setters: every key a config
 * uses needs a setter here, and an unknown key fails the load.
 */
public class Configuration {
    private List<String> models;
    private List<String> ontologies;
    private String system;
    private int maxTokens;
    private String type;
    private String queryFormat;
    // Null when the config leaves them out; the launcher then keeps 0.2 / 0.1.
    private Double epsilon;
    private Double delta;

    public List<String> getModels() { return models; }
    public void setModels(List<String> models) { this.models = models; }

    public List<String> getOntologies() { return ontologies; }
    // Datasets are read from data_paclo/, so a path into someone else's scratch
    // space is rewritten to the local copy. See PacloDataset.resolve.
    public void setOntologies(List<String> ontologies) {
        this.ontologies = ontologies.stream().map(PacloDataset::resolve).collect(Collectors.toList());
    }

    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system.trim(); }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    // Throws when the config has no queryFormat; only the LLM launchers ask.
    public String getQueryFormat() { return queryFormat.trim(); }
    public void setQueryFormat(String queryFormat) { this.queryFormat = queryFormat; }

    public Double getEpsilon() { return epsilon; }
    public void setEpsilon(Double epsilon) { this.epsilon = epsilon; }

    public Double getDelta() { return delta; }
    public void setDelta(Double delta) { this.delta = delta; }
}
