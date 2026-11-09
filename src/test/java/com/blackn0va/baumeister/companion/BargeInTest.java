package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Ins Wort fallen: erst nach 400 ms Sprache am Stück, genau einmal je Äußerung, Husten zählt nicht. */
class BargeInTest {

    private static final UUID P = UUID.randomUUID();

    /** Pakete alle 20 ms von t0 bis t0+dauer; liefert, wie oft ausgelöst wurde. */
    private static int speak(BargeIn b, long t0, long duration) {
        int fired = 0;
        for (long t = t0; t <= t0 + duration; t += 20) {
            if (b.feed(P, t)) {
                fired++;
            }
        }
        return fired;
    }

    @Test
    void firesOnceAfterHoldTime() {
        BargeIn b = new BargeIn();
        assertEquals(1, speak(b, 1000, 2000));
    }

    @Test
    void shortNoiseDoesNotFire() {
        BargeIn b = new BargeIn();
        assertEquals(0, speak(b, 1000, 200), "Husten, Klick");
        // Pause dazwischen: zählt neu.
        assertEquals(0, speak(b, 1500, 300));
    }

    @Test
    void newUtteranceFiresAgain() {
        BargeIn b = new BargeIn();
        assertEquals(1, speak(b, 1000, 600));
        assertEquals(1, speak(b, 3000, 600));
    }

    @Test
    void resetForgetsTheRun() {
        BargeIn b = new BargeIn();
        speak(b, 1000, 300);
        b.reset(P);
        assertFalse(b.feed(P, 1320));
        assertTrue(speak(b, 1340, 500) == 1);
    }
}
