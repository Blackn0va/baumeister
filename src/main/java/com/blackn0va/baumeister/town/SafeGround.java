package com.blackn0va.baumeister.town;

import com.blackn0va.baumeister.companion.CompanionPath;

/**
 * Wo ein Bewohner hintreten darf (Vertrag v013, AK-42: „meiden Wasser, Lava und Abgründe“).
 *
 * Vera schwimmt (CompanionPath geht durch Wasser), Bewohner nicht: Ein Bewohner, der durch einen Fluss watet, sinkt
 * (Mannequin, gemessen 2026-11-06) und kommt an der Uferkante oft nicht heraus. dry() nimmt der Wegsuche das Wasser.
 * Ohne gefundenen Weg läuft ein Bewohner geradeaus; dann prüft safeAhead den nächsten Schritt, damit er nicht in einen
 * See, in Lava oder über eine Kante tiefer als CompanionPath.MAX_DROP (3) läuft.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class SafeGround {

    /** Tiefer als so viele Blöcke geht er nie hinunter (wie CompanionPath.MAX_DROP). */
    public static final int MAX_DROP = 3;

    private SafeGround() {
    }

    /** Die Welt für die Wegsuche ohne Wasser: nicht passierbar, kein Grund zum Stehen. */
    public static CompanionPath.Blocks dry(CompanionPath.Blocks b) {
        return new CompanionPath.Blocks() {
            @Override
            public boolean solid(int x, int y, int z) {
                return b.solid(x, y, z);
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return b.passable(x, y, z) && !b.water(x, y, z);
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return b.climbable(x, y, z);
            }

            @Override
            public boolean doorway(int x, int y, int z) {
                return b.doorway(x, y, z);
            }

            @Override
            public boolean stairs(int x, int y, int z) {
                return b.stairs(x, y, z);
            }
        };
    }

    /** Gefahr an dieser Stelle: Wasser, oder weder fest noch passierbar (Lava, Feuer, Magma, Spinnweben). */
    static boolean hazard(CompanionPath.Blocks w, int x, int y, int z) {
        return w.water(x, y, z) || !w.solid(x, y, z) && !w.passable(x, y, z);
    }

    /**
     * Darf er von (x, y, z) einen Schritt in Richtung (vx, vz) tun? raw = Welt MIT Wasser (sonst sähe er es nicht).
     * Nein bei Wasser oder Gefahr unter oder an den Füßen und bei einer Kante tiefer als MAX_DROP. Eine Stufe hoch ist
     * erlaubt (er springt), eine Wand auch – gegen die läuft er nur, das löst Unstuck.
     */
    public static boolean safeAhead(CompanionPath.Blocks raw, double x, double y, double z, double vx, double vz) {
        double h = Math.hypot(vx, vz);
        if (h < 1.0e-6) {
            return true;
        }
        int fx = (int) Math.floor(x + vx / h * 0.9);
        int fz = (int) Math.floor(z + vz / h * 0.9);
        int fy = (int) Math.floor(y + 0.01);
        if (fx == (int) Math.floor(x) && fz == (int) Math.floor(z)) {
            return true;
        }
        if (hazard(raw, fx, fy, fz)) {
            return false;
        }
        if (raw.solid(fx, fy, fz)) {
            // Stufe oder Wand: darüber darf keine Gefahr liegen (Lavasee auf einer Stufe).
            return !hazard(raw, fx, fy + 1, fz);
        }
        // Boden bei fy − d heißt d − 1 Blöcke Sturz; d läuft bis MAX_DROP + 1, also höchstens MAX_DROP tief.
        for (int d = 1; d <= MAX_DROP + 1; d++) {
            int yy = fy - d;
            if (hazard(raw, fx, yy, fz)) {
                return false;
            }
            if (raw.solid(fx, yy, fz)) {
                return true;
            }
        }
        return false;
    }

    /** Ein Stehplatz ohne Gefahr: Füße und Kopf frei und trocken, darunter fest. */
    static boolean standable(CompanionPath.Blocks raw, int x, int y, int z) {
        return raw.passable(x, y, z) && raw.passable(x, y + 1, z) && !raw.water(x, y, z) && !raw.water(x, y + 1, z)
                && raw.solid(x, y - 1, z);
    }

    /**
     * Ausweichpunkt 2 oder 3 Blöcke seitlich zur Laufrichtung (dirX, dirZ), erst links, dann rechts, auf gleicher Höhe
     * oder eine Stufe höher/tiefer; Mitte des Blocks. null, wenn keiner frei ist.
     */
    public static double[] sidestep(CompanionPath.Blocks raw, double x, double y, double z, double dirX, double dirZ,
            boolean leftFirst) {
        double h = Math.hypot(dirX, dirZ);
        if (h < 1.0e-6) {
            dirX = 1;
            dirZ = 0;
            h = 1;
        }
        double px = -dirZ / h;
        double pz = dirX / h;
        int fy = (int) Math.floor(y + 0.01);
        for (int dist = 2; dist <= 3; dist++) {
            for (int side : leftFirst ? new int[] {1, -1} : new int[] {-1, 1}) {
                int sx = (int) Math.floor(x + px * dist * side);
                int sz = (int) Math.floor(z + pz * dist * side);
                for (int dy : new int[] {0, 1, -1}) {
                    if (standable(raw, sx, fy + dy, sz)) {
                        return new double[] {sx + 0.5, fy + dy, sz + 0.5};
                    }
                }
            }
        }
        return null;
    }
}
