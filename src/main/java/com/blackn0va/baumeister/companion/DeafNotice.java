package com.blackn0va.baumeister.companion;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wann Vera sagt, dass sie den Spieler nicht hören kann.
 *
 * Wunsch 2026-11-07. Die Spracherkennung läuft in der
 * Baumeister-App; war sie aus, verwarf das Plugin jeden Satz aus Simple Voice Chat still (Log: host_nicht_erreichbar
 * alle 15 min, im Spiel nichts). Jetzt sagt sie es – beim ersten verlorenen Satz sofort, danach höchstens alle
 * REPEAT_MS, damit Gespräche mit Mitspielern im Voice-Chat sie nicht dauernd auslösen. Kommt wieder ein Satz an,
 * beginnt die Zählung neu: Der nächste Ausfall wird gleich gemeldet.
 *
 * Wer die Meldung wieder streicht, lässt Spieler ins Leere reden, ohne zu wissen warum.
 *
 * Threads: threadsicher (ConcurrentHashMap), Aufrufe kommen aus dem Hauptthread. Fehler: wirft nicht.
 */
public final class DeafNotice {

    static final long REPEAT_MS = 10 * 60_000L;

    private final Map<UUID, Long> told = new ConcurrentHashMap<>();

    /** Ein Satz ging verloren. true = jetzt Bescheid sagen. */
    public boolean failed(UUID player, long nowMs) {
        Long last = told.get(player);
        if (last != null && nowMs - last < REPEAT_MS) {
            return false;
        }
        told.put(player, nowMs);
        return true;
    }

    /** Erkennung läuft wieder (oder Spieler weg): der nächste Ausfall wird sofort gemeldet. */
    public void reset(UUID player) {
        told.remove(player);
    }
}
