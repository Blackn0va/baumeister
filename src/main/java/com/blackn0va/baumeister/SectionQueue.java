package com.blackn0va.baumeister;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Warteschlange der Bauabschnitte eines Stadt- oder Weltbaus, in Planreihenfolge.
 *
 * Warum eine eigene Klasse (Prüfbefund 2026-10-29): Die Reihenfolge des Plans an geteilten Wänden hängt daran, dass
 * CityJob Abschnitte nur über StagePrep.takeNext nimmt und den Rest eines Arbeiters, dessen Helfer wegfällt, VORN
 * mit seinem Rechteck wieder einreiht. Als offene ArrayDeque in CityJob ließ sich beides unbemerkt zurückdrehen
 * (pollFirst, addLast) – die Tests prüften nur takeNext. Jetzt gibt es keinen anderen Weg an die Abschnitte als
 * take(), und das Wiedereinreihen ist hier ohne Server prüfbar (SectionQueueTest). Wer hier ein pollFirst oder ein
 * Einreihen hinten einbaut, macht das Ergebnis mit Helfern wieder vom Takt abhängig.
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
final class SectionQueue<T> {

    private final Deque<T> queue = new ArrayDeque<>();
    private final Function<T, int[]> box;

    /** box: Rechteck {minX, minZ, maxX, maxZ} eines Abschnitts (null = leer, berührt nichts). */
    SectionQueue(Function<T, int[]> box) {
        this.box = box;
    }

    /** Hinten anhängen – in der Reihenfolge des Plans. */
    void add(T section) {
        queue.addLast(section);
    }

    /**
     * Rest eines angefangenen Abschnitts zurückgeben: vorn, weil er im Plan vor allem steht, was noch wartet. Hinten
     * eingereiht dürfte ein späterer Nachbar ihn überholen und die gemeinsame Wand zuerst setzen.
     */
    void putBack(T rest) {
        queue.addFirst(rest);
    }

    /** Nächster Abschnitt, der neben den laufenden (running) beginnen darf, oder null (siehe StagePrep.takeNext). */
    T take(List<int[]> running) {
        return StagePrep.takeNext(queue, box, running);
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    int size() {
        return queue.size();
    }

    /** Kleinster Wert von f über alle wartenden Abschnitte, ifEmpty ohne Abschnitte. */
    int minOf(java.util.function.ToIntFunction<T> f, int ifEmpty) {
        int min = ifEmpty;
        for (T t : queue) {
            min = Math.min(min, f.applyAsInt(t));
        }
        return min;
    }

    void clear() {
        queue.clear();
    }
}
