package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Team-Abenteuer: gemeinsamer Stand, Fortschritt für alle, Hardcore ein Leben fürs Team, übersteht Neustarts. */
class AdventureTeamTest {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static final UUID C = UUID.randomUUID();

    @Test
    void guestTakesOverTheHostsProgress() {
        AdventureBook book = new AdventureBook();
        book.start(A, Adventure.Mode.NORMAL, 0, Set.of("holz", "holzspitzhacke"));
        assertFalse(book.join(C, B), "ohne Abenteuer kein Team");
        assertTrue(book.join(A, B));
        assertEquals(book.run(A).orElseThrow().stage(), book.run(B).orElseThrow().stage());
        assertEquals(0, book.run(B).orElseThrow().deaths());
        assertEquals(Set.of(A, B), book.team(A));
        assertEquals(Set.of(A, B), book.team(B));
    }

    @Test
    void whatOneAchievesCountsForEveryone() {
        AdventureBook book = new AdventureBook();
        book.start(A, Adventure.Mode.NORMAL, 0, Set.of("holz"));
        book.join(A, B);
        int before = book.run(A).orElseThrow().stage();
        var changes = book.observeTeam(B, Set.of("holzspitzhacke"), 1000);
        assertEquals(Set.of(A, B), changes.keySet());
        assertTrue(book.run(A).orElseThrow().stage() > before, "B hat geschafft, A rückt mit");
        assertEquals(book.run(A).orElseThrow().stage(), book.run(B).orElseThrow().stage());
    }

    @Test
    void hardcoreTeamHasOneLife() {
        AdventureBook book = new AdventureBook();
        book.start(A, Adventure.Mode.HARDCORE, 0, Set.of("holz"));
        book.join(A, B);
        AdventureBook.Death d = book.died(B).orElseThrow();
        assertTrue(d.over());
        assertEquals(List.of(A), d.teammates());
        assertTrue(book.run(A).isEmpty(), "auch A ist raus");
        assertEquals(Set.of(A), book.team(A));
        assertEquals(1, book.best(A), "Bestleistung bleibt für alle");
    }

    @Test
    void normalDeathOnlyCountsForTheOneWhoDied() {
        AdventureBook book = new AdventureBook();
        book.start(A, Adventure.Mode.NORMAL, 0, Set.of());
        book.join(A, B);
        AdventureBook.Death d = book.died(B).orElseThrow();
        assertFalse(d.over());
        assertEquals(List.of(), d.teammates());
        assertEquals(0, book.run(A).orElseThrow().deaths());
        assertEquals(1, book.run(B).orElseThrow().deaths());
    }

    @Test
    void leavingOrStartingAloneEndsTheTeamButKeepsTheRun() {
        AdventureBook book = new AdventureBook();
        book.start(A, Adventure.Mode.NORMAL, 0, Set.of("holz"));
        book.join(A, B);
        book.join(A, C);
        book.stop(C);
        assertEquals(Set.of(A, B), book.team(A));
        book.start(B, Adventure.Mode.NORMAL, 5, Set.of());
        assertEquals(Set.of(A), book.team(A), "allein übrig = kein Team mehr");
        assertTrue(book.run(A).isPresent());
    }

    @Test
    void teamsSurviveARestart() {
        AdventureBook book = new AdventureBook();
        book.start(A, Adventure.Mode.NORMAL, 0, Set.of());
        book.join(A, B);
        AdventureBook back = AdventureBook.fromJson(book.toJson());
        assertEquals(Set.of(A, B), back.team(B));
        back.observeTeam(A, Set.of("holz"), 1);
        assertEquals(back.run(A).orElseThrow().stage(), back.run(B).orElseThrow().stage());
    }
}
