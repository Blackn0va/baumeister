package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * Wohin Vera beim Folgen läuft und wohin sie notfalls springt.
 *
 * Vorher (bis 2026-11-07) war das Ziel immer ein Punkt schräg hinter dem Spieler (2–3 Blöcke zurück, 1,2 zur Seite),
 * nur in der Höhe angepasst. Wunsch 2026-11-07. In einem 1 breiten Gang liegt dieser Punkt im Fels oder im Gang
 * daneben: Die Wegsuche findet nichts,
 * Vera läuft gegen die Wand, und nach 40 Ticks teleportierte sie genau dorthin – in den Stein (gemessen in
 * probes/mannequin/FollowProbe).
 *
 * Jetzt:
 * - Spur (Trail): Wo der Spieler gestanden hat, kann sie auch stehen. Die letzten TRAIL_MAX Standpunkte bleiben gemerkt.
 * - Ziel: Der Punkt schräg hinter dem Spieler gilt nur, wenn dort Platz ist und der Weg dorthin frei ist (keine Wand
 *   zwischen Spieler und Punkt). Sonst nimmt sie den Spur-Punkt `distance` Blöcke Weglänge hinter dem Spieler.
 * - Findet die Wegsuche nichts, läuft sie die Spur entlang (ahead): der Weg, den der Spieler selbst gegangen ist.
 * - Teleport nur noch auf einen Spur-Punkt, nie auf ein Ziel im Fels.
 *
 * Wer das Ziel wieder blind schräg hinter den Spieler legt, lässt sie in jedem Gang festlaufen und in die Wand springen.
 *
 * Threads: Trail gehört einem Begleiter und wird nur im Hauptthread benutzt (nicht threadsicher). Fehler: wirft nicht.
 */
/** Eingefroren: CompanionFollow wie in e9c9278 auf dev01 (FollowProbe vergleicht dagegen). */
public final class FollowDeployed {

    /** So viele Standpunkte reichen für rund 40 Blöcke Weg (Abstand je Punkt TRAIL_STEP). */
    public static final int TRAIL_MAX = 48;
    static final double TRAIL_STEP = 0.8;
    /** Größerer Sprung zwischen zwei Standpunkten: Teleport, Enderperle, Welt – die alte Spur führt nicht mehr hin. */
    static final double TRAIL_BREAK = 6.0;
    static final double SIDE = 1.2;
    /** So nah an einem Spur-Punkt (waagrecht) gilt Vera als auf der Spur. */
    static final double ON_TRAIL = 2.5;
    /** Ab hier gilt ein Spur-Punkt als erreicht, dann zielt sie auf den nächsten (wie CompanionPath.REACHED). */
    static final double PASSED = 0.7;

    private FollowDeployed() {
    }

    /** Standpunkte des Spielers, ältester zuerst. */
    public static final class Trail {
        private final List<double[]> points = new ArrayList<>();

        /** Standpunkt merken (nur wenn er auf dem Boden steht oder schwimmt – das prüft der Aufrufer). */
        public void record(double x, double y, double z) {
            if (!points.isEmpty()) {
                double[] last = points.get(points.size() - 1);
                double d = dist(last, x, y, z);
                if (d < TRAIL_STEP) {
                    return;
                }
                if (d > TRAIL_BREAK) {
                    points.clear();
                }
            }
            points.add(new double[] {x, y, z});
            if (points.size() > TRAIL_MAX) {
                points.remove(0);
            }
        }

        public void clear() {
            points.clear();
        }

        public int size() {
            return points.size();
        }

        /** Spur-Punkt dist Blöcke Weglänge hinter dem neuesten; null ohne Spur. */
        public double[] behind(double dist) {
            if (points.isEmpty()) {
                return null;
            }
            double acc = 0;
            for (int i = points.size() - 1; i > 0; i--) {
                double[] a = points.get(i);
                double[] b = points.get(i - 1);
                acc += dist(a, b[0], b[1], b[2]);
                if (acc >= dist) {
                    return b.clone();
                }
            }
            return points.get(0).clone();
        }

        /**
         * Nächster Spur-Punkt vor Vera (Richtung Spieler): der ihr nächste Punkt der Spur, bei Erreichen der danach.
         * null, wenn sie nicht auf der Spur ist (weiter als ON_TRAIL waagrecht oder 1,5 senkrecht von jedem Punkt).
         */
        public double[] ahead(double x, double y, double z) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                double[] p = points.get(i);
                double h = Math.hypot(p[0] - x, p[2] - z);
                if (h <= ON_TRAIL && Math.abs(p[1] - y) <= 1.5 && h < bestD) {
                    best = i;
                    bestD = h;
                }
            }
            if (best < 0) {
                return null;
            }
            // Den jüngsten schon erreichten Punkt überspringen; die Spur kreuzt sich in Höhlen oft selbst.
            int i = best;
            while (i < points.size() - 1 && Math.hypot(points.get(i)[0] - x, points.get(i)[2] - z) < PASSED) {
                i++;
            }
            return points.get(i).clone();
        }
    }

    /**
     * Ziel beim Folgen (Füße, Blockmitte): schräg hinter dem Spieler, wenn dort Platz ist und nichts dazwischen liegt,
     * sonst der Spur-Punkt distance Blöcke zurück, ohne Spur der Spielerplatz. dirX/dirZ: Blickrichtung des Spielers.
     */
    public static double[] goal(CompanionPath.Blocks w, double px, double py, double pz, double dirX, double dirZ,
            double distance, Trail trail) {
        double[] beside = beside(w, px, py, pz, dirX, dirZ, distance);
        if (beside != null && lineClear(w, px, py, pz, beside[0], beside[2])) {
            return beside;
        }
        double[] t = trail == null ? null : trail.behind(distance);
        return t != null ? t : new double[] {px, py, pz};
    }

    /** Wie vor 2026-11-07: schräg hinter dem Spieler auf passender Stehhöhe; null, wenn dort kein Platz ist. */
    static double[] beside(CompanionPath.Blocks w, double px, double py, double pz, double dirX, double dirZ,
            double distance) {
        double len = Math.hypot(dirX, dirZ);
        double bx = len < 1.0e-6 ? 0 : -dirX / len;
        double bz = len < 1.0e-6 ? -1 : -dirZ / len;
        double x = px + bx * distance - bz * SIDE;
        double z = pz + bz * distance + bx * SIDE;
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        int top = (int) Math.floor(py) + 2;
        for (int y = top; y >= top - 6; y--) {
            if (CompanionPath.standable(w, ix, y, iz)) {
                return new double[] {ix + 0.5, y, iz + 0.5};
            }
        }
        return null;
    }

    /**
     * Freie Sicht in Kopf- und Fußhöhe vom Spieler bis (x, z), in Vierteln eines Blocks abgetastet. Eine Stufe auf dem
     * Weg zählt als Wand – dann nimmt Vera eben die Spur, das sieht an Hängen ebenso natürlich aus.
     */
    static boolean lineClear(CompanionPath.Blocks w, double px, double py, double pz, double x, double z) {
        double len = Math.hypot(x - px, z - pz);
        int n = Math.max(1, (int) Math.ceil(len / 0.25));
        int y = (int) Math.floor(py + 0.01);
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            int cx = (int) Math.floor(px + (x - px) * t);
            int cz = (int) Math.floor(pz + (z - pz) * t);
            if (!w.passable(cx, y, cz) || !w.passable(cx, y + 1, cz)) {
                return false;
            }
        }
        return true;
    }

    private static double dist(double[] a, double x, double y, double z) {
        double dx = a[0] - x;
        double dy = a[1] - y;
        double dz = a[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
