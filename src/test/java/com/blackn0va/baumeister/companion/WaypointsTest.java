package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WaypointsTest {

    static final UUID ME = new UUID(3, 1);
    static final UUID OTHER = new UUID(3, 2);
    static final UUID WORLD = new UUID(4, 1);

    static Waypoints.Point at(double x) {
        return new Waypoints.Point(WORLD, x, 64, -5, 90f);
    }

    @Test
    void namesAreNormalized() {
        assertEquals("zuhause", Waypoints.normalize("Zuhause!"));
        assertEquals("meine basis", Waypoints.normalize("  Meine   Basis "));
        assertNull(Waypoints.normalize("   "));
        assertNull(Waypoints.normalize("x".repeat(33)));
    }

    @Test
    void markFindReplaceRemove() {
        Waypoints w = new Waypoints();
        assertEquals(Waypoints.Marked.NEW, w.mark(ME, "Zuhause", at(1)));
        assertEquals(Waypoints.Marked.REPLACED, w.mark(ME, "zuhause", at(2)));
        assertEquals(2, w.find(ME, "ZUHAUSE").orElseThrow().getValue().x());
        assertTrue(w.find(OTHER, "zuhause").isEmpty(), "Orte gehören dem Spieler");
        assertTrue(w.remove(ME, "zuhause"));
        assertTrue(w.find(ME, "zuhause").isEmpty());
    }

    @Test
    void uniquePrefixFindsButAmbiguousDoesNot() {
        Waypoints w = new Waypoints();
        w.mark(ME, "Haus am See", at(1));
        assertEquals("haus am see", w.find(ME, "haus").orElseThrow().getKey());
        assertEquals("haus am see", w.find(ME, "see").orElseThrow().getKey());
        w.mark(ME, "Hausboot", at(2));
        assertTrue(w.find(ME, "haus").isEmpty(), "zwei Treffer = keiner");
    }

    @Test
    void limitPerPlayer() {
        Waypoints w = new Waypoints();
        for (int i = 0; i < Waypoints.MAX_PER_PLAYER; i++) {
            assertEquals(Waypoints.Marked.NEW, w.mark(ME, "ort " + i, at(i)));
        }
        assertEquals(Waypoints.Marked.FULL, w.mark(ME, "noch einer", at(99)));
        assertEquals(Waypoints.Marked.NEW, w.mark(OTHER, "noch einer", at(99)));
    }

    @Test
    void jsonRoundTripSkipsBrokenEntries() {
        Waypoints w = new Waypoints();
        w.mark(ME, "Zuhause", at(7));
        w.mark(OTHER, "Mine", at(8));
        Waypoints back = Waypoints.fromJson(w.toJson());
        assertEquals(7, back.find(ME, "zuhause").orElseThrow().getValue().x());
        assertEquals(List.of("mine"), back.names(OTHER));
        String broken = "{\"" + ME + "\":{\"gut\":{\"welt\":\"" + WORLD + "\",\"x\":1,\"y\":2,\"z\":3,\"blick\":0},"
                + "\"kaputt\":{\"x\":1}},\"keine-uuid\":{}}";
        Waypoints partial = Waypoints.fromJson(broken);
        assertEquals(List.of("gut"), partial.names(ME));
        assertTrue(Waypoints.fromJson("kein json").names(ME).isEmpty());
    }
}
