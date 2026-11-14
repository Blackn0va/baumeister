package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rückgängig über Neustart: Einträge mit Zettel überleben den Start, alles Verdächtige wird nicht geladen, sondern
 * gelöscht – und nie über eine Verknüpfung hinweg.
 */
class UndoJournalTest {

    private static final UUID OWNER = UUID.fromString("6f1c2e1a-1111-4222-8333-444455556666");
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 86_400_000L;
    private static final UndoJournal.Limits LIMITS = new UndoJournal.Limits(5, 7 * DAY, 512L * 1024 * 1024);

    @TempDir
    Path tmp;

    private Path root() throws IOException {
        return Files.createDirectories(tmp.resolve("rückgängig"));
    }

    private static UndoSpool.Part house(int n) {
        int[] xs = new int[n];
        int[] ys = new int[n];
        int[] zs = new int[n];
        String[] st = new String[n];
        for (int i = 0; i < n; i++) {
            xs[i] = 100 + i % 7;
            ys[i] = 64 + i / 49;
            zs[i] = -30 - i / 7 % 7;
            st[i] = i % 3 == 0 ? "minecraft:air" : "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,"
                    + "waterlogged=false]";
        }
        return new UndoSpool.Part(xs, ys, zs, st, n);
    }

    private static UndoJournal.Manifest manifest(long when, long count) {
        return new UndoJournal.Manifest(OWNER, "Heiko", "world", when, count);
    }

    /** Eintrag wie ein normaler Bau ihn hinterlässt (Kopie + Zettel), synchron. */
    private static Path copy(Path root, String name, long when, int n) {
        Path dir = root.resolve(name);
        UndoJournal.writeCopy(dir, house(n), manifest(when, n));
        return dir;
    }

    @Test
    void normalBuildSurvivesARestart() throws Exception {
        Path root = root();
        UndoJournal j = new UndoJournal(root, true);
        Object entry = new Object();
        j.persistCopy(entry, house(300), manifest(NOW - 1000, 300));
        j.drain();
        assertTrue(j.hasCopy(entry));
        j.close();

        // Neustart: Sweep wie in onEnable, dann prüfen wie im Hintergrund-Thread.
        List<Path> kept = UndoJournal.sweep(root, true);
        assertEquals(1, kept.size(), "Eintrag mit Zettel muss den Start überleben");
        List<UndoJournal.Loaded> loaded = UndoJournal.validate(kept, LIMITS, NOW);
        assertEquals(1, loaded.size());
        UndoJournal.Manifest m = loaded.getFirst().manifest();
        assertEquals(OWNER, m.owner());
        assertEquals("Heiko", m.ownerName());
        assertEquals("world", m.world());
        assertEquals(300, m.count());
        UndoSpool.Part back = UndoSpool.read(loaded.getFirst().dir().resolve("teil-00001.bin.gz"));
        UndoSpool.Part orig = house(300);
        assertArrayEquals(Arrays.copyOf(orig.x(), 300), Arrays.copyOf(back.x(), back.size()));
        assertArrayEquals(Arrays.copyOf(orig.state(), 300), Arrays.copyOf(back.state(), back.size()));
    }

    @Test
    void placedTypeSurvivesARestartAndAForgedOneRejectsTheEntry() throws Exception {
        // Format 2 (2026-11-14): Was der Bau gesetzt hat, kommt über den Neustart mit – sonst setzte das Rückgängig
        // nach jedem Deploy wieder blind zurück (UndoCheck).
        Path root = root();
        UndoSpool.Part h = house(50);
        String[] placed = new String[50];
        for (int i = 0; i < 50; i++) {
            placed[i] = i % 2 == 0 ? "minecraft:oak_planks" : null;
        }
        Path good = root.resolve("bau-" + (NOW - 2000) + "-1");
        UndoJournal.writeCopy(good, new UndoSpool.Part(h.x(), h.y(), h.z(), h.state(), placed, 50),
                manifest(NOW - 2000, 50));
        List<UndoJournal.Loaded> loaded = UndoJournal.validate(List.of(good), LIMITS, NOW);
        assertEquals(1, loaded.size());
        assertTrue(loaded.getFirst().states().contains("minecraft:oak_planks"), "Typ wird beim Start mitgeprüft");
        UndoSpool.Part back = UndoSpool.read(good.resolve("teil-00001.bin.gz"));
        assertEquals("minecraft:oak_planks", back.placedAt(0));
        assertEquals(null, back.placedAt(1));

        // Ein Typ, der kein Blockname ist, verwirft den Eintrag wie ein kaputter Zustand (fail-closed).
        placed[4] = "minecraft:Stein mit Leerzeichen";
        Path bad = root.resolve("bau-" + (NOW - 1000) + "-2");
        UndoJournal.writeCopy(bad, new UndoSpool.Part(h.x(), h.y(), h.z(), h.state(), placed, 50),
                manifest(NOW - 1000, 50));
        assertEquals(List.of(), UndoJournal.validate(List.of(bad), LIMITS, NOW));
    }

    @Test
    void tileEntrySurvivesARestartWithItsTileAndAForgedTileIsRejected() throws Exception {
        // 2026-11-14: Weltbau-Kacheln lagen ohne Zettel unter rückgängig/ und gingen bei jedem Start verloren.
        Path root = Files.createDirectories(tmp.resolve(UndoJournal.TILE_DIR));
        UndoJournal j = new UndoJournal(root, true);
        UndoSpool tile = new UndoSpool(root.resolve(String.valueOf(NOW - 3000)));
        for (int i = 0; i < 500; i++) {
            tile.record(i, 70, -i, "minecraft:air", "minecraft:stone");
        }
        tile.finish();
        j.persistSpool(tile, manifest(NOW - 3000, 500).withTile(3, -2));
        List<Path> kept = UndoJournal.sweep(root, true);
        assertEquals(1, kept.size(), "Kachel mit Zettel muss den Start überleben");
        List<UndoJournal.Loaded> loaded = UndoJournal.validate(kept, LIMITS, NOW);
        assertEquals(1, loaded.size());
        assertEquals("3,-2", loaded.getFirst().manifest().tile());
        assertEquals(500, loaded.getFirst().manifest().count());
        // Ein normaler Eintrag trägt keine Kachel.
        assertEquals(null, UndoJournal.readManifest(copy(root(), "bau-" + (NOW - 1) + "-1", NOW - 1, 10)).tile());
        // Gefälschte Kachel: Eintrag verworfen und gelöscht (fail-closed wie jedes andere Feld).
        Path forged = root.resolve(String.valueOf(NOW - 2000));
        UndoSpool f = new UndoSpool(forged);
        f.record(1, 70, 1, "minecraft:air");
        f.finish();
        UndoJournal.writeManifest(forged, new UndoJournal.Manifest(OWNER, "Heiko", "world", NOW - 2000, 1,
                "3;rm -rf"));
        assertEquals(List.of(), UndoJournal.validate(List.of(forged), LIMITS, NOW));
        j.close();
    }

    @Test
    void citySpoolGetsAManifestAndOrphansAreSweptAsBefore() throws Exception {
        Path root = root();
        UndoSpool city = new UndoSpool(root.resolve(String.valueOf(NOW - 5000)));
        for (int i = 0; i < 1000; i++) {
            city.record(i, 64, i, "minecraft:stone");
        }
        city.finish();
        UndoJournal j = new UndoJournal(root, true);
        j.persistSpool(city, manifest(NOW - 5000, city.count()));
        // Weltbau-Kachel (TileUndo) und abgebrochenes Schreiben: ohne Zettel.
        UndoSpool tile = new UndoSpool(root.resolve(String.valueOf(NOW - 4000)));
        tile.record(1, 2, 3, "minecraft:dirt");
        tile.finish();
        Files.writeString(root.resolve("streu.txt"), "x");
        j.close();

        List<Path> kept = UndoJournal.sweep(root, true);
        assertEquals(List.of(city.dir()), kept);
        assertFalse(Files.exists(tile.dir()), "Ordner ohne Zettel bleibt liegen");
        assertFalse(Files.exists(root.resolve("streu.txt")));
        assertEquals(1000, UndoJournal.validate(kept, LIMITS, NOW).getFirst().manifest().count());
    }

    @Test
    void switchedOffEverythingIsDeletedAtStartLikeBefore() throws Exception {
        Path root = root();
        Path a = copy(root, "bau-1-1", NOW, 10);
        assertEquals(List.of(), UndoJournal.sweep(root, false));
        assertFalse(Files.exists(a));
        UndoJournal off = new UndoJournal(root, false);
        Object entry = new Object();
        off.persistCopy(entry, house(10), manifest(NOW, 10));
        off.close();
        assertFalse(off.hasCopy(entry));
        try (var s = Files.list(root)) {
            assertEquals(0, s.count(), "ausgeschaltet darf nichts geschrieben werden");
        }
    }

    @Test
    void damagedPartIsSkippedAndDeleted() throws Exception {
        Path root = root();
        Path bad = copy(root, "bau-2-1", NOW - 10, 500);
        Path good = copy(root, "bau-1-1", NOW - 20, 50);
        byte[] gz = Files.readAllBytes(bad.resolve("teil-00001.bin.gz"));
        for (int i = 20; i < gz.length - 8; i += 3) {
            gz[i] ^= 0x5A;
        }
        Files.write(bad.resolve("teil-00001.bin.gz"), gz);
        List<UndoJournal.Loaded> loaded = UndoJournal.validate(UndoJournal.sweep(root, true), LIMITS, NOW);
        assertEquals(List.of(good), loaded.stream().map(UndoJournal.Loaded::dir).toList());
        assertFalse(Files.exists(bad), "beschädigter Eintrag muss weg");
    }

    @Test
    void countOnTheManifestMustMatchThePartsBecauseCutGzipReadsShorter() throws Exception {
        Path root = root();
        Path dir = copy(root, "bau-1-1", NOW, 300);
        UndoJournal.writeManifest(dir, manifest(NOW, 301));
        assertEquals(List.of(), UndoJournal.validate(List.of(dir), LIMITS, NOW));
        assertFalse(Files.exists(dir));

        // Abgeschnittene Teildatei: wird nie als vollständiger Eintrag geladen.
        Path cut = copy(root, "bau-2-1", NOW, 20_000);
        Path part = cut.resolve("teil-00001.bin.gz");
        byte[] gz = Files.readAllBytes(part);
        Files.write(part, Arrays.copyOf(gz, gz.length / 2));
        assertEquals(List.of(), UndoJournal.validate(List.of(cut), LIMITS, NOW));
    }

    @Test
    void foreignNamesFilesAndValuesAreRejected() throws Exception {
        Path root = root();
        Path extra = copy(root, "bau-1-1", NOW, 10);
        Files.writeString(extra.resolve("notiz.txt"), "hallo");
        Path badName = copy(root, "x1", NOW, 10);
        Path gap = copy(root, "bau-3-1", NOW, 10);
        Files.move(gap.resolve("teil-00001.bin.gz"), gap.resolve("teil-00002.bin.gz"));
        Path badWorld = copy(root, "bau-4-1", NOW, 10);
        UndoJournal.writeManifest(badWorld, new UndoJournal.Manifest(OWNER, "Heiko", "../../server", NOW, 10));
        Path badState = root.resolve("bau-5-1");
        UndoSpool.Part p = house(10);
        p.state()[3] = "minecraft:stone]; /op jeder";
        UndoJournal.writeCopy(badState, p, manifest(NOW, 10));
        Path future = copy(root, "bau-6-1", NOW + 3 * DAY, 10);
        Path old = copy(root, "bau-7-1", NOW - 8 * DAY, 10);
        Path badJson = copy(root, "bau-8-1", NOW, 10);
        Files.writeString(badJson.resolve(UndoJournal.MANIFEST), "{\"version\":1,\"name\":7}", StandardCharsets.UTF_8);
        Path ok = copy(root, "bau-9-1", NOW, 10);

        List<Path> kept = UndoJournal.sweep(root, true);
        assertEquals(9, kept.size());
        List<UndoJournal.Loaded> loaded = UndoJournal.validate(kept, LIMITS, NOW);
        assertEquals(List.of(ok), loaded.stream().map(UndoJournal.Loaded::dir).toList());
        for (Path gone : List.of(extra, badName, gap, badWorld, badState, future, old, badJson)) {
            assertFalse(Files.exists(gone), "nicht gelöscht: " + gone.getFileName());
        }
    }

    @Test
    void newestEntriesWinWhenDepthOrSizeIsExceeded() throws Exception {
        Path root = root();
        Path a = copy(root, "bau-1-1", NOW - 3000, 100);
        Path b = copy(root, "bau-2-1", NOW - 2000, 100);
        Path c = copy(root, "bau-3-1", NOW - 1000, 100);
        List<UndoJournal.Loaded> two = UndoJournal.validate(List.of(a, b, c),
                new UndoJournal.Limits(2, 7 * DAY, Long.MAX_VALUE), NOW);
        assertEquals(List.of(c, b), two.stream().map(UndoJournal.Loaded::dir).toList());
        assertFalse(Files.exists(a));

        long oneEntry = two.getFirst().bytes();
        List<UndoJournal.Loaded> small = UndoJournal.validate(List.of(b, c),
                new UndoJournal.Limits(5, 7 * DAY, oneEntry + oneEntry / 2), NOW);
        assertEquals(List.of(c), small.stream().map(UndoJournal.Loaded::dir).toList());
        assertFalse(Files.exists(b));
    }

    /**
     * Verknüpfung link → target anlegen: Symlink, sonst (Windows ohne Entwicklermodus, gemessen 2026-10-29: „Dem
     * Client fehlt ein erforderliches Recht“) eine Junction – die geht ohne Recht, und Files.walk läuft in sie hinein.
     */
    private static void link(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Symlink nicht anlegbar: " + e.getMessage());
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assumeTrue(p.waitFor() == 0 && Files.isDirectory(link), "Junction nicht anlegbar");
        }
    }

    private Path outsideWithImportantFile() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("fremd"));
        Files.writeString(outside.resolve(UndoJournal.MANIFEST), "{}");
        Files.writeString(outside.resolve("wichtig.txt"), "nicht löschen");
        return outside;
    }

    @Test
    void linkedEntryIsRemovedWithoutTouchingItsTarget() throws Exception {
        Path root = root();
        Path outside = outsideWithImportantFile();
        Path link = root.resolve("12345");
        link(link, outside);
        assertEquals(List.of(), UndoJournal.sweep(root, true));
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(outside.resolve("wichtig.txt")), "Ziel der Verknüpfung wurde angefasst");
        assertThrows(IOException.class, () -> UndoJournal.check(link, NOW, DAY));
    }

    @Test
    void linkInsideAnEntryRejectsItAndDeletingDoesNotFollowIt() throws Exception {
        Path root = root();
        Path outside = outsideWithImportantFile();
        Path entry = copy(root, "bau-1-1", NOW, 10);
        link(entry.resolve("teil-00002.bin.gz"), outside);
        assertEquals(List.of(), UndoJournal.validate(UndoJournal.sweep(root, true), LIMITS, NOW));
        assertFalse(Files.exists(entry), "Eintrag mit Verknüpfung wurde geladen oder liegt noch");
        assertTrue(Files.exists(outside.resolve("wichtig.txt")), "Löschen lief durch die Verknüpfung");
    }

    @Test
    void deleteDirNeverRunsIntoALinkedFolder() throws Exception {
        Path outside = outsideWithImportantFile();
        Path spool = Files.createDirectories(tmp.resolve("spool"));
        Files.writeString(spool.resolve("teil-00001.bin.gz"), "x");
        link(spool.resolve("verknüpft"), outside);
        UndoSpool.deleteDir(spool);
        assertFalse(Files.exists(spool, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(outside.resolve("wichtig.txt")), "deleteDir hat das Ziel einer Verknüpfung geleert");
    }

    @Test
    void forgetDeletesTheCopyAfterItWasWritten() throws Exception {
        Path root = root();
        UndoJournal j = new UndoJournal(root, true);
        Object entry = new Object();
        j.persistCopy(entry, house(40), manifest(NOW, 40));
        j.forget(entry);
        j.drain();
        assertFalse(j.hasCopy(entry));
        try (var s = Files.list(root)) {
            assertEquals(0, s.count(), "Kopie liegt nach forget noch");
        }
        j.close();
    }

    @Test
    void spoolOutsideTheRootGetsNoManifest() throws Exception {
        Path root = root();
        UndoSpool elsewhere = new UndoSpool(tmp.resolve("anderswo").resolve("123"));
        elsewhere.record(1, 1, 1, "minecraft:stone");
        elsewhere.finish();
        UndoJournal j = new UndoJournal(root, true);
        j.persistSpool(elsewhere, manifest(NOW, 1));
        j.close();
        assertFalse(Files.exists(elsewhere.dir().resolve(UndoJournal.MANIFEST)));
    }

    @Test
    void reloadedSpoolIsReadOnly() throws Exception {
        Path root = root();
        Path dir = copy(root, "bau-1-1", NOW, 5);
        UndoSpool s = UndoSpool.existing(dir, 5);
        assertEquals(5, s.count());
        assertThrows(IllegalStateException.class, () -> s.record(0, 0, 0, "minecraft:stone"));
        assertEquals(5, UndoSpool.read(dir.resolve("teil-00001.bin.gz")).size(), "Teil wurde überschrieben");
    }

    @Test
    void namesAreCleanedSoTheNextStartAcceptsThem() {
        assertEquals("Rcon", UndoJournal.cleanName("Rcon"));
        assertEquals("a_b_c", UndoJournal.cleanName("a/b\\c"));
        assertEquals("unbekannt", UndoJournal.cleanName(null));
        assertEquals(40, UndoJournal.cleanName("x".repeat(80)).length());
    }

    // ---------------------------------------------------------------- Nachbesserung (Prüferbefunde)

    @Test
    void reloadHandsTheWriterThreadOverInsteadOfWaitingOnTheMainThread() throws Exception {
        Path root = root();
        UndoJournal old = new UndoJournal(root, true);
        CountDownLatch release = new CountDownLatch(1);
        // Steht für die Prüfung beim Start oder eine große Kopie, die gerade im alten Thread läuft.
        old.later(() -> {
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Object entry = new Object();
        old.persistCopy(entry, house(40), manifest(NOW, 40));
        UndoJournal now = new UndoJournal(root, true);
        long t0 = System.nanoTime();
        try {
            now.takeOver(old);
        } finally {
            release.countDown();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2000, "takeOver wartete " + ms + " ms auf dem Hauptthread auf den alten Schreib-Thread");

        // forget im neuen Journal läuft in derselben Reihe NACH dem Schreiben im alten – keine Kopie bleibt übrig.
        now.forget(entry);
        now.drain();
        try (var st = Files.list(root)) {
            assertEquals(0, st.count(), "Kopie überlebte forget nach dem Neuladen");
        }
        // Das alte Journal darf den übernommenen Thread nicht mehr beenden.
        old.close();
        Object next = new Object();
        now.persistCopy(next, house(5), manifest(NOW, 5));
        now.drain();
        assertTrue(now.hasCopy(next));
        now.close();
    }

    @Test
    void entryWithAStateThisServerDoesNotKnowIsDiscardedAtLoad() throws Exception {
        Path root = root();
        Path stale = root.resolve("bau-1-1");
        UndoSpool.Part p = house(30);
        // Hieß bis 1.16 so, heute dirt_path: Das Muster passt, nur die laufende Serverfassung kennt ihn nicht.
        p.state()[4] = "minecraft:grass_path";
        UndoJournal.writeCopy(stale, p, manifest(NOW, 30));
        Path ok = copy(root, "bau-2-1", NOW, 30);
        List<UndoJournal.Loaded> loaded = UndoJournal.validate(UndoJournal.sweep(root, true), LIMITS, NOW);
        assertEquals(2, loaded.size(), "das Muster allein fängt den Fall nicht – erst der Hauptthread");
        assertTrue(loaded.stream().anyMatch(l -> l.states().contains("minecraft:grass_path")),
                "Zustände des Eintrags fehlen in Loaded");

        UndoJournal j = new UndoJournal(root, true);
        List<UndoJournal.Loaded> kept = j.keepKnown(loaded, st -> !st.equals("minecraft:grass_path"));
        assertEquals(List.of(ok), kept.stream().map(UndoJournal.Loaded::dir).toList(),
                "Eintrag mit unbekanntem Zustand wurde übernommen");
        j.drain();
        assertFalse(Files.exists(stale), "Eintrag mit unbekanntem Zustand liegt noch");
        assertTrue(Files.exists(ok));
        j.close();
    }

    @Test
    void partWithMoreEntriesThanASpoolEverWritesIsRejected() throws Exception {
        Path root = root();
        Path dir = Files.createDirectories(root.resolve("bau-1-1"));
        Path part = dir.resolve("teil-00001.bin.gz");
        int n = UndoSpool.PART + 1;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(
                Files.newOutputStream(part)), 1 << 16))) {
            out.writeInt(Integer.MIN_VALUE);
            out.writeUTF("minecraft:stone");
            for (int i = 0; i < n; i++) {
                out.writeInt(i % 100);
                out.writeInt(64);
                out.writeInt(i / 100);
                out.writeInt(0);
            }
        }
        UndoJournal.writeManifest(dir, manifest(NOW, n));
        IOException e = assertThrows(IOException.class, () -> UndoSpool.read(part, UndoSpool.PART),
                "Teil über PART wurde ganz eingelesen");
        assertEquals("teil_zu_groß", e.getMessage());
        assertEquals(List.of(), UndoJournal.validate(List.of(dir), LIMITS, NOW));
        assertFalse(Files.exists(dir));
    }

    @Test
    void worldWhoseNameCannotBeStoredGetsNoEntry() throws Exception {
        Path root = root();
        UndoJournal j = new UndoJournal(root, true);
        Object entry = new Object();
        j.persistCopy(entry, house(10), new UndoJournal.Manifest(OWNER, "Heiko", "Bauwelt Süd", NOW, 10));
        j.drain();
        assertFalse(j.hasCopy(entry), "Eintrag für eine Welt geschrieben, die der nächste Start still verwirft");
        try (var st = Files.list(root)) {
            assertEquals(0, st.count());
        }
        assertTrue(UndoJournal.storableWorld("world_nether"));
        assertFalse(UndoJournal.storableWorld("Bauwelt Süd"));
        j.close();
    }
}
