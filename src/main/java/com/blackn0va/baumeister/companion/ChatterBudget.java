package com.blackn0va.baumeister.companion;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wie oft Vera von sich aus etwas sagt – ein gemeinsames Maß für alle Anlässe, wie bei einem Menschen, der nicht zu
 * jeder Kleinigkeit etwas sagt.
 *
 * Vorher hatte jeder Anlass nur seine eigene Sperrzeit (Sturz 90 s, Diamant 60 s, Hunger 5 min, Langeweile 4 min …)
 * und Spielmodus-Wechsel und Wiederkommen gar keine. Beim Testen (ständig /gamemode, neu verbinden, Diamanten,
 * Fallen) kam so Satz auf Satz – Rückmeldung 2026-11-15: „der bot spricht Dinge aus, die wirklich nervig sind und
 * unnötig, das muss natürlicher wirken“. Jetzt:
 * - NORMAL (Plaudern, Kommentare): frühestens GAP_NORMAL nach dem letzten ungefragten Satz.
 * - URGENT (Tod, kaum noch Leben): frühestens GAP_URGENT danach – wer gleich stirbt, soll es hören, aber nicht im
 *   Sekundentakt.
 * Antworten auf den Spieler zählen nicht hier (sie sind gefragt), setzen aber die Uhr: Wer gerade mit ihr redet, hört
 * danach nicht gleich noch einen Spruch.
 *
 * Wer die Lücken verkürzt oder Anlässe an diesem Maß vorbei sprechen lässt, holt das Dauerreden zurück.
 *
 * Threads: threadsicher (ConcurrentHashMap). Fehler: wirft nicht.
 */
public final class ChatterBudget {

    /** Mindestabstand zwischen zwei ungefragten Sätzen. */
    public static final long GAP_NORMAL = 3 * 60_000L;
    /** Mindestabstand für Dringendes (Tod, kaum Leben). */
    public static final long GAP_URGENT = 20_000L;

    public enum Kind {
        NORMAL, URGENT
    }

    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    /** true = jetzt sprechen (und die Uhr stellen), false = schweigen. */
    public boolean allow(UUID player, Kind kind, long nowMs) {
        Long at = last.get(player);
        long gap = kind == Kind.URGENT ? GAP_URGENT : GAP_NORMAL;
        if (at != null && nowMs - at < gap) {
            return false;
        }
        last.put(player, nowMs);
        return true;
    }

    /** Vera hat gerade geantwortet (gefragt): Die nächste ungefragte Bemerkung wartet wieder die volle Lücke ab. */
    public void spoke(UUID player, long nowMs) {
        last.put(player, nowMs);
    }

    /** Spieler weg: nichts merken. Die Lücke zählt nur, solange er da ist. */
    public void forget(UUID player) {
        last.remove(player);
    }
}
