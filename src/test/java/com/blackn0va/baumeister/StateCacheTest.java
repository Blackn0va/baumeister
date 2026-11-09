package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Zustände aus einem früheren Lauf, die diese Serverfassung nicht kennt: überspringen statt im Timer zu werfen. */
class StateCacheTest {

    /** Wie Bukkit.createBlockData: unbekannte Zustände werfen IllegalArgumentException. */
    private static String create(List<String> calls, String state) {
        calls.add(state);
        if (state.startsWith("minecraft:gibts_nicht")) {
            throw new IllegalArgumentException("Could not parse data: " + state);
        }
        return "data(" + state + ")";
    }

    @Test
    void unknownStateIsSkippedAndCountedInsteadOfThrowing() {
        List<String> calls = new ArrayList<>();
        StateCache<String> cache = new StateCache<>(s -> create(calls, s));
        assertEquals("data(minecraft:stone)", cache.get("minecraft:stone"));
        assertNull(cache.get("minecraft:gibts_nicht[alt=ja]"), "unbekannter Zustand muss null liefern");
        assertNull(cache.get("minecraft:gibts_nicht[alt=ja]"));
        assertEquals("data(minecraft:stone)", cache.get("minecraft:stone"));
        assertEquals(2, cache.skipped());
        assertEquals(1, cache.unknownStates());
        assertEquals(List.of("minecraft:stone", "minecraft:gibts_nicht[alt=ja]"), calls,
                "jeder Zustand nur einmal geparst");
    }

    @Test
    void knowsDoesNotCountAsSkipped() {
        StateCache<String> cache = new StateCache<>(s -> create(new ArrayList<>(), s));
        assertTrue(cache.knows("minecraft:oak_stairs[facing=north]"));
        assertFalse(cache.knows("minecraft:gibts_nicht"));
        assertEquals(0, cache.skipped());
    }
}
