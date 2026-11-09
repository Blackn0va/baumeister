package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Anfrage und Antwort im OpenAI-Format (2026-11-08, Ollama als Unterbau): Modellname in der Anfrage, Token-Zahl aus
 * timings (llama-server, Baumeister-App) oder usage (Ollama).
 */
class LlmProtocolTest {

    @Test
    void modelNameGoesIntoTheRequestAndDefaultsToLokal() {
        List<JsonObject> msgs = List.of(LlmProtocol.message("user", "x"));
        assertEquals("qwen2.5:7b", LlmProtocol.body("qwen2.5:7b", msgs, new JsonObject(), "bauplan", 0.4, 100)
                .get("model").getAsString());
        assertEquals("lokal", LlmProtocol.body(" ", msgs, new JsonObject(), "bauplan", 0.4, 100).get("model")
                .getAsString());
        assertEquals("lokal", LlmProtocol.body(msgs, new JsonObject(), "bauplan", 0.4, 100).get("model")
                .getAsString());
    }

    @Test
    void tokensComeFromTimingsOrFromUsage() {
        String llama = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
                + "\"timings\":{\"predicted_n\":120,\"predicted_per_second\":30.5}}";
        LlmProtocol.Reply a = LlmProtocol.parse(llama, 4000);
        assertEquals(120, a.tokens());
        assertEquals(30.5, a.tokensPerSecond(), 1e-9);
        // Wortlaut wie von Ollama 0.40.15 (gemessen): usage statt timings.
        String ollama = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],"
                + "\"usage\":{\"prompt_tokens\":2050,\"completion_tokens\":242,\"total_tokens\":2292}}";
        LlmProtocol.Reply b = LlmProtocol.parse(ollama, 24_200);
        assertEquals(242, b.tokens());
        assertEquals(10.0, b.tokensPerSecond(), 1e-9);
    }
}
