package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.OsmClient;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ganze Karte vorab (Wunsch 2026-10-29). Vorher holte der Bau jede Kachel erst, wenn ihre erste Etappe dran war –
 * dev01, Sindelfingen: 5,5 von
 * 10 min „lädt Karte“.
 */
class CityStagesPrefetchTest {

    private static byte[] town() throws Exception {
        try (InputStream in = CityStagesPrefetchTest.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            return in.readAllBytes();
        }
    }

    @Test
    void wholeMapIsFetchedOneTileAtATimeAndTheBuildNeverAsksAgain(@TempDir Path cache) throws Exception {
        byte[] body = town();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        s.createContext("/api/interpreter", ex -> {
            calls.incrementAndGet();
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                ex.getRequestBody().readAllBytes();
                Thread.sleep(50);  // wie ein echter Dienst: Zeit, in der eine zweite Abfrage dazwischenkäme
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        });
        s.start();
        try {
            OsmClient osm = new OsmClient(List.of("http://127.0.0.1:" + s.getAddress().getPort() + "/api/interpreter"),
                    "http://127.0.0.1:1/unused");
            CityStages stages = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 1500, 5_000_000);
            int tiles = stages.tiles();
            assertTrue(tiles >= 4, "Ort über mehrere Kacheln: " + tiles);
            assertEquals("", stages.mapProgress());
            stages.downloadAll();
            assertTrue(stages.mapProgress().startsWith(", Karte "), stages.mapProgress());
            long until = System.nanoTime() + 20_000_000_000L;
            while (!stages.mapProgress().isEmpty() && System.nanoTime() < until) {
                Thread.sleep(20);
            }
            assertEquals(tiles, stages.readyTiles(), "alle Kacheln vorab auf der Platte");
            assertEquals(tiles, calls.get(), "jede Kachel genau einmal");
            assertEquals(1, maxActive.get(), "nie zwei Abfragen gleichzeitig");
            // Bauen danach: die letzte Etappe liegt auf der letzten Kachel – keine einzige neue Abfrage.
            assertNotNull(stages.plan(stages.count() - 1).get(20, TimeUnit.SECONDS));
            assertNotNull(stages.plan(0).get(20, TimeUnit.SECONDS));
            assertEquals(tiles, calls.get());
            stages.close();
        } finally {
            s.stop(0);
        }
    }

    /**
     * Vorher lief eine Kachelabfrage nach close() (/bm stadt stopp, Neuplanen) mit allen Wiederholungen weiter und
     * fragte öffentliche Overpass-Instanzen parallel zum neuen Lauf. Zwei Adressen auf denselben Dienst: Die
     * Wiederholung auf Instanz 2 käme ohne 30-s-Pause sofort – nach close() darf sie gar nicht mehr kommen.
     */
    @Test
    void closedStagesStopRetryingTheMapService(@TempDir Path cache) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<CityStages> ref = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/api/interpreter", ex -> {
            ex.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                ref.get().close();
            }
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        s.start();
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/api/interpreter";
            OsmClient osm = new OsmClient(List.of(url, url + "?zwei"), "http://127.0.0.1:1/unused");
            CityStages stages = new CityStages(osm, cache, "Test", 49.8451, 7.8676, 1500, 5_000_000);
            ref.set(stages);
            stages.downloadAll();
            Thread.sleep(1000);
            assertEquals(1, calls.get(), "nach close() keine weitere Abfrage");
        } finally {
            s.stop(0);
        }
    }
}
