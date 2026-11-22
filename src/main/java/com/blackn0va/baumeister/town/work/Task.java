package com.blackn0va.baumeister.town.work;

import java.util.List;

/**
 * Eine Arbeit im Feld: der Platz, an dem der Bewohner dafür steht, und die Blockänderungen in Reihenfolge (ernten,
 * dann nachpflanzen; Stamm von unten nach oben, dann Setzling).
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public record Task(FieldJob job, int standX, int standY, int standZ, List<Change> changes) {

    public enum Kind {
        /** Abbauen (BlockBreakEvent, dann Luft). */
        BREAK,
        /** Setzen auf expect (Luft, bei Wegen Grasblock); BlockPlaceEvent nach dem Setzen, abgesagt = zurück. */
        PLACE
    }

    /** expect = was jetzt dort stehen muss (sonst hat sich die Welt geändert: verworfen); type = was hin soll. */
    public record Change(Kind kind, int x, int y, int z, String expect, String type) {

        /** Schlüssel des Blocks für Belegung und Sperre (TownLife: zwei Bewohner nie am selben Block). */
        public long key() {
            return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
        }
    }

    public Task {
        changes = List.copyOf(changes);
    }

    /** Schlüssel der Arbeit: der erste Block. */
    public long key() {
        return changes.getFirst().key();
    }
}
