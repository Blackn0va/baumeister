package com.blackn0va.baumeister.rucksack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** Rucksack: kein Gegenstand entsteht, keiner verschwindet – beim Einlagern, Sortieren und Blättern. */
class RucksackLogicTest {

    /** Stapel im Test: Sorte, Menge, Höchstmenge (Schwert 1, Perle 16, Stein 64). */
    record S(String kind, int amount, int max) {
    }

    static final RucksackLogic.StackOps<S> OPS = new RucksackLogic.StackOps<>() {
        @Override
        public boolean similar(S a, S b) {
            return a.kind().equals(b.kind());
        }

        @Override
        public int amount(S t) {
            return t.amount();
        }

        @Override
        public int max(S t) {
            return t.max();
        }

        @Override
        public S withAmount(S t, int amount) {
            return new S(t.kind(), amount, t.max());
        }
    };

    static List<S> empty(int n) {
        return new ArrayList<>(Collections.nCopies(n, null));
    }

    static Map<String, Integer> totals(List<S> slots) {
        Map<String, Integer> m = new TreeMap<>();
        for (S s : slots) {
            if (s != null) {
                m.merge(s.kind(), s.amount(), Integer::sum);
            }
        }
        return m;
    }

    @Test
    void depositFillsMatchingStacksBeforeEmptySlots() {
        List<S> slots = empty(90);
        slots.set(60, new S("stein", 40, 64)); // auf Seite 2
        assertEquals(0, RucksackLogic.deposit(slots, new S("stein", 50, 64), OPS));
        assertEquals(64, slots.get(60).amount(), "erst den angefangenen Stapel auffüllen, auch auf späterer Seite");
        assertEquals(new S("stein", 26, 64), slots.get(0));
        assertEquals(Map.of("stein", 90), totals(slots));
    }

    @Test
    void depositReturnsWhatDoesNotFit() {
        List<S> slots = empty(2);
        assertEquals(1, RucksackLogic.deposit(slots, new S("perle", 33, 16), OPS));
        assertEquals(Map.of("perle", 32), totals(slots));
    }

    @Test
    void sortMergesAndKeepsEveryItem() {
        List<S> slots = empty(135);
        slots.set(3, new S("stein", 10, 64));
        slots.set(50, new S("schwert", 1, 1));
        slots.set(70, new S("stein", 60, 64));
        slots.set(100, new S("perle", 9, 16));
        slots.set(130, new S("perle", 9, 16));
        slots.set(131, new S("schwert", 1, 1));
        Map<String, Integer> before = totals(slots);
        RucksackLogic.sort(slots, OPS, Comparator.comparing(S::kind));
        assertEquals(before, totals(slots));
        assertEquals(List.of(new S("perle", 16, 16), new S("perle", 2, 16), new S("schwert", 1, 1),
                new S("schwert", 1, 1), new S("stein", 64, 64), new S("stein", 6, 64)), slots.subList(0, 6));
        assertNull(slots.get(6));
        assertEquals(6, RucksackLogic.used(slots));
    }

    @Test
    void sortNeverNeedsMoreSlotsEvenForOversizedStacks() {
        // Per Befehl erzeugte 100er-Stapel einer 64er-Sorte: nicht in 64er zerteilen, sonst reichen die Plätze nicht.
        List<S> slots = empty(2);
        slots.set(0, new S("stein", 100, 64));
        slots.set(1, new S("stein", 100, 64));
        RucksackLogic.sort(slots, OPS, Comparator.comparing(S::kind));
        assertEquals(Map.of("stein", 200), totals(slots));
    }

    @Test
    void pagesTurnWithinBounds() {
        assertEquals(1, RucksackLogic.turn(0, 100, true, false));
        assertEquals(10, RucksackLogic.turn(0, 100, true, true));
        assertEquals(99, RucksackLogic.turn(95, 100, true, true));
        assertEquals(0, RucksackLogic.turn(3, 100, false, true));
        assertEquals(0, RucksackLogic.turn(0, 1, true, false));
        assertEquals("Rucksack · Seite 3/100", RucksackLogic.title(2, 100));
    }

    @Test
    void searchFindsEveryPlaceOnEveryPage() {
        List<S> slots = empty(135);
        slots.set(2, new S("diamant", 5, 64));
        slots.set(99, new S("diamant", 64, 64));
        slots.set(40, new S("stein", 1, 64));
        assertEquals(List.of(2, 99), RucksackLogic.find(slots, s -> s.kind().equals("diamant")));
    }

    @Test
    void takeRemovesExactlyWhatIsHandedOut() {
        List<S> slots = empty(90);
        slots.set(0, new S("fackel", 64, 64));
        slots.set(50, new S("fackel", 10, 64));
        slots.set(70, new S("stein", 30, 64));
        List<S> got = RucksackLogic.take(slots, s -> s.kind().equals("fackel"), 32, OPS);
        assertEquals(List.of(new S("fackel", 32, 64)), got);
        // Erst der angebrochene 10er-Stapel, dann 22 vom vollen.
        assertNull(slots.get(50));
        assertEquals(42, slots.get(0).amount());
        assertEquals(Map.of("fackel", 42, "stein", 30), totals(slots));
    }

    @Test
    void takeGivesWhatThereIsWhenThereIsTooLittle() {
        List<S> slots = empty(45);
        slots.set(3, new S("perle", 9, 16));
        List<S> got = RucksackLogic.take(slots, s -> s.kind().equals("perle"), 100, OPS);
        assertEquals(List.of(new S("perle", 9, 16)), got);
        assertEquals(Map.of(), totals(slots));
        assertEquals(List.of(), RucksackLogic.take(slots, s -> s.kind().equals("perle"), 5, OPS));
    }
}
