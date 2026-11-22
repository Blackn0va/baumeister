package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Feste Sätze ohne KI (Vertrag v013, AK-41): Vielfalt gezählt, Lage beachtet, keine Wiederholung, Filter greift. */
class TownLinesTest {

    /** v008: GREET 6 + REPLY 5 + MEET 5 + 12 Arbeitssprüche (Person.workLine). */
    static final int BEFORE = 28;

    private static final Roster.Person LOTTE = Roster.all().getFirst();
    private static final Roster.Person EMIL = Roster.all().get(1);

    @Test
    void atLeastFiveTimesAsManySentencesAsBefore() {
        int now = TownLines.all().size();
        assertTrue(now >= 5 * BEFORE, "nur " + now + " Sätze, verlangt " + 5 * BEFORE);
        System.out.println("feste Sätze: vorher " + BEFORE + ", jetzt " + now);
    }

    /** Kein Horror, kein Schimpfen, kein Befehl, keine Adresse – derselbe Filter wie für KI-Antworten. */
    @Test
    void everyFixedSentencePassesTheFilter() {
        List<String> bad = new ArrayList<>();
        for (String s : TownLines.all()) {
            if (TownTalk.blocked(s) || s.length() > TownTalk.MAX_SAY || s.isBlank()) {
                bad.add(s);
            }
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void everyJobAndCharacterHasItsOwnLines() {
        for (Roster.Person p : Roster.all()) {
            assertTrue(TownLines.WORK_BY_JOB.containsKey(p.job()), p.job());
            assertTrue(TownLines.REPLY_BY_JOB.containsKey(p.job()), p.job());
            assertFalse(TownLines.trait(p.character()).isEmpty(), "kein Charakterzug für " + p.character());
        }
        List<String> lotte = TownLines.candidates(TownLines.Occasion.REPLY, LOTTE, DayPlan.Phase.WORK,
                TownTalk.Weather.CLEAR);
        List<String> emil = TownLines.candidates(TownLines.Occasion.REPLY, EMIL, DayPlan.Phase.WORK,
                TownTalk.Weather.CLEAR);
        assertNotEquals(lotte, emil);
        assertTrue(lotte.contains(LOTTE.workLine()) && !emil.contains(LOTTE.workLine()));
        assertTrue(emil.contains("Hm. Frag mich, wenn das Eisen kalt ist."));
    }

    @Test
    void timeOfDayAndWeatherDecide() {
        List<String> morningRain = TownLines.candidates(TownLines.Occasion.GREET, LOTTE, DayPlan.Phase.MORNING,
                TownTalk.Weather.RAIN);
        List<String> eveningClear = TownLines.candidates(TownLines.Occasion.GREET, LOTTE, DayPlan.Phase.EVENING,
                TownTalk.Weather.CLEAR);
        assertTrue(morningRain.contains("Guten Morgen!") && !eveningClear.contains("Guten Morgen!"));
        assertTrue(morningRain.contains("Ganz schön nass heute!") && !eveningClear.contains("Ganz schön nass heute!"));
        assertTrue(eveningClear.contains("Guten Abend!"));
        // Sonnen-Sätze nicht am Abend.
        assertFalse(eveningClear.contains("Herrlich, die Sonne scheint!"));
        assertTrue(TownLines.candidates(TownLines.Occasion.GREET, LOTTE, DayPlan.Phase.NOON, TownTalk.Weather.CLEAR)
                .contains("Herrlich, die Sonne scheint!"));
    }

    /** Zuletzt Gesagtes ist gesperrt: 30 Grüße in derselben Lage, keiner doppelt. */
    @Test
    void recentSentencesAreNotRepeated() {
        TownLines lines = new TownLines();
        Random r = new Random(4);
        Set<String> seen = new HashSet<>();
        int pool = TownLines.candidates(TownLines.Occasion.GREET, LOTTE, DayPlan.Phase.NOON, TownTalk.Weather.CLEAR)
                .size();
        int n = Math.min(30, pool);
        for (int i = 0; i < n; i++) {
            String s = lines.pick(TownLines.Occasion.GREET, LOTTE, DayPlan.Phase.NOON, TownTalk.Weather.CLEAR,
                    r.nextInt());
            assertTrue(seen.add(s), "wiederholt nach " + i + ": " + s);
        }
        // Alles gesperrt: dann der am längsten nicht gesagte, nie eine Ausnahme.
        TownLines tiny = new TownLines();
        List<String> farewell = TownLines.candidates(TownLines.Occasion.FAREWELL, EMIL, DayPlan.Phase.NOON,
                TownTalk.Weather.CLEAR);
        String first = null;
        for (int i = 0; i < farewell.size(); i++) {
            String s = tiny.pick(TownLines.Occasion.FAREWELL, EMIL, DayPlan.Phase.NOON, TownTalk.Weather.CLEAR, 0);
            if (first == null) {
                first = s;
            }
        }
        assertEquals(first, tiny.pick(TownLines.Occasion.FAREWELL, EMIL, DayPlan.Phase.NOON, TownTalk.Weather.CLEAR,
                0));
    }

    /** Ein KI-Satz sperrt den gleichen festen Satz danach. */
    @Test
    void aiSentencesBlockToo() {
        TownLines lines = new TownLines();
        for (String s : TownLines.candidates(TownLines.Occasion.FAREWELL, LOTTE, DayPlan.Phase.EVENING,
                TownTalk.Weather.CLEAR)) {
            if (!s.equals("Bis morgen!")) {
                lines.said(s);
            }
        }
        assertEquals("Bis morgen!", lines.pick(TownLines.Occasion.FAREWELL, LOTTE, DayPlan.Phase.EVENING,
                TownTalk.Weather.CLEAR, 7));
    }

    @Test
    void occasionFromThePlayerLine() {
        assertEquals(TownLines.Occasion.FAREWELL, TownLines.occasionFor("Tschüss Lotte, bis bald"));
        assertEquals(TownLines.Occasion.FAREWELL, TownLines.occasionFor("gute Nacht!"));
        assertEquals(TownLines.Occasion.GREET, TownLines.occasionFor("Hallo!"));
        assertEquals(TownLines.Occasion.GREET, TownLines.occasionFor("  moin "));
        assertEquals(TownLines.Occasion.REPLY, TownLines.occasionFor("Hallo, was backst du?"));
        assertEquals(TownLines.Occasion.REPLY, TownLines.occasionFor("Wie geht's?"));
    }

    /**
     * Ohne KI über einen Tag: wie viele verschiedene Sätze Lotte in 200 Anlässen sagt. Vorher konnte sie überhaupt nur
     * 17 verschiedene sagen (16 Floskeln + ihr Arbeitsspruch); gemessen 2026-11-22: 121.
     */
    @Test
    void aDayWithoutAiIsVaried() {
        TownLines lines = new TownLines();
        Random r = new Random(11);
        Set<String> said = new HashSet<>();
        TownLines.Occasion[] os = TownLines.Occasion.values();
        DayPlan.Phase[] ps = {DayPlan.Phase.MORNING, DayPlan.Phase.WORK, DayPlan.Phase.NOON, DayPlan.Phase.AFTERNOON,
            DayPlan.Phase.EVENING};
        for (int i = 0; i < 200; i++) {
            said.add(lines.pick(os[i % os.length], LOTTE, ps[(i / 40) % ps.length],
                    i % 3 == 0 ? TownTalk.Weather.RAIN : TownTalk.Weather.CLEAR, r.nextInt()));
        }
        System.out.println("Lotte ohne KI, 200 Anlässe: " + said.size() + " verschiedene Sätze");
        assertTrue(said.size() >= 100, "nur " + said.size() + " verschiedene");
    }
}
