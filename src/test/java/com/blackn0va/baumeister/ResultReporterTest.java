package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

/** Ergebnis-Meldung an den Host (Gegenstück host/src/server/result_api.cpp, Tests dort in test_result_api.cpp). */
class ResultReporterTest {

    @Test
    void sendsTheFieldsTheHostExpects() {
        List<String> sent = new ArrayList<>();
        List<URI> to = new ArrayList<>();
        ResultReporter r = new ResultReporter(URI.create("http://127.0.0.1:18080/baumeister/v1/ergebnis"),
                (u, json) -> {
                    to.add(u);
                    sent.add(json);
                    return CompletableFuture.completedFuture(200);
                }, System::nanoTime);
        r.report("bau ein hochhaus mit 2 stockwerken", "gebaut", "", 8652, "1;1");
        assertEquals(1, sent.size());
        JsonObject o = JsonParser.parseString(sent.getFirst()).getAsJsonObject();
        assertEquals("gebaut", o.get("ergebnis").getAsString());
        assertEquals(8652, o.get("bloecke").getAsLong());  // umlaut-ok: Schlüssel wie im Host
        assertEquals("1;1", o.get("plot").getAsString());
        assertEquals("bau ein hochhaus mit 2 stockwerken", o.get("auftrag").getAsString());
        assertEquals("/baumeister/v1/ergebnis", to.getFirst().getPath());
        // Ohne Angabe: keine Nachbesserung, 0 Runden (Schnellauftrag).
        assertEquals("", o.get("korrekturen").getAsString());
        assertEquals(0, o.get("runden").getAsInt());

        r.report("bau ein haus", "gebaut", "", 412, "", List.of("Schritt 2: house-Grundriss 32×1 auf 24×5 angepasst",
                "Schritt 4: cylinder innen hohl gemacht"), 2);
        JsonObject fixed = JsonParser.parseString(sent.getLast()).getAsJsonObject();
        assertEquals("Schritt 2: house-Grundriss 32×1 auf 24×5 angepasst; Schritt 4: cylinder innen hohl gemacht",
                fixed.get("korrekturen").getAsString());
        assertEquals(2, fixed.get("runden").getAsInt());
    }

    @Test
    void aMissingHostNeverReachesTheBuild() {
        ResultReporter r = new ResultReporter(URI.create("http://127.0.0.1:1/x"), (u, json) -> {
            throw new IllegalStateException("weg");
        }, System::nanoTime);
        r.report("x", "verworfen", "schwebende Teile", 0, null);
        ResultReporter failing = new ResultReporter(URI.create("http://127.0.0.1:1/x"),
                (u, json) -> CompletableFuture.failedFuture(new java.net.ConnectException()), System::nanoTime);
        failing.report("x", "abgesagt", "zu tief", 0, "");
        assertTrue(true, "keine Ausnahme bis hierher");
    }

    @Test
    void addressFollowsTheLlmUrlLikeTheCityView() {
        URI base = HostReporter.target("", URI.create("http://127.0.0.1:18080/v1/chat/completions"));
        assertEquals("http://127.0.0.1:18080/baumeister/v1/ergebnis", base.resolve(ResultReporter.PATH).toString());
    }
}
