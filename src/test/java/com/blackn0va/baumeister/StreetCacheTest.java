package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.OsmClient;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zwischenspeicher und Stadtausstattung (2026-10-29): Die Abfrage holt Gleise, Zäune und Parkplätze; eine Kachel der
 * alten Abfrage (v2) darf nicht still weiterbenutzt werden – ihr fehlen genau diese Elemente. Lokaler Fake-Dienst,
 * kein Overpass.
 */
class StreetCacheTest {

    private static byte[] town() throws Exception {
        try (InputStream in = StreetCacheTest.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            return in.readAllBytes();
        }
    }

    private static HttpServer server(byte[] body, AtomicInteger calls, List<String> queries) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/api/interpreter", ex -> {
            calls.incrementAndGet();
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            queries.add(URLDecoder.decode(req.substring("data=".length()), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        s.start();
        return s;
    }

    @Test
    void oldV2TileIsNotUsedButFetchedAgainWithTheNewElements(@TempDir Path root) throws Exception {
        // Beide Ordner im TempDir – ein Geschwister des TempDir läge im gemeinsamen %TEMP% und überlebte den Lauf.
        Path cache = Files.createDirectories(root.resolve("neu"));
        AtomicInteger calls = new AtomicInteger();
        List<String> queries = new CopyOnWriteArrayList<>();
        HttpServer ok = server(town(), calls, queries);
        try {
            OsmClient osm = new OsmClient(List.of("http://127.0.0.1:" + ok.getAddress().getPort()
                    + "/api/interpreter"), "http://127.0.0.1:1/unused");
            // Erst ermitteln, welche Kachel die Etappe 0 braucht (ein Lauf ins leere Verzeichnis), dann dieselbe
            // Kachel unter dem alten Namen „v2_…“ hinlegen – wie sie bis 2026-10-28 geschrieben wurde.
            CityStages probe = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 120, 5_000_000);
            probe.plan(0).get(20, TimeUnit.SECONDS);
            probe.close();
            Path written;
            try (var files = Files.list(cache)) {
                written = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            }
            String name = written.getFileName().toString();
            Path old = Files.createDirectories(root.resolve("alt"));
            Files.copy(written, old.resolve("v2" + name.substring(name.indexOf('_'))));
            calls.set(0);
            queries.clear();
            CityStages stages = new CityStages(osm, old, "Test", 49.8451, 7.8676, 120, 5_000_000);
            stages.plan(0).get(20, TimeUnit.SECONDS);
            stages.close();
            assertEquals(1, calls.get(), "alte v2-Kachel still weiterbenutzt");
            String q = queries.getFirst();
            assertTrue(q.contains("way[\"railway\"~"), q);
            assertTrue(q.contains("way[\"barrier\"~"), q);
            assertTrue(q.contains("way[\"amenity\"=\"parking\"]"), q);
        } finally {
            ok.stop(0);
        }
    }

    @Test
    void cacheTagFollowsTheQueryContentNotTheHandwrittenVersion() {
        String tag = OsmClient.cacheTag();
        assertTrue(tag.matches("q[0-9a-f]{8}"), tag);
        assertNotEquals("v2", tag);
        String name = CityStages.cacheName(49.8, 7.8, 49.9, 7.9);
        assertTrue(name.startsWith(tag + "_"), name);
        assertFalse(name.startsWith("v2_"), name);
        assertEquals(String.format(Locale.ROOT, "%s_%.5f_%.5f_%.5f_%.5f.json", tag, 49.8, 7.8, 49.9, 7.9), name);
    }
}
