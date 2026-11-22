package com.blackn0va.baumeister.module;

/**
 * Wann modul_json an den Host geht (Vertrag v012: „bei Änderung und höchstens alle 60 s“).
 *
 * Regeln: nie zwei Sendungen gleichzeitig; zwischen zwei Sendungen mindestens MIN_GAP_NANOS; gesendet wird nur, was
 * sich gegenüber dem zuletzt ZUGESTELLTEN Stand geändert hat – eine gescheiterte Sendung zählt nicht als zugestellt
 * und geht nach MIN_GAP_NANOS erneut (der Host startet oft nach dem Server). Unverändert geht der Stand höchstens alle
 * REFRESH_NANOS noch einmal: Ein neu gestarteter Host weiß sonst bis zur nächsten Änderung nichts, und das Plugin
 * merkt einen Neustart der App nicht (der POST gelingt ja). Wer REFRESH entfernt, bekommt nach jedem App-Neustart eine
 * leere Modulseite, bis zufällig ein Zustand wechselt.
 *
 * Threads: next() aus dem Hauptthread, done() aus dem HttpClient-Pool – deshalb synchronisiert. Fehler: keine.
 */
public final class SendGate {

    public static final long MIN_GAP_NANOS = 60_000_000_000L;
    public static final long REFRESH_NANOS = 10 * 60_000_000_000L;

    private String delivered;
    private long lastSendAt = Long.MIN_VALUE / 2;
    private boolean inFlight;

    /** Zu sendender Stand oder null. Gibt next() etwas zurück, muss genau ein done() folgen. */
    public synchronized String next(String current, long nowNanos) {
        if (inFlight || current == null || nowNanos - lastSendAt < MIN_GAP_NANOS) {
            return null;
        }
        if (current.equals(delivered) && nowNanos - lastSendAt < REFRESH_NANOS) {
            return null;
        }
        inFlight = true;
        lastSendAt = nowNanos;
        return current;
    }

    /** Ergebnis der Sendung von sent. */
    public synchronized void done(String sent, boolean ok) {
        inFlight = false;
        if (ok) {
            delivered = sent;
        }
    }

    public synchronized boolean inFlight() {
        return inFlight;
    }
}
