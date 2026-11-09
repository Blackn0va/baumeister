package com.blackn0va.baumeister.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Speichergrenze, parallele Abrufe und Fehlerbehandlung der Höhenkacheln – mit Attrappe statt HTTP. */
class ElevationTilesTest {

    /** Terrarium-PNG, in dem jedes Pixel denselben Wert hat. */
    static byte[] png(double metres) {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        double v = metres + 32768;
        int r = (int) Math.floor(v / 256);
        int g = (int) Math.floor(v) - r * 256;
        int b = (int) Math.round((v - Math.floor(v)) * 256);
        int rgb = r << 16 | g << 8 | b;
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Zählende Attrappe: Kachel (tx, ty) hat die Höhe tx + ty. */
    private static final class CountingSource implements ElevationTiles.TileSource {
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        @Override
        public byte[] fetch(int zoom, int tx, int ty) {
            calls.computeIfAbsent(zoom + "/" + tx + "/" + ty, k -> new AtomicInteger()).incrementAndGet();
            return png(tx + ty);
        }

        int calls(int zoom, int tx, int ty) {
            AtomicInteger n = calls.get(zoom + "/" + tx + "/" + ty);
            return n == null ? 0 : n.get();
        }
    }

    @Test
    void evictsTheLeastRecentlyUsedTileAboveTheLimit(@TempDir Path dir) throws Exception {
        ElevationTiles tiles = new ElevationTiles(dir, 10, 3, new CountingSource());
        tiles.tile(1, 1);
        tiles.tile(2, 1);
        tiles.tile(3, 1);
        tiles.tile(1, 1);  // 1/1 frisch benutzt → 2/1 ist jetzt die älteste
        tiles.tile(4, 1);
        assertEquals(3, tiles.inMemoryCount());
        assertTrue(tiles.inMemory(1, 1));
        assertFalse(tiles.inMemory(2, 1));
        assertTrue(tiles.inMemory(3, 1));
        assertTrue(tiles.inMemory(4, 1));
    }

    @Test
    void memoryStaysBoundedWhileTheDiskKeepsEverything(@TempDir Path dir) throws Exception {
        CountingSource source = new CountingSource();
        ElevationTiles tiles = new ElevationTiles(dir, 10, 8, source);
        for (int i = 0; i < 100; i++) {
            assertEquals(i + 7, tiles.tile(i, 7)[0], 1e-3);
            assertTrue(tiles.inMemoryCount() <= 8, "Speicher " + tiles.inMemoryCount());
        }
        // Rausgeflogene Kacheln kommen von der Platte, nicht noch einmal aus dem Netz.
        assertEquals(7f, tiles.tile(0, 7)[0], 1e-3);
        assertEquals(1, source.calls(10, 0, 7));
        // Der Dateiname trägt den Zoom – zwei Zooms im selben Ordner kommen sich nicht in die Quere.
        assertTrue(Files.isRegularFile(dir.resolve("10_0_7.png")));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(f -> f.toString().endsWith(".tmp")), "keine .tmp-Reste");
        }
    }

    @Test
    void differentTilesDownloadAtTheSameTime(@TempDir Path dir) throws Exception {
        int n = 4;
        CountDownLatch allInside = new CountDownLatch(n);
        ElevationTiles tiles = new ElevationTiles(dir, 10, 16, (zoom, tx, ty) -> {
            allInside.countDown();
            // Vorher hielt jeder Download ein globales Schloss: dann käme nie ein zweiter herein und das hier liefe ab.
            try {
                if (!allInside.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Downloads liefen nacheinander statt gleichzeitig");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return png(tx);
        });
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<float[]>> jobs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int tx = i;
                jobs.add(pool.submit(() -> tiles.tile(tx, 0)));
            }
            for (int i = 0; i < n; i++) {
                assertEquals(i, jobs.get(i).get(20, TimeUnit.SECONDS)[0], 1e-3);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameTileIsFetchedOnceForAllWaiters(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ElevationTiles tiles = new ElevationTiles(dir, 10, 16, (zoom, tx, ty) -> {
            calls.incrementAndGet();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return png(42);
        });
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<float[]>> jobs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                jobs.add(pool.submit(() -> tiles.tile(5, 5)));
            }
            Thread.sleep(200);
            release.countDown();
            float[] first = jobs.get(0).get(20, TimeUnit.SECONDS);
            for (Future<float[]> f : jobs) {
                assertSame(first, f.get(20, TimeUnit.SECONDS));
            }
            assertEquals(1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aFailedDownloadIsNotCachedBeyondTheBlock(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        ElevationTiles tiles = new ElevationTiles(dir, 10, 16, (zoom, tx, ty) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("Höhendaten: HTTP 503 für Kachel " + tx + "/" + ty);
            }
            return png(123);
        }, now::get);
        IOException e = assertThrows(IOException.class, () -> tiles.tile(3, 4));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
        assertFalse(tiles.inMemory(3, 4));
        assertFalse(Files.exists(dir.resolve("10_3_4.png")));
        now.addAndGet(ElevationTiles.FAILURE_BLOCK_NANOS);
        assertEquals(123f, tiles.tile(3, 4)[0], 1e-3);
        assertEquals(2, calls.get());
    }

    @Test
    void aDeadTileIsAskedOnceWithinTheBlockNotOncePerPixel(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        ElevationTiles tiles = new ElevationTiles(dir, 10, 16, (zoom, tx, ty) -> {
            calls.incrementAndGet();
            throw new IOException("offline");
        }, now::get);
        // Vorher: jede Abfrage ein neuer Download (Prüferbefund: 972 je Chunk der Erdwelt).
        for (int i = 0; i < 50; i++) {
            IOException e = assertThrows(IOException.class, () -> tiles.elevation(48.82, 11.46));
            assertEquals("offline", e.getMessage());
        }
        assertEquals(1, calls.get());
        now.addAndGet(ElevationTiles.FAILURE_BLOCK_NANOS - 1);
        assertThrows(IOException.class, () -> tiles.tile(3, 4));
        assertEquals(2, calls.get(), "andere Kachel: eigener Versuch");
        assertThrows(IOException.class, () -> tiles.elevation(48.82, 11.46));
        assertEquals(2, calls.get(), "Sperre der ersten Kachel noch nicht abgelaufen");
        now.addAndGet(1);
        assertThrows(IOException.class, () -> tiles.elevation(48.82, 11.46));
        assertEquals(3, calls.get(), "nach der Sperre ein neuer Versuch");
    }

    @Test
    void aBrokenResponseNeverReachesTheDisk(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong();
        byte[] valid = png(77);
        ElevationTiles tiles = new ElevationTiles(dir, 10, 16, (zoom, tx, ty) -> {
            if (calls.incrementAndGet() == 1) {
                // Captive-Portal-Seite mit HTTP 200 bzw. ein abgeschnittener Body.
                return "<html>Bitte anmelden</html>".getBytes(StandardCharsets.UTF_8);
            }
            return valid;
        }, now::get);
        assertThrows(IOException.class, () -> tiles.tile(3, 4));
        assertFalse(Files.exists(dir.resolve("10_3_4.png")), "kaputte Antwort darf nicht im Cache liegen");
        now.addAndGet(ElevationTiles.FAILURE_BLOCK_NANOS);
        assertEquals(77f, tiles.tile(3, 4)[0], 1e-3);
        assertEquals(2, calls.get());
        assertTrue(Files.isRegularFile(dir.resolve("10_3_4.png")));
    }

    @Test
    void aBrokenFileOnDiskIsReplacedByAFreshDownload(@TempDir Path dir) throws Exception {
        CountingSource source = new CountingSource();
        byte[] truncated = Arrays.copyOf(png(5), 40);
        Files.write(dir.resolve("10_3_4.png"), truncated);
        ElevationTiles tiles = new ElevationTiles(dir, 10, 16, source);
        assertEquals(7f, tiles.tile(3, 4)[0], 1e-3);
        assertEquals(1, source.calls(10, 3, 4));
        assertTrue(Files.size(dir.resolve("10_3_4.png")) > truncated.length, "Datei ersetzt");
    }

    @Test
    void columnsWrapAcrossTheDateLine(@TempDir Path dir) throws Exception {
        CountingSource source = new CountingSource();
        ElevationTiles tiles = new ElevationTiles(dir, 2, 16, source);
        // Zoom 2 hat die Spalten 0..3; lon 180 lag vorher in der nicht vorhandenen Spalte 4.
        tiles.elevation(10, 180);
        tiles.elevation(10, -180);
        assertEquals(0, source.calls(2, 4, 1));
        assertTrue(source.calls(2, 0, 1) + source.calls(2, 3, 1) >= 1);
        // Pole: Mercator endet bei 85,05°, darüber gilt die Randzeile statt einer Ausnahme.
        tiles.elevation(89.9, 0);
        tiles.elevation(-90, 0);
        assertThrows(IOException.class, () -> tiles.tile(0, 4));
    }

    @Test
    void elevationInterpolatesInsideOneTile(@TempDir Path dir) throws Exception {
        ElevationTiles tiles = new ElevationTiles(dir, 10, 16, (zoom, tx, ty) -> png(812.5));
        assertEquals(812.5, tiles.elevation(48.82, 11.46), 0.01);
        assertEquals(10, tiles.zoom());
    }
}
