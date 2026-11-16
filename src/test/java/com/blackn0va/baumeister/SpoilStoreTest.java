package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Abraum in vollen Fässern (Wunsch 2026-11-16, „alles, was er beim Tunnelbau abbaut, stellt er immer in einer vollen
 * Kiste ab“): Ein Fass kommt erst, wenn 27 Stapel zusammen sind; was zusammen anfiel, liegt zusammen.
 */
class SpoilStoreTest {

    /** Stapelgröße wie im Spiel: Enderperlen 16, sonst 64. */
    private static SpoilStore<String> store() {
        return new SpoilStore<>(id -> id.equals("ender_pearl") ? 16 : 64);
    }

    @Test
    void aBarrelIsFullAtTwentySevenStacks() {
        SpoilStore<String> s = store();
        s.add("cobblestone", 26 * 64);
        s.add("raw_iron", 3);
        assertEquals(27, s.slots(), "26 Stapel Bruchstein und ein angefangener Stapel Roheisen");
        s.add("cobblestone", 64 + 10);
        s.add("ender_pearl", 17);
        assertEquals(28 + 1 + 2, s.slots(), "Bruchstein 28, Roheisen 1, Enderperlen 2 (je 16)");
        List<Map.Entry<String, Integer>> barrel = s.take(SpoilStore.BARREL_SLOTS);
        assertEquals(27, barrel.size());
        assertTrue(barrel.stream().allMatch(e -> e.getKey().equals("cobblestone") && e.getValue() == 64),
                "volle Stapel derselben Sorte zuerst");
        assertEquals(1 + 1 + 2, s.slots(), "übrig: 10 Bruchstein, 3 Roheisen, 17 Enderperlen");
        List<Map.Entry<String, Integer>> rest = s.take(Integer.MAX_VALUE);
        assertEquals(List.of(Map.entry("cobblestone", 10), Map.entry("raw_iron", 3), Map.entry("ender_pearl", 16),
                Map.entry("ender_pearl", 1)), rest);
        assertTrue(s.isEmpty());
        s.add("cobblestone", 0);
        assertTrue(s.isEmpty(), "nichts dazu");
    }
}
