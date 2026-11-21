package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.SiteSurvey;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * ClientPlanner gegen einen gefälschten Baumeister-Host (com.sun.net.httpserver): Fehlerstatus, unbrauchbare Antwort,
 * Nachbesserungsrunde, Gedächtnis für Nachträge (AK-5) und die einstellbare Wartezeit (AK-6). Vorher hatte die Kette
 * im Client keinen einzigen Test – nur der Spieltest im echten Minecraft, der ohne Host gar nicht bis zum LLM kommt.
 */
class ClientPlannerTest {

    /** Das 7×7-Haus aus dem System-Prompt (wie PlanReviewTest.HOUSE) – sauber, kostet keine Nachbesserung. */
    private static final String[] HOUSE = {
        "{\"op\":\"fill\",\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[6,0,6]}",
        "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[6,3,6]}",
        "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[0,1,0],\"to\":[0,3,0]}",
        "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[6,1,0],\"to\":[6,3,0]}",
        "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[0,1,6],\"to\":[0,3,6]}",
        "{\"op\":\"fill\",\"block\":\"oak_log\",\"from\":[6,1,6],\"to\":[6,3,6]}",
        "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[3,1,0],\"facing\":\"front\"}",
        "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[1,2,0]}",
        "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[5,2,0]}",
        "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[0,2,3]}",
        "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[6,2,3]}",
        "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,6],\"shape\":\"gable\","
                + "\"overhang\":1,\"gable_block\":\"oak_planks\"}",
        "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,1,4]}"};

    private static String plan(String mode, String roof) {
        List<String> steps = new ArrayList<>(List.of(HOUSE));
        steps.set(11, steps.get(11).replace("oak_stairs", roof));
        return "{\"design\":\"t\",\"reply\":\"Fertig geplant\",\"mode\":\"" + mode + "\",\"steps\":["
                + String.join(",", steps) + "]}";
    }

    /** Antwort im llama-server-Format mit content als Text. */
    private static String completion(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", msg);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return root.toString();
    }

    /** Ebener Boden unter dem Bauplatz, sonst Luft – kein Nachbarbau, kein Überbau. */
    private static SiteSurvey flat() {
        return SiteSurvey.scan(new Pos(-4, -2, -2), new Pos(20, 10, 20), p -> p.y() < 0 ? "grass_block" : "air");
    }

    private record Reply(int status, String body) {
    }

    private HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());

    private ClientSettings host(Reply... script) throws Exception {
        replies.addAll(List.of(script));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            try (InputStream in = ex.getRequestBody(); OutputStream out = ex.getResponseBody()) {
                requests.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                Reply r;
                synchronized (replies) {
                    r = replies.isEmpty() ? new Reply(500, "Skript zu Ende") : replies.poll();
                }
                byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(r.status(), b.length);
                out.write(b);
            }
        });
        server.start();
        ClientSettings s = new ClientSettings();
        s.llmUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
        return s;
    }

    @AfterEach
    void stopHost() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ClientPlanner planner(ClientSettings s) throws Exception {
        return new ClientPlanner(s, id -> true);
    }

    private static ClientPlanner.Planned run(ClientPlanner p, String prompt, ClientPlanner.Previous previous,
            List<String> said) throws Exception {
        CompletableFuture<ClientPlanner.Planned> f = p.llm(prompt, flat(), previous, said::add, new PlanRequest());
        return f.get(30, TimeUnit.SECONDS);
    }

    private static PlanException failure(ClientPlanner p) {
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> p.llm("bau ein Haus", flat(), null, m -> { }, new PlanRequest()).get(30, TimeUnit.SECONDS));
        return assertInstanceOf(PlanException.class, e.getCause());
    }

    /** Nachricht an das LLM (Rolle user, die letzte) aus einer aufgezeichneten Anfrage. */
    private static String userMessage(String request) {
        JsonArray messages = JsonParser.parseString(request).getAsJsonObject().getAsJsonArray("messages");
        String user = "";
        for (var m : messages) {
            if ("user".equals(m.getAsJsonObject().get("role").getAsString())) {
                user = m.getAsJsonObject().get("content").getAsString();
            }
        }
        return user;
    }

    @Test
    void busyHostIsReportedAsModelNotLoaded() throws Exception {
        PlanException e = failure(planner(host(new Reply(503, "{}"))));
        assertTrue(e.getMessage().contains("HTTP 503"), e.getMessage());
        assertTrue(e.getMessage().contains("Modell nicht geladen"), e.getMessage());
    }

    @Test
    void otherStatusIsReportedWithoutTheModelHint() throws Exception {
        PlanException e = failure(planner(host(new Reply(500, "kaputt"))));
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
        assertFalse(e.getMessage().contains("Modell nicht geladen"), e.getMessage());
    }

    @Test
    void unusableReplyIsNamed() throws Exception {
        PlanException e = failure(planner(host(new Reply(200, "{\"choices\":[]}"))));
        assertTrue(e.getMessage().startsWith("Antwort des Hosts unbrauchbar"), e.getMessage());
    }

    @Test
    void brokenPlanGetsOneRepairRoundAndThenTheFixedPlanIsAccepted() throws Exception {
        ClientSettings s = host(new Reply(200, completion("das ist kein Plan")),
                new Reply(200, completion(plan("new", "oak_stairs"))));
        s.repairAttempts = 1;
        List<String> said = new ArrayList<>();
        ClientPlanner.Planned p = run(planner(s), "bau ein Haus", null, said);
        assertEquals(2, requests.size(), "genau eine Nachbesserungsrunde");
        assertEquals(2, p.rounds());
        assertFalse(said.isEmpty(), "Der Spieler erfährt von der Nachbesserung");
        assertFalse(p.update());
    }

    @Test
    void followUpSendsThePreviousPlanAndBecomesAnUpdate() throws Exception {
        // Gedächtnis: ein Nachtrag schickt den vorigen Plan mit (AK-5).
        String first = plan("new", "oak_stairs");
        ClientSettings s = host(new Reply(200, completion(plan("update", "red_nether_brick_stairs"))));
        ClientPlanner.Planned p = run(planner(s), "mach das Dach rot",
                new ClientPlanner.Previous(first, "bau ein Haus", 5), new ArrayList<>());
        String user = userMessage(requests.get(0));
        assertTrue(user.contains("PREVIOUS BUILD"), "Ohne Gedächtnis-Abschnitt kennt das LLM das Dach nicht");
        assertTrue(user.contains("5 min ago for \"bau ein Haus\""), user);
        assertTrue(user.contains("oak_stairs"), "Der alte Plan steht in der Nachricht");
        assertTrue(p.update(), "mode update mit Gedächtnis ist ein Umbau");
        assertTrue(p.json().contains("red_nether_brick_stairs"));
    }

    @Test
    void withoutMemoryUpdateIsANewBuildAndNoPreviousBuildIsSent() throws Exception {
        ClientSettings s = host(new Reply(200, completion(plan("update", "oak_stairs"))));
        ClientPlanner.Planned p = run(planner(s), "mach das Dach rot", null, new ArrayList<>());
        assertFalse(userMessage(requests.get(0)).contains("PREVIOUS BUILD"));
        assertFalse(p.update(), "update ohne vorigen Bau gilt als Neubau – wie im Plugin");
    }

    @Test
    void silentHostEndsAfterTheConfiguredWait() throws Exception {
        // AK-6: Wartezeit einstellbar; vorher fest 10 min – ein stummer Host hielt die Planung so lange fest.
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (Socket c = silent.accept(); InputStream in = c.getInputStream()) {
                    while (in.read() >= 0) {
                        // liest und antwortet nie
                    }
                } catch (Exception e) {
                    // Verbindung vom Client geschlossen: genau das soll nach der Frist passieren
                }
            }, "stummer-host");
            t.setDaemon(true);
            t.start();
            ClientSettings s = new ClientSettings();
            s.llmUrl = "http://127.0.0.1:" + silent.getLocalPort() + "/v1/chat/completions";
            s.llmTimeoutSeconds = 1;
            long start = System.nanoTime();
            ExecutionException e = assertThrows(ExecutionException.class, () -> planner(s)
                    .llm("bau ein Haus", flat(), null, m -> { }, new PlanRequest()).get(20, TimeUnit.SECONDS));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertInstanceOf(PlanException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains("binnen 1 s nicht geantwortet"), e.getCause().getMessage());
            assertTrue(ms < 10_000, "Frist 1 s, gedauert " + ms + " ms");
        }
    }
}
