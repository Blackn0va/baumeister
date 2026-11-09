package com.blackn0va.baumeister.client;

import java.util.HashMap;
import java.util.Map;

/**
 * Versuche je zurückgestellter Stelle (PlayerPlacer.defer). Ein Versuch zählt erst nach 10 Ticks: Ein gerade gesetzter
 * Nachbar erscheint erst mit der Antwort des Servers. Vorher liefen alle drei Versuche im selben Tick ab, bevor der
 * Trichter daneben sichtbar war, und Truhe und Trichter der Schmelzanlage blieben aus (Spieltest 2026-10-31).
 *
 * Gilt je Bau und Durchgang (needsReset): Vorher lebten die Zähler die ganze Sitzung – eine Stelle, die im ersten
 * Durchgang dreimal zurückgestellt wurde, war im Nachbesserungsdurchgang, beim Rückgängig-Bau und bei einem neuen Bau
 * am selben Platz beim ersten gezählten Versuch (4) sofort ausgelassen; genau den Stellen, für die der zweite Durchgang
 * da ist, half er nicht. Und die Maps wuchsen unbegrenzt. Wer reset() weglässt, hat beides wieder.
 *
 * Threads: nur Client-Thread. Fehler: keine.
 */
final class DeferLedger<K> {

    private final Map<K, Integer> tries = new HashMap<>();
    /** Tick des letzten gezählten Versuchs je Stelle. */
    private final Map<K, Integer> lastTry = new HashMap<>();

    /** Versuch an key im Tick tick vermerken; liefert den Stand danach (gezählt oder nicht). */
    int count(K key, int tick) {
        Integer last = lastTry.get(key);
        boolean counts = last == null || tick - last >= 10;
        if (counts) {
            lastTry.put(key, tick);
            return tries.merge(key, 1, Integer::sum);
        }
        return tries.getOrDefault(key, 0);
    }

    void reset() {
        tries.clear();
        lastTry.clear();
    }

    /** Neuer Bau (andere Instanz) oder neuer Durchgang desselben Baus → Zähler von vorn. */
    static boolean needsReset(Object lastJob, int lastPass, Object job, int pass) {
        return lastJob != job || lastPass != pass;
    }
}
