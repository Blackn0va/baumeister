package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Meldungen an die Stadtansicht im Host: Form nach Schnittstelle v1, Drossel, Warteschlange, Ausfall des Hosts. */
class HostReporterTest {

    /** Nimmt Nachrichten an, schließt sie aber erst auf Zuruf ab – so ist „eine Anfrage zugleich“ prüfbar. */
    private static final class HeldTransport implements HostReporter.Transport {
        final List<String> sent = new ArrayList<>();
        final List<CompletableFuture<Integer>> open = new ArrayList<>();

        @Override
        public CompletableFuture<Integer> post(URI uri, String json) {
            sent.add(json);
            CompletableFuture<Integer> f = new CompletableFuture<>();
            open.add(f);
            return f;
        }

        void answer(int status) {
            open.removeFirst().complete(status);
        }
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void addressComesFromTheLlmUrlUnlessConfigured() {
        URI llm = URI.create("http://127.0.0.1:18080/v1/chat/completions");
        assertEquals(URI.create("http://127.0.0.1:18080/baumeister/v1/stadt"), HostReporter.target("", llm));
        assertEquals(URI.create("http://127.0.0.1:9000/x"), HostReporter.target("http://127.0.0.1:9000/x", llm));
        assertNull(HostReporter.target("ftp://host/x", llm));
        assertNull(HostReporter.target("", null));
        YamlConfiguration off = new YamlConfiguration();
        off.set("gui.stadt-anzeige", false);
        assertNull(HostReporter.from(off, llm));
    }

    @Test
    void messagesMatchVersionOneOfTheInterface() {
        HeldTransport t = new HeldTransport();
        HostReporter r = new HostReporter(URI.create("http://h/x"), t, () -> 0L);
        r.start("job1", "stadt", "Bad Kreuznach", "stadtwelt", 5, -3000, -3000);
        t.answer(204);
        r.stage("job1", 0, new CityRaster.Raster(-3100, -3100, 2, 3, 2, new byte[] {1, 2, 3, 4, 5, 6}));
        t.answer(204);
        r.end("job1", "fertig", 151141);
        t.answer(204);
        JsonObject start = json(t.sent.get(0));
        assertEquals(1, start.get("v").getAsInt());
        assertEquals("start", start.get("event").getAsString());
        assertEquals("stadt", start.get("kind").getAsString());
        assertEquals(5, start.get("stages").getAsInt());
        assertEquals(-3000, start.getAsJsonArray("origin").get(0).getAsInt());
        JsonObject stage = json(t.sent.get(1));
        assertEquals(3, stage.get("w").getAsInt());
        assertEquals(2, stage.get("h").getAsInt());
        assertEquals(2, stage.get("cell").getAsInt());
        byte[] cells = Base64.getDecoder().decode(stage.get("cells").getAsString());
        assertEquals(6, cells.length, "genau w*h Bytes");
        assertEquals("end", json(t.sent.get(2)).get("event").getAsString());
        assertEquals(151141, json(t.sent.get(2)).get("blocks").getAsLong());
    }

    @Test
    void progressAtMostOncePerSecondAndOnlyTheNewestWaits() {
        HeldTransport t = new HeldTransport();
        AtomicLong now = new AtomicLong();
        HostReporter r = new HostReporter(URI.create("http://h/x"), t, now::get);
        r.progress("j", 1, 0, 10, 100, 5.0, false, null);
        r.progress("j", 1, 0, 20, 100, 5.0, false, null);  // gleiche Sekunde → nichts
        assertEquals(1, t.sent.size());
        now.addAndGet(1_100_000_000L);
        r.progress("j", 2, 1, 30, 200, 5.0, false, new int[] {7, 8});
        now.addAndGet(1_100_000_000L);
        r.progress("j", 3, 2, 40, 300, 5.0, true, null);
        assertEquals(1, t.sent.size(), "eine Anfrage zugleich – die erste ist noch offen");
        t.answer(204);
        assertEquals(2, t.sent.size());
        assertEquals(40, json(t.sent.get(1)).get("blocks").getAsLong(), "nur der jüngste Stand wartete");
        assertTrue(json(t.sent.get(1)).get("paused").getAsBoolean());
    }

    @Test
    void queueIsBoundedAndStagesComeBeforeProgress() {
        HeldTransport t = new HeldTransport();
        HostReporter r = new HostReporter(URI.create("http://h/x"), t, () -> 0L);
        r.start("j", "stadt", "x", "w", 200, 0, 0);
        CityRaster.Raster one = new CityRaster.Raster(0, 0, 1, 1, 1, new byte[] {1});
        for (int i = 0; i < HostReporter.MAX_QUEUE + 10; i++) {
            r.stage("j", i, one);
        }
        r.progress("j", 1, 0, 1, 1, 1, false, null);
        assertEquals(10, r.dropped());
        while (!t.open.isEmpty()) {
            t.answer(204);
        }
        assertEquals("progress", json(t.sent.getLast()).get("event").getAsString());
        assertEquals(1 + HostReporter.MAX_QUEUE + 1, t.sent.size());
    }

    @Test
    void unreachableHostNeverThrowsAndKeepsGoing() throws Exception {
        // Port 1 auf Loopback: Verbindung wird sofort abgelehnt – wie ein geschlossener Tunnel.
        YamlConfiguration c = new YamlConfiguration();
        HostReporter r = HostReporter.from(c, URI.create("http://127.0.0.1:1/v1/chat/completions"));
        long t0 = System.nanoTime();
        r.start("j", "stadt", "x", "w", 1, 0, 0);
        r.stage("j", 0, new CityRaster.Raster(0, 0, 1, 1, 1, new byte[] {1}));
        r.end("j", "fertig", 1);
        assertTrue(System.nanoTime() - t0 < 500_000_000L, "Aufrufe dürfen nicht auf das Netz warten");
    }

    @Test
    void realHttpRoundTripReachesTheServer() throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch got = new CountDownLatch(2);
        server.createContext(HostReporter.PATH, ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
            got.countDown();
        });
        server.start();
        try {
            URI llm = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            HostReporter r = HostReporter.from(new YamlConfiguration(), llm);
            r.start("j", "weltbau", "Hepberg", "world_baumeister_erde", 16, 1, 2);
            r.end("j", "abgebrochen", 0);
            assertTrue(got.await(5, TimeUnit.SECONDS));
            assertEquals("start", json(bodies.get(0)).get("event").getAsString());
            assertEquals("abgebrochen", json(bodies.get(1)).get("reason").getAsString());
        } finally {
            server.stop(0);
        }
    }

    /**
     * App samt Tunnel mitten in einer Kachel neu gestartet (dev01 2026-11-01 21:28:13 ConnectException): Der Host
     * verwirft Etappen und Fortschritt ohne vorheriges start, die unveränderte Übersicht kam nicht mehr.
     */
    @Test
    void afterAnOutageStartStagesAndOverviewAreSentAgain() {
        HeldTransport t = new HeldTransport();
        AtomicLong now = new AtomicLong();
        HostReporter r = new HostReporter(URI.create("http://h/x"), t, now::get);
        r.world("world_baumeister_erde", 1024, List.of(), List.of(), List.of(), new int[] {1, 2}, "bauen", true,
                List.of());
        t.answer(200);
        r.start("job", "weltbau", "Erdkachel 1/2", "world_baumeister_erde", 16, 0, 0);
        t.answer(204);
        CityRaster.Raster one = new CityRaster.Raster(0, 0, 1, 1, 1, new byte[] {1});
        r.stage("job", 0, one);
        t.answer(204);
        r.stage("job", 1, one);
        t.answer(503);  // Tunnel weg: Etappe 1 geht verloren, der Host wird gleich neu gestartet
        now.addAndGet(1_100_000_000L);
        r.progress("job", 2, 2, 10, 100, 1, false, null);
        int before = t.sent.size();
        t.answer(204);  // Host wieder da – kennt den Auftrag aber nicht mehr
        List<String> events = new ArrayList<>();
        while (!t.open.isEmpty()) {
            t.answer(204);
        }
        for (String s : t.sent.subList(before, t.sent.size())) {
            JsonObject o = json(s);
            events.add(o.get("event").getAsString() + (o.has("index") ? o.get("index").getAsInt() : ""));
        }
        assertEquals(List.of("start", "stage0", "stage1", "welt"), events);
    }

    @Test
    void finishedJobIsNotSentAgainAfterAnOutage() {
        HeldTransport t = new HeldTransport();
        HostReporter r = new HostReporter(URI.create("http://h/x"), t, () -> 0L);
        r.start("job", "weltbau", "x", "w", 1, 0, 0);
        t.answer(204);
        r.end("job", "fertig", 1);
        t.answer(503);
        r.start("job2", "weltbau", "y", "w", 1, 0, 0);
        int before = t.sent.size();
        t.answer(204);
        assertEquals(before, t.sent.size(), "nur job2 – kein Wiederholen des beendeten Auftrags");
        assertEquals("job2", json(t.sent.getLast()).get("job").getAsString());
    }

    @Test
    void unchangedOverviewIsRepeatedAfterAMinute() {
        HeldTransport t = new HeldTransport();
        AtomicLong now = new AtomicLong();
        HostReporter r = new HostReporter(URI.create("http://h/x"), t, now::get);
        for (int i = 0; i < 3; i++) {
            r.world("w", 1024, List.of(), List.of(), List.of(), null, "wartet", false, List.of());
            while (!t.open.isEmpty()) {
                t.answer(200);
            }
            now.addAndGet(30_000_000_000L);
        }
        // 0 s gesendet, 30 s gleich → nichts, 60 s gleich → erneut (Host könnte neu gestartet sein).
        assertEquals(2, t.sent.size());
    }

    @Test
    void worldOverviewMatchesTheInterfaceAndIsOnlySentWhenItChanges() {
        HeldTransport t = new HeldTransport();
        HostReporter r = new HostReporter(URI.create("http://h/x"), t, () -> 0L);
        List<int[]> done = new ArrayList<>();
        for (int i = 0; i < HostReporter.MAX_DONE + 5; i++) {
            done.add(new int[] {i, -i});
        }
        r.world("world_baumeister_erde", 1024, done, List.of(new int[] {9, 9}), List.of(new int[] {366, 6584}),
                new int[] {366, 6584}, "bauen", true, List.of(new int[] {375000, 6742000}));
        assertEquals(1, t.sent.size());
        JsonObject o = json(t.sent.getFirst());
        assertEquals("welt", o.get("event").getAsString());
        assertEquals(1024, o.get("tile").getAsInt());
        assertEquals(HostReporter.MAX_DONE, o.getAsJsonArray("done").size(), "gekappt wie im Host");
        assertEquals(HostReporter.MAX_DONE + 4, o.getAsJsonArray("done").get(HostReporter.MAX_DONE - 1)
                .getAsJsonArray().get(0).getAsInt(), "die jüngsten fertigen Kacheln bleiben");
        assertEquals(366, o.getAsJsonArray("current").get(0).getAsInt());
        assertEquals("bauen", o.get("phase").getAsString());
        assertTrue(o.get("auto").getAsBoolean());
        assertEquals(6742000, o.getAsJsonArray("players").get(0).getAsJsonArray().get(1).getAsInt());
        t.answer(200);
        // Gleicher Stand noch einmal: nichts senden. Spieler bewegt: senden, ohne current.
        r.world("world_baumeister_erde", 1024, done, List.of(new int[] {9, 9}), List.of(new int[] {366, 6584}),
                new int[] {366, 6584}, "bauen", true, List.of(new int[] {375000, 6742000}));
        assertEquals(1, t.sent.size());
        r.world("world_baumeister_erde", 1024, done, List.of(), List.of(), null, "wartet", false,
                List.of(new int[] {375010, 6742000}));
        assertEquals(2, t.sent.size());
        assertTrue(json(t.sent.get(1)).get("current") == null);
    }
}
