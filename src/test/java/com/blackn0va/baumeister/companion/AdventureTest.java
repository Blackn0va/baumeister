package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Abenteuer mit Vera (Wunsch 2026-11-04). */
class AdventureTest {

    @Test
    void theInventoryShowsHowFarThePlayerGot() {
        assertTrue(Adventure.flags(Map.of("oak_log", 5), false, "normal").contains("holz"));
        assertFalse(Adventure.flags(Map.of("oak_log", 4), false, "normal").contains("holz"));
        assertTrue(Adventure.flags(Map.of("white_bed", 1), false, "normal").contains("bett"));
        assertTrue(Adventure.flags(Map.of(), true, "normal").contains("bett"), "Wiedereinstieg am Bett");
        assertTrue(Adventure.flags(Map.of("bread", 3, "cooked_beef", 3), false, "normal").contains("essen"));
        assertTrue(Adventure.flags(Map.of("iron_chestplate", 1), false, "normal").contains("rüstung"));
        assertTrue(Adventure.flags(Map.of("ender_pearl", 8, "ender_eye", 4), false, "normal").contains("perlen"));
        assertTrue(Adventure.flags(Map.of(), false, "nether").contains("nether"));
        assertTrue(Adventure.flags(Map.of(), false, "the_end").contains("end"));
        assertTrue(Adventure.flags(Map.of("dirt", 64), false, "normal").isEmpty());
    }

    @Test
    void stagesOnlyMoveForwardAndJumpToTheFurthestOneReached() {
        assertEquals(0, Adventure.advance(0, Set.of()));
        assertEquals(1, Adventure.advance(0, Set.of("holz")));
        assertEquals(3, Adventure.advance(0, Set.of("holz", "holzspitzhacke", "stein")));
        // Eisenspitzhacke ohne Bett und Essen: weiter bei Rüstung, nicht beim Bett hängen bleiben.
        assertEquals(7, Adventure.advance(1, Set.of("eisenwerkzeug")));
        // Nie zurück: Gegenstände verbraucht, Etappe bleibt.
        assertEquals(5, Adventure.advance(5, Set.of()));
        assertEquals(Adventure.STAGES.size(), Adventure.advance(13, Set.of("drache")));
        assertTrue(Adventure.finished(Adventure.STAGES.size()));
    }

    @Test
    void everyStageHasItsOwnFlagAndText() {
        Set<String> seen = new HashSet<>();
        for (Adventure.Stage s : Adventure.STAGES) {
            assertTrue(seen.add(s.flag()), "doppelt: " + s.flag());
            assertFalse(s.goal().isBlank() || s.hint().isBlank() || s.title().isBlank(), s.flag());
        }
        assertEquals("Etappe 1 von 14: Holz sammeln", Adventure.progress(0));
        assertEquals("alle 14 Etappen geschafft", Adventure.progress(14));
    }

    @Test
    void hardcoreEndsWithTheFirstDeathNormalCarriesOn() {
        AdventureBook book = new AdventureBook();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        book.start(a, Adventure.Mode.HARDCORE, 1_000, Set.of("holz", "holzspitzhacke"));
        book.start(b, Adventure.Mode.NORMAL, 1_000, Set.of());
        assertEquals(2, book.run(a).orElseThrow().stage(), "wer mit Werkzeug startet, ist schon weiter");
        AdventureBook.Death d = book.died(a).orElseThrow();
        assertTrue(d.over() && d.newBest());
        assertTrue(book.run(a).isEmpty());
        assertEquals(2, book.best(a));
        AdventureBook.Death n = book.died(b).orElseThrow();
        assertFalse(n.over());
        assertEquals(1, book.run(b).orElseThrow().deaths());
        assertTrue(book.died(UUID.randomUUID()).isEmpty(), "ohne Abenteuer kein Eintrag");
    }

    @Test
    void observedFlagsStayEvenWhenItemsAreUsedUp() {
        AdventureBook book = new AdventureBook();
        UUID a = UUID.randomUUID();
        book.start(a, Adventure.Mode.NORMAL, 0, Set.of());
        book.observe(a, Set.of("holz"));
        AdventureBook.Run[] r = book.observe(a, Set.of()).orElseThrow();
        assertEquals(1, r[1].stage());
        assertTrue(r[1].flags().contains("holz"));
    }

    @Test
    void theBookSurvivesARestartAndSkipsBrokenEntries() {
        AdventureBook book = new AdventureBook();
        UUID a = UUID.randomUUID();
        book.start(a, Adventure.Mode.HARDCORE, 42, Set.of("holz"));
        UUID dead = UUID.randomUUID();
        book.start(dead, Adventure.Mode.HARDCORE, 0, Set.of("holz", "holzspitzhacke", "stein"));
        book.died(dead);
        AdventureBook back = AdventureBook.fromJson(book.toJson());
        AdventureBook.Run r = back.run(a).orElseThrow();
        assertEquals(1, r.stage());
        assertEquals(Adventure.Mode.HARDCORE, r.mode());
        assertEquals(42, r.startedMillis());
        assertEquals(Set.of("holz"), r.flags());
        assertEquals(3, back.best(dead));
        String broken = "{\"läufe\":{\"" + a + "\":{\"etappe\":2,\"modus\":\"quatsch\",\"tode\":0,\"start\":0},"
                + "\"kein-uuid\":{}},\"bestleistung\":{}}";
        AdventureBook b2 = AdventureBook.fromJson(broken);
        assertTrue(b2.run(a).isEmpty());
        assertEquals(2, b2.skipped());
        assertEquals(0, AdventureBook.fromJson("kein json {").skipped());
    }
}
