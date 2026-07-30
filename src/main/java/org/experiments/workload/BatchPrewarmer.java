package org.experiments.workload;

import org.exactlearner.connection.OllamaBridge;
import org.exactlearner.engine.LLMEngine;
import org.experiments.logger.Cache;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fills the cache for Learner.precomputation() using batched LLM calls.
 *
 * WHY THIS EXISTS
 * ---------------
 * precomputation() asks about every ordered pair of distinct classes -- 17,030
 * queries for C1 -- and each one is independent of every other. The learner
 * nonetheless issues them strictly one at a time, which runs the model at batch
 * size 1. Measured on 4xA100 with 8 CPU cores, batching 16 at a time is 6.9x
 * faster: 2.08 s/query against 14.40. That turns 69 hours of precomputation
 * into roughly 10, which is the difference between chaining three 24-hour jobs
 * and running one.
 *
 * HOW IT AVOIDS CHANGING THE EXPERIMENT
 * -------------------------------------
 * It does not touch the learner, the engine's control flow, or the workload
 * path. It runs BEFORE precomputation() and writes answers into the same cache
 * the learner reads, keyed identically. precomputation() then runs unmodified
 * and finds every answer already present, so it issues no LLM calls at all.
 * Environment.run() skips any task whose query is already cached, which is the
 * mechanism this relies on.
 *
 * The queries come from LLMEngine.queryFor(), the same code path that builds
 * them for a live query, so the cache keys match by construction rather than by
 * a duplicated string transformation that could drift.
 *
 * SAFETY
 * ------
 * - Resumable: already-cached queries are skipped, so an interrupted run can be
 *   restarted and will pick up where it stopped.
 * - Fails soft: any error leaves the cache as it was and lets the learner fall
 *   back to sequential queries. A pre-warm that does not work must not be able
 *   to break a run that would otherwise have succeeded, just slow it down.
 * - Refuses to cache answers the server flagged as at_cap, i.e. generations that
 *   ran out of token budget with their reasoning cut short. Those answers are
 *   unreliable and the cache never recomputes, so writing one would freeze a
 *   probably-wrong verdict for the rest of the run.
 */
public class BatchPrewarmer {

    /** Batch size. 0 or unset disables pre-warming entirely. */
    public static final String BATCH_ENV = "EXACTLEARNER_BATCH_SIZE";

    /**
     * Batches can take minutes: 16 reasoning queries at ~2 s each, plus a
     * possible second forced-answer pass. Well above the worst case observed.
     */
    private static final int READ_TIMEOUT_MS = 15 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 30 * 1000;

    private BatchPrewarmer() {
    }

    public static int batchSizeFromEnv() {
        String raw = System.getenv(BATCH_ENV);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            System.out.println("Ignoring " + BATCH_ENV + "=" + raw + " (not a number)");
            return 0;
        }
    }

    /** Derives the batch endpoint from the configured /api/generate URL. */
    private static String batchUrl() {
        String generate = System.getenv("EXACTLEARNER_OLLAMA_URL");
        if (generate == null || generate.isBlank()) {
            return null;
        }
        if (!generate.endsWith("/api/generate")) {
            return null;
        }
        return generate.substring(0, generate.length() - "/generate".length()) + "/batch";
    }

    /**
     * Pre-computes and caches every ordered pair of distinct classes.
     * Returns the number of answers written. Never throws.
     */
    public static int prewarmPrecomputation(LLMEngine engine, Cache cache, String system, int batchSize) {
        if (batchSize <= 0 || cache == null) {
            return 0;
        }
        String url = batchUrl();
        if (url == null) {
            System.out.println("Batch pre-warm skipped: EXACTLEARNER_OLLAMA_URL is unset or does "
                    + "not end in /api/generate, so the /api/batch endpoint cannot be derived.");
            return 0;
        }

        List<OWLClass> classes = engine.getClassesInSignature();
        List<String> pending = new ArrayList<>();
        int alreadyCached = 0;

        for (OWLClass a : classes) {
            for (OWLClass b : classes) {
                if (a.equals(b)) {
                    continue;
                }
                OWLSubClassOfAxiom axiom = engine.getSubClassAxiom(a, b);
                String query = engine.queryFor(axiom);
                if (cache.resultString(query) != null) {
                    alreadyCached++;
                } else {
                    pending.add(query);
                }
            }
        }

        int total = pending.size() + alreadyCached;
        System.out.println("Batch pre-warm: " + total + " precomputation queries, "
                + alreadyCached + " already cached, " + pending.size() + " to fetch, "
                + "batch size " + batchSize);
        if (pending.isEmpty()) {
            return 0;
        }

        int stored = 0;
        int atCapSkipped = 0;
        long startedAt = System.currentTimeMillis();

        for (int from = 0; from < pending.size(); from += batchSize) {
            int to = Math.min(from + batchSize, pending.size());
            List<String> chunk = pending.subList(from, to);
            BatchResponse response;
            try {
                response = postBatch(url, chunk, system);
            } catch (Exception e) {
                // Leave everything not yet stored to the sequential path.
                System.out.println("Batch pre-warm aborted after " + stored + " answers: " + e
                        + ". The learner will query the remainder one at a time.");
                return stored;
            }
            if (response == null || response.answers.size() != chunk.size()) {
                System.out.println("Batch pre-warm aborted after " + stored
                        + " answers: malformed response. The learner will query the "
                        + "remainder one at a time.");
                return stored;
            }

            for (int i = 0; i < chunk.size(); i++) {
                if (response.atCap.size() == chunk.size() && response.atCap.get(i)) {
                    atCapSkipped++;
                    continue;
                }
                cache.storeQuery(chunk.get(i), response.answers.get(i));
                stored++;
            }

            long elapsed = System.currentTimeMillis() - startedAt;
            double perQuery = elapsed / 1000.0 / Math.max(stored + atCapSkipped, 1);
            long remaining = (long) (perQuery * (pending.size() - to));
            System.out.println("  pre-warm " + to + "/" + pending.size()
                    + String.format(" | %.2fs/query | eta %d min", perQuery, remaining / 60));
        }

        if (atCapSkipped > 0) {
            System.out.println("WARNING: " + atCapSkipped + " pre-warm answers hit the token "
                    + "budget and were NOT cached -- their reasoning was cut short, so the "
                    + "answer is unreliable. Raise --max-new-tokens on the server. The learner "
                    + "will re-ask these individually.");
        }
        System.out.println("Batch pre-warm complete: " + stored + " answers cached in "
                + (System.currentTimeMillis() - startedAt) / 1000 + "s.");
        return stored;
    }

    /**
     * Fetches arbitrary queries in batches and stores the answers in the cache.
     *
     * Used by the A-induced loop, which draws a block of candidates and prefetches
     * their answers in one call so the GPU sees several prompts at a time instead
     * of one. The loop itself is unchanged and still reads every answer through
     * the engine, which finds them here.
     *
     * Returns the number of answers stored. Never throws: on any failure the cache
     * is left as it was and the caller falls back to querying one at a time.
     */
    public static int fetchAndCache(Cache cache, String system, List<String> queries, int batchSize) {
        if (cache == null || queries == null || queries.isEmpty() || batchSize <= 0) {
            return 0;
        }
        String url = batchUrl();
        if (url == null) {
            return 0;
        }

        int stored = 0;
        for (int from = 0; from < queries.size(); from += batchSize) {
            int to = Math.min(from + batchSize, queries.size());
            List<String> chunk = queries.subList(from, to);
            BatchResponse response;
            try {
                response = postBatch(url, chunk, system);
            } catch (Exception e) {
                System.out.println("Batch fetch failed after " + stored + " answers: " + e
                        + ". Remaining queries will be asked one at a time.");
                return stored;
            }
            if (response == null || response.answers.size() != chunk.size()) {
                System.out.println("Batch fetch aborted after " + stored
                        + " answers: malformed response. Remaining queries will be asked "
                        + "one at a time.");
                return stored;
            }
            for (int i = 0; i < chunk.size(); i++) {
                // Same rule as the pre-warm: never cache an answer whose reasoning
                // was cut short by the token budget, since the cache never recomputes.
                if (response.atCap.size() == chunk.size() && response.atCap.get(i)) {
                    continue;
                }
                cache.storeQuery(chunk.get(i), response.answers.get(i));
                stored++;
            }
        }
        return stored;
    }

    private record BatchResponse(List<String> answers, List<Boolean> atCap) {
    }

    private static BatchResponse postBatch(String url, List<String> prompts, String system)
            throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("{\"system\":\"").append(OllamaBridge.escapeJson(system)).append("\",\"prompts\":[");
        for (int i = 0; i < prompts.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append('"').append(OllamaBridge.escapeJson(prompts.get(i))).append('"');
        }
        body.append("]}");

        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);

        try (OutputStreamWriter writer =
                     new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(body.toString());
            writer.flush();
        }

        String json;
        try (var in = connection.getInputStream()) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> answers = parseStringArray(json, "\"answers\":[");
        List<Boolean> atCap = parseBoolArray(json, "\"at_cap\":[");
        if (answers == null) {
            return null;
        }
        return new BatchResponse(answers, atCap == null ? List.of() : atCap);
    }

    /**
     * Reads a flat array of JSON strings. The server writes compact JSON with no
     * spaces (OllamaBridge depends on that too), and answers are bare
     * True/False, so a full parser would be more machinery than the format
     * warrants. Returns null if the key is absent.
     */
    private static List<String> parseStringArray(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        int i = start + key.length();
        List<String> out = new ArrayList<>();
        StringBuilder current = null;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (current == null) {
                if (c == ']') {
                    return out;
                }
                if (c == '"') {
                    current = new StringBuilder();
                }
            } else if (c == '\\' && i + 1 < json.length()) {
                current.append(json.charAt(i + 1));
                i++;
            } else if (c == '"') {
                out.add(current.toString());
                current = null;
            } else {
                current.append(c);
            }
            i++;
        }
        return null;
    }

    private static List<Boolean> parseBoolArray(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf(']', start);
        if (end < 0) {
            return null;
        }
        String inner = json.substring(start + key.length(), end).trim();
        List<Boolean> out = new ArrayList<>();
        if (inner.isEmpty()) {
            return out;
        }
        for (String part : inner.split(",")) {
            out.add(Boolean.parseBoolean(part.trim()));
        }
        return out;
    }
}
