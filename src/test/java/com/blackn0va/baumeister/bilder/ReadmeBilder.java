package com.blackn0va.baumeister.bilder;

import com.blackn0va.baumeister.help.HelpBook;
import com.blackn0va.baumeister.logic.LogicCircuit;
import com.blackn0va.baumeister.logic.SevenSegment;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.OsmImport;
import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.BuildPlan;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.QuickOrders;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * Bilder fürs README (docs/bilder/), gezeichnet aus dem echten Plugin-Code statt aus Bildschirmfotos: Die
 * Schnellbau-Formen laufen durch QuickOrders → PlanParser → PlanCompiler wie im Spiel, das Hilfebuch kommt aus
 * HelpBook.pages. Ein neues Bild zeigt also, was der Code heute baut – ein Bildschirmfoto veraltete beim ersten
 * Umbau und bräuchte einen laufenden Client.
 *
 * Keine Spiel-Texturen und keine Minecraft-Schrift (Rechte bei Mojang): Blöcke als schattierte Würfel in eigener
 * Farbe, Schrift aus dem System.
 *
 * Aufruf: java -cp target/classes;target/test-classes;lib/* com.blackn0va.baumeister.bilder.ReadmeBilder docs/bilder
 * [name …] – ohne Namen alle Bilder, sonst nur die genannten (z. B. „tunnel banner“).
 *
 * Threads: einer. Fehler: Ausnahme bricht ab.
 */
public final class ReadmeBilder {

    private static final Color BG1 = new Color(0x15, 0x14, 0x1f);
    private static final Color BG2 = new Color(0x2a, 0x22, 0x44);
    private static final Color ACCENT = new Color(0x8b, 0x5c, 0xf6);
    private static final Color TEXT = new Color(0xec, 0xe9, 0xf5);
    private static final Color MUTED = new Color(0xa3, 0x9f, 0xb8);

    private ReadmeBilder() {
    }

    /** Ein Bild: Dateiname ohne .png und wie es entsteht. */
    @FunctionalInterface
    private interface Painter {
        void paint(Path file) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/bilder");
        Set<String> only = Set.of(java.util.Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
        Files.createDirectories(out);
        Map<String, Painter> all = new java.util.LinkedHashMap<>();
        all.put("banner", ReadmeBilder::banner);
        all.put("schnellbau", ReadmeBilder::schnellbau);
        all.put("tunnel", ReadmeBilder::tunnel);
        all.put("hilfebuch", ReadmeBilder::hilfebuch);
        all.put("katalog", ReadmeBilder::katalog);
        all.put("stadt", ReadmeBilder::stadt);
        all.put("technik", ReadmeBilder::technik);
        for (Map.Entry<String, Painter> e : all.entrySet()) {
            if (only.isEmpty() || only.contains(e.getKey())) {
                e.getValue().paint(out.resolve(e.getKey() + ".png"));
                System.out.println("gezeichnet: " + e.getKey() + ".png");
            }
        }
        System.out.println("fertig: " + out.toAbsolutePath());
    }

    // ------------------------------------------------------------------ Kopfbild

    /**
     * Kopfbild des README (2026-11-19): Name, Satz, drei echte Bauten – Burg und Hochhaus aus QuickOrders, die Röhre
     * über den See aus TunnelShape.
     */
    static void banner(Path file) throws Exception {
        BufferedImage img = new BufferedImage(1860, 560, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 96));
        g.drawString("Baumeister", 60, 200);
        g.setColor(ACCENT);
        g.fillRect(64, 226, 260, 5);
        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
        g.drawString("Baut auf Zuruf – Block für Block, wie ein Spieler.", 62, 292);
        g.drawString("Mit Vera, die dich begleitet und mit dir spricht.", 62, 336);
        g.setColor(MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 21));
        g.drawString("Plugin für Paper 26.2 und Spigot 1.21.11 · KI lokal, aber nicht nötig", 62, 400);
        g.drawString("Schnellbau · Tunnel · Maschinen · Städte 1:1 · Rückgängig über Neustart", 62, 434);
        TunnelBilder.Scene tube = TunnelBilder.scenes().get(1);
        tile(g, 900, 60, 300, 440);
        iso(g, quick("eine Burg"), 910, 90, 280, 380);
        tile(g, 1220, 60, 300, 440);
        iso(g, tube.cells(), 1230, 90, 280, 380);
        tile(g, 1540, 60, 280, 440);
        iso(g, quick("ein Hochhaus mit 8 Stockwerken"), 1550, 90, 260, 380);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    // ------------------------------------------------------------------ Tunnel, Treppe, Brücke

    /** Sechs Karten aus TunnelBilder: echte Pläne aus TunnelShape, Fels als Kulisse. */
    static void tunnel(Path file) throws Exception {
        List<TunnelBilder.Scene> scenes = TunnelBilder.scenes();
        int tw = 620;
        int th = 470;
        int head = 110;
        BufferedImage img = new BufferedImage(tw * 3, head + th * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        title(g, "Tunnel, Treppen und Brücken",
                "Echte Pläne aus TunnelShape, so wie der Baumeister gräbt und mauert – der Fels ist im Bild "
                        + "aufgeschnitten", img.getWidth());
        for (int i = 0; i < scenes.size(); i++) {
            TunnelBilder.Scene s = scenes.get(i);
            int x0 = (i % 3) * tw;
            int y0 = head + (i / 3) * th;
            tile(g, x0 + 14, y0 + 6, tw - 28, th - 20);
            iso(g, s.cells(), x0 + 30, y0 + 20, tw - 60, th - 100);
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            g.drawString(s.title(), x0 + 36, y0 + th - 52);
            g.setColor(MUTED);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            String count = s.title().startsWith("Treppe")
                    ? String.format(Locale.GERMANY, "%d Stufen, %,d Blöcke", s.slices(), s.placed())
                    : String.format(Locale.GERMANY, "%,d Blöcke", s.placed());
            g.drawString(s.sub() + " · " + count, x0 + 36, y0 + th - 28);
        }
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    /** Schnellbau-Plan wie im Spiel: QuickOrders → PlanParser → PlanCompiler, ohne Luft. */
    private static Map<Pos, BlockSpec> quick(String prompt) throws Exception {
        PlanCompiler compiler = new PlanCompiler(
                id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
                new PlanCompiler.Limits(400_000, 192));
        String json = QuickOrders.match(prompt, Set.of(), 64).orElseThrow(
                () -> new IllegalStateException("kein Schnellweg: " + prompt));
        CompiledBuild build = compiler.compile(PlanParser.parse(json));
        Map<Pos, BlockSpec> cells = new HashMap<>();
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                if (!isAir(c.block().id())) {
                    cells.put(c.pos(), c.block());
                } else {
                    cells.remove(c.pos());
                }
            }
        }
        return cells;
    }

    // ------------------------------------------------------------------ Stadt

    /**
     * Echte OpenStreetMap-Daten aus dem Testbestand (Bad Kreuznach, 120 m, dieselbe Datei wie CityPlannerTest) durch
     * OsmImport und CityPlanner – auf flachem Boden, ohne Gelände. Kein Netz: Overpass wird hier nicht gefragt.
     */
    private static final int CITY_WINDOW = 105;

    static void stadt(Path file) throws Exception {
        String json;
        try (var in = ReadmeBilder.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            if (in == null) {
                throw new IllegalStateException("Testdaten /osm/bad-kreuznach-120m.json fehlen");
            }
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        CityModel city = OsmImport.parse(json, "Bad Kreuznach", 49.8451, 7.8676, 120);
        CityPlanner.Plan plan = CityPlanner.plan(city, 3_000_000);
        Map<Pos, BlockSpec> cells = new HashMap<>();
        for (CityPlanner.Section s : plan.sections()) {
            for (CityPlanner.Cell c : s.cells()) {
                String id = plan.palette().get(c.mat());
                String bareId = bare(id.contains("[") ? id.substring(0, id.indexOf('[')) : id);
                // Ausschnitt um die Mitte: Einzelne Straßen reichen bis 345 Blöcke hinaus (gemessen) und machten
                // die Häuser im Bild winzig.
                if (Math.abs(c.x()) > CITY_WINDOW || Math.abs(c.z()) > CITY_WINDOW) {
                    continue;
                }
                Pos p = new Pos(c.x(), c.y(), c.z());
                if (isAir(bareId)) {
                    cells.remove(p);
                } else {
                    cells.put(p, BlockSpec.of(bareId));
                }
            }
        }
        BufferedImage img = new BufferedImage(1860, 1180, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        title(g, "Städte 1:1 aus OpenStreetMap",
                String.format(Locale.GERMANY, "Bad Kreuznach: %d Gebäude, %d Straßen – Ausschnitt %d × %d mit %,d "
                        + "Blöcken, geplant von CityPlanner, ohne KI", plan.buildings(), plan.roads(),
                        2 * CITY_WINDOW + 1, 2 * CITY_WINDOW + 1, cells.size()),
                img.getWidth());
        tile(g, 24, 120, img.getWidth() - 48, img.getHeight() - 150);
        iso(g, cells, 40, 130, img.getWidth() - 80, img.getHeight() - 200);
        g.setColor(MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        g.drawString("Kartendaten © OpenStreetMap-Mitwirkende, ODbL · „/bm stadt <Ort|PLZ> [radius]“",
                44, img.getHeight() - 46);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    // ------------------------------------------------------------------ Maschinen und Logik

    private record Part(String title, String sub, ModuleLibrary.Module module) {
    }

    /** Geprüfte Maschinen aus Machines, eine Schaltung aus einer Formel (LogicCircuit) und die 7-Segment-Anzeige. */
    static void technik(Path file) throws Exception {
        Machines machines = new Machines();
        String formula = "Q = A und (B oder nicht C)";
        List<Part> parts = List.of(
                new Part("Kolbentür", "„bau eine Kolbentür“", machine(machines, "kolbentuer")),
                new Part("Halbaddierer", "rechnet A + B in Redstone", machine(machines, "halbaddierer")),
                new Part("Eisenfarm", "ohne Lava, 3 Dorfbewohner", machine(machines, "eisenfarm")),
                new Part("4-Bit-Addierer", "rechnet bis 15 + 15", machine(machines, "addierer_4bit")),
                new Part("Logik aus einer Formel", "„/bm logik " + formula + "“", LogicCircuit.compile(formula)
                        .module()),
                new Part("7-Segment-Anzeige", "zeigt 0–F über vier Hebel", SevenSegment.compile().module()));
        int tw = 620;
        int th = 470;
        int head = 110;
        BufferedImage img = new BufferedImage(tw * 3, head + th * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        title(g, "Maschinen und Logik",
                "Redstone-Bauten, je in vier Richtungen auf Spigot und Paper geprüft – und Schaltungen direkt aus "
                        + "einer Formel", img.getWidth());
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            Map<Pos, BlockSpec> cells = new HashMap<>();
            for (CompiledBuild.Change c : part.module().cells()) {
                if (!isAir(c.block().id())) {
                    cells.put(c.pos(), c.block());
                }
            }
            int x0 = (i % 3) * tw;
            int y0 = head + (i / 3) * th;
            tile(g, x0 + 14, y0 + 6, tw - 28, th - 20);
            iso(g, cells, x0 + 30, y0 + 20, tw - 60, th - 100);
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            g.drawString(part.title(), x0 + 36, y0 + th - 52);
            g.setColor(MUTED);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            g.drawString(String.format(Locale.GERMANY, "%s · %,d Blöcke", part.sub(), cells.size()), x0 + 36,
                    y0 + th - 28);
        }
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    private static ModuleLibrary.Module machine(Machines machines, String name) {
        return machines.find(name).orElseThrow(() -> new IllegalStateException("Maschine fehlt: " + name));
    }

    // ------------------------------------------------------------------ Schnellbau

    private record Shot(String title, String prompt) {
    }

    static void schnellbau(Path file) throws Exception {
        List<Shot> shots = List.of(new Shot("Pyramide", "eine Pyramide"), new Shot("Burg", "eine Burg"),
                new Shot("Hochhaus, 8 Stockwerke", "ein Hochhaus mit 8 Stockwerken"),
                new Shot("Brücke", "eine Brücke"), new Shot("Garten", "einen Garten"),
                new Shot("Tanne", "eine Tanne"));
        int tw = 620;
        int th = 470;
        int head = 110;
        BufferedImage img = new BufferedImage(tw * 3, head + th * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        title(g, "Schnellbau – sofort, ohne KI",
                "Echte Pläne aus QuickOrders und PlanCompiler, so wie der Baumeister sie Block für Block setzt",
                img.getWidth());
        PlanCompiler compiler = new PlanCompiler(
                id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
                new PlanCompiler.Limits(400_000, 192));
        for (int i = 0; i < shots.size(); i++) {
            Shot s = shots.get(i);
            String json = QuickOrders.match(s.prompt(), Set.of(), 64).orElseThrow(
                    () -> new IllegalStateException("kein Schnellweg: " + s.prompt()));
            BuildPlan plan = PlanParser.parse(json);
            CompiledBuild build = compiler.compile(plan);
            Map<Pos, BlockSpec> cells = new HashMap<>();
            for (CompiledBuild.Action a : build.actions()) {
                for (CompiledBuild.Change c : a.changes()) {
                    if (!isAir(c.block().id())) {
                        cells.put(c.pos(), c.block());
                    } else {
                        cells.remove(c.pos());
                    }
                }
            }
            int x0 = (i % 3) * tw;
            int y0 = head + (i / 3) * th;
            tile(g, x0 + 14, y0 + 6, tw - 28, th - 20);
            iso(g, cells, x0 + 30, y0 + 20, tw - 60, th - 100);
            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            g.drawString(s.title(), x0 + 36, y0 + th - 52);
            g.setColor(MUTED);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            g.drawString(String.format(Locale.GERMANY, "„Baumeister, bau %s“ · %,d Blöcke", s.prompt(),
                    cells.size()), x0 + 36, y0 + th - 28);
        }
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    private static boolean isAir(String id) {
        String n = bare(id);
        return n.equals("air") || n.equals("cave_air") || n.equals("void_air");
    }

    private static String bare(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** Würfelform: Breite (x/z) und Höhe als Anteil eines Blocks, unten = Unterkante. */
    private record Shape(double w, double h, boolean solid) {
    }

    private static Shape shape(BlockSpec b) {
        String n = bare(b.id());
        if (n.endsWith("_slab")) {
            return new Shape(1, 0.5, false);
        }
        if (n.endsWith("_carpet") || n.endsWith("pressure_plate") || n.endsWith("rail") || n.equals("redstone_wire")
                || n.equals("repeater") || n.equals("comparator") || n.endsWith("_button") || n.equals("lever")) {
            return new Shape(1, 0.12, false);
        }
        if (n.endsWith("_fence") || n.endsWith("_wall") || n.equals("iron_bars") || n.endsWith("_pane")
                || n.equals("chain") || n.endsWith("fence_gate")) {
            return new Shape(0.38, 1, false);
        }
        if (n.contains("torch") || n.contains("lantern") || n.endsWith("_sapling") || n.equals("short_grass")
                || n.equals("fern") || n.equals("dandelion") || n.equals("poppy") || n.contains("tulip")
                || n.equals("allium") || n.equals("azure_bluet") || n.equals("cornflower") || n.equals("oxeye_daisy")
                || n.equals("lily_of_the_valley") || n.endsWith("_flower") || n.equals("sweet_berry_bush")) {
            return new Shape(0.36, 0.55, false);
        }
        boolean see = n.contains("glass") || n.equals("water") || n.contains("leaves");
        return new Shape(1, 1, !see);
    }

    private static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("stone", 0x7d7d7d), Map.entry("cobblestone", 0x707070), Map.entry("stone_bricks", 0x7a7a7a),
            Map.entry("mossy_stone_bricks", 0x6c7a5c), Map.entry("cracked_stone_bricks", 0x737373),
            Map.entry("mossy_cobblestone", 0x667258), Map.entry("andesite", 0x888888), Map.entry("polished_andesite",
                    0x8c8f8e), Map.entry("deepslate_bricks", 0x4d4d52), Map.entry("deepslate", 0x55555a),
            Map.entry("sandstone", 0xdccf9a), Map.entry("smooth_sandstone", 0xe0d6a8), Map.entry("cut_sandstone",
                    0xd9cb92), Map.entry("chiseled_sandstone", 0xd6c88c), Map.entry("sand", 0xdbd3a0),
            Map.entry("red_sandstone", 0xb8642e), Map.entry("oak_planks", 0xa2834f), Map.entry("spruce_planks",
                    0x6e5232), Map.entry("birch_planks", 0xc5b77b), Map.entry("dark_oak_planks", 0x43301a),
            Map.entry("oak_log", 0x6b5130), Map.entry("spruce_log", 0x3b2a17), Map.entry("birch_log", 0xd8d4c9),
            Map.entry("cherry_log", 0x3d2028), Map.entry("stripped_oak_log", 0xb08b55), Map.entry("oak_leaves",
                    0x4c8a2a), Map.entry("spruce_leaves", 0x3e5f3a), Map.entry("birch_leaves", 0x6b9a45),
            Map.entry("cherry_leaves", 0xf2b5cf), Map.entry("azalea_leaves", 0x5e8a2f), Map.entry("grass_block",
                    0x6aa84f), Map.entry("dirt", 0x866043), Map.entry("coarse_dirt", 0x77553a), Map.entry("dirt_path",
                    0x947a46), Map.entry("gravel", 0x827f7e), Map.entry("glass", 0xc8e6f0), Map.entry("water",
                    0x3f76e4), Map.entry("bricks", 0x9a4f3e), Map.entry("quartz_block", 0xece7df),
            Map.entry("smooth_quartz", 0xece7df), Map.entry("terracotta", 0x985f45), Map.entry("mud_bricks",
                    0x8a6a50), Map.entry("hay_block", 0xc7a52a), Map.entry("gold_block", 0xf5d340),
            Map.entry("iron_bars", 0x9a9a9a), Map.entry("lantern", 0xf3c54b), Map.entry("torch", 0xf3c54b),
            Map.entry("wall_torch", 0xf3c54b), Map.entry("poppy", 0xc3352b), Map.entry("dandelion", 0xf2d63a),
            Map.entry("cornflower", 0x4a6cd4), Map.entry("allium", 0xb766d8), Map.entry("azure_bluet", 0xe8e8f0),
            Map.entry("oxeye_daisy", 0xf0f0e0), Map.entry("lily_of_the_valley", 0xf4f4f4), Map.entry("short_grass",
                    0x5f9a3c), Map.entry("fern", 0x4f8a35), Map.entry("moss_block", 0x5a7a2c), Map.entry("bookshelf",
                    0x8a6b3f), Map.entry("white_wool", 0xe9ecec), Map.entry("red_wool", 0xa12722),
            Map.entry("blue_wool", 0x35399d), Map.entry("glowstone", 0xf9d49c), Map.entry("sea_lantern", 0xd4e8e2),
            Map.entry("prismarine", 0x5ea494), Map.entry("chain", 0x3d4148), Map.entry("lily_pad", 0x208030),
            Map.entry("redstone_wire", 0xc81d11), Map.entry("redstone_block", 0xb0190e), Map.entry("redstone_torch",
                    0xff3b2a), Map.entry("redstone_wall_torch", 0xff3b2a), Map.entry("redstone_lamp", 0xe7a45a),
            Map.entry("repeater", 0xa8a29e), Map.entry("comparator", 0xb5aaa4), Map.entry("lever", 0x6e5a3c),
            Map.entry("piston", 0xa58a5c), Map.entry("sticky_piston", 0x8fae5e), Map.entry("observer", 0x5f5f5f),
            Map.entry("hopper", 0x4a4a4e), Map.entry("dropper", 0x6d6d6d), Map.entry("dispenser", 0x6d6d6d),
            Map.entry("lava", 0xe5641a), Map.entry("sugar_cane", 0x8fc760), Map.entry("target", 0xe8d2c5),
            Map.entry("iron_door", 0xc4c4c4), Map.entry("note_block", 0x6b4a32), Map.entry("chest", 0xa06e2a),
            Map.entry("smooth_stone", 0x9e9e9e), Map.entry("asphalt", 0x3a3a3e), Map.entry("black_concrete",
                    0x1c1e22), Map.entry("gray_concrete", 0x4c5056), Map.entry("light_gray_concrete", 0xa3a39b),
            Map.entry("white_concrete", 0xdfe3e4), Map.entry("polished_blackstone", 0x35303a),
            Map.entry("red_terracotta", 0x8f3d2e), Map.entry("brown_terracotta", 0x4d3323),
            Map.entry("orange_terracotta", 0xa15325), Map.entry("white_terracotta", 0xd1b2a1),
            Map.entry("red_nether_bricks", 0x5a1013), Map.entry("light_gray_terracotta", 0x876a61),
            Map.entry("polished_blackstone_bricks", 0x302a32), Map.entry("deepslate_tiles", 0x36363a),
            Map.entry("yellow_terracotta", 0xba8523), Map.entry("birch_sign", 0xc5b77b),
            // Tunnel und Brücke (2026-11-19, BildFarbenProbe): sonst Ersatzfarben aus dem Namen, lila Gurtbögen
            Map.entry("chiseled_stone_bricks", 0x6f6f6f), Map.entry("chiseled_deepslate", 0x3a3a3f),
            Map.entry("polished_deepslate", 0x4a4a4f), Map.entry("iron_chain", 0x3d4148));

    private static Color color(String id) {
        Integer c = colorOrNull(id);
        if (c == null) {
            int h = bare(id).hashCode();
            c = Color.HSBtoRGB((h & 0xff) / 255f, 0.35f, 0.65f) & 0xffffff;
        }
        return new Color(c);
    }

    /** Hat id eine eigene Farbe (nicht die Ersatzfarbe aus dem Namen)? Für BildFarbenProbe. */
    static boolean hasColor(String id) {
        return colorOrNull(id) != null;
    }

    private static Integer colorOrNull(String id) {
        String n = bare(id);
        for (String cut : List.of("_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door",
                "_pressure_plate", "_button", "_carpet")) {
            if (n.endsWith(cut)) {
                n = n.substring(0, n.length() - cut.length());
                break;
            }
        }
        Integer c = COLORS.get(n);
        if (c == null && COLORS.containsKey(n + "s")) {
            c = COLORS.get(n + "s");  // stone_brick_stairs → stone_bricks
        }
        if (c == null && COLORS.containsKey(n + "_planks")) {
            c = COLORS.get(n + "_planks");  // oak_stairs → oak_planks
        }
        if (c == null && n.contains("glass")) {
            c = n.contains("light_blue") ? 0x9fd0ea : 0xc8e6f0;
        }
        if (c == null && n.endsWith("_concrete")) {
            c = n.startsWith("light_gray") ? 0xa3a39b : n.startsWith("white") ? 0xdfe3e4 : n.startsWith("gray")
                    ? 0x4c5056 : n.startsWith("black") ? 0x1c1e22 : 0x9a9a9a;
        }
        if (c == null && n.endsWith("_wool")) {
            c = 0xd0d0d0;
        }
        if (c == null && n.contains("tulip")) {
            c = 0xe07a3a;
        }
        return c;
    }

    /** Isometrische Würfel, verdeckte Flächen weggelassen, von hinten nach vorn gemalt, in den Rahmen eingepasst. */
    static void iso(Graphics2D g, Map<Pos, BlockSpec> cells, int x, int y, int w, int h) {
        if (cells.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Pos p : cells.keySet()) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        // Bildschirm: u = (x − z)·s·0,866, v = (x + z)·s·0,5 − y·s. Einpassen über die Ecken des Quaders.
        double spanU = (maxX - minX + 1 + maxZ - minZ + 1) * 0.866;
        double spanV = (maxX - minX + 1 + maxZ - minZ + 1) * 0.5 + (maxY - minY + 1);
        double s = Math.min(w / spanU, h / spanV);
        s = Math.min(s, 34);
        double ox = x + w / 2.0 - ((maxX + 1 - minZ) + (minX - maxZ - 1)) / 2.0 * s * 0.866;
        double oy = y + (h - spanV * s) / 2.0 + (maxY + 1) * s - (minX + minZ) * s * 0.5;
        // Schatten unter dem Bauwerk
        g.setColor(new Color(0, 0, 0, 70));
        Polygon shadow = new Polygon();
        double[][] corners = {{minX, minZ}, {maxX + 1, minZ}, {maxX + 1, maxZ + 1}, {minX, maxZ + 1}};
        for (double[] c : corners) {
            shadow.addPoint((int) (ox + (c[0] - c[1]) * s * 0.866), (int) (oy + (c[0] + c[1]) * s * 0.5 - minY * s
                    + s * 0.15));
        }
        g.fill(shadow);
        List<Map.Entry<Pos, BlockSpec>> order = new ArrayList<>(cells.entrySet());
        order.sort(Comparator.<Map.Entry<Pos, BlockSpec>>comparingInt(e -> e.getKey().x() + e.getKey().z())
                .thenComparingInt(e -> e.getKey().y()).thenComparingInt(e -> e.getKey().x()));
        for (Map.Entry<Pos, BlockSpec> e : order) {
            Pos p = e.getKey();
            Shape sh = shape(e.getValue());
            boolean top = !solidAt(cells, new Pos(p.x(), p.y() + 1, p.z())) || sh.h() < 1;
            boolean south = !solidAt(cells, new Pos(p.x(), p.y(), p.z() + 1)) || sh.w() < 1;
            boolean east = !solidAt(cells, new Pos(p.x() + 1, p.y(), p.z())) || sh.w() < 1;
            if (!top && !south && !east) {
                continue;
            }
            String n = bare(e.getValue().id());
            Color c = color(n);
            float alpha = n.contains("glass") ? 0.45f : n.equals("water") ? 0.7f : 1f;
            double inset = (1 - sh.w()) / 2;
            double bx = p.x() + inset;
            double bz = p.z() + inset;
            double bw = sh.w();
            double by = p.y();
            double bh = sh.h();
            Color topC = n.endsWith("_log") && !n.startsWith("stripped") ? c.brighter() : c;
            if (n.equals("grass_block")) {
                topC = new Color(0x7cbd56);
                c = new Color(0x866043);
            }
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            if (south) {
                face(g, ox, oy, s, shade(c, 0.78), new double[][] {{bx, by, bz + bw}, {bx + bw, by, bz + bw},
                    {bx + bw, by + bh, bz + bw}, {bx, by + bh, bz + bw}});
            }
            if (east) {
                face(g, ox, oy, s, shade(c, 0.62), new double[][] {{bx + bw, by, bz}, {bx + bw, by, bz + bw},
                    {bx + bw, by + bh, bz + bw}, {bx + bw, by + bh, bz}});
            }
            if (top) {
                face(g, ox, oy, s, shade(topC, 1.0), new double[][] {{bx, by + bh, bz}, {bx + bw, by + bh, bz},
                    {bx + bw, by + bh, bz + bw}, {bx, by + bh, bz + bw}});
            }
            g.setComposite(AlphaComposite.SrcOver);
        }
    }

    private static boolean solidAt(Map<Pos, BlockSpec> cells, Pos p) {
        BlockSpec b = cells.get(p);
        return b != null && shape(b).solid();
    }

    private static void face(Graphics2D g, double ox, double oy, double s, Color c, double[][] pts) {
        Polygon poly = new Polygon();
        for (double[] q : pts) {
            poly.addPoint((int) Math.round(ox + (q[0] - q[2]) * s * 0.866),
                    (int) Math.round(oy + (q[0] + q[2]) * s * 0.5 - q[1] * s));
        }
        g.setColor(c);
        g.fill(poly);
        if (s >= 6) {
            g.setColor(shade(c, 0.85));
            g.setStroke(new BasicStroke(0.6f));
            g.draw(poly);
        }
    }

    private static Color shade(Color c, double f) {
        return new Color(Math.min(255, (int) (c.getRed() * f)), Math.min(255, (int) (c.getGreen() * f)),
                Math.min(255, (int) (c.getBlue() * f)));
    }

    // ------------------------------------------------------------------ Hilfebuch

    static void hilfebuch(Path file) throws IOException {
        Set<String> admin = Set.of("baumeister.begleiter", "baumeister.rucksack", "baumeister.warp",
                "baumeister.warp.setzen", "baumeister.aufgaben", "baumeister.bauen", "baumeister.reparieren",
                "baumeister.admin", "baumeister.begleiter.suchen");
        // Wie auf dev01 eingestellt (2026-11-08): Baumeister thorsten, Vera kerstin im Cartoon-Ton.
        HelpBook.Voice voice = new HelpBook.Voice(true, "de_DE-thorsten-medium", List.of("de_DE-kerstin-low",
                "de_DE-thorsten-medium", "ryan-deutsch"), "", "de_DE-kerstin-low", true);
        HelpBook.Context c = new HelpBook.Context("Vera", admin::contains, List.of("Alex", "Steve"),
                List.of("farmwelt", "plotwelt", "spawn"), List.of("bauteam"), List.of("· Baue 16 Kupfererz ab 3/16",
                        "✔ Besiege 8 Skelette"), List.of("turm"), "Kumpel", List.of(), List.of(), List.of(), true,
                new HelpBook.Extras(804, voice));
        List<HelpBook.Page> pages = HelpBook.pages(c);
        List<HelpBook.Page> pick = new ArrayList<>();
        pick.add(pages.getFirst());
        for (String ch : List.of("Schnellbau", "Suchen", HelpBook.VOICE_CHAPTER)) {
            pages.stream().filter(p -> p.chapter().equals(ch)).findFirst().ifPresent(pick::add);
        }
        int pw = 380;
        int ph = 500;
        int gap = 34;
        int head = 110;
        BufferedImage img = new BufferedImage(pick.size() * (pw + gap) + gap, head + ph + 40,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        title(g, "Hilfebuch – alles per Klick", "Inhalt, Schnellbau, Suchen mit Vera und die Stimmwahl für Admins",
                img.getWidth());
        for (int i = 0; i < pick.size(); i++) {
            page(g, pick.get(i), gap + i * (pw + gap), head, pw, ph);
        }
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    private static void page(Graphics2D g, HelpBook.Page page, int x, int y, int w, int h) {
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Double(x + 6, y + 8, w, h, 14, 14));
        g.setPaint(new GradientPaint(x, y, new Color(0xf6, 0xee, 0xd8), x + w, y + h, new Color(0xe9, 0xdc, 0xbb)));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 14, 14));
        g.setColor(new Color(0xb8, 0xa4, 0x7a));
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 14, 14));
        Font plain = new Font(Font.SANS_SERIF, Font.PLAIN, 19);
        Font bold = new Font(Font.SANS_SERIF, Font.BOLD, 20);
        int lineH = 33;
        int cx = x + 28;
        int cy = y + 44;
        int col = cx;
        for (HelpBook.Part part : page.parts()) {
            String[] pieces = part.text().split("\n", -1);
            for (int k = 0; k < pieces.length; k++) {
                String t = pieces[k];
                if (!t.isEmpty()) {
                    g.setFont(part.bold() ? bold : plain);
                    boolean arrow = t.equals(HelpBook.FOOTER_BACK) || t.equals(HelpBook.FOOTER_NEXT);
                    g.setColor(part.bold() ? new Color(0x6a, 0x2c, 0x9e)
                            : arrow && part.command() == null ? new Color(0x9a, 0x92, 0x80)
                            : part.command() != null || part.page() > 0 ? new Color(0x1f, 0x3c, 0xa8)
                            : new Color(0x26, 0x22, 0x1c));
                    g.drawString(t, col, cy);
                    col += g.getFontMetrics().stringWidth(t);
                }
                if (k < pieces.length - 1) {
                    cy += lineH;
                    col = cx;
                }
            }
        }
    }

    // ------------------------------------------------------------------ Katalog

    static void katalog(Path file) throws IOException {
        String[][] items = {
            {"Taktgeber", "12", "clock"}, {"Kolbentür", "9", "piston"}, {"Drop-Aufzug", "3",
                "slime_block"}, {"Weizenfarm", "4", "wheat"}, {"Lampenschaltung", "5", "redstone_lamp"},
            {"Binärzähler", "6", "comparator"}, {"Addierer", "4", "comparator"}, {"Speicherzelle", "7", "repeater"},
            {"Tür- und Toranlage", "8", "iron_door"}, {"Sortieranlage", "3", "hopper"}, {"Lorenbahnhof", "2", "rail"},
            {"Kanalschleuse", "2", "water_bucket"}, {"Logikgatter", "11", "redstone_torch"}, {"Flipflop", "5",
                "repeater"}, {"Zuckerrohrfarm", "3", "wheat"}, {"Geheimtür", "6", "iron_door"},
            {"Tageslichtuhr", "2", "clock"}, {"Kürbisfarm", "2", "wheat"}, {"Kolbenaufzug", "3", "piston"},
            {"Zahlenanzeige", "4", "redstone_lamp"}, {"Burgtor", "2", "stone_bricks"}, {"Wachturm", "3",
                "cobblestone"}, {"Schiffsanleger", "1", "oak_boat"}, {"Trichterkette", "2", "hopper"}};
        Map<String, Integer> icon = Map.ofEntries(Map.entry("clock", 0xe2b33a), Map.entry("iron_door", 0xc9c9c9),
                Map.entry("slime_block", 0x7ccd5b), Map.entry("wheat", 0xd6b74a), Map.entry("redstone_lamp",
                        0xd08a3c), Map.entry("comparator", 0xb84a3a), Map.entry("repeater", 0xa83a2e),
                Map.entry("piston", 0x9a8a62), Map.entry("hopper", 0x4a4a50), Map.entry("rail", 0x8a7a5a),
                Map.entry("water_bucket", 0x3f76e4), Map.entry("redstone_torch", 0xe23a2a), Map.entry("stone_bricks",
                        0x7a7a7a), Map.entry("cobblestone", 0x707070), Map.entry("oak_boat", 0xa2834f));
        int slot = 64;
        int cols = 9;
        int rows = 6;
        int pad = 22;
        int head = 110;
        int ww = cols * slot + pad * 2;
        int wh = rows * slot + pad * 2 + 46;
        int imgW = ww + 520;
        BufferedImage img = new BufferedImage(imgW, head + wh + 50, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas(img);
        title(g, "Katalog – Hunderte Schematics zum Durchklicken",
                "/bm katalog: 45 Familien je Seite, Varianten auf Klick, gebaut über /bm bau", imgW);
        int wx = 40;
        int wy = head;
        g.setColor(new Color(0x24, 0x22, 0x30));
        g.fill(new RoundRectangle2D.Double(wx, wy, ww, wh, 16, 16));
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(2f));
        g.draw(new RoundRectangle2D.Double(wx, wy, ww, wh, 16, 16));
        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.drawString("Sammlung · 1/5", wx + pad, wy + 34);
        int gx = wx + pad;
        int gy = wy + 52;
        for (int r = 0; r < rows; r++) {
            for (int k = 0; k < cols; k++) {
                int sx = gx + k * slot;
                int sy = gy + r * slot;
                g.setColor(new Color(0x34, 0x31, 0x44));
                g.fill(new RoundRectangle2D.Double(sx + 3, sy + 3, slot - 6, slot - 6, 10, 10));
                int idx = r * cols + k;
                Integer c = null;
                String mark = null;
                if (r < 5) {
                    c = icon.get(items[idx % items.length][2]);
                } else if (r == 5) {
                    c = switch (k) {
                        case 0, 8 -> 0xdcdcdc;
                        case 2 -> 0xa2834f;
                        case 4 -> 0xc9a86a;
                        case 6 -> 0xc0392b;
                        default -> null;
                    };
                    mark = switch (k) {
                        case 0 -> "◀";
                        case 8 -> "▶";
                        case 2 -> "?";
                        case 4 -> "i";
                        case 6 -> "✕";
                        default -> null;
                    };
                }
                if (c != null) {
                    Color base = new Color(c);
                    g.setPaint(new GradientPaint(sx + 14, sy + 14, base.brighter(), sx + slot - 14, sy + slot - 14,
                            shade(base, 0.7)));
                    g.fill(new RoundRectangle2D.Double(sx + 14, sy + 14, slot - 28, slot - 28, 8, 8));
                    if (mark != null) {
                        g.setColor(new Color(0x20, 0x1e, 0x28));
                        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
                        int mw = g.getFontMetrics().stringWidth(mark);
                        g.drawString(mark, sx + (slot - mw) / 2, sy + slot / 2 + 7);
                    }
                }
            }
        }
        // Hervorgehobener Platz und sein Hinweis
        int hx = gx + 8 * slot;
        int hy = gy;
        g.setColor(ACCENT);
        g.setStroke(new BasicStroke(3f));
        g.draw(new RoundRectangle2D.Double(hx + 3, hy + 3, slot - 6, slot - 6, 10, 10));
        int tx = wx + ww + 30;
        int ty = head + 40;
        g.setColor(new Color(0x1a, 0x10, 0x2c, 240));
        g.fill(new RoundRectangle2D.Double(tx, ty, 440, 210, 14, 14));
        g.setColor(ACCENT);
        g.draw(new RoundRectangle2D.Double(tx, ty, 440, 210, 14, 14));
        g.setColor(new Color(0xf5, 0xc5, 0x42));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.drawString("Tür- und Toranlage", tx + 22, ty + 40);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        g.setColor(MUTED);
        g.drawString("8 Varianten", tx + 22, ty + 72);
        g.drawString("Beschreibung aus der index.tsv", tx + 22, ty + 102);
        g.drawString("der Sammlung, bis zu drei Zeilen.", tx + 22, ty + 126);
        g.setColor(new Color(0xf5, 0xe0, 0x6a));
        g.drawString("Klick: Varianten zeigen", tx + 22, ty + 172);
        g.setStroke(new BasicStroke(2f));
        g.setColor(ACCENT);
        g.drawLine(hx + slot - 3, hy + slot / 2, tx, ty + 40);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    // ------------------------------------------------------------------ gemeinsam

    private static Graphics2D canvas(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setPaint(new GradientPaint(0, 0, BG1, img.getWidth(), img.getHeight(), BG2));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        return g;
    }

    private static void title(Graphics2D g, String head, String sub, int width) {
        g.setColor(TEXT);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
        g.drawString(head, 40, 56);
        g.setColor(MUTED);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 19));
        g.drawString(sub, 40, 88);
        g.setColor(ACCENT);
        g.fillRect(40, 98, Math.min(220, width - 80), 3);
    }

    private static void tile(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(255, 255, 255, 12));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 18, 18));
        g.setColor(new Color(255, 255, 255, 28));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(new RoundRectangle2D.Double(x, y, w, h, 18, 18));
    }
}
