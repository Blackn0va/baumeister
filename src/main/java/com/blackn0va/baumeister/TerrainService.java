package com.blackn0va.baumeister;

import com.blackn0va.baumeister.earth.EarthService;
import com.blackn0va.baumeister.osm.OsmClient;
import com.blackn0va.baumeister.terrain.DemGenerator;
import com.blackn0va.baumeister.terrain.ElevationTiles;
import com.blackn0va.baumeister.terrain.TerrainPopulator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Echtes Gelände 1:1 (Wunsch 2026-10-28). /bm gelaende &lt;Ort|lat,lon&gt; [radius] füllt Chunks beim ersten Laden aus
 * echten
 * Höhendaten (DemGenerator).
 *
 * Senkrecht 1:1 geht nur in einer hohen Welt. Eine normale Welt ist 384 Blöcke hoch, der Grand Canyon rund 1 800 m
 * tief. Deshalb zwei Wege:
 * - Mit Datapack (/bm gelaende einrichten, dann Neustart): Dimension baumeister:gelaende, y −2032..2031 (gemessen
 *   2026-10-28 auf Spigot 1.21.11). Höhe = echte Meereshöhe, solange das Gebiet zwischen −1 990 und 1 990 m liegt,
 *   sonst um volle 100 m verschoben. Alle Orte liegen in dieser einen Welt nebeneinander (TerrainPopulator).
 * - Ohne Datapack: eigene Welt je Ort mit DemGenerator, Höhe gestaucht, wenn sie nicht passt – das wird gesagt.
 *
 * Die Werte jedes Orts liegen unter gelaende/&lt;ort&gt;.json und werden beim Start wieder angehängt: Ohne sie füllte
 * Minecraft unerkundete Chunks mit Luft bzw. normalem Gelände.
 *
 * Threads: Kacheln laden und Höhen messen im eigenen Thread, Welt anlegen und teleportieren auf dem Hauptthread.
 */
final class TerrainService {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Zoom-15-Kachel ≈ 1 km auf 40° Breite; 25 km Radius ≈ 2 500 Kacheln ≈ 250 MB – darüber nicht mehr sinnvoll. */
    static final int MAX_RADIUS = 25_000;
    private static final int DEFAULT_RADIUS = 3_000;
    private static final int MARGIN_BLOCKS = 20;
    /** Stein unter dem tiefsten Punkt des Gebiets (hohe Welt) – genug zum Graben, ohne 4 000 Blöcke je Säule. */
    static final int FLOOR_DEPTH = 48;
    /** Datapack-Ordner und Dimension; Spigot lädt sie als Welt „&lt;Hauptwelt&gt;_baumeister_gelaende“. */
    static final String PACK = "baumeister_gelaende";
    static final String DIMENSION = "baumeister:gelaende";
    /**
     * Datapack-Formate, die das Pack abdeckt: 1.21.11 (94.1) bis 26.2 (107.1, aus version.json der 26.2-Jar,
     * 2026-11-07). Liegt die Serverfassung darüber, meldet Minecraft das Pack als veraltet – geladen wird es trotzdem
     * (gemessen im Probelauf); die Warnung ist dann der Hinweis, upgradePack nachzuziehen.
     */
    static final int[] PACK_MIN = {94, 0};
    static final int[] PACK_MAX = {107, 1};
    private static final int HIGH_WORLD_MIN_HEIGHT = 1024;

    private final BaumeisterPlugin plugin;
    private final ElevationTiles tiles;
    private final TerrainPopulator populator = new TerrainPopulator();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Baumeister-Gelände");
        t.setDaemon(true);
        return t;
    });
    private boolean busy;
    private World attached;

    TerrainService(BaumeisterPlugin plugin) {
        this.plugin = plugin;
        this.tiles = new ElevationTiles(plugin.getDataFolder().toPath().resolve("höhen"));
    }

    /** Die hohe Datapack-Dimension, falls geladen und wirklich hoch – mit angehängtem Populator. */
    World highWorld() {
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        World w = Bukkit.getWorld(Bukkit.getWorlds().get(0).getName() + "_" + PACK);
        if (w == null || w.getMaxHeight() - w.getMinHeight() < HIGH_WORLD_MIN_HEIGHT) {
            return null;
        }
        if (attached != w) {
            w.getPopulators().add(populator);
            w.setSpawnFlags(false, true);
            attached = w;
            LOG.info(() -> "gelände | hohe_welt | welt=" + w.getName() + " y=" + w.getMinHeight() + ".."
                    + (w.getMaxHeight() - 1));
        }
        return w;
    }

    /**
     * Datapack-Ordner der Hauptwelt. Über den Weltcontainer statt getWorldFolder(): Seit 26.x liegen die Dimensionen
     * unter &lt;welt&gt;/dimensions/…, datapacks bleibt im Spielstand-Ordner (gemessen 2026-11-07 im Probelauf).
     */
    static Path datapackDir(Path worldContainer, String levelName) {
        return worldContainer.resolve(levelName).resolve("datapacks").resolve(PACK);
    }

    /** Siehe {@link #upgradePack}; nur wenn das Pack schon liegt (/bm gelaende einrichten). */
    private void upgradeInstalledPack() {
        if (Bukkit.getWorlds().isEmpty()) {
            return;
        }
        Path pack = datapackDir(Bukkit.getWorldContainer().toPath(), Bukkit.getWorlds().get(0).getName());
        if (!Files.isDirectory(pack)) {
            return;
        }
        try {
            List<String> changed = upgradePack(pack);
            if (!changed.isEmpty()) {
                LOG.info(() -> "gelände | datapack_ergaenzt | pfad=" + pack + " dateien=" + String.join(",", changed)
                        + " – wirkt beim nächsten Start, alte Werte unverändert");
            }
        } catch (IOException | RuntimeException e) {
            // Grenze Plugin-Start: Ein unlesbares Pack meldet sich hier, die Geländewelt läuft weiter wie geladen.
            LOG.log(Level.WARNING, "gelände | datapack_ergaenzen_fehlgeschlagen | pfad=" + pack, e);
        }
    }

    /** Gespeicherte Orte mit ihrem Generator bzw. Platz wieder anhängen (beim Start). */
    void loadSaved() {
        upgradeInstalledPack();
        Path dir = plugin.getDataFolder().toPath().resolve("gelaende");
        if (!Files.isDirectory(dir)) {
            highWorld();
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                String name = o.get("welt").getAsString();
                if (o.has("platz")) {
                    if (highWorld() == null) {
                        LOG.warning(() -> "gelände | hohe_welt_fehlt | ort=" + o.get("ort").getAsString()
                                + " – Datapack " + PACK + " entfernt? Neue Chunks bleiben dort leer.");
                        continue;
                    }
                    populator.put(o.get("platz").getAsInt(), slot(o));
                    LOG.info(() -> "gelände | geladen | welt=" + name + " platz=" + o.get("platz").getAsInt());
                } else if (Bukkit.getWorld(name) == null) {
                    create(name, generator(o));
                    LOG.info(() -> "gelände | geladen | welt=" + name);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Eine kaputte Datei darf den Plugin-Start nicht verhindern; der Ort bleibt dann ungeladen.
            LOG.log(Level.WARNING, "gelände | laden_fehlgeschlagen", e);
        }
        highWorld();
    }

    private DemGenerator generator(JsonObject o) {
        return new DemGenerator(tiles, o.get("lat").getAsDouble(), o.get("lon").getAsDouble(),
                o.get("bezug").getAsDouble(), o.get("faktor").getAsDouble(), o.get("basisY").getAsInt(),
                o.has("schnee") ? o.get("schnee").getAsDouble() : 2800,
                o.has("platz") ? o.get("radius").getAsInt() : 0);
    }

    private TerrainPopulator.Slot slot(JsonObject o) {
        return new TerrainPopulator.Slot(generator(o), o.get("bodenY").getAsInt(), o.get("radius").getAsInt());
    }

    private static World create(String name, DemGenerator gen) {
        World w = new WorldCreator(name).generator(gen).generateStructures(false).createWorld();
        if (w != null) {
            w.setSpawnFlags(false, true);
        }
        return w;
    }

    /**
     * /bm gelaende einrichten: Datapack mit der hohen Dimension in den Ordner der Hauptwelt schreiben. Wirksam erst
     * nach einem Neustart (Datapacks mit Dimensionen lädt Minecraft nur beim Start).
     *
     * FALLE (gemessen 2026-10-28, Testserver): level.dat speichert die Dimension. Fehlt das Datapack danach, bricht
     * der Start ab („Failed to get element ResourceKey[minecraft:dimension_type / …]“, nur --safeMode hilft). Deshalb
     * schreibt das Plugin das Datapack nur auf ausdrücklichen Befehl und entfernt es nie selbst.
     */
    void install(CommandSender sender) {
        World main = Bukkit.getWorlds().get(0);
        Path pack = datapackDir(Bukkit.getWorldContainer().toPath(), main.getName());
        String earthName = main.getName() + EarthService.WORLD_SUFFIX;
        List<String> added;
        try {
            added = writePack(pack);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "gelände | datapack_fehlgeschlagen | pfad=" + pack, e);
            Texts.info(sender, "Konnte das Datapack nicht schreiben: " + e.getMessage());
            return;
        }
        World high = highWorld();
        if (added.isEmpty()) {
            if (high != null && Bukkit.getWorld(earthName) != null) {
                Texts.info(sender, "Die hohe Geländewelt (" + high.getName() + ", y " + high.getMinHeight() + " bis "
                        + (high.getMaxHeight() - 1) + ") und die Erdwelt (" + earthName + ") sind schon da.");
            } else {
                Texts.info(sender, "Das Datapack in " + main.getName() + "/datapacks/" + PACK + " ist vollständig – "
                        + "es fehlt nur noch ein Neustart des Servers.");
            }
            return;
        }
        LOG.info(() -> "gelände | datapack_geschrieben | pfad=" + pack + " neu=" + String.join(",", added));
        if (added.contains("pack.mcmeta")) {
            Texts.info(sender, "Datapack liegt in " + main.getName() + "/datapacks/" + PACK + ". Nach dem nächsten "
                    + "Neustart gibt es die Welt " + main.getName() + "_" + PACK + " mit 4 064 Blöcken Höhe – dann baut "
                    + "/bm gelaende jeden Ort 1:1 – und die Erdwelt " + earthName + " (/bm erde). Das Datapack danach "
                    + "NIE löschen: Die Hauptwelt merkt sich die Dimensionen, ohne Datapack startet der Server nicht "
                    + "mehr.");
        } else {
            Texts.info(sender, "Datapack ergänzt um " + String.join(", ", added) + " – nichts entfernt. Nach dem "
                    + "nächsten Neustart gibt es die Erdwelt " + earthName + " (/bm erde). Das Datapack NIE löschen.");
        }
    }

    /**
     * Dimensionen: leerer Flachgenerator (Luft), Höhe −2032..2031; Format von 1.21.11 (Umgebungsattribute) plus die
     * Pflichtschlüssel von 26.x (siehe upgradePack) – 1.21.11 überliest sie. Zwei Dimensionen mit demselben Typ:
     * gelaende (Orte nebeneinander) und erde (die ganze Erde, EarthService).
     *
     * Schreibt nur Dateien, die fehlen, und liefert sie (relativ zum Pack) zurück. Vorher schrieb es immer alles neu
     * – das war harmlos, solange es nur einen Inhalt gab; jetzt ergänzt /bm gelaende einrichten ein älteres Datapack
     * um erde.json. Wer hier etwas löscht oder umschreibt, riskiert den Serverstart (level.dat merkt sich jede
     * Dimension, siehe FALLE oben).
     */
    static List<String> writePack(Path pack) throws IOException {
        List<String> added = new ArrayList<>();
        writeIfMissing(pack, "pack.mcmeta", "{\"pack\":{\"description\":\"Baumeister: hohe Welt für "
                + "Gelände 1:1\",\"min_format\":[" + PACK_MIN[0] + "," + PACK_MIN[1] + "],\"max_format\":["
                + PACK_MAX[0] + "," + PACK_MAX[1] + "]}}\n", added);
        writeIfMissing(pack, "data/baumeister/dimension_type/gelaende.json", "{\"has_fixed_time\":false,"
                + "\"has_skylight\":true,\"has_ceiling\":false,"
                + "\"coordinate_scale\":1.0,\"min_y\":-2032,\"height\":4064,\"logical_height\":4064,"
                + "\"infiniburn\":\"#minecraft:infiniburn_overworld\",\"ambient_light\":0.0,"
                + "\"monster_spawn_light_level\":0,\"monster_spawn_block_light_limit\":0,\"skybox\":\"overworld\","
                + "\"cardinal_light\":\"default\",\"timelines\":\"#minecraft:in_overworld\","
                + "\"has_ender_dragon_fight\":false,\"default_clock\":\"minecraft:overworld\"}\n", added);
        writeIfMissing(pack, "data/baumeister/dimension/gelaende.json", flatDimension(), added);
        writeIfMissing(pack, "data/baumeister/dimension/erde.json", flatDimension(), added);
        return added;
    }

    /**
     * Vorhandenes Datapack für neuere Minecraft-Fassungen ergänzen – nur fehlende Schlüssel, kein vorhandener Wert
     * ändert sich. Läuft bei jedem Plugin-Start. Gemessen 2026-11-07 (Probelauf der dev01-Sicherung auf Paper 26.2):
     * 26.x verlangt im dimension_type „has_ender_dragon_fight“; fehlt es, bricht der Start beim Laden der Datapacks ab
     * („No key has_ender_dragon_fight“), BEVOR ein Plugin läuft. Deshalb ergänzt das Plugin den Schlüssel schon auf
     * der alten Fassung – 1.21.11 überliest unbekannte Schlüssel. „default_clock“: ohne ihn hätte die Dimension in
     * 26.x keine Uhr, also nicht mehr den Tag-Nacht-Wechsel der Oberwelt. pack.mcmeta: max_format bis PACK_MAX; eine
     * vom Admin umgeschriebene, nicht lesbare pack.mcmeta bleibt unberührt.
     * Wer das herausnimmt, bekommt beim nächsten Versionssprung einen Server, der nicht mehr startet.
     */
    static List<String> upgradePack(Path pack) throws IOException {
        List<String> changed = new ArrayList<>();
        String typeRel = "data/baumeister/dimension_type/gelaende.json";
        Path type = pack.resolve(typeRel);
        if (Files.exists(type)) {
            JsonObject o = JsonParser.parseString(Files.readString(type, StandardCharsets.UTF_8)).getAsJsonObject();
            boolean dirty = false;
            if (!o.has("has_ender_dragon_fight")) {
                o.addProperty("has_ender_dragon_fight", false);
                dirty = true;
            }
            boolean fixedTime = o.has("has_fixed_time") && o.get("has_fixed_time").getAsBoolean();
            if (!o.has("default_clock") && !fixedTime) {
                o.addProperty("default_clock", "minecraft:overworld");
                dirty = true;
            }
            if (dirty) {
                Files.writeString(type, o + "\n", StandardCharsets.UTF_8);
                changed.add(typeRel);
            }
        }
        Path meta = pack.resolve("pack.mcmeta");
        if (Files.exists(meta)) {
            JsonObject root = null;
            try {
                root = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                LOG.info(() -> "gelände | pack_mcmeta_eigene_fassung | pfad=" + meta + " – bleibt unverändert");
            }
            if (root != null && root.has("pack") && root.get("pack").isJsonObject()) {
                JsonObject p = root.getAsJsonObject("pack");
                if (p.has("max_format") && olderThan(p.get("max_format"), PACK_MAX)) {
                    com.google.gson.JsonArray max = new com.google.gson.JsonArray();
                    max.add(PACK_MAX[0]);
                    max.add(PACK_MAX[1]);
                    p.add("max_format", max);
                    Files.writeString(meta, root + "\n", StandardCharsets.UTF_8);
                    changed.add("pack.mcmeta");
                }
            }
        }
        return changed;
    }

    /** max_format als [major, minor] oder Zahl – kleiner als want? */
    private static boolean olderThan(com.google.gson.JsonElement format, int[] want) {
        int major;
        int minor = 0;
        if (format.isJsonArray() && !format.getAsJsonArray().isEmpty()) {
            major = format.getAsJsonArray().get(0).getAsInt();
            minor = format.getAsJsonArray().size() > 1 ? format.getAsJsonArray().get(1).getAsInt() : 0;
        } else if (format.isJsonPrimitive() && format.getAsJsonPrimitive().isNumber()) {
            major = format.getAsInt();
        } else {
            return false;
        }
        return major < want[0] || major == want[0] && minor < want[1];
    }

    /** Beide Dimensionen benutzen denselben dimension_type baumeister:gelaende. */
    private static String flatDimension() {
        return "{\"type\":\"" + DIMENSION + "\",\"generator\":{\"type\":\"minecraft:flat\","
                + "\"settings\":{\"layers\":[],\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false}}}\n";
    }

    private static void writeIfMissing(Path pack, String rel, String content, List<String> added) throws IOException {
        Path file = pack.resolve(rel);
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        added.add(rel);
    }

    void build(CommandSender sender, OsmClient osm, String query, Integer radiusArg) {
        if (busy) {
            Texts.info(sender, "Ich lade gerade schon ein Gelände – einen Moment.");
            return;
        }
        int radius = Math.max(200, Math.min(MAX_RADIUS, radiusArg != null ? radiusArg : DEFAULT_RADIUS));
        World high = highWorld();
        Range range = high != null ? new Range(high.getMinHeight(), high.getMaxHeight()) : null;
        int nextSlot = nextSlot();
        busy = true;
        Texts.info(sender, "Suche „" + query + "“ und lade die echten Höhen im Umkreis von " + radius + " m …");
        osm.resolve(query).thenApplyAsync(place -> prepare(sender, place, radius, range, nextSlot), pool)
                .whenComplete((prep, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy = false;
                    if (err != null) {
                        Throwable c = err.getCause() != null ? err.getCause() : err;
                        LOG.log(Level.WARNING, "gelände | fehlgeschlagen | ort=" + query, c);
                        Texts.info(sender, "Das ging nicht: " + c.getMessage());
                        return;
                    }
                    finish(sender, prep);
                }));
    }

    /** Höhe der hohen Welt (Blockkoordinaten, max ausschließlich). */
    record Range(int minY, int maxY) {
    }

    /** Senkrechte Abbildung: Y = basisY + (Höhe − bezug) · faktor; boden = Grundgestein. */
    record Vertical(double ref, double scale, int baseY, int floorY) {
    }

    /**
     * Hohe Welt: echte Meereshöhe, wenn das Gebiet hineinpasst; sonst um volle 100 m verschoben, im Notfall
     * gestaucht (nur Gebiete mit über 3 900 m Höhenunterschied). Normale Welt: tiefster Punkt auf minY + 20.
     */
    static Vertical vertical(double min, double max, Range high) {
        if (high != null) {
            double lo = high.minY() + FLOOR_DEPTH + MARGIN_BLOCKS;
            double hi = high.maxY() - MARGIN_BLOCKS;
            double room = hi - lo;
            double rangeM = Math.max(1, max - min);
            double scale = rangeM <= room ? 1.0 : room / rangeM;
            double ref = 0;
            if (min * scale < lo || max * scale > hi) {
                ref = Math.round((min + max) / 2 / 100.0) * 100.0;
                if ((min - ref) * scale < lo || (max - ref) * scale > hi) {
                    ref = (min + max) / 2;
                }
            }
            int floor = (int) Math.max(high.minY(), Math.floor((min - ref) * scale) - FLOOR_DEPTH);
            return new Vertical(ref, scale, 0, floor);
        }
        int worldMin = -64;
        int worldHeight = 384;
        double room = worldHeight - 2.0 * MARGIN_BLOCKS;
        double rangeM = Math.max(1, max - min);
        double scale = rangeM <= room ? 1.0 : room / rangeM;
        return new Vertical(min, scale, worldMin + MARGIN_BLOCKS, worldMin);
    }

    private int nextSlot() {
        int max = 0;
        Path dir = plugin.getDataFolder().toPath().resolve("gelaende");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (o.has("platz")) {
                        max = Math.max(max, o.get("platz").getAsInt());
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "gelände | plätze_lesen_fehlgeschlagen", e);
            }
        }
        return max + 1;
    }

    /**
     * Platz, auf dem derselbe Ort (Breite/Länge auf ~1 cm, gleicher Radius) schon liegt – sonst leer.
     *
     * Vorher bekam jeder Aufruf von /bm gelaende einen neuen Platz, auch für denselben Ort: auf dev01 lag der Grand
     * Canyon dreimal in der Welt (Gedächtnis, „Offen“), und der Reisepunkt zeigte nur noch auf den letzten. Jetzt
     * führt der zweite Aufruf an den ersten Platz zurück. Ein anderer Radius ist ein anderes Gelände und bekommt einen
     * neuen Platz – sonst passten schon erzeugte Chunks nicht zu den neuen. Wer das entfernt, füllt die hohe Welt mit
     * Kopien (jeder Platz 65 536 Blöcke weiter).
     */
    static java.util.OptionalInt existingSlot(Path dir, double lat, double lon, int radius) {
        if (!Files.isDirectory(dir)) {
            return java.util.OptionalInt.empty();
        }
        List<Path> list;
        try (Stream<Path> files = Files.list(dir)) {
            list = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "gelände | plätze_lesen_fehlgeschlagen", e);
            return java.util.OptionalInt.empty();
        }
        for (Path f : list) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("platz") && o.has("lat") && o.has("lon") && o.has("radius")
                        && Math.abs(o.get("lat").getAsDouble() - lat) < 1e-7
                        && Math.abs(o.get("lon").getAsDouble() - lon) < 1e-7
                        && o.get("radius").getAsInt() == radius) {
                    return java.util.OptionalInt.of(o.get("platz").getAsInt());
                }
            } catch (IOException | RuntimeException e) {
                // Eine unlesbare Datei kostet nur sich selbst; ohne Treffer gibt es einen neuen Platz – lieber eine
                // Kopie als ein falsches Gelände.
                LOG.log(Level.WARNING, "gelände | platz_unlesbar | datei=" + f.getFileName(), e);
            }
        }
        return java.util.OptionalInt.empty();
    }

    /** Ergebnis der Vorarbeit im Hintergrund. reused: Platz gab es schon (existingSlot). */
    private record Prepared(String world, String place, JsonObject params, double min, double max, int tiles,
            Vertical v, boolean high, boolean reused) {
    }

    private Prepared prepare(CommandSender sender, OsmClient.Place place, int radius, Range high, int freeSlot) {
        java.util.OptionalInt known = high == null ? java.util.OptionalInt.empty()
                : existingSlot(plugin.getDataFolder().toPath().resolve("gelaende"), place.lat(), place.lon(), radius);
        int slot = known.orElse(freeSlot);
        double cos = Math.cos(Math.toRadians(place.lat()));
        double dLat = radius / 110_540.0;
        double dLon = radius / (111_320.0 * cos);
        int z = ElevationTiles.ZOOM;
        int x0 = (int) Math.floor(ElevationTiles.tileX(place.lon() - dLon, z));
        int x1 = (int) Math.floor(ElevationTiles.tileX(place.lon() + dLon, z));
        int y0 = (int) Math.floor(ElevationTiles.tileY(place.lat() + dLat, z));
        int y1 = (int) Math.floor(ElevationTiles.tileY(place.lat() - dLat, z));
        List<Future<?>> jobs = new ArrayList<>();
        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                int fx = tx;
                int fy = ty;
                jobs.add(pool.submit(() -> {
                    tiles.tile(fx, fy);
                    return null;
                }));
            }
        }
        int count = jobs.size();
        Bukkit.getScheduler().runTask(plugin, () -> Texts.info(sender, count + " Höhenkacheln …"));
        for (Future<?> f : jobs) {
            try {
                f.get();
            } catch (Exception e) {
                throw new IllegalStateException("Höhendaten nicht ladbar: " + (e.getCause() != null
                        ? e.getCause().getMessage() : e.getMessage()), e);
            }
        }
        // Tiefster und höchster Punkt, alle 16 m gemessen.
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int bx = -radius; bx <= radius; bx += 16) {
            for (int bz = -radius; bz <= radius; bz += 16) {
                double lat = place.lat() - bz / 110_540.0;
                double lon = place.lon() + bx / (111_320.0 * cos);
                double e;
                try {
                    e = tiles.elevation(lat, lon);
                } catch (IOException ex) {
                    continue;
                }
                min = Math.min(min, e);
                max = Math.max(max, e);
            }
        }
        if (min == Double.MAX_VALUE) {
            throw new IllegalStateException("Keine Höhendaten für diesen Ort.");
        }
        Vertical v = vertical(min, max, high);
        JsonObject o = new JsonObject();
        String slug = slug(place.name());
        String name = high != null ? Bukkit.getWorlds().get(0).getName() + "_" + PACK : "gelaende_" + slug;
        o.addProperty("welt", name);
        o.addProperty("ort", place.name());
        o.addProperty("lat", place.lat());
        o.addProperty("lon", place.lon());
        o.addProperty("bezug", v.ref());
        o.addProperty("faktor", v.scale());
        o.addProperty("basisY", v.baseY());
        o.addProperty("radius", radius);
        o.addProperty("schnee", 2800);
        if (high != null) {
            o.addProperty("platz", slot);
            o.addProperty("bodenY", v.floorY());
        }
        try {
            Path dir = plugin.getDataFolder().toPath().resolve("gelaende");
            Files.createDirectories(dir);
            String file = high != null ? slot + "_" + slug : slug;
            Files.writeString(dir.resolve(file + ".json"), o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Kann die Geländedaten nicht speichern: " + e.getMessage(), e);
        }
        return new Prepared(name, place.name(), o, min, max, count, v, high != null, known.isPresent());
    }

    private void finish(CommandSender sender, Prepared p) {
        World w;
        Location target;
        if (p.high()) {
            w = highWorld();
            if (w == null) {
                Texts.info(sender, "Die hohe Geländewelt ist weg – siehe Serverlog.");
                return;
            }
            int slot = p.params().get("platz").getAsInt();
            TerrainPopulator.Slot s = slot(p.params());
            populator.put(slot, s);
            int y = s.gen().surfaceY(0, 0, w.getMinHeight(), w.getMaxHeight());
            target = new Location(w, slot * (double) TerrainPopulator.SLOT_BLOCKS + 0.5, y + 2, 0.5);
        } else {
            w = Bukkit.getWorld(p.world());
            if (w == null) {
                w = create(p.world(), generator(p.params()));
            }
            if (w == null) {
                Texts.info(sender, "Die Welt ließ sich nicht anlegen – siehe Serverlog.");
                return;
            }
            target = w.getSpawnLocation();
        }
        Vertical v = p.v();
        String scale;
        if (v.scale() < 0.999) {
            scale = String.format(Locale.GERMAN, "Höhe gestaucht 1:%.1f – %.0f m Höhenunterschied passen nicht in "
                    + "die Welt", 1 / v.scale(), p.max() - p.min());
        } else if (p.high()) {
            scale = v.ref() == 0 ? "Höhe 1:1, y = Meter über dem Meer"
                    : String.format(Locale.GERMAN, "Höhe 1:1, y + %.0f = Meter über dem Meer", v.ref());
        } else {
            scale = "Höhe 1:1";
        }
        String hint = p.high() || p.max() - p.min() <= 344 ? ""
                : " Für 1:1 auch in der Höhe: /bm gelaende einrichten, dann Neustart.";
        Texts.info(sender, String.format(Locale.GERMAN, "%s: Gelände von %.0f m bis %.0f m, %d Höhenkacheln, "
                + "1 Block = 1 m waagerecht, %s. Welt: %s – sie entsteht beim Erkunden. Höhen: Mapzen/AWS Terrain "
                + "Tiles (SRTM, USGS u. a.).%s%s", shortName(p.place()), p.min(), p.max(), p.tiles(), scale,
                w.getName(), hint, p.reused() ? " Diesen Ort gab es schon – du landest am selben Platz wie vorher." : ""));
        plugin.places().remember(shortName(p.place()), target, PlaceRegistry.Kind.TERRAIN);
        if (sender instanceof Player player) {
            player.teleport(target);
        }
        String worldName = w.getName();
        LOG.info(() -> String.format(Locale.ROOT, "gelände | angelegt | welt=%s min=%.0f max=%.0f bezug=%.0f "
                + "faktor=%.3f kacheln=%d ziel=%d/%d/%d", worldName, p.min(), p.max(), v.ref(), v.scale(), p.tiles(),
                target.getBlockX(), target.getBlockY(), target.getBlockZ()));
    }

    static String slug(String place) {
        String first = place.contains(",") && !place.matches("^-?\\d.*") ? place.substring(0, place.indexOf(','))
                : place;
        String ascii = Normalizer.normalize(first.toLowerCase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String s = ascii.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "ort" : s.length() > 24 ? s.substring(0, 24) : s;
    }

    private static String shortName(String display) {
        int comma = display.indexOf(',');
        return comma > 0 && !display.matches("^-?\\d.*") ? display.substring(0, comma) : display;
    }
}
