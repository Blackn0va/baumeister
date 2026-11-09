package com.blackn0va.baumeister.companion;

/**
 * Eingefroren: CompanionWalk wie auf dev01 (Stand decf2dc, 2026-11-07) – Vergleichsfassung „jetzt“ für SwimProbe.
 * Im Wasser nur „+0,04 je Tick nach oben, außer das Ziel liegt tiefer“ und kein Sprung im Wasser.
 */
public final class WalkDeployed {

    /** Normales Tempo: 4,4 Blöcke/s, etwas schneller als ein gehender Spieler (4,3). */
    public static final double SPEED = 0.22;
    /** Volles Aufholtempo ab CATCH_UP_FROM Blöcken vom Ziel (speedFor): 6,8 Blöcke/s, schneller als Sprinten (5,6). */
    public static final double CATCH_UP = 0.34;
    public static final double CATCH_UP_FROM = 5.0;
    /** Im Kampf an das Monster heran. */
    public static final double FIGHT = 0.32;
    /** Sprungkraft wie ein Spieler (jump_strength des Mannequins, gemessen 0,42). */
    public static final double JUMP = 0.42;
    /** Auftrieb je Tick im Wasser (gemessen: hält sie an der Oberfläche). */
    public static final double SWIM_UP = 0.04;
    static final double MAX_RISE = 0.3;
    /** So nah gilt das Ziel als erreicht. */
    public static final double ARRIVE = 0.4;
    /** Weiter weg wird teleportiert statt gelaufen (andere Welt, Reittier, Enderperle). */
    public static final double TELEPORT_FROM = 20.0;
    /** So viele Ticks ohne Fortschritt, dann Teleport (Loch, zu hohe Mauer, Zaun). */
    public static final int STUCK_TICKS = 40;
    /** Weniger Weg in einem Tick gilt als „kommt nicht weiter“. */
    static final double MOVED_MIN = 0.03;

    /** Lage und Bewegung des Begleiters in diesem Tick. vy = aktuelle senkrechte Geschwindigkeit. */
    /** climbing = steht in Leiter, Ranke oder Gerüst. */
    public record Body(double x, double y, double z, double vy, boolean onGround, boolean inWater,
            boolean climbing) {

        public Body(double x, double y, double z, double vy, boolean onGround, boolean inWater) {
            this(x, y, z, vy, onGround, inWater, false);
        }
    }

    /** Steiggeschwindigkeit an Leitern wie im Spiel (LivingEntity: 0,2 je Tick bei Kollision mit der Wand). */
    public static final double CLIMB = 0.2;

    /** Neue Geschwindigkeit; teleport = stattdessen auf das Ziel setzen; arrived = steht am Ziel. */
    public record Step(double vx, double vy, double vz, boolean teleport, boolean arrived) {

        static final Step TELEPORT = new Step(0, 0, 0, true, false);

        public boolean moving() {
            return Math.abs(vx) + Math.abs(vz) > 1.0e-3;
        }
    }

    /** Gedächtnis eines Begleiters von Tick zu Tick. */
    public static final class State {
        private double lastX = Double.NaN;
        private double lastZ;
        private double lastY;
        private boolean pushed;
        private int stuck;

        public void reset() {
            lastX = Double.NaN;
            pushed = false;
            stuck = 0;
        }

        public int stuck() {
            return stuck;
        }
    }

    private WalkDeployed() {
    }

    /**
     * Tempo nach Abstand zum Ziel: bis 2 Blöcke normal, dann gleitend schneller, ab CATCH_UP_FROM volles Aufholtempo.
     * Vorher sprang es erst bei 6 Blöcken auf Aufholen – im Gelände (Hügel, Sprünge) hing Vera dauerhaft 6–9 Blöcke
     * zurück (FollowProbe Natur-Welt 2026-11-07), weil sie knapp unter der Schwelle nur so schnell lief wie der Spieler.
     */
    public static double speedFor(double distance) {
        return Math.min(CATCH_UP, Math.max(SPEED, SPEED + (distance - 2) * (CATCH_UP - SPEED) / (CATCH_UP_FROM - 2)));
    }

    /** Ein Tick: wohin mit welcher Geschwindigkeit, oder teleportieren. */
    public static Step next(State s, Body b, double gx, double gy, double gz, double speed) {
        double dx = gx - b.x();
        double dz = gz - b.z();
        double dy = gy - b.y();
        double h = Math.hypot(dx, dz);
        if (h * h + dy * dy > TELEPORT_FROM * TELEPORT_FROM) {
            s.reset();
            return Step.TELEPORT;
        }
        // An der Leiter zählt Steigen als Fortschritt; sonst nur waagrecht (ein Springen auf der Stelle vor einer Mauer
        // darf kein Fortschritt sein, sonst käme nie der Teleport).
        boolean moved = Double.isNaN(s.lastX) || Math.hypot(b.x() - s.lastX, b.z() - s.lastZ) >= MOVED_MIN
                || b.climbing() && Math.abs(b.y() - s.lastY) >= MOVED_MIN;
        boolean blocked = s.pushed && !moved;
        s.lastX = b.x();
        s.lastZ = b.z();
        s.lastY = b.y();

        double vy = b.vy();
        // Im Wasser nach oben, außer das Ziel liegt deutlich tiefer (Spieler taucht) – sonst sänke sie auf den Grund.
        if (b.inWater() && dy > -0.5) {
            vy = Math.min(MAX_RISE, b.vy() + SWIM_UP);
        }
        if (h < ARRIVE && Math.abs(dy) < 1.3) {
            s.pushed = false;
            s.stuck = 0;
            return new Step(0, vy, 0, false, true);
        }
        // Direkt unter/über dem Ziel, aber nicht oben: zählt wie festhängen (Spieler auf einem Turm).
        // Steigt sie an der Leiter, ist „direkt darunter“ Fortschritt, kein Festhängen (sonst Teleport mitten im Klettern).
        s.stuck = blocked || h < ARRIVE && !(b.climbing() && moved) ? s.stuck + 1 : 0;
        if (s.stuck >= STUCK_TICKS) {
            s.reset();
            return Step.TELEPORT;
        }
        double v = Math.min(speed, h);
        double vx = h < 1.0e-6 ? 0 : dx / h * v;
        double vz = h < 1.0e-6 ? 0 : dz / h * v;
        // Springen, wenn sie am Boden gegen etwas läuft (volle Blöcke: step_height reicht nur bis 0,6).
        // Vorher erst NACH dem Anstoßen (blocked): Bergauf kostete jede Stufe einen Tick Stillstand. Jetzt auch,
        // wenn der nächste Punkt höher liegt und nah ist (Wegpunkte aus CompanionPath liegen je einen Block weit).
        if (b.onGround() && !b.inWater() && !b.climbing() && (blocked || dy > 0.6 && h < 1.3)) {
            vy = JUMP;
        }
        // Leiter: hoch oder runter je nach Ziel, waagrecht kaum (sonst rutscht sie von der Sprosse).
        if (b.climbing() && Math.abs(dy) > 0.3 && h < 1.0) {
            vy = dy > 0 ? CLIMB : -0.15;
            vx *= 0.3;
            vz *= 0.3;
        }
        s.pushed = v > 2 * MOVED_MIN;
        return new Step(vx, vy, vz, false, false);
    }
}
