package com.blackn0va.baumeister.structure;

import com.blackn0va.baumeister.plan.BlockRules;
import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.Pos;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rückübersetzer: ein fertiges Bauwerk (Blöcke je Stelle, lokal, aus {@link StructureFile#toLocal}) → Plan-JSON aus
 * wenigen Schritten (fill, walls, cylinder, roof, line, set, door), so wie das LLM ihn schreiben soll.
 *
 * Warum: Echte Bauten von Spielern sollen ins Lehrset des eigenen Modells. Block für Block wären es tausende Schritte
 * – das Modell lernte, endlos zu schreiben (Grundlinie 2026-11-01: schon 8/24 Antworten liefen bis an die
 * Token-Grenze). Als Schritte steht dieselbe Form in einigen Dutzend Zeilen.
 *
 * Verfahren, gierig und nachprüfbar:
 * 1. Gesperrte Blöcke (BlockRules: Betten, hohe Pflanzen, TNT …) fallen weg und werden gezählt.
 * 2. Solange sich ein Schritt lohnt: unter allen Kandidaten – Quader (fill) aus Startzellen gewachsen, Wandringe
 *    (walls) und runde Formen (cylinder, Rohr oder Scheibe, Form aus PlanCompiler.inDisc) je Schicht nach oben und
 *    unten verlängert – den mit dem größten Gewinn nehmen. Gewinn = neu richtige Zellen minus
 *    zerstörte richtige Zellen (×2) minus fremde Zellen, die der Schritt mitschreibt (×0,1; sie werden später
 *    überschrieben). Ein Schritt schreibt NIE in eine Stelle, die im Original Luft ist – sonst stimmte der Rückbau
 *    nicht, und eine Luftlücke müsste wieder herausgeschnitten werden.
 *    Dächer (roof, gable oder hip) treten im selben Wettbewerb an: Aus Treppenreihen, die sich gegenüberstehen (hohe
 *    Seite zur Mitte), entsteht die Grundfläche, und der Kandidat sind genau die Zellen, die PlanCompiler.roof dafür
 *    schreibt (Schleifen hier nachgebildet, verify prüft gegen den echten Compiler). Vorher (bis 2026-11-21) wurden
 *    Dachtreppen zu Dutzenden line-Schritten – das Modell lernte aus Vorbildern nie den roof-Schritt.
 * 3. Was danach noch falsch ist: gleiche Blöcke in Reihe als line, der Rest als set.
 * 4. Türen zuletzt als door (der Compiler setzt beide Hälften).
 *
 * Gegenprobe ist eingebaut: {@link #verify} übersetzt den Plan mit dem echten PlanCompiler zurück und vergleicht
 * Block für Block mit dem Original (beides über BlockStates.normalize). Ins Lehrset darf nur, was dabei übereinstimmt.
 *
 * Threads: zustandslos. Fehler: decompile wirft IllegalArgumentException, wenn das Bauwerk zu groß ist.
 */
public final class PlanDecompiler {

    /** Größer lohnt nicht: Das Lehrset will Pläne bis 40 Schritte, und die Suche wächst mit dem Volumen. */
    public static final int MAX_CELLS = 60_000;
    public static final int MAX_VOLUME = 2_000_000;
    /** Ein Schritt unter diesem Gewinn ist teurer als die Einzelblöcke, die er spart. */
    static final double MIN_GAIN = 6;
    /** Mindestanteil des Hauptmaterials in einer neuen Quaderscheibe bzw. einem Wandring. */
    static final double FILL_SHARE = 0.75;
    static final double RING_SHARE = 0.6;
    static final int SEEDS_PER_BLOCK = 48;
    /** = height maximum in plan-schema.json; ein höherer Zylinder wäre für das Modell nicht schreibbar. */
    static final int MAX_CYLINDER_HEIGHT = 48;
    static final int MAX_PRIMITIVES = 120;
    /** = PlanCompiler.MAX_ROOF_LAYERS (dort privat): Höhere Dächer baut der Compiler nur bis zu dieser Schicht. */
    static final int MAX_ROOF_LAYERS = 24;
    /** Mindestzahl verschiedener Höhen mit Dachtreppen – darunter ist es eine Bank oder Stufe, kein Dach. */
    static final int MIN_ROOF_LAYERS = 2;

    /**
     * @param json     Plan-JSON (design, reply, mode new, steps)
     * @param steps    Anzahl Schritte
     * @param cells    Blöcke des Originals ohne gesperrte
     * @param dropped  gesperrte Blöcke, die weggefallen sind
     * @param ops      Schritte je op
     */
    public record Result(String json, int steps, int cells, int dropped, Map<String, Integer> ops) {
    }

    /** Rückbau-Vergleich: matched von cells richtig, extra = Blöcke, die im Original Luft sind. */
    public record Verify(int cells, int matched, int extra, List<String> warnings, String error) {

        public double share() {
            return cells == 0 ? 0 : (double) matched / cells;
        }

        public boolean exact() {
            return error == null && matched == cells && extra == 0;
        }
    }

    private PlanDecompiler() {
    }

    // ---------------------------------------------------------------- Raster

    /** Dichtes Raster: id je Zelle (0 = Luft), Palette der normalisierten Blöcke. */
    private static final class Grid {
        final int sx;
        final int sy;
        final int sz;
        final int[] target;
        final int[] state;
        final List<BlockSpec> palette = new ArrayList<>(List.of(BlockSpec.of("air")));
        final Map<BlockSpec, Integer> ids = new HashMap<>();
        final int[] wrong;  // je id: Zellen dieser id, die noch nicht richtig stehen
        final int[][] cellsOf;  // je id: Indizes ihrer Zellen (die Suche läuft nur über diese, nicht übers Raster)

        Grid(Map<Pos, BlockSpec> cells) {
            int mx = 0;
            int my = 0;
            int mz = 0;
            for (Pos p : cells.keySet()) {
                mx = Math.max(mx, p.x());
                my = Math.max(my, p.y());
                mz = Math.max(mz, p.z());
            }
            sx = mx + 1;
            sy = my + 1;
            sz = mz + 1;
            if ((long) sx * sy * sz > MAX_VOLUME) {
                throw new IllegalArgumentException("Bauwerk zu groß für den Rückübersetzer: " + sx + "×" + sy + "×"
                        + sz);
            }
            target = new int[sx * sy * sz];
            state = new int[target.length];
            for (Map.Entry<Pos, BlockSpec> e : cells.entrySet()) {
                int id = ids.computeIfAbsent(e.getValue(), b -> {
                    palette.add(b);
                    return palette.size() - 1;
                });
                target[idx(e.getKey().x(), e.getKey().y(), e.getKey().z())] = id;
            }
            wrong = new int[palette.size()];
            for (int t : target) {
                if (t != 0) {
                    wrong[t]++;
                }
            }
            cellsOf = new int[palette.size()][];
            int[] fill = new int[palette.size()];
            for (int id = 1; id < palette.size(); id++) {
                cellsOf[id] = new int[wrong[id]];
            }
            for (int i = 0; i < target.length; i++) {
                if (target[i] != 0) {
                    cellsOf[target[i]][fill[target[i]]++] = i;
                }
            }
        }

        int idx(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }

        boolean inside(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
        }

        void write(int i, int id) {
            int t = target[i];
            if (state[i] == t && t != 0) {
                wrong[t]++;
            }
            state[i] = id;
            if (id == t && t != 0) {
                wrong[t]--;
            }
        }
    }

    /** Form eines Kandidaten; Zylinder wie PlanCompiler.cylinder (Mitte = Rechteckmitte, radius = halbe Seite). */
    private enum Kind {
        FILL, WALLS, DISC, TUBE
    }

    /** Ein Kandidat aus Block id im Rechteck x0..x1, y0..y1, z0..z1. */
    private record Box(Kind kind, int id, int x0, int y0, int z0, int x1, int y1, int z1, double gain) {

        int r() {
            return (x1 - x0) / 2;
        }

        /** Schreibt der Schritt die Säule (x, z)? Gleiche Regeln wie im PlanCompiler. */
        boolean covers(int x, int z) {
            return switch (kind) {
                case FILL -> true;
                case WALLS -> x == x0 || x == x1 || z == z0 || z == z1;
                case DISC, TUBE -> {
                    int r = r();
                    int dx = x - (x0 + r);
                    int dz = z - (z0 + r);
                    boolean in = PlanCompiler.inDisc(dx, dz, r);
                    yield in && (kind == Kind.DISC || !(PlanCompiler.inDisc(dx + 1, dz, r)
                            && PlanCompiler.inDisc(dx - 1, dz, r) && PlanCompiler.inDisc(dx, dz + 1, r)
                            && PlanCompiler.inDisc(dx, dz - 1, r)));
                }
            };
        }
    }

    // ---------------------------------------------------------------- Zerlegung

    /** Verlustfrei: so viele Schritte, wie das Bauwerk braucht. */
    public static Result decompile(Map<Pos, BlockSpec> local, String design, String reply) {
        return decompile(local, design, reply, Integer.MAX_VALUE);
    }

    /** Ein fertiger Schritt mit der Zahl der Zellen, die er richtig setzt – danach wird bei knappem Budget gewählt. */
    private record Piece(JsonObject json, String op, int cells) {
    }

    /**
     * Mit Schrittbudget (verlustbehaftet): Türen zuerst, dann die großen Formen in Gewinn-Reihenfolge, dann die
     * längsten Reihen; was nicht mehr hineinpasst, fehlt. Nie wird dabei in Luft des Originals geschrieben – der
     * vereinfachte Bau ist eine Teilmenge des Originals, keine Erfindung. Wie viel fehlt, misst verify.
     *
     * Warum: Echte Bauten brauchen verlustfrei hunderte Schritte (gemessen 2026-11-01: 121–2054 je Schematic), ein
     * Lehrplan darf höchstens 40 haben (System-Prompt „5 to 40 steps“).
     */
    public static Result decompile(Map<Pos, BlockSpec> local, String design, String reply, int budget) {
        return decompile(local, design, reply, budget, true);
    }

    /** roofs = false: wie vor der Dach-Erkennung – nur für die Gegenprobe im Test („Bau ohne Dach unverändert“). */
    static Result decompile(Map<Pos, BlockSpec> local, String design, String reply, int budget, boolean roofs) {
        Map<Pos, BlockSpec> cells = new TreeMap<>(StructureFile::order);
        int dropped = 0;
        for (Map.Entry<Pos, BlockSpec> e : local.entrySet()) {
            if (BlockRules.isRestricted(e.getValue().id())) {
                dropped++;
            } else {
                cells.put(e.getKey(), e.getValue());
            }
        }
        if (cells.size() > MAX_CELLS) {
            throw new IllegalArgumentException("Bauwerk hat " + cells.size() + " Blöcke, der Rückübersetzer nimmt "
                    + "höchstens " + MAX_CELLS);
        }
        List<JsonObject> steps = new ArrayList<>();
        Map<String, Integer> ops = new TreeMap<>();
        if (cells.isEmpty()) {
            return new Result(render(design, reply, steps), 0, 0, dropped, ops);
        }
        Grid g = new Grid(cells);
        boolean[] door = doorCells(g);
        List<Piece> doorSteps = doors(g);
        int room = budget == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, budget - doorSteps.size());

        List<Roof> roofShapes = roofs ? roofs(g) : List.of();
        List<Roof> placed = new ArrayList<>();
        for (int n = 0; n < Math.min(MAX_PRIMITIVES, room); n++) {
            Box best = null;
            for (int id = 1; id < g.palette.size(); id++) {
                if (g.wrong[id] < MIN_GAIN || g.palette.get(id).isDoor()) {
                    continue;
                }
                for (Box b : candidates(g, id)) {
                    if (best == null || b.gain() > best.gain()) {
                        best = b;
                    }
                }
            }
            Roof bestRoof = null;
            for (Roof r : roofShapes) {
                // Zwei Dächer über derselben Fläche lehnt der Compiler ab (checkStackedRoofs) – hier gar nicht erst.
                if (placed.stream().anyMatch(r::overlaps)) {
                    continue;
                }
                Roof s = scored(g, r);
                if (bestRoof == null || s.gain() > bestRoof.gain()) {
                    bestRoof = s;
                }
            }
            if (bestRoof != null && bestRoof.gain() >= MIN_GAIN && (best == null || bestRoof.gain() > best.gain())) {
                for (int k = 0; k < bestRoof.cells().length; k++) {
                    g.write(bestRoof.cells()[k], bestRoof.ids()[k]);
                }
                placed.add(bestRoof);
                steps.add(bestRoof.json().deepCopy());
                ops.merge("roof", 1, Integer::sum);
                continue;
            }
            if (best == null || best.gain() < MIN_GAIN) {
                break;
            }
            apply(g, best);
            steps.add(boxStep(g, best));
            ops.merge(opName(best.kind()), 1, Integer::sum);
        }
        List<Piece> rest = residual(g, door);
        int left = room == Integer.MAX_VALUE ? Integer.MAX_VALUE : room - steps.size();
        if (rest.size() > left) {
            // Die längsten Reihen behalten, in ihrer ursprünglichen Reihenfolge (sie überschneiden sich nie).
            List<Piece> byLength = new ArrayList<>(rest);
            byLength.sort((a, b) -> Integer.compare(b.cells(), a.cells()));
            java.util.Set<Piece> keep = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            keep.addAll(byLength.subList(0, Math.max(0, left)));
            rest.removeIf(piece -> !keep.contains(piece));
        }
        for (Piece piece : rest) {
            steps.add(piece.json());
            ops.merge(piece.op(), 1, Integer::sum);
        }
        for (Piece piece : doorSteps.subList(0, Math.min(doorSteps.size(), budget))) {
            steps.add(piece.json());
            ops.merge(piece.op(), 1, Integer::sum);
        }
        return new Result(render(design, reply, steps), steps.size(), cells.size(), dropped, ops);
    }

    private static boolean[] doorCells(Grid g) {
        boolean[] door = new boolean[g.target.length];
        for (int i = 0; i < g.target.length; i++) {
            door[i] = g.target[i] != 0 && g.palette.get(g.target[i]).isDoor();
        }
        return door;
    }

    private static List<Box> candidates(Grid g, int id) {
        List<Box> out = new ArrayList<>();
        // Startzellen gleichmäßig über die noch falschen Zellen verteilt, nicht nur die ersten in Speicherfolge – sonst
        // wüchsen alle Quader von derselben Ecke aus.
        int step = Math.max(1, g.wrong[id] / SEEDS_PER_BLOCK);
        int seen = 0;
        boolean[] layerDone = new boolean[g.sy];
        for (int i : g.cellsOf[id]) {
            if (g.state[i] == id) {
                continue;
            }
            int x = i % g.sx;
            int z = (i / g.sx) % g.sz;
            int y = i / (g.sx * g.sz);
            if (seen++ % step == 0) {
                out.add(grow(g, id, x, y, z));
            }
            if (!layerDone[y]) {
                layerDone[y] = true;
                out.addAll(rings(g, id, y));
            }
        }
        return out;
    }

    /** Quader aus einer Startzelle: jede Seite um eine Scheibe erweitern, solange die Scheibe passt. */
    private static Box grow(Grid g, int id, int x, int y, int z) {
        int[] b = {x, y, z, x, y, z};
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int face = 0; face < 6; face++) {
                // b = {x0, y0, z0, x1, y1, z1}; face 0..2 erweitert nach unten (x0/y0/z0 − 1), 3..5 nach oben.
                int axis = face % 3;
                int[] t = b.clone();
                int[] slab = b.clone();
                if (face < 3) {
                    t[axis]--;
                    slab[axis] = t[axis];
                    slab[axis + 3] = t[axis];
                } else {
                    t[axis + 3]++;
                    slab[axis] = t[axis + 3];
                    slab[axis + 3] = t[axis + 3];
                }
                if (slabFits(g, id, slab)) {
                    System.arraycopy(t, 0, b, 0, 6);
                    grew = true;
                }
            }
        }
        return scored(g, new Box(Kind.FILL, id, b[0], b[1], b[2], b[3], b[4], b[5], 0));
    }

    private static boolean slabFits(Grid g, int id, int[] s) {
        if (!g.inside(s[0], s[1], s[2]) || !g.inside(s[3], s[4], s[5])) {
            return false;
        }
        int total = 0;
        int same = 0;
        for (int y = s[1]; y <= s[4]; y++) {
            for (int z = s[2]; z <= s[5]; z++) {
                for (int x = s[0]; x <= s[3]; x++) {
                    int t = g.target[g.idx(x, y, z)];
                    if (t == 0) {
                        return false;
                    }
                    total++;
                    if (t == id) {
                        same++;
                    }
                }
            }
        }
        return same >= FILL_SHARE * total;
    }

    /**
     * Wandringe in Schicht y: Umriss jeder zusammenhängenden Fläche aus id (Rechteck ab 3×3), dann nach oben und unten
     * verlängert, solange der Ring in der Nachbarschicht passt. Fenster und Türen dürfen im Ring liegen (fremde
     * Blöcke, später überschrieben), Luft nicht.
     */
    private static List<Box> rings(Grid g, int id, int y) {
        List<Box> out = new ArrayList<>();
        // Zwei Durchläufe: Wände über 4 Nachbarn, runde Formen über 8. Ein Kreisring hängt an den Schrägen nur über
        // Ecken zusammen; nähme man 8 Nachbarn auch für Wände, verschmölzen eckige Flächen zu großen Rechtecken, die
        // nicht mehr als Ring passen (gemessen 2026-11-01, sorter.schem: 862 → 992 Schritte).
        for (boolean round : new boolean[] {false, true}) {
            out.addAll(rings(g, id, y, round));
        }
        return out;
    }

    private static List<Box> rings(Grid g, int id, int y, boolean round) {
        List<Box> out = new ArrayList<>();
        boolean[] seen = new boolean[g.sx * g.sz];
        for (int z = 0; z < g.sz; z++) {
            for (int x = 0; x < g.sx; x++) {
                if (seen[z * g.sx + x] || g.target[g.idx(x, y, z)] != id) {
                    continue;
                }
                int[] bb = component(g, id, x, y, z, seen, round);
                if (bb[2] - bb[0] < 2 || bb[3] - bb[1] < 2) {
                    continue;
                }
                List<Kind> kinds = new ArrayList<>();
                if (!round) {
                    kinds.add(Kind.WALLS);
                } else if (bb[2] - bb[0] == bb[3] - bb[1] && (bb[2] - bb[0]) % 2 == 0 && bb[2] - bb[0] >= 4) {
                    // Quadratisches, ungerades Begrenzungsrechteck ab Radius 2: Rohr oder Scheibe.
                    kinds.add(Kind.TUBE);
                    kinds.add(Kind.DISC);
                }
                for (Kind k : kinds) {
                    Box probe = new Box(k, id, bb[0], y, bb[1], bb[2], y, bb[3], 0);
                    if (!layerFits(g, probe, y)) {
                        continue;
                    }
                    int y0 = y;
                    int y1 = y;
                    // Höchstens MAX_CYLINDER_HEIGHT: Die Grammatik des Hosts erlaubt height 1..48 (plan-schema.json).
                    // fill und walls haben keine solche Grenze, nur Koordinaten bis 200.
                    while (y0 > 0 && (k == Kind.WALLS || y1 - y0 + 1 < MAX_CYLINDER_HEIGHT)
                            && layerFits(g, probe, y0 - 1)) {
                        y0--;
                    }
                    while (y1 + 1 < g.sy && (k == Kind.WALLS || y1 - y0 + 1 < MAX_CYLINDER_HEIGHT)
                            && layerFits(g, probe, y1 + 1)) {
                        y1++;
                    }
                    out.add(scored(g, new Box(k, id, bb[0], y0, bb[1], bb[2], y1, bb[3], 0)));
                }
            }
        }
        return out;
    }

    /** Begrenzungsrechteck {x0, z0, x1, z1} der Fläche aus id in Schicht y (4 oder mit diagonal 8 Nachbarn). */
    private static int[] component(Grid g, int id, int x, int y, int z, boolean[] seen, boolean diagonal) {
        int[] bb = {x, z, x, z};
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        q.add(new int[] {x, z});
        seen[z * g.sx + x] = true;
        while (!q.isEmpty()) {
            int[] p = q.poll();
            bb[0] = Math.min(bb[0], p[0]);
            bb[1] = Math.min(bb[1], p[1]);
            bb[2] = Math.max(bb[2], p[0]);
            bb[3] = Math.max(bb[3], p[1]);
            int[][] next = diagonal
                    ? new int[][] {{p[0] + 1, p[1]}, {p[0] - 1, p[1]}, {p[0], p[1] + 1}, {p[0], p[1] - 1},
                        {p[0] + 1, p[1] + 1}, {p[0] - 1, p[1] - 1}, {p[0] + 1, p[1] - 1}, {p[0] - 1, p[1] + 1}}
                    : new int[][] {{p[0] + 1, p[1]}, {p[0] - 1, p[1]}, {p[0], p[1] + 1}, {p[0], p[1] - 1}};
            for (int[] n : next) {
                if (n[0] >= 0 && n[1] >= 0 && n[0] < g.sx && n[1] < g.sz && !seen[n[1] * g.sx + n[0]]
                        && g.target[g.idx(n[0], y, n[1])] == id) {
                    seen[n[1] * g.sx + n[0]] = true;
                    q.add(n);
                }
            }
        }
        return bb;
    }

    /** Passt die Form in Schicht y: keine Luft unter ihren Zellen, genug Hauptmaterial (Scheibe wie ein Quader). */
    private static boolean layerFits(Grid g, Box b, int y) {
        int total = 0;
        int same = 0;
        for (int x = b.x0(); x <= b.x1(); x++) {
            for (int z = b.z0(); z <= b.z1(); z++) {
                if (!b.covers(x, z)) {
                    continue;
                }
                int t = g.target[g.idx(x, y, z)];
                if (t == 0) {
                    return false;
                }
                total++;
                if (t == b.id()) {
                    same++;
                }
            }
        }
        return same >= (b.kind() == Kind.DISC ? FILL_SHARE : RING_SHARE) * total;
    }

    private static Box scored(Grid g, Box b) {
        double gain = 0;
        for (int y = b.y0(); y <= b.y1(); y++) {
            for (int z = b.z0(); z <= b.z1(); z++) {
                for (int x = b.x0(); x <= b.x1(); x++) {
                    if (!b.covers(x, z)) {
                        continue;
                    }
                    int i = g.idx(x, y, z);
                    int t = g.target[i];
                    if (t == b.id()) {
                        gain += g.state[i] == t ? 0 : 1;
                    } else {
                        gain -= g.state[i] == t ? 2 : 0.1;
                    }
                }
            }
        }
        return new Box(b.kind(), b.id(), b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1(), gain);
    }

    private static void apply(Grid g, Box b) {
        for (int y = b.y0(); y <= b.y1(); y++) {
            for (int z = b.z0(); z <= b.z1(); z++) {
                for (int x = b.x0(); x <= b.x1(); x++) {
                    if (b.covers(x, z)) {
                        g.write(g.idx(x, y, z), b.id());
                    }
                }
            }
        }
    }

    /** Restliche falsche Zellen: Reihen gleicher Blöcke entlang x, z, y als line (ab 2), sonst set. */
    private static List<Piece> residual(Grid g, boolean[] door) {
        List<Piece> out = new ArrayList<>();
        int[][] dirs = {{1, 0, 0}, {0, 0, 1}, {0, 1, 0}};
        for (int[] d : dirs) {
            for (int i = 0; i < g.target.length; i++) {
                if (!open(g, door, i)) {
                    continue;
                }
                int x = i % g.sx;
                int z = (i / g.sx) % g.sz;
                int y = i / (g.sx * g.sz);
                int id = g.target[i];
                int len = 1;
                while (g.inside(x + d[0] * len, y + d[1] * len, z + d[2] * len)) {
                    int j = g.idx(x + d[0] * len, y + d[1] * len, z + d[2] * len);
                    if (!open(g, door, j) || g.target[j] != id) {
                        break;
                    }
                    len++;
                }
                if (len < 2) {
                    continue;
                }
                for (int k = 0; k < len; k++) {
                    g.write(g.idx(x + d[0] * k, y + d[1] * k, z + d[2] * k), id);
                }
                out.add(new Piece(step("line", g.palette.get(id)).from(x, y, z)
                        .to(x + d[0] * (len - 1), y + d[1] * (len - 1), z + d[2] * (len - 1)).json, "line", len));
            }
        }
        for (int i = 0; i < g.target.length; i++) {
            if (open(g, door, i)) {
                int id = g.target[i];
                g.write(i, id);
                out.add(new Piece(step("set", g.palette.get(id)).pos(i % g.sx, i / (g.sx * g.sz), (i / g.sx) % g.sz)
                        .json, "set", 1));
            }
        }
        return out;
    }

    private static boolean open(Grid g, boolean[] door, int i) {
        return g.target[i] != 0 && !door[i] && g.state[i] != g.target[i];
    }

    /** Untere Türhälfte → op door (Seite = Gegenrichtung von facing, siehe PlanCompiler.door); obere entfällt. */
    private static List<Piece> doors(Grid g) {
        List<Piece> out = new ArrayList<>();
        for (int i = 0; i < g.target.length; i++) {
            int id = g.target[i];
            if (id == 0 || !g.palette.get(id).isDoor()) {
                continue;
            }
            BlockSpec d = g.palette.get(id);
            int x = i % g.sx;
            int z = (i / g.sx) % g.sz;
            int y = i / (g.sx * g.sz);
            String half = d.props().getOrDefault("half", "lower");
            if (half.equals("upper")) {
                boolean lowerBelow = y > 0 && g.target[g.idx(x, y - 1, z)] != 0
                        && g.palette.get(g.target[g.idx(x, y - 1, z)]).isDoor();
                if (!lowerBelow) {
                    out.add(new Piece(step("set", d).pos(x, y, z).json, "set", 1));
                }
                continue;
            }
            String facing = d.props().getOrDefault("facing", "back");
            Map<String, String> props = new LinkedHashMap<>();
            if ("right".equals(d.props().get("hinge"))) {
                props.put("hinge", "right");
            }
            S s = step("door", new BlockSpec(d.id(), props)).pos(x, y, z);
            s.json.addProperty("facing", opposite(facing));
            out.add(new Piece(s.json, "door", 2));
        }
        return out;
    }

    private static String opposite(String dir) {
        return switch (dir) {
            case "front" -> "back";
            case "back" -> "front";
            case "left" -> "right";
            case "right" -> "left";
            default -> "front";
        };
    }

    // ---------------------------------------------------------------- Dächer

    /**
     * Ein Dach-Kandidat: fertiger roof-Schritt, Grundfläche samt Überstand (x0..x1, z0..z1) und die Zellen, die der
     * Compiler dafür schreibt – je Rasterindex die Palette-id, −1 für einen Block, den das Original nirgends hat.
     */
    private record Roof(JsonObject json, int x0, int z0, int x1, int z1, int[] cells, int[] ids, double gain) {

        boolean overlaps(Roof o) {
            return x0 <= o.x1 && o.x0 <= x1 && z0 <= o.z1 && o.z0 <= z1;
        }
    }

    /**
     * Dach-Kandidaten aus geraden, unteren Treppen. Gesucht wird die unterste Schicht: eine Reihe Treppen mit der
     * hohen Seite nach hinten (Satteldach mit First entlang x, Walmdach) bzw. nach rechts (First entlang z), und in
     * derselben Höhe die nächste gegenüberliegende Reihe. Daraus die Grundfläche, dann je Form, Überstand und
     * Giebelblock genau ein Kandidat. Ob er passt, entscheidet nicht diese Suche, sondern der Gewinn im Wettbewerb –
     * was sich nicht eindeutig als Dach lesen lässt (Luft unter einer Dachzelle, zu wenig Treffer), bleibt wie vorher.
     */
    private static List<Roof> roofs(Grid g) {
        Map<String, Roof> out = new LinkedHashMap<>();
        for (int id = 1; id < g.palette.size(); id++) {
            BlockSpec s = g.palette.get(id);
            String facing = s.props().get("facing");
            if (!s.isStairs() || s.props().size() != 1 || !("back".equals(facing) || "right".equals(facing))) {
                continue;
            }
            boolean alongX = "back".equals(facing);
            Integer partner = g.ids.get(new BlockSpec(s.id(), Map.of("facing", alongX ? "front" : "left")));
            if (partner == null) {
                continue;
            }
            for (int i : g.cellsOf[id]) {
                int x = i % g.sx;
                int z = (i / g.sx) % g.sz;
                int y = i / (g.sx * g.sz);
                // Reihe gleicher Treppen (jeder Zustand: Ecken eines Walmdachs tragen shape=outer_…) entlang der Achse.
                int a = alongX ? x : z;
                int lo = a;
                int hi = a;
                while (lo > 0 && sameStairs(g, s.id(), alongX ? lo - 1 : x, y, alongX ? z : lo - 1)) {
                    lo--;
                }
                int end = alongX ? g.sx : g.sz;
                while (hi + 1 < end && sameStairs(g, s.id(), alongX ? hi + 1 : x, y, alongX ? z : hi + 1)) {
                    hi++;
                }
                if (a != (lo + hi) / 2) {
                    continue;  // je Reihe nur einmal, von ihrer Mitte aus
                }
                // Liegt schräg darunter dieselbe Treppe, ist das schon eine obere Schicht.
                if (y > 0 && (alongX ? z : x) > 0
                        && g.target[alongX ? g.idx(x, y - 1, z - 1) : g.idx(x - 1, y - 1, z)] == id) {
                    continue;
                }
                int far = -1;
                for (int t = (alongX ? z : x) + 1; t < (alongX ? g.sz : g.sx); t++) {
                    if (g.target[alongX ? g.idx(x, y, t) : g.idx(t, y, z)] == partner) {
                        far = t;
                        break;
                    }
                }
                if (far < 0) {
                    continue;
                }
                int x0 = alongX ? lo : x;
                int x1 = alongX ? hi : far;
                int z0 = alongX ? z : lo;
                int z1 = alongX ? far : hi;
                // Satteldach nur, wenn der Compiler den First genauso legt (entlang der längeren Seite, bei Gleichstand x).
                if (alongX == (x1 - x0 >= z1 - z0)) {
                    roofVariants(g, s.id(), "gable", x0, y, z0, x1, z1, out);
                }
                roofVariants(g, s.id(), "hip", x0, y, z0, x1, z1, out);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static boolean sameStairs(Grid g, String stairs, int x, int y, int z) {
        int t = g.target[g.idx(x, y, z)];
        return t != 0 && g.palette.get(t).id().equals(stairs);
    }

    /**
     * Überstand 1 (Vorgabe im Prompt) vor 0 und 2; ohne Giebelblock sind alle Überstände dieselben Zellen, dann nur der
     * erste. Mit Giebelblock (nur gable) je Überstand der häufigste Block an den Giebelstellen.
     */
    private static void roofVariants(Grid g, String stairs, String shape, int x0, int y, int z0, int x1, int z1,
            Map<String, Roof> out) {
        boolean plain = false;
        for (int o : new int[] {1, 0, 2}) {
            if (x0 + o > x1 - o || z0 + o > z1 - o) {
                continue;
            }
            if (!plain) {
                plain = true;
                add(out, roofCandidate(g, stairs, shape, x0, y, z0, x1, z1, o, null));
            }
            if (shape.equals("gable")) {
                BlockSpec gable = gableBlock(g, x0, y, z0, x1, z1, o);
                if (gable != null) {
                    add(out, roofCandidate(g, stairs, shape, x0, y, z0, x1, z1, o, gable));
                }
            }
        }
    }

    private static void add(Map<String, Roof> out, Roof r) {
        if (r != null) {
            out.putIfAbsent(r.json().toString(), r);
        }
    }

    /** Häufigster Block an den Giebelstellen; null, wenn dort Luft oder nur Treppen/Türen stehen. */
    private static BlockSpec gableBlock(Grid g, int x0, int y, int z0, int x1, int z1, int o) {
        List<int[]> spots = new ArrayList<>();
        roofCells(x0, y, z0, x1, z1, o, "gable", true, (x, yy, z, kind) -> {
            if (kind == 'g') {
                spots.add(new int[] {x, yy, z});
            }
        });
        Map<Integer, Integer> count = new HashMap<>();
        for (int[] p : spots) {
            if (!g.inside(p[0], p[1], p[2])) {
                return null;
            }
            int t = g.target[g.idx(p[0], p[1], p[2])];
            if (t == 0) {
                return null;
            }
            count.merge(t, 1, Integer::sum);
        }
        return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> g.palette.get(e.getKey()))
                .filter(b -> !b.isStairs() && !b.isDoor()).orElse(null);
    }

    /** Zelle eines Dachs: kind 's' Schräge (facing), 't' First, 'g' Giebel. */
    private interface RoofCell {
        void put(int x, int y, int z, char kind, String facing);
    }

    /** Kurzform für Aufrufer, die die Richtung nicht brauchen. */
    private interface RoofSpot {
        void put(int x, int y, int z, char kind);
    }

    private static void roofCells(int x0, int y, int z0, int x1, int z1, int o, String shape, boolean gable,
            RoofSpot spot) {
        roofCells(x0, y, z0, x1, z1, o, shape, gable, (x, yy, z, kind, facing) -> spot.put(x, yy, z, kind));
    }

    /**
     * Die Schleifen aus PlanCompiler.roof (gable/hip), Grundfläche schon samt Überstand. Wer dort etwas ändert, muss es
     * hier nachziehen – sonst passt der Kandidat nicht mehr zum Rückbau und verify zeigt die Abweichung (der Test
     * „Dachschritt ergibt beim Kompilieren dieselben Zellen“ fällt dann).
     */
    private static void roofCells(int x0, int baseY, int z0, int x1, int z1, int o, String shape, boolean gable,
            RoofCell cell) {
        if (shape.equals("hip")) {
            for (int i = 0; i < MAX_ROOF_LAYERS; i++) {
                int xa = x0 + i;
                int xb = x1 - i;
                int za = z0 + i;
                int zb = z1 - i;
                if (xa > xb || za > zb) {
                    break;
                }
                int y = baseY + i;
                if (xa == xb || za == zb) {
                    for (int z = za; z <= zb; z++) {
                        for (int x = xa; x <= xb; x++) {
                            cell.put(x, y, z, 't', "");
                        }
                    }
                    break;
                }
                for (int x = xa; x <= xb; x++) {
                    cell.put(x, y, za, 's', "back");
                    cell.put(x, y, zb, 's', "front");
                }
                for (int z = za + 1; z < zb; z++) {
                    cell.put(xa, y, z, 's', "right");
                    cell.put(xb, y, z, 's', "left");
                }
            }
            return;
        }
        boolean ridgeAlongX = (x1 - x0) >= (z1 - z0);
        int lo = ridgeAlongX ? z0 : x0;
        int hi = ridgeAlongX ? z1 : x1;
        int ridgeLo = ridgeAlongX ? x0 : z0;
        int ridgeHi = ridgeAlongX ? x1 : z1;
        int wallLo = (ridgeAlongX ? z0 : x0) + o;
        int wallHi = (ridgeAlongX ? z1 : x1) - o;
        int gableA = (ridgeAlongX ? x0 : z0) + o;
        int gableB = (ridgeAlongX ? x1 : z1) - o;
        for (int i = 0; i < MAX_ROOF_LAYERS && lo + i <= hi - i; i++) {
            int y = baseY + i;
            int a = lo + i;
            int b = hi - i;
            for (int r = ridgeLo; r <= ridgeHi; r++) {
                if (a == b) {
                    putRoof(cell, ridgeAlongX, r, y, a, 't', "");
                } else {
                    putRoof(cell, ridgeAlongX, r, y, a, 's', ridgeAlongX ? "back" : "right");
                    putRoof(cell, ridgeAlongX, r, y, b, 's', ridgeAlongX ? "front" : "left");
                }
            }
            if (gable) {
                for (int t = Math.max(a + 1, wallLo); t <= Math.min(b - 1, wallHi); t++) {
                    putRoof(cell, ridgeAlongX, gableA, y, t, 'g', "");
                    putRoof(cell, ridgeAlongX, gableB, y, t, 'g', "");
                }
            }
        }
    }

    private static void putRoof(RoofCell cell, boolean ridgeAlongX, int r, int y, int t, char kind, String facing) {
        if (ridgeAlongX) {
            cell.put(r, y, t, kind, facing);
        } else {
            cell.put(t, y, r, kind, facing);
        }
    }

    /**
     * Kandidat oder null: null, sobald eine Dachzelle außerhalb des Bauwerks oder in Luft des Originals läge, oder wenn
     * weniger als RING_SHARE der Dachzellen schon im Original genau so stehen.
     */
    private static Roof roofCandidate(Grid g, String stairs, String shape, int x0, int y, int z0, int x1, int z1, int o,
            BlockSpec gable) {
        BlockSpec block = BlockSpec.of(stairs);
        // Wie PlanCompiler.slabFor: Treppe → gleichnamige Platte (type=bottom fällt in BlockStates.normalize weg).
        BlockSpec top = BlockSpec.of(stairs.substring(0, stairs.length() - "_stairs".length()) + "_slab");
        Map<Integer, BlockSpec> cells = new LinkedHashMap<>();
        boolean[] bad = {false};
        java.util.Set<Integer> slopeLayers = new java.util.HashSet<>();
        roofCells(x0, y, z0, x1, z1, o, shape, gable != null, (x, yy, z, kind, facing) -> {
            if (!g.inside(x, yy, z) || g.target[g.idx(x, yy, z)] == 0) {
                bad[0] = true;
                return;
            }
            if (kind == 's') {
                slopeLayers.add(yy);
            }
            cells.put(g.idx(x, yy, z), switch (kind) {
                case 's' -> new BlockSpec(stairs, Map.of("facing", facing));
                case 't' -> top;
                default -> gable;
            });
        });
        // Ein Dach steigt an: Treppenlagen auf mindestens MIN_ROOF_LAYERS Höhen. Vorher (Runde 1 von T-014) wurden zwei
        // Bankreihen Rücken an Rücken (back/front ohne Lücke, eine Lage) zu roof – Zellen exakt, aber das Lehrset
        // nannte eine Bank „Dach“. Wer die Grenze auf 1 senkt, bringt dem Modell Sitzbänke als Dächer bei
        // (PlanDecompilerRoofTest.doubleBenchIsNoRoof).
        if (bad[0] || cells.isEmpty() || slopeLayers.size() < MIN_ROOF_LAYERS) {
            return null;
        }
        int[] idx = new int[cells.size()];
        int[] ids = new int[cells.size()];
        int k = 0;
        int same = 0;
        for (Map.Entry<Integer, BlockSpec> e : cells.entrySet()) {
            idx[k] = e.getKey();
            ids[k] = g.ids.getOrDefault(e.getValue(), -1);
            if (ids[k] == g.target[idx[k]]) {
                same++;
            }
            k++;
        }
        if (same < RING_SHARE * cells.size()) {
            return null;
        }
        S s = step("roof", block).from(x0 + o, y, z0 + o).to(x1 - o, y, z1 - o);
        s.json.addProperty("shape", shape);
        s.json.addProperty("overhang", o);
        if (gable != null) {
            s.json.addProperty("gable_block", text(gable));
        }
        return new Roof(s.json, x0, z0, x1, z1, idx, ids, 0);
    }

    /** Gewinn wie bei den Formen: neu richtig +1, zerstört −2, fremd (später überschrieben) −0,1. */
    private static Roof scored(Grid g, Roof r) {
        double gain = 0;
        for (int k = 0; k < r.cells().length; k++) {
            int i = r.cells()[k];
            int t = g.target[i];
            if (r.ids()[k] == t) {
                gain += g.state[i] == t ? 0 : 1;
            } else {
                gain -= g.state[i] == t ? 2 : 0.1;
            }
        }
        return new Roof(r.json(), r.x0(), r.z0(), r.x1(), r.z1(), r.cells(), r.ids(), gain);
    }

    // ---------------------------------------------------------------- Ausgabe

    private static JsonObject boxStep(Grid g, Box b) {
        if (b.kind() == Kind.DISC || b.kind() == Kind.TUBE) {
            S s = step("cylinder", g.palette.get(b.id())).pos(b.x0() + b.r(), b.y0(), b.z0() + b.r());
            s.json.addProperty("radius", b.r());
            s.json.addProperty("height", b.y1() - b.y0() + 1);
            if (b.kind() == Kind.TUBE) {
                s.json.addProperty("hollow", true);
            }
            return s.json;
        }
        return step(opName(b.kind()), g.palette.get(b.id())).from(b.x0(), b.y0(), b.z0())
                .to(b.x1(), b.y1(), b.z1()).json;
    }

    private static String opName(Kind k) {
        return switch (k) {
            case FILL -> "fill";
            case WALLS -> "walls";
            case DISC, TUBE -> "cylinder";
        };
    }

    /** Schritt in der Feldfolge der Prompt-Beispiele: op, block, from, to, pos. */
    private static final class S {
        final JsonObject json = new JsonObject();

        S pos(int x, int y, int z) {
            json.add("pos", vec(x, y, z));
            return this;
        }

        S from(int x, int y, int z) {
            json.add("from", vec(x, y, z));
            return this;
        }

        S to(int x, int y, int z) {
            json.add("to", vec(x, y, z));
            return this;
        }
    }

    private static S step(String op, BlockSpec block) {
        S s = new S();
        s.json.addProperty("op", op);
        s.json.addProperty("block", text(block));
        return s;
    }

    /** Blocktext ohne „minecraft:“, wie das LLM ihn schreibt. */
    static String text(BlockSpec b) {
        return b.asString().substring("minecraft:".length());
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Kopf in einer Zeile, je Schritt eine Zeile – Schreibweise der Prompt-Beispiele (wie das Lehrset). */
    static String render(String design, String reply, List<JsonObject> steps) {
        JsonObject head = new JsonObject();
        head.addProperty("design", design);
        head.addProperty("reply", reply);
        head.addProperty("mode", "new");
        String h = GSON.toJson(head);
        StringBuilder sb = new StringBuilder(h.substring(0, h.length() - 1)).append(",\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            sb.append('\n').append(GSON.toJson(steps.get(i))).append(i + 1 < steps.size() ? "," : "");
        }
        return sb.append("]}").toString();
    }

    // ---------------------------------------------------------------- Rückbau

    /**
     * Plan mit dem echten Compiler zurückübersetzen und Block für Block mit dem Original vergleichen. maxSteps erlaubt
     * auch Pläne über PlanParser.MAX_STEPS – für die Messung großer Bauten; ins Lehrset kommen sie nicht.
     */
    public static Verify verify(String json, Map<Pos, BlockSpec> local, PlanCompiler compiler, int maxSteps) {
        Map<Pos, BlockSpec> target = new HashMap<>();
        for (Map.Entry<Pos, BlockSpec> e : local.entrySet()) {
            if (!BlockRules.isRestricted(e.getValue().id())) {
                target.put(e.getKey(), BlockStates.normalize(e.getValue()));
            }
        }
        CompiledBuild b;
        try {
            b = compiler.compile(PlanParser.parse(json, maxSteps));
        } catch (PlanException e) {
            return new Verify(target.size(), 0, 0, List.of(), e.getMessage());
        }
        Map<Pos, BlockSpec> built = new HashMap<>();
        for (CompiledBuild.Action a : b.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                built.put(c.pos(), BlockStates.normalize(c.block()));
            }
        }
        int matched = 0;
        for (Map.Entry<Pos, BlockSpec> e : target.entrySet()) {
            if (e.getValue().equals(built.get(e.getKey()))) {
                matched++;
            }
        }
        int extra = 0;
        for (Map.Entry<Pos, BlockSpec> e : built.entrySet()) {
            if (!e.getValue().isAir() && !target.containsKey(e.getKey())) {
                extra++;
            }
        }
        return new Verify(target.size(), matched, extra, b.warnings(), null);
    }
}
