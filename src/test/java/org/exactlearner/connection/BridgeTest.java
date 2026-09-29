package org.exactlearner.connection;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLMServerBridge against a stand-in for scripts/llm_server.py. The request body
 * is pinned byte for byte because it is what the server parses, and the prompt
 * inside it is the cache key.
 */
public class BridgeTest {

    private HttpServer server;
    private String url;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> contentTypes = new ArrayList<>();

    @BeforeEach
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            if (body.contains("STATUS503")) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String answer = body.contains("FALSE") ? "False" : "True";
            byte[] out = ("{\"model\":\"x\",\"response\":\"" + answer + "\",\"done\":true}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/generate";
    }

    @AfterEach
    public void stopServer() {
        server.stop(0);
    }

    @Test
    public void sendsTheRequestBodyTheServerExpects() {
        new LLMServerBridge("olmo-2-13b", 128, url)
                .ask("Can A be considered a subcategory of 'B'?", "sys");
        assertEquals(List.of("{\"model\": \"olmo-2-13b\",\n"
                + "\"system\": \"sys\",\n"
                + "\"options\": {\n\"num_predict\": 128\n},\n"
                + "\"stream\": false,\n"
                + "\"prompt\": \"Can A be considered a subcategory of 'B'?\"}"), bodies);
        assertEquals(List.of("application/json"), contentTypes);
    }

    @Test
    public void escapesSpecialCharactersInThePrompt() {
        new LLMServerBridge("m", 2, url).ask("q \" b \\ n \n t \t é", "s");
        assertTrue(bodies.get(0).endsWith("\"prompt\": \"q \\\" b \\\\ n \\n t \\t \\u00e9\"}"),
                bodies.get(0));
    }

    @Test
    public void returnsTheAnswer() {
        LLMServerBridge bridge = new LLMServerBridge("m", 2, url);
        assertEquals("True", bridge.ask("is it", "s"));
        assertEquals("False", bridge.ask("FALSE please", "s"));
    }

    @Test
    public void returnsNullOnAnHttpError() {
        assertNull(new LLMServerBridge("m", 2, url).ask("STATUS503", "s"));
    }

    @Test
    public void returnsNullWhenTheServerIsDown() {
        server.stop(0);
        assertNull(new LLMServerBridge("m", 2, url).ask("anyone there", "s"));
    }

    // model and url used to be static, so creating a second bridge changed the first.
    @Test
    public void eachBridgeKeepsItsOwnModel() {
        LLMServerBridge one = new LLMServerBridge("model-one", 5, url);
        LLMServerBridge two = new LLMServerBridge("model-two", 7, url);
        one.ask("q", "s");
        two.ask("q", "s");
        assertTrue(bodies.get(0).startsWith("{\"model\": \"model-one\""), bodies.get(0));
        assertTrue(bodies.get(1).startsWith("{\"model\": \"model-two\""), bodies.get(1));
    }

    @Test
    public void refusesToStartWithoutAUrl() {
        assertThrows(IllegalStateException.class, () -> new LLMServerBridge("m", 2, null));
    }
}
