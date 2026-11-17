package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.blackn0va.baumeister.BuildJob.UndoRecord;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rückgängig-Stapel mit Tiefe 50 (2026-11-17): Herausfallen nur über der Tiefe und nie still, ältere Einträge auf der
 * Platte statt im Speicher, und die Lag-Wache findet ihren Eintrag auch nach dem Umzug.
 */
class UndoStackTest {

    private static final UUID HEIKO = UUID.fromString("0d7f3aa9-1111-4222-8333-444455556666");
    private static final UUID GAST = UUID.fromString("9a1b2c3d-1111-4222-8333-444455556666");
    private static final Instant T0 = Instant.parse("2026-11-16T20:17:15.123456Z");

    @TempDir
    Path tmp;

    /** Eintrag dieser Sitzung: Ursprünge im Speicher (Inhalt egal, nur die Zahl zählt). */
    private static UndoRecord inMemory(UUID owner, int blocks, int minute) {
        List<Map.Entry<Block, BlockData>> originals = new ArrayList<>(Collections.nCopies(blocks, null));
        return new UndoRecord(owner, owner == HEIKO ? "Blackkn0va" : "Gast", null, originals,
                T0.plusSeconds(60L * minute), null);
    }

    /** Wie ComfortService.onDisk: derselbe Eintrag, gelesen aus einer Kopie. */
    private UndoRecord onDisk(UndoRecord r) {
        return new UndoRecord(r.owner(), r.ownerName(), r.world(), List.of(), r.when(),
                UndoSpool.existing(tmp.resolve("kopie-" + r.when().toEpochMilli()), r.size()));
    }

    /** Stapel wie in BuildService: push legt vorn an, der neueste steht vorn. */
    private static Deque<UndoRecord> stackOf(List<UndoRecord> oldestFirst) {
        Deque<UndoRecord> d = new ArrayDeque<>();
        oldestFirst.forEach(d::push);
        return d;
    }

    private static List<UndoRecord> sessionOf(int n) {
        List<UndoRecord> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(inMemory(HEIKO, 40 + i, i));
        }
        return out;
    }

    @Test
    void trimDropsOnlyTheOldestBeyondTheDepthAndHandsEachOverOldestFirst() {
        List<UndoRecord> all = sessionOf(53);
        Deque<UndoRecord> stack = stackOf(all);
        List<UndoRecord> dropped = new ArrayList<>();
        UndoStack.trim(stack, 50, dropped::add);
        assertEquals(List.of(all.get(0), all.get(1), all.get(2)), dropped);
        assertEquals(50, stack.size());
        assertSame(all.get(52), stack.peekFirst());
        assertSame(all.get(3), stack.peekLast());

        UndoStack.trim(stack, 50, dropped::add);
        assertEquals(3, dropped.size(), "unter der Tiefe fällt nichts heraus");
    }

    @Test
    void onlyEntriesBehindTheNewestFiveMoveToDiskAndOnlyWithAFinishedCopy() {
        List<UndoRecord> all = sessionOf(9);  // Platz 0 = all[8] … Platz 8 = all[0]
        Deque<UndoRecord> stack = stackOf(all);
        UndoRecord copyStillWriting = all.get(2);  // Platz 6
        List<UndoRecord> asked = new ArrayList<>();
        int moved = UndoStack.offload(stack, UndoStack.IN_MEMORY, r -> {
            asked.add(r);
            return r == copyStillWriting ? r : onDisk(r);
        });
        assertEquals(3, moved);
        assertEquals(List.of(all.get(3), all.get(2), all.get(1), all.get(0)), asked, "nur ab Platz 5 gefragt");
        List<UndoRecord> now = new ArrayList<>(stack);
        assertEquals(9, now.size());
        for (int place = 0; place < UndoStack.IN_MEMORY; place++) {
            assertSame(all.get(8 - place), now.get(place), "Platz " + place + " bleibt im Speicher");
        }
        assertSame(copyStillWriting, now.get(6));
        for (int place : new int[] {5, 7, 8}) {
            UndoRecord r = now.get(place);
            assertEquals(all.get(8 - place).when(), r.when(), "Reihenfolge verloren auf Platz " + place);
            assertEquals(0, r.originals().size());
            assertEquals(all.get(8 - place).size(), r.size());
        }
    }

    @Test
    void entriesAlreadyOnDiskAreNotAskedAgain() {
        List<UndoRecord> all = sessionOf(7);
        all.set(0, onDisk(all.get(0)));  // nach Neustart geladen oder schon umgezogen
        Deque<UndoRecord> stack = stackOf(all);
        List<UndoRecord> asked = new ArrayList<>();
        UndoStack.offload(stack, UndoStack.IN_MEMORY, r -> {
            asked.add(r);
            return r;
        });
        assertEquals(List.of(all.get(1)), asked);
    }

    @Test
    void takeFindsTheEntryAlsoAfterItMovedToDisk() {
        List<UndoRecord> all = sessionOf(8);
        Deque<UndoRecord> stack = stackOf(all);
        UndoRecord watched = all.get(0);  // die Lag-Wache hält das Objekt von vor dem Umzug
        UndoStack.offload(stack, UndoStack.IN_MEMORY, this::onDisk);
        UndoRecord taken = UndoStack.take(stack, watched);
        assertEquals(watched.when(), taken.when());
        assertEquals(0, taken.originals().size(), "der Vertreter auf der Platte kommt zurück");
        assertEquals(7, stack.size());
        assertNull(UndoStack.take(stack, watched), "schon weg");
    }

    @Test
    void takeLeavesAnotherOwnersEntryFromTheSameMomentAlone() {
        UndoRecord mine = inMemory(HEIKO, 10, 3);
        UndoRecord theirs = new UndoRecord(GAST, "Gast", null, List.of(), mine.when(),
                UndoSpool.existing(tmp.resolve("gast"), 5));
        Deque<UndoRecord> stack = stackOf(List.of(theirs));
        assertNull(UndoStack.take(stack, mine));
        assertEquals(1, stack.size());
    }

    @Test
    void dropLineNamesOwnerBlocksAgeAndDepth() {
        UndoRecord r = inMemory(HEIKO, 8428, 0);
        assertEquals("rückgängig | aus_dem_stapel | besitzer=Blackkn0va blöcke=8428 alter_min=108 tiefe=50",
                UndoStack.dropLine(r, 50, T0.plusSeconds(108 * 60 + 59)));
    }
}
