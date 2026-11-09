package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Witze ohne Wiederholung und die Rangliste der Abenteuer (Wunsch 2026-11-04). */
class JokesAndRankingTest {

    @Test
    void noJokeRepeatsBeforeAllWereTold() {
        Jokes j = new Jokes(new Random(7));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Jokes.ALL.size(); i++) {
            assertEquals(true, seen.add(j.next()), "Wiederholung nach " + i);
        }
        assertEquals(Set.copyOf(Jokes.ALL), seen);
        // Danach neu gemischt, wieder vollständig.
        Set<String> again = new HashSet<>();
        for (int i = 0; i < Jokes.ALL.size(); i++) {
            again.add(j.next());
        }
        assertEquals(seen, again);
    }

    @Test
    void jokesUseDuNeverSie() {
        for (String joke : Jokes.ALL) {
            assertFalse(joke.matches(".*\\b(Sie|Ihnen|Ihr|Ihre)\\b.*"), joke);
        }
    }

    @Test
    void theRankingPutsTheFurthestFirstAndCountsHardcoreBests() {
        AdventureBook book = new AdventureBook();
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        UUID c = new UUID(0, 3);
        book.start(a, Adventure.Mode.NORMAL, 0, Set.of("holz"));
        book.start(b, Adventure.Mode.HARDCORE, 0, Set.of("holz", "holzspitzhacke", "stein", "bett", "essen"));
        book.died(b);  // Hardcore vorbei, Bestleistung 5
        book.start(c, Adventure.Mode.NORMAL, 0, Set.of("eisen"));  // Sprung auf Etappe 6
        List<AdventureBook.Rank> r = book.ranking(10);
        assertEquals(List.of(c, b, a), r.stream().map(AdventureBook.Rank::player).toList());
        assertEquals(5, r.get(1).score());
        assertEquals(-1, r.get(1).stage(), "Lauf von b ist vorbei");
        assertEquals(2, book.ranking(2).size());
    }
}
