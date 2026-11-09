package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Takt der Stimme: Vorlauf, Echtzeit, Nachholen nach Pausen, Neubeginn nach langer Lücke. */
class AudioPacingTest {

    private static final long F = AudioPacing.FRAME_NS;

    /** Sendet wie der Sende-Faden: wartet bis due, sendet. Liefert die Sendezeit. */
    private static long send(AudioPacing p, long now) {
        long due = p.due(now);
        long at = Math.max(now, due);
        p.sent();
        return at;
    }

    @Test
    void firstFramesGoOutAtOnceThenRealTime() {
        AudioPacing p = new AudioPacing();
        long t0 = 1_000_000_000L;
        for (int i = 0; i < AudioPacing.PREROLL; i++) {
            assertEquals(t0, send(p, t0), "Vorlauf-Rahmen " + i + " sofort");
        }
        assertEquals(t0, send(p, t0), "Rahmen 10 fällig zum Start");
        assertEquals(t0, p.started(), "Vorlauf zählt ab hier als pünktlich");
        assertEquals(t0 + F, send(p, t0), "danach alle 20 ms");
        assertEquals(t0 + 2 * F, send(p, t0 + F));
    }

    @Test
    void afterAPauseOverdueFramesAreSentAtOnceAndThePlanHolds() {
        AudioPacing p = new AudioPacing();
        long t = 0;
        for (int i = 0; i < 40; i++) {
            t = send(p, t);
        }
        // Speicherpause auf dev01: 132 ms nichts (gemessen 2026-11-07).
        long resume = t + 132_000_000L;
        int burst = 0;
        long at = resume;
        while ((at = send(p, resume)) <= resume) {
            burst++;
        }
        assertTrue(burst >= 6 && burst <= 8, "überfällige Rahmen sofort: " + burst);
        assertFalse(p.restarted(), "kein neuer Strom – das Polster beim Client reichte");
        assertEquals(t + 7 * F, at, "Plan läuft weiter, ohne Verschiebung");
    }

    @Test
    void longGapStartsANewStreamWithPreroll() {
        AudioPacing p = new AudioPacing();
        long t = 0;
        for (int i = 0; i < 30; i++) {
            t = send(p, t);
        }
        long later = t + AudioPacing.MAX_BEHIND + 50_000_000L;
        p.due(later);
        assertTrue(p.restarted(), "Polster beim Client leer: neu mit Vorlauf");
    }

    @Test
    void stopEndsTheStreamAndAheadShowsTheCushion() {
        AudioPacing p = new AudioPacing();
        long t0 = 0;
        for (int i = 0; i < 12; i++) {
            send(p, t0);
        }
        assertEquals((2 + 5) * F, p.ahead(t0), "zwei Rahmen vor Plan plus Polster des Clients");
        p.stop();
        assertEquals(0, p.ahead(t0));
        p.due(t0 + F);
        assertTrue(p.restarted());
    }

    @Test
    void prerollStaysWellBelowTheClientsThirtyTwoBuffers() {
        assertTrue(AudioPacing.PREROLL + AudioPacing.CLIENT_CUSHION < 32 - 8, "Abstand zu „Full playback buffer“");
    }
}
