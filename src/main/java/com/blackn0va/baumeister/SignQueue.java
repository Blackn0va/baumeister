package com.blackn0va.baumeister;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

/**
 * Warteschlange der Schilder, deren Text noch geschrieben werden muss – der reine Kern von {@link SignWriter}, ohne
 * Bukkit testbar.
 *
 * Warum eine eigene Schlange statt Text im Bauschritt: Schildtext ist Blockzustand (Blockentität), BlockData trägt ihn
 * nicht. CityJob setzt nur BlockData, und das soll so bleiben – im heißen Pfad ein getState() je Block kostete bei
 * Millionen Blöcken spürbar. Also schreibt ein eigener, kleiner Takt die Texte nach, sobald das Schild steht: je Lauf
 * höchstens perRun Versuche, reihum (ein noch nicht gebautes Schild hält die anderen nicht auf).
 *
 * Nach close() (Bau zu Ende) bekommt jedes Schild noch grace Nanosekunden; was dann noch nicht steht (Stopp, Lücke im
 * Bau), fällt weg, statt ewig im Takt zu kreisen.
 *
 * Threads: nur Hauptthread. Fehler: keine (das Ergebnis des Schreibers entscheidet).
 */
final class SignQueue<T> {

    enum Result {
        /** Text steht. */
        WRITTEN,
        /** Schild (noch) nicht da oder Chunk nicht geladen – später nochmal. */
        NOT_READY,
        /** Kann nie klappen (unbekannter Block) – verwerfen. */
        DROP
    }

    interface Writer<T> {
        Result tryWrite(T sign);
    }

    private final Deque<T> pending = new ArrayDeque<>();
    private final int perRun;
    private final long graceNanos;
    private long closedAt = Long.MIN_VALUE;
    private long written;
    private long dropped;

    SignQueue(int perRun, long graceNanos) {
        this.perRun = perRun;
        this.graceNanos = graceNanos;
    }

    void add(Collection<T> signs) {
        pending.addAll(signs);
    }

    /** Bau zu Ende: ab now läuft die Gnadenfrist. */
    void close(long now) {
        if (closedAt == Long.MIN_VALUE) {
            closedAt = now;
        }
    }

    boolean closed() {
        return closedAt != Long.MIN_VALUE;
    }

    /** Ein Lauf: höchstens perRun Schilder versuchen. */
    void run(Writer<T> writer, long now) {
        attempt(writer, now, Math.min(perRun, pending.size()));
    }

    /**
     * Jedes wartende Schild genau einmal versuchen, ohne Grenze je Lauf – für das Bauende (SignWriter.close), solange
     * die Chunks noch gehalten werden. Einmalig je Bau, deshalb ohne die Drosselung von run().
     */
    void drain(Writer<T> writer, long now) {
        attempt(writer, now, pending.size());
    }

    private void attempt(Writer<T> writer, long now, int n) {
        boolean expired = closed() && now - closedAt > graceNanos;
        for (int i = 0; i < n; i++) {
            T sign = pending.pollFirst();
            Result r = writer.tryWrite(sign);
            if (r == Result.WRITTEN) {
                written++;
            } else if (r == Result.DROP || expired) {
                dropped++;
            } else {
                pending.addLast(sign);
            }
        }
    }

    int pending() {
        return pending.size();
    }

    long written() {
        return written;
    }

    long dropped() {
        return dropped;
    }
}
