package com.blackn0va.baumeister.warp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Warps statt GWarp: Übernahme des Bestands, Grenzen, Besitz, Speichern. */
class WarpBookTest {

    /** plugins/GWarp/Warps.data von dev01, Stand 2026-11-05 (unverändert). */
    static final String GWARP = """
            Warps:
              plotwelt:
                name: plotwelt
                owner: 0d7f3aa9-c414-49d1-84e9-171a69f6a430
                ownername: Blackkn0va
                count: true
                loc:
                  x: 8.21739568695078
                  y: 63.0
                  z: -114.0906642600324
                  pitch: 14.100006
                  yaw: 1.0403748
                  world: plotwelt
              farmwelt:
                name: farmwelt
                owner: 0d7f3aa9-c414-49d1-84e9-171a69f6a430
                ownername: Blackkn0va
                count: true
                loc:
                  x: -129.54540761885485
                  y: 72.0
                  z: -270.17327989656025
                  pitch: 26.700089
                  yaw: 1.2250977
                  world: world
                visitors: 1
            """;

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();

    static WarpBook.Warp w(String name, UUID owner) {
        return new WarpBook.Warp(name, owner, "x", "world", 1, 2, 3, 0, 0, 0);
    }

    @Test
    void takesOverTheGWarpWarpsOfDev01() {
        WarpBook.Imported imp = WarpBook.importGWarp(GWARP);
        assertEquals(0, imp.skipped());
        WarpBook b = imp.book();
        assertEquals(2, b.size());
        WarpBook.Warp f = b.find("Farmwelt").orElseThrow();
        assertEquals("world", f.world());
        assertEquals(-129.54540761885485, f.x());
        assertEquals(72.0, f.y());
        assertEquals(1, f.visits());
        assertEquals("plotwelt", b.find("plotwelt").orElseThrow().world());
        // Meistbesucht zuerst.
        assertEquals(List.of("farmwelt", "plotwelt"), b.list().stream().map(WarpBook.Warp::name).toList());
    }

    @Test
    void brokenEntriesAreSkippedAndCounted() {
        WarpBook.Imported imp = WarpBook.importGWarp("""
                Warps:
                  ohneort:
                    name: ohneort
                    owner: 0d7f3aa9-c414-49d1-84e9-171a69f6a430
                  "../böse":
                    name: "../böse"
                    owner: 0d7f3aa9-c414-49d1-84e9-171a69f6a430
                    loc: {x: 1, y: 2, z: 3, world: world}
                """);
        assertEquals(0, imp.book().size());
        assertEquals(2, imp.skipped());
    }

    @Test
    void everyPlayerGetsTheirLimitAndOnlyMovesTheirOwn() {
        WarpBook b = new WarpBook();
        assertEquals(WarpBook.Result.OK, b.set(w("haus", A), 1, false));
        assertEquals(WarpBook.Result.LIMIT, b.set(w("mine", A), 1, false));
        assertEquals(WarpBook.Result.UPDATED, b.set(w("Haus", A), 1, false), "eigenen versetzen geht immer");
        assertEquals(WarpBook.Result.TAKEN, b.set(w("haus", B), 1, false));
        assertEquals(WarpBook.Result.UPDATED, b.set(w("haus", B), 1, true), "Admin darf");
        assertEquals(A, b.find("haus").orElseThrow().owner(), "Versetzen ändert den Besitzer nicht");
        assertEquals(WarpBook.Result.OK, b.set(w("mine", A), 0, false), "0 = unbegrenzt");
        assertEquals(WarpBook.Result.BAD_NAME, b.set(w("a b", A), 0, false));
    }

    @Test
    void onlyTheOwnerOrAnAdminDeletes() {
        WarpBook b = new WarpBook();
        b.set(w("haus", A), 1, false);
        assertEquals(WarpBook.Result.NOT_OWNER, b.delete("haus", B, false));
        assertEquals(WarpBook.Result.OK, b.delete("HAUS", A, false));
        assertEquals(WarpBook.Result.MISSING, b.delete("haus", A, true));
    }

    @Test
    void visitsCountOnlyStrangersAndSurviveSaving() {
        WarpBook b = new WarpBook();
        b.set(w("haus", A), 1, false);
        b.visit("haus", A);
        b.visit("haus", B);
        b.visit("haus", B);
        WarpBook back = WarpBook.fromJson(b.toJson());
        assertEquals(2, back.find("haus").orElseThrow().visits());
        assertEquals(b.list(), back.list());
    }

    @Test
    void unreadableFileIsAnErrorNotAnEmptyBook() {
        assertThrows(IllegalArgumentException.class, () -> WarpBook.fromJson("{kaputt"));
        assertThrows(IllegalArgumentException.class, () -> WarpBook.fromJson("{\"warps\":[{\"name\":\"../x\"}]}"));
    }

    @Test
    void namesAreSafeForChatAndClickableCommands() {
        assertTrue(WarpBook.validName("farmwelt"));
        assertTrue(WarpBook.validName("Schöne_Insel-2"));
        assertFalse(WarpBook.validName("a/b"));
        assertFalse(WarpBook.validName("§cRot"));
        assertFalse(WarpBook.validName("einsehrlangername-mehrals20"));
    }
}
