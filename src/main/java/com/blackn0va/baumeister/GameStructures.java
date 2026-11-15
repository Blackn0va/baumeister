package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.SchematicCatalog;
import com.blackn0va.baumeister.structure.NbtOut;

import java.io.IOException;
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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.structure.Structure;
import org.bukkit.persistence.PersistentDataType;

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
 * Gemessen 2026-11-15: Auf Spigot 1.21.11 scheiterte mansion („Failed to place structure“), auf Paper 26.2 (dev01)
 * greifen alle 38 Arten hier, je 3× erzeugt (114/114) – das Ozeanmonument nur in der Tiefsee-Welt (SEA_WORLD),
 * Wüstenbrunnen und Eisstachel nur auf ihrem Untergrund (GROUND).
 *
 * Ablauf (seit 2026-11-15 nachmittags, nach dem Review): place im Tick, 5 Ticks später ChunkSnapshots im Tick, alles
 * Weitere außerhalb – Vergleich, Datei aus den Snapshots schreiben (writeTemplate), index-Zeile, Umbenennen. Vorher
 * erfasste Structure#fill den ganzen Quader im Tick (antike Stadt ≥ 1,3 s am Laptop), prune las die Datei danach
 * als Java-Maps wieder ein (~550 MB), und behaltene Luft über dem Boden machte 85 % der Datei aus – beim Bau kam sie
 * nie an.
 *
 * Threads: Server-Thread; Vorladen über ChunkPrefetch, Vergleich und Schreiben auf ChunkSnapshots außerhalb.
 * Fehler: Meldung an den Absender und ins Log; nichts halb Gespeichertes (erst .nbt.neu, dann die index-Zeile, dann
 * atomar umbenennen – FileSchematics liest .neu nicht).
 */
final class GameStructures {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    static final String WORLD = "baumeister_vorlagen";
    /**
     * Zweite Vorlagenwelt mit Tiefsee-Biom: Das Ozeanmonument prüft beim Setzen selbst, ob ringsum Ozean ist, und
     * scheiterte in der Wiesen-Flachwelt mit „Failed to place structure“ (gemessen 2026-11-15, Paper 26.2). Die anderen
     * Arten prüfen das Biom bei /place structure nicht.
     */
    static final String SEA_WORLD = "baumeister_vorlagen_meer";
    static final Set<String> SEA = Set.of("monument");
    /** Quellenspalte der index.tsv; daran erkennt der Auftragsweg ein Bauwerk des Spiels (preferred). */
    static final String SOURCE = "Minecraft, auf diesem Server erzeugt";
    /** Stichwort der Katalogsuche für genau diese Herkunft: /bm katalog spiel. */
    static final String TAG = "spiel";
    /** Obergrenze des Quaders (nur noch fürs BitSet der Maske; der vorgeladene Bereich ist ohnehin kleiner). */
    static final long MAX_VOLUME = 4_000_000;
    /**
     * Höchstens so viele Blöcke je Vorlage. Gemessen 2026-11-15 nach dem Streichen: antike Stadt rund 54 000,
     * Netherfestung rund 32 000 – das Zehnfache reicht, ohne dev01 eine Datei zuzumuten, die das Lesen sprengt.
     */
    static final int MAX_KEPT = 600_000;
    /** Präfix der Dateinamen: Ohne ihn überdeckten „schiffswrack_N“ die gleichnamigen Vorlagen des TemplateCatalog. */
    static final String STEM_PREFIX = "sp_";
    /** Plätze je Art in der Vorlagenwelt (Zähler im PersistentDataContainer der Welt, nie wiederverwendet). */
    static final int SLOTS_PER_KIND = 63;
    /**
     * Flachwelt wie das echte Gelände: Grundgestein, 123 Lagen Stein, Erde, Gras – Oberfläche y=63.
     * Vorher die Standard-Flachwelt mit Gras auf y=-61 direkt über dem Grundgestein: Die Fallgrube des Wüstentempels
     * (rund 14 Blöcke unter dem Boden) lag unter der Welt und fehlte (21×15×21, Probe 2026-11-15). Ein schwebender
     * Boden über Luft war schlechter: Das Spiel füllt Fundamente bis zum nächsten festen Block auf, der Tempel wurde
     * 21×137×21 (gemessen). Unberührtes Gestein nimmt keepMask wieder aus der Vorlage.
     */
    static final String FLAT_SETTINGS = "{\"biome\":\"%s\",\"lakes\":false,\"features\":false,"
            + "\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:stone\","
            + "\"height\":123},{\"block\":\"minecraft:dirt\",\"height\":3},{\"block\":\"minecraft:grass_block\","
            + "\"height\":1}],\"structure_overrides\":[]}";
    /**
     * Arten, die im Spiel tief im Gestein liegen. Oben auf den Boden gesetzt (wie bis 2026-11-15) standen
     * Prüfungskammern und antike Stadt frei in der Luft – ohne die Gänge, die das Gestein im Original ausspart.
     */
    static final Set<String> UNDERGROUND = Set.of("ancient_city", "trial_chambers", "stronghold", "mineshaft",
            "amethyst_geode");
    /**
     * Untergrund, den ein Merkmal unter seinem Startpunkt verlangt. Auf Gras scheiterten Wüstenbrunnen (Sand) und
     * Eisstachel (Schneeblock) mit „Failed to place feature“ (gemessen 2026-11-15, je 2/2); der eine Block landet als
     * Teil des Gebildes in der Vorlage.
     */
    static final Map<String, Material> GROUND = Map.of("desert_well", Material.SAND, "ice_spike",
            Material.SNOW_BLOCK);
    /** Arten, die „place feature“ setzt; Registry.STRUCTURE kennt sie nicht. */
    static final Set<String> FEATURES = Set.of("desert_well", "amethyst_geode", "ice_spike", "fancy_oak",
            "mega_spruce", "mega_jungle_tree", "dark_oak", "cherry", "mangrove", "pale_oak", "azalea_tree",
            "huge_red_mushroom", "huge_brown_mushroom");
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
        // Neue Arten nur hinten anhängen: Der Platz in der Vorlagenwelt hängt an der Reihenfolge (slot in next).
        // Familiennamen mit zwei Wörtern, wo ein Wort allein schon etwas anderes heißt („Festung“ baut sonst das LLM
        // als Burg, „Mine“ wäre jeder Schacht) – der Katalog trifft nur den ganzen Namen.
        put("monument", "Ozeanmonument", "Unterwassertempel aus Prismarin");
        put("mansion", "Waldanwesen", "dreistöckige Villa der Illager");
        put("stronghold", "Endportal-Festung", "Gänge, Bibliothek und Endportal");
        put("mineshaft", "Verlassene Mine", "Stollen mit Schienen und Stützen");
        put("ocean_ruin_warm", "Warme Ozeanruine", "Sandsteinruine aus warmem Meer");
        put("shipwreck_beached", "Gestrandetes Schiffswrack", "Wrack am Strand");
        // Naturgebilde (FEATURES, 2026-11-15): kein Bauwerk mit Bauwerk-ID, sondern ein Merkmal der Weltgenerierung –
        // dasselbe Erfassen, nur „place feature“ statt „place structure“.
        put("desert_well", "Wüstenbrunnen", "Sandsteinbrunnen mit Wasser");
        put("amethyst_geode", "Amethystgeode", "Kugel aus Amethyst, Calcit und Basalt");
        put("ice_spike", "Eisstachel", "Turm aus Packeis");
        put("fancy_oak", "Große Eiche", "verzweigte Eiche");
        put("mega_spruce", "Riesenfichte", "Fichte mit 2×2-Stamm");
        put("mega_jungle_tree", "Riesentropenbaum", "Tropenbaum mit 2×2-Stamm und Ranken");
        put("dark_oak", "Schwarzeiche", "Schwarzeiche mit 2×2-Stamm");
        put("cherry", "Kirschbaum", "Kirschbaum mit rosa Blüten");
        put("mangrove", "Mangrove", "Mangrove mit Luftwurzeln");
        put("pale_oak", "Blasseiche", "Blasseiche mit Moosbart");
        put("azalea_tree", "Azaleenbaum", "Azalee mit Blüten");
        put("huge_red_mushroom", "Roter Riesenpilz", "roter Pilz in Baumgröße");
        put("huge_brown_mushroom", "Brauner Riesenpilz", "brauner Pilz in Baumgröße");
    }

    /**
     * Zweitnamen für den Katalog. Die offiziellen deutschen Namen weichen ab (de.minecraft.wiki/w/Bauwerk, gelesen
     * 2026-11-15): Sumpfhütte, Endsiedlung, Antike Stätte; „Prüfungskammern“ heißt offiziell so, gesagt wird oft die
     * Einzahl. Ohne Zweitnamen ging „bau mir eine Sumpfhütte“ ans LLM, obwohl drei echte im Katalog liegen. Die
     * eigenen Namen bleiben, damit Dateien und index.tsv auf dev01 gültig bleiben.
     */
    static final Map<String, List<String>> ALIASES = Map.of(
            "Hexenhütte", List.of("Sumpfhütte"),
            "Endstadt", List.of("Endsiedlung"),
            "Antike Stadt", List.of("Antike Stätte"),
            "Prüfungskammern", List.of("Prüfungskammer"),
            // Gebeugt („bau mir einen roten Riesenpilz“) – vorher ans LLM (Review 2026-11-15).
            "Roter Riesenpilz", List.of("Roten Riesenpilz"),
            "Brauner Riesenpilz", List.of("Braunen Riesenpilz"),
            "Gestrandetes Schiffswrack", List.of("Gestrandeten Schiffswrack"),
            "Große Eiche", List.of("Großen Eiche"));

    private static void put(String id, String family, String description) {
        KINDS.put(id, new String[] {family, description});
    }

    private final BaumeisterPlugin plugin;
    private boolean busy;

    GameStructures(BaumeisterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Dateiname ohne Endung: Präfix, family in ASCII (ä → ae …), klein, Unterstrich, plus Nummer. */
    static String fileStem(String family, int n) {
        String s = family.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue") // umlaut-ok
                .replace("ß", "ss").replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", ""); // umlaut-ok
        return STEM_PREFIX + s + "_" + n;
    }

    /**
     * Zeile für index.tsv (datei, name, anzeige, beschreibung, quelle, boden) – Tabs und Zeilenumbrüche entfernt.
     * boden = so viele Lagen liegen unter der Oberfläche; beim Bau beginnt die Vorlage so tief unter den Füßen
     * (FileSchematics.ground). Vorher stand Schicht 0 immer auf Fußhöhe: Der Wüstentempel schwebte 14 Blöcke über
     * seiner eigenen Schatzkammer.
     */
    static String indexLine(String file, String name, String family, int n, String description, int ground) {
        List<String> cols = new ArrayList<>();
        for (String c : List.of(file, name, family + ", Variante " + n, description, SOURCE,
                Integer.toString(ground))) {
            // Je Feld: Ein Tab in einem Feld verschöbe sonst alle Spalten dahinter (GameStructuresTest).
            cols.add(c.replaceAll("[\\t\\r\\n]+", " "));
        }
        return String.join("\t", cols) + "\n";
    }

    /**
     * Nächste Nummer einer Familie: höchste vorhandene + 1, gezählt über .nbt, .nbt.neu und die index.tsv. Vorher die
     * erste Lücke – nach dem Löschen einer Datei bekam das neue Exemplar ihre Nummer (und früher ihren Platz).
     */
    static int nextNumber(Path dir, String family) throws IOException {
        String base = fileStem(family, 0);
        String prefix = base.substring(0, base.length() - 1);
        int max = 0;
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    max = Math.max(max, numberOf(f.getFileName().toString(), prefix));
                }
            }
            Path idx = dir.resolve("index.tsv");
            if (Files.exists(idx)) {
                for (String line : Files.readAllLines(idx, StandardCharsets.UTF_8)) {
                    max = Math.max(max, numberOf(line.split("\t", 2)[0], prefix));
                }
            }
        }
        return max + 1;
    }

    private static int numberOf(String fileName, String prefix) {
        String f = fileName.endsWith(".neu") ? fileName.substring(0, fileName.length() - 4) : fileName;
        if (!f.startsWith(prefix) || !f.endsWith(".nbt")) {
            return 0;
        }
        String num = f.substring(prefix.length(), f.length() - 4);
        return num.matches("\\d{1,4}") ? Integer.parseInt(num) : 0;
    }

    /**
     * Wie viele Lagen unter der Oberfläche liegen. 0 für unterirdische Arten und für alles, was ganz unter den Füßen
     * endet: Das Spiel setzt manche Portalruinen selbst tief in den Fels (Versatz 82 und 93 bei 16–21 Blöcken Höhe,
     * gemessen 2026-11-15) und das Ozeanmonument mit der Oberkante an die Oberfläche – abgesenkt läge beides
     * vollständig vergraben. Solche stehen dann wie ein Monument auf dem Boden.
     */
    static int groundOffset(String id, int placedY, int minY, int maxY) {
        if (UNDERGROUND.contains(id) || maxY < placedY) {
            return 0;
        }
        return Math.max(0, placedY - minY);
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
        boolean feature = FEATURES.contains(id);
        Structure structure = feature ? null : Registry.STRUCTURE.get(NamespacedKey.minecraft(id));
        if (!feature && structure == null) {
            Texts.info(sender, "Dieses Bauwerk gibt es in dieser Minecraft-Fassung nicht: " + id);
            return;
        }
        World w = SEA.contains(id) ? world(SEA_WORLD, "minecraft:deep_ocean") : world(WORLD, "minecraft:plains");
        if (w == null) {
            Texts.info(sender, "Die Vorlagenwelt ließ sich nicht anlegen – siehe Serverlog.");
            return;
        }
        busy = true;
        Path dir = plugin.getDataFolder().toPath().resolve("schematics").resolve("spiel");
        cleanLeftovers(dir);
        next(sender, w, structure, id, kind, dir, count, 0, 0);
    }

    /** Reste eines abgebrochenen Laufs (.neu) löschen – sie stehen in keinem Katalog, aber im Weg. */
    private static void cleanLeftovers(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".neu")).toList()) {
                deleteQuietly(f);
                LOG.info(() -> "spielbauwerk | rest_gelöscht | datei=" + f.getFileName());
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "spielbauwerk | aufräumen | ordner=" + dir.getFileName(), e);
        }
    }

    /** Nächster freier Platz der Art in dieser Welt; -1, wenn alle SLOTS_PER_KIND vergeben sind. */
    private int takeSlot(World w, String id) {
        NamespacedKey key = new NamespacedKey(plugin, "platz_" + id);
        Integer used = w.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        int next = used == null ? 1 : used + 1;
        if (next > SLOTS_PER_KIND) {
            return -1;
        }
        w.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, next);
        return next;
    }

    private void next(CommandSender sender, World w, Structure structure, String id, String[] kind, Path dir,
            int count, int done, int saved) {
        if (done >= count) {
            busy = false;
            String summary = summary(kind[0], saved, count);
            LOG.info(() -> "spielbauwerk | fertig | art=" + id + " gespeichert=" + saved + " versucht=" + count);
            Texts.info(sender, summary);
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
        // Platz aus einem Zähler je Art und Welt, nie aus der Dateinummer: Vorher setzte ein neues Exemplar nach dem
        // Löschen einer Datei (oder ab Nr. 65 in der Nachbarart) auf ein altes – gespeichert wurde der Doppelbau.
        int slotNo = takeSlot(w, id);
        if (slotNo < 0) {
            busy = false;
            Texts.info(sender, "Alle " + SLOTS_PER_KIND + " Plätze für " + kind[0] + " in " + w.getName()
                    + " sind belegt – Welt löschen, dann geht es von vorn los.");
            return;
        }
        int slot = (List.copyOf(KINDS.keySet()).indexOf(id) + 1) * 64 + slotNo;
        int x = slot * SPACING;
        int z = 0;
        int cx = x >> 4;
        int refCx = (x + SPACING / 2) >> 4;
        long[] keys = new long[(2 * RADIUS_CHUNKS + 1) * (2 * RADIUS_CHUNKS + 1) + 1];
        int k = 0;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                keys[k++] = ChunkPrefetch.key(cx + dx, dz);
            }
        }
        // Bezugschunk mit vorladen: vorher erzeugte ihn der Snapshot-Tick synchron.
        keys[k] = ChunkPrefetch.key(refCx, 0);
        ChunkPrefetch.ensure(ChunkPrefetch.of(plugin, w), keys, 8, p -> {
            int y = UNDERGROUND.contains(id) ? UNDERGROUND_Y : w.getHighestBlockYAt(x, z) + 1;
            Material ground = GROUND.get(id);
            if (ground != null) {
                w.getBlockAt(x, y - 1, z).setType(ground, false);
            }
            boolean placed = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), String.format(Locale.ROOT,
                    "execute in %s run place %s minecraft:%s %d %d %d", w.getKey(),
                    FEATURES.contains(id) ? "feature" : "structure", id, x, y, z));
            // Nach 5 Ticks: Jigsaw-Teile (Dorf, Bastion) setzt der Befehl sofort, Nachbar-Updates sind dann durch.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                List<ChunkSnapshot> snaps = new ArrayList<>();
                for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                    for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                        snaps.add(w.getChunkAt(cx + dx, dz).getChunkSnapshot(false, false, false));
                    }
                }
                // Bezug: die unberührte flache Welt zwischen zwei Plätzen (dort wird nie etwas erzeugt).
                ChunkSnapshot ref = w.getChunkAt(refCx, 0).getChunkSnapshot(false, false, false);
                int minY = w.getMinHeight();
                Material[] reference = new Material[w.getMaxHeight() - minY];
                for (int i = 0; i < reference.length; i++) {
                    reference[i] = ref.getBlockType(0, minY + i, 0);
                }
                @SuppressWarnings("deprecation")
                int dataVersion = Bukkit.getUnsafe().getDataVersion();
                if (p != null) {
                    p.release();
                }
                String stem = fileStem(kind[0], n);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String text;
                    boolean ok = false;
                    try {
                        int[] b = changedBounds(snaps, reference, minY);
                        if (b == null) {
                            text = placed ? "Das Spiel hat " + kind[0] + " nicht gesetzt (kein Block weicht von der "
                                    + "flachen Welt ab)." : "place " + id + " ist gescheitert – siehe Serverlog.";
                        } else {
                            Written wr = writeTemplate(dir, stem, snaps, reference, minY, b, dataVersion);
                            int depth = groundOffset(id, y, b[1], b[4]);
                            Files.writeString(dir.resolve("index.tsv"),
                                    indexLine(stem + ".nbt", stem, kind[0], n, kind[1], depth),
                                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            Files.move(wr.file(), dir.resolve(stem + ".nbt"), StandardCopyOption.REPLACE_EXISTING,
                                    StandardCopyOption.ATOMIC_MOVE);
                            boolean cut = touchesEdge(b, cx);
                            LOG.info(() -> String.format(Locale.ROOT, "spielbauwerk | gespeichert | art=%s datei=%s "
                                    + "größe=%dx%dx%d blöcke=%d boden=%d abgeschnitten=%s", id, stem, wr.sx(), wr.sy(),
                                    wr.sz(), wr.blocks(), depth, cut));
                            text = kind[0] + " " + n + " gespeichert (" + wr.sx() + "×" + wr.sy() + "×" + wr.sz()
                                    + ", " + wr.blocks() + " Blöcke)." + (cut ? " Achtung: reicht bis an den Rand des "
                                    + "Suchbereichs, ein Stück fehlt vielleicht." : "");
                            ok = true;
                        }
                    } catch (Throwable e) {
                        // Werkzeug-Grenze (auch OutOfMemoryError): eine missglückte Vorlage beendet die Reihe, nicht
                        // den Server – und busy darf nie hängen bleiben (vorher: bis zum Neustart „schon dabei“).
                        LOG.log(Level.WARNING, "spielbauwerk | fehlgeschlagen | art=" + id, e);
                        deleteQuietly(dir.resolve(stem + ".nbt.neu"));
                        text = "Das ging nicht: " + e.getMessage();
                    }
                    String msg = text;
                    int nowSaved = saved + (ok ? 1 : 0);
                    if (!plugin.isEnabled()) {
                        LOG.info(() -> "spielbauwerk | abgebrochen | art=" + id + " nummer=" + n + " " + msg);
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin,
                            () -> finish(sender, w, structure, id, kind, dir, count, done, nowSaved, msg));
                });
            }, 5L);
        });
    }

    /** Schlusszeile: zählt gespeicherte Vorlagen, nicht Versuche (vorher „Fertig: 3 ד nach drei Fehlschlägen). */
    static String summary(String family, int saved, int tried) {
        if (saved == 0) {
            return "Keine " + family + "-Vorlage gespeichert (" + tried + " versucht).";
        }
        return "Fertig: " + saved + " von " + tried + " × " + family + ". Im Katalog: /bm katalog spiel";
    }

    private void finish(CommandSender sender, World w, Structure structure, String id, String[] kind, Path dir,
            int count, int done, int saved, String text) {
        // Vorher ging das Ergebnis nur an den Absender – beim RCON-Aufruf war es nach dem Trennen weg, und im Log
        // stand nichts (Probe 2026-11-15, 7 Arten ohne jede Zeile). Jetzt immer ins Log.
        LOG.info(() -> "spielbauwerk | ergebnis | art=" + id + " " + text);
        Texts.info(sender, text);
        next(sender, w, structure, id, kind, dir, count, done + 1, saved);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "spielbauwerk | aufräumen | datei=" + file.getFileName(), e);
        }
    }

    /** Reicht der Umriss bis an den Rand der vorgeladenen Chunks? Dann fehlt vermutlich ein Stück. */
    static boolean touchesEdge(int[] b, int cx) {
        int lo = (cx - RADIUS_CHUNKS) << 4;
        int hi = ((cx + RADIUS_CHUNKS) << 4) + 15;
        int r = RADIUS_CHUNKS << 4;
        return b[0] <= lo || b[3] >= hi || b[2] <= -r || b[5] >= r + 15;
    }

    /** Was writeTemplate geschrieben hat: die .neu-Datei, Maße, Zahl der Blockeinträge. */
    record Written(Path file, int sx, int sy, int sz, int blocks) {
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

    /** Ohne Material#isAir: das fragt die Registry, die es nur im laufenden Server gibt (Unit-Test). */
    static boolean isAir(Material m) {
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR;
    }

    /** Index einer Zelle im Quader, wie keepMask und writeTemplate ihn teilen. */
    static int cell(int x, int y, int z, int sx, int sz) {
        return (y * sz + z) * sx + x;
    }

    /**
     * Welche Zellen des Quaders in die Vorlage gehören: genau die, die das Bauwerk verändert hat – auch
     * ausgegrabene Luft unter der Oberfläche (Fallgrube, Keller, Gänge). Unberührtes bleibt draußen: Gestein, Erde,
     * Gras und die Luft über dem Boden. Vorher behielt die Maske jede Luftzelle (85 % der Datei, Netherfestung
     * 735 000 von 767 000); beim Bau verwarf StructureFile sie ohnehin. Läuft außerhalb des Hauptthreads.
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
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        if (c.getBlockType(x - ox, y, z - oz) != ref) {
                            keep.set(cell(x - b[0], y - b[1], z - b[2], sx, sz));
                        }
                    }
                }
            }
        }
        return keep;
    }

    /**
     * Schreibt die Vorlage direkt aus den Snapshots als Strukturdatei des Spiels nach stem.nbt.neu (NbtOut): size,
     * palette (Name + Properties aus BlockData#getAsString), blocks (nur keepMask), keine entities, DataVersion des
     * Servers. Blockdaten (Kisteninhalte, Beutetabellen, Spawner) fehlen bewusst: StructureFile liest sie nicht, und
     * Beutetabellen gehören nicht in Vorlagen für Spieler. Läuft außerhalb des Hauptthreads.
     */
    static Written writeTemplate(Path dir, String stem, List<ChunkSnapshot> snaps, Material[] reference, int minY,
            int[] b, int dataVersion) throws IOException {
        int sx = b[3] - b[0] + 1;
        int sy = b[4] - b[1] + 1;
        int sz = b[5] - b[2] + 1;
        long volume = (long) sx * sy * sz;
        if (volume > MAX_VOLUME) {
            throw new IOException("Quader zu groß (" + sx + "×" + sy + "×" + sz + " = " + volume + ", höchstens "
                    + MAX_VOLUME + ")");
        }
        BitSet keep = keepMask(snaps, reference, minY, b);
        if (keep.cardinality() > MAX_KEPT) {
            throw new IOException("zu viele Blöcke für eine Vorlage (" + keep.cardinality() + ", höchstens " + MAX_KEPT
                    + ")");
        }
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<Object> palette = new ArrayList<>();
        List<Object> blocks = new ArrayList<>(keep.cardinality());
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
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        int rx = x - b[0];
                        int ry = y - b[1];
                        int rz = z - b[2];
                        if (!keep.get(cell(rx, ry, rz, sx, sz))) {
                            continue;
                        }
                        String state = c.getBlockData(x - ox, y, z - oz).getAsString();
                        Integer idx = paletteIndex.get(state);
                        if (idx == null) {
                            idx = palette.size();
                            paletteIndex.put(state, idx);
                            palette.add(paletteEntry(state));
                        }
                        Map<String, Object> blk = new LinkedHashMap<>();
                        blk.put("pos", List.of(rx, ry, rz));
                        blk.put("state", idx);
                        blocks.add(blk);
                    }
                }
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", dataVersion);
        root.put("size", List.of(sx, sy, sz));
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", List.of());
        Path tmp = dir.resolve(stem + ".nbt.neu");
        try (OutputStream o = Files.newOutputStream(tmp)) {
            NbtOut.writeGzip(root, o);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        }
        return new Written(tmp, sx, sy, sz, blocks.size());
    }

    /** „minecraft:oak_stairs[facing=north,half=bottom]“ → {Name, Properties} wie in Strukturdateien des Spiels. */
    static Map<String, Object> paletteEntry(String state) {
        Map<String, Object> e = new LinkedHashMap<>();
        int open = state.indexOf('[');
        e.put("Name", open < 0 ? state : state.substring(0, open));
        if (open >= 0 && state.endsWith("]") && open + 1 < state.length() - 1) {
            Map<String, Object> props = new LinkedHashMap<>();
            for (String kv : state.substring(open + 1, state.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    props.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
            if (!props.isEmpty()) {
                e.put("Properties", props);
            }
        }
        return e;
    }

    /** Flache Vorlagenwelt ohne eigene Bauwerke und ohne Monster; null, wenn sie sich nicht anlegen lässt. */
    private World world(String name, String biome) {
        World w = Bukkit.getWorld(name);
        if (w != null) {
            return w;
        }
        w = new WorldCreator(name).type(WorldType.FLAT).generatorSettings(FLAT_SETTINGS.formatted(biome))
                .generateStructures(false).createWorld();
        if (w != null) {
            w.setSpawnFlags(false, false);
            LOG.info(() -> "spielbauwerk | welt_angelegt | welt=" + name + " biom=" + biome);
        }
        return w;
    }

    /**
     * Ein Katalogtreffer auf ein Bauwerk des Spiels geht dem TemplateCatalog vor. Vorher fing der 9 der 19 Familien ab
     * (gemessen 2026-11-15): „bau mir ein Wiesendorf“ gab das zusammengesetzte Straßendorf, „einen Wüstentempel“ das
     * Tempelhaus aus dem Wüstendorf statt der Pyramide, „die antike Stadt“ ein Einzelstück; selbst „… 2“ ging dorthin,
     * nur „… Variante 2“ kam durch. Aufträge mit Zusätzen („ein Wüstendorf mit 8 Häusern“) trifft der Katalog nicht –
     * die bleiben beim TemplateCatalog. Wer das zurückdreht, macht die Spiel-Vorlagen für die meisten Sätze unsichtbar.
     */
    static boolean preferred(SchematicCatalog.Match m) {
        return SOURCE.equals(m.entry().source());
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
