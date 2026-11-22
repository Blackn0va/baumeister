package com.blackn0va.baumeister.town;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Treffpunkte (Vertrag v013, AK-42): In der Mittagspause finden sich Bewohner in kleinen Gruppen von MIN bis MAX
 * zusammen, gehen zu einem gemeinsamen Punkt, stellen sich im Kreis auf und bleiben STAY_MIN–STAY_MAX dort.
 *
 * Vorher bummelte jeder für sich 3–8 Blöcke um den nächsten Spieler (alle 20–40 s ein neuer Fleck) – zwei Bewohner
 * redeten nur, wenn sie zufällig auf 4 Blöcke aneinander vorbeikamen. Gespräche selbst sind nicht Sache dieser Klasse:
 * Ist eine Gruppe vollständig, meldet TownLife das über den Einhängepunkt gathered (T-034 baut darauf auf).
 *
 * Reine Rechnung ohne Bukkit; TownLife hält die Gruppen und setzt die Ziele.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class Gathering {

    public static final int MIN = 2;
    public static final int MAX = 4;
    /** Nur Bewohner, die höchstens so weit (waagrecht) vom ersten der Gruppe stehen, kommen dazu. */
    public static final double RANGE = 32.0;
    /** Radius des Kreises um den Treffpunkt: nah genug zum Reden (TownLife.MEET 4), weit genug ohne Gedränge. */
    public static final double SLOT = 1.6;
    public static final long STAY_MIN = 30_000L;
    public static final long STAY_MAX = 60_000L;
    /** Höchstens so lange warten auf Nachzügler; dann reicht MIN. */
    public static final long WAIT_MS = 30_000L;
    /** Abstand zwischen zwei Treffen desselben Bewohners. */
    public static final long AGAIN_MS = 60_000L;

    /** Ein freier Bewohner (gerade nicht in einer Gruppe, folgt niemandem, wartet nicht auf Zuruf). */
    public record Member(int id, double x, double z) {
    }

    private Gathering() {
    }

    /**
     * Gruppen aus freien Bewohnern: Der erste freie (nach id) ist Kern, dazu die nächsten freien in RANGE, bis die
     * Größe erreicht ist (MIN + pick % (MAX − MIN + 1), höchstens so viele, wie da sind). Wer keine MIN
     * zusammenbekommt, bleibt allein (bummelt weiter). Ergebnis: Listen von ids.
     */
    public static List<List<Integer>> groups(List<Member> free, int pick) {
        List<Member> left = new ArrayList<>(free);
        left.sort(Comparator.comparingInt(Member::id));
        List<List<Integer>> out = new ArrayList<>();
        int round = 0;
        while (left.size() >= MIN) {
            Member seed = left.removeFirst();
            int size = MIN + Math.floorMod(pick + round++, MAX - MIN + 1);
            List<Member> near = new ArrayList<>();
            for (Member m : left) {
                if (Math.hypot(m.x() - seed.x(), m.z() - seed.z()) <= RANGE) {
                    near.add(m);
                }
            }
            near.sort(Comparator.comparingDouble((Member m) -> Math.hypot(m.x() - seed.x(), m.z() - seed.z()))
                    .thenComparingInt(Member::id));
            if (near.size() + 1 < MIN) {
                continue;
            }
            List<Integer> g = new ArrayList<>();
            g.add(seed.id());
            for (Member m : near) {
                if (g.size() >= size) {
                    break;
                }
                g.add(m.id());
                left.remove(m);
            }
            out.add(g);
        }
        return out;
    }

    /** Mittelpunkt der Gruppe – der Treffpunkt, bevor TownLife ihn auf einen Stehplatz legt. */
    public static double[] center(List<Member> group) {
        double x = 0;
        double z = 0;
        for (Member m : group) {
            x += m.x();
            z += m.z();
        }
        return new double[] {x / group.size(), z / group.size()};
    }

    /** Platz index von size im Kreis um (cx, cz): gleichmäßig verteilt, jeder mit Blick zur Mitte. */
    public static double[] slot(double cx, double cz, int index, int size) {
        double a = 2 * Math.PI * index / Math.max(1, size);
        return new double[] {cx + Math.cos(a) * SLOT, cz + Math.sin(a) * SLOT};
    }

    /** Ist die Gruppe beisammen? Alle da, oder nach WAIT_MS wenigstens MIN. */
    public static boolean assembled(int arrived, int size, long waited) {
        return arrived >= size || arrived >= MIN && waited >= WAIT_MS;
    }

    /** Wie lange die Gruppe zusammensteht, zufällig aus pick (0–999). */
    public static long stay(int pick) {
        return STAY_MIN + Math.floorMod(pick, 1000) * (STAY_MAX - STAY_MIN) / 1000;
    }
}
