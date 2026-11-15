package com.blackn0va.baumeister.companion;

import java.util.List;

/**
 * Platz machen wie ein höflicher Mensch (Rückmeldung 2026-11-15: „manchmal steht der vera im weg rum und checkt das
 * nicht“). Entwurf aus der Ultracode-Runde 2026-11-15 (Blickwinkel Bewegung), im Simulator 25 Tests, eingebunden in
 * CompanionService.
 *
 * Vorher blieb Vera stehen, wo sie bei 3 Blöcken Abstand ankam (CompanionService.tick, Zweig „nah genug“), und ging
 * erst ab 4 Blöcken wieder los (CompanionFollow.shouldWalk). Kam der Spieler auf sie zu, sank der Abstand – für sie
 * kein Grund, sich zu rühren. Stand sie vor dem Block, den er anschaut, fing ihr Körper den Klick ab (26.2-Client,
 * LocalPlayer.pick: Wesen vor dem Block gewinnt; bis 3 Blöcke trifft der Klick Vera, bis 4,5 ins Leere).
 *
 * Jetzt je Aufruf: Bahn jedes Spielers 1,5 s vorausrechnen (nächster Vorbeigang), Zielstrahl beim Arbeiten,
 * Blickmitte, Nähe und den eigenen Platz (Tür, Leiter, Gang, Treppe, Baustelle). Ausgelöst wird mit Verweilzeit und
 * Hysterese; erst dann wird ein Platz gesucht (Ring um den Besitzer, Geometrie vor Blockabfragen). Ohne Platz in einem
 * Gang läuft sie in seiner Richtung voraus bis zur nächsten Nische; in einer Sackgasse meldet sie SWAP.
 *
 * Zeiten in Aufrufen (1 Aufruf = 2 Ticks = 0,1 s, CompanionService.start), Geschwindigkeiten in Block je Tick.
 *
 * Threads: Hauptthread, je Begleiter ein State und je Spieler eine Motion. Fehler: wirft nicht.
 */
public final class CompanionYield {

    /** Ticks je Aufruf (runTaskTimer(…, 20L, 2L)). */
    static final int CALL_TICKS = 2;
    /** Vorausschau in Ticks: 1,5 s – gehend 6,5, sprintend 8,4 Blöcke; mehr als Ruheabstand plus Ausweichzeit. */
    static final double HORIZON = 30;
    /** Langsamer gilt als stehend (Schleichen 0,065 Block/Tick). */
    static final double MOVING = 0.04;
    /** Nächster Vorbeigang (Mitte zu Mitte) unter CLEAR_ENTER: ausweichen; Konflikt vorbei erst über CLEAR_EXIT. */
    static final double CLEAR_ENTER = 1.0;
    static final double CLEAR_EXIT = 1.6;
    /** Ausweichplatz mindestens so weit von der vorhergesagten Bahn (Körper 0,3 + 0,3, dazu 0,9 Luft). */
    static final double TUBE = 1.5;
    /** In Gängen reicht eine Nische 1 Block neben der Bahn (0,4 Luft zwischen den Körpern). */
    static final double NICHE = 0.95;
    static final double NEAR_ENTER = 1.5;
    static final double NEAR_EXIT = 2.2;
    /** Kein neuer Platz näher als das an einem Spieler. */
    static final double PERSONAL = 1.8;
    static final double COMFORT_MIN = 2.0;
    static final double COMFORT_MAX = 3.5;
    static final double[] RADII = {2.25, 2.75, 3.25};
    static final double[] LOCAL = {1.0, 1.75};
    static final double VIEW_ENTER = Math.cos(Math.toRadians(20));
    static final double VIEW_EXIT = Math.cos(Math.toRadians(30));
    static final double VIEW_FREE = Math.cos(Math.toRadians(35));
    static final double VIEW_RANGE = 6.0;
    static final double VIEW_WEIGHT = 3.0;
    static final double RAY_PAD_ENTER = 0.2;
    static final double RAY_PAD_EXIT = 0.4;
    static final int RAY_DWELL = 2;
    static final int VIEW_DWELL = 15;
    static final int NEAR_DWELL = 10;
    static final int SPOT_DWELL = 10;
    /** So lange (Aufrufe) bleibt sie an einem neuen Platz, außer Bahn oder Zielstrahl verlangen anderes. */
    static final int HOLD = 20;
    static final int SIDE_HOLD = 15;
    static final double SWITCH_MARGIN = 0.5;
    static final double MOVE_WEIGHT = 0.8;
    /** Befohlenes Tempo beim Ausweichen; bei Steuerung alle 2 Ticks wirken ~77 % (Bodenreibung 0,546, Rechnung). */
    public static final double YIELD_SPEED = 0.30;
    public static final double CALM_SPEED = 0.22;
    static final double ARRIVED = 0.45;
    static final int GIVE_UP = 40;
    static final int LEAD_STEPS = 8;
    static final double INF = Double.POSITIVE_INFINITY;
    private static final int[][] N4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] N8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private CompanionYield() {
    }

    /** Baut hier jemand (Arbeitszellen der Spieler, BuildDropGuard.covers für den Baumeister)? */
    @FunctionalInterface
    public interface Zones {
        boolean busy(int x, int y, int z);

        Zones NONE = (x, y, z) -> false;
    }

    /**
     * Ein Spieler in der Nähe: Füße, Geschwindigkeit (Block je Tick, Motion), Augenhöhe, Blick (Einheitsvektor), Länge
     * des Zielstrahls bis zum anvisierten Block (sonst Reichweite), arbeitet er gerade (Bauen, Abbauen, Klick in den
     * letzten 3 s), Besitzer, im Gespräch mit Vera.
     */
    public record Person(double x, double y, double z, double vx, double vz, double eyeY, double lookX, double lookY,
            double lookZ, double rayLen, boolean working, boolean owner, boolean talking) {

        public double speed() {
            return Math.hypot(vx, vz);
        }
    }

    public enum Reason {
        NONE, PATH, RAY, NEAR, SPOT, VIEW, LEAD, SWAP
    }

    /**
     * Wohin (Füße) und wie schnell; faceOwner = beim Ausweichen den Spieler ansehen; holdFollow = nicht
     * hinterherlaufen.
     */
    public record Decision(Reason reason, double x, double y, double z, double speed, boolean faceOwner,
            boolean holdFollow) {

        static final Decision STAY = new Decision(Reason.NONE, 0, 0, 0, 0, true, false);
        static final Decision HOLD_FOLLOW = new Decision(Reason.NONE, 0, 0, 0, 0, true, true);

        public boolean moving() {
            return reason != Reason.NONE && reason != Reason.SWAP;
        }
    }

    /** Geschwindigkeit aus dem Ort von Aufruf zu Aufruf, geglättet. Sprünge über JUMP (Teleport) zählen nicht. */
    public static final class Motion {
        static final double ALPHA = 0.7;
        static final double JUMP = 4.0;
        private double lx = Double.NaN;
        private double lz;
        private double vx;
        private double vz;

        public void update(double x, double z) {
            if (!Double.isNaN(lx)) {
                double dx = (x - lx) / CALL_TICKS;
                double dz = (z - lz) / CALL_TICKS;
                if (Math.hypot(dx, dz) * CALL_TICKS > JUMP) {
                    vx = 0;
                    vz = 0;
                } else {
                    vx = ALPHA * dx + (1 - ALPHA) * vx;
                    vz = ALPHA * dz + (1 - ALPHA) * vz;
                }
            }
            lx = x;
            lz = z;
        }

        public void reset() {
            lx = Double.NaN;
            vx = 0;
            vz = 0;
        }

        public double vx() {
            return vx;
        }

        public double vz() {
            return vz;
        }
    }

    /** Gedächtnis eines Begleiters. */
    public static final class State {
        private long call;
        private Reason reason = Reason.NONE;
        private double[] spot;
        private long started;
        private long arrived = Long.MIN_VALUE / 2;
        private int side;
        private long sideUntil = Long.MIN_VALUE / 2;
        private boolean conflict;
        private int rayTicks;
        private int viewTicks;
        private int nearTicks;
        private int badTicks;

        public void reset() {
            reason = Reason.NONE;
            spot = null;
            conflict = false;
            rayTicks = 0;
            viewTicks = 0;
            nearTicks = 0;
            badTicks = 0;
        }

        /** Ein Spieler hat Vera angeklickt (BaumeisterListener): sie steht in seinem Zielstrahl – sofort weichen. */
        public void bumped() {
            rayTicks = RAY_DWELL;
        }

        public Reason reason() {
            return reason;
        }

        boolean conflict() {
            return conflict;
        }

        int side() {
            return side;
        }
    }

    /**
     * Ein Aufruf. (x, y, z0) = Veras Füße, (ovx, ovz) = ihre Geschwindigkeit (Motion). STAY = nichts zu tun, dann
     * entscheidet das Folgen wie bisher – außer holdFollow (Spieler kommt auf sie zu oder ein Platz ist in Arbeit).
     */
    public static Decision decide(State s, CompanionPath.Blocks w, Zones z, double x, double y, double z0,
            double ovx, double ovz, List<Person> people) {
        s.call++;
        Person owner = null;
        for (Person p : people) {
            if (p.owner()) {
                owner = p;
                break;
            }
        }
        // 1. Bahn: nächster Vorbeigang innerhalb der Vorausschau. Neu ausweichen nur unter CLEAR_ENTER; der Konflikt
        // (kein Hinterherlaufen, Blick zum Spieler) hält bis CLEAR_EXIT – sonst ginge sie am Ausweichplatz wieder los.
        Person threat = null;
        double tMin = INF;
        boolean keep = false;
        for (Person p : people) {
            if (Math.abs(p.y() - y) > 2.0) {
                continue;
            }
            double[] c = cpa(p, x, z0, ovx, ovz);
            if (c == null) {
                continue;
            }
            keep |= c[1] < CLEAR_EXIT;
            if (c[1] < CLEAR_ENTER && c[0] < tMin) {
                tMin = c[0];
                threat = p;
            }
        }
        s.conflict = s.conflict ? keep : threat != null;
        boolean approaching = owner != null && Math.abs(owner.y() - y) <= 2.0 && closing(owner, x, z0) > MOVING;
        boolean hold = approaching || s.conflict;
        // 2. Zielstrahl eines arbeitenden Spielers (seine Klicks gingen an ihr hängen).
        boolean ray = false;
        double pad = s.rayTicks > 0 ? RAY_PAD_EXIT : RAY_PAD_ENTER;
        for (Person p : people) {
            if (p.working() && rayHits(p, x, y, z0, pad)) {
                ray = true;
            }
        }
        s.rayTicks = ray ? s.rayTicks + 1 : 0;
        // 3. Blickmitte des Besitzers (nicht im Gespräch – dort gehört sie hin).
        boolean view = owner != null && !owner.talking() && inView(owner, x, y, z0, s.viewTicks > 0);
        s.viewTicks = view ? s.viewTicks + 1 : 0;
        // 4. Auf die Pelle.
        boolean near = false;
        double nearLimit = s.nearTicks > 0 ? NEAR_EXIT : NEAR_ENTER;
        for (Person p : people) {
            if (Math.abs(p.y() - y) <= 2.0 && Math.hypot(x - p.x(), z0 - p.z()) < nearLimit) {
                near = true;
            }
        }
        s.nearTicks = near ? s.nearTicks + 1 : 0;
        // 5. Eigener Platz, nur jeden 5. Aufruf (Blockabfragen).
        if (s.call % 5 == 0) {
            int fx = (int) Math.floor(x);
            int fy = (int) Math.floor(y + 0.01);
            int fz = (int) Math.floor(z0);
            s.badTicks = spotCost(w, z, fx, fy, fz) >= 1.5 ? s.badTicks + 5 : 0;
        }

        // Unterwegs zu einem Platz: dabei bleiben, solange er frei ist (kein Zappeln).
        if (s.spot != null) {
            double h = Math.hypot(s.spot[0] - x, s.spot[2] - z0);
            boolean blocked = !tubeFree(s.spot[0], s.spot[2], people, y)
                    || ray && rayAt(people, s.spot[0], s.spot[1], s.spot[2]);
            if (h < ARRIVED || s.call - s.started > GIVE_UP) {
                s.spot = null;
                s.reason = Reason.NONE;
                s.arrived = s.call;
            } else if (!blocked) {
                return new Decision(s.reason, s.spot[0], s.spot[1], s.spot[2], speedFor(s.reason, threat), h < 2.5,
                        true);
            } else {
                s.spot = null;
            }
        }

        Reason why = Reason.NONE;
        boolean hard = false;
        if (threat != null) {
            why = Reason.PATH;
            hard = true;
        } else if (s.rayTicks >= RAY_DWELL) {
            why = Reason.RAY;
            hard = true;
        } else if (s.nearTicks >= NEAR_DWELL) {
            why = Reason.NEAR;
        } else if (s.badTicks >= SPOT_DWELL) {
            why = Reason.SPOT;
        } else if (s.viewTicks >= VIEW_DWELL) {
            why = Reason.VIEW;
        }
        if (why == Reason.NONE || !hard && s.call - s.arrived < HOLD) {
            return hold ? Decision.HOLD_FOLLOW : Decision.STAY;
        }
        Person ref = threat != null ? threat : owner;
        if (ref != null && s.call >= s.sideUntil) {
            // 0 = genau davor; dann zieht geo seine Linke vor (wer sich begegnet, hält sich rechts).
            s.side = sideOf(ref, x, z0);
            s.sideUntil = s.call + SIDE_HOLD;
        }
        double[] spot = choose(s, w, z, x, y, z0, people, owner, ref, threat, hard);
        if (spot == null && why == Reason.PATH) {
            spot = lead(w, x, y, z0, threat);
            if (spot == null) {
                s.arrived = s.call;
                return new Decision(Reason.SWAP, x, y, z0, 0, true, true);
            }
            if (spot[3] > 0) {
                why = Reason.LEAD;
            }
        }
        if (spot == null) {
            // Nichts Besseres: bleiben und HOLD Aufrufe lang nicht neu suchen (Kosten, kein Zappeln).
            s.arrived = s.call;
            return hold ? Decision.HOLD_FOLLOW : Decision.STAY;
        }
        s.reason = why;
        s.started = s.call;
        // Voraus laufen hält keinen Platz fest: jeder Aufruf entscheidet neu, bis eine Nische frei ist.
        s.spot = why == Reason.LEAD ? null : new double[] {spot[0], spot[1], spot[2]};
        double dist = Math.hypot(spot[0] - x, spot[2] - z0);
        return new Decision(why, spot[0], spot[1], spot[2], speedFor(why, threat), why != Reason.LEAD && dist < 2.5,
                true);
    }

    static double speedFor(Reason r, Person threat) {
        return switch (r) {
            case PATH, RAY -> YIELD_SPEED;
            case LEAD -> threat == null ? CALM_SPEED : Math.max(CALM_SPEED, Math.min(0.34, threat.speed() * 1.4));
            default -> CALM_SPEED;
        };
    }

    /**
     * {Zeit bis zum nächsten Vorbeigang (Ticks), Abstand dort}; null, wenn er steht, sich entfernt oder zu weit ist.
     */
    static double[] cpa(Person p, double x, double z, double ovx, double ovz) {
        if (p.speed() < MOVING) {
            return null;
        }
        double rx = x - p.x();
        double rz = z - p.z();
        double ux = p.vx() - ovx;
        double uz = p.vz() - ovz;
        double uu = ux * ux + uz * uz;
        if (uu < 1.0e-9) {
            return null;
        }
        double t = (rx * ux + rz * uz) / uu;
        if (t <= 0 || t > HORIZON) {
            return null;
        }
        return new double[] {t, Math.hypot(rx - ux * t, rz - uz * t)};
    }

    /** Wie schnell kommt p auf (x, z) zu (Block je Tick, positiv = näher)? */
    static double closing(Person p, double x, double z) {
        double rx = x - p.x();
        double rz = z - p.z();
        double len = Math.hypot(rx, rz);
        return len < 1.0e-6 ? 0 : (p.vx() * rx + p.vz() * rz) / len;
    }

    /** Seite von (x, z) zur Laufrichtung (sonst Blickrichtung) von p: -1 links, +1 rechts, 0 genau davor. */
    static int sideOf(Person p, double x, double z) {
        double hx = p.vx();
        double hz = p.vz();
        if (Math.hypot(hx, hz) < MOVING) {
            hx = p.lookX();
            hz = p.lookZ();
        }
        double len = Math.hypot(hx, hz);
        if (len < 1.0e-6) {
            return 0;
        }
        double cross = (hx * (z - p.z()) - hz * (x - p.x())) / len;
        return Math.abs(cross) < 0.25 ? 0 : cross < 0 ? -1 : 1;
    }

    /** Schneidet der Zielstrahl von p Veras Körper (0,6 × 1,8, um pad vergrößert) vor dem anvisierten Block? */
    static boolean rayHits(Person p, double x, double y, double z, double pad) {
        double len = p.rayLen();
        if (len <= 0) {
            return false;
        }
        double t0 = 0;
        double t1 = len;
        // Platten-Test je Achse, ohne Zuteilung (alle 2 Ticks je Spieler).
        double o = p.x();
        double d = p.lookX();
        double lo = x - 0.3 - pad;
        double hi = x + 0.3 + pad;
        if (Math.abs(d) < 1.0e-9) {
            if (o < lo || o > hi) {
                return false;
            }
        } else {
            double a = (lo - o) / d;
            double b = (hi - o) / d;
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
            if (t0 > t1) {
                return false;
            }
        }
        o = p.eyeY();
        d = p.lookY();
        lo = y - pad;
        hi = y + 1.8 + pad;
        if (Math.abs(d) < 1.0e-9) {
            if (o < lo || o > hi) {
                return false;
            }
        } else {
            double a = (lo - o) / d;
            double b = (hi - o) / d;
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
            if (t0 > t1) {
                return false;
            }
        }
        o = p.z();
        d = p.lookZ();
        lo = z - 0.3 - pad;
        hi = z + 0.3 + pad;
        if (Math.abs(d) < 1.0e-9) {
            return o >= lo && o <= hi;
        }
        double a = (lo - o) / d;
        double b = (hi - o) / d;
        return Math.max(t0, Math.min(a, b)) <= Math.min(t1, Math.max(a, b));
    }

    private static boolean rayAt(List<Person> people, double x, double y, double z) {
        for (Person p : people) {
            if (p.working() && rayHits(p, x, y, z, RAY_PAD_EXIT)) {
                return true;
            }
        }
        return false;
    }

    /** Liegt Veras Brust in der Blickmitte von p (bis VIEW_RANGE waagrecht)? Mit Hysterese 20°/30°. */
    static boolean inView(Person p, double x, double y, double z, boolean was) {
        double ex = x - p.x();
        double ey = y + 1.0 - p.eyeY();
        double ez = z - p.z();
        double len = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (len < 1.0e-6 || Math.hypot(ex, ez) > VIEW_RANGE) {
            return false;
        }
        double cos = (ex * p.lookX() + ey * p.lookY() + ez * p.lookZ()) / len;
        return cos >= (was ? VIEW_EXIT : VIEW_ENTER);
    }

    /** Bahnen aller laufenden Spieler (bis HORIZON) mindestens TUBE entfernt? */
    static boolean tubeFree(double x, double z, List<Person> people, double y) {
        for (Person p : people) {
            if (Math.abs(p.y() - y) > 2.5 || p.speed() < MOVING) {
                continue;
            }
            if (segDist(x, z, p.x(), p.z(), p.x() + p.vx() * HORIZON, p.z() + p.vz() * HORIZON) < TUBE) {
                return false;
            }
        }
        return true;
    }

    static double segDist(double x, double z, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double ll = dx * dx + dz * dz;
        double t = ll < 1.0e-12 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / ll));
        return Math.hypot(x - ax - dx * t, z - az - dz * t);
    }

    /**
     * Kosten eines Stehplatzes (Füße fx, fy, fz): unendlich in Tür, an Leiter oder auf einer Baustelle; 3 im 1 breiten
     * Durchgang, 2 neben einer Tür, 2 auf einer Treppe. Eine Nische (drei Seiten zu) kostet nichts – dort lässt man
     * jemanden vorbei.
     */
    static double spotCost(CompanionPath.Blocks w, Zones z, int fx, int fy, int fz) {
        if (w.climbable(fx, fy, fz) || w.climbable(fx, fy + 1, fz) || w.doorway(fx, fy, fz) || w.doorway(fx, fy + 1, fz)
                || z.busy(fx, fy, fz) || z.busy(fx, fy + 1, fz) || z.busy(fx, fy - 1, fz)) {
            return INF;
        }
        double c = 0;
        boolean e = standY(w, fx + 1, fy, fz) != Integer.MIN_VALUE;
        boolean wv = standY(w, fx - 1, fy, fz) != Integer.MIN_VALUE;
        boolean s = standY(w, fx, fy, fz + 1) != Integer.MIN_VALUE;
        boolean n = standY(w, fx, fy, fz - 1) != Integer.MIN_VALUE;
        if (!e && !wv && s && n || !s && !n && e && wv) {
            c += 3;
        }
        if (w.doorway(fx + 1, fy, fz) || w.doorway(fx - 1, fy, fz) || w.doorway(fx, fy, fz + 1)
                || w.doorway(fx, fy, fz - 1)) {
            c += 2;
        }
        if (w.stairs(fx, fy, fz) || w.stairs(fx, fy - 1, fz)) {
            c += 2;
        }
        return c;
    }

    /** Stehhöhe in Spalte (x, z) bei y, y-1 oder y+1; Integer.MIN_VALUE ohne Platz. */
    static int standY(CompanionPath.Blocks w, int x, int y, int z) {
        if (CompanionPath.standable(w, x, y, z)) {
            return y;
        }
        if (CompanionPath.standable(w, x, y - 1, z)) {
            return y - 1;
        }
        return CompanionPath.standable(w, x, y + 1, z) ? y + 1 : Integer.MIN_VALUE;
    }

    /**
     * Bester Platz: Ring um den Besitzer (3 Abstände × 16 Richtungen) und Nahplätze um Vera (2 × 8). Erst die
     * Geometrie ohne Blockabfragen (Bahn, Abstand, Blick, Seite, Weg), dann in aufsteigender Reihenfolge die Blöcke,
     * bis kein Kandidat mehr besser sein kann. null = bleiben (gut genug) oder keiner frei.
     */
    static double[] choose(State s, CompanionPath.Blocks w, Zones z, double x, double y, double z0,
            List<Person> people, Person owner, Person ref, Person threat, boolean mustLeave) {
        int max = RADII.length * 16 + LOCAL.length * 8;
        double[] cx = new double[max];
        double[] cz = new double[max];
        double[] g = new double[max];
        int n = 0;
        if (owner != null) {
            for (double r : RADII) {
                for (int i = 0; i < 16; i++) {
                    double a = i * Math.PI / 8;
                    n = add(cx, cz, n, owner.x() + r * Math.cos(a), owner.z() + r * Math.sin(a));
                }
            }
        }
        for (double r : LOCAL) {
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                n = add(cx, cz, n, x + r * Math.cos(a), z0 + r * Math.sin(a));
            }
        }
        double baseY = owner != null ? owner.y() : y;
        for (int i = 0; i < n; i++) {
            g[i] = geo(s, cx[i], cz[i], x, z0, y, people, owner, ref, threat);
        }
        double stay = mustLeave ? INF
                : geo(s, x, z0, x, z0, y, people, owner, ref, threat)
                        + spotCost(w, z, (int) Math.floor(x), (int) Math.floor(y + 0.01), (int) Math.floor(z0));
        int[] order = sortIdx(g, n);
        double best = INF;
        double[] out = null;
        int by = (int) Math.floor(baseY + 0.01);
        for (int k = 0; k < n; k++) {
            int i = order[k];
            // Blockkosten kommen nur dazu: Ist die Geometrie schon teurer als der beste, kann keiner mehr gewinnen.
            if (g[i] >= best || g[i] == INF) {
                break;
            }
            int ix = (int) Math.floor(cx[i]);
            int iz = (int) Math.floor(cz[i]);
            int fy = standY(w, ix, by, iz);
            if (fy == Integer.MIN_VALUE || Math.abs(fy - y) > 1.2 || rayAt(people, cx[i], fy, cz[i])) {
                continue;
            }
            double c = spotCost(w, z, ix, fy, iz);
            if (c == INF || !CompanionFollow.walkable(w, x, y, z0, cx[i], fy, cz[i])) {
                continue;
            }
            if (g[i] + c < best) {
                best = g[i] + c;
                out = new double[] {cx[i], fy, cz[i], 0};
            }
        }
        if (out != null && best + SWITCH_MARGIN >= stay) {
            return null;
        }
        return out;
    }

    private static int add(double[] cx, double[] cz, int n, double x, double z) {
        double ux = Math.floor(x) + 0.5;
        double uz = Math.floor(z) + 0.5;
        for (int i = 0; i < n; i++) {
            if (cx[i] == ux && cz[i] == uz) {
                return n;
            }
        }
        cx[n] = ux;
        cz[n] = uz;
        return n + 1;
    }

    private static int[] sortIdx(double[] g, int n) {
        int[] o = new int[n];
        for (int i = 0; i < n; i++) {
            o[i] = i;
        }
        // Einfügesortieren: höchstens 64 Einträge.
        for (int i = 1; i < n; i++) {
            int v = o[i];
            int j = i - 1;
            while (j >= 0 && g[o[j]] > g[v]) {
                o[j + 1] = o[j];
                j--;
            }
            o[j + 1] = v;
        }
        return o;
    }

    /**
     * Geometrische Kosten eines Platzes ohne Blockabfragen; unendlich = verboten. threat != null: Ausweichen vor einem
     * Laufenden – dann zählt kein Wohlfühlabstand (er ist gleich vorbei), aber sie geht ihm nie entgegen.
     */
    static double geo(State s, double cx, double cz, double x, double z0, double y, List<Person> people,
            Person owner, Person ref, Person threat) {
        double cost = MOVE_WEIGHT * Math.hypot(cx - x, cz - z0);
        for (Person p : people) {
            if (Math.abs(p.y() - y) > 2.5) {
                continue;
            }
            // Nicht neben ihm stehen und nicht durch ihn hindurch laufen (erste Fassung im Test: Platz HINTER dem
            // Spieler, quer durch ihn – die Bahn reicht nur nach vorn).
            if (Math.hypot(cx - p.x(), cz - p.z()) < PERSONAL || segDist(p.x(), p.z(), x, z0, cx, cz) < 0.9) {
                return INF;
            }
        }
        if (!tubeFree(cx, cz, people, y)) {
            return INF;
        }
        if (threat != null) {
            double sp = threat.speed();
            if (((cx - x) * threat.vx() + (cz - z0) * threat.vz()) / sp < -0.5) {
                return INF;
            }
        }
        if (owner != null) {
            double r = Math.hypot(cx - owner.x(), cz - owner.z());
            if (threat == null) {
                cost += 2.0 * Math.max(0, COMFORT_MIN - r) + 1.0 * Math.max(0, r - COMFORT_MAX);
            }
            double ll = Math.hypot(owner.lookX(), owner.lookZ());
            if (!owner.talking() && r > 1.0e-6 && ll > 1.0e-6) {
                double cos = ((cx - owner.x()) * owner.lookX() + (cz - owner.z()) * owner.lookZ()) / (r * ll);
                if (cos > VIEW_FREE) {
                    cost += VIEW_WEIGHT * (cos - VIEW_FREE) / (1 - VIEW_FREE);
                }
            }
        }
        // Seite halten: Steht sie seitlich, wechselt sie nie vor ihm über seine Bahn. Genau davor: seine Linke
        // bevorzugt (+1 für seine Rechte), wie zwei, die sich begegnen und beide rechts gehen.
        if (ref != null) {
            int side = sideOf(ref, cx, cz);
            if (s.side != 0 && side == -s.side) {
                // Vor einem Stehenden darf sie die Seite wechseln, es kostet nur etwas.
                if (ref.speed() >= MOVING) {
                    return INF;
                }
                cost += 0.5;
            }
            if (s.side == 0 && side > 0) {
                cost += 1.0;
            }
        }
        return cost;
    }

    /**
     * Gang ohne Platz: steht sie schon NICHE neben seiner Bahn, bleibt sie. Sonst eine Nische neben ihr, sonst bis
     * LEAD_STEPS Felder in seiner Laufrichtung voraus und dort wieder eine Nische. Ergebnis {x, y, z, voraus 0/1};
     * null = Sackgasse (tauschen).
     */
    static double[] lead(CompanionPath.Blocks w, double x, double y, double z0, Person threat) {
        double sp = threat.speed();
        double dx = threat.vx() / sp;
        double dz = threat.vz() / sp;
        if (Math.abs((x - threat.x()) * dz - (z0 - threat.z()) * dx) >= NICHE) {
            return new double[] {x, y, z0, 0};
        }
        int cx = (int) Math.floor(x);
        int cy = (int) Math.floor(y + 0.01);
        int cz = (int) Math.floor(z0);
        for (int step = 0; step <= LEAD_STEPS; step++) {
            for (int[] d : N4) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                int ny = standY(w, nx, cy, nz);
                if (ny == Integer.MIN_VALUE) {
                    continue;
                }
                double px = nx + 0.5;
                double pz = nz + 0.5;
                double off = Math.abs((px - threat.x()) * dz - (pz - threat.z()) * dx);
                double along = (px - threat.x()) * dx + (pz - threat.z()) * dz;
                if (off >= NICHE && along > 0.5 && spotCost(w, Zones.NONE, nx, ny, nz) < INF) {
                    return new double[] {px, ny, pz, step > 0 ? 1 : 0};
                }
            }
            double here = (cx + 0.5 - threat.x()) * dx + (cz + 0.5 - threat.z()) * dz;
            int bx = 0;
            int bz = 0;
            int byy = 0;
            double bestDot = 0.3;
            for (int[] d : N8) {
                int nx = cx + d[0];
                int nz = cz + d[1];
                int ny = standY(w, nx, cy, nz);
                if (ny == Integer.MIN_VALUE) {
                    continue;
                }
                double dot = (d[0] * dx + d[1] * dz) / Math.hypot(d[0], d[1]);
                double ahead = (nx + 0.5 - threat.x()) * dx + (nz + 0.5 - threat.z()) * dz;
                if (dot > bestDot && ahead > here) {
                    bestDot = dot;
                    bx = nx;
                    bz = nz;
                    byy = ny;
                }
            }
            if (bestDot <= 0.3) {
                return step >= 2 ? new double[] {cx + 0.5, cy, cz + 0.5, 1} : null;
            }
            cx = bx;
            cz = bz;
            cy = byy;
        }
        // Keine Nische in LEAD_STEPS Feldern: wie eine Sackgasse tauschen. Vorher lief sie weiter vor ihm her, kaum
        // schneller als er – WegProbe 2026-11-15 im 1 breiten Gang mit Umkehr: 10 Berührungen und 22 Aufrufe im Weg
        // statt 4 und 6 beim alten Stand.
        return null;
    }
}
