package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Letzte Stellen der Erdwelt: Speichern beim Stopp, Reihenfolge, Laden – ohne Server. */
class VisitStoreTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void closeWritesAVisitWhoseSaveWasStillQueued(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("erde").resolve("besuche.json");
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService saver = Executors.newSingleThreadExecutor();
        try {
            // Der Schreib-Thread hängt noch an etwas anderem: das Speichern bleibt eingereiht (wie kurz vor dem Stopp).
            saver.execute(() -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            VisitStore store = new VisitStore(file, saver);
            store.remember(ALICE, new double[] {1.5, 64, -3.5, 180, 0});
            store.close();
            assertTrue(Files.isRegularFile(file), "Stelle beim Stopp verloren");
            String json = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(json.contains(ALICE.toString()), json);
        } finally {
            release.countDown();
            saver.shutdownNow();
        }
    }

    @Test
    void aQueuedOlderSaveNeverOverwritesTheStateWrittenAtClose(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("besuche.json");
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService saver = Executors.newSingleThreadExecutor();
        try {
            saver.execute(() -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            VisitStore store = new VisitStore(file, saver);
            store.remember(ALICE, new double[] {1, 2, 3, 4, 5});
            store.remember(BOB, new double[] {6, 7, 8, 9, 10});
            store.close();
            // Jetzt laufen die beiden eingereihten (älteren) Aufträge noch nach.
            release.countDown();
            saver.awaitTermination(10, TimeUnit.SECONDS);
            VisitStore reloaded = new VisitStore(file);
            reloaded.load();
            assertArrayEquals(new double[] {1, 2, 3, 4, 5}, reloaded.get(ALICE));
            assertArrayEquals(new double[] {6, 7, 8, 9, 10}, reloaded.get(BOB));
        } finally {
            release.countDown();
            saver.shutdownNow();
        }
    }

    @Test
    void closeWithoutNewVisitsLeavesTheFileAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("besuche.json");
        Files.writeString(file, "{\"" + ALICE + "\":[1.0,2.0,3.0,4.0,5.0]}\n", StandardCharsets.UTF_8);
        // Start gescheitert, load() lief nie: Der Stopp darf die Datei nicht mit {} überschreiben.
        VisitStore store = new VisitStore(file);
        store.close();
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains(ALICE.toString()));
        assertNull(store.get(ALICE));
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(f -> f.toString().endsWith(".tmp")), "keine .tmp-Reste");
        }
    }
}
