package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Spielmodus-Wechsel: der Ablauf aus dem Log von dev01 (2026-11-05 19:07–20:06), der Wechsel verschluckte. */
class ModeWatchTest {

    private static final UUID P = UUID.fromString("0d7f3aa9-c414-49d1-84e9-171a69f6a430");

    @Test
    void firstLookOnlyRecordsTheMode() {
        ModeWatch w = new ModeWatch();
        // Im Kreativmodus zuerst gesehen: das ist kein Wechsel (vorher: „Pause“ ohne Umschalten).
        assertEquals(ModeWatch.Change.NONE, w.observe(P, false));
        assertEquals(ModeWatch.Change.NONE, w.observe(P, false));
    }

    @Test
    void everySwitchIsReportedOnce() {
        ModeWatch w = new ModeWatch();
        w.observe(P, true);
        assertEquals(ModeWatch.Change.PAUSED, w.observe(P, false));
        assertEquals(ModeWatch.Change.NONE, w.observe(P, false), "Takt danach meldet nicht noch einmal");
        assertEquals(ModeWatch.Change.RESUMED, w.observe(P, true));
        assertEquals(ModeWatch.Change.PAUSED, w.observe(P, false));
    }

    @Test
    void switchBackWhileStartedInCreativeIsNotLost() {
        // 19:07:09 Kreativ (Pause), Abenteuer-Neustart löschte früher den Stand, 19:08:45 Überleben: früher kein
        // „weiter“, weil der neue Stand fest auf „spielt“ stand.
        ModeWatch w = new ModeWatch();
        w.observe(P, true);
        w.observe(P, false);
        assertEquals(ModeWatch.Change.RESUMED, w.observe(P, true));
    }

    @Test
    void forgetStartsFresh() {
        ModeWatch w = new ModeWatch();
        w.observe(P, true);
        w.forget(P);
        assertEquals(ModeWatch.Change.NONE, w.observe(P, false));
    }
}
