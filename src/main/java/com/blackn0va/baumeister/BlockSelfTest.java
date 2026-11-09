package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.BlockTiers;
import com.blackn0va.baumeister.plan.Nbt;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Snbt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Selbsttest „jeder Block“ (Plan B9; Wunsch 2026-11-08). Prüft im laufenden Server, was kein Unit-Test kann: ob der
 * Server jeden Zustand über den Weg eines
 * Baus annimmt und so stehen lässt.
 *
 * <ul>
 * <li><b>blöcke</b>: Jeder Zustand einer Liste (Datengenerator von Vanilla, probes/blockstates) in allen vier
 * Blickrichtungen über genau den Weg eines Baus – BlockSpec.parse → BuildService.toBlockData → BuildService.placement →
 * BuildJob.setLikeABuild –, im selben Takt zurückgelesen und mit Bukkit.createBlockData(Zustand).rotate(…) verglichen.
 * Danach wird die Zelle ohne Physik geleert; nichts bleibt stehen.</li>
 * <li><b>daten</b>: je Block-Entity-Art ein Datenbeispiel über den data-merge-Weg (Plan B4) samt der Prüfung, die auch
 * der Bau macht (BlockDataWriter.Expectation).</li>
 * <li><b>stabil</b>: jede Blockart einmal in ein Raster, nach 5 s nachgelesen – die Liste dessen, was Minecraft von
 * selbst angleicht (Laub-Abstand, Fallendes, Pflanzen ohne Erde …). Danach wird das Raster geleert.</li>
 * </ul>
 *
 * Bericht nach plugins/Baumeister/selbsttest/&lt;teil&gt;-&lt;fassung&gt;.txt, die Kurzfassung an den Absender. Nur für
 * Admins und die Konsole; gedacht für die Testserver, nicht für eine bespielte Welt (die Testzelle wird überschrieben).
 *
 * Threads: Hauptthread, in Zeitscheiben (TimeSlice) über viele Takte. Fehler: je Zustand gezählt, nie geworfen.
 */
final class BlockSelfTest {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final Cardinal[] FACINGS = Cardinal.values();
    /** Ausgedruckte Abweichungen je Teil höchstens – der Rest steht in der Datei. */
    private static final int MAX_LISTED = 12;

    /**
     * Ein Datenbeispiel je Block-Entity-Art, die ein Bau setzen kann (Stand 1.21.11 und 26.2). Köpfe mit Profil fehlen
     * mit Absicht: Minecraft fragt dafür den Profil-Dienst von Mojang im Internet an.
     */
    static final Map<String, String> DATA_SAMPLES = samples();

    private BlockSelfTest() {
    }

    private static Map<String, String> samples() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("oak_sign", "{front_text:{messages:[\"Selbsttest\",\"Zeile 2\",\"\",\"\"]}}");
        m.put("oak_hanging_sign", "{front_text:{messages:[\"a\",\"b\",\"c\",\"d\"]},is_waxed:1b}");
        m.put("chest", "{Items:[{Slot:0b,id:\"minecraft:bread\",count:5}]}");
        m.put("trapped_chest", "{Items:[{Slot:1b,id:\"minecraft:apple\",count:2}]}");
        m.put("barrel", "{Items:[{Slot:3b,id:\"minecraft:stone\",count:64}]}");
        m.put("shulker_box", "{Items:[{Slot:0b,id:\"minecraft:diamond\",count:1}]}");
        m.put("hopper", "{Items:[{Slot:0b,id:\"minecraft:iron_ingot\",count:3}]}");
        m.put("dispenser", "{Items:[{Slot:4b,id:\"minecraft:arrow\",count:16}]}");
        m.put("dropper", "{Items:[{Slot:0b,id:\"minecraft:cobblestone\",count:1}]}");
        m.put("furnace", "{Items:[{Slot:0b,id:\"minecraft:raw_iron\",count:8}]}");
        m.put("smoker", "{Items:[{Slot:0b,id:\"minecraft:beef\",count:1}]}");
        m.put("blast_furnace", "{Items:[{Slot:1b,id:\"minecraft:coal\",count:4}]}");
        m.put("brewing_stand", "{Items:[{Slot:3b,id:\"minecraft:nether_wart\",count:1}]}");
        m.put("campfire", "{Items:[{Slot:0b,id:\"minecraft:beef\",count:1}]}");
        m.put("chiseled_bookshelf", "{Items:[{Slot:0b,id:\"minecraft:book\",count:1}]}");
        m.put("crafter", "{Items:[{Slot:0b,id:\"minecraft:stick\",count:1}]}");
        m.put("copper_chest", "{Items:[{Slot:0b,id:\"minecraft:copper_ingot\",count:9}]}");
        m.put("lectern[has_book=true]", "{Book:{id:\"minecraft:writable_book\",count:1}}");
        m.put("jukebox", "{RecordItem:{id:\"minecraft:music_disc_cat\",count:1}}");
        m.put("decorated_pot", "{sherds:[\"minecraft:angler_pottery_sherd\",\"minecraft:brick\",\"minecraft:brick\","
                + "\"minecraft:brick\"]}");
        m.put("white_banner", "{patterns:[{pattern:\"minecraft:creeper\",color:\"green\"}]}");
        m.put("beehive", "{bees:[]}");
        m.put("beacon", "{primary_effect:\"minecraft:speed\"}");
        m.put("command_block", "{Command:\"say Selbsttest\"}");
        m.put("spawner", "{SpawnData:{entity:{id:\"minecraft:pig\"}}}");
        return m;
    }

    /** Liste lesen: eine Zeile je Zustand „minecraft:id[k=v,…]“, .gz oder Text; Leerzeilen und # fallen weg. */
    static List<String> readStates(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
                InputStream in = file.getFileName().toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> out = new ArrayList<>();
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add(t);
                }
            }
            return out;
        }
    }

    /** Erwarteter Weltzustand: Zustand der Liste, gedreht wie der Bau bei dieser Blickrichtung (BuildService.TURNS). */
    static BlockData expected(String state, Cardinal facing) {
        BlockData d = Bukkit.createBlockData(state);
        if (facing.ordinal() != 0) {
            d.rotate(BuildService.TURNS[facing.ordinal()]);
        }
        return d;
    }

    /** Ergebnis eines Teils: geprüft, gleich, abweichend (mit Zeilen), nicht umsetzbar. */
    static final class Report {

        final String part;
        int checked;
        int same;
        final List<String> lines = new ArrayList<>();

        Report(String part) {
            this.part = part;
        }

        void bad(String line) {
            lines.add(line);
        }

        boolean clean() {
            return lines.isEmpty();
        }

        String summary() {
            return part + ": " + checked + " geprüft, " + same + " gleich, " + lines.size() + " abweichend";
        }
    }

    /**
     * Startet einen Teil. at = Testzelle (darunter kommt Stein). done bekommt den Bericht, im Hauptthread.
     */
    static void start(Plugin plugin, CommandSender to, String part, Path list, Block at,
            java.util.function.Consumer<Report> done) throws IOException {
        Report report = new Report(part);
        World w = at.getWorld();
        Block floor = at.getRelative(0, -1, 0);
        floor.setType(Material.STONE, false);
        BlockTiers.WorldFacts facts = new BlockTiers.WorldFacts(w.isBedWorks(), w.isRespawnAnchorWorks());
        BlockData air = Material.AIR.createBlockData();
        switch (part) {
            case "blöcke" -> {
                List<String> states = readStates(list);
                int[] cursor = {0};
                int total = states.size() * FACINGS.length;
                Texts.info(to, "Selbsttest blöcke: " + states.size() + " Zustände × 4 Richtungen = " + total
                        + " Setzvorgänge …");
                Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                    TimeSlice.run(System::nanoTime, 25_000_000L, 64, 100_000, () -> {
                        if (cursor[0] >= total) {
                            return false;
                        }
                        int i = cursor[0]++;
                        checkState(report, states.get(i / FACINGS.length), FACINGS[i % FACINGS.length], at, facts);
                        at.setBlockData(air, false);
                        return true;
                    });
                    if (cursor[0] >= total) {
                        task.cancel();
                        done.accept(report);
                    }
                }, 1L, 1L);
            }
            case "daten" -> {
                for (Map.Entry<String, String> e : DATA_SAMPLES.entrySet()) {
                    checkData(report, e.getKey(), e.getValue(), at, facts);
                    at.setBlockData(air, false);
                }
                done.accept(report);
            }
            case "stabil" -> stability(plugin, to, report, readStates(list), at, facts, done);
            default -> throw new IOException("unbekannter Teil " + part);
        }
    }

    private static void checkState(Report report, String state, Cardinal facing, Block at,
            BlockTiers.WorldFacts facts) {
        report.checked++;
        String text = state.startsWith("minecraft:") ? state.substring("minecraft:".length()) : state;
        BlockSpec spec;
        try {
            spec = BlockSpec.parse(text);
        } catch (PlanException e) {
            report.bad(state + " " + facing + ": nicht lesbar – " + e.getMessage());
            return;
        }
        Orientation o = new Orientation(facing);
        BuildService.Converted conv = BuildService.toBlockData(spec, o);
        if (conv.data() == null) {
            report.bad(state + " " + facing + ": nicht umsetzbar – " + conv.problem());
            return;
        }
        BlockData want;
        try {
            want = expected(state, facing);
        } catch (IllegalArgumentException e) {
            report.bad(state + " " + facing + ": Server kennt den Zustand nicht – " + e.getMessage());
            return;
        }
        BuildJob.Placement pl = BuildService.placement(spec, 0, facts, o, q -> at);
        BuildJob.setLikeABuild(at, conv.data(), pl.explicit());
        String got = at.getBlockData().getAsString();
        if (got.equals(want.getAsString())) {
            report.same++;
        } else {
            report.bad(state + " " + facing + ": erwartet " + want.getAsString() + ", steht " + got);
        }
    }

    private static void checkData(Report report, String block, String snbt, Block at, BlockTiers.WorldFacts facts) {
        report.checked++;
        String id = block.contains("[") ? block.substring(0, block.indexOf('[')) : block;
        if (Material.matchMaterial(id) == null) {
            report.bad(block + ": gibt es in dieser Fassung nicht (übersprungen)");
            return;
        }
        BlockSpec spec;
        try {
            spec = BlockSpec.parseTrusted(block + snbt);
        } catch (PlanException e) {
            report.bad(block + ": Daten nicht lesbar – " + e.getMessage());
            return;
        }
        Orientation o = new Orientation(Cardinal.NORTH);
        BuildService.Converted conv = BuildService.toBlockData(spec, o);
        if (conv.data() == null) {
            report.bad(block + ": nicht umsetzbar – " + conv.problem());
            return;
        }
        BuildJob.Placement pl = BuildService.placement(spec, 0, facts, o, q -> at);
        BuildJob.setLikeABuild(at, conv.data(), pl.explicit());
        BlockDataWriter writer = new BlockDataWriter();
        writer.apply(at, pl.nbt(), pl.expect());
        if (writer.applied() == 1 && at.getType() == conv.data().getMaterial()) {
            report.same++;
        } else {
            report.bad(block + snbt + ": Daten nicht übernommen (Server-Log: bau | daten_…)");
        }
    }

    /**
     * Jede Blockart (Grundzustand der Liste) einmal in ein Raster mit 3 Blöcken Abstand, Stein darunter; nach 100
     * Takten nachlesen. Was sich geändert hat, gleicht Minecraft von selbst an – das ist Wissen für die Doku, kein
     * Fehler des Plugins. Danach Raster und Boden ohne Physik leeren.
     */
    private static void stability(Plugin plugin, CommandSender to, Report report, List<String> states, Block at,
            BlockTiers.WorldFacts facts, java.util.function.Consumer<Report> done) {
        Map<String, String> firstState = new LinkedHashMap<>();
        for (String s : states) {
            String id = s.contains("[") ? s.substring(0, s.indexOf('[')) : s;
            firstState.putIfAbsent(id, s);
        }
        int side = (int) Math.ceil(Math.sqrt(firstState.size()));
        List<Block> cells = new ArrayList<>();
        List<String> wanted = new ArrayList<>();
        int n = 0;
        Orientation o = new Orientation(Cardinal.NORTH);
        // Chunks des Rasters halten, solange nachgelesen wird – sonst entlädt der Server sie ohne Spieler, und es
        // tickt nichts.
        java.util.Set<Long> tickets = new java.util.HashSet<>();
        for (int dx = 0; dx <= side * 3; dx += 16) {
            for (int dz = 0; dz <= side * 3; dz += 16) {
                Block c = at.getRelative(dx, 0, dz);
                tickets.add(((long) (c.getX() >> 4) << 32) | ((c.getZ() >> 4) & 0xFFFFFFFFL));
            }
        }
        for (long k : tickets) {
            at.getWorld().addPluginChunkTicket((int) (k >> 32), (int) k, plugin);
        }
        for (String state : firstState.values()) {
            String id = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
            if (NOT_IN_GRID.contains(id)) {
                continue;
            }
            Block cell = at.getRelative((n % side) * 3, 0, (n / side) * 3);
            n++;
            cell.getRelative(0, -1, 0).setType(Material.STONE, false);
            try {
                BlockSpec spec = BlockSpec.parse(state.substring("minecraft:".length()));
                BuildService.Converted conv = BuildService.toBlockData(spec, o);
                if (conv.data() == null) {
                    report.bad(state + ": nicht umsetzbar");
                    continue;
                }
                BuildJob.setLikeABuild(cell, conv.data(), BuildService.placement(spec, 0, facts, o, q -> cell)
                        .explicit());
                cells.add(cell);
                wanted.add(cell.getBlockData().getAsString());
            } catch (PlanException | IllegalArgumentException e) {
                report.bad(state + ": " + e.getMessage());
            }
        }
        Texts.info(to, "Selbsttest stabil: " + cells.size() + " Blockarten stehen, nachgelesen in 5 s …");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            BlockData air = Material.AIR.createBlockData();
            for (int i = 0; i < cells.size(); i++) {
                report.checked++;
                String now = cells.get(i).getBlockData().getAsString();
                if (now.equals(wanted.get(i))) {
                    report.same++;
                } else {
                    report.bad(wanted.get(i) + " → " + now);
                }
            }
            for (Block c : cells) {
                c.setBlockData(air, false);
                c.getRelative(0, -1, 0).setBlockData(air, false);
            }
            for (long k : tickets) {
                at.getWorld().removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
            }
            done.accept(report);
        }, 100L);
    }

    /** Bericht schreiben und kurz melden. */
    static void finish(Plugin plugin, CommandSender to, Report report) {
        String version = Bukkit.getBukkitVersion().replaceAll("[^0-9A-Za-z.-]", "_");
        Path dir = plugin.getDataFolder().toPath().resolve("selbsttest");
        Path file = dir.resolve(report.part.replace('ö', 'o') + "-" + version + ".txt");
        try {
            Files.createDirectories(dir);
            List<String> out = new ArrayList<>();
            out.add(report.summary());
            out.addAll(report.lines);
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning(() -> "selbsttest | bericht_nicht_geschrieben | grund=" + e.getMessage());
        }
        LOG.info(() -> "selbsttest | " + report.part + " | geprüft=" + report.checked + " gleich=" + report.same
                + " abweichend=" + report.lines.size() + " datei=" + file.getFileName());
        Texts.info(to, report.summary() + (report.clean() ? "." : " – Bericht: " + file));
        for (int i = 0; i < Math.min(MAX_LISTED, report.lines.size()); i++) {
            Texts.info(to, "  " + report.lines.get(i));
        }
        if (report.lines.size() > MAX_LISTED) {
            Texts.info(to, "  … und " + (report.lines.size() - MAX_LISTED) + " weitere in der Datei.");
        }
    }

    /** Datenbeispiel als Baum – für den Test, dass jedes Beispiel durch den Leser geht. */
    static Nbt.Compound sampleData(String block, String snbt) throws PlanException {
        return BlockSpec.parseTrusted(block + snbt).nbt();
    }

    /** Neu geschriebene Fassung eines Beispiels (Snbt.write) – wie sie per data merge an den Server geht. */
    static String sampleText(String block, String snbt) throws PlanException {
        return Snbt.write(sampleData(block, snbt));
    }

    /**
     * Nicht ins Stabil-Raster: Fließendes, Brennendes, Portale und Spawner würden die Nachbarzellen verändern – das
     * Ergebnis hinge dann vom Nachbarn ab, nicht vom Block.
     */
    static final java.util.Set<String> NOT_IN_GRID = java.util.Set.of("minecraft:water", "minecraft:lava",
            "minecraft:bubble_column", "minecraft:fire", "minecraft:soul_fire", "minecraft:nether_portal",
            "minecraft:end_portal", "minecraft:end_gateway", "minecraft:spawner", "minecraft:trial_spawner",
            "minecraft:moving_piston", "minecraft:tnt");
}
