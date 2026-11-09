package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Bauvorschau: Ablauf, Ersetzen, Chat-Wörter und der Partikel-Umriss. */
class BuildPreviewTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final BuildPreview.Box BOX = BuildPreview.Box.of(10, 64, 20, 18, 70, 31);

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final BuildPreview<String> book = new BuildPreview<>(now::get);

    @Test
    void previewExpiresAfterItsLifetime() {
        book.offer(A, "world", BOX, 500, "plan", 120_000);
        now.addAndGet(119_000);
        assertTrue(book.has(A));
        assertEquals(1, book.secondsLeft(A));
        now.addAndGet(1_000);
        assertFalse(book.has(A), "Vorschau gilt nach Ablauf noch");
        assertNull(book.take(A), "verfallene Vorschau ließ sich bestätigen");
    }

    @Test
    void expireReportsEachLapsedPreviewOnce() {
        book.offer(A, "world", BOX, 500, "plan", 10_000);
        assertEquals(List.of(), book.expire());
        assertEquals(1, book.live().size());
        now.addAndGet(10_000);
        assertEquals(List.of("plan"), book.expire().stream().map(BuildPreview.Pending::plan).toList());
        assertEquals(List.of(), book.expire());
        assertTrue(book.isEmpty());
    }

    @Test
    void confirmTakesThePlanOnceAndANewPreviewReplacesTheOld() {
        book.offer(A, "world", BOX, 500, "alt", 60_000);
        assertEquals("alt", book.offer(A, "world", BOX, 700, "neu", 60_000).plan());
        BuildPreview.Pending<String> p = book.take(A);
        assertEquals("neu", p.plan());
        assertEquals(700, p.blocks());
        assertNull(book.take(A));
        assertFalse(book.discard(A));
    }

    @Test
    void chatWords() {
        assertEquals("bau ein kleines Haus", BuildPreview.showRequest("zeig mir ein kleines Haus"));
        assertEquals("bau einen Turm", BuildPreview.showRequest("Zeige mir einen Turm"));
        assertEquals("baue eine Mauer", BuildPreview.showRequest("vorschau: baue eine Mauer"));
        assertNull(BuildPreview.showRequest("bau mir ein Haus"));
        assertNull(BuildPreview.showRequest("zeig mir"));
        assertTrue(BuildPreview.confirms("Ja!"));
        assertTrue(BuildPreview.confirms("ja, bau es"));
        assertTrue(BuildPreview.confirms("bau"));
        assertFalse(BuildPreview.confirms("bau ein Haus"));
        assertTrue(BuildPreview.discards("nein."));
        // „abbrechen“ bleibt das Stopp-Wort (ChatIntent), „weg damit“ das Rückgängig-Wort.
        assertFalse(BuildPreview.discards("abbrechen"));
        assertFalse(BuildPreview.discards("weg damit"));
    }

    @Test
    void outlineTouchesAllEightCornersAndStaysUnderTheLimit() {
        List<double[]> pts = BuildPreview.outline(BOX, 400);
        assertTrue(pts.size() <= 400, "Punkte: " + pts.size());
        for (double x : new double[] {10, 19}) {
            for (double y : new double[] {64, 71}) {
                for (double z : new double[] {20, 32}) {
                    assertTrue(pts.stream().anyMatch(p -> p[0] == x && p[1] == y && p[2] == z),
                            "Ecke fehlt: " + x + " " + y + " " + z);
                }
            }
        }
        // Jeder Punkt liegt auf dem Rand des Kastens (mindestens zwei Koordinaten auf einer Kastenseite).
        for (double[] p : pts) {
            int onFace = (p[0] == 10 || p[0] == 19 ? 1 : 0) + (p[1] == 64 || p[1] == 71 ? 1 : 0)
                    + (p[2] == 20 || p[2] == 32 ? 1 : 0);
            assertTrue(onFace >= 2, "Punkt nicht auf einer Kante");
        }
    }

    @Test
    void hugeBuildsGetSparserNotMorePoints() {
        BuildPreview.Box huge = BuildPreview.Box.of(-96, -60, 0, 95, 130, 191);
        List<double[]> pts = BuildPreview.outline(huge, 400);
        assertTrue(pts.size() <= 400 && pts.size() > 300, "Punkte: " + pts.size());
        assertTrue(pts.stream().anyMatch(p -> p[0] == 96 && p[1] == 131 && p[2] == 192), "letzte Ecke fehlt");
        assertEquals(192, huge.sizeX());
        assertEquals(191, huge.sizeY());
    }
}
