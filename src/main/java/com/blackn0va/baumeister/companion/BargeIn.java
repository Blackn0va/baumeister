package com.blackn0va.baumeister.companion;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ins Wort fallen: Spricht ein Spieler am Stück mindestens HOLD_MS, während Vera zu ihm spricht, hört sie auf.
 *
 * Warum mit Mindestdauer statt beim ersten Sprachpaket: Simple Voice Chat schickt auch Husten, Klicks und – ohne
 * Kopfhörer – Veras eigene Stimme aus dem Lautsprecher des Spielers zurück. Ein einzelnes Paket (20 ms) hätte sie
 * dauernd abgewürgt. 400 ms am Stück sind ein angefangenes Wort. Ein Paket gilt als „am Stück“, wenn seit dem
 * vorigen höchstens GAP_MS vergangen sind (Simple Voice Chat sendet alle 20 ms, solange gesprochen wird).
 *
 * Threads: feed aus dem Netzwerk-Thread von Simple Voice Chat, reset aus dem Hauptthread – daher ConcurrentHashMap.
 * Fehler: keine.
 */
public final class BargeIn {

    public static final long HOLD_MS = 400;
    static final long GAP_MS = 120;

    private record Run(long start, long last, boolean fired) {
    }

    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    /** Ein Sprachpaket des Spielers. true genau einmal je Äußerung, sobald sie HOLD_MS lang ist. */
    public boolean feed(UUID player, long nowMs) {
        Run r = runs.get(player);
        if (r == null || nowMs - r.last() > GAP_MS) {
            r = new Run(nowMs, nowMs, false);
        } else {
            r = new Run(r.start(), nowMs, r.fired());
        }
        boolean fire = !r.fired() && nowMs - r.start() >= HOLD_MS;
        runs.put(player, fire ? new Run(r.start(), nowMs, true) : r);
        return fire;
    }

    public void reset(UUID player) {
        runs.remove(player);
    }
}
