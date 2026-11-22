package com.blackn0va.baumeister.town;

import com.blackn0va.baumeister.companion.CompanionPath;

import java.util.List;

/**
 * Weg eines Bewohners zu seinem Ziel: Wegsuche wie bei Vera (CompanionPath, A*), aber gedrosselt über eine
 * Erlaubnis, die der Aufrufer je Tick vergibt (Vertrag v008, AK-25: „nicht jeder Bewohner jeden Tick“).
 *
 * Unterschied zu CompanionFollow.Planner: Der plant, sobald 5 Ticks seit dem letzten Plan vergangen sind – bei Vera
 * (einer Entität) richtig, bei 12 Bewohnern im selben Tick wären es bis zu 12 Suchen à 900 Knoten (gemessen bis
 * 1,7 ms je Suche, Gedächtnis „Wegsuche 2026-11-06“). Hier plant ein Bewohner nur, wenn er mayPlan bekommt; TownLife
 * gibt je Tick höchstens eine Erlaubnis aus, beim Bau mit Lag-Wache seltener.
 *
 * Ziele weiter als CompanionPath.RANGE: Der Aufrufer reicht ein Zwischenziel (höchstens LEG Blöcke weit, auf der
 * Oberfläche); ist das erreicht, kommt das nächste. Ohne Weg läuft der Bewohner geradeaus (CompanionWalk springt und
 * teleportiert erst nach 40 Ticks Feststecken).
 *
 * Threads: Hauptthread, je Bewohner eine Route. Fehler: keine.
 */
public final class Route {

    /** Höchste Länge eines Teilstücks (Blöcke), unter CompanionPath.RANGE (24), damit die Suche das Ziel erreicht. */
    public static final double LEG = 14.0;
    /** Neu planen, wenn so viele Ticks festgehangen. */
    static final int REPLAN_STUCK = 10;
    /** Weg spätestens nach so vielen Ticks neu planen (Spieler oder Tür im Weg). */
    static final int REPLAN_AGE = 100;

    private List<CompanionPath.Node> path;
    private int index;
    private CompanionPath.Node goal;
    private long planned = Long.MIN_VALUE / 2;
    private int searches;

    /**
     * Höchstens eine Suche je MIN_GAP Ticks und Route, außer nach reset() (neues Ziel, Teleport). Paper-Probe
     * 2026-11-21: Ein festhängender Bewohner suchte ~400 Mal je Minute, weil „festgehangen“ alle 10 Ticks neu
     * planen ließ und die Erlaubnis je Tick reihum immer wieder bei ihm landete. Jetzt höchstens 60 je Minute.
     */
    static final int MIN_GAP = 20;

    /** Will diese Route in diesem Tick neu planen? */
    public boolean wantsPlan(double gx, double gy, double gz, long tick, int stuck) {
        if (path != null && tick - planned < MIN_GAP) {
            return false;
        }
        CompanionPath.Node g = node(gx, gy, gz);
        boolean moved = goal == null || Math.abs(goal.x() - g.x()) + Math.abs(goal.y() - g.y())
                + Math.abs(goal.z() - g.z()) > 1;
        return path == null || moved || stuck >= REPLAN_STUCK || tick - planned > REPLAN_AGE;
    }

    /**
     * Nächster Punkt, auf den der Bewohner zuläuft (Füße). mayPlan = diese Route darf jetzt suchen (eine Erlaubnis aus
     * dem Tick-Kontingent); sonst geht es auf dem alten Weg oder geradeaus weiter.
     */
    public double[] next(CompanionPath.Blocks w, double x, double y, double z, double gx, double gy, double gz,
            long tick, int stuck, boolean mayPlan) {
        if (mayPlan && wantsPlan(gx, gy, gz, tick, stuck)) {
            path = CompanionPath.find(w, node(x, y, z), node(gx, gy, gz));
            index = 0;
            goal = node(gx, gy, gz);
            planned = tick;
            searches++;
        }
        if (path != null && path.size() >= 2) {
            index = CompanionPath.advance(path, index, x, y, z);
            if (index < path.size() - 1) {
                CompanionPath.Node n = path.get(index);
                return new double[] {n.x() + 0.5, n.y(), n.z() + 0.5};
            }
        }
        return new double[] {gx, gy, gz};
    }

    /**
     * Restweg entlang des geplanten Wegs ab (x, y, z): Abstand zum nächsten Wegpunkt plus die Strecke der übrigen;
     * NaN ohne Weg. Fortschritt (Progress) misst TownLife daran statt an der Luftlinie zum Teilziel – vorher galt ein
     * Bewohner, der um eine U-förmige Mauer herumging, nach 3 s als festgesteckt, weil die Luftlinie wuchs.
     */
    public double remaining(double x, double y, double z) {
        if (path == null || path.size() < 2 || index >= path.size() - 1) {
            return Double.NaN;
        }
        CompanionPath.Node n = path.get(index);
        double sum = Math.hypot(Math.hypot(n.x() + 0.5 - x, n.z() + 0.5 - z), n.y() - y);
        for (int i = index + 1; i < path.size(); i++) {
            CompanionPath.Node a = path.get(i - 1);
            CompanionPath.Node b = path.get(i);
            sum += Math.sqrt((a.x() - b.x()) * (a.x() - b.x()) + (a.y() - b.y()) * (a.y() - b.y())
                    + (a.z() - b.z()) * (a.z() - b.z()));
        }
        return sum;
    }

    /** Hat die letzte Suche einen Weg gefunden (mindestens zwei Punkte)? */
    public boolean hasPath() {
        return path != null && path.size() >= 2;
    }

    /** Nach Teleport oder Zielwechsel: neu planen. */
    public void reset() {
        path = null;
        goal = null;
        planned = Long.MIN_VALUE / 2;
    }

    /** Wie viele Suchen diese Route gemacht hat (Messung). */
    public int searches() {
        return searches;
    }

    private static CompanionPath.Node node(double x, double y, double z) {
        return new CompanionPath.Node((int) Math.floor(x), (int) Math.floor(y + 0.01), (int) Math.floor(z));
    }

    /**
     * Zwischenziel auf der Geraden von (x, z) nach (gx, gz), höchstens LEG Blöcke weit; {gx, gz}, wenn das Ziel näher
     * liegt. Die Höhe setzt der Aufrufer (Oberfläche, nur in geladenen Chunks).
     */
    public static double[] leg(double x, double z, double gx, double gz) {
        double dx = gx - x;
        double dz = gz - z;
        double d = Math.hypot(dx, dz);
        if (d <= LEG) {
            return new double[] {gx, gz};
        }
        return new double[] {x + dx / d * LEG, z + dz / d * LEG};
    }
}
