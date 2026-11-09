package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Ausfall des Hosts: eine Zeile, Erinnerung alle 15 min, eine Zeile bei Rückkehr (dev01 2026-11-04 19:26–20:19). */
class OutageLogTest {

    private static final long MINUTE = 60_000_000_000L;

    @Test
    void anHourOfOutageWritesFiveLinesInsteadOfSixty() {
        AtomicLong now = new AtomicLong(1_000);
        OutageLog log = new OutageLog(now::get);
        int lines = 0;
        for (int m = 0; m < 60; m++) {
            now.set(1_000 + m * MINUTE);
            if (log.failure() != OutageLog.UP) {
                lines++;
            }
        }
        assertEquals(4, lines, "Beginn plus Erinnerung bei 15, 30, 45 min");
        now.set(1_000 + 61 * MINUTE);
        assertEquals(61, log.success(), "Rückkehr nennt die Dauer");
        assertEquals(OutageLog.UP, log.success(), "nur einmal");
    }

    @Test
    void theFirstFailureAfterRecoveryIsLoggedAgain() {
        AtomicLong now = new AtomicLong(0);
        OutageLog log = new OutageLog(now::get);
        assertEquals(OutageLog.UP, log.success(), "ohne Ausfall keine Zeile");
        assertEquals(0, log.failure());
        assertEquals(OutageLog.UP, log.failure());
        now.set(2 * MINUTE);
        assertEquals(2, log.success());
        assertEquals(0, log.failure(), "neuer Ausfall sofort sichtbar");
        now.set(17 * MINUTE + 1);
        assertEquals(15, log.failure(), "Erinnerung mit Dauer seit Beginn");
    }
}
