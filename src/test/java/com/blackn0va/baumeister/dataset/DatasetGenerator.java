package com.blackn0va.baumeister.dataset;

import com.blackn0va.baumeister.dataset.Phrases.Gender;
import com.blackn0va.baumeister.dataset.Phrases.Noun;
import com.blackn0va.baumeister.dataset.PlanText.S;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.QuickOrders;
import com.blackn0va.baumeister.plan.RequestHints;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Lehrset für ein eigenes Baumeister-Modell: Paare Auftragstext → Plan-JSON, erzeugt aus den Bauwegen, die es ohne LLM
 * schon gibt (QuickOrders, QuickForms, Bunker, MachineHall, Maschinenbibliothek, op house/skyscraper/logic) und aus
 * zusammengesetzten Aufträgen (Haus mit Garten, Dorf, Turm, Hochhaus mit Platz). Nur Pläne, die PlanJudge.rejection
 * ohne jeden Befund bestehen, kommen hinein.
 *
 * Warum aus den festen Bauwegen: Diese Pläne sind im Spiel geprüft und rechnen ihre Geometrie im Compiler – das kleine
 * Modell soll lernen, die richtige op mit den richtigen Maßen zu wählen, statt Wände, Fenster und Zinnen Block für
 * Block zu setzen. Genau dort scheiterten die verworfenen LLM-Pläne (dev01 2026-10-31: 55 von 136 verworfen).
 *
 * Aufruf (nach mvn test-compile):
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.dataset.DatasetGenerator
 *        [--anzahl 4000] [--seed 20261031] [--ziel target/dataset] [--pläne <ordner mit dev01-Plänen>]
 *        [--vorbilder <ordner mit Bauwerk-Dateien und vorbilder.json>]
 *
 * Threads: ein Lauf je Instanz. Fehler: main beendet mit Exitcode 1 bei Schreibfehlern; unbrauchbare Kandidaten
 * werden gezählt (Statistik), nie stillschweigend verworfen.
 */
public final class DatasetGenerator {

    /** Ein Eintrag im Lehrset. kind = Art für Statistik und Aufteilung. */
    public record Sample(String kind, String order, String json) {
    }

    /** Ein Kandidat vor der Prüfung. */
    record Candidate(String kind, String order, String json) {
    }

    /** Zähler je Art: versucht, angenommen, doppelt, verworfen mit Gründen. */
    public static final class KindStats {
        int tried;
        int accepted;
        int duplicate;
        int noCandidate;
        final Map<String, Integer> rejected = new TreeMap<>();

        public int accepted() {
            return accepted;
        }

        public int tried() {
            return tried;
        }

        public Map<String, Integer> rejected() {
            return rejected;
        }
    }

    public record Result(List<Sample> samples, Map<String, KindStats> stats) {
    }

    /** Wand-, Treppen- und Holzbezug eines Materials; word ist die Form nach „aus“. */
    record Material(String word, String block, String stairs, boolean wood) {
    }

    private static final List<Material> HOUSE_MATERIALS = List.of(
            new Material("Holz", "oak_planks", "oak_stairs", true),
            new Material("Eichenholz", "oak_planks", "oak_stairs", true),
            new Material("Fichtenholz", "spruce_planks", "spruce_stairs", true),
            new Material("Birkenholz", "birch_planks", "birch_stairs", true),
            new Material("Dunkeleichenholz", "dark_oak_planks", "dark_oak_stairs", true),
            new Material("Akazienholz", "acacia_planks", "acacia_stairs", true),
            new Material("Kirschholz", "cherry_planks", "cherry_stairs", true),
            new Material("Mangrovenholz", "mangrove_planks", "mangrove_stairs", true),
            new Material("Ziegeln", "bricks", "brick_stairs", false),
            new Material("Backstein", "bricks", "brick_stairs", false),
            new Material("Steinziegeln", "stone_bricks", "stone_brick_stairs", false),
            new Material("Bruchstein", "cobblestone", "cobblestone_stairs", false),
            new Material("Sandstein", "sandstone", "sandstone_stairs", false),
            new Material("Quarz", "quartz_block", "quartz_stairs", false),
            new Material("Tiefenschiefer", "deepslate_bricks", "deepslate_brick_stairs", false),
            new Material("Stein", "stone", "stone_stairs", false));

    private static final List<Material> TOWER_MATERIALS = List.of(
            new Material("Stein", "stone_bricks", "stone_brick_stairs", false),
            new Material("Steinziegeln", "stone_bricks", "stone_brick_stairs", false),
            new Material("Bruchstein", "cobblestone", "cobblestone_stairs", false),
            new Material("Sandstein", "sandstone", "sandstone_stairs", false),
            new Material("Tiefenschiefer", "deepslate_bricks", "deepslate_brick_stairs", false),
            new Material("Ziegeln", "bricks", "brick_stairs", false),
            new Material("Quarz", "quartz_block", "quartz_stairs", false));

    /** Zinnen je Wandmaterial (es gibt nicht zu jedem Block eine Mauer). */
    private static final Map<String, String> WALL_OF = Map.of("stone_bricks", "stone_brick_wall", "cobblestone",
            "cobblestone_wall", "sandstone", "sandstone_wall", "deepslate_bricks", "deepslate_brick_wall", "bricks",
            "brick_wall", "quartz_block", "cobblestone_wall");

    private static final String[] NUMBER_WORDS = {"null", "ein", "zwei", "drei", "vier"};

    private final PlanJudge judge;
    private final Set<String> benchOrders;

    public DatasetGenerator(PlanJudge judge) {
        this.judge = judge;
        this.benchOrders = new HashSet<>();
        for (String o : BenchOrders.load()) {
            benchOrders.add(RequestHints.normalize(o));
        }
    }

    /** Arten mit Gewicht (Anteil der Versuche). Summe beliebig. */
    private Map<String, Function<Random, Optional<Candidate>>> kinds() {
        Map<String, Function<Random, Optional<Candidate>>> k = new LinkedHashMap<>();
        k.put("haus", r -> house(r, false));
        k.put("anwesen", r -> house(r, true));
        k.put("turm", this::tower);
        k.put("dorf", this::village);
        k.put("hochhaus", this::highRise);
        k.put("bauform", this::form);
        k.put("burg_mit_bäumen", this::castleWithTrees);
        k.put("logik", this::logic);
        k.put("maschine", this::machine);
        k.put("sonderbau", this::special);
        return k;
    }

    private static final Map<String, Integer> WEIGHTS = Map.of("haus", 20, "anwesen", 12, "turm", 12, "dorf", 6,
            "hochhaus", 8, "bauform", 16, "burg_mit_bäumen", 4, "logik", 12, "maschine", 8, "sonderbau", 2);

    /**
     * @param count Zielzahl angenommener Einträge (ohne Doppelte); Abbruch spätestens nach 20 × count Versuchen,
     *              falls eine Art kaum Neues mehr liefert
     */
    public Result generate(int count, long seed) {
        Random r = new Random(seed);
        Map<String, Function<Random, Optional<Candidate>>> kinds = kinds();
        Map<String, KindStats> stats = new LinkedHashMap<>();
        kinds.keySet().forEach(k -> stats.put(k, new KindStats()));
        List<String> names = new ArrayList<>(kinds.keySet());
        int total = names.stream().mapToInt(WEIGHTS::get).sum();
        Set<String> seen = new HashSet<>();
        List<Sample> out = new ArrayList<>();
        for (int attempt = 0; out.size() < count && attempt < count * 20; attempt++) {
            int pick = r.nextInt(total);
            String kind = names.getFirst();
            for (String n : names) {
                pick -= WEIGHTS.get(n);
                if (pick < 0) {
                    kind = n;
                    break;
                }
            }
            KindStats ks = stats.get(kind);
            ks.tried++;
            Optional<Candidate> c;
            try {
                c = kinds.get(kind).apply(r);
            } catch (IllegalArgumentException e) {
                ks.rejected.merge("teil_unübersetzbar", 1, Integer::sum);
                continue;
            }
            if (c.isEmpty()) {
                ks.noCandidate++;
                continue;
            }
            String key = RequestHints.normalize(c.get().order());
            if (nearBench(key) || !seen.add(key)) {
                ks.duplicate++;
                continue;
            }
            Optional<String> why = judge.rejection(c.get().order(), c.get().json());
            if (why.isPresent()) {
                seen.remove(key);
                ks.rejected.merge(shortReason(why.get()), 1, Integer::sum);
                continue;
            }
            ks.accepted++;
            out.add(new Sample(kind, c.get().order(), c.get().json()));
        }
        return new Result(out, stats);
    }

    /**
     * Testauftrag oder ein Auftrag, der einen Testauftrag ganz enthält („Wachturm aus Holz mit Dach“). Vorher galt nur
     * genau derselbe Text als gesperrt; 2026-11-01 stand „Bau mir einen Wachturm aus Holz“ fast wörtlich im Lehrset, und
     * die Messung hätte dort Auswendiglernen statt Planen gezählt.
     */
    boolean nearBench(String normalized) {
        for (String b : benchOrders) {
            if (normalized.contains(b) || b.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Grund ohne Koordinaten, damit sich gleiche Fehler in der Statistik zusammenzählen. */
    static String shortReason(String why) {
        String s = why.replaceAll("\\[[-0-9, ]+\\]", "[..]").replaceAll("-?\\d+", "#");
        return s.length() > 110 ? s.substring(0, 110) + "…" : s;
    }

    // ---------------------------------------------------------------- Haus

    private static final List<int[]> SMALL_HOUSE = List.of(new int[] {7, 7}, new int[] {5, 7}, new int[] {7, 9},
            new int[] {6, 6}, new int[] {7, 8});
    private static final List<int[]> MEDIUM_HOUSE = List.of(new int[] {9, 9}, new int[] {9, 11}, new int[] {10, 10},
            new int[] {11, 9}, new int[] {8, 10});
    private static final List<int[]> LARGE_HOUSE = List.of(new int[] {11, 13}, new int[] {13, 13},
            new int[] {15, 11}, new int[] {12, 14}, new int[] {14, 12});

    private Optional<Candidate> house(Random r, boolean estate) {
        Material m = Phrases.pick(r, HOUSE_MATERIALS);
        int size = r.nextInt(3);
        int[] wd = Phrases.pick(r, size == 0 ? SMALL_HOUSE : size == 1 ? MEDIUM_HOUSE : LARGE_HOUSE);
        int w = wd[0];
        int d = wd[1];
        int floors = switch (size) {
            case 0 -> r.nextInt(100) < 20 ? 2 : 1;
            case 1 -> Phrases.between(r, 1, 2);
            default -> r.nextInt(100) < 10 ? 4 : Phrases.between(r, 2, 3);
        };
        boolean sayMaterial = r.nextInt(100) < 80;
        Material used = sayMaterial ? m : HOUSE_MATERIALS.getFirst();
        List<String> adjectives = new ArrayList<>();
        if (size == 0 && r.nextBoolean()) {
            adjectives.add("klein");
        } else if (size == 2 && r.nextBoolean()) {
            adjectives.add("groß");
        } else if (r.nextInt(100) < 25) {
            adjectives.add(Phrases.pick(r, List.of("gemütlich", "schön", "einfach", "hübsch")));
        }
        String floorsRest = "";
        if (floors > 1 || r.nextInt(100) < 15) {
            switch (r.nextInt(3)) {
                case 0 -> adjectives.add(NUMBER_WORDS[floors] + "stöckig");
                case 1 -> adjectives.add(floors + "-stöckig");
                default -> floorsRest = "mit " + floors + " " + (floors == 1 ? Phrases.pick(r, List.of("Etage",
                        "Stockwerk")) : Phrases.pick(r, List.of("Etagen", "Stockwerken", "Geschossen")));
            }
        }
        Noun noun;
        if (used.wood() && !sayMaterial && r.nextBoolean()) {
            noun = new Noun("Holzhaus", Gender.N);
        } else if (floors == 1 && size == 0 && used.wood() && r.nextInt(100) < 30) {
            noun = new Noun(r.nextBoolean() ? "Hütte" : "Holzhütte", Gender.F);
        } else {
            noun = new Noun(Phrases.pick(r, List.of("Haus", "Haus", "Wohnhaus", "Landhaus")), Gender.N);
        }
        List<String> rest = new ArrayList<>();
        if (!floorsRest.isEmpty()) {
            rest.add(floorsRest);
        }
        if (sayMaterial) {
            rest.add("aus " + m.word());
        }
        boolean saySize = r.nextInt(100) < 20;
        if (saySize) {
            rest.add(Phrases.pick(r, List.of("(" + w + "x" + d + ")", w + "x" + d + " groß", "mit " + w + "x" + d
                    + " Grundfläche")));
        }

        int mid = (w - 1) / 2;
        List<JsonObject> steps = new ArrayList<>();
        S h = S.op("house").block(used.block()).from(0, 0, 0).to(w - 1, 0, d - 1).num("floors", floors);
        boolean darkRoof = !used.wood() && r.nextInt(100) < 30;
        String roof = darkRoof ? "dark_oak_stairs" : used.stairs();
        if (darkRoof || r.nextBoolean()) {
            h.str("roof_block", roof);
        }
        steps.add(h.json());
        StringBuilder design = new StringBuilder("house " + w + "x" + d + " x0-" + (w - 1) + " z0-" + (d - 1) + ", "
                + floors + (floors == 1 ? " storey" : " storeys") + ", walls " + used.block() + ", roof " + roof
                + "; the server adds floor, door front at x" + mid + ", windows, " + (floors > 1 ? "ladder, " : "")
                + "roof and furniture.");
        if (darkRoof) {
            rest.add(Phrases.pick(r, List.of("mit dunklem Dach", "mit einem Dach aus dunkler Eiche")));
        }

        // Weg zur Tür: mal verlangt, mal wie im Prompt-Beispiel ungefragt dazu.
        int pathLen = Phrases.between(r, 3, 5);
        String pathBlock = "dirt_path";
        boolean askPath = r.nextInt(100) < 35;
        if (askPath) {
            int kind = r.nextInt(3);
            pathBlock = kind == 0 ? "dirt_path" : kind == 1 ? "gravel" : "cobblestone";
            rest.add(kind == 0 ? "mit einem Weg zur Tür" : kind == 1 ? "mit einem Kiesweg" : "mit einem Steinweg");
        }
        boolean path = askPath || r.nextInt(100) < 45;
        String fenceBlock = used.wood() ? used.block().replace("_planks", "_fence") : "oak_fence";
        int fenceKind = r.nextInt(100) < 30 ? (r.nextBoolean() ? 1 : 2) : 0;  // 1 davor, 2 drumherum
        boolean lanterns = r.nextInt(100) < 20;
        if (path || fenceKind > 0) {
            steps.add(S.op("line").block(pathBlock).from(mid, 0, -1).to(mid, 0, -pathLen).json());
            design.append(" path ").append(pathBlock).append(" y0 x").append(mid).append(" z-1..-").append(pathLen)
                    .append(" to the door.");
        }
        int fz = -(pathLen + 1);
        if (fenceKind > 0) {
            rest.add(fenceKind == 1 ? Phrases.pick(r, List.of("mit Zaun davor", "mit einem Zaun vorne"))
                    : Phrases.pick(r, List.of("mit Zaun drumherum", "mit einem Zaun ringsum")));
            steps.add(S.op("line").block(fenceBlock).from(-2, 0, fz).to(mid - 1, 0, fz).json());
            steps.add(S.op("line").block(fenceBlock).from(mid + 1, 0, fz).to(w + 1, 0, fz).json());
            design.append(" fence ").append(fenceBlock).append(" y0 at z").append(fz).append(" from x-2 to x")
                    .append(w + 1).append(" with a gate gap at x").append(mid).append('.');
            if (fenceKind == 2) {
                steps.add(S.op("line").block(fenceBlock).from(-2, 0, fz + 1).to(-2, 0, d + 1).json());
                steps.add(S.op("line").block(fenceBlock).from(w + 1, 0, fz + 1).to(w + 1, 0, d + 1).json());
                steps.add(S.op("line").block(fenceBlock).from(-1, 0, d + 1).to(w, 0, d + 1).json());
                design.append(" side fences x-2 and x").append(w + 1).append(", back fence z").append(d + 1)
                        .append('.');
            }
        }
        if (lanterns) {
            rest.add(Phrases.pick(r, List.of("mit Laternen am Eingang", "mit zwei Laternen")));
            int ly = fenceKind > 0 ? 1 : 0;
            int lz = fenceKind > 0 ? fz : -2;
            steps.add(S.op("set").block("lantern").pos(mid - 1, ly, lz).json());
            steps.add(S.op("set").block("lantern").pos(mid + 1, ly, lz).json());
            design.append(" lanterns at x").append(mid - 1).append(" and x").append(mid + 1).append(" z").append(lz)
                    .append(fenceKind > 0 ? " on the fence posts." : " beside the path.");
        }
        String reply = houseReply(r, noun, floors, sayMaterial ? m : null);
        if (estate) {
            int extra = r.nextInt(3);  // 0 Garten, 1 Bäume, 2 beides
            if (extra != 1) {
                int gw = Phrases.between(r, 7, 12);
                int gd = Phrases.between(r, 7, Math.max(7, d));
                List<JsonObject> garden = List.of(S.op("garden").block(fenceBlock).from(0, 0, 0)
                        .to(gw - 1, 0, gd - 1).str("facing", "front").json());
                int dx = placeRight(steps, garden, 2);
                steps.add(shift(garden.getFirst(), dx, 0, 0));
                rest.add(Phrases.pick(r, List.of("mit einem Garten daneben", "mit Garten", "mit einem kleinen "
                        + "Garten")));
                design.append(" garden ").append(gw).append('x').append(gd).append(" right of the house at x")
                        .append(dx).append('-').append(dx + gw - 1).append(", gate front.");
            }
            if (extra != 0) {
                int trees = Phrases.between(r, 1, 3);
                String log = Phrases.pick(r, List.of("oak_log", "birch_log", "spruce_log", "cherry_log"));
                String style = log.equals("spruce_log") ? "cone" : "round";
                for (int t = 0; t < trees; t++) {
                    List<JsonObject> tree = List.of(S.op("tree").block(log).pos(0, 0, 0)
                            .num("height", Phrases.between(r, 6, 10)).str("style", style).json());
                    int dx = placeLeft(steps, tree, 2);
                    int dz = t == 0 ? d / 2 : 0;
                    steps.add(shift(tree.getFirst(), dx, 0, dz));
                    design.append(" tree ").append(log).append(" at x").append(dx).append(" z").append(dz)
                            .append('.');
                }
                String treeWord = log.equals("spruce_log") ? (trees == 1 ? "einer Tanne" : NUMBER_WORDS[trees]
                        + " Tannen") : log.equals("birch_log") ? (trees == 1 ? "einer Birke" : NUMBER_WORDS[trees]
                        + " Birken") : trees == 1 ? "einem Baum" : NUMBER_WORDS[trees] + " Bäumen";
                rest.add((extra == 2 ? "und " : "mit ") + treeWord + " daneben");
            }
        }
        String order = Phrases.order(r, noun, adjectives, String.join(" ", rest));
        return Optional.of(new Candidate(estate ? "anwesen" : "haus", order, PlanText.render(design.toString(),
                reply, steps)));
    }

    private static String houseReply(Random r, Noun noun, int floors, Material m) {
        String what = switch (noun.word()) {
            case "Hütte", "Holzhütte" -> "eine gemütliche " + noun.word();
            default -> floors > 1 ? "ein " + floors + "-stöckiges " + noun.word() : "ein " + noun.word();
        };
        String mat = m == null ? "" : " aus " + m.word();
        return Phrases.pick(r, List.of("Klar, ich baue dir " + what + mat + "!", what.substring(0, 1).toUpperCase()
                + what.substring(1) + mat + " – ich fange gleich an!", "Gern, " + what + mat + " kommt sofort!",
                "Alles klar, ich baue " + what + mat + "."));
    }

    // ---------------------------------------------------------------- Turm

    private Optional<Candidate> tower(Random r) {
        return r.nextInt(100) < 60 ? roundTower(r) : squareTower(r);
    }

    private Optional<Candidate> roundTower(Random r) {
        Material m = Phrases.pick(r, TOWER_MATERIALS);
        int size = r.nextInt(3);
        int radius = size == 0 ? 3 : size == 1 ? 4 : 5;
        int height = size == 0 ? Phrases.between(r, 8, 10) : size == 1 ? Phrases.between(r, 11, 14)
                : Phrases.between(r, 15, 20);
        List<String> adj = new ArrayList<>();
        String rest = "";
        boolean sayHeight = r.nextInt(100) < 20;
        if (sayHeight) {
            rest = height + " Blöcke hoch";
        } else if (size == 0 && r.nextBoolean()) {
            adj.add("klein");
        } else if (size == 2 && r.nextBoolean()) {
            adj.add(r.nextBoolean() ? "hoh" : "groß");
        }
        if (r.nextInt(100) < 40) {
            adj.add("rund");
        }
        Noun noun = new Noun(Phrases.pick(r, List.of("Turm", "Turm", "Wachturm", "Burgturm", "Steinturm")),
                Gender.M);
        boolean sayMat = !noun.word().equals("Steinturm") || r.nextBoolean();
        Material used = sayMat ? m : TOWER_MATERIALS.getFirst();
        rest = (sayMat ? "aus " + m.word() + (rest.isEmpty() ? "" : " ") : "") + rest;
        boolean battlement = r.nextInt(100) < 85;
        if (battlement && r.nextInt(100) < 30) {
            rest += " mit Zinnen";
        }
        int c = radius;
        String door = Phrases.pick(r, List.of("spruce_door", "oak_door", "dark_oak_door"));
        String wall = WALL_OF.getOrDefault(used.block(), "cobblestone_wall");
        List<JsonObject> steps = new ArrayList<>();
        steps.add(S.op("cylinder").block(used.block()).pos(c, 0, c).num("radius", radius).num("height", 1).json());
        steps.add(S.op("cylinder").block(used.block()).pos(c, 1, c).num("radius", radius).num("height", height)
                .flag("hollow").json());
        steps.add(S.op("door").block(door).pos(c, 1, 0).str("facing", "front").json());
        StringBuilder windows = new StringBuilder();
        int side = 0;
        for (int y = 5; y <= height - 2; y += 4) {
            int[] p = switch (side++ % 3) {
                case 0 -> new int[] {0, y, c};
                case 1 -> new int[] {2 * c, y, c};
                default -> new int[] {c, y, 2 * c};
            };
            steps.add(S.op("set").block("glass_pane").pos(p[0], p[1], p[2]).json());
            windows.append(windows.isEmpty() ? "" : ", ").append("y").append(y);
        }
        int top = height + 1;
        steps.add(S.op("cylinder").block(used.block()).pos(c, top, c).num("radius", radius).num("height", 1)
                .json());
        if (battlement) {
            steps.add(S.op("cylinder").block(wall).pos(c, top + 1, c).num("radius", radius).num("height", 1)
                    .flag("hollow").json());
        }
        steps.add(S.op("set").block("lantern").pos(c, top + 1, c).json());
        String design = "round tower radius " + radius + " center x" + c + " z" + c + ". floor " + used.block()
                + " y0. tube " + used.block() + " y1-" + height + ". windows glass_pane at " + windows + ". door front"
                + " at x" + c + " z0. top floor y" + top + (battlement ? ", battlement ring y" + (top + 1) + " with "
                + wall : "") + ", lantern on top.";
        String reply = Phrases.pick(r, List.of("Ein stolzer Turm" + (sayMat ? " aus " + m.word() : "")
                + " kommt sofort!", "Klar, ich baue dir einen Turm mit " + height + " Blöcken Höhe!", "Gern – dein "
                + "Turm entsteht gleich!"));
        return Optional.of(new Candidate("turm", Phrases.order(r, noun, adj, rest.trim()),
                PlanText.render(design, reply, steps)));
    }

    private Optional<Candidate> squareTower(Random r) {
        boolean wood = r.nextBoolean();
        Material m = wood ? Phrases.pick(r, HOUSE_MATERIALS.subList(0, 8)) : Phrases.pick(r, TOWER_MATERIALS);
        int s = Phrases.between(r, 5, 7);
        int height = Phrases.between(r, 8, 14);
        int mid = (s - 1) / 2;
        List<String> adj = new ArrayList<>(List.of(r.nextBoolean() ? "eckig" : "viereckig"));
        Noun noun = new Noun(wood ? Phrases.pick(r, List.of("Wachturm", "Turm", "Aussichtsturm")) : "Turm",
                Gender.M);
        if (noun.word().equals("Wachturm") && r.nextBoolean()) {
            adj.clear();
        }
        String rest = "aus " + m.word() + (r.nextInt(100) < 20 ? " mit Dach" : "");
        String shape = r.nextBoolean() ? "hip" : "gable";
        List<JsonObject> steps = new ArrayList<>();
        steps.add(S.op("fill").block(wood ? "cobblestone" : m.block()).from(0, 0, 0).to(s - 1, 0, s - 1).json());
        steps.add(S.op("walls").block(m.block()).from(0, 1, 0).to(s - 1, height, s - 1).json());
        if (wood) {
            String log = m.block().replace("_planks", "_log");
            for (int[] k : new int[][] {{0, 0}, {s - 1, 0}, {0, s - 1}, {s - 1, s - 1}}) {
                steps.add(S.op("fill").block(log).from(k[0], 1, k[1]).to(k[0], height, k[1]).json());
            }
        }
        String door = wood ? m.block().replace("_planks", "_door") : "spruce_door";
        steps.add(S.op("door").block(door).pos(mid, 1, 0).str("facing", "front").json());
        int wy = height - 2;
        steps.add(S.op("set").block("glass_pane").pos(mid, wy, 0).json());
        steps.add(S.op("set").block("glass_pane").pos(0, wy, mid).json());
        steps.add(S.op("set").block("glass_pane").pos(s - 1, wy, mid).json());
        steps.add(S.op("set").block("glass_pane").pos(mid, wy, s - 1).json());
        S roof = S.op("roof").block(m.stairs()).from(0, height + 1, 0).to(s - 1, height + 1, s - 1)
                .str("shape", shape);
        if (shape.equals("gable")) {
            roof.str("gable_block", m.block());
        }
        steps.add(roof.json());
        steps.add(S.op("set").block("lantern").pos(mid, 1, mid).json());
        String design = "square tower " + s + "x" + s + " x0-" + (s - 1) + " z0-" + (s - 1) + ", walls " + m.block()
                + " y1-" + height + (wood ? " with log corners" : "") + ", door front at x" + mid + ", windows y" + wy
                + " on all four sides, " + shape + " roof " + m.stairs() + " at y" + (height + 1)
                + ", lantern inside.";
        String reply = Phrases.pick(r, List.of("Ein " + noun.word() + " aus " + m.word() + " – ich fange an!",
                "Klar, dein " + noun.word() + " kommt sofort!", "Gern, ich baue dir einen eckigen Turm aus "
                + m.word() + "."));
        return Optional.of(new Candidate("turm", Phrases.order(r, noun, adj, rest), PlanText.render(design, reply,
                steps)));
    }

    // ---------------------------------------------------------------- Dorf

    private Optional<Candidate> village(Random r) {
        int n = Phrases.between(r, 2, 4);
        Material m = Phrases.pick(r, HOUSE_MATERIALS);
        boolean sameMaterial = r.nextInt(100) < 70;
        String pathBlock = Phrases.pick(r, List.of("dirt_path", "gravel", "cobblestone"));
        List<JsonObject> steps = new ArrayList<>();
        StringBuilder design = new StringBuilder("row of " + n + " houses along x with 3 blocks between the roofs:");
        List<Integer> doors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Material hm = sameMaterial ? m : Phrases.pick(r, HOUSE_MATERIALS);
            int w = Phrases.pick(r, List.of(7, 7, 9));
            int d = Phrases.pick(r, List.of(7, 9));
            List<JsonObject> house = List.of(S.op("house").block(hm.block()).from(0, 0, 0).to(w - 1, 0, d - 1)
                    .num("floors", r.nextInt(100) < 25 ? 2 : 1).json());
            int dx = steps.isEmpty() ? 0 : placeRight(steps, house, 3);
            steps.add(shift(house.getFirst(), dx, 0, 0));
            int door = dx + (w - 1) / 2;
            doors.add(door);
            design.append(" house ").append(w).append('x').append(d).append(' ').append(hm.block()).append(" x")
                    .append(dx).append('-').append(dx + w - 1).append(" (door x").append(door).append(')')
                    .append(i + 1 < n ? ";" : ".");
        }
        Pos[] all = judge.extent(steps);
        steps.add(S.op("line").block(pathBlock).from(all[0].x(), 0, -2).to(all[1].x(), 0, -2).json());
        for (int door : doors) {
            steps.add(S.op("set").block(pathBlock).pos(door, 0, -1).json());
        }
        design.append(" main path ").append(pathBlock).append(" y0 z-2 from x").append(all[0].x()).append(" to x")
                .append(all[1].x()).append(", one path block z-1 in front of each door.");
        String count = NUMBER_WORDS[n].equals("ein") ? "einem" : (r.nextBoolean() ? NUMBER_WORDS[n] : "" + n);
        String order = switch (r.nextInt(3)) {
            case 0 -> Phrases.order(r, new Noun("Dorf", Gender.N), r.nextBoolean() ? List.of("klein")
                    : List.of(), "mit " + count + " Häusern" + (sameMaterial ? " aus " + m.word() : ""));
            case 1 -> Phrases.orderPhrase(r, count + " Häuser" + (sameMaterial ? " aus " + m.word() : "")
                    + " nebeneinander mit einem Weg davor", count + " Häuser nebeneinander mit Weg");
            default -> Phrases.order(r, new Noun("Häuserreihe", Gender.F), List.of(), "mit " + count + " Häusern"
                    + (sameMaterial ? " aus " + m.word() : ""));
        };
        String reply = Phrases.pick(r, List.of("Ein kleines Dorf mit " + n + " Häusern – ich lege los!", "Klar, "
                + n + " Häuser mit einem Weg davor kommen sofort!"));
        return Optional.of(new Candidate("dorf", order, PlanText.render(design.toString(), reply, steps)));
    }

    // ---------------------------------------------------------------- Hochhaus

    private static final List<String[]> FACADES = List.of(new String[] {"Beton", "light_gray_concrete"},
            new String[] {"Quarz", "quartz_block"}, new String[] {"Sandstein", "sandstone"},
            new String[] {"Steinziegeln", "stone_bricks"}, new String[] {"Tiefenschiefer", "deepslate_bricks"},
            new String[] {"Ziegeln", "bricks"});

    private Optional<Candidate> highRise(Random r) {
        int floors = Phrases.between(r, 5, 40);
        boolean area = r.nextInt(100) < 25;
        int w = area ? Phrases.between(r, 10, 24) : 16;
        int d = area ? Phrases.between(r, 10, 24) : 16;
        String[] mat = r.nextInt(100) < 50 ? Phrases.pick(r, FACADES) : null;
        String floorWord = Phrases.pick(r, List.of("Stockwerken", "Etagen", "Geschossen"));
        Noun noun = r.nextInt(100) < 70 ? new Noun("Hochhaus", Gender.N) : new Noun("Wolkenkratzer", Gender.M);
        boolean extras = r.nextInt(100) < 45;
        String base = "mit " + floors + " " + floorWord + (mat != null ? " aus " + mat[0] : "") + (area ? " " + w
                + "x" + d : "");
        if (!extras) {
            String quick = Phrases.quickOrder(r, noun, List.of(), base);
            Optional<String> plan = QuickOrders.match(quick, judge.modules(), PlanJudge.MAX_EXTENT);
            return plan.map(p -> new Candidate("hochhaus", quick, reRender(p)));
        }
        String facade = mat != null ? mat[1] : "light_gray_concrete";
        String window = "light_blue_stained_glass";
        boolean plaza = r.nextBoolean();
        boolean antenna = !plaza || r.nextBoolean();
        List<JsonObject> steps = new ArrayList<>();
        StringBuilder design = new StringBuilder();
        if (plaza) {
            steps.add(S.op("fill").block("smooth_stone").from(-3, 0, -3).to(w + 2, 0, -1).json());
            design.append("plaza smooth_stone y0 x-3..").append(w + 2).append(" z-3..-1 in front. ");
        }
        steps.add(S.op("skyscraper").block(facade).from(0, 0, 0).to(w - 1, 0, d - 1).num("floors", floors)
                .str("window_block", window).json());
        design.insert(0, "skyscraper " + w + "x" + d + " x0-" + (w - 1) + " z0-" + (d - 1) + ", " + floors
                + " floors = " + floors * 4 + " blocks high, facade " + facade + ", windows " + window + ". ");
        String rest = base;
        if (plaza) {
            rest += Phrases.pick(r, List.of(" und einem Platz davor", " mit Vorplatz", " und einem gepflasterten "
                    + "Platz vor dem Eingang"));
        }
        if (antenna) {
            int y = floors * 4 + 1;
            int cx = w / 2;
            int cz = d / 2;
            steps.add(S.op("line").block("iron_bars").from(cx, y, cz).to(cx, y + 8, cz).json());
            design.append("roof at y").append(floors * 4).append(", antenna iron_bars x").append(cx).append(" z")
                    .append(cz).append(" y").append(y).append('-').append(y + 8).append('.');
            rest += Phrases.pick(r, List.of(" und Antenne", " mit einer Antenne auf dem Dach", " und einer "
                    + "Antenne"));
        }
        String reply = Phrases.pick(r, List.of("Ein " + floors + "-stöckiges Hochhaus – das wird ein Riese!",
                "Klar, dein Hochhaus mit " + floors + " Stockwerken kommt sofort!"));
        return Optional.of(new Candidate("hochhaus", Phrases.order(r, noun, List.of(), rest),
                PlanText.render(design.toString().trim(), reply, steps)));
    }

    // ---------------------------------------------------------------- Bauformen (QuickForms)

    private Optional<Candidate> form(Random r) {
        String np;
        String nom;
        switch (r.nextInt(5)) {
            case 0 -> {
                String noun = Phrases.pick(r, List.of("Burg", "Burg", "Ritterburg", "Festung"));
                String size = Phrases.pick(r, List.of("", "", "kleine ", "große ", "riesige "));
                String extra = Phrases.pick(r, List.of("", "", " aus Sandstein", " aus Steinziegeln",
                        " aus Bruchstein", " aus Tiefenschiefer", " aus Quarz", " " + Phrases.between(r, 20, 45)
                        + "x" + Phrases.between(r, 20, 45)));
                np = "eine " + size + noun + extra;
                nom = np;
            }
            case 1 -> {
                String noun = Phrases.pick(r, List.of("Mauer", "Stadtmauer", "Burgmauer", "Schutzmauer"));
                String extra = Phrases.pick(r, List.of(" " + Phrases.between(r, 10, 60) + " Blöcke lang", " "
                        + Phrases.between(r, 10, 40) + " Blöcke lang aus Sandstein", " aus Steinziegeln", "",
                        " " + Phrases.between(r, 15, 40) + " Blöcke lang und " + Phrases.between(r, 4, 9)
                        + " Blöcke hoch"));
                np = "eine " + noun + extra.replace(" und ", " ");
                nom = np;
            }
            case 2 -> {
                String noun = Phrases.pick(r, List.of("Brücke", "Steinbrücke", "Holzbrücke", "Bogenbrücke",
                        "Balkenbrücke"));
                String extra = Phrases.pick(r, List.of("", " über " + Phrases.between(r, 8, 40) + " Blöcke", " "
                        + Phrases.between(r, 10, 40) + " Blöcke lang", " über " + Phrases.between(r, 8, 30)
                        + " Blöcke aus Sandstein"));
                np = "eine " + noun + extra;
                nom = np;
            }
            case 3 -> {
                String noun = Phrases.pick(r, List.of("Garten", "Blumengarten", "Ziergarten"));
                String size = Phrases.pick(r, List.of("", "kleinen ", "großen "));
                String extra = Phrases.pick(r, List.of("", "", " " + Phrases.between(r, 6, 30) + "x"
                        + Phrases.between(r, 6, 30)));
                np = "einen " + size + noun + extra;
                nom = "ein " + size.replace("en ", "er ") + noun + extra;
            }
            default -> {
                String[] t = Phrases.pick(r, List.of(new String[] {"einen", "Baum"}, new String[] {"eine", "Tanne"},
                        new String[] {"eine", "Eiche"}, new String[] {"eine", "Birke"}, new String[] {"einen",
                        "Kirschbaum"}, new String[] {"einen", "Tannenbaum"}, new String[] {"eine", "Fichte"},
                        new String[] {"einen", "Laubbaum"}, new String[] {"einen", "Nadelbaum"}));
                boolean masc = t[0].equals("einen");
                String size = Phrases.pick(r, List.of("", "", masc ? "großen " : "große ", masc ? "kleinen "
                        : "kleine ", masc ? "riesigen " : "riesige "));
                String extra = Phrases.pick(r, List.of("", "", " " + Phrases.between(r, 5, 30) + " Blöcke hoch"));
                np = t[0] + " " + size + t[1] + extra;
                nom = (masc ? "ein " : "eine ") + size.replace("en ", masc ? "er " : "e ") + t[1] + extra;
            }
        }
        String quick = Phrases.quickPhrase(r, np, nom);
        Optional<String> plan = QuickOrders.match(quick, judge.modules(), PlanJudge.MAX_EXTENT);
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        // Halb als Schnellauftrag, halb in freier Form („Kannst du mir … bauen?“): Dann landet derselbe Wunsch beim
        // LLM, und das soll denselben Plan schreiben, den der Baumeister ohne LLM gebaut hätte.
        String order = r.nextBoolean() ? quick : Phrases.orderPhrase(r, np, nom);
        return Optional.of(new Candidate("bauform", order, reRender(plan.get())));
    }

    private Optional<Candidate> castleWithTrees(Random r) {
        String mat = Phrases.pick(r, List.of("", " aus Sandstein", " aus Steinziegeln", " aus Bruchstein"));
        Optional<String> castle = QuickOrders.match("bau eine burg" + mat, judge.modules(), PlanJudge.MAX_EXTENT);
        if (castle.isEmpty()) {
            return Optional.empty();
        }
        JsonObject plan = JsonParser.parseString(castle.get()).getAsJsonObject();
        List<JsonObject> steps = new ArrayList<>();
        for (JsonElement e : plan.getAsJsonArray("steps")) {
            steps.add(e.getAsJsonObject());
        }
        int trees = Phrases.between(r, 2, 4);
        String log = Phrases.pick(r, List.of("oak_log", "spruce_log", "birch_log"));
        String style = log.equals("spruce_log") ? "cone" : "round";
        Pos[] c = judge.extent(steps);
        StringBuilder design = new StringBuilder(plan.get("design").getAsString()).append(". ").append(trees)
                .append(" trees ").append(log).append(" in front of the castle at z").append(c[0].z() - 6)
                .append(':');
        int span = c[1].x() - c[0].x();
        for (int t = 0; t < trees; t++) {
            int x = c[0].x() + span * (t + 1) / (trees + 1);
            steps.add(S.op("tree").block(log).pos(x, 0, c[0].z() - 6).num("height", Phrases.between(r, 6, 9))
                    .str("style", style).json());
            design.append(" x").append(x);
        }
        design.append('.');
        String treeWord = log.equals("spruce_log") ? "Tannen" : log.equals("birch_log") ? "Birken" : "Bäumen";
        String count = r.nextBoolean() ? NUMBER_WORDS[trees] : "" + trees;
        String order = Phrases.orderPhrase(r, "eine Burg" + mat + " mit " + count + " " + treeWord + " davor",
                "eine Burg" + mat + " mit " + treeWord + " davor");
        String reply = "Eine Burg mit " + treeWord + " davor – ich fange an!";
        return Optional.of(new Candidate("burg_mit_bäumen", order, PlanText.render(design.toString(), reply,
                steps)));
    }

    // ---------------------------------------------------------------- Logik

    /** Formel und deutscher Satz dazu; Platzhalter A/B/C/D werden durch die gewählten Eingangsnamen ersetzt. */
    private record LogicPattern(String expr, String sentence, int inputs) {
    }

    private static final List<LogicPattern> LOGIC = List.of(
            new LogicPattern("A und B", "wenn A und B an sind", 2),
            new LogicPattern("A oder B", "wenn A oder B an ist", 2),
            new LogicPattern("A und nicht B", "wenn A an ist, aber B nicht", 2),
            new LogicPattern("A xor B", "wenn genau einer von A und B an ist", 2),
            new LogicPattern("nicht (A oder B)", "wenn weder A noch B an ist", 2),
            new LogicPattern("nicht (A und B)", "solange nicht beide, A und B, an sind", 2),
            new LogicPattern("A und B und C", "wenn A, B und C alle an sind", 3),
            new LogicPattern("A oder B oder C", "wenn mindestens einer von A, B und C an ist", 3),
            new LogicPattern("A&B | A&C | B&C", "wenn mindestens zwei von A, B und C an sind", 3),
            new LogicPattern("(A und B) oder nicht C", "wenn A und B an sind oder C aus ist", 3),
            new LogicPattern("A und (B oder C)", "wenn A an ist und dazu B oder C", 3),
            new LogicPattern("A xor B xor C", "wenn eine ungerade Zahl von A, B und C an ist", 3),
            new LogicPattern("A und nicht B und C", "nur wenn A und C an sind und B aus ist", 3),
            new LogicPattern("(A oder B) und nicht C", "wenn A oder B an ist, solange C aus ist", 3),
            new LogicPattern("A und B und nicht C und nicht D", "nur wenn A und B an und C und D aus sind", 4),
            new LogicPattern("(A und B) oder (C und D)", "wenn A und B an sind oder C und D an sind", 4));

    private static final List<List<String>> INPUT_NAMES = List.of(List.of("A", "B", "C", "D"),
            List.of("X", "Y", "Z", "W"), List.of("H1", "H2", "H3", "H4"), List.of("T", "S", "F", "K"));

    private Optional<Candidate> logic(Random r) {
        LogicPattern p = Phrases.pick(r, LOGIC);
        List<String> in = Phrases.pick(r, INPUT_NAMES);
        String expr = p.expr();
        String sentence = p.sentence();
        String[] ph = {"A", "B", "C", "D"};
        // Erst auf Zwischenzeichen, dann auf die Namen: „A“ → „H1“ darf ein später ersetztes „B“ nicht treffen.
        for (int i = 0; i < 4; i++) {
            expr = expr.replaceAll("\\b" + ph[i] + "\\b", "§" + i);
            sentence = sentence.replaceAll("\\b" + ph[i] + "\\b", "§" + i);
        }
        for (int i = 0; i < 4; i++) {
            expr = expr.replace("§" + i, in.get(i));
            sentence = sentence.replace("§" + i, in.get(i));
        }
        String[] out = Phrases.pick(r, List.of(new String[] {"Lampe", "die Lampe soll angehen"},
                new String[] {"Licht", "das Licht soll angehen"}, new String[] {"Alarm", "der Alarm soll losgehen"},
                new String[] {"Q", "die Lampe geht an"}, new String[] {"Signal", "das Signal soll kommen"}));
        String formula = out[0] + " = " + expr;
        String order = switch (r.nextInt(4)) {
            case 0 -> "bau mir eine Schaltung: " + out[1] + ", " + sentence;
            case 1 -> "Ich brauche eine Redstone-Schaltung – " + out[1] + ", " + sentence + ".";
            case 2 -> "eine Logikschaltung bitte: " + out[1] + " " + sentence;
            default -> "Kannst du mir eine Schaltung bauen? " + Character.toUpperCase(out[1].charAt(0))
                    + out[1].substring(1) + ", " + sentence + ".";
        };
        order = Phrases.casual(r, order);
        List<JsonObject> steps = new ArrayList<>();
        JsonObject logic = S.op("logic").str("expr", formula).pos(0, 0, 0).str("facing", "front").json();
        boolean path = r.nextBoolean();
        if (path) {
            Pos[] e = judge.extent(List.of(logic));
            steps.add(S.op("fill").block("stone_bricks").from(e[0].x() - 1, 0, -1).to(e[1].x() + 1, 0, -1).json());
        }
        steps.add(logic);
        String design = "logic circuit " + formula + " at x0 z0 facing front" + (path ? ", stone path in front" : "")
                + ". Levers " + String.join(", ", in.subList(0, p.inputs())) + " on the left from front to back, "
                + "lamp at the back right.";
        String reply = Phrases.pick(r, List.of("Ich baue dir die Schaltung – Hebel links, Lampe hinten rechts!",
                "Klar, die Schaltung " + formula + " kommt sofort!"));
        return Optional.of(new Candidate("logik", order, PlanText.render(design, reply, steps)));
    }

    // ---------------------------------------------------------------- Maschinen

    /** Bibliotheksname → Akkusativ/Nominativ, so wie RequestHints den Namen wiederfindet. */
    private static final Map<String, String[]> MODULE_NAMES = Map.ofEntries(
            Map.entry("schmelzanlage", new String[] {"eine Schmelzanlage", "eine Schmelzanlage"}),
            Map.entry("item_sortierer", new String[] {"einen Item-Sortierer", "ein Item-Sortierer"}),
            Map.entry("sammelstelle", new String[] {"eine Sammelstelle", "eine Sammelstelle"}),
            Map.entry("zuckerrohrfarm", new String[] {"eine Zuckerrohrfarm", "eine Zuckerrohrfarm"}),
            Map.entry("wasserfahrstuhl", new String[] {"einen Wasserfahrstuhl", "ein Wasserfahrstuhl"}),
            Map.entry("abwaerts_fahrstuhl", new String[] {"einen Abwärts-Fahrstuhl", "ein Abwärts-Fahrstuhl"}),
            Map.entry("automatiktuer", new String[] {"eine Automatiktür", "eine Automatiktür"}),
            Map.entry("kolbentuer", new String[] {"eine Kolbentür", "eine Kolbentür"}),
            Map.entry("klingel", new String[] {"eine Klingel", "eine Klingel"}),
            Map.entry("glocke", new String[] {"eine Glocke", "eine Glocke"}),
            Map.entry("nachtlicht", new String[] {"ein Nachtlicht", "ein Nachtlicht"}),
            Map.entry("lampenreihe", new String[] {"eine Lampenreihe", "eine Lampenreihe"}),
            Map.entry("pfeilfalle", new String[] {"eine Pfeilfalle", "eine Pfeilfalle"}),
            Map.entry("kombischloss", new String[] {"ein Kombischloss", "ein Kombischloss"}),
            Map.entry("t_flipflop", new String[] {"ein T-Flipflop", "ein T-Flipflop"}),
            Map.entry("zaehler", new String[] {"einen Zähler", "ein Zähler"}),
            Map.entry("rs_speicher", new String[] {"einen RS-Speicher", "ein RS-Speicher"}),
            Map.entry("d_speicher", new String[] {"einen D-Speicher", "ein D-Speicher"}),
            Map.entry("und_gatter", new String[] {"ein UND-Gatter", "ein UND-Gatter"}),
            Map.entry("oder_gatter", new String[] {"ein ODER-Gatter", "ein ODER-Gatter"}),
            Map.entry("xor_gatter", new String[] {"ein XOR-Gatter", "ein XOR-Gatter"}),
            Map.entry("nicht_gatter", new String[] {"ein NICHT-Gatter", "ein NICHT-Gatter"}),
            Map.entry("nand_gatter", new String[] {"ein NAND-Gatter", "ein NAND-Gatter"}),
            Map.entry("nor_gatter", new String[] {"ein NOR-Gatter", "ein NOR-Gatter"}),
            Map.entry("xnor_gatter", new String[] {"ein XNOR-Gatter", "ein XNOR-Gatter"}),
            Map.entry("halbaddierer", new String[] {"einen Halbaddierer", "ein Halbaddierer"}),
            Map.entry("volladdierer", new String[] {"einen Volladdierer", "ein Volladdierer"}),
            Map.entry("impulsverlaengerer", new String[] {"einen Impulsverlängerer", "ein Impulsverlängerer"}),
            Map.entry("takt", new String[] {"einen Takt", "ein Takt"}));

    private Optional<Candidate> machine(Random r) {
        List<String> names = new ArrayList<>(MODULE_NAMES.keySet().stream().sorted().toList());
        int kind = r.nextInt(100);
        if (kind < 45) {
            String name = Phrases.pick(r, names);
            String[] np = MODULE_NAMES.get(name);
            String quick = Phrases.quickPhrase(r, np[0], np[1]);
            Optional<String> plan = QuickOrders.match(quick, judge.modules(), PlanJudge.MAX_EXTENT);
            if (plan.isEmpty()) {
                return Optional.empty();
            }
            String order = r.nextBoolean() ? quick : Phrases.orderPhrase(r, np[0], np[1]);
            return Optional.of(new Candidate("maschine", order, reRender(plan.get())));
        }
        if (kind < 65) {
            String np = Phrases.pick(r, List.of("eine Maschine", "eine große Maschine", "eine fette Maschine",
                    "eine kleine Maschine", "eine Maschinenhalle", "eine Fabrik", "eine riesige Maschinenhalle"));
            String quick = Phrases.quickPhrase(r, np, np);
            Optional<String> plan = QuickOrders.match(quick, judge.modules(), PlanJudge.MAX_EXTENT);
            return plan.map(p -> new Candidate("maschine", quick, reRender(p)));
        }
        // Zwei Anlagen nebeneinander, 2 Blöcke Abstand wie im System-Prompt verlangt.
        String a = Phrases.pick(r, names);
        String b = Phrases.pick(r, names);
        if (a.equals(b)) {
            return Optional.empty();
        }
        ModuleLibrary.Module ma = judge.modules().find(a).orElseThrow();
        ModuleLibrary.Module mb = judge.modules().find(b).orElseThrow();
        int bx = ma.size().x() + 2;
        List<JsonObject> steps = List.of(
                S.op("module").str("name", a).pos(0, 0, 0).str("facing", "front").json(),
                S.op("module").str("name", b).pos(bx, 0, 0).str("facing", "front").json());
        String design = "module " + a + " " + ma.size().x() + "x" + ma.size().y() + "x" + ma.size().z()
                + " at x0-" + (ma.size().x() - 1) + ", module " + b + " " + mb.size().x() + "x" + mb.size().y() + "x"
                + mb.size().z() + " at x" + bx + "-" + (bx + mb.size().x() - 1) + ", both on y0 facing front, 2 "
                + "blocks between them.";
        String[] na = MODULE_NAMES.get(a);
        String[] nb = MODULE_NAMES.get(b);
        String joiner = Phrases.pick(r, List.of(" nebeneinander", " direkt nebeneinander", " in einer Reihe", ""));
        String order = Phrases.orderPhrase(r, na[0] + " und " + nb[0] + joiner, na[1] + " und " + nb[1] + joiner);
        String reply = "Ich stelle dir " + na[0] + " und " + nb[0] + " nebeneinander auf!";
        return Optional.of(new Candidate("maschine", order, PlanText.render(design, reply, steps)));
    }

    // ---------------------------------------------------------------- Sonderbauten

    private Optional<Candidate> special(Random r) {
        String np = Phrases.pick(r, List.of("einen Bunker", "einen großen Bunker", "einen kleinen Bunker",
                "eine unterirdische Bunkeranlage", "einen Schutzbunker", "einen geheimen Bunker", "Fort Knox",
                "einen Taschenrechner", "einen Rechner", "einen Taschenrechner der wirklich rechnen kann",
                "einen Addierer aus Redstone", "einen Prozessor aus Redstone", "einen Computer",
                "einen Redstone Computer", "einen großen Prozessor", "einen Atombunker", "einen Luftschutzbunker"));
        String nom = np.replace("einen ", "ein ").replace("großen ", "großer ").replace("kleinen ", "kleiner ")
                .replace("geheimen ", "geheimer ");
        String quick = Phrases.quickPhrase(r, np, nom);
        Optional<String> plan = QuickOrders.match(quick, judge.modules(), PlanJudge.MAX_EXTENT);
        return plan.map(p -> new Candidate("sonderbau", quick, reRender(p)));
    }

    // ---------------------------------------------------------------- Aufstellung

    /** Verschiebung in x, damit part mit gap freien Blöcken rechts von base steht (gemessene Ausdehnung). */
    private int placeRight(List<JsonObject> base, List<JsonObject> part, int gap) {
        Pos[] b = judge.extent(base);
        Pos[] p = judge.extent(part);
        return b[1].x() + gap + 1 - p[0].x();
    }

    private int placeLeft(List<JsonObject> base, List<JsonObject> part, int gap) {
        Pos[] b = judge.extent(base);
        Pos[] p = judge.extent(part);
        return b[0].x() - gap - 1 - p[1].x();
    }

    static JsonObject shift(JsonObject step, int dx, int dy, int dz) {
        JsonObject o = step.deepCopy();
        for (String key : List.of("from", "to", "pos")) {
            if (o.has(key)) {
                JsonArray a = o.getAsJsonArray(key);
                JsonArray n = new JsonArray();
                n.add(a.get(0).getAsInt() + dx);
                n.add(a.get(1).getAsInt() + dy);
                n.add(a.get(2).getAsInt() + dz);
                o.add(key, n);
            }
        }
        return o;
    }

    /** Schnellauftrag-JSON in die Lehrset-Schreibweise (PlanText) – dieselben Schritte, nur anders gesetzt. */
    static String reRender(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        List<JsonObject> steps = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("steps")) {
            steps.add(e.getAsJsonObject());
        }
        return PlanText.render(o.get("design").getAsString(), o.get("reply").getAsString(), steps);
    }

    // ---------------------------------------------------------------- Ausgabe

    public static void main(String[] args) throws IOException {
        int count = 4000;
        long seed = 20261031L;
        Path out = Path.of("target", "dataset");
        Path plans = null;
        Path models = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--anzahl" -> count = Integer.parseInt(args[i + 1]);
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--ziel" -> out = Path.of(args[i + 1]);
                case "--pläne", "--plaene" -> plans = Path.of(args[i + 1]); // umlaut-ok: ASCII-Schreibweise
                case "--vorbilder" -> models = Path.of(args[i + 1]);
                default -> {
                    System.err.println("Unbekannter Schalter: " + args[i]);
                    System.exit(1);
                }
            }
        }
        PlanJudge judge = new PlanJudge();
        DatasetGenerator gen = new DatasetGenerator(judge);
        long t0 = System.nanoTime();
        Result result = gen.generate(count, seed);
        List<Sample> samples = new ArrayList<>(result.samples());
        Map<String, KindStats> stats = new LinkedHashMap<>(result.stats());
        if (plans != null) {
            PlanImport.Result imported = PlanImport.collect(plans, judge, gen.benchOrders);
            samples.addAll(imported.samples());
            stats.put("llm_angenommen", imported.stats());
        }
        if (models != null) {
            VorbildImport.Result imported = VorbildImport.collect(models, judge, gen.benchOrders);
            samples.addAll(imported.samples());
            stats.put("vorbild", imported.stats());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        write(out, judge, samples, stats, seed, ms);
    }

    /** Anteil fürs Prüfset: fest nach Auftragstext, damit ein neuer Lauf mit mehr Einträgen dieselben behält. */
    static boolean isEval(Sample s) {
        return Math.floorMod(RequestHints.normalize(s.order()).hashCode(), 20) == 0;
    }

    static void write(Path out, PlanJudge judge, List<Sample> samples, Map<String, KindStats> stats, long seed,
            long ms) throws IOException {
        Files.createDirectories(out);
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        StringBuilder train = new StringBuilder();
        StringBuilder eval = new StringBuilder();
        Map<String, int[]> perKind = new TreeMap<>();
        long assistantChars = 0;
        long userChars = 0;
        int maxAssistant = 0;
        for (Sample s : samples) {
            JsonObject line = new JsonObject();
            line.addProperty("art", s.kind());
            line.addProperty("auftrag", s.order());
            JsonArray messages = new JsonArray();
            String user = judge.userMessage(s.order());
            messages.add(com.blackn0va.baumeister.plan.LlmProtocol.message("user", user));
            messages.add(com.blackn0va.baumeister.plan.LlmProtocol.message("assistant", s.json()));
            line.add("messages", messages);
            boolean ev = isEval(s);
            (ev ? eval : train).append(gson.toJson(line)).append('\n');
            int[] k = perKind.computeIfAbsent(s.kind(), x -> new int[2]);
            k[ev ? 1 : 0]++;
            assistantChars += s.json().length();
            userChars += user.length();
            maxAssistant = Math.max(maxAssistant, s.json().length());
        }
        Files.writeString(out.resolve("train.jsonl"), train, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("eval.jsonl"), eval, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("system-prompt.txt"), judge.systemPrompt(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("plan-schema.json"), judge.schema(), StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder("# Lehrset Baumeister\n\n");
        md.append("Seed ").append(seed).append(", ").append(samples.size()).append(" Einträge in ").append(ms)
                .append(" ms. Antwort im Mittel ").append(samples.isEmpty() ? 0 : assistantChars / samples.size())
                .append(" Zeichen (max ").append(maxAssistant).append("), Nutzernachricht im Mittel ")
                .append(samples.isEmpty() ? 0 : userChars / samples.size()).append(" Zeichen, System-Prompt ")
                .append(judge.systemPrompt().length()).append(" Zeichen.\n\n");
        md.append("| Art | versucht | angenommen | Lehrset | Prüfset | doppelt | kein Kandidat | verworfen |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map.Entry<String, KindStats> e : stats.entrySet()) {
            KindStats ks = e.getValue();
            int[] k = perKind.getOrDefault(e.getKey(), new int[2]);
            int rej = ks.rejected.values().stream().mapToInt(Integer::intValue).sum();
            md.append("| ").append(e.getKey()).append(" | ").append(ks.tried).append(" | ").append(ks.accepted)
                    .append(" | ").append(k[0]).append(" | ").append(k[1]).append(" | ").append(ks.duplicate)
                    .append(" | ").append(ks.noCandidate).append(" | ").append(rej).append(" |\n");
        }
        md.append("\n## Verwerfungsgründe\n\n");
        for (Map.Entry<String, KindStats> e : stats.entrySet()) {
            for (Map.Entry<String, Integer> g : e.getValue().rejected.entrySet()) {
                md.append("- ").append(e.getKey()).append(": ").append(g.getValue()).append("× ")
                        .append(g.getKey()).append('\n');
            }
        }
        Map<String, Integer> ops = new TreeMap<>();
        for (Sample s : samples) {
            for (JsonElement st : JsonParser.parseString(s.json()).getAsJsonObject().getAsJsonArray("steps")) {
                ops.merge(st.getAsJsonObject().get("op").getAsString(), 1, Integer::sum);
            }
        }
        md.append("\n## Schritte je op\n\n");
        ops.forEach((op, n) -> md.append("- ").append(op).append(": ").append(n).append('\n'));
        Files.writeString(out.resolve("statistik.md"), md, StandardCharsets.UTF_8);
        System.out.print(md);
        System.out.println("\nGeschrieben nach " + out.toAbsolutePath());
    }
}
