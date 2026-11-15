package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Rückgängig-Daten großer Bauten kopieren, ohne den Server-Thread einzufrieren (Vorfall dev01 2026-11-15 11:46:
 * Bastionsruine 45 804 Blöcke, Watchdog nach 35 s in BuildJob.undoRecord → Map.copyOf).
 */
class BuildJobUndoCopyTest {

    /** Hasht wie ein Paper-Block: Vec3i (y + z·31)·31 + x, verknüpft mit dem Hash der Welt. */
    record PaperLikeBlock(int x, int y, int z) {
        @Override
        public int hashCode() {
            return ((y + z * 31) * 31 + x) ^ 0x2f3a9c41;
        }
    }

    private static Map<PaperLikeBlock, Integer> bastion(int n) {
        Map<PaperLikeBlock, Integer> m = new LinkedHashMap<>();
        for (int y = 0; y < 120 && m.size() < n; y++) {
            for (int z = 0; z < 40 && m.size() < n; z++) {
                for (int x = 0; x < 76 && m.size() < n; x++) {
                    m.put(new PaperLikeBlock(180 + x, 63 + y, 30 + z), x);
                }
            }
        }
        return m;
    }

    @Test
    void copiesAHundredThousandBlockPositionsQuickly() {
        Map<PaperLikeBlock, Integer> placed = bastion(100_000);
        // Map.copyOf braucht hier mehrere Sekunden (45 000: 1,1 s, quadratisch wachsend); die Kopie muss weit darunter.
        Map<PaperLikeBlock, Integer> copy = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> BuildJob.frozenCopy(placed));
        assertEquals(placed, copy);
        assertEquals(100_000, copy.size());
    }

    @Test
    void copyIsReadOnlyAndIndependentOfTheJob() {
        Map<PaperLikeBlock, Integer> placed = bastion(10);
        Map<PaperLikeBlock, Integer> copy = BuildJob.frozenCopy(placed);
        placed.clear();
        assertEquals(10, copy.size(), "spätere Änderungen am Bau dürfen den Rückgängig-Eintrag nicht ändern");
        assertThrows(UnsupportedOperationException.class, () -> copy.put(new PaperLikeBlock(0, 0, 0), 1));
    }
}
