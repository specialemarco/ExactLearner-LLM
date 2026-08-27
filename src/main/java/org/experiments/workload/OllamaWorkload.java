package org.experiments.workload;

import org.exactlearner.connection.OllamaBridge;
import org.experiments.logger.Cache;
import org.experiments.logger.SmartLogger;

import java.net.URL;
import java.net.URLConnection;
import java.util.List;

public class OllamaWorkload implements BaseWorkload {
    private final String model;
    private final String system;
    private final String query;
    private final int maxTokens;
    public static final List<String> supportedModels = List.of("mistral", "deepseek-r1-32b", "mixtral", "llama2", "llama2:13b","llama2:70b","megadolphin","llama3","llama3.1","llama3.1:70b", "llava-llama3","llama3:70b","llama3-chatqa","dolphin-llama3", "qordmlwls/llama3.1-medical-v2");
    public static final int timeout = 1000 * 60; // 1 minute
    public final Cache cache;

    public OllamaWorkload(String model, String system, String query, int maxTokens) {
        this(model, system, query, maxTokens, null);
    }

    public OllamaWorkload(String model, String system, String query, int maxTokens, Cache cache) {
        this.model = model;
        this.system = system;
        this.query = query;
        this.maxTokens = maxTokens;
        this.cache = cache;
    }

    @Override
    public void run() {
        OllamaBridge bridge =  new OllamaBridge(model,maxTokens);
        //checkConnection(bridge);
        String response = bridge.ask(query, system);
        while (response == null) {
            // Sleep for 100 milliseconds to avoid overloading the Ollama bridge and retrying the request
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            response = bridge.ask(query, system);
            if (response == null) {
                System.out.println("Could not get a response from the Ollama bridge.");
                System.out.println("Trying again.");
            }
        }
        if (cache != null) {
            cache.storeQuery(query, response);
        } else {
            SmartLogger.log(query + ", " + response);
        }
    }

    // PARKED -- sole call site is commented out in the constructor above.
    // Note the asymmetry with OpenAIWorkload, which does call its equivalent: verify
    // whether skipping the check is intentional here (local Ollama) or an oversight.
    private void checkConnection(OllamaBridge bridge) {
        try {
            URLConnection connection = new URL(bridge.getUrl()).openConnection();
            connection.connect();
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect to the Ollama bridge.");
        }

    }
    public String getModel() {
        return model;
    }

    public String getSystem() {
        return system;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public String getQuery() {
        return query;
    }
}
