package com.blackn0va.baumeister.relay;

import java.util.logging.Logger;

/**
 * Byte-Budget aller gepufferten Daten (docs/kanal-v1.md, Abschnitt 4): Antwortstücke, die auf das Plugin warten, und
 * Anfrage-Stücke, die auf die App warten. Wer mehr reservieren will, als frei ist, bekommt false; was dann passiert
 * (größte offene Antwort abbrechen), entscheidet der Hub.
 *
 * Jede Reservierung wird genau einmal freigegeben. Ein Unterlauf wäre ein Fehler im Relay – er wird laut geloggt und
 * auf 0 gekappt, damit das Budget nicht dauerhaft wächst; die Tests prüfen nach jedem Lauf used() == 0.
 *
 * Threads: synchronisiert, kurz, ohne Ein-/Ausgabe. Fehler: keine.
 */
final class ByteBudget {

    private static final Logger LOG = RelayLog.LOG;

    private final long limit;
    private long used;
    private long peak;

    ByteBudget(long limit) {
        this.limit = limit;
    }

    synchronized boolean tryReserve(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative Reservierung");
        }
        if (used + n > limit) {
            return false;
        }
        used += n;
        peak = Math.max(peak, used);
        return true;
    }

    void release(long n) {
        if (n == 0) {
            return;
        }
        boolean underflow;
        synchronized (this) {
            used -= n;
            underflow = used < 0;
            if (underflow) {
                used = 0;
            }
        }
        if (underflow) {
            LOG.severe("relay | budget_unterlauf | bytes=" + n + " (doppelte Freigabe – Fehler im Relay)");
        }
    }

    long limit() {
        return limit;
    }

    synchronized long used() {
        return used;
    }

    synchronized long peak() {
        return peak;
    }
}
