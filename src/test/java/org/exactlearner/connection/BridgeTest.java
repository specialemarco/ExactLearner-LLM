package org.exactlearner.connection;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class BridgeTest {

    private static final String testQuestion = "What\'s the temperature of the Sun?";

    @Test
    public void testLLMServerBridgeAsk() {
        LLMServerBridge bridge = new LLMServerBridge("mixtral");
        String response = bridge.ask(testQuestion, "");
        assertNotNull(response);
        System.out.println(response);
    }
}
