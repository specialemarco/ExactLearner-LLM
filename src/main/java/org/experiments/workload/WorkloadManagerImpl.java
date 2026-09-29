package org.experiments.workload;

import org.experiments.logger.Cache;
import org.experiments.logger.CacheManager;

public class WorkloadManagerImpl implements WorkloadManager {
    private final String model;
    private final String system;
    private final int maxTokens;
    private final Cache cache;
    private final WorkLoadCounter workLoadCounter;

    public WorkloadManagerImpl(String model, String system, int maxTokens, String queryFormat, String ontologyName, CacheManager cache) {
        this(model, system, maxTokens, queryFormat, ontologyName, cache, null);
    }

    public WorkloadManagerImpl(String model, String system, int maxTokens, String queryFormat, String ontologyName, CacheManager cache, WorkLoadCounter workLoadCounter) {
        this.model = model;
        this.system = system;
        this.maxTokens = maxTokens;
        this.cache = cache.getCache(model, system);
        this.workLoadCounter = workLoadCounter;
    }

    // Asks the model only on a cache miss; the workload writes its answer into
    // the cache, which is then the single place the result is read from.
    public boolean runWorkload(String message) {
        if (cache.isStrictlyTrue(message) == null) {
            BaseWorkload.forModel(model, system, message, maxTokens, cache).run();
        }
        if (workLoadCounter != null) {
            workLoadCounter.task(message);
        }
        return cache.isStrictlyTrue(message);
    }
}
