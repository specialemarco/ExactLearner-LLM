package org.experiments.workload;

import org.experiments.logger.Cache;

public interface BaseWorkload extends Runnable {

    // Local is the default: llm_server.py serves whatever weights it was started
    // with, so a list of local names could only reject a model, never check it.
    // A hardcoded one did, after the pre-warm -- deepseek-r1-14b cost jobs
    // 4130779/4130781 6.1 hours that way. The model is defined by
    // scripts/models/<model>.env.
    static BaseWorkload forModel(String model, String system, String message, int maxTokens, Cache cache) {
        if (model.equals("false")) {
            return new FalseWorkload(cache, message);
        }
        if (OpenAIWorkload.supportedModels.contains(model)) {
            return new OpenAIWorkload(model, system, message, maxTokens, cache);
        }
        return new OllamaWorkload(model, system, message, maxTokens, cache);
    }
}
