package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AK-12 (Vertrag v004): abgeschnittene Antwort und Host-Ausfall, gegen einen nachgebauten Host. Anlass sind die
 * dev01-Logs bis 2026-11-21 – 3× „JSON fehlerhaft: EOFException … path $.steps[N]“, 41× „llm | wiederholung …
 * grund=ConnectException“, 30× „llm | fehler“.
 */
class LlmClientRecoveryTest {

    /** Ein Plan, der bei max_tokens mitten in steps abbricht – wie auf dev01. */
    private static final String CUT = "{\"reply\":\"Ich baue\",\"steps\":[{\"op\":\"box\",\"from\":[0,0,0],"
            + "\"to\":[4,0,4],\"block\":\"stone\"},{\"op\":\"box\",\"from\":[0,1";
    private static final String WHOLE = "{\"reply\":\"Ich baue\",\"steps\":[]}";

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> logLines = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord r) {
            logLines.add(r.getMessage());
        }

        @Override
        public void flush() {
            // nichts gepuffert
        }

        @Override
        public void close() {
            // nichts offen
        }
    };

    @BeforeEach
    void listen() {
        Logger.getLogger("Baumeister").addHandler(handler);
    }

    @AfterEach
    void stop() {
        Logger.getLogger("Baumeister").removeHandler(handler);
        if (server != null) {
            server.stop(0);
        }
    }

    private static Settings settings(URI url, int maxTokens) {
        return new Settings("Baumeister", url, Duration.ofSeconds(10), maxTokens, 0.4, 1, 2, 1000, 64, 3, "stone",
                false, 5, false, 12, 0, 0, 60, com.blackn0va.baumeister.plan.PlanReview.LintMode.STRICT);
    }

    /** Körper einer Antwort: Inhalt, finish_reason (null = fehlt), erzeugte Token (≤ 0 = keine timings). */
    private static String reply(String content, String finish, int tokens) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", msg);
        if (finish != null) {
            choice.addProperty("finish_reason", finish);
        }
        JsonObject root = new JsonObject();
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(choice);
        root.add("choices", choices);
        if (tokens > 0) {
            JsonObject timings = new JsonObject();
            timings.addProperty("predicted_n", tokens);
            timings.addProperty("predicted_per_second", 10.0);
            root.add("timings", timings);
        }
        return root.toString();
    }

    /** Host, der der Reihe nach die Körper liefert (der letzte wiederholt sich) und jede Anfrage mitschreibt. */
    private URI host(String... replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(ex, replies[Math.min(bodies.size() - 1, replies.length - 1)]);
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
    }

    private static void send(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private int maxTokensOf(int call) {
        return JsonParser.parseString(bodies.get(call)).getAsJsonObject().get("max_tokens").getAsInt();
    }

    private static List<JsonObject> messages() {
        return List.of(LlmClient.message("user", "bau ein haus"));
    }

    private long lines(String part) {
        return logLines.stream().filter(l -> l != null && l.contains(part)).count();
    }

    /** AK-12, Vertragstest „KI: eine abgeschnittene Antwort wird einmal mit mehr Platz nachgefragt“. */
    @Test
    void asksOnceMoreWithMoreRoomWhenTheAnswerWasCut() throws Exception {
        LlmClient c = new LlmClient(settings(host(reply(CUT, "length", 512), reply(WHOLE, "stop", 300)), 512),
                new JsonObject(), new long[0]);
        LlmClient.Reply r = c.complete(messages()).get(10, TimeUnit.SECONDS);
        assertEquals(WHOLE, r.content(), "die vollständige zweite Antwort gilt");
        assertEquals(2, bodies.size());
        assertEquals(512, maxTokensOf(0));
        assertEquals(1024, maxTokensOf(1), "doppelte Grenze");
        assertEquals(1, lines("llm | nachfrage |"));
    }

    @Test
    void asksOnlyOnceEvenIfTheSecondAnswerIsCutToo() throws Exception {
        LlmClient c = new LlmClient(settings(host(reply(CUT, "length", 512), reply(CUT + ",2", "length", 1024)),
                512), new JsonObject(), new long[0]);
        LlmClient.Reply r = c.complete(messages()).get(10, TimeUnit.SECONDS);
        assertEquals(CUT + ",2", r.content(), "zweite Antwort geht wie bisher an PlanReview/JsonRepair");
        assertEquals(2, bodies.size(), "keine Kette");
    }

    @Test
    void countsOpenJsonAsCutWithoutFinishReason() throws Exception {
        LlmClient c = new LlmClient(settings(host(reply(CUT, null, 0), reply(WHOLE, null, 0)), 3000),
                new JsonObject(), new long[0]);
        assertEquals(WHOLE, c.complete(messages()).get(10, TimeUnit.SECONDS).content());
        assertEquals(6000, maxTokensOf(1));
    }

    @Test
    void staysBelowTheTokenCap() throws Exception {
        LlmClient c = new LlmClient(settings(host(reply(CUT, "length", 5000), reply(WHOLE, "stop", 10)), 5000),
                new JsonObject(), new long[0]);
        c.complete(messages()).get(10, TimeUnit.SECONDS);
        assertEquals(LlmClient.MAX_TOKENS_CAP, maxTokensOf(1), "min(2 × 5000, 8192)");
        bodies.clear();
        server.stop(0);
        LlmClient atCap = new LlmClient(settings(host(reply(CUT, "length", 8192)), 8192), new JsonObject(),
                new long[0]);
        assertEquals(CUT, atCap.complete(messages()).get(10, TimeUnit.SECONDS).content());
        assertEquals(1, bodies.size(), "schon an der Obergrenze: wie bisher, keine Nachfrage");
    }

    @Test
    void completeAnswersAndTheCompanionAreNotAskedAgain() throws Exception {
        LlmClient c = new LlmClient(settings(host(reply(WHOLE, "stop", 40)), 512), new JsonObject(), new long[0]);
        assertEquals(WHOLE, c.complete(messages()).get(10, TimeUnit.SECONDS).content());
        assertEquals(1, bodies.size(), "vollständig: wie bisher");
        bodies.clear();
        server.stop(0);
        LlmClient companion = new LlmClient(settings(host(reply(CUT, "length", 100)), 512), new JsonObject(),
                "begleiter", 100, new long[0]);
        companion.complete(messages()).get(10, TimeUnit.SECONDS);
        assertEquals(1, bodies.size(), "Begleiter hat eine Antwortfrist – keine Nachfrage");
    }

    @Test
    void doesNotAskAgainWhenTheContextCappedTheAnswer() {
        assertFalse(LlmClient.askAgain(true, 200, 3000, 6000), "Host hat auf den Kontext gekappt");
        assertTrue(LlmClient.askAgain(true, 3000, 3000, 6000));
        assertTrue(LlmClient.askAgain(true, 0, 3000, 6000), "Server ohne Token-Zahl");
        assertFalse(LlmClient.askAgain(false, 3000, 3000, 6000));
        assertFalse(LlmClient.askAgain(true, 3000, 3000, 0), "Begleiter");
    }

    @Test
    void detectsCutAnswers() {
        assertTrue(LlmClient.truncated(reply(WHOLE, "length", 1), WHOLE), "finish_reason zählt");
        assertTrue(LlmClient.truncated(reply(CUT, null, 0), CUT), "offenes JSON zählt");
        assertFalse(LlmClient.truncated(reply(WHOLE, "stop", 1), WHOLE));
        assertFalse(LlmClient.truncated("{\"choices\":[{\"finish_reason\":7}]}", WHOLE), "wirft nicht");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /**
     * Vorher (gemessen 2026-11-21, echte Wartezeiten 3 s + 10 s): jeder Auftrag 3 Versuche, 13,0–13,1 s, auch der
     * zweite. Jetzt läuft die Kette nur beim ersten; der zweite sagt nach einem Versuch ab und nennt den Schnellbau.
     */
    @Test
    void knownOutageFailsWithoutRetryChainAndPointsToQuickBuilds() throws Exception {
        URI url = URI.create("http://127.0.0.1:" + closedPort() + "/v1/chat/completions");
        LlmClient c = new LlmClient(settings(url, 512), new JsonObject(), new long[] {300, 300});

        long t0 = System.nanoTime();
        ExecutionException first = assertThrows(ExecutionException.class,
                () -> c.complete(messages()).get(10, TimeUnit.SECONDS));
        long firstMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(2, lines("llm | wiederholung |"), "erster Ausfall: volle Kette (ein Wackler wäre das auch)");
        assertTrue(firstMs >= 600, "zwei Wartezeiten: " + firstMs);
        assertInstanceOf(java.net.ConnectException.class, rootOf(first));
        assertTrue(LlmClient.describe(first).contains("Schnellbau"), LlmClient.describe(first));
        assertEquals(1, lines("llm | host_weg |"), "Beginn des Ausfalls einmal im Log");

        long t1 = System.nanoTime();
        ExecutionException second = assertThrows(ExecutionException.class,
                () -> c.complete(messages()).get(10, TimeUnit.SECONDS));
        long secondMs = (System.nanoTime() - t1) / 1_000_000;
        assertEquals(2, lines("llm | wiederholung |"), "zweiter Auftrag: keine Wiederholung");
        assertTrue(secondMs < 300, "ohne Wartezeit: " + secondMs);
        assertInstanceOf(LlmClient.HostDownException.class, rootOf(second));
        String text = LlmClient.describe(second);
        assertTrue(text.contains("Schnellbau") && text.contains("Pyramide") && text.contains("Burg")
                && text.contains("Hochhaus"), text);
        assertFalse(text.contains("drei Versuchen"), text);
        assertEquals(1, lines("llm | host_weg |"), "keine Zeile je Auftrag – Erinnerung erst nach 15 min");
    }

    /** Antwortet der Host wieder, gilt er als da: eine Rückkehr-Zeile, und ein neuer Ausfall bekommt die volle Kette. */
    @Test
    void retriesFullyAgainAfterTheHostCameBack() throws Exception {
        int port = closedPort();
        URI url = URI.create("http://127.0.0.1:" + port + "/v1/chat/completions");
        LlmClient c = new LlmClient(settings(url, 512), new JsonObject(), new long[] {10, 10});
        assertThrows(ExecutionException.class, () -> c.complete(messages()).get(10, TimeUnit.SECONDS));
        assertEquals(1, lines("llm | host_weg |"));

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/v1/chat/completions", ex -> {
            ex.getRequestBody().readAllBytes();
            send(ex, reply(WHOLE, "stop", 5));
        });
        server.start();
        assertEquals(WHOLE, c.complete(messages()).get(10, TimeUnit.SECONDS).content(), "ein Versuch reicht");
        assertEquals(1, lines("llm | host_zurück |"));

        server.stop(0);
        server = null;
        logLines.clear();
        assertThrows(ExecutionException.class, () -> c.complete(messages()).get(10, TimeUnit.SECONDS));
        assertEquals(2, lines("llm | wiederholung |"), "Host galt als da: volle Kette");
    }

    private static Throwable rootOf(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c instanceof ExecutionException
                || c instanceof java.util.concurrent.CompletionException)) {
            c = c.getCause();
        }
        return c;
    }
}
