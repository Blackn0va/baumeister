package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Drossel des Weltbaus gegen eine gestellte Uhr: eine Abfrage zur Zeit, Abstand ab dem Ende der letzten. */
class ThrottleTest {

    private static final long GAP = 20_000;

    @Test
    void firstQueryMayStartAtOnce() {
        Throttle t = new Throttle(new AtomicLong(5)::get, GAP);
        assertTrue(t.ready());
        assertEquals(0, t.waitMillis());
    }

    @Test
    void neverTwoQueriesAtTheSameTime() {
        AtomicLong now = new AtomicLong();
        Throttle t = new Throttle(now::get, GAP);
        t.begin();
        assertTrue(t.inFlight());
        now.addAndGet(10 * 60_000);  // auch nach zehn Minuten ohne Antwort keine zweite
        assertFalse(t.ready());
        assertEquals(-1, t.waitMillis());
        assertThrows(IllegalStateException.class, t::begin);
    }

    @Test
    void gapCountsFromTheEndOfTheLastQuery() {
        AtomicLong now = new AtomicLong(1_000);
        Throttle t = new Throttle(now::get, GAP);
        t.begin();
        now.set(141_000);  // Abfrage lief 140 s (Overpass-Zeitlimit)
        t.end();
        assertFalse(t.ready(), "direkt nach einer langen Abfrage schon die nächste");
        assertEquals(GAP, t.waitMillis());
        now.set(141_000 + GAP - 1);
        assertFalse(t.ready());
        assertEquals(1, t.waitMillis());
        now.set(141_000 + GAP);
        assertTrue(t.ready());
        t.begin();
        assertTrue(t.inFlight());
    }

    @Test
    void failedQueryAlsoWaits() {
        AtomicLong now = new AtomicLong();
        Throttle t = new Throttle(now::get, GAP);
        t.begin();
        now.addAndGet(300);  // „502“ nach 0,3 s – der nächste Dienst kommt trotzdem erst nach dem Abstand dran
        t.end();
        now.addAndGet(GAP - 1);
        assertFalse(t.ready());
        now.addAndGet(1);
        assertTrue(t.ready());
    }

    @Test
    void queryThatThrowsBeforeItStartsReleasesTheThrottle() {
        // Prüfbefund 2026-10-28: Overpass-Adresse ohne Schema → HttpRequest wirft synchron („URI with undefined
        // scheme“). Vorher blieb die Drossel danach für immer belegt: kein Weltbau mehr, /bm stadt dauerhaft gesperrt.
        AtomicLong now = new AtomicLong(1_000);
        Throttle t = new Throttle(now::get, GAP);
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> t.start(() -> {
            throw new IllegalArgumentException("URI with undefined scheme");
        }));
        assertEquals("URI with undefined scheme", thrown.getMessage());
        assertFalse(t.inFlight(), "Drossel blieb nach synchronem Wurf belegt");
        now.addAndGet(GAP);
        assertTrue(t.ready());
        // Eine gestartete Abfrage dagegen bleibt belegt, bis der Aufrufer end() ruft.
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertSame(pending, t.start(() -> pending));
        assertTrue(t.inFlight());
        t.end();
        assertFalse(t.inFlight());
    }

    @Test
    void newGapAppliesFromTheNextEnd() {
        AtomicLong now = new AtomicLong();
        Throttle t = new Throttle(now::get, GAP);
        t.begin();
        t.end();
        t.gap(60_000);
        now.set(GAP);
        assertTrue(t.ready(), "laufende Wartezeit bleibt bei der alten Einstellung");
        t.begin();
        t.end();
        now.set(GAP + 59_999);
        assertFalse(t.ready());
        now.set(GAP + 60_000);
        assertTrue(t.ready());
    }

    /**
     * Vorfall 2026-10-31: Das Vorausladen im Weltbau startete Abfragen, gab die Drossel aber nie frei – nach einer
     * fehlgeschlagenen Abfrage (HTTP 502) blieb der Weltbau für immer stehen. startReleasing gibt sie über den
     * Hauptthread frei, auch bei Fehler; vorher nicht (erst wenn der Hauptthread die Aufgabe ausführt).
     */
    @Test
    void releasingQueryFreesTheThrottleEvenWhenTheServiceFails() {
        AtomicLong now = new AtomicLong();
        Throttle t = new Throttle(now::get, GAP);
        java.util.List<Runnable> mainThread = new java.util.ArrayList<>();
        CompletableFuture<String> answer = new CompletableFuture<>();
        assertSame(answer, t.startReleasing(() -> answer, mainThread::add));
        assertTrue(t.inFlight());
        answer.completeExceptionally(new java.io.IOException("HTTP 502"));
        assertTrue(t.inFlight(), "frei erst im Hauptthread, nicht im Thread der Antwort");
        mainThread.forEach(Runnable::run);
        assertFalse(t.inFlight());
        now.set(GAP);
        assertTrue(t.ready(), "nach dem Abstand wieder frei");
    }

    @Test
    void releasingQueryFreesTheThrottleAfterSuccess() {
        AtomicLong now = new AtomicLong();
        Throttle t = new Throttle(now::get, GAP);
        java.util.List<Runnable> mainThread = new java.util.ArrayList<>();
        CompletableFuture<String> answer = new CompletableFuture<>();
        t.startReleasing(() -> answer, mainThread::add);
        answer.complete("{}");
        mainThread.forEach(Runnable::run);
        now.set(GAP);
        assertTrue(t.ready());
    }
}
