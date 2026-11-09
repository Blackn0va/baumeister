package com.blackn0va.baumeister.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Was am Bauplatz schon steht, in denselben lokalen Koordinaten wie der Bauplan.
 *
 * Warum: Ohne das plant das LLM blind und baut quer durch ein vorhandenes Haus (Wunsch 2026-10-28).
 * Gelände wird nur als Bodenhöhe beschrieben; Bauwerke als zusammenhängende Blockgruppen mit
 * Ausdehnung, Materialien, Türen und – wenn klein genug – einem Grundriss je Schicht.
 *
 * Gelände/Bewuchs wird an der Block-ID erkannt (ohne Bukkit-Tags, damit testbar). Bäume sind
 * Gruppen mit viel Laub; vergrabene Blöcke unter der Bodenoberfläche zählen nicht.
 */
public final class SiteSurvey {

    public record Structure(int number, Pos min, Pos max, int blocks, Map<String, Integer> materials,
            List<Pos> doors) {
    }

    private static final Set<String> TERRAIN = Set.of(
            "grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt", "mycelium", "mud", "clay", "gravel",
            "sand", "red_sand", "suspicious_sand", "suspicious_gravel", "stone", "deepslate", "tuff", "granite",
            "diorite", "andesite", "calcite", "dripstone_block", "bedrock", "netherrack", "end_stone",
            "snow_block", "powder_snow", "ice", "packed_ice", "blue_ice", "water", "lava", "farmland",
            "dirt_path", "soul_sand", "soul_soil", "basalt", "blackstone", "terracotta", "moss_block",
            "red_sandstone", "sandstone", "magma_block", "obsidian");

    private static final Set<String> PLANTS = Set.of(
            "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "sugar_cane", "kelp", "kelp_plant",
            "seagrass", "tall_seagrass", "vine", "glow_lichen", "snow", "bamboo", "sweet_berry_bush",
            "brown_mushroom", "red_mushroom", "moss_carpet", "lily_pad", "cactus", "pumpkin", "melon",
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
            "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower",
            "lilac", "rose_bush", "peony", "pink_petals", "hanging_roots", "spore_blossom", "bush",
            "firefly_bush", "leaf_litter", "wildflowers", "short_dry_grass", "tall_dry_grass", "cave_vines",
            "cave_vines_plant", "big_dripleaf", "small_dripleaf", "pointed_dripstone");

    private static final int MIN_STRUCTURE = 4;
    private static final int MAX_STRUCTURES = 6;
    private static final int MAP_MAX_FOOTPRINT = 24;
    /** Rand um die Bauwerke in den Ansichten – Platz für einen Anbau von üblicher Breite (views). */
    static final int VIEW_MARGIN = 8;
    private static final String MAP_SYMBOLS = "#=%@&*+oOxX$~^!?abcdefghijklmnpqrstuvwyz";

    private final Pos min;
    private final Pos max;
    private final Map<Pos, String> blocks;
    private final int[][] ground;
    private final List<Structure> structures;
    private final Set<Pos> structureCells;

    private SiteSurvey(Pos min, Pos max, Map<Pos, String> blocks, int[][] ground, List<Structure> structures,
            Set<Pos> structureCells) {
        this.min = min;
        this.max = max;
        this.blocks = blocks;
        this.ground = ground;
        this.structures = structures;
        this.structureCells = structureCells;
    }

    static boolean isTerrain(String id) {
        return TERRAIN.contains(id) || id.endsWith("_ore");
    }

    static boolean isPlant(String id) {
        return PLANTS.contains(id) || id.endsWith("_sapling") || id.endsWith("_flower")
                || id.endsWith("_grass") && !id.equals("grass_block");
    }

    static boolean isAir(String id) {
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air");
    }

    /** idAt liefert die Block-ID (ohne "minecraft:") an einer lokalen Position im Kasten min..max. */
    public static SiteSurvey scan(Pos min, Pos max, Function<Pos, String> idAt) {
        int w = max.x() - min.x() + 1;
        int d = max.z() - min.z() + 1;
        Map<Pos, String> all = new HashMap<>();
        int[][] ground = new int[w][d];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                ground[x][z] = min.y();
                for (int y = max.y(); y >= min.y(); y--) {
                    Pos p = new Pos(min.x() + x, y, min.z() + z);
                    String id = idAt.apply(p);
                    if (!isAir(id)) {
                        all.put(p, id);
                    }
                    // Bodenhöhe = Schicht über dem obersten Geländeblock der Säule (dort stünde man).
                    if (ground[x][z] == min.y() && isTerrain(id) && !id.equals("water")) {
                        ground[x][z] = y + 1;
                    }
                }
            }
        }
        // Bauwerkszellen: kein Gelände, kein Bewuchs, nicht unter der Bodenoberfläche vergraben.
        Set<Pos> candidates = new HashSet<>();
        for (Map.Entry<Pos, String> e : all.entrySet()) {
            Pos p = e.getKey();
            String id = e.getValue();
            if (isTerrain(id) || isPlant(id)) {
                continue;
            }
            if (p.y() < ground[p.x() - min.x()][p.z() - min.z()] - 1) {
                continue;
            }
            candidates.add(p);
        }
        List<Set<Pos>> groups = components(candidates);
        List<Structure> found = new ArrayList<>();
        Set<Pos> cells = new HashSet<>();
        groups.sort(Comparator.comparingInt((Set<Pos> g) -> -g.size()));
        for (Set<Pos> g : groups) {
            if (g.size() < MIN_STRUCTURE || isTree(g, all)) {
                continue;
            }
            if (found.size() >= MAX_STRUCTURES) {
                break;
            }
            found.add(describeGroup(found.size() + 1, g, all));
            cells.addAll(g);
        }
        return new SiteSurvey(min, max, all, ground, List.copyOf(found), cells);
    }

    private static List<Set<Pos>> components(Set<Pos> cells) {
        List<Set<Pos>> out = new ArrayList<>();
        Set<Pos> seen = new HashSet<>();
        for (Pos start : cells) {
            if (!seen.add(start)) {
                continue;
            }
            Set<Pos> group = new HashSet<>();
            Deque<Pos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Pos p = queue.poll();
                group.add(p);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            Pos n = p.add(dx, dy, dz);
                            if (cells.contains(n) && seen.add(n)) {
                                queue.add(n);
                            }
                        }
                    }
                }
            }
            out.add(group);
        }
        return out;
    }

    /** Ein Baum: Stämme plus viel Laub. Ein Haus mit einer Hecke hat weit weniger Laub. */
    private static boolean isTree(Set<Pos> group, Map<Pos, String> all) {
        long leaves = group.stream().filter(p -> all.get(p).endsWith("_leaves")).count();
        long logs = group.stream().filter(p -> {
            String id = all.get(p);
            return id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem");
        }).count();
        return leaves + logs >= group.size() * 0.8 && leaves > 0;
    }

    private static Structure describeGroup(int number, Set<Pos> g, Map<Pos, String> all) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y1 = Integer.MIN_VALUE;
        int z1 = Integer.MIN_VALUE;
        Map<String, Integer> counts = new HashMap<>();
        List<Pos> doors = new ArrayList<>();
        for (Pos p : g) {
            x0 = Math.min(x0, p.x());
            y0 = Math.min(y0, p.y());
            z0 = Math.min(z0, p.z());
            x1 = Math.max(x1, p.x());
            y1 = Math.max(y1, p.y());
            z1 = Math.max(z1, p.z());
            String id = all.get(p);
            counts.merge(id, 1, Integer::sum);
            if (id.endsWith("_door") && !id.equals(all.get(p.down()))) {
                doors.add(p);
            }
        }
        Map<String, Integer> top = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(6)
                .forEach(e -> top.put(e.getKey(), e.getValue()));
        doors.sort(Comparator.comparingInt(Pos::x).thenComparingInt(Pos::z));
        return new Structure(number, new Pos(x0, y0, z0), new Pos(x1, y1, z1), g.size(), top, List.copyOf(doors));
    }

    // ---------------------------------------------------------------- Überbau

    /** Wie viele Blöcke bestehender Bauwerke der Plan verändern würde, plus ein Beispiel für die Rückmeldung. */
    public record Overbuild(int count, Pos example, String before, String after, int structure) {
    }

    /**
     * Zählt nur echte Änderungen an Bauwerkszellen: gleiche ID bleibt erlaubt (Wand weiterführen),
     * Luft in einer Wand zählt (Durchbruch). Ein Durchgang kostet so 2, ein Fenster 1.
     */
    public Overbuild overbuild(CompiledBuild build) {
        int count = 0;
        Pos example = null;
        String before = null;
        String after = null;
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                if (!structureCells.contains(c.pos())) {
                    continue;
                }
                String existing = blocks.get(c.pos());
                if (existing != null && !existing.equals(c.block().id())) {
                    count++;
                    if (example == null) {
                        example = c.pos();
                        before = existing;
                        after = c.block().id();
                    }
                }
            }
        }
        Structure s = example == null ? null : structureAt(example);
        return new Overbuild(count, example, before, after, s == null ? 0 : s.number());
    }

    // ---------------------------------------------------------------- Auskunft

    public List<Structure> structures() {
        return structures;
    }

    public boolean hasStructures() {
        return !structures.isEmpty();
    }

    /** Gehört die Position zu einem erkannten Bauwerk? Für den Überbau-Schutz. */
    public boolean isStructure(Pos p) {
        return structureCells.contains(p);
    }

    /** Block-ID an p, "air" wenn leer oder außerhalb des Scans. */
    public String idAt(Pos p) {
        return blocks.getOrDefault(p, "air");
    }

    /**
     * Steht an p etwas, das trägt oder eine Wand bildet – Gelände oder Bauwerk, nicht Luft, Wasser oder Bewuchs?
     * Für den PlanLinter: Eine Tür in der Wand eines bestehenden Hauses steht in einer Wand, auch wenn der Plan die
     * Wand nicht selbst setzt.
     */
    public boolean solidAt(Pos p) {
        String id = idAt(p);
        return !isAir(id) && !id.equals("water") && !id.equals("lava") && !isPlant(id);
    }

    /** Liegt p im gescannten Kasten? Außerhalb ist der Bauplatz unbekannt, nicht leer. */
    public boolean inScan(Pos p) {
        return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y() && p.z() >= min.z()
                && p.z() <= max.z();
    }

    public Structure structureAt(Pos p) {
        for (Structure s : structures) {
            if (p.x() >= s.min().x() && p.x() <= s.max().x() && p.y() >= s.min().y() && p.y() <= s.max().y()
                    && p.z() >= s.min().z() && p.z() <= s.max().z()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Text für das LLM (englisch wie der System-Prompt). Hält sich an maxChars: erst Boden und
     * Bauwerks-Eckdaten, Grundrisse nur, solange Platz ist – lieber grob vollständig als
     * ein detailliertes Bauwerk und die übrigen abgeschnitten.
     */
    public String describe(int maxChars, int playerZ) {
        return describe(maxChars, playerZ, true);
    }

    /**
     * @param attachPoints die vorgerechneten Anschlussstellen je Bauwerk und Seite (~400 Zeichen je Bauwerk). Ohne
     *                     Anbau-Auftrag laden sie nur zum Anbauen ein, mit Anbau-Auftrag legt AttachPlanner EINE Wand
     *                     fest – dann sind die übrigen Seiten nur Ablenkung und Rechenzeit (Prompt-Token auf der CPU).
     */
    public String describe(int maxChars, int playerZ, boolean attachPoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("EXISTING SURROUNDINGS (same coordinates as your plan; the player stands at [0,0," + playerZ + "] ")
                .append("and looks toward +z; scanned x ").append(min.x()).append("..").append(max.x())
                .append(", z ").append(min.z()).append("..").append(max.z()).append("):\n");
        sb.append(groundLine()).append('\n');
        if (structures.isEmpty()) {
            sb.append("No buildings nearby. The site is free.\n");
            return sb.toString();
        }
        for (Structure s : structures) {
            sb.append("Structure ").append(s.number()).append(": x ").append(s.min().x()).append("..")
                    .append(s.max().x()).append(", y ").append(s.min().y()).append("..").append(s.max().y())
                    .append(", z ").append(s.min().z()).append("..").append(s.max().z()).append(", ")
                    .append(s.blocks()).append(" blocks. Materials: ");
            List<String> mats = new ArrayList<>();
            s.materials().forEach((id, n) -> mats.add(id + " " + n));
            sb.append(String.join(", ", mats)).append('.');
            if (!s.doors().isEmpty()) {
                sb.append(" Doors at ");
                List<String> ds = new ArrayList<>();
                s.doors().forEach(p -> ds.add("[" + p.x() + "," + p.y() + "," + p.z() + "]"));
                sb.append(String.join(" ", ds)).append('.');
            }
            sb.append('\n');
            if (attachPoints) {
                sb.append(attachPoints(s));
            }
        }
        for (Structure s : structures) {
            String map = layerMaps(s);
            if (map.isEmpty() || sb.length() + map.length() > maxChars) {
                continue;
            }
            sb.append(map);
        }
        return sb.toString();
    }

    /**
     * Fertige Anschlusskoordinaten je Seite. Gemessen 2026-10-28: Mit nur Box und Grundriss setzte
     * das 7B-Modell einen „Anbau“ fünf Blöcke neben das Haus (x 10..15 statt ab x=4) – das Ableiten
     * der gemeinsamen Wand aus dem Grundriss schafft es nicht. Vorgerechnet muss es nur abschreiben.
     */
    private static String attachPoints(Structure s) {
        int floor = s.min().y();
        int midX = (s.min().x() + s.max().x()) / 2;
        int midZ = (s.min().z() + s.max().z()) / 2;
        String main = s.materials().isEmpty() ? "" : s.materials().keySet().iterator().next();
        return "  To attach to structure " + s.number() + " (floor y=" + floor + ", main material " + main + "):\n"
                + "  - right side: shared wall plane x=" + s.max().x() + ", new part spans x " + s.max().x()
                + "..(" + s.max().x() + "+width), z " + s.min().z() + ".." + s.max().z()
                + "; doorway: fill air [" + s.max().x() + "," + (floor + 1) + "," + midZ + "] to ["
                + s.max().x() + "," + (floor + 2) + "," + midZ + "]\n"
                + "  - left side: shared wall plane x=" + s.min().x() + ", new part spans x (" + s.min().x()
                + "-width).." + s.min().x() + "; doorway at [" + s.min().x() + "," + (floor + 1) + "," + midZ
                + "]..[" + s.min().x() + "," + (floor + 2) + "," + midZ + "]\n"
                + "  - back side: shared wall plane z=" + s.max().z() + ", new part spans z " + s.max().z()
                + "..(" + s.max().z() + "+depth), x " + s.min().x() + ".." + s.max().x() + "; doorway at ["
                + midX + "," + (floor + 1) + "," + s.max().z() + "]..[" + midX + "," + (floor + 2) + ","
                + s.max().z() + "]\n";
    }

    /**
     * Berührt der Plan (feste Blöcke) irgendein Bauwerk – gemeinsame Wand, direkt daneben oder
     * darin? Für die Anschluss-Prüfung bei Aufträgen wie „bau an das Haus an“.
     */
    public boolean touchesStructure(CompiledBuild build) {
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                if (c.block().isAir()) {
                    continue;
                }
                Pos p = c.pos();
                for (Structure s : structures) {
                    if (p.x() >= s.min().x() - 1 && p.x() <= s.max().x() + 1 && p.y() >= s.min().y() - 1
                            && p.y() <= s.max().y() + 1 && p.z() >= s.min().z() - 1 && p.z() <= s.max().z() + 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String groundLine() {
        Map<Integer, Integer> freq = new HashMap<>();
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int[] col : ground) {
            for (int g : col) {
                freq.merge(g, 1, Integer::sum);
                lo = Math.min(lo, g);
                hi = Math.max(hi, g);
            }
        }
        int mode = freq.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        if (lo == hi) {
            return "Ground: flat, you stand on y=" + mode + ".";
        }
        return "Ground: mostly y=" + mode + " (range " + lo + ".." + hi + "). Build the floor at the ground "
                + "height of its spot.";
    }

    // ---------------------------------------------------------------- Augen

    static final String LEGEND = "Legend: . soil  , rock  : sand  ~ water  = path  T tree  # wall/solid  ^ roof/stairs/"
            + "slab  o glass  D door  | fence/wall/bars  * light  r redstone  x other  (space) air";

    /** Ein Zeichen je Block – grob genug für ein 7–14B-Modell, fein genug für Wand, Dach, Fenster, Tür. */
    static char symbol(String id) {
        if (isAir(id)) {
            return ' ';
        }
        if (id.equals("water")) {
            return '~';
        }
        if (id.equals("dirt_path") || id.endsWith("_concrete_powder") || id.equals("gravel")) {
            return '=';
        }
        if (id.endsWith("_log") || id.endsWith("_leaves") || id.endsWith("_wood")) {
            return 'T';
        }
        if (id.contains("sand") && isTerrain(id)) {
            return ':';
        }
        if (isTerrain(id)) {
            return id.equals("grass_block") || id.contains("dirt") || id.equals("podzol") || id.equals("mud")
                    || id.equals("farmland") || id.equals("moss_block") ? '.' : ',';
        }
        if (id.contains("redstone") || id.equals("repeater") || id.equals("comparator") || id.equals("lever")
                || id.endsWith("_button") || id.endsWith("piston") || id.equals("observer")) {
            return 'r';
        }
        if (id.contains("glass")) {
            return 'o';
        }
        if (id.endsWith("_door") || id.endsWith("_trapdoor") || id.endsWith("fence_gate")) {
            return 'D';
        }
        if (id.endsWith("_stairs") || id.endsWith("_slab")) {
            return '^';
        }
        if (id.endsWith("_fence") || id.endsWith("_wall") || id.endsWith("_bars") || id.equals("chain")) {
            return '|';
        }
        if (id.contains("lantern") || id.contains("torch") || id.contains("lamp") || id.equals("glowstone")
                || id.equals("sea_lantern") || id.endsWith("candle")) {
            return '*';
        }
        if (isPlant(id)) {
            return ' ';
        }
        return id.endsWith("_planks") || id.endsWith("bricks") || id.endsWith("_concrete") || id.endsWith("stone")
                || id.contains("terracotta") || id.endsWith("_block") || id.contains("quartz")
                || id.contains("sandstone") || id.contains("deepslate") || id.contains("copper") ? '#' : 'x';
    }

    /**
     * Augen fürs LLM (Wunsch 2026-10-28): Draufsicht und
     * Vorderansicht des Bauplatzes aus dem echten Scan, ein Zeichen je Block. Vorher sah das Modell nur Kästen und
     * Materiallisten und baute Anbauten neben statt an Häuser oder Dächer übereinander.
     *
     * Leer ohne Bauwerke: Vorher kamen die Bilder schon bei unebenem, aber freiem Gelände – eine Fläche aus '.' und
     * ',' von 49 × 43 Zeichen plus Vorderansicht für eine Auskunft, die die Bodenzeile schon gibt. Mit Bauwerken nur
     * der Ausschnitt um sie (VIEW_MARGIN), nicht der ganze Scan: Der Anbau braucht die Umgebung des Hauses, nicht
     * den Rasen 30 Blöcke daneben. Gemessen in SiteSurveyViewsTest am echten Scan-Kasten von SiteFrame
     * (2026-10-29): Haus 9 × 11 über den ganzen Scan 2 695 Zeichen Ansichten, im Ausschnitt 1 181. Prompt-Token
     * kosten auf der Laptop-CPU ~1/37 s je Token (Gedächtnis: 37 t/s Prompt). Wer den Ausschnitt wieder auf den
     * Scan weitet, zahlt das bei jedem Auftrag.
     */
    public String views() {
        if (structures.isEmpty()) {
            return "";
        }
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE;
        int z1 = Integer.MIN_VALUE;
        int structTop = Integer.MIN_VALUE;
        for (Structure s : structures) {
            x0 = Math.min(x0, s.min().x());
            x1 = Math.max(x1, s.max().x());
            z0 = Math.min(z0, s.min().z());
            z1 = Math.max(z1, s.max().z());
            structTop = Math.max(structTop, s.max().y());
        }
        x0 = Math.max(min.x(), x0 - VIEW_MARGIN);
        x1 = Math.min(max.x(), x1 + VIEW_MARGIN);
        z0 = Math.max(min.z(), z0 - VIEW_MARGIN);
        z1 = Math.min(max.z(), z1 + VIEW_MARGIN);
        int topY = min.y();
        for (Pos p : blocks.keySet()) {
            if (!isPlant(blocks.get(p)) && p.x() >= x0 && p.x() <= x1 && p.z() >= z0 && p.z() <= z1) {
                topY = Math.max(topY, p.y());
            }
        }
        // Baumkronen hoch über dem Dach zeigen nichts, was ein Anbau braucht.
        topY = Math.min(topY, structTop + 2);
        int lowY = Integer.MAX_VALUE;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                lowY = Math.min(lowY, ground[x - min.x()][z - min.z()] - 1);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("TOP VIEW (looking down; first row = back z=").append(z1).append(", last row = front z=")
                .append(z0).append("; columns x=").append(x0).append("..").append(x1).append("):\n");
        for (int z = z1; z >= z0; z--) {
            StringBuilder row = new StringBuilder();
            for (int x = x0; x <= x1; x++) {
                char c = ' ';
                for (int y = max.y(); y >= min.y(); y--) {
                    String id = idAt(new Pos(x, y, z));
                    char s = symbol(id);
                    if (s != ' ') {
                        c = s;
                        break;
                    }
                }
                row.append(c);
            }
            sb.append(row.toString().stripTrailing()).append('\n');
        }
        sb.append("FRONT VIEW (what the player sees looking toward +z; first row = y=").append(topY)
                .append(", last row = y=").append(lowY).append("; columns x=").append(x0).append("..")
                .append(x1).append("):\n");
        for (int y = topY; y >= lowY; y--) {
            StringBuilder row = new StringBuilder();
            for (int x = x0; x <= x1; x++) {
                char c = ' ';
                for (int z = z0; z <= z1; z++) {
                    char s = symbol(idAt(new Pos(x, y, z)));
                    if (s != ' ') {
                        c = s;
                        break;
                    }
                }
                row.append(c);
            }
            sb.append(row.toString().stripTrailing()).append('\n');
        }
        sb.append(LEGEND).append('\n');
        return sb.toString();
    }

    /** Grundriss je Schicht, oben die hintere Reihe (größtes z), Spalten x von links nach rechts. */
    private String layerMaps(Structure s) {
        int w = s.max().x() - s.min().x() + 1;
        int d = s.max().z() - s.min().z() + 1;
        if (w > MAP_MAX_FOOTPRINT || d > MAP_MAX_FOOTPRINT) {
            return "";
        }
        Map<String, Character> legend = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("Structure ").append(s.number()).append(" layers (row = one z, top row z=").append(s.max().z())
                .append(", bottom row z=").append(s.min().z()).append("; column = x from ").append(s.min().x())
                .append("; '.' empty):\n");
        for (int y = s.min().y(); y <= s.max().y(); y++) {
            sb.append("y=").append(y).append('\n');
            for (int z = s.max().z(); z >= s.min().z(); z--) {
                for (int x = s.min().x(); x <= s.max().x(); x++) {
                    Pos p = new Pos(x, y, z);
                    if (!structureCells.contains(p)) {
                        sb.append('.');
                        continue;
                    }
                    String id = blocks.get(p);
                    sb.append(legend.computeIfAbsent(id,
                            k -> MAP_SYMBOLS.charAt(legend.size() % MAP_SYMBOLS.length())));
                }
                sb.append('\n');
            }
        }
        StringBuilder leg = new StringBuilder("Legend: ");
        legend.forEach((id, c) -> leg.append(c).append('=').append(id).append(' '));
        return sb.append(leg.toString().trim()).append('\n').toString();
    }
}
