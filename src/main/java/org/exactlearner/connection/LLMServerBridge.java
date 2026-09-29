package org.exactlearner.connection;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;

/** Client for scripts/llm_server.py's /api/generate (an Ollama-shaped JSON contract). */
public class LLMServerBridge extends BasicBridge {

    public static final String URL_ENV = "EXACTLEARNER_LLM_URL";
    // Old name, still exported by job scripts submitted before the rename.
    // Drop once those jobs have run.
    private static final String LEGACY_URL_ENV = "EXACTLEARNER_OLLAMA_URL";

    private int maxTokens = 100;

    /** The /api/generate URL, or null when neither variable is set. */
    public static String serverUrl() {
        String url = System.getenv(URL_ENV);
        if (url == null || url.isBlank()) {
            url = System.getenv(LEGACY_URL_ENV);
        }
        return (url == null || url.isBlank()) ? null : url.trim();
    }

    public LLMServerBridge(String model) {
        this(model, 100);
    }

    public LLMServerBridge(String model, int maxTokens) {
        super();
        String url = serverUrl();
        if (url == null) {
            throw new IllegalStateException(URL_ENV + " is not set; point it at llm_server.py's"
                    + " /api/generate (run_experiment.sh does this).");
        }
        BasicBridge.model = model;
        this.maxTokens = maxTokens;
        BasicBridge.url = url;
    }

    public String ask(String message, String system) {
        try {
            HttpURLConnection connection = getConnection(url);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            String jsonInputString = "{\"model\": \"" + escapeJson(model) + "\",\n" +
                    "\"system\": \"" + escapeJson(system) + "\",\n" +
                    "\"options\": {\n\"num_predict\": " + maxTokens + "\n},\n" +
                    "\"stream\": false,\n" +
                    "\"prompt\": \"" + escapeJson(message) + "\"}";
            connection.setDoOutput(true);
            OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());
            writer.write(jsonInputString);
            writer.flush();
            writer.close();
            String jsonResponse = getChatGPTResponse(connection);
            return extractMessageFromJSON(jsonResponse);
        } catch (Exception e) {
            System.out.println(ChatGPTCodes.valueOf(extractErrorCode(e.getMessage())));
            return null;
        }
    }

    private String extractMessageFromJSON(String json) {
        String key = "\"response\":\"";
        int start = json.indexOf(key) + key.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    public static String escapeJson(String str) {
        StringBuilder sb = new StringBuilder();
        for (char c : str.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c > 0x7E) {
                        sb.append(String.format("\\u%04x", (int) c)); // Unicode escape for non-printable characters
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    @Override
    public String ask(String message, String key, String system) {
        return ask(message, system);
    }
}
