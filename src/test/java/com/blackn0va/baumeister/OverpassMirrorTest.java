package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.OsmClient;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * dev01 2026-10-28: overpass-api.de antwortete 1,5 min nur mit 502, „/bm stadt hepberg“ scheiterte. Ein ausgelasteter
 * Dienst muss sofort zur nächsten Instanz führen, und ein Zwischenspeicher aus der Zeit vor den Etappen muss reichen.
 */
class OverpassMirrorTest {

    private static HttpServer server(int status, byte[] body, AtomicInteger calls) throws Exception {
        return server(status, body, null, calls, null);
    }

    /**
     * Fake-Overpass. contentEncoding: gesetzt → Antwort-Header Content-Encoding (body muss dann schon gzip sein).
     * seen: nimmt je Anfrage „Accept-Encoding“ und den entschlüsselten Abfragetext auf.
     */
    private static HttpServer server(int status, byte[] body, String contentEncoding, AtomicInteger calls,
            List<String> seen) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/api/interpreter", ex -> {
            calls.incrementAndGet();
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (seen != null) {
                seen.add(String.valueOf(ex.getRequestHeaders().getFirst("Accept-Encoding")));
                seen.add(URLDecoder.decode(req.substring("data=".length()), StandardCharsets.UTF_8));
            }
            if (contentEncoding != null) {
                ex.getResponseHeaders().set("Content-Encoding", contentEncoding);
            }
            ex.sendResponseHeaders(status, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        s.start();
        return s;
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (OutputStream o = new GZIPOutputStream(b)) {
            o.write(raw);
        }
        return b.toByteArray();
    }

    /**
     * Gebäude im Plan von Etappe 0, gemessen 2026-10-28 am Klartext-Fake. Die Datei hat 68 (CityPlannerTest), der
     * Kreis mit Radius 120 schneidet 5 davon ab. Eine leere oder kaputte Kachel ergibt 0.
     */
    private static final int TOWN_BUILDINGS = 63;

    private static byte[] town() throws Exception {
        try (InputStream in = OverpassMirrorTest.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            return in.readAllBytes();
        }
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + "/api/interpreter";
    }

    @Test
    void busyFirstInstanceFallsThroughToTheNextWithoutWaiting(@TempDir Path cache) throws Exception {
        AtomicInteger busyCalls = new AtomicInteger();
        AtomicInteger okCalls = new AtomicInteger();
        HttpServer busy = server(502, "busy".getBytes(), busyCalls);
        HttpServer ok = server(200, town(), okCalls);
        try {
            OsmClient osm = new OsmClient(List.of(url(busy), url(ok)), "http://127.0.0.1:1/unused");
            CityStages stages = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 120, 5_000_000);
            long t0 = System.nanoTime();
            CityPlanner.Plan p = stages.plan(0).get(20, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(p.blocks() > 1000, "Plan aus der zweiten Instanz");
            assertEquals(1, busyCalls.get());
            assertEquals(1, okCalls.get());
            assertTrue(ms < 10_000, "ohne 30-s-Pause: " + ms + " ms");
            stages.close();
        } finally {
            busy.stop(0);
            ok.stop(0);
        }
    }

    @Test
    void cacheFromBeforeTheStagesIsUsedForASmallPlace(@TempDir Path cache) throws Exception {
        Files.write(cache.resolve("49.84510_7.86760_150.json"), town());
        AtomicInteger calls = new AtomicInteger();
        HttpServer never = server(502, "busy".getBytes(), calls);
        try {
            OsmClient osm = new OsmClient(List.of(url(never)), "http://127.0.0.1:1/unused");
            CityStages stages = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 120, 5_000_000);
            CityPlanner.Plan p = stages.plan(0).get(20, TimeUnit.SECONDS);
            assertTrue(p.blocks() > 1000);
            assertEquals(0, calls.get(), "kein Netz nötig");
            stages.close();
        } finally {
            never.stop(0);
        }
    }

    /** gzip über die Leitung: Anfrage bittet darum, Antwort mit Content-Encoding gzip wird entpackt. */
    @Test
    void gzipAnswerIsUnpacked(@TempDir Path cache) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer gz = server(200, gzip(town()), "GZIP", calls, seen);
        try {
            OsmClient osm = new OsmClient(List.of(url(gz)), "http://127.0.0.1:1/unused");
            CityStages stages = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 120, 5_000_000);
            CityPlanner.Plan p = stages.plan(0).get(20, TimeUnit.SECONDS);
            assertEquals(TOWN_BUILDINGS, p.buildings());
            assertEquals("gzip", seen.get(0), "Accept-Encoding der Anfrage");
            stages.close();
        } finally {
            gz.stop(0);
        }
    }

    /**
     * Overpass bricht bei Zeitüberschreitung mit HTTP 200, leeren elements und „remark: runtime error“ ab. Das darf
     * weder als leere Kachel durchgehen noch im Zwischenspeicher landen – nächste Instanz.
     */
    @Test
    void runtimeErrorRemarkFallsThroughAndIsNotCached(@TempDir Path cache) throws Exception {
        byte[] aborted = ("{\"version\":0.6,\"elements\":[],\"remark\":\"runtime error: Query timed out in "
                + "\\\"query\\\" at line 1 after 140 seconds.\"}").getBytes(StandardCharsets.UTF_8);
        AtomicInteger abortedCalls = new AtomicInteger();
        AtomicInteger okCalls = new AtomicInteger();
        HttpServer bad = server(200, aborted, abortedCalls);
        HttpServer ok = server(200, town(), okCalls);
        try {
            OsmClient osm = new OsmClient(List.of(url(bad), url(ok)), "http://127.0.0.1:1/unused");
            CityStages stages = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 120, 5_000_000);
            CityPlanner.Plan p = stages.plan(0).get(20, TimeUnit.SECONDS);
            assertEquals(TOWN_BUILDINGS, p.buildings(), "Kachel der zweiten Instanz");
            assertEquals(1, abortedCalls.get());
            assertEquals(1, okCalls.get());
            stages.close();
            for (Path f : cacheFiles(cache)) {
                String json = CityStages.readCache(f);
                assertTrue(!json.contains("runtime error"), "abgebrochene Kachel im Zwischenspeicher: " + f);
            }
        } finally {
            bad.stop(0);
            ok.stop(0);
        }
    }

    /** Neu geschrieben wird nur .json.gz; ein zweiter Lauf liest sie, eine alte Klartext-Kachel bleibt lesbar. */
    @Test
    void tileIsCachedAsGzipAndOldPlainTileIsStillRead(@TempDir Path root) throws Exception {
        Path cache = root.resolve("neu");
        AtomicInteger calls = new AtomicInteger();
        HttpServer ok = server(200, town(), calls);
        Path written;
        try {
            OsmClient osm = new OsmClient(List.of(url(ok)), "http://127.0.0.1:1/unused");
            CityStages first = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 120, 5_000_000);
            first.plan(0).get(20, TimeUnit.SECONDS);
            first.close();
            List<Path> files = cacheFiles(cache);
            assertEquals(1, files.size(), "genau eine Datei: " + files);
            written = files.get(0);
            assertTrue(written.getFileName().toString().startsWith(OsmClient.cacheTag() + "_")
                    && written.getFileName().toString().endsWith(".json.gz"), written.toString());

            CityStages second = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 120, 5_000_000);
            assertEquals(TOWN_BUILDINGS, second.plan(0).get(20, TimeUnit.SECONDS).buildings());
            second.close();
            assertEquals(1, calls.get(), "zweiter Lauf aus dem Zwischenspeicher");

            // Klartext-Kachel derselben Fassung (Name ohne .gz) wie bis 2026-10-28 geschrieben.
            Path old = Files.createDirectories(root.resolve("alt"));
            String plainName = written.getFileName().toString().replaceFirst("\\.gz$", "");
            Files.write(old.resolve(plainName), town());
            CityStages third = new CityStages(osm, old, "Test", 49.8451, 7.8676, 120, 5_000_000);
            assertEquals(TOWN_BUILDINGS, third.plan(0).get(20, TimeUnit.SECONDS).buildings());
            third.close();
            assertEquals(1, calls.get(), "alte .json ohne Netz gelesen");
        } finally {
            ok.stop(0);
        }
    }

    /** Server-Timeout unter den 150 s des Clients, Ausgabe nach Quadtile. */
    @Test
    void queryAsksForServerTimeout140AndQuadtileOrder(@TempDir Path cache) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer ok = server(200, town(), null, calls, seen);
        try {
            OsmClient osm = new OsmClient(List.of(url(ok)), "http://127.0.0.1:1/unused");
            CityStages stages = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 120, 5_000_000);
            stages.plan(0).get(20, TimeUnit.SECONDS);
            stages.close();
            String q = seen.get(1);
            assertTrue(q.startsWith("[out:json][timeout:140];"), q);
            assertTrue(q.endsWith("out geom qt;"), q);
        } finally {
            ok.stop(0);
        }
    }

    private static List<Path> cacheFiles(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }
}
