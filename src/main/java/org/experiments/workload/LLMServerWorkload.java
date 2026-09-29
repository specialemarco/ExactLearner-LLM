package org.experiments.workload;

import org.exactlearner.connection.LLMServerBridge;
import org.experiments.logger.Cache;

public class LLMServerWorkload implements BaseWorkload {
    private final String model;
    private final String system;
    private final String query;
    private final int maxTokens;
    public static final int timeout = 1000 * 60; // 1 minute
    // ~9 minutes of retries. It used to retry forever, so a dead server hung the
    // job until walltime; failing lets the checkpoint be resumed instead.
    static final int MAX_ATTEMPTS = 10;
    public final Cache cache;

    public LLMServerWorkload(String model, String system, String query, int maxTokens, Cache cache) {
        this.model = model;
        this.system = system;
        this.query = query;
        this.maxTokens = maxTokens;
        this.cache = cache;
    }

    @Override
    public void run() {
        LLMServerBridge bridge = new LLMServerBridge(model, maxTokens);
        String response = bridge.ask(query, system);
        for (int attempt = 1; response == null; attempt++) {
            if (attempt >= MAX_ATTEMPTS) {
                throw new IllegalStateException("No response from " + bridge.getUrl()
                        + " after " + MAX_ATTEMPTS + " attempts; is llm_server.py running?");
            }
            System.out.println("Could not get a response from the LLM server (attempt "
                    + attempt + "/" + MAX_ATTEMPTS + "). Trying again in " + timeout / 1000 + " s.");
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while retrying the LLM server", e);
            }
            response = bridge.ask(query, system);
        }
        cache.storeQuery(query, response);
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
