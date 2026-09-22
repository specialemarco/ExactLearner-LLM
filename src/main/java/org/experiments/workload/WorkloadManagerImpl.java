package org.experiments.workload;

import org.experiments.Environment;
import org.experiments.Result;
import org.experiments.logger.Cache;
import org.experiments.logger.CacheManager;
import org.experiments.task.ExperimentTask;
import org.experiments.task.Task;

public class WorkloadManagerImpl implements WorkloadManager {
    private final String model;
    private final String system;
    private final int maxTokens;
    private final String queryFormat;
    private final String ontologyName;
    private final Cache cache;
    private final WorkLoadCounter workLoadCounter;

    public WorkloadManagerImpl(String model, String system, int maxTokens, String queryFormat, String ontologyName, CacheManager cache) {
        this(model, system, maxTokens, queryFormat, ontologyName, cache, null);
    }

    public WorkloadManagerImpl(String model, String system, int maxTokens, String queryFormat, String ontologyName, CacheManager cache, WorkLoadCounter workLoadCounter) {
        this.model = model;
        this.system = system;
        this.maxTokens = maxTokens;
        this.queryFormat = queryFormat;
        this.ontologyName = ontologyName;
        this.cache = cache.getCache(model, system);
        this.workLoadCounter = workLoadCounter;
    }

    public boolean runWorkload(String message) {
        Runnable work = BaseWorkload.forModel(model, system, message, maxTokens, cache);
        Task task = new ExperimentTask("statementsQuerying", model, queryFormat, ontologyName, message, system, work);
        Environment.run(task, cache);
        if (workLoadCounter != null) {
            workLoadCounter.task(message);
        }
        if (cache != null) {
            return cache.isStrictlyTrue(message);
        }
        return new Result(task.getFileName()).isStrictlyTrue(message);
    }
}
