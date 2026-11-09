package com.blackn0va.baumeister.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Tagesaufgaben: gleiche Aufgaben für alle, Punkte genau einmal, Serie über Tage, Stand übersteht Neustarts. */
class QuestBookTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 5);
    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();

    /** Erfüllt alle Aufgaben des Tags für p. */
    static QuestBook.Gain finishAll(QuestBook b, UUID p) {
        QuestBook.Gain last = QuestBook.Gain.NONE;
        for (DailyQuests.Quest q : b.quests()) {
            String key = q.keys().isEmpty() ? "" : q.keys().iterator().next();
            last = b.add(p, "p", q.kind(), key, q.amount());
        }
        return last;
    }

    @Test
    void sameDaySameQuestsAndOneOfEachKind() {
        assertEquals(DailyQuests.forDay(DAY), DailyQuests.forDay(DAY));
        List<DailyQuests.Quest> q = DailyQuests.forDay(DAY);
        assertEquals(DailyQuests.Kind.MINE, q.get(0).kind());
        assertEquals(DailyQuests.Kind.KILL, q.get(1).kind());
        assertTrue(Set.of(DailyQuests.Kind.FISH, DailyQuests.Kind.HARVEST).contains(q.get(2).kind()));
        // Über einen Monat nicht jeden Tag dasselbe.
        long distinct = java.util.stream.IntStream.range(0, 30).mapToObj(i -> DailyQuests.forDay(DAY.plusDays(i)))
                .distinct().count();
        assertTrue(distinct > 10, "verschieden: " + distinct);
    }

    @Test
    void pointsArePaidExactlyOnce() {
        QuestBook b = new QuestBook(DAY);
        DailyQuests.Quest mine = b.quests().get(0);
        String key = mine.keys().iterator().next();
        assertEquals(QuestBook.Gain.NONE, b.add(A, "a", DailyQuests.Kind.MINE, key, mine.amount() - 1));
        QuestBook.Gain g = b.add(A, "a", DailyQuests.Kind.MINE, key, 5);
        assertEquals(List.of(0), g.finished());
        assertEquals(mine.points(), g.points());
        assertEquals(QuestBook.Gain.NONE, b.add(A, "a", DailyQuests.Kind.MINE, key, 500), "kein zweites Mal");
        assertEquals(mine.points(), b.points(A));
        assertEquals(QuestBook.Gain.NONE, b.add(A, "a", DailyQuests.Kind.MINE, "dirt_unbekannt", 500));
    }

    @Test
    void allThreeGiveTheBonusAndStartAStreak() {
        QuestBook b = new QuestBook(DAY);
        QuestBook.Gain g = finishAll(b, A);
        assertTrue(g.allDone());
        int sum = b.quests().stream().mapToInt(DailyQuests.Quest::points).sum() + DailyQuests.ALL_DONE_BONUS;
        assertEquals(sum, b.points(A));
        assertEquals(1, b.streak(A));
    }

    @Test
    void streakGrowsOnConsecutiveDaysAndBreaksOnAGap() {
        QuestBook b = new QuestBook(DAY);
        finishAll(b, A);
        assertTrue(b.roll(DAY.plusDays(1)));
        assertFalse(b.roll(DAY.plusDays(1)));
        assertEquals(0, b.progress(A, 0), "neuer Tag, neuer Fortschritt");
        finishAll(b, A);
        assertEquals(2, b.streak(A));
        b.roll(DAY.plusDays(3));
        assertEquals(0, b.streak(A), "ein Tag Pause beendet die Serie");
        finishAll(b, A);
        assertEquals(1, b.streak(A));
    }

    @Test
    void rankingAndRestartKeepEverything() {
        QuestBook b = new QuestBook(DAY);
        finishAll(b, A);
        DailyQuests.Quest k = b.quests().get(1);
        b.add(B, "b", k.kind(), k.keys().iterator().next(), 1);
        QuestBook back = QuestBook.fromJson(b.toJson());
        assertEquals(b.points(A), back.points(A));
        assertEquals(1, back.progress(B, 1));
        assertTrue(back.done(A, 2));
        assertEquals(A, back.ranking().get(0).player());
        assertNotEquals(0, back.ranking().get(0).points());
        assertThrows(IllegalArgumentException.class, () -> QuestBook.fromJson("{kaputt"));
    }
}
