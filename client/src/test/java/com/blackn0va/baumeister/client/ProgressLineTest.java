package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Laufende Fortschrittsanzeige in der Aktionsleiste; Bau- und Restzeit ohne Pausen, je Durchgang (2026-11-01). */
class ProgressLineTest {

    @Test
    void showsCountPercentAndRemainingTime() {
        // 200 von 800 in 50 s → 4 je Sekunde → 600 übrig = 150 s = 2:30.
        assertEquals("§6Baumeister §fbaut 200/800 · 25 % · noch ~2:30",
                ClientBuild.progressLine(200, 800, 50, null));
    }

    @Test
    void noRemainingTimeWhileTheRateIsStillUnreliable() {
        assertFalse(ClientBuild.progressLine(5, 800, 10, null).contains("noch"), "unter 10 Zielen");
        assertFalse(ClientBuild.progressLine(50, 800, 2, null).contains("noch"), "unter 3 s");
    }

    @Test
    void pausedNamesTheReasonAndHowToContinue() {
        String line = ClientBuild.progressLine(40, 100, 20, "Maus bewegt");
        assertTrue(line.contains("pausiert 40/100 · 40 % · Maus bewegt · [K] weiter"), line);
        assertFalse(line.contains("noch"));
    }

    @Test
    void emptyPlanCountsAsDone() {
        assertTrue(ClientBuild.progressLine(0, 0, 0, null).contains("100 %"));
    }

    // ------------------------------------------------ Uhr: Pausen und Nachbesserungsdurchgang

    private static final long S = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong();

    private ClientBuild build() {
        return new ClientBuild("bau ein Haus", new ArrayList<>(), false, 0, new Object(), now::get);
    }

    @Test
    void pauseDoesNotCountAsBuildTime() {
        ClientBuild b = build();
        now.set(10 * S);
        b.pause("Maus bewegt");
        now.set(310 * S);
        b.resume();
        now.set(320 * S);
        assertEquals(20.0, b.activeSeconds(), 1e-9);
        // 100 von 1000 in 20 aktiven Sekunden → 900 übrig = 180 s. Mit der Zeit seit Anlage (320 s) wären es 48:00.
        assertTrue(ClientBuild.progressLine(100, 1000, b.activeSeconds(), null).contains("noch ~3:00"));
    }

    @Test
    void runningPauseIsNotCountedEither() {
        ClientBuild b = build();
        now.set(10 * S);
        b.pause("angehalten");
        now.set(70 * S);
        assertEquals(10.0, b.activeSeconds(), 1e-9);
    }

    @Test
    void secondPassMeasuresItsOwnRate() {
        ClientBuild b = build();
        now.set(100 * S);
        b.startPass();
        now.set(105 * S);
        assertEquals(5.0, b.passSeconds(), 1e-9);
        // 10 von 50 Stellen in 5 s → 40 übrig = 20 s. Mit der Gesamtzeit (105 s) wären es ~7:00.
        assertTrue(ClientBuild.progressLine(10, 50, b.passSeconds(), null).contains("noch ~0:20"));
        assertEquals(105.0, b.activeSeconds(), 1e-9, "Schlussmeldung: ganze aktive Bauzeit");
    }

    @Test
    void resumeAfterTheLastTargetGoesBackToVerifying() {
        ClientBuild b = build();  // leere Zielliste: current() == null
        b.pause("angehalten");
        b.resume();
        assertEquals(ClientBuild.State.VERIFYING, b.state);
    }
}
