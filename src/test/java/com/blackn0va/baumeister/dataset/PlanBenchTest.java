package com.blackn0va.baumeister.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Messgerät gegen einen Fake-llama-server (feste Antworten, Anfragen gemerkt): sofort angenommen, nach der
 * Reparaturrunde angenommen, verworfen – und dass die Reparaturrunde dasselbe Gespräch fortsetzt wie BuildService
 * (vorige Antwort als assistant, Rückmeldung als user).
 */
class PlanBenchTest {

    private static final String TOWER = "{\"design\":\"t\",\"reply\":\"Ein Turm!\",\"mode\":\"new\",\"steps\":["
            + "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,0,4],\"radius\":4,\"height\":1},"
            + "{\"op\":\"cylinder\",\"block\":\"stone_bricks\",\"pos\":[4,1,4],\"radius\":4,\"height\":12,"
            + "\"hollow\":true},"
            + "{\"op\":\"door\",\"block\":\"spruce_door\",\"pos\":[4,1,0],\"facing\":\"front\"}]}";
    private static final String SEALED = "{\"design\":\"b\",\"reply\":\"Ein Becken!\",\"mode\":\"new\",\"steps\":["
            + "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]},"
            + "{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,1,0],\"to\":[4,2,4]},"
            + "{\"op\":\"fill\",\"block\":\"water\",\"from\":[1,1,1],\"to\":[3,2,3]},"
            + "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,3,0],\"to\":[4,3,4]}]}";

    private HttpServer server;
    private final Deque<String> replies = new ArrayDeque<>();
    private final List<JsonObject> requests = new ArrayList<>();

    private PlanBench start(String... answers) throws Exception {
        replies.addAll(List.of(answers));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            requests.add(JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant");
            msg.addProperty("content", replies.poll());
            JsonObject choice = new JsonObject();
            choice.add("message", msg);
            JsonObject body = new JsonObject();
            body.add("choices", new com.google.gson.JsonArray());
            body.getAsJsonArray("choices").add(choice);
            JsonObject timings = new JsonObject();
            timings.addProperty("predicted_n", 100);
            timings.addProperty("predicted_per_second", 10.0);
            body.add("timings", timings);
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return new PlanBench(new PlanJudge(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/chat/completions"));
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cleanFirstAnswerCountsAsImmediate() throws Exception {
        PlanBench.Outcome o = start(TOWER).run("ein Turm aus Stein");
        assertEquals("sofort", o.outcome());
        assertTrue(o.clean());
        assertEquals(1, requests.size());
        JsonObject req = requests.getFirst();
        assertEquals("bauplan", req.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("name")
                .getAsString(), "ohne diesen Namen baut der Host keine Grammatik");
        assertEquals(PlanBench.MAX_TOKENS, req.get("max_tokens").getAsInt());
        assertTrue(req.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString()
                .startsWith("Bauauftrag: ein Turm aus Stein"));
    }

    @Test
    void repairRoundContinuesTheConversation() throws Exception {
        PlanBench.Outcome o = start(SEALED, TOWER).run("ein Turm aus Stein");
        assertEquals("nachgebessert", o.outcome());
        assertFalse(o.clean());
        assertEquals(2, requests.size());
        var msgs = requests.get(1).getAsJsonArray("messages");
        assertEquals(4, msgs.size());
        assertEquals("assistant", msgs.get(2).getAsJsonObject().get("role").getAsString());
        assertTrue(msgs.get(3).getAsJsonObject().get("content").getAsString().startsWith("The plan has building "
                + "errors"), msgs.get(3).toString());
    }

    @Test
    void secondFailureIsRejected() throws Exception {
        PlanBench.Outcome o = start(SEALED, SEALED).run("ein becken");
        assertEquals("verworfen", o.outcome());
        assertFalse(o.reason().isEmpty());
        assertEquals(2, requests.size());
    }
}
