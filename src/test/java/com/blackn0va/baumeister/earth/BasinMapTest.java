package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.terrain.ElevationTiles;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Offene und geschlossene Senken auf einer synthetischen Karte (Zoom 1: 2 × 2 Kacheln, 512 × 512 Pixel). */
class BasinMapTest {

    private static final int ZOOM = 1;

    /** Landkarte: +100 m, darin Ozean, Bucht, Senke, Lagune, Becken über die Kachelgrenze und ein „Kaspisches Meer“. */
    static float height(int gx, int gy) {
        if (gx >= 10 && gx < 100 && gy >= 50 && gy < 450) {
            return -3000;  // Ozean (erreicht den Kartenrand nicht – offen nur über die Tiefe)
        }
        if (gx >= 100 && gx < 150 && gy >= 200 && gy < 260) {
            return -20;  // flache Bucht am Ozean
        }
        if (gx >= 300 && gx < 330 && gy >= 300 && gy < 330) {
            return -86;  // Death Valley
        }
        if (gx >= 200 && gx < 210 && gy >= 100 && gy < 110) {
            return -5;  // abgeschnittene Lagune
        }
        if (gx >= 240 && gx < 280 && gy >= 400 && gy < 420) {
            return -50;  // Becken über die Kachelgrenze x = 256
        }
        if (gx >= 400 && gx < 480 && gy >= 150 && gy < 250) {
            return gx >= 440 && gy >= 200 ? -773 : -29;  // Kaspisches Meer: Seefläche und Tiefenrinne, aber kein Ozean
        }
        return 100;
    }

    private static class Source implements BasinMap.Tiles {
        final Set<String> loaded = ConcurrentHashMap.newKeySet();
        final Map<String, AtomicInteger> loads = new ConcurrentHashMap<>();

        @Override
        public float[] tile(int tx, int ty) throws IOException {
            loaded.add(tx + "/" + ty);
            loads.computeIfAbsent(tx + "/" + ty, k -> new AtomicInteger()).incrementAndGet();
            float[] t = new float[256 * 256];
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    t[y * 256 + x] = height(tx * 256 + x, ty * 256 + y);
                }
            }
            return t;
        }
    }

    private static BasinMap map(Source s) {
        return new BasinMap(s, ZOOM, 1500, 10, 1_000_000);
    }

    @Test
    void bayConnectedToTheDeepOceanIsOpen() throws IOException {
        BasinMap m = map(new Source());
        assertEquals(BasinMap.OPEN, m.label(120, 230));
        assertEquals(BasinMap.OPEN, m.label(50, 300));
    }

    @Test
    void enclosedDepressionIsClosed() throws IOException {
        assertEquals(BasinMap.CLOSED, map(new Source()).label(310, 310));
    }

    @Test
    void shallowCutOffLagoonCountsAsSea() throws IOException {
        assertEquals(BasinMap.OPEN, map(new Source()).label(205, 105));
    }

    @Test
    void deepLakeWithoutOceanDepthIsClosed() throws IOException {
        // Das Kaspische Meer reicht in den Daten bis −773 m – unter deepSeed (1 500), also eine Senke.
        BasinMap m = map(new Source());
        assertEquals(BasinMap.CLOSED, m.label(410, 160));
        assertEquals(BasinMap.CLOSED, m.label(470, 240));
    }

    @Test
    void basinAcrossATileBorderGetsOneAnswerFromBothSides() throws IOException {
        // Vorher (Fenster um die gerechnete Kachel) konnte dieselbe Komponente von zwei Kacheln aus verschieden
        // ausfallen – gemessen am Kaspischen Meer (42° N geschlossen, Atyrau offen). Jetzt zählt nur die Komponente.
        BasinMap left = map(new Source());
        BasinMap right = map(new Source());
        assertEquals(BasinMap.CLOSED, left.label(250, 410));
        assertEquals(BasinMap.CLOSED, right.label(270, 410));
        assertEquals(left.label(270, 410), right.label(250, 410));
    }

    @Test
    void componentLargerThanTheLimitCountsAsSea() throws IOException {
        BasinMap tiny = new BasinMap(new Source(), ZOOM, 1500, 10, 100);
        assertEquals(BasinMap.OPEN, tiny.label(310, 310));
    }

    @Test
    void landNextToADepressionAnswersForTheDepression() throws IOException {
        BasinMap m = map(new Source());
        // Ein Pixel neben Death Valley (Land): die Senke in der Nähe entscheidet.
        assertFalse(m.open(ElevationTiles.pixelLat(315, ZOOM), ElevationTiles.pixelLon(331, ZOOM)));
        // Mitten im Land ohne Senke in der Nähe: im Zweifel offen (Stand vor der Karte).
        assertTrue(m.open(ElevationTiles.pixelLat(150, ZOOM), ElevationTiles.pixelLon(350, ZOOM)));
        // Mitten im Ozean.
        assertTrue(m.open(ElevationTiles.pixelLat(300, ZOOM), ElevationTiles.pixelLon(50, ZOOM)));
    }

    @Test
    void oceanAnswersWithoutLoadingTheWholeMap() throws IOException {
        Source s = new Source();
        map(s).label(50, 100);
        assertEquals(1, s.loaded.size(), s.loaded.toString());
    }

    @Test
    void aSearchWaitingForATileDoesNotBlockOtherRegions() throws Exception {
        // Vorher lief jede Suche samt Download unter EINEM Schloss: Hing die Kachel einer Gegend (Nordsee 3,5 s,
        // Netzausfall bis 60 s), stand die Erzeugung unter 0 m überall.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        Source slow = new Source() {
            @Override
            public float[] tile(int tx, int ty) throws IOException {
                if (tx == 1 && ty == 1) {
                    blocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("unterbrochen", e);
                    }
                }
                return super.tile(tx, ty);
            }
        };
        BasinMap m = map(slow);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Byte> deathValley = pool.submit(() -> m.label(310, 310));
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            Future<Byte> ocean = pool.submit(() -> m.label(50, 100));
            assertEquals(BasinMap.OPEN, ocean.get(2, TimeUnit.SECONDS), "Ozean wartet nicht auf Death Valley");
            release.countDown();
            assertEquals(BasinMap.CLOSED, deathValley.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cacheDropsTheLeastRecentlyReadTileNotAll() throws IOException {
        // Vorher leerte das Limit ALLE Ergebnisse auf einmal – die gerade benutzte Gegend musste neu gesucht werden.
        Source s = new Source();
        BasinMap m = new BasinMap(s, ZOOM, 1500, 10, 1_000_000, 2);
        assertEquals(BasinMap.OPEN, m.label(50, 100));    // Kachel 0/0
        assertEquals(BasinMap.LAND, m.label(300, 50));    // Kachel 1/0
        assertEquals(BasinMap.OPEN, m.label(50, 100));    // 0/0 wieder gelesen
        assertEquals(BasinMap.LAND, m.label(5, 470));     // Kachel 0/1: 1/0 fällt heraus
        assertEquals(BasinMap.OPEN, m.label(50, 100));
        assertEquals(BasinMap.LAND, m.label(400, 400));   // Kachel 1/1: 0/1 fällt heraus
        assertEquals(BasinMap.OPEN, m.label(50, 100));
        assertEquals(1, s.loads.get("0/0").get(), "die laufend gelesene Gegend bleibt im Speicher");
        assertTrue(m.cachedTiles() <= 2, "Limit eingehalten: " + m.cachedTiles());
    }

    @Test
    void missingTilesAreReportedNotGuessed() {
        BasinMap m = new BasinMap((tx, ty) -> {
            throw new IOException("offline");
        }, ZOOM, 1500, 10, 1000);
        assertThrows(IOException.class, () -> m.open(10, 10));
    }
}
