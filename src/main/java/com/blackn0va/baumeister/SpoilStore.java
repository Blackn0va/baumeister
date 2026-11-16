package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import org.bukkit.Location;

/**
 * Abraum eines Grabauftrags (Wunsch 2026-11-16: „alles, was er beim Tunnelbau abbaut, stellt er immer in einer vollen
 * Kiste ab – mach das intelligent, nicht nur für den Admin“). Was beim Graben anfällt, sammelt sich hier je Sorte;
 * sobald es ein volles Fass ergibt ({@link #BARREL_SLOTS} Stapel), stellt TunnelService eins in eine Nische der
 * Tunnelwand, am Ende kommt der Rest ins letzte. Vorher landete es nur in einer Materialkiste neben dem Spieler – voll,
 * fiel es daneben heraus, und ohne Kiste (Admin) war es weg. Ein Speicher für alle Stücke eines Auftrags.
 *
 * Fässer statt Truhen: Eine Truhe in der Wand klemmt, weil über ihr Wandstein liegt; ein Fass öffnet zur Seite.
 *
 * Sorte K: auf dem Server Material, im Test ein Name – Bukkits Material lässt sich ohne Server nicht laden.
 *
 * Threads: Hauptthread. Fehler: keine.
 */
final class SpoilStore<K> {

    /** Plätze eines Fasses (wie einer Truhe). */
    static final int BARREL_SLOTS = 27;

    private final ToIntFunction<K> maxStack;
    private final Map<K, Integer> counts = new LinkedHashMap<>();
    /** So viele Fässer stehen schon. */
    int placed;
    /** Wo das letzte steht (für die Schlussansage). */
    Location last;
    /** „Fässer in der Wand“ einmal gesagt. */
    boolean told;
    /** „keine Fässer in der Kiste“ einmal gesagt. */
    boolean noBarrelSaid;

    SpoilStore(ToIntFunction<K> maxStack) {
        this.maxStack = maxStack;
    }

    void add(K key, int amount) {
        if (key != null && amount > 0) {
            counts.merge(key, amount, Integer::sum);
        }
    }

    boolean isEmpty() {
        return counts.isEmpty();
    }

    /** Stapel, die das Gesammelte belegt. */
    int slots() {
        int n = 0;
        for (Map.Entry<K, Integer> e : counts.entrySet()) {
            n += Math.ceilDiv(e.getValue(), Math.max(1, maxStack.applyAsInt(e.getKey())));
        }
        return n;
    }

    /**
     * Höchstens slots Stapel herausnehmen, Sorte für Sorte in der Reihenfolge des Anfalls und je Sorte volle Stapel
     * zuerst – so liegt im Fass zusammen, was zusammen anfiel.
     */
    List<Map.Entry<K, Integer>> take(int slots) {
        List<Map.Entry<K, Integer>> out = new ArrayList<>();
        Iterator<Map.Entry<K, Integer>> it = counts.entrySet().iterator();
        while (it.hasNext() && out.size() < slots) {
            Map.Entry<K, Integer> e = it.next();
            int max = Math.max(1, maxStack.applyAsInt(e.getKey()));
            int left = e.getValue();
            while (left > 0 && out.size() < slots) {
                int n = Math.min(left, max);
                out.add(Map.entry(e.getKey(), n));
                left -= n;
            }
            if (left == 0) {
                it.remove();
            } else {
                e.setValue(left);
            }
        }
        return out;
    }
}
