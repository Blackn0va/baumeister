package com.blackn0va.baumeister.town.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Sucht im Feld die nächste Arbeit für einen Beruf (AK-43). Liest nur (Plot), ändert nichts.
 *
 * - Bauer: reife Feldfrucht (Stufe = höchste) auf Ackerboden → abbauen, Saat derselben Sorte setzen.
 * - Holzfäller: natürlicher Baum → alle Stämme von unten nach oben, dann Setzlinge auf jeden Fuß. Natürlich heißt:
 *   mindestens MIN_LEAVES natürliche Blätter (persistent=false) am Stamm, kein vom Spieler gesetztes Laub, kein
 *   fremder Block am Stamm (Bretter, Glas, Fackel …), alle Stämme im Feld, höchstens MAX_LOGS. Spielerbauten aus Holz
 *   fallen so heraus: Ein Stamm in einer Hauswand berührt Bretter oder Glas, ein Holzstapel hat kein natürliches Laub.
 *   Das Laub fällt danach von selbst (Vanilla: natürliches Laub ohne Stamm in 6 Blöcken zerfällt) – so kostet es keinen
 *   Platz in der Rate und lässt wie im Spiel Setzlinge und Äpfel liegen.
 * - Gärtner: Trampelpfad als Kreuz durch die Feldmitte (Grasblock → Pfad), Blumen auf dem Ring einen Block innerhalb
 *   des Randes, jede dritte Stelle. Klein und immer gleich, damit er nach einmal Anlegen nur noch Lücken füllt.
 *
 * Gesucht wird spaltenweise ab cursor, höchstens COLUMNS Spalten je Aufruf (bei Radius 24 sind es 2401 Spalten, je
 * bis 21 Höhen) – TownLife ruft je Takt höchstens einmal.
 *
 * Threads: Hauptthread. Fehler: keine.
 */
public final class Planner {

    static final int COLUMNS = 48;
    static final int MAX_LOGS = 48;
    static final int MIN_LEAVES = 3;

    /** Was an einem Stamm stehen darf, ohne dass er als Spielerbau gilt (außer Stamm, Laub und Boden). */
    static final Set<String> NEAR_TREE = Set.of("air", "cave_air", "vine", "short_grass", "tall_grass", "fern",
            "large_fern", "snow", "cocoa", "moss_carpet", "dandelion", "poppy", "bee_nest", "pink_petals",
            "leaf_litter", "firefly_bush", "bush", "short_dry_grass", "tall_dry_grass");

    /** Ergebnis: die Arbeit (oder null) und wo die nächste Suche weitermacht. */
    public record Found(Task task, int cursor) {
    }

    private Planner() {
    }

    /** claimed = Block schon von einem anderen Bewohner belegt oder gesperrt (abgelehnt). */
    public static Found find(Plot p, Field f, FieldJob job, int cursor, LongPredicate claimed) {
        int n = f.columns();
        for (int k = 0; k < Math.min(COLUMNS, n); k++) {
            int i = Math.floorMod(cursor + k, n);
            int[] c = f.column(i);
            Task t = switch (job) {
                case FARMER -> crop(p, f, c[0], c[1]);
                case LUMBERJACK -> tree(p, f, c[0], c[1]);
                case GARDENER -> garden(p, f, c[0], c[1]);
            };
            if (t != null && !claimed.test(t.key()) && safe(p, t)) {
                return new Found(t, i + 1);
            }
        }
        return new Found(null, Math.floorMod(cursor + Math.min(COLUMNS, n), n));
    }

    /** Stufe 1 und 2 aus Safety für jede Änderung – der Planer schlägt nie vor, was FieldWork ablehnen müsste. */
    static boolean safe(Plot p, Task t) {
        for (Task.Change c : t.changes()) {
            if (!Safety.allowed(c) || Safety.nearForbidden(p, c.x(), c.y(), c.z())) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Bauer

    private static Task crop(Plot p, Field f, int x, int z) {
        for (int y = f.y() + 2; y >= f.y() - Field.DOWN; y--) {
            String t = p.type(x, y, z);
            if (!Safety.CROPS.contains(t)) {
                continue;
            }
            int max = p.maxAge(x, y, z);
            if (max < 0 || p.age(x, y, z) < max || !p.type(x, y - 1, z).equals("farmland")) {
                return null;
            }
            return new Task(FieldJob.FARMER, x, y, z, List.of(new Task.Change(Task.Kind.BREAK, x, y, z, t, "air"),
                    new Task.Change(Task.Kind.PLACE, x, y, z, "air", t)));
        }
        return null;
    }

    // ---------------------------------------------------------------- Holzfäller

    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** Baum, dessen Fuß in dieser Spalte steht (Stamm auf Boden), oder null. Von oben gesucht, dann bis zum Fuß. */
    private static Task tree(Plot p, Field f, int x, int z) {
        for (int y = f.y() + 3; y >= f.y() - Field.DOWN; y--) {
            String t = p.type(x, y, z);
            if (!Safety.log(t)) {
                continue;
            }
            int base = y;
            while (base - 1 >= f.y() - Field.DOWN && p.type(x, base - 1, z).equals(t)) {
                base--;
            }
            if (!Safety.GROUND.contains(p.type(x, base - 1, z))) {
                return null;
            }
            return fell(p, f, x, base, z, Safety.wood(t));
        }
        return null;
    }

    static Task fell(Plot p, Field f, int x, int y, int z, String wood) {
        String logType = wood + "_log";
        List<int[]> logs = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> open = new ArrayDeque<>();
        open.add(new int[] {x, y, z});
        seen.add(key(x, y, z));
        int natural = 0;
        while (!open.isEmpty()) {
            int[] b = open.removeFirst();
            if (!f.contains(f.world(), b[0], b[1], b[2])) {
                return null;
            }
            logs.add(b);
            if (logs.size() > MAX_LOGS) {
                return null;
            }
            for (int[] d : SIX) {
                int nx = b[0] + d[0];
                int ny = b[1] + d[1];
                int nz = b[2] + d[2];
                String t = p.type(nx, ny, nz);
                if (Safety.leaves(t)) {
                    if (p.persistent(nx, ny, nz)) {
                        return null;
                    }
                    natural++;
                } else if (!t.equals(logType) && !Safety.log(t) && !NEAR_TREE.contains(t)
                        && !Safety.GROUND.contains(t)) {
                    return null;
                }
            }
            // Äste: Stämme auch schräg (Akazie, Eiche mit Ästen), nur dieselbe Holzart.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = b[0] + dx;
                        int ny = b[1] + dy;
                        int nz = b[2] + dz;
                        if (p.type(nx, ny, nz).equals(logType) && seen.add(key(nx, ny, nz))) {
                            open.add(new int[] {nx, ny, nz});
                        }
                    }
                }
            }
        }
        if (natural < MIN_LEAVES) {
            return null;
        }
        logs.sort(Comparator.<int[]>comparingInt(b -> b[1]).thenComparingInt(b -> b[0]).thenComparingInt(b -> b[2]));
        int low = logs.getFirst()[1];
        List<Task.Change> out = new ArrayList<>();
        List<int[]> feet = new ArrayList<>();
        for (int[] b : logs) {
            out.add(new Task.Change(Task.Kind.BREAK, b[0], b[1], b[2], logType, "air"));
            if (b[1] == low && Safety.GROUND.contains(p.type(b[0], b[1] - 1, b[2]))) {
                feet.add(b);
            }
        }
        for (int[] b : feet) {
            out.add(new Task.Change(Task.Kind.PLACE, b[0], b[1], b[2], "air", wood + "_sapling"));
        }
        int[] stand = besides(p, x, y, z);
        return new Task(FieldJob.LUMBERJACK, stand[0], stand[1], stand[2], out);
    }

    /** Erst die acht Nachbarn, dann der Ring zwei Blöcke weit. */
    private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1},
        {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 1}, {2, -1}, {-2, 1}, {-2, -1}, {1, 2}, {-1, 2}, {1, -2}, {-1, -2},
        {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};

    /**
     * Stehplatz neben (x, y, z): Füße und Kopf frei, darunter fest – bis zwei Blöcke weit, sonst der Block selbst.
     * Vorher nur die acht Nachbarn: Unter tiefem Laub (Eiche, gemessen live T-035) war keiner frei, der Stehplatz war
     * der Stamm selbst, die Wegsuche fand keinen Weg dorthin, und der Holzfäller wackelte davor.
     */
    private static int[] besides(Plot p, int x, int y, int z) {
        for (int[] d : AROUND) {
            int sx = x + d[0];
            int sz = z + d[1];
            if (open(p.type(sx, y, sz)) && open(p.type(sx, y + 1, sz)) && !open(p.type(sx, y - 1, sz))
                    && !p.type(sx, y - 1, sz).equals("water")) {
                return new int[] {sx, y, sz};
            }
        }
        return new int[] {x, y, z};
    }

    private static boolean open(String t) {
        return t.equals("air") || t.equals("cave_air") || t.equals("short_grass") || Safety.CROPS.contains(t)
                || Safety.FLOWERS.contains(t);
    }

    // ---------------------------------------------------------------- Gärtner

    private static Task garden(Plot p, Field f, int x, int z) {
        int dx = x - f.x();
        int dz = z - f.z();
        boolean path = (dx == 0 || dz == 0) && Math.max(Math.abs(dx), Math.abs(dz)) <= f.radius() - 1;
        boolean flower = !path && Math.max(Math.abs(dx), Math.abs(dz)) == f.radius() - 1
                && Math.floorMod(dx + dz, 3) == 0;
        if (!path && !flower) {
            return null;
        }
        for (int y = f.y() + 2; y >= f.y() - Field.DOWN; y--) {
            String t = p.type(x, y, z);
            if (t.equals("air") || t.equals("cave_air")) {
                continue;
            }
            if (!t.equals("grass_block") || !p.type(x, y + 1, z).equals("air")) {
                return null;
            }
            int[] stand = besides(p, x, y + 1, z);
            if (path) {
                return new Task(FieldJob.GARDENER, stand[0], stand[1], stand[2],
                        List.of(new Task.Change(Task.Kind.PLACE, x, y, z, "grass_block", "dirt_path")));
            }
            List<String> kinds = List.of("poppy", "dandelion", "cornflower", "oxeye_daisy");
            String kind = kinds.get(Math.floorMod(dx * 7 + dz * 3, kinds.size()));
            return new Task(FieldJob.GARDENER, stand[0], stand[1], stand[2],
                    List.of(new Task.Change(Task.Kind.PLACE, x, y + 1, z, "air", kind)));
        }
        return null;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
    }
}
