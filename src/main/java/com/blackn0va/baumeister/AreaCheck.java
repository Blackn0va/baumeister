package com.blackn0va.baumeister;

/**
 * Prüft einen Quader Zelle für Zelle, verteilbar auf mehrere Takte, und hört beim ersten Nein auf (fail-closed).
 *
 * Wofür (Plan B6): Veras „merk dir das Bauwerk“ kopierte bisher jeden Bereich, den ein Spieler mit zwei Ecken zeigte –
 * ohne Bauprüfung und samt Blockdaten (Truhen-Inhalte, Befehle) aus fremden Plots. Jetzt fragt sie vorher für jeden
 * Block im Bereich WorldGuard/PlotSquared, ob der Spieler dort abbauen dürfte (BuildJob prüft Bauten genauso). Bis
 * 32 × 32 × 32 Blöcke sind das bis zu 32 768 Fragen – daher in Portionen nach Zeit (TimeSlice) statt in einem Takt.
 *
 * Threads: nur der aufrufende (im Plugin der Hauptthread). Fehler: keine (Ausnahmen der Probe gehen durch).
 */
final class AreaCheck {

    /** true = an dieser Stelle erlaubt. */
    interface Probe {
        boolean allowed(int x, int y, int z);
    }

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final Probe probe;
    private int x;
    private int y;
    private int z;
    private boolean done;
    private boolean denied;
    private int checked;

    AreaCheck(int ax, int ay, int az, int bx, int by, int bz, Probe probe) {
        this.minX = Math.min(ax, bx);
        this.minY = Math.min(ay, by);
        this.minZ = Math.min(az, bz);
        this.maxX = Math.max(ax, bx);
        this.maxY = Math.max(ay, by);
        this.maxZ = Math.max(az, bz);
        this.probe = probe;
        this.x = minX;
        this.y = minY;
        this.z = minZ;
    }

    /** Eine Zelle prüfen; false = fertig (alles erlaubt) oder abgebrochen (verweigert). Für TimeSlice.run. */
    boolean step() {
        if (done) {
            return false;
        }
        checked++;
        if (!probe.allowed(x, y, z)) {
            denied = true;
            done = true;
            return false;
        }
        if (++x > maxX) {
            x = minX;
            if (++z > maxZ) {
                z = minZ;
                if (++y > maxY) {
                    done = true;
                    return false;
                }
            }
        }
        return true;
    }

    boolean done() {
        return done;
    }

    /** Erst nach done() aussagekräftig. */
    boolean allowed() {
        return done && !denied;
    }

    /** Stelle des Neins (nur bei !allowed()). */
    int[] deniedAt() {
        return new int[] {x, y, z};
    }

    int checked() {
        return checked;
    }
}
