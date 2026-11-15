package com.blackn0va.baumeister;

import com.blackn0va.baumeister.structure.Nbt;
import com.blackn0va.baumeister.structure.NbtOut;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.BlockVector;

/**
 * Bauwerke des Spiels als Vorlagen für den Baumeister (Wunsch 2026-11-14: „Monumente, Gebäude … massenhaft“).
 *
 * Planet Minecraft scheidet aus (Nutzungsbedingungen), fremde Sammlungen bestehen oft aus Lag-Technik. Das Spiel selbst
 * erzeugt aber jedes Bauwerk vollständig – Tempel, Hexenhütte, Außenposten, Bastion, antike Stadt, Prüfungskammern,
 * ganze Dörfer. /bm spielbauwerk lässt es in einer eigenen Flachwelt entstehen (place structure, wie ein Admin es von
 * Hand täte), bestimmt den Umriss über den Vergleich mit der unberührten Flachwelt und speichert den Bereich als
 * .nbt unter schematics/spiel/ samt index.tsv. Danach baut der Baumeister es wie jede Schematic: Block für Block,
 * mit Rückgängig, Rechteprüfung und Lag-Wächter. Die Dateien entstehen auf dem Server und kommen nie ins Repo.
 *
 * Gemessen 2026-11-15 (Spigot 1.21.11, Flachwelt): place structure greift für desert_pyramid, swamp_hut,
 * pillager_outpost, bastion_remnant, ancient_city, trial_chambers, village_plains, jungle_pyramid, igloo; mansion
 * scheiterte („Failed to place structure“).
 *
 * Threads: Server-Thread; Vorladen über ChunkPrefetch, nur der Blockvergleich läuft auf ChunkSnapshots außerhalb.
 * Fehler: Meldung an den Absender und ins Log, nichts halb Gespeichertes (erst .nbt, dann die index-Zeile).
 */
final class GameStructures {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    static final String WORLD = "baumeister_vorlagen";
    /**
     * Obergrenze des erfassten Quaders. Vorher 1 Mio. – die antike Stadt (184×31×213 = 1,21 Mio., gemessen 2026-11-15)
     * fiel heraus, obwohl der Großteil Luft ist. Ob der Baumeister sie baut, entscheidet beim Bau schematics.max-blöcke
     * (zählt Blöcke, höchstens 4 Mio.); hier nur die Speichergrenze für Structure#fill.
     */
    static final long MAX_VOLUME = 4_000_000;
    /**
     * Flachwelt wie das echte Gelände: Grundgestein, 123 Lagen Stein, Erde, Gras – Oberfläche y=63.
     * Vorher die Standard-Flachwelt mit Gras auf y=-61 direkt über dem Grundgestein: Die Fallgrube des Wüstentempels
     * (rund 14 Blöcke unter dem Boden) lag unter der Welt und fehlte (21×15×21, Probe 2026-11-15). Ein schwebender
     * Boden über Luft war schlechter: Das Spiel füllt Fundamente bis zum nächsten festen Block auf, der Tempel wurde
     * 21×137×21 (gemessen). Unberührtes Gestein nimmt keepMask wieder aus der Vorlage.
     */
    static final String FLAT_SETTINGS = "{\"biome\":\"minecraft:plains\",\"lakes\":false,\"features\":false,"
            + "\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:stone\","
            + "\"height\":123},{\"block\":\"minecraft:dirt\",\"height\":3},{\"block\":\"minecraft:grass_block\","
            + "\"height\":1}],\"structure_overrides\":[]}";
    /**
     * Arten, die im Spiel tief im Gestein liegen. Oben auf den Boden gesetzt (wie bis 2026-11-15) standen
     * Prüfungskammern und antike Stadt frei in der Luft – ohne die Gänge, die das Gestein im Original ausspart.
     */
    static final Set<String> UNDERGROUND = Set.of("ancient_city", "trial_chambers");
    /** Tiefe für diese Arten: 83 Blöcke Stein darüber, 44 darunter bis zum Grundgestein. */
    static final int UNDERGROUND_Y = -20;
    /** Vorladen um den Startpunkt: antike Stadt und Prüfungskammern reichen gut 100 Blöcke weit. */
    private static final int RADIUS_CHUNKS = 10;
    /** Abstand der Plätze in der Vorlagenwelt: weit genug, dass sich keine zwei Bauwerke berühren. */
    private static final int SPACING = 2048;

    /** id → (Familienname für Katalog und Chat, Beschreibung). Reihenfolge = Liste im Chat. */
    static final Map<String, String[]> KINDS = new LinkedHashMap<>();

    static {
        put("desert_pyramid", "Wüstentempel", "Sandsteinpyramide mit Schatzkammer");
        put("jungle_pyramid", "Dschungeltempel", "bemooster Tempel mit Fallen");
        put("swamp_hut", "Hexenhütte", "Hütte auf Stelzen");
        put("igloo", "Iglu", "Schneehütte");
        put("pillager_outpost", "Plünderer-Außenposten", "Wachturm mit Zelten und Käfigen");
        put("village_plains", "Wiesendorf", "ganzes Dorf im Wiesenstil");
        put("village_desert", "Wüstendorf", "ganzes Dorf im Wüstenstil");
        put("village_savanna", "Savannendorf", "ganzes Dorf im Savannenstil");
        put("village_snowy", "Schneedorf", "ganzes Dorf im Schneestil");
        put("village_taiga", "Taigadorf", "ganzes Dorf im Taigastil");
        put("bastion_remnant", "Bastionsruine", "Netherfestung der Piglins");
        put("fortress", "Netherfestung", "Brücken und Gänge aus Netherziegeln");
        put("ancient_city", "Antike Stadt", "Ruinenstadt der Tiefe");
        put("trial_chambers", "Prüfungskammern", "Kammern aus Tuff und Kupfer");
        put("trail_ruins", "Pfadruine", "verschüttete Ruine");
        put("ruined_portal", "Portalruine", "zerfallenes Netherportal");
        put("shipwreck", "Schiffswrack", "gestrandetes Schiff");
        put("ocean_ruin_cold", "Ozeanruine", "Steinziegelruine aus dem Meer");
        put("end_city", "Endstadt", "Türme der Endstadt");
    }

    private static void put(String id, String family, String description) {
        KINDS.put(id, new String[] {family, description});
    }

    private final BaumeisterPlugin plugin;
    private boolean busy;

    GameStructures(BaumeisterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Dateiname ohne Endung: family in ASCII (ä → ae …), klein, Unterstrich, plus Nummer. */
    static String fileStem(String family, int n) {
        String s = family.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue") // umlaut-ok
                .replace("ß", "ss").replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", ""); // umlaut-ok
        return s + "_" + n;
    }

    /** Zeile für index.tsv (datei, name, anzeige, beschreibung, quelle) – Tabs und Zeilenumbrüche entfernt. */
    static String indexLine(String file, String name, String family, int n, String description) {
        List<String> cols = new ArrayList<>();
        for (String c : List.of(file, name, family + ", Variante " + n, description,
                "Minecraft, auf diesem Server erzeugt")) {
            // Je Feld: Ein Tab in einem Feld verschöbe sonst alle Spalten dahinter (GameStructuresTest).
            cols.add(c.replaceAll("[\\t\\r\\n]+", " "));
        }
        return String.join("\t", cols) + "\n";
    }

    /** Nächste freie Nummer für eine Familie im Ordner. */
    static int nextNumber(Path dir, String family) {
        int n = 1;
        while (Files.exists(dir.resolve(fileStem(family, n) + ".nbt"))) {
            n++;
        }
        return n;
    }

    void command(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("liste")) {
            Texts.info(sender, "/bm spielbauwerk <art> [anzahl 1-5] – erzeugt Bauwerke des Spiels als Vorlagen. Arten: "
                    + String.join(", ", KINDS.keySet()));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        String[] kind = KINDS.get(id);
        if (kind == null) {
            Texts.info(sender, "Die Art „" + Texts.clean(args[1], 40) + "“ kenne ich nicht. /bm spielbauwerk liste");
            return;
        }
        int count = 1;
        if (args.length > 2) {
            try {
                count = Math.clamp(Integer.parseInt(args[2]), 1, 5);
            } catch (NumberFormatException e) {
                Texts.info(sender, "Anzahl als Zahl 1-5 angeben.");
                return;
            }
        }
        if (busy) {
            Texts.info(sender, "Ich erzeuge gerade schon ein Bauwerk – einen Moment.");
            return;
        }
        Structure structure = Registry.STRUCTURE.get(NamespacedKey.minecraft(id));
        if (structure == null) {
            Texts.info(sender, "Dieses Bauwerk gibt es in dieser Minecraft-Fassung nicht: " + id);
            return;
        }
        World w = world();
        if (w == null) {
            Texts.info(sender, "Die Vorlagenwelt ließ sich nicht anlegen – siehe Serverlog.");
            return;
        }
        busy = true;
        Path dir = plugin.getDataFolder().toPath().resolve("schematics").resolve("spiel");
        next(sender, w, structure, id, kind, dir, count, 0);
    }

    private void next(CommandSender sender, World w, Structure structure, String id, String[] kind, Path dir,
            int count, int done) {
        if (done >= count) {
            busy = false;
            Texts.info(sender, "Fertig: " + done + " × " + kind[0] + ". Im Katalog nach spätestens 30 s: /bm katalog "
                    + kind[0]);
            return;
        }
        int n;
        try {
            Files.createDirectories(dir);
            n = nextNumber(dir, kind[0]);
        } catch (IOException e) {
            busy = false;
            LOG.log(Level.WARNING, "spielbauwerk | ordner | " + dir, e);
            Texts.info(sender, "Ordner nicht anlegbar: " + e.getMessage());
            return;
        }
        int slot = (List.copyOf(KINDS.keySet()).indexOf(id) + 1) * 64 + n;
        int x = slot * SPACING;
        int z = 0;
        int cx = x >> 4;
        long[] keys = new long[(2 * RADIUS_CHUNKS + 1) * (2 * RADIUS_CHUNKS + 1)];
        int k = 0;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                keys[k++] = ChunkPrefetch.key(cx + dx, dz);
            }
        }
        ChunkPrefetch.ensure(ChunkPrefetch.of(plugin, w), keys, 8, p -> {
            int y = UNDERGROUND.contains(id) ? UNDERGROUND_Y : w.getHighestBlockYAt(x, z) + 1;
            boolean placed = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), String.format(Locale.ROOT,
                    "execute in %s run place structure minecraft:%s %d %d %d", w.getKey(), id, x, y, z));
            // Nach 5 Ticks: Jigsaw-Teile (Dorf, Bastion) setzt der Befehl sofort, Nachbar-Updates sind dann durch.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                List<ChunkSnapshot> snaps = new ArrayList<>();
                for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                    for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                        snaps.add(w.getChunkAt(cx + dx, dz).getChunkSnapshot(false, false, false));
                    }
                }
                // Bezug: die unberührte flache Welt zwischen zwei Plätzen (dort wird nie etwas erzeugt).
                ChunkSnapshot ref = w.getChunkAt((x + SPACING / 2) >> 4, 0).getChunkSnapshot(false, false, false);
                int minY = w.getMinHeight();
                Material[] reference = new Material[w.getMaxHeight() - minY];
                for (int i = 0; i < reference.length; i++) {
                    reference[i] = ref.getBlockType(0, minY + i, 0);
                }
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    int[] b = changedBounds(snaps, reference, minY);
                    BitSet keep = b == null ? null : keepMask(snaps, reference, minY, b);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Saved saved;
                        try {
                            saved = save(w, id, kind, dir, n, b, placed, cx);
                        } catch (IOException | RuntimeException e) {
                            // Werkzeug-Grenze: eine missglückte Vorlage beendet die Reihe, nicht den Server.
                            LOG.log(Level.WARNING, "spielbauwerk | fehlgeschlagen | art=" + id, e);
                            saved = Saved.failed("Das ging nicht: " + e.getMessage());
                        } finally {
                            if (p != null) {
                                p.release();
                            }
                        }
                        if (saved.file() == null) {
                            finish(sender, w, structure, id, kind, dir, count, done, saved.message());
                            return;
                        }
                        Saved s = saved;
                        // Datei lesen, filtern und schreiben braucht bei der antiken Stadt Hunderte ms – nicht im Tick.
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            String msg;
                            try {
                                int dropped = prune(s.file(), keep, s.sx(), s.sy(), s.sz());
                                Files.writeString(dir.resolve("index.tsv"),
                                        indexLine(s.stem() + ".nbt", s.stem(), kind[0], n, kind[1]),
                                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                LOG.info(() -> String.format(Locale.ROOT, "spielbauwerk | gespeichert | art=%s "
                                        + "datei=%s größe=%dx%dx%d untergrund_entfernt=%d abgeschnitten=%s", id,
                                        s.stem(), s.sx(), s.sy(), s.sz(), dropped, s.cut()));
                                msg = s.message();
                            } catch (IOException | RuntimeException e) {
                                // Ohne index-Zeile kommt die Datei nicht in den Katalog – lieber ganz weg als halb.
                                LOG.log(Level.WARNING, "spielbauwerk | fehlgeschlagen | art=" + id, e);
                                deleteQuietly(s.file());
                                msg = "Das ging nicht: " + e.getMessage();
                            }
                            String text = msg;
                            Bukkit.getScheduler().runTask(plugin,
                                    () -> finish(sender, w, structure, id, kind, dir, count, done, text));
                        });
                    });
                });
            }, 5L);
        });
    }

    private void finish(CommandSender sender, World w, Structure structure, String id, String[] kind, Path dir,
            int count, int done, String text) {
        // Vorher ging das Ergebnis nur an den Absender – beim RCON-Aufruf war es nach dem Trennen weg, und im Log
        // stand nichts (Probe 2026-11-15, 7 Arten ohne jede Zeile). Jetzt immer ins Log.
        LOG.info(() -> "spielbauwerk | ergebnis | art=" + id + " " + text);
        Texts.info(sender, text);
        next(sender, w, structure, id, kind, dir, count, done + 1);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "spielbauwerk | aufräumen | datei=" + file.getFileName(), e);
        }
    }

    /** Ergebnis des Speicherns im Server-Thread; file null heißt: nichts gespeichert, message sagt warum. */
    record Saved(Path file, String stem, int sx, int sy, int sz, boolean cut, String message) {

        static Saved failed(String message) {
            return new Saved(null, null, 0, 0, 0, false, message);
        }
    }

    /**
     * Umriss aller Blöcke, die von der flachen Welt abweichen: {minX, minY, minZ, maxX, maxY, maxZ} in Weltkoordinaten,
     * oder null, wenn nichts abweicht. Vorher kam der Umriss aus Chunk#getStructures – /place structure trägt aber
     * keinen Strukturstart ein, die Liste blieb bei allen 7 Arten leer (gemessen auf Paper 26.2, 2026-11-15). Läuft
     * außerhalb des Hauptthreads; leere Abschnitte über reiner Luft werden übersprungen.
     */
    static int[] changedBounds(List<ChunkSnapshot> snaps, Material[] reference, int minY) {
        int sections = (reference.length + 15) / 16;
        boolean[] airSection = new boolean[sections];
        for (int s = 0; s < sections; s++) {
            boolean air = true;
            for (int i = s * 16; i < Math.min(reference.length, s * 16 + 16); i++) {
                air &= isAir(reference[i]);
            }
            airSection[s] = air;
        }
        int[] b = null;
        for (ChunkSnapshot c : snaps) {
            int ox = c.getX() << 4;
            int oz = c.getZ() << 4;
            for (int s = 0; s < sections; s++) {
                if (airSection[s] && c.isSectionEmpty(s)) {
                    continue;
                }
                for (int i = s * 16; i < Math.min(reference.length, s * 16 + 16); i++) {
                    int y = minY + i;
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            if (c.getBlockType(lx, y, lz) == reference[i]) {
                                continue;
                            }
                            int wx = ox + lx;
                            int wz = oz + lz;
                            if (b == null) {
                                b = new int[] {wx, y, wz, wx, y, wz};
                            } else {
                                b[0] = Math.min(b[0], wx);
                                b[1] = Math.min(b[1], y);
                                b[2] = Math.min(b[2], wz);
                                b[3] = Math.max(b[3], wx);
                                b[4] = Math.max(b[4], y);
                                b[5] = Math.max(b[5], wz);
                            }
                        }
                    }
                }
            }
        }
        return b;
    }

    private Saved save(World w, String id, String[] kind, Path dir, int n, int[] b, boolean placed, int cx)
            throws IOException {
        if (b == null) {
            return Saved.failed(placed
                    ? "Das Spiel hat " + kind[0] + " nicht gesetzt (kein Block weicht von der flachen Welt ab)."
                    : "place structure ist gescheitert – siehe Serverlog.");
        }
        int sx = b[3] - b[0] + 1;
        int sy = b[4] - b[1] + 1;
        int sz = b[5] - b[2] + 1;
        long volume = (long) sx * sy * sz;
        if (volume > MAX_VOLUME) {
            return Saved.failed(kind[0] + " ist zu groß für eine Vorlage (" + sx + "×" + sy + "×" + sz + " = " + volume
                    + " Blöcke, höchstens " + MAX_VOLUME + ").");
        }
        // Reicht der Umriss bis an den Rand der vorgeladenen Chunks, fehlt vermutlich ein Stück – ehrlich melden.
        int lo = (cx - RADIUS_CHUNKS) << 4;
        int hi = ((cx + RADIUS_CHUNKS) << 4) + 15;
        int r = RADIUS_CHUNKS << 4;
        boolean cut = b[0] <= lo || b[3] >= hi || b[2] <= -r || b[5] >= r + 15;
        Location corner = new Location(w, b[0], b[1], b[2]);
        org.bukkit.structure.Structure s = Bukkit.getStructureManager().createStructure();
        s.fill(corner, new BlockVector(sx, sy, sz), false);
        String stem = fileStem(kind[0], n);
        Path file = dir.resolve(stem + ".nbt");
        Bukkit.getStructureManager().saveStructure(file.toFile(), s);
        return new Saved(file, stem, sx, sy, sz, cut, kind[0] + " " + n + " gespeichert (" + sx + "×" + sy + "×" + sz
                + ")." + (cut ? " Achtung: reicht bis an den Rand des Suchbereichs, ein Stück fehlt vielleicht." : ""));
    }

    /** Ohne Material#isAir: das fragt die Registry, die es nur im laufenden Server gibt (Unit-Test). */
    static boolean isAir(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    /** Index einer Zelle im Quader, wie keepMask und prune ihn teilen. */
    static int cell(int x, int y, int z, int sx, int sz) {
        return (y * sz + z) * sx + x;
    }

    /**
     * Welche Zellen des Quaders in die Vorlage gehören: alles, was das Bauwerk verändert hat, und Luft (damit der
     * Baumeister den Innenraum freiräumt). Unberührter Untergrund – Gestein, Erde, Gras – gehört nicht dazu: Ohne
     * diese Maske käme der ganze Quader mit, um jede Fallgrube ein Klotz aus Stein. Läuft außerhalb des Hauptthreads.
     */
    static BitSet keepMask(List<ChunkSnapshot> snaps, Material[] reference, int minY, int[] b) {
        int sx = b[3] - b[0] + 1;
        int sz = b[5] - b[2] + 1;
        BitSet keep = new BitSet();
        for (ChunkSnapshot c : snaps) {
            int ox = c.getX() << 4;
            int oz = c.getZ() << 4;
            int x0 = Math.max(ox, b[0]);
            int x1 = Math.min(ox + 15, b[3]);
            int z0 = Math.max(oz, b[2]);
            int z1 = Math.min(oz + 15, b[5]);
            if (x0 > x1 || z0 > z1) {
                continue;
            }
            for (int y = b[1]; y <= b[4]; y++) {
                Material ref = reference[y - minY];
                boolean refAir = isAir(ref);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        if (refAir || c.getBlockType(x - ox, y, z - oz) != ref) {
                            keep.set(cell(x - b[0], y - b[1], z - b[2], sx, sz));
                        }
                    }
                }
            }
        }
        return keep;
    }

    /**
     * Streicht aus einer gespeicherten Strukturdatei jeden Blockeintrag, den keep nicht enthält, und schreibt sie über
     * eine Nachbardatei atomar zurück. Liefert die Zahl der gestrichenen Einträge.
     */
    @SuppressWarnings("unchecked")
    static int prune(Path file, BitSet keep, int sx, int sy, int sz) throws IOException {
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(file)) {
            root = Nbt.read(in);
        }
        if (!(root.get("blocks") instanceof List<?> blocks)) {
            throw new IOException("Strukturdatei ohne blocks: " + file.getFileName());
        }
        List<Object> kept = new ArrayList<>(blocks.size());
        for (Object o : blocks) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("pos") instanceof List<?> pos) || pos.size() != 3) {
                throw new IOException("Blockeintrag ohne pos in " + file.getFileName());
            }
            int x = ((Number) pos.get(0)).intValue();
            int y = ((Number) pos.get(1)).intValue();
            int z = ((Number) pos.get(2)).intValue();
            boolean inside = x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
            // Außerhalb des Quaders kann nichts stehen; käme es doch, bleibt es lieber drin als still zu fehlen.
            if (!inside || keep.get(cell(x, y, z, sx, sz))) {
                kept.add(o);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(root);
        out.put("blocks", kept);
        Path tmp = file.resolveSibling(file.getFileName() + ".neu");
        try (OutputStream o = Files.newOutputStream(tmp)) {
            NbtOut.writeGzip(out, o);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return blocks.size() - kept.size();
    }

    /** Flache Vorlagenwelt ohne eigene Bauwerke und ohne Monster; null, wenn sie sich nicht anlegen lässt. */
    private World world() {
        World w = Bukkit.getWorld(WORLD);
        if (w != null) {
            return w;
        }
        w = new WorldCreator(WORLD).type(WorldType.FLAT).generatorSettings(FLAT_SETTINGS).generateStructures(false)
                .createWorld();
        if (w != null) {
            w.setSpawnFlags(false, false);
            LOG.info(() -> "spielbauwerk | welt_angelegt | welt=" + WORLD);
        }
        return w;
    }

    /** Für Tests: alle Dateinamen gültig nach SchematicCatalog.NAME (a-z0-9_, 2–32). */
    static List<String> stems() {
        List<String> out = new ArrayList<>();
        for (String[] k : KINDS.values()) {
            out.add(fileStem(k[0], 12));
        }
        return out;
    }
}
