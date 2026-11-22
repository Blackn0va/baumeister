package com.blackn0va.baumeister.town.presence;

import java.util.function.LongUnaryOperator;

/**
 * Wann ein Bewohner den Arm schwingt und wann er schleicht – reine Rechnung, ohne Bukkit testbar.
 *
 * Vorher (bis 2026-11-22) schwang TownLife den Arm bei der Arbeit nur, solange KEIN Spieler näher als 8 Blöcke stand
 * (dann schaute der Bewohner ihn an) – wer neben der Bäckerin stand, sah sie nie arbeiten. Jetzt schwingt jeder
 * arbeitende Bewohner alle 1,2–3 s, egal wer zusieht, wie ein Spieler beim Abbauen.
 *
 * Schleichen: Ein stehender Bewohner (nicht bei der Arbeit, nicht im Gespräch) duckt sich ab und zu 1–3 s, wie Spieler
 * es zum Spaß oder zum Grüßen tun; frühestens 10 s nach dem Erscheinen, danach alle 20–60 s. Beim Laufen, Reden oder
 * Arbeiten steht er sofort wieder auf – schleichend laufen sähe mit dem Lauftempo der Bewohner (3,2 Blöcke/s, schneller
 * als Schleichen mit 1,3) falsch aus.
 *
 * Springen über Stufen macht schon die Laufrechnung (CompanionWalk.JUMP, volle Blöcke; Stufen bis 0,6 nimmt die Physik
 * von selbst) – hier nicht doppelt.
 *
 * Threads: Hauptthread (ein State je Bewohner). Fehler: keine.
 */
public final class Gestures {

    /** Was der Bewohner in diesem Tick tut (TownLife.stepResident). */
    public enum Activity {
        WALK, WORK, IDLE, TALK
    }

    static final long SWING_MIN_MS = 1_200;
    static final long SWING_SPAN_MS = 1_800;
    static final long SNEAK_FIRST_MIN_MS = 10_000;
    static final long SNEAK_FIRST_SPAN_MS = 30_000;
    static final long SNEAK_GAP_MIN_MS = 20_000;
    static final long SNEAK_GAP_SPAN_MS = 40_000;
    static final long SNEAK_MIN_MS = 1_000;
    static final long SNEAK_SPAN_MS = 2_000;

    /** Je Bewohner. */
    public static final class State {
        long nextSwing;
        long nextSneak = Long.MIN_VALUE;
        long sneakUntil;
        boolean sneaking;

        public boolean sneaking() {
            return sneaking;
        }
    }

    /** Ergebnis eines Ticks: Arm schwingen? Pose wechseln (null = bleibt)? */
    public record Move(boolean swing, Boolean sneak) {
        static final Move NONE = new Move(false, null);
    }

    private Gestures() {
    }

    /** rnd(n) liefert eine Zahl in [0, n) – im Betrieb ThreadLocalRandom, im Test fest. */
    public static Move next(State s, Activity a, long now, LongUnaryOperator rnd) {
        if (s.nextSneak == Long.MIN_VALUE) {
            s.nextSneak = now + SNEAK_FIRST_MIN_MS + rnd.applyAsLong(SNEAK_FIRST_SPAN_MS);
        }
        Boolean sneak = null;
        if (a != Activity.IDLE && s.sneaking) {
            s.sneaking = false;
            sneak = Boolean.FALSE;
        } else if (a == Activity.IDLE) {
            if (s.sneaking && now >= s.sneakUntil) {
                s.sneaking = false;
                s.nextSneak = now + SNEAK_GAP_MIN_MS + rnd.applyAsLong(SNEAK_GAP_SPAN_MS);
                sneak = Boolean.FALSE;
            } else if (!s.sneaking && now >= s.nextSneak) {
                s.sneaking = true;
                s.sneakUntil = now + SNEAK_MIN_MS + rnd.applyAsLong(SNEAK_SPAN_MS);
                sneak = Boolean.TRUE;
            }
        }
        boolean swing = false;
        if (a == Activity.WORK && now >= s.nextSwing) {
            swing = true;
            s.nextSwing = now + SWING_MIN_MS + rnd.applyAsLong(SWING_SPAN_MS);
        }
        return swing || sneak != null ? new Move(swing, sneak) : Move.NONE;
    }
}
