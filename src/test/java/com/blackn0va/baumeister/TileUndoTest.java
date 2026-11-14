package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.BuildJob.UndoRecord;
import com.blackn0va.baumeister.WorldTiles.Tile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Eigener Rückgängig-Speicher des Weltbaus: je Kachel ein Eintrag, älteste fallen samt Dateien heraus. */
class TileUndoTest {

    @TempDir
    Path dir;

    /** Eintrag mit n Blöcken auf der Platte (wie CityJob ihn liefert). */
    private UndoRecord record(String name, int n) {
        UndoSpool spool = new UndoSpool(dir.resolve(name));
        for (int i = 0; i < n; i++) {
            spool.record(i, 64, 0, "minecraft:stone");
        }
        spool.finish();
        return new UndoRecord(null, "CONSOLE", null, List.of(), Instant.now(), spool);
    }

    @Test
    void keepsOnlyTheNewestTilesAndDeletesTheFilesOfTheOldest() {
        TileUndo undo = new TileUndo(2);
        UndoRecord a = record("a", 3);
        UndoRecord b = record("b", 3);
        UndoRecord c = record("c", 3);
        assertTrue(undo.add(new Tile(0, 0), a));
        assertTrue(undo.add(new Tile(1, 0), b));
        assertTrue(Files.isDirectory(a.spool().dir()));
        assertTrue(undo.add(new Tile(2, 0), c));
        assertEquals(2, undo.size());
        assertFalse(Files.exists(a.spool().dir()), "Dateien der verdrängten Kachel liegen noch");
        assertTrue(Files.isDirectory(b.spool().dir()));
        assertNull(undo.take(new Tile(0, 0)));
        assertEquals(new Tile(2, 0), undo.latest());
    }

    @Test
    void takeReturnsTheEntryOfThatTileOnce() {
        TileUndo undo = new TileUndo(3);
        UndoRecord a = record("a", 2);
        undo.add(new Tile(-4, 7), a);
        undo.add(new Tile(5, 5), record("b", 2));
        TileUndo.Entry e = undo.take(new Tile(-4, 7));
        assertEquals(new Tile(-4, 7), e.tile());
        assertSame(a, e.record());
        assertTrue(Files.isDirectory(a.spool().dir()), "take löscht nichts – das Zurücksetzen braucht die Dateien");
        assertNull(undo.take(new Tile(-4, 7)));
        assertEquals(1, undo.size());
    }

    @Test
    void sameTileTwiceReplacesTheOlderEntry() {
        TileUndo undo = new TileUndo(3);
        UndoRecord first = record("a", 2);
        UndoRecord second = record("b", 2);
        undo.add(new Tile(1, 1), first);
        undo.add(new Tile(1, 1), second);
        assertEquals(1, undo.size());
        assertFalse(Files.exists(first.spool().dir()));
        assertTrue(undo.has(new Tile(1, 1)), "Start-Übernahme fragt das, bevor sie einen alten Eintrag nimmt");
        assertFalse(undo.has(new Tile(1, 2)));
        assertSame(second, undo.take(new Tile(1, 1)).record());
        assertFalse(undo.has(new Tile(1, 1)));
    }

    @Test
    void emptyRecordIsNotKept() {
        TileUndo undo = new TileUndo(3);
        assertFalse(undo.add(new Tile(0, 0), new UndoRecord(null, "CONSOLE", null, List.of(), Instant.now(), null)));
        assertFalse(undo.add(new Tile(0, 1), record("leer", 0)));
        assertEquals(0, undo.size());
        assertNull(undo.latest());
    }

    @Test
    void depthZeroKeepsNothing() {
        TileUndo undo = new TileUndo(0);
        UndoRecord a = record("a", 2);
        assertFalse(undo.add(new Tile(0, 0), a));
        assertFalse(Files.exists(a.spool().dir()));
    }
}
