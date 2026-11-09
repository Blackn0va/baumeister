package com.blackn0va.baumeister.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Pos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 7-Segment-Anzeige: Ziffernform, Hex-Tabelle, Leitungen berühren keine fremden Segmente. */
class SevenSegmentTest {

    @Test
    void hexTableIsTheUsualOne() {
        assertTrue("abcdefg".chars().allMatch(c -> SevenSegment.lit(8, (char) c)), "8: alles an");
        assertEquals("bc", segments(1));
        assertEquals("abdeg", segments(2));
        assertEquals("abcefg", segments(10));
        assertEquals("aefg", segments(15));
    }

    @Test
    void digitHasSevenSegmentsOf27Lamps() {
        SevenSegment.Display d = SevenSegment.compile();
        assertEquals(4, d.levers().size());
        assertEquals(7, d.segments().size());
        assertEquals(27, d.segments().values().stream().mapToInt(List::size).sum());
        for (char c : "adg".toCharArray()) {
            assertEquals(5, d.segments().get(c).size(), "waagrecht " + c);
        }
        for (char c : "bcef".toCharArray()) {
            assertEquals(3, d.segments().get(c).size(), "senkrecht " + c);
        }
    }

    /**
     * Kein Staub eines Segments liegt waagrecht neben Staub eines anderen auf gleicher Höhe – sonst verbänden sie sich,
     * und zwei Segmente leuchteten zusammen.
     */
    @Test
    void segmentWiresDoNotTouchEachOther() {
        SevenSegment.Display d = SevenSegment.compile();
        Map<Pos, Character> owner = new HashMap<>();
        d.segments().forEach((s, lamps) -> lamps.forEach(l -> owner.put(l.up(), s)));
        for (Map.Entry<Pos, Character> e : owner.entrySet()) {
            Pos p = e.getKey();
            for (int[] dd : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Character other = owner.get(p.add(dd[0], 0, dd[1]));
                assertTrue(other == null || other.equals(e.getValue()), e.getValue() + " berührt " + other + " bei " + p);
            }
        }
    }

    @Test
    void everyCellIsPlacedOnceAndTheFloorIsComplete() {
        SevenSegment.Display d = SevenSegment.compile();
        Map<Pos, Integer> seen = new HashMap<>();
        for (CompiledBuild.Change c : d.module().cells()) {
            seen.merge(c.pos(), 1, Integer::sum);
        }
        assertFalse(seen.values().stream().anyMatch(n -> n > 1), "doppelt gesetzt");
        Pos size = d.module().size();
        for (int x = 0; x < size.x(); x++) {
            for (int z = 0; z < size.z(); z++) {
                assertTrue(seen.containsKey(new Pos(x, 0, z)), "Boden fehlt bei " + x + "," + z);
            }
        }
    }

    /**
     * Verstärker nur, wo die Stärke ausgeht: Gemessen 2026-11-07 stand mit der alten Regel fast auf jedem Block einer
     * – Segment b hinkte über 200 Ticks hinterher. Mit der neuen Regel 39 auf den 7 Leitungen (~100 Blöcke die längste).
     */
    @Test
    void linesHaveFewRepeaters() {
        SevenSegment.Display d = SevenSegment.compile();
        int orZ = d.taps().getFirst().z() - 1;
        long lineRepeaters = d.module().cells().stream()
                .filter(c -> c.block().id().equals("repeater") && c.pos().z() > orZ).count();
        assertTrue(lineRepeaters <= 60, "Verstärker auf den Leitungen: " + lineRepeaters);
    }

    private static String segments(int v) {
        StringBuilder sb = new StringBuilder();
        for (char c : "abcdefg".toCharArray()) {
            if (SevenSegment.lit(v, c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
