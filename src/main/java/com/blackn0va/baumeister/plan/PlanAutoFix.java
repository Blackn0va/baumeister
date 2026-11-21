package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.PlanLinter.Issue;
import com.blackn0va.baumeister.plan.PlanLinter.Rule;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reparaturen ohne LLM – was der Baumeister selbst richten kann, statt den Plan in eine Reparaturrunde zu schicken
 * oder zu verwerfen. Gemessen in allen dev01-Logs bis 2026-10-31: 136 LLM-Anfragen, 55 verworfene Pläne, und die
 * Gründe wiederholten sich – das LLM machte denselben Fehler auch in der Reparaturrunde:
 * 26× Modul ohne erkannten Maschinenwunsch (meist hatte das LLM richtig ein Schloss oder eine Klingel gewählt),
 * 9× Grundriss knapp außerhalb (house 32×1, skyscraper unter 5×5), 2× knapp zu groß (201 bzw. 241 bei 192),
 * 3× schwebende Teile mit 93–712 Blöcken, dazu Vollklötze. Alles hier ist rein und ohne Server testbar
 * (PlanAutoFixTest, je Log-Fall ein Test).
 *
 * Grenze: Hier wird nur gerichtet, was die Bauqualität betrifft. Überbau-Schutz, Ausdehnung, Blockzahl und Rechte
 * prüfen weiter Compiler, PlanReview.checkOverbuild und BuildService – nach diesen Reparaturen, auf dem fertigen Bau.
 */
public final class PlanAutoFix {

    private PlanAutoFix() {
    }

    /** Geänderter Plan-Text und was geändert wurde (deutsch, für Chat, Log und App). */
    public record Fixed(String json, List<String> notes) {
    }

    // ---------------------------------------------------------------- Grundriss

    /** Gültige Seitenlängen je Bauform (wie PlanParser/Structures/PlanCompiler sie prüfen). */
    /** Material, wenn es bei einer Bauform fehlt – wie die Vorgaben in QuickForms (castle/wall/bridge/garden/tree). */
    static final Map<String, String> FORM_BLOCKS = Map.of("castle", "stone_bricks", "wall", "stone_bricks",
            "bridge", "stone_bricks", "garden", "oak_fence", "tree", "oak_log");

    private static final Map<String, int[]> SIDES = Map.of(
            "house", new int[] {PlanParser.MIN_HOUSE_SIDE, PlanParser.MAX_HOUSE_SIDE},
            "skyscraper", new int[] {5, Integer.MAX_VALUE},
            "castle", new int[] {Structures.MIN_CASTLE_SIDE, Structures.MAX_CASTLE_SIDE},
            "garden", new int[] {Structures.MIN_GARDEN_SIDE, Structures.MAX_GARDEN_SIDE});

    /**
     * Grundriss von house, skyscraper, castle und garden in den gültigen Bereich: from bleibt, to rückt nach.
     * Vorher warf der Parser („house braucht einen Grundriss von 5×5 bis 24×24, nicht 32×1“), das LLM bekam die
     * Meldung – und lieferte in 9 von 9 Fällen auf dev01 denselben Grundriss noch einmal (verworfen). Ein Haus mit
     * 24 statt 32 Breite ist näher am Wunsch als gar keins. Unlesbares JSON bleibt unverändert (meldet der Parser).
     */
    public static Fixed footprints(String json) {
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject() || !el.getAsJsonObject().has("steps")
                    || !el.getAsJsonObject().get("steps").isJsonArray()) {
                return new Fixed(json, List.of());
            }
            root = el.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return new Fixed(json, List.of());
        }
        List<String> notes = new ArrayList<>();
        JsonArray steps = root.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).isJsonObject()) {
                continue;
            }
            JsonObject s = steps.get(i).getAsJsonObject();
            String op = s.has("op") && s.get("op").isJsonPrimitive()
                    ? s.get("op").getAsString().toLowerCase(Locale.ROOT) : "";
            // Material fehlt bei einer Bauform, deren Geometrie das Plugin rechnet (2026-11-08, Host-Log, Qwen2.5
            // 14B): „bridge“ ohne block → „Schritt 1: block fehlt“, die Reparaturrunde kam mit demselben Fehler
            // zurück (84 s), der Auftrag „Kanalbrücke“ wurde verworfen. Jetzt die Vorgabe der Schnellaufträge
            // (QuickForms) – eine Steinbrücke ist näher am Wunsch als keine. Vorgaben dort und hier gleich halten.
            String fallback = FORM_BLOCKS.get(op);
            if (fallback != null && (!s.has("block") || !s.get("block").isJsonPrimitive()
                    || s.get("block").getAsString().isBlank())) {
                s.addProperty("block", fallback);
                notes.add("Schritt " + (i + 1) + ": " + op + " ohne Material – " + fallback + " genommen");
            }
            int[] range = SIDES.get(op);
            int[] from = vec(s, "from");
            int[] to = vec(s, "to");
            if (range == null || from == null || to == null) {
                continue;
            }
            int w = Math.abs(to[0] - from[0]) + 1;
            int d = Math.abs(to[2] - from[2]) + 1;
            int nx = clampSide(from[0], to[0], range);
            int nz = clampSide(from[2], to[2], range);
            if (nx != to[0] || nz != to[2]) {
                JsonArray fixed = new JsonArray();
                fixed.add(nx);
                fixed.add(to[1]);
                fixed.add(nz);
                s.add("to", fixed);
                notes.add("Schritt " + (i + 1) + ": " + op + "-Grundriss " + w + "×" + d + " auf "
                        + (Math.abs(nx - from[0]) + 1) + "×" + (Math.abs(nz - from[2]) + 1) + " angepasst");
            }
        }
        return notes.isEmpty() ? new Fixed(json, List.of()) : new Fixed(root.toString(), List.copyOf(notes));
    }

    /** Neues Ende einer Seite: Länge in [min, max], Richtung von from nach to bleibt. */
    private static int clampSide(int from, int to, int[] range) {
        int side = Math.abs(to - from) + 1;
        int want = Math.clamp(side, range[0], range[1]);
        if (want == side) {
            return to;
        }
        int dir = to >= from ? 1 : -1;
        return from + dir * (want - 1);
    }

    private static int[] vec(JsonObject s, String key) {
        if (!s.has(key) || !s.get(key).isJsonArray() || s.getAsJsonArray(key).size() != 3) {
            return null;
        }
        JsonArray a = s.getAsJsonArray(key);
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!a.get(i).isJsonPrimitive() || !a.get(i).getAsJsonPrimitive().isNumber()) {
                return null;
            }
            v[i] = a.get(i).getAsInt();
        }
        return v;
    }

    // ---------------------------------------------------------------- Seite der Bauformen

    /** Bauformen, deren facing eine der vier Seiten sein muss (Structures.side) und bei denen leer die Vorgabe ist. */
    static final Set<String> SIDE_FORMS = Set.of("castle", "wall", "garden");

    /**
     * Ungültiges facing bei castle, wall und garden fällt weg – dann gilt die Vorgabe der Bauform (Tor vorn, Außenseite
     * der Mauer quer zu ihr, siehe Structures.wall). Vorher warf Structures.side („wall: facing muss front, back, left
     * oder right sein“) bzw. der Parser bei einem Wort außerhalb up/down, und das Modell schrieb in der Reparaturrunde
     * dasselbe noch einmal: dev01-Logs bis 2026-11-21, 3× verworfen. Eine Mauer mit der Außenseite nach oben gibt es
     * nicht – welche Seite gemeint war, steht nirgends, die Vorgabe ist so gut wie jede geratene Seite und besser als
     * keine Mauer. Andere Ops (Tür, Kolben, Modul) bleiben unberührt: Dort trägt facing die Funktion, und ein Wegfall
     * wäre geraten.
     */
    public static Fixed facings(String json) {
        JsonObject root = stepsRoot(json);
        if (root == null) {
            return new Fixed(json, List.of());
        }
        List<String> notes = new ArrayList<>();
        JsonArray steps = root.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).isJsonObject()) {
                continue;
            }
            JsonObject s = steps.get(i).getAsJsonObject();
            String op = s.has("op") && s.get("op").isJsonPrimitive()
                    ? s.get("op").getAsString().toLowerCase(Locale.ROOT) : "";
            if (!SIDE_FORMS.contains(op) || !s.has("facing") || s.get("facing").isJsonNull()) {
                continue;
            }
            JsonElement f = s.get("facing");
            String facing = f.isJsonPrimitive() ? f.getAsString().trim().toLowerCase(Locale.ROOT) : f.toString();
            if (facing.isEmpty() || Orientation.isLocalDirection(facing)) {
                continue;
            }
            s.remove("facing");
            notes.add("Schritt " + (i + 1) + ": " + op + "-facing „" + facing + "“ ungültig – Vorgabe der Bauform "
                    + "genommen");
        }
        return notes.isEmpty() ? new Fixed(json, List.of()) : new Fixed(root.toString(), List.copyOf(notes));
    }

    /** Wurzelobjekt mit steps-Feld oder null (unlesbar – das meldet dann der Parser). */
    private static JsonObject stepsRoot(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject() || !el.getAsJsonObject().has("steps")
                    || !el.getAsJsonObject().get("steps").isJsonArray()) {
                return null;
            }
            return el.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Module ohne Maschinenwunsch

    /**
     * Ab diesem Anteil der Blöcke aus Modulen gilt der Plan als Maschine – das LLM hat den Auftrag als Maschinenwunsch
     * verstanden, auch wenn RequestHints kein Maschinenwort fand. Gemessen auf dev01 (2026-10-31): 26 Pläne
     * verworfen, fast alle mit genau einem Modul (kombischloss, klingel) als ganzem Plan – das war der Auftrag.
     * Darunter sind Module Verzierung eines Gebäudes (2026-10-28: 60 Logikgatter in einer Hochhausfassade) und
     * werden entfernt.
     */
    static final double MACHINE_SHARE = 0.5;

    /** Anteil der Blöcke des Baus, die aus Modul-Schritten stammen (0..1). */
    static double moduleShare(BuildPlan plan, CompiledBuild build) {
        long all = 0;
        long fromModules = 0;
        for (CompiledBuild.Action a : build.actions()) {
            all += a.changes().size();
            if (a.step() >= 0 && a.step() < plan.steps().size() && plan.steps().get(a.step()).op() == Op.MODULE) {
                fromModules += a.changes().size();
            }
        }
        return all == 0 ? 0 : (double) fromModules / all;
    }

    /** Der Plan ohne die Schritte an diesen Stellen (0-basiert). */
    public static Optional<Fixed> withoutSteps(String json, List<Integer> drop, String note) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray steps = root.getAsJsonArray("steps");
            Set<Integer> gone = new HashSet<>(drop);
            JsonArray kept = new JsonArray();
            for (int i = 0; i < steps.size(); i++) {
                if (!gone.contains(i)) {
                    kept.add(steps.get(i));
                }
            }
            if (kept.isEmpty()) {
                return Optional.empty();
            }
            root.add("steps", kept);
            return Optional.of(new Fixed(root.toString(), List.of(note)));
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- Vollklötze

    /**
     * Vollklotz → Hohlkörper: cylinder/sphere mit hollow=true, ein fill als geschlossene Hülle (op hollow). Vorher
     * Reparaturrunde, und auf dev01 kam der Zylinder aus Steinziegeln (33×12×33) auch danach massiv zurück
     * (verworfen, 2026-10-30). Dass ein Turm innen hohl sein soll, steht im Prompt; das LLM hat es nur vergessen.
     */
    public static Optional<Fixed> hollowSolids(String json, List<Issue> issues) {
        Set<Integer> steps = new LinkedHashSet<>();
        for (Issue i : issues) {
            if (i.rule() == Rule.SOLID) {
                steps.add(i.step() - 1);
            }
        }
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("steps");
            List<String> notes = new ArrayList<>();
            for (int i : steps) {
                if (i < 0 || i >= arr.size() || !arr.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject s = arr.get(i).getAsJsonObject();
                String op = s.has("op") ? s.get("op").getAsString().toLowerCase(Locale.ROOT) : "";
                switch (op) {
                    case "cylinder", "sphere" -> s.addProperty("hollow", true);
                    case "fill" -> s.addProperty("op", "hollow");
                    default -> {
                        continue;
                    }
                }
                notes.add("Schritt " + (i + 1) + ": " + op + " innen hohl gemacht");
            }
            return notes.isEmpty() ? Optional.empty() : Optional.of(new Fixed(root.toString(), List.copyOf(notes)));
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- Stützen unter Schwebendem

    /** Höchste Stütze. Darüber ist es kein vergessener Pfeiler mehr, sondern ein falsch gesetztes Teil – ans LLM. */
    static final int MAX_PILLAR = 32;

    /** Blöcke, die als Pfeiler nicht taugen (fallen, durchsichtig, keine volle Fläche). */
    private static final Set<String> NOT_A_PILLAR_SUFFIX = Set.of("_stairs", "_slab", "_wall", "_fence", "_pane",
            "_glass", "_leaves", "_carpet", "_button", "_plate", "_door", "_trapdoor", "_sign", "_banner", "_torch");
    private static final Set<String> NOT_A_PILLAR = Set.of("sand", "red_sand", "gravel", "glass", "anvil", "ice",
            "lantern", "lever", "redstone_wire", "repeater", "comparator", "chain", "ladder", "scaffolding");

    /**
     * Unter jede schwebende Gruppe Pfeiler (1×1, aus ihrem Block, sonst Steinziegel) an den Ecken ihrer untersten
     * Lage bis zum nächsten festen Block – eigener Bau, Gelände/Bestand (existing) oder Bauhöhe floorY. Vorher ging
     * jede größere Gruppe ans LLM; auf dev01 (2026-10-30/31) schwebten danach noch 93, 546 bzw. 712 Blöcke, und der
     * Plan wurde verworfen. Meist fehlte genau das: Säulen unter einem Dach oder einer Plattform. Ist eine Stütze
     * höher als MAX_PILLAR, bleibt die Gruppe unverändert (dann sitzt sie falsch, nicht nur ohne Halt).
     *
     * @return der Bau mit Pfeilern (als erste Aktion, damit sie vor dem Getragenen stehen) oder leer
     */
    public static Optional<CompiledBuild> supports(CompiledBuild build, List<Issue> issues, Predicate<Pos> existing) {
        Map<Pos, BlockSpec> cells = new HashMap<>();
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                cells.put(c.pos(), c.block());
            }
        }
        int floorY = build.min().y();
        List<CompiledBuild.Change> pillars = new ArrayList<>();
        Set<Pos> done = new HashSet<>();
        int parts = 0;
        int firstStep = -1;
        for (Issue i : issues) {
            if (i.rule() != Rule.FLOATING || done.contains(i.pos()) || !solid(cells.get(i.pos()))) {
                continue;
            }
            Set<Pos> part = component(cells, i.pos());
            done.addAll(part);
            List<CompiledBuild.Change> mine = pillarsUnder(part, cells, existing, floorY);
            if (mine.isEmpty()) {
                continue;
            }
            for (CompiledBuild.Change c : mine) {
                cells.put(c.pos(), c.block());
            }
            pillars.addAll(mine);
            parts++;
            if (firstStep < 0) {
                firstStep = i.step() - 1;
            }
        }
        if (pillars.isEmpty()) {
            return Optional.empty();
        }
        List<CompiledBuild.Action> actions = new ArrayList<>(build.actions().size() + 1);
        actions.add(new CompiledBuild.Action(Math.max(0, firstStep), List.copyOf(pillars)));
        actions.addAll(build.actions());
        List<String> warnings = new ArrayList<>(build.warnings());
        warnings.add(parts + " schwebende(s) Teil(e) mit " + pillars.size() + " Block/Blöcken Stützen getragen – das "
                + "LLM hatte die Pfeiler vergessen.");
        return Optional.of(new CompiledBuild(List.copyOf(actions), List.copyOf(warnings), build.min(), build.max(),
                build.stepNames()));
    }

    private static List<CompiledBuild.Change> pillarsUnder(Set<Pos> part, Map<Pos, BlockSpec> cells,
            Predicate<Pos> existing, int floorY) {
        int low = Integer.MAX_VALUE;
        for (Pos p : part) {
            low = Math.min(low, p.y());
        }
        List<Pos> bottom = new ArrayList<>();
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (Pos p : part) {
            if (p.y() == low) {
                bottom.add(p);
                x0 = Math.min(x0, p.x());
                x1 = Math.max(x1, p.x());
                z0 = Math.min(z0, p.z());
                z1 = Math.max(z1, p.z());
            }
        }
        // Je Ecke des Umrisses die nächste Zelle der untersten Lage; kleine Teile bekommen so 1–4 Pfeiler.
        Set<Pos> sites = new LinkedHashSet<>();
        int[][] corners = {{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}};
        for (int[] c : corners) {
            Pos best = null;
            long bestD = Long.MAX_VALUE;
            for (Pos p : bottom) {
                long d = (long) (p.x() - c[0]) * (p.x() - c[0]) + (long) (p.z() - c[1]) * (p.z() - c[1]);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            sites.add(best);
        }
        List<CompiledBuild.Change> out = new ArrayList<>();
        for (Pos top : sites) {
            BlockSpec block = pillarBlock(cells.get(top));
            List<CompiledBuild.Change> column = new ArrayList<>();
            Pos p = top.add(0, -1, 0);
            boolean reached = false;
            while (column.size() <= MAX_PILLAR) {
                if (solid(cells.get(p)) || existing.test(p)) {
                    reached = true;
                    break;
                }
                if (cells.containsKey(p)) {
                    // Luft, Wasser oder ein Zierblock des Plans an dieser Stelle – nicht überbauen.
                    break;
                }
                column.add(new CompiledBuild.Change(p, block));
                if (p.y() <= floorY) {
                    reached = true;
                    break;
                }
                p = p.add(0, -1, 0);
            }
            if (!reached || column.size() > MAX_PILLAR) {
                return List.of();
            }
            out.addAll(column);
        }
        return out;
    }

    private static BlockSpec pillarBlock(BlockSpec top) {
        if (top != null && top.props().isEmpty() && !NOT_A_PILLAR.contains(top.id())
                && NOT_A_PILLAR_SUFFIX.stream().noneMatch(top.id()::endsWith)) {
            return top;
        }
        return BlockSpec.of("stone_bricks");
    }

    /** Ganze zusammenhängende feste Gruppe um start (26 Nachbarn), wie PlanLinter.floatingParts. */
    private static Set<Pos> component(Map<Pos, BlockSpec> cells, Pos start) {
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Pos n = p.add(dx, dy, dz);
                        if (solid(cells.get(n)) && seen.add(n)) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return seen;
    }

    private static boolean solid(BlockSpec b) {
        return b != null && !b.isAir() && !b.id().equals("water") && !b.id().equals("lava");
    }
}
