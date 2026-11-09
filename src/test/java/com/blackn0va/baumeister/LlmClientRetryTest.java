package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wiederholversuche des LlmClient gegen einen nachgebauten Host (com.sun.net.httpserver): Die Fälle sind die aus den
 * dev01-Logs bis 2026-10-31 – 503 (Modell lädt), Verbindung ohne Antwort abgerissen, 400 (nie wiederholen).
 */
class LlmClientRetryTest {

    private static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{}\"}}]}";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Antwortet der Reihe nach mit den Codes; 0 = Verbindung ohne Antwort schließen, danach immer 200. */
    private LlmClient client(AtomicInteger calls, int... codes) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            int n = calls.getAndIncrement();
            int code = n < codes.length ? codes[n] : 200;
            if (code == 0) {
                ex.close();
                return;
            }
            send(ex, code, code == 200 ? OK : "{}");
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        Settings s = new Settings("Baumeister", url, Duration.ofSeconds(10), 512, 0.4, 1, 2, 1000, 64, 3, "stone",
                false, 5, false, 12, 0, 0, 60, com.blackn0va.baumeister.plan.PlanReview.LintMode.STRICT);
        return new LlmClient(s, new JsonObject(), new long[] {10, 10});
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private static List<JsonObject> messages() {
        return List.of(LlmClient.message("user", "bau ein haus"));
    }

    @Test
    void retriesWhileTheHostIsStillLoadingTheModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient.Reply r = client(calls, 503, 503).complete(messages()).get(10, TimeUnit.SECONDS);
        assertEquals("{}", r.content());
        assertEquals(3, calls.get());
    }

    @Test
    void retriesAfterAConnectionThatClosedWithoutAnswer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient.Reply r = client(calls, 0).complete(messages()).get(10, TimeUnit.SECONDS);
        assertEquals("{}", r.content());
        assertEquals(2, calls.get());
    }

    @Test
    void givesUpAfterThreeAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient c = client(calls, 503, 503, 503, 503);
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> c.complete(messages()).get(10, TimeUnit.SECONDS));
        assertTrue(LlmClient.describe(e).contains("llm_http_503"), LlmClient.describe(e));
        assertEquals(3, calls.get());
    }

    /**
     * Relay ohne verbundene App (2026-11-08): Jede Verbindung wird angenommen und ohne Antwort geschlossen. Nach drei
     * Versuchen bekommt der Spieler einen Satz, keinen Java-Klassennamen (im Spiel stand „(IOException)“).
     */
    @Test
    void hostNotConnectedReadsAsASentenceWithoutClassNames() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient c = client(calls, 0, 0, 0);
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> c.complete(messages()).get(10, TimeUnit.SECONDS));
        String text = LlmClient.describe(e);
        assertEquals(3, calls.get());
        assertTrue(text.contains("nicht verbunden"), text);
        assertFalse(text.contains("Exception"), text);
    }

    /**
     * Ollama (2026-11-08): kein /health (404), aber /v1/models (200) – zählt als erreichbar. Und der Modellname aus
     * llm.modell steht in der Anfrage; mit „lokal“ antwortet Ollama 404 „model not found“.
     */
    @Test
    void ollamaCountsAsReachableAndGetsTheModelName() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/health", ex -> send(ex, 404, "404 page not found"));
        server.createContext("/v1/models", ex -> send(ex, 200, "{\"object\":\"list\",\"data\":[]}"));
        server.createContext("/v1/chat/completions", ex -> {
            sent.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(ex, 200, OK);
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        Settings s = new Settings("Baumeister", url, Duration.ofSeconds(10), 512, 0.4, 1, 2, 1000, 64, 3, "stone",
                false, 5, false, 12, 0, 0, 60, com.blackn0va.baumeister.plan.PlanReview.LintMode.STRICT);
        LlmClient c = new LlmClient(s, new JsonObject(), new long[0]).withModel("qwen2.5:7b");
        assertTrue(c.health().get(10, TimeUnit.SECONDS), "/v1/models reicht");
        c.complete(messages()).get(10, TimeUnit.SECONDS);
        assertTrue(sent.get().contains("\"model\":\"qwen2.5:7b\""), sent.get());
        server.removeContext("/v1/models");
        assertFalse(c.health().get(10, TimeUnit.SECONDS), "weder /health noch /v1/models");
        assertEquals("lokal", new LlmClient(s, new JsonObject()).withModel(" ").model());
    }

    @Test
    void doesNotRepeatABadRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient c = client(calls, 400);
        assertThrows(ExecutionException.class, () -> c.complete(messages()).get(10, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    @Test
    void timeoutsAreNotRepeated() {
        assertFalse(LlmClient.retryable(new CompletionException(new java.net.http.HttpTimeoutException("x"))));
        assertTrue(LlmClient.retryable(new CompletionException(new java.net.ConnectException("x"))));
        assertFalse(LlmClient.retryable(new LlmClient.LlmException("llm_http_400")));
    }
}
