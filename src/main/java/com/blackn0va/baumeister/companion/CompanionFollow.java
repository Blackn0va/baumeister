package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * Wohin Vera beim Folgen läuft, auf welchem Weg, und wohin sie notfalls springt.
 *
 * Geschichte:
 * - Bis 2026-11-07 lag das Ziel immer schräg hinter dem Spieler (2–3 Blöcke zurück, 1,2 zur Seite). In einem Gang lag
 *   dieser Punkt im Fels oder im Nachbargang; nach 40 Ticks teleportierte sie hinein (FollowProbe: 120 Ticks im Stein).
 * - e9c9278 (2026-11-07): Spur (Trail) als Rückfall. Wunsch. Zwei Ursachen:
 *   1. „Hinter dem Spieler“ hing an seiner BLICKRICHTUNG. Dreht er um, liegt „hinter“ auf der anderen Seite – Vera lief
 *      an ihm vorbei dorthin zurück, wo sie herkamen, und folgte dann neu.
 *   2. Ohne Weg (Wegsuche erfolglos – oder nur ein Punkt, weil sie schon fast am Ziel stand) nahm sie den ihr NÄCHSTEN
 *      Spur-Punkt und lief die Spur von dort weiter. Lag der auf einem alten Stück (Spieler umgekehrt, Abzweig), lief
 *      sie seinen ganzen alten Weg noch einmal ab.
 *
 * Jetzt:
 * - Ziel (goal): auf VERAS Seite in distance Blöcken Abstand, wenn dort Platz ist (höchstens 1 Block höher/tiefer als
 *   der Spieler) und keine Wand dazwischen – wie ein Wolf, der hinläuft und stehen bleibt, statt um den Spieler zu
 *   kreisen. Sonst der Spur-Punkt distance Blöcke Weglänge hinter dem Spieler (behind).
 * - Weg (Planner): Wegsuche (CompanionPath). Ein Ein-Punkt-Weg heißt „schon da“ (Ziel direkt). Ohne Weg: der NEUESTE
 *   Spur-Punkt, den sie geradeaus erreicht (reachable) – so kürzt sie ab und läuft nie zurück; erst wenn sie keinen
 *   sieht, der nächste in 2,5 Blöcken, um wieder auf die Spur zu kommen.
 * - Anlaufen ab distance + 1,5, Stehenbleiben erst ab distance + 0,5 (shouldWalk): vorher dieselbe Schwelle für
 *   beides, und sie zuckelte bei jedem Schritt des Spielers an und hielt wieder.
 * - Teleport nur auf einen Spur-Punkt (behind), nie aufs Ziel im Fels.
 *
 * - Veraltete Spur (fresh): Liegt ihr neuester Punkt mehr als FRESH Blöcke vom Spieler weg, zählt sie nicht. Gemessen
 *   im Spiel 2026-11-07 17:15 (Log „weg_vom_spieler“): Spur mit 16 Punkten, der Spieler 48 Blöcke weiter, Vera stand
 *   GENAU auf ihrem Ziel (Abstand 0,0) – dem Spur-Punkt 2,5 Blöcke hinter dem letzten aufgezeichneten Standpunkt am
 *   Start. Die Spur hatte nicht mehr mitgeschrieben (Stand-Erkennung, Boot); das war „geht wieder an den Startpunkt“.
 *
 * Wer „hinter dem Spieler“ wieder an die Blickrichtung hängt oder im Rückfall den nächsten statt den neuesten Punkt
 * nimmt, bringt beide Fehler vom 2026-11-07 zurück (CompanionFollowTest, probes/mannequin/FollowProbe).
 *
 * Threads: Trail und Planner gehören einem Begleiter und werden nur im Hauptthread benutzt (nicht threadsicher).
 * Fehler: wirft nicht.
 */
public final class CompanionFollow {

    /** So viele Standpunkte reichen für rund 50 Blöcke Weg (Abstand je Punkt TRAIL_STEP). */
    public static final int TRAIL_MAX = 64;
    static final double TRAIL_STEP = 0.8;
    /** Größerer Sprung zwischen zwei Standpunkten: Teleport, Enderperle, Welt – die alte Spur führt nicht mehr hin. */
    static final double TRAIL_BREAK = 6.0;
    /** Bis so weit (waagrecht) sucht sie auf der Spur einen Punkt, den sie geradeaus erreicht. */
    static final double REACH = 12.0;
    /** Spur gilt nur, wenn ihr neuester Punkt höchstens so weit (waagrecht, senkrecht 3) vom Spieler weg ist. */
    public static final double FRESH = 4.0;
    /** So nah an einem Spur-Punkt (waagrecht) gilt Vera als auf der Spur. */
    static final double ON_TRAIL = 2.5;
    /**
     * Losgehen ab distance + START_GAP, stehen bleiben ab distance + STOP_GAP. START_GAP wie die alte Schwelle (1,5):
     * Mit 2,5 fiel sie in der Natur-Welt im Mittel bis 7,1 statt 5,9 Blöcke zurück (FollowProbe 2026-11-07).
     */
    public static final double START_GAP = 1.5;
    public static final double STOP_GAP = 0.5;

    private CompanionFollow() {
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

        /**
         * Schreibt die Spur noch mit? Neuester Punkt höchstens FRESH Blöcke waagrecht und 3 senkrecht vom Spieler. Sonst
         * zeigt „hinter dem Spieler auf der Spur“ dorthin, wo die Aufzeichnung abbrach – nicht zum Spieler.
         */
        public boolean fresh(double px, double py, double pz) {
            if (points.isEmpty()) {
                return false;
            }
            double[] last = points.get(points.size() - 1);
            return Math.hypot(last[0] - px, last[2] - pz) <= FRESH && Math.abs(last[1] - py) <= 3;
        }

        /** Neuester Standpunkt; null ohne Spur. */
        public double[] newest() {
            return points.isEmpty() ? null : points.get(points.size() - 1).clone();
        }

        /** this, wenn die Spur zum Spieler passt (fresh), sonst null – für Aufrufer, die eine Spur übergeben. */
        public Trail ifFresh(double px, double py, double pz) {
            return fresh(px, py, pz) ? this : null;
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
         * Spur-Punkt, auf den sie ohne Weg zuläuft: der NEUESTE, den sie geradeaus erreicht (bis REACH waagrecht,
         * höchstens 1,2 höher oder tiefer, walkable). Sieht sie keinen, der nächste in ON_TRAIL – nur um wieder auf die
         * Spur zu kommen, nicht um ihr von dort weiterzufolgen. null, wenn sie weit weg von der Spur ist.
         */
        public double[] reachable(CompanionPath.Blocks w, double x, double y, double z) {
            for (int i = points.size() - 1; i >= 0; i--) {
                double[] p = points.get(i);
                if (Math.hypot(p[0] - x, p[2] - z) <= REACH && Math.abs(p[1] - y) <= 1.2
                        && walkable(w, x, y, z, p[0], p[1], p[2])) {
                    return p.clone();
                }
            }
            double[] best = null;
            double bestD = Double.MAX_VALUE;
            for (double[] p : points) {
                double h = Math.hypot(p[0] - x, p[2] - z);
                if (h <= ON_TRAIL && Math.abs(p[1] - y) <= 1.5 && h < bestD) {
                    best = p;
                    bestD = h;
                }
            }
            return best == null ? null : best.clone();
        }

        /** Laufrichtung des Spielers über die letzten ~2 Blöcke (dx, dz), null wenn er (noch) nicht läuft. */
        public double[] heading() {
            if (points.size() < 2) {
                return null;
            }
            double[] last = points.get(points.size() - 1);
            for (int i = points.size() - 2; i >= 0; i--) {
                double[] p = points.get(i);
                double h = Math.hypot(last[0] - p[0], last[2] - p[2]);
                if (h >= 1.5) {
                    return new double[] {(last[0] - p[0]) / h, (last[2] - p[2]) / h};
                }
            }
            return null;
        }
    }

    /**
     * Ziel beim Folgen (Füße): auf Veras Seite (vx, vz) in distance Blöcken Abstand, wenn dort Platz ist und nichts
     * dazwischen liegt; sonst der Spur-Punkt hinter dem Spieler, der ihr am nächsten ist; ohne Spur der Spielerplatz.
     */
    public static double[] goal(CompanionPath.Blocks w, double px, double py, double pz, double vx, double vz,
            double distance, Trail all) {
        Trail trail = all == null ? null : all.ifFresh(px, py, pz);
        double[] spot = toward(w, px, py, pz, vx, vz, distance, trail);
        if (spot != null && lineClear(w, px, py, pz, spot[0], spot[2])) {
            return spot;
        }
        // Spur-Punkt distance Blöcke WEGLÄNGE hinter dem Spieler: dort kam er her, dort ist Platz. Nicht „der Spur-Punkt,
        // der Vera am nächsten ist“ – das war in der ersten Fassung meist ihr eigener Standort, und sie blieb im Gang
        // 10 Blöcke zurück stehen (FollowProbe 2026-11-07).
        double[] t = trail == null ? null : trail.behind(distance);
        return t != null ? t : new double[] {px, py, pz};
    }

    /**
     * Ziel = der Spieler selbst; anhalten übernimmt shouldWalk (bei distance + STOP_GAP). Steht er nicht auf einem
     * begehbaren Platz (im Sprung, auf einem Halbblock), der neueste frische Spur-Punkt; sonst seine Position.
     *
     * Warum (Log dev01 2026-11-07 18:00–18:02, „weg_vom_spieler“): In den Bergen stand Vera mehrfach GENAU auf ihrem
     * Ziel (Abstand 0,0) und hing doch 5–6 Blöcke zurück – das Ziel „auf Veras Seite“ bzw. der Spur-Punkt hinter einer
     * kurzen Spur lag bei ihr selbst. Mit dem Spieler als Ziel kann das nicht passieren.
     */
    public static double[] goalPlayer(CompanionPath.Blocks w, double px, double py, double pz, Trail all) {
        int x = (int) Math.floor(px);
        int y = (int) Math.floor(py + 0.01);
        int z = (int) Math.floor(pz);
        if (CompanionPath.standable(w, x, y, z) || CompanionPath.standable(w, x, y + 1, z)) {
            return new double[] {px, py, pz};
        }
        Trail t = all == null ? null : all.ifFresh(px, py, pz);
        double[] n = t == null ? null : t.newest();
        return n != null ? n : new double[] {px, py, pz};
    }

    /**
     * Platz in distance Blöcken vom Spieler in Richtung Vera, auf Stehhöhe des Spielers ±1; null ohne Platz. Steht
     * Vera genau auf dem Spieler, in Gegenrichtung seiner Laufrichtung (heading), sonst nach Süden.
     */
    static double[] toward(CompanionPath.Blocks w, double px, double py, double pz, double vx, double vz,
            double distance, Trail trail) {
        double dx = vx - px;
        double dz = vz - pz;
        double len = Math.hypot(dx, dz);
        if (len < 0.3) {
            double[] h = trail == null ? null : trail.heading();
            dx = h == null ? 0 : -h[0];
            dz = h == null ? 1 : -h[1];
            len = Math.hypot(dx, dz);
        }
        int ix = (int) Math.floor(px + dx / len * distance);
        int iz = (int) Math.floor(pz + dz / len * distance);
        int y0 = (int) Math.floor(py + 0.01);
        for (int dy : new int[] {0, -1, 1}) {
            if (CompanionPath.standable(w, ix, y0 + dy, iz)) {
                return new double[] {ix + 0.5, y0 + dy, iz + 0.5};
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

    /**
     * Geradeaus begehbar von (x, y, z) nach (tx, ty, tz)? Je 0,3 Blöcke: Füße und Kopf frei und Boden darunter (bis
     * 1 Block tiefer, oder Wasser) – sonst liefe sie über ein Loch zum Spur-Punkt dahinter.
     */
    static boolean walkable(CompanionPath.Blocks w, double x, double y, double z, double tx, double ty, double tz) {
        double len = Math.hypot(tx - x, tz - z);
        int n = Math.max(1, (int) Math.ceil(len / 0.3));
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            int cx = (int) Math.floor(x + (tx - x) * t);
            int cz = (int) Math.floor(z + (tz - z) * t);
            int cy = (int) Math.floor(y + (ty - y) * t + 0.01);
            boolean free = w.passable(cx, cy, cz) && w.passable(cx, cy + 1, cz)
                    || w.passable(cx, cy + 1, cz) && w.passable(cx, cy + 2, cz) && w.solid(cx, cy, cz);
            boolean floor = w.solid(cx, cy - 1, cz) || w.solid(cx, cy, cz) || w.solid(cx, cy - 2, cz)
                    || w.water(cx, cy, cz) || w.water(cx, cy - 1, cz) || w.climbable(cx, cy, cz);
            if (!free || !floor) {
                return false;
            }
        }
        return true;
    }

    /**
     * Soll sie laufen? Mit Hysterese: Steht sie, geht sie erst ab distance + START_GAP los; läuft sie, hält sie erst
     * bei distance + STOP_GAP. Vorher eine Schwelle – bei jedem Schritt des Spielers ging sie los und hielt gleich wieder.
     */
    public static boolean shouldWalk(boolean walking, double dist, double distance) {
        return walking ? dist > distance + STOP_GAP : dist > distance + START_GAP;
    }

    /**
     * Weg zum Ziel für einen Begleiter: Wegsuche mit Neuplanung (Ziel mehr als 2 Blöcke gewandert, 10 Ticks fest,
     * Weg älter als 40 Ticks; höchstens alle 5 Ticks), sonst die Spur (Trail.reachable), sonst geradeaus.
     */
    public static final class Planner {
        private List<CompanionPath.Node> path;
        private int index;
        private CompanionPath.Node goal;
        private long planned = Long.MIN_VALUE / 2;
        private String mode = "";

        /** Nächster Punkt, auf den sie zuläuft (Füße). */
        public double[] next(CompanionPath.Blocks w, double x, double y, double z, double gx, double gy, double gz,
                Trail trail, long tick, int stuck) {
            CompanionPath.Node goalNode = new CompanionPath.Node((int) Math.floor(gx), (int) Math.floor(gy + 0.01),
                    (int) Math.floor(gz));
            boolean moved = goal == null || Math.abs(goal.x() - goalNode.x()) + Math.abs(goal.y() - goalNode.y())
                    + Math.abs(goal.z() - goalNode.z()) > 2;
            boolean replan = path == null || moved || stuck >= 10 || tick - planned > 40;
            if (replan && tick - planned >= 5) {
                path = CompanionPath.find(w, new CompanionPath.Node((int) Math.floor(x), (int) Math.floor(y + 0.01),
                        (int) Math.floor(z)), goalNode);
                index = 0;
                goal = goalNode;
                planned = tick;
            }
            if (path != null && path.size() >= 2) {
                index = CompanionPath.advance(path, index, x, y, z);
                if (index < path.size() - 1) {
                    mode = "weg";
                    CompanionPath.Node n = path.get(index);
                    return new double[] {n.x() + 0.5, n.y(), n.z() + 0.5};
                }
                mode = "ziel";
                return new double[] {gx, gy, gz};
            }
            if (path != null && path.size() == 1) {
                // Schon da (innerhalb 1 Block): Vorher galt das als „kein Weg“, und sie lief auf der Spur davon.
                mode = "ziel";
                return new double[] {gx, gy, gz};
            }
            double[] t = trail == null ? null : trail.reachable(w, x, y, z);
            if (t != null) {
                mode = "spur";
                return t;
            }
            mode = "direkt";
            return new double[] {gx, gy, gz};
        }

        /** Aktueller Wegpunkt der Wegsuche (für Türen auf dem Weg), null ohne Weg. */
        public CompanionPath.Node waypoint() {
            return "weg".equals(mode) && path != null && index < path.size() ? path.get(index) : null;
        }

        /** Wie zuletzt entschieden: weg, ziel, spur, direkt (Probe, Log). */
        public String mode() {
            return mode;
        }

        /** Nach Teleport oder Weltwechsel: neu planen. */
        public void reset() {
            path = null;
            goal = null;
            planned = Long.MIN_VALUE / 2;
        }
    }

    private static double dist(double[] a, double x, double y, double z) {
        double dx = a[0] - x;
        double dy = a[1] - y;
        double dz = a[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
