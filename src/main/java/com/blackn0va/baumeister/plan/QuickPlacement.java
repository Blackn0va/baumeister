package com.blackn0va.baumeister.plan;

import java.util.List;
import java.util.Optional;

/**
 * Freie Stelle für einen Schnellauftrag neben bestehenden Bauwerken: kleinste Verschiebung (dx, dz) in der Ebene, nach
 * der der Grundriss des Baus samt Abstand keinen Grundriss eines erkannten Bauwerks mehr schneidet und nicht auf dem
 * Spieler steht.
 *
 * Vorfall dev01 2026-11-04 20:22: „bau mir Fort Knox“ direkt neben dem eben gebauten Haus. Der Schnellauftrag stand an
 * seiner festen Stelle vor dem Spieler, hätte 508 Blöcke des Hauses verändert und wurde verworfen; das 14B-Modell
 * schaffte es in zwei Runden auch nicht (13 Blöcke Überbau), Fort Knox kam nie. Ein Schnellauftrag ist ein fertiger Plan
 * ohne Bezug zum Bestand – ihn zur Seite zu rücken ändert nichts an ihm. Danach prüft BuildService den verschobenen
 * Plan noch einmal mit demselben Überbau-Schutz (Zellen, nicht nur Kästen); dieser Rechner ersetzt die Prüfung nicht.
 *
 * Threads: zustandslos. Fehler: keine – ohne freie Stelle im Suchbereich leer (dann bleibt es beim LLM).
 */
public final class QuickPlacement {

    /** Abstand zwischen Bau und Bestand, damit Wände nicht verschmelzen und ein Weg dazwischen bleibt. */
    static final int MARGIN = 2;
    /** Weiter rückt ein Schnellauftrag nicht – sonst stünde er außer Sicht und außerhalb des Scans. */
    static final int MAX_SHIFT = 40;

    private QuickPlacement() {
    }

    /**
     * @param min/max  Ausdehnung des Baus in Plankoordinaten
     * @param playerZ  z der Füße des Spielers (x = 0); der Bau darf ihn nicht einschließen
     * @return Verschiebung {dx, dz}, oder leer
     */
    public static Optional<int[]> shift(List<SiteSurvey.Structure> structures, Pos min, Pos max, int playerZ) {
        if (structures.isEmpty()) {
            return Optional.of(new int[] {0, 0});
        }
        // Nach Entfernung aufsteigend (Manhattan); bei Gleichstand zuerst zur Seite (dz klein), dann weiter weg (dz > 0),
        // zuletzt zum Spieler hin – der Bau soll vor ihm bleiben, nicht hinter ihm landen.
        for (int d = 0; d <= 2 * MAX_SHIFT; d++) {
            for (int k = 0; k <= d; k++) {
                int rest = d - k;
                if (rest > MAX_SHIFT || k > MAX_SHIFT) {
                    continue;
                }
                for (int dz : k == 0 ? new int[] {0} : new int[] {k, -k}) {
                    for (int dx : rest == 0 ? new int[] {0} : new int[] {rest, -rest}) {
                        if (free(structures, min, max, dx, dz, playerZ)) {
                            return Optional.of(new int[] {dx, dz});
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** „12 Blöcke weiter rechts und 4 weiter hinten“ – x rechts, z vom Spieler weg (Plankoordinaten). */
    public static String describe(int dx, int dz) {
        String side = dx == 0 ? "" : Math.abs(dx) + " Blöcke weiter " + (dx > 0 ? "rechts" : "links");
        String depth = dz == 0 ? "" : Math.abs(dz) + (side.isEmpty() ? " Blöcke weiter " : " weiter ")
                + (dz > 0 ? "hinten" : "vorn");
        return side.isEmpty() ? depth : depth.isEmpty() ? side : side + " und " + depth;
    }

    public static boolean free(List<SiteSurvey.Structure> structures, Pos min, Pos max, int dx, int dz, int playerZ) {
        int x0 = min.x() + dx;
        int x1 = max.x() + dx;
        int z0 = min.z() + dz;
        int z1 = max.z() + dz;
        // Der Spieler (x 0, z playerZ) samt einem Block drumherum bleibt frei – sonst stünde er im Bau.
        if (x0 <= 1 && x1 >= -1 && z0 <= playerZ + 1 && z1 >= playerZ - 1) {
            return false;
        }
        for (SiteSurvey.Structure s : structures) {
            if (x0 <= s.max().x() + MARGIN && x1 >= s.min().x() - MARGIN && z0 <= s.max().z() + MARGIN
                    && z1 >= s.min().z() - MARGIN) {
                return false;
            }
        }
        return true;
    }
}
