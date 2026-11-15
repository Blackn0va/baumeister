package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Wegsuche (A*) für Vera über Blöcke: um Mauern herum, eine Stufe hoch (Sprung), bis drei Blöcke hinunter, durch
 * Wasser. CompanionWalk läuft dann von Wegpunkt zu Wegpunkt statt geradeaus.
 *
 * Vorher (bis 2026-11-06) lief sie in gerader Linie aufs Ziel und teleportierte nach 40 Ticks Festhängen – gemessen
 * vor einer 4 hohen Mauer: Teleport nach Tick 52 (probes/mannequin). Mit Weg geht sie außen herum; der Teleport bleibt
 * Rückfall, wenn kein Weg gefunden wird (Ziel auf einem Turm, Grube ohne Ausgang).
 *
 * Grenzen, damit die Suche im Hauptthread billig bleibt: höchstens MAX_NODES Knoten, höchstens RANGE Blöcke vom Start.
 * Bewegungsregeln wie gemessen: Stufe 0,6 von selbst, voller Block mit Sprung (also +1), zwei Blöcke Luft über den
 * Füßen (Spielergröße 1,8), fallen ohne Schaden (setInvulnerable) – 3 Blöcke, damit sie nicht in Schluchten springt.
 *
 * Threads: zustandslos (World wird je Suche übergeben). Fehler: kein Weg → leere Liste.
 */
public final class CompanionPath {

    static final int MAX_NODES = 900;
    static final int RANGE = 24;
    static final int MAX_DROP = 3;
    /**
     * Ab diesem waagrechten Abstand gilt ein Wegpunkt als erreicht. Vorher 0,7: An Kurven auf 1 breiten Stegen bog sie
     * so früh ab, dass sie die Kante schnitt. Muss über CompanionWalk.ARRIVE (0,4) bleiben, sonst hält sie an jedem
     * Punkt an.
     */
    static final double REACHED = 0.55;

    /** Blickwinkel auf die Welt – im Plugin Bukkit-Blöcke, im Test ein Gitter. */
    public interface Blocks {
        /** Trägt (man kann darauf stehen). */
        boolean solid(int x, int y, int z);

        /** Man kann hindurch (Luft, Gras, offene Tür, Wasser). Lava und Feuer gelten als nicht passierbar. */
        boolean passable(int x, int y, int z);

        boolean water(int x, int y, int z);

        /** Leiter, Ranke, Gerüst: hier kann sie senkrecht klettern. */
        default boolean climbable(int x, int y, int z) {
            return false;
        }

        /** Tür, Falltür oder Tor (offen oder zu): dort bleibt Vera nicht stehen (CompanionYield). */
        default boolean doorway(int x, int y, int z) {
            return false;
        }

        /** Treppenstufe: dort bleibt Vera nicht stehen (CompanionYield). */
        default boolean stairs(int x, int y, int z) {
            return false;
        }
    }

    public record Node(int x, int y, int z) {
    }

    private CompanionPath() {
    }

    static boolean standable(Blocks w, int x, int y, int z) {
        return w.passable(x, y, z) && w.passable(x, y + 1, z) && (w.solid(x, y - 1, z) || w.water(x, y, z)
                || w.water(x, y - 1, z) || w.climbable(x, y, z) || w.climbable(x, y - 1, z));
    }

    /**
     * Weg von start zu goal (Fußblöcke). Angekommen gilt, wer höchstens 1 Block waagrecht und 1 senkrecht vom Ziel
     * entfernt steht. Leer, wenn kein Weg innerhalb der Grenzen.
     */
    public static List<Node> find(Blocks w, Node start, Node goal) {
        if (!standable(w, start.x(), start.y(), start.z())) {
            // Steht sie schief (halber Block, im Sprung): eine Höhe darüber versuchen.
            if (standable(w, start.x(), start.y() + 1, start.z())) {
                start = new Node(start.x(), start.y() + 1, start.z());
            }
        }
        Map<Node, Node> from = new HashMap<>();
        Map<Node, Double> cost = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[1], (double) b[1]));
        cost.put(start, 0.0);
        open.add(new Object[] {start, h(start, goal)});
        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node n = (Node) open.poll()[0];
            if (Math.abs(n.x() - goal.x()) <= 1 && Math.abs(n.z() - goal.z()) <= 1 && Math.abs(n.y() - goal.y()) <= 1) {
                return path(from, n);
            }
            expanded++;
            double base = cost.get(n);
            for (Object[] step : neighbours(w, n)) {
                Node m = (Node) step[0];
                if (Math.abs(m.x() - start.x()) > RANGE || Math.abs(m.z() - start.z()) > RANGE) {
                    continue;
                }
                double c = base + (double) step[1];
                Double known = cost.get(m);
                if (known == null || c < known) {
                    cost.put(m, c);
                    from.put(m, n);
                    open.add(new Object[] {m, c + h(m, goal)});
                }
            }
        }
        return List.of();
    }

    private static double h(Node a, Node b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Node> path(Map<Node, Node> from, Node end) {
        List<Node> out = new ArrayList<>();
        for (Node n = end; n != null; n = from.get(n)) {
            out.add(n);
        }
        Collections.reverse(out);
        return out;
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Schritte von n: {Knoten, Kosten}. Schräg nur, wenn beide geraden Nachbarn frei sind (keine Ecken schneiden). */
    private static List<Object[]> neighbours(Blocks w, Node n) {
        List<Object[]> out = new ArrayList<>();
        // Leiter: senkrecht hoch und runter (gemessen: sie klettert mit 0,12–0,2 je Tick, probes/mannequin).
        if (w.climbable(n.x(), n.y(), n.z()) && standable(w, n.x(), n.y() + 1, n.z())) {
            out.add(new Object[] {new Node(n.x(), n.y() + 1, n.z()), 1.5});
        }
        if (w.climbable(n.x(), n.y() - 1, n.z()) && standable(w, n.x(), n.y() - 1, n.z())) {
            out.add(new Object[] {new Node(n.x(), n.y() - 1, n.z()), 1.2});
        }
        for (int[] d : DIRS) {
            int x = n.x() + d[0];
            int z = n.z() + d[1];
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && !(clear(w, n.x() + d[0], n.y(), n.z()) && clear(w, n.x(), n.y(), n.z() + d[1]))) {
                continue;
            }
            double step = diagonal ? 1.414 : 1.0;
            double wet = w.water(x, n.y(), z) ? 1.0 : 0.0;
            if (standable(w, x, n.y(), z)) {
                out.add(new Object[] {new Node(x, n.y(), z), step + wet});
                continue;
            }
            // Eine Stufe hoch: Kopffreiheit über sich für den Sprung, oben zwei Blöcke Luft. Nicht schräg springen.
            if (!diagonal && w.passable(n.x(), n.y() + 2, n.z()) && standable(w, x, n.y() + 1, z)) {
                out.add(new Object[] {new Node(x, n.y() + 1, z), step + 0.6});
                continue;
            }
            // Hinunter: Spalte frei, erster tragender Platz bis MAX_DROP tiefer.
            if (clear(w, x, n.y(), z)) {
                for (int drop = 1; drop <= MAX_DROP; drop++) {
                    if (!w.passable(x, n.y() - drop, z) && !standable(w, x, n.y() - drop, z)) {
                        break;
                    }
                    if (standable(w, x, n.y() - drop, z)) {
                        out.add(new Object[] {new Node(x, n.y() - drop, z), step + 0.3 * drop});
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Nächster anzusteuernder Wegpunkt: Wer einem Punkt näher als REACHED waagrecht (und 1,2 senkrecht) ist, nimmt den
     * nächsten – so bleibt sie an Zwischenpunkten nicht stehen (CompanionWalk hält erst bei 0,4 an).
     */
    public static int advance(List<Node> path, int index, double x, double y, double z) {
        // Bis zu 3 Punkte vorausschauen: Ist sie an einem schon vorbei (Stoß, Sprung, Strömung), nicht zurücklaufen.
        int i = Math.max(0, index);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int j = i; j < Math.min(path.size() - 1, i + 3); j++) {
                Node n = path.get(j);
                double h = Math.hypot(n.x() + 0.5 - x, n.z() + 0.5 - z);
                if (h < REACHED && Math.abs(n.y() - y) < 1.2) {
                    i = j + 1;
                    changed = true;
                }
            }
        }
        return Math.min(i, path.size() - 1);
    }

    /** Füße und Kopf frei (aber nicht unbedingt Boden darunter). */
    private static boolean clear(Blocks w, int x, int y, int z) {
        return w.passable(x, y, z) && w.passable(x, y + 1, z);
    }
}
