package com.blackn0va.baumeister;

import com.blackn0va.baumeister.minimap.MinimapImage;
import com.blackn0va.baumeister.minimap.OverviewStore;
import com.blackn0va.baumeister.minimap.RegionScanner;
import com.blackn0va.baumeister.minimap.TileCache;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.scheduler.BukkitTask;

/**
 * Minikarte für Bauwelten (/bm karte, Nutzerwunsch 2026-10-31 nach dem Entfernen von Cartographer2): eine Karte in der
 * Hand, die mitwandert – Norden oben, der Spieler in der Mitte, Plotgrenze als roter Rahmen, andere Spieler, die
 * laufende Baustelle (rotes X) und der Baumeister samt Helfern (grüne Pfeile).
 *
 * Last – der Grund, warum es das hier gibt statt Cartographer2: Cartographer hielt auf dev01 den Server-Thread ohne
 * Spieler bei 100 % (jeden Tick Bukkit.getTPS, unter Paper teuer, gemessen auf dev01). Deshalb hier:
 * - nie getTPS, nie ein Chunk laden – gelesen wird nur, was ohnehin geladen ist (isChunkLoaded vorher);
 * - Chunks gehen in einen Zwischenspeicher (TileCache) und werden nur gelesen, wenn sie fehlen, geändert oder älter als
 *   karte.auffrischen-sekunden sind – mit festem Zeitbudget je Tick (karte.ms-pro-tick);
 * - ohne Karte in der Hand passiert gar nichts: Bukkit ruft render() nur für Spieler, die die Karte sehen.
 * Wer das Lesen in render() verlegt (ohne Budget), macht genau das, was Cartographer kaputt gemacht hat: Arbeit, die mit
 * der Zahl der Spieler und Chunks wächst, auf dem Server-Thread ohne Deckel.
 *
 * Weites Herauszoomen (bis 64 Blöcke je Punkt) zeigt die Übersicht (OverviewStore): 4 × 4 Stichproben je Chunk, beim
 * Laden und Entladen jedes Chunks einer Kartenwelt mitgeschrieben (je Chunk ~16 Spalten, Mikrosekunden) und je Region
 * unter minikarte/<welt>/ gespeichert. Gelesen wird auch dann nur in Sichtweite – bei 64 Blöcken je Punkt wären es
 * sonst 260 000 Chunks.
 *
 * Threads: Weltzugriffe und Daten auf dem Server-Thread; Plattenzugriffe der Übersicht nacheinander auf EINEM eigenen
 * Thread (io) – so überholt nie eine ältere Fassung einer Region eine neuere. Fehler: Lesefehler einer Kachel werden
 * geloggt und übersprungen (fail-open: eine Karte ist Anzeige, kein Schutz); kaputte karten.yml → neue Karten-IDs;
 * kaputte Übersichtsdatei → Region gilt als leer und wird beim nächsten Speichern überschrieben.
 */
final class Minimap implements Listener {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final String FILE = "karten.yml";
    /** Vanilla: Normale Helligkeit 220/255 – die Grundfarbe einer Kachel liegt bei Helligkeit 1 („normal“). */
    private static final int NORMAL_BRIGHTNESS = 220;
    /** Wie viele Blöcke unter der Oberfläche höchstens gesucht wird, bis ein Block eine Kartenfarbe hat (Glas, Luft). */
    private static final int SEARCH_DOWN = 8;
    /** Volle Kacheln nur in diesem Umkreis um den Spieler anfordern (Chunks) – mehr ist ohnehin nicht geladen. */
    private static final int NEAR_PADDING = 1;
    /** Höchstens so viele Übersichtsregionen gleichzeitig von der Platte holen. */
    private static final int MAX_LOADING = 16;
    /** Wartende Übersichts-Lesungen (ChunkLoadEvent) höchstens – ein Stadtbau lädt Tausende Chunks. */
    private static final int MAX_OVERVIEW_QUEUE = 50_000;
    /**
     * Straßenbeläge des Stadt- und Weltbaus (osm/CityPlanner: Fahrbahn gray_concrete, Mittelstreifen white_concrete,
     * Bordstein smooth_stone, Fußgängerzone polished_andesite). Bewusst ohne andesite: Das liegt in Bergen natürlich
     * an der Oberfläche und würde dort weiß.
     */
    private static final Set<Material> ROAD_BLOCKS = EnumSet.of(Material.GRAY_CONCRETE, Material.BLACK_CONCRETE,
            Material.WHITE_CONCRETE, Material.SMOOTH_STONE, Material.POLISHED_ANDESITE);
    /** Straße nur, wenn höchstens so viel über dem tiefsten Punkt ihres 4 × 4-Felds – sonst ist es ein Dach. */
    private static final int ROAD_ABOVE_GROUND = 2;
    /** Bei Drehung höchstens alle n Ticks neu zeichnen – und nur, wenn sich der Blick um mindestens ROTATE_DEG dreht. */
    private static final int ROTATE_TICKS = 3;
    private static final float ROTATE_DEG = 4f;
    static final int MAX_WAYPOINTS = 50;
    private static final java.util.regex.Pattern WAYPOINT_NAME = java.util.regex.Pattern.compile(
            "[\\p{L}\\p{N} _-]{1,32}");

    /** Eigener Wegpunkt eines Spielers. */
    record Waypoint(String world, double x, double z) {
    }

    private final BaumeisterPlugin plugin;
    private final MinimapSettings settings;
    private final TileCache cache;
    private final PlotSquaredPlots plots = new PlotSquaredPlots();
    private final Renderer renderer = new Renderer();
    /** Karten-ID je Spieler (karten.yml), damit /bm karte dieselbe Karte wiedergibt statt neue Kartendateien anzulegen. */
    private final Map<UUID, Integer> mapIds = new HashMap<>();
    /** Zuletzt gelesener Regionsordner je Welt (nur Server-Thread) – für die Logzeile bei Änderung. */
    private final Map<String, Path> scannedDirs = new HashMap<>();
    /** Blöcke je Kartenpunkt je Spieler (Kommazahl, MinimapSettings.MIN_ZOOM..MAX_ZOOM). */
    private final Map<UUID, Double> zoom = new HashMap<>();
    /** Letzter Zoom-Klick je Spieler (Tick) – ein Linksklick auf einen Block meldet Bukkit mehrfach. */
    private final Map<UUID, Long> lastZoomClick = new HashMap<>();
    /** Karte dreht sich mit dem Blick (Vorgabe karte.drehen); je Spieler umschaltbar mit /bm karte drehen. */
    private final Map<UUID, Boolean> rotate = new HashMap<>();
    private final Map<UUID, Float> lastYaw = new HashMap<>();
    /** Wegpunkte je Spieler (Name → Ort), in karten.yml. */
    private final Map<UUID, LinkedHashMap<String, Waypoint>> waypoints = new HashMap<>();
    /** Aktives Ziel je Spieler: Name eines Wegpunkts oder Reisepunkts. */
    private final Map<UUID, String> target = new HashMap<>();
    /** Gewünschte Chunks je Welt in Reihenfolge der Anfrage – fehlende zuerst, veraltete hinten. */
    private final Map<String, LinkedHashSet<Long>> wanted = new HashMap<>();
    /** Palettenbyte-Grundfarbe je Blockfarbe (RGB) – matchColor sucht linear, also nur einmal je Farbe. */
    private final Map<Integer, Integer> baseOfRgb = new HashMap<>();
    private final Map<UUID, Long> lastFrame = new HashMap<>();
    private final byte plotColor;
    private final byte unknownDark;
    private final byte unknownLight;
    private final OverviewStore overview;
    /** Chunks, deren Übersicht nach dem Laden gelesen werden soll (Server-Thread, mit Budget). */
    private final Map<String, LinkedHashSet<Long>> overviewWanted = new HashMap<>();
    /** Regionen, die gerade von der Platte kommen, und solche, die es dort nicht gibt („welt|schlüssel“). */
    private final Set<String> loadingRegions = new HashSet<>();
    private final Set<String> absentRegions = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Baumeister-Minikarte-io");
        t.setDaemon(true);
        return t;
    });
    /**
     * Vorab-Scan der Regionsdateien (RegionScanner) – eigener Thread mit niedriger Priorität, damit der io-Thread für
     * Kartenregionen frei bleibt; ein Erst-Scan von ~1 GB Regionen dauert Minuten.
     */
    private final ExecutorService scanner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Baumeister-Minikarte-scan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile String scanState = "noch nicht";
    /** Farbtabelle für den Scan – einmal gebaut (~1 100 createBlockData auf dem Server-Thread), dann wiederverwendet. */
    private Map<String, Integer> scanColors;
    private BukkitTask rescanTask;
    private BukkitTask task;
    private BukkitTask saveTask;
    private long tick;
    // Zahlen für /bm karte status
    private long tilesRead;
    private long readNanos;
    private long frames;
    private long composeNanos;
    private long overviewReads;

    Minimap(BaumeisterPlugin plugin, MinimapSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.cache = new TileCache(settings.maxTiles());
        this.plotColor = palette(255, 64, 64);
        this.unknownDark = palette(46, 46, 52);
        this.unknownLight = palette(64, 64, 72);
        this.overview = new OverviewStore(settings.maxRegions(), this::saveLater);
    }

    /**
     * Takte nach Wanduhr, nicht nach Ticks. Vorfall dev01 2026-11-04 20:22: Nach „/tick rate 2000“ lief der
     * 10-Minuten-Scan (12 000 Ticks) alle 6 s und las in der Plotwelt immer wieder dieselben Regionsdateien – 70 Zeilen
     * „vorab_gescannt“ in 9 Minuten, dazu ständige Plattenlast. Der Timer weckt weiter je Tick-Takt, gearbeitet wird erst
     * nach der Wanduhr-Frist (etwas kürzer als der Takt, damit bei 20 TPS kein Durchgang ausfällt).
     */
    static final long RESCAN_NANOS = 9L * 60 * 1_000_000_000L;
    static final long SAVE_NANOS = 50L * 1_000_000_000L;
    private long lastScan = Long.MIN_VALUE / 2;
    private long lastSave = Long.MIN_VALUE / 2;

    static boolean wallClockDue(long last, long period) {
        return System.nanoTime() - last >= period;
    }

    void start() {
        load();
        if (!settings.enabled()) {
            LOG.info("karte | aus | karte.an=false");
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWork, 1L, 1L);
        // Geänderte Übersicht einmal je Minute auf die Platte (Kopien; geschrieben wird auf dem io-Thread).
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (wallClockDue(lastSave, SAVE_NANOS)) {
                lastSave = System.nanoTime();
                overview.takeDirty().forEach(this::saveLater);
            }
        }, 1200L, 1200L);
        if (settings.overview()) {
            // Erst nach 10 s (Multiverse lädt Welten nach dem Plugin), dann alle 10 min nur geänderte Regionsdateien.
            rescanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::startScan, 200L, 12_000L);
        }
        // Schon geladene Karten (z. B. nach /reload) bekommen den Renderer; neu geladene über MapInitializeEvent.
        for (int id : mapIds.values()) {
            MapView v = Bukkit.getMap(id);
            if (v != null) {
                attach(v);
            }
        }
        LOG.info(() -> "karte | bereit | welten=" + (settings.worlds().isEmpty() ? "alle_oberwelten"
                : String.join(",", settings.worlds())) + " karten=" + mapIds.size());
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (rescanTask != null) {
            rescanTask.cancel();
            rescanTask = null;
        }
        scanner.shutdownNow();  // ein halber Scan ist kein Verlust: die Merkliste zählt nur fertige Dateien
        // Alles Geänderte noch speichern: erst die Warteschlange des io-Threads leeren, dann den Rest direkt schreiben.
        List<OverviewStore.Saved> rest = overview.takeDirty();
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warning("karte | übersicht_speichern_hängt | io-Thread nach 10 s nicht fertig");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        rest.forEach(this::writeRegion);
    }

    // ------------------------------------------------------------------ Befehle

    /** /bm karte [zoom <n> | drehen [an|aus] | punkt <Name> [x z] | punkte | weg <Name> | ziel <Name|aus> | status] */
    void command(CommandSender sender, String[] args) {
        if (!settings.enabled()) {
            Texts.info(sender, "Die Minikarte ist abgeschaltet (karte.an in der config.yml).");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            status(sender);
            return;
        }
        if (!(sender instanceof Player p)) {
            Texts.info(sender, "Nur im Spiel. Konsole: /bm kartenbild <welt> <x> <z> [zoom]");
            return;
        }
        if (args.length >= 2 && waypointCommand(p, args)) {
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("drehen")) {
            boolean on = args.length >= 3 ? args[2].equalsIgnoreCase("an") : !rotates(p);
            rotate.put(p.getUniqueId(), on);
            lastFrame.remove(p.getUniqueId());
            save();
            Texts.info(p, on ? "Karte dreht sich mit deinem Blick – oben ist, wohin du schaust."
                    : "Karte steht still – Norden oben.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("zoom")) {
            double z = args.length >= 3 ? parseZoom(args[2]) : -1;
            if (z < 0) {
                Texts.info(p, "Zoom: 0,25 bis 4 Blöcke je Kartenpunkt – z. B. /bm karte zoom 1,5. Oder mit der Karte "
                        + "in der Hand: Linksklick näher, Rechtsklick weiter.");
                return;
            }
            setZoom(p, z);
            Texts.info(p, zoomText(z));
            return;
        }
        if (!settings.allows(p.getWorld())) {
            Texts.info(p, "In dieser Welt gibt es keine Minikarte (karte.welten).");
            return;
        }
        MapView view = viewFor(p);
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta == null) {
            Texts.info(p, "Die Karte ließ sich nicht anlegen.");
            return;
        }
        meta.setMapView(view);
        meta.setDisplayName("Baumeister-Karte");
        item.setItemMeta(meta);
        for (ItemStack rest : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItem(p.getLocation(), rest);
        }
        Texts.info(p, "Hier ist deine Minikarte – in die Hand nehmen. Linksklick zoomt hinein, Rechtsklick hinaus, mit "
                + "Schleichen doppelt so schnell (solange du sie hältst, setzt und brichst du keine Blöcke).");
        Texts.info(p, "Wegpunkte: /bm karte punkt <Name> · punkte · ziel <Name> · weg <Name> · drehen an|aus");
        LOG.info(() -> "karte | ausgegeben | spieler=" + p.getName() + " id=" + view.getId());
    }

    /**
     * /bm kartenbild <welt> <x> <z> [zoom] – das Kartenbild als PNG nach plugins/Baumeister/minikarte/, für die Prüfung
     * ohne Spieler (Testserver per RCON). Liest nur geladene Chunks, wie die Karte im Spiel; die Zeile im Log nennt,
     * wie lange Lesen und Zusammensetzen gedauert haben.
     */
    void imageCommand(CommandSender sender, String[] args) {
        if (args.length < 4) {
            Texts.info(sender, "/bm kartenbild <welt> <x> <z> [zoom 0,0625–64] [blick in Grad, 180 = Norden oben]");
            return;
        }
        World w = Bukkit.getWorld(args[1]);
        if (w == null) {
            Texts.info(sender, "Welt " + args[1] + " gibt es nicht.");
            return;
        }
        int x;
        int z;
        try {
            x = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            Texts.info(sender, "x und z müssen ganze Zahlen sein.");
            return;
        }
        double zm = args.length >= 5 ? parseZoom(args[4]) : 1.0;
        if (zm < 0) {
            Texts.info(sender, "Zoom: 0,0625 bis 64.");
            return;
        }
        float yaw = 180f;
        if (args.length >= 6) {
            try {
                yaw = Float.parseFloat(args[5].replace(',', '.'));
            } catch (NumberFormatException e) {
                Texts.info(sender, "Blick in Grad, z. B. 180 (Norden oben) oder 90 (Westen oben).");
                return;
            }
        }
        MinimapImage.View view = MinimapImage.View.headingUp(x, z, zm, yaw);
        long t0 = System.nanoTime();
        // Wie im Spiel nur in der Nähe lesen; weiter draußen zeigt die Übersicht (von der Platte, hier direkt).
        int reach = view.reach();
        Set<Long> need = visibleChunks(x, z, reach, 32);
        String wn0 = w.getName();
        for (long rk : visibleRegions(view)) {
            if (!overview.hasRegion(wn0, rk)) {
                int[] d = readRegion(wn0, rk);
                if (d != null) {
                    overview.putRegion(wn0, rk, d);
                }
            }
        }
        int read = 0;
        int unloaded = 0;
        for (long key : need) {
            int cx = MinimapImage.chunkKeyX(key);
            int cz = MinimapImage.chunkKeyZ(key);
            if (w.isChunkLoaded(cx, cz)) {
                readTile(w, cx, cz);
                read++;
            } else {
                unloaded++;
            }
        }
        long t1 = System.nanoTime();
        String wn = w.getName();
        byte[] img = MinimapImage.compose((cx, cz) -> cache.get(wn, cx, cz), (bx, bz) -> overview.sample(wn, bx, bz),
                view, MinimapImage.NONE, new HashSet<>());
        MinimapImage.fillUnknown(img, MinimapImage.NONE, unknownDark, unknownLight);
        long t2 = System.nanoTime();
        // Dateiname nur aus Zahlen und dem Weltnamen ohne Sonderzeichen – kein Pfad aus der Eingabe (ASVS V12.3.1).
        String name = "bild-" + wn.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + x + "_" + z + "_z"
                + String.format(Locale.ROOT, "%.2f", zm).replace('.', '_') + "_b" + Math.round(yaw) + ".png";
        Path out = plugin.getDataFolder().toPath().resolve("minikarte").resolve(name);
        try {
            Files.createDirectories(out.getParent());
            ImageIO.write(toImage(img), "png", out.toFile());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "karte | bild_nicht_geschrieben | datei=" + out, e);
            Texts.info(sender, "Bild nicht geschrieben: " + e.getMessage());
            return;
        }
        int r = read;
        int u = unloaded;
        LOG.info(() -> String.format(Locale.ROOT, "karte | bild | welt=%s mitte=%d,%d zoom=%.3f chunks=%d "
                + "nicht_geladen=%d lesen_ms=%.1f je_chunk_us=%.0f zusammensetzen_ms=%.2f datei=%s", wn, x, z, zm, r, u,
                (t1 - t0) / 1e6, r == 0 ? 0.0 : (t1 - t0) / 1e3 / r, (t2 - t1) / 1e6, name));
        Texts.info(sender, "Kartenbild: " + out + " (" + r + " Chunks gelesen, " + u + " nicht geladen)");
    }

    private void status(CommandSender s) {
        Texts.info(s, String.format(Locale.ROOT, "Karte: %d Kacheln im Speicher, %d gelesen (%.0f µs je Kachel), "
                + "%d Bilder (%.2f ms je Bild), wartend %d; Übersicht: %d Regionen, %d Chunks gelesen, wartend %d; "
                + "Vorab-Scan: " + scanState,
                cache.size(), tilesRead, tilesRead == 0 ? 0.0 : readNanos / 1e3 / tilesRead, frames,
                frames == 0 ? 0.0 : composeNanos / 1e6 / frames, wanted.values().stream().mapToInt(Set::size).sum(),
                overview.regions(), overviewReads, overviewWanted.values().stream().mapToInt(Set::size).sum()));
    }

    /** punkt, punkte, weg/löschen, ziel – true, wenn der Unterbefehl dazugehörte. */
    private boolean waypointCommand(Player p, String[] args) {
        String verb = args[1].toLowerCase(Locale.ROOT);
        UUID id = p.getUniqueId();
        LinkedHashMap<String, Waypoint> mine = waypoints.computeIfAbsent(id, k -> new LinkedHashMap<>());
        switch (verb) {
            case "punkt", "markieren", "wegpunkt" -> {
                if (args.length < 3) {
                    Texts.info(p, "/bm karte punkt <Name> [x z] – Wegpunkt hier oder an x/z setzen");
                    return true;
                }
                boolean coords = args.length >= 5 && isNumber(args[args.length - 2]) && isNumber(args[args.length - 1]);
                String name = String.join(" ", java.util.Arrays.copyOfRange(args, 2, coords ? args.length - 2
                        : args.length)).trim();
                if (!WAYPOINT_NAME.matcher(name).matches()) {
                    Texts.info(p, "Name: 1–32 Buchstaben, Ziffern, Leerzeichen, - oder _.");
                    return true;
                }
                if (!mine.containsKey(key(name)) && mine.size() >= MAX_WAYPOINTS) {
                    Texts.info(p, "Du hast schon " + MAX_WAYPOINTS + " Wegpunkte – lösche erst einen (/bm karte weg <Name>).");
                    return true;
                }
                double x = coords ? Double.parseDouble(args[args.length - 2]) + 0.5 : p.getLocation().getX();
                double z = coords ? Double.parseDouble(args[args.length - 1]) + 0.5 : p.getLocation().getZ();
                mine.remove(key(name));
                mine.put(key(name), new Waypoint(p.getWorld().getName(), x, z));
                names.put(key(name), name);
                save();
                Texts.info(p, String.format(Locale.GERMANY, "Wegpunkt „%s“ bei %d / %d gesetzt. Ziel setzen: "
                        + "/bm karte ziel %s", name, (int) Math.floor(x), (int) Math.floor(z), name));
            }
            case "punkte", "wegpunkte" -> {
                if (mine.isEmpty()) {
                    Texts.info(p, "Noch keine Wegpunkte. /bm karte punkt <Name> setzt einen, wo du stehst.");
                    return true;
                }
                Location here = p.getLocation();
                mine.forEach((k, w) -> Texts.info(p, String.format(Locale.GERMANY, "%s – %s %d / %d%s%s",
                        names.getOrDefault(k, k), w.world(), (int) Math.floor(w.x()), (int) Math.floor(w.z()),
                        w.world().equals(here.getWorld().getName())
                                ? ", " + distanceText(Math.hypot(w.x() - here.getX(), w.z() - here.getZ())) : "",
                        k.equals(target.get(id)) ? " (Ziel)" : "")));
            }
            case "weg", "löschen", "loeschen" -> { // umlaut-ok: Eingabe ohne Umlaut-Tastatur
                String name = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                        : "";
                if (mine.remove(key(name)) == null) {
                    Texts.info(p, "Keinen Wegpunkt „" + name + "“ gefunden. /bm karte punkte zeigt deine.");
                    return true;
                }
                if (key(name).equals(target.get(id))) {
                    target.remove(id);
                }
                save();
                Texts.info(p, "Wegpunkt „" + name + "“ gelöscht.");
            }
            case "ziel" -> {
                String name = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                        : "";
                if (name.isEmpty() || name.equalsIgnoreCase("aus")) {
                    target.remove(id);
                    save();
                    Texts.info(p, "Kein Ziel mehr auf der Karte.");
                    return true;
                }
                if (!mine.containsKey(key(name)) && placeNamed(name) == null) {
                    Texts.info(p, "Kein Wegpunkt und kein Reisepunkt „" + name + "“. /bm karte punkte · /bm orte");
                    return true;
                }
                target.put(id, key(name));
                save();
                Texts.info(p, "Ziel „" + name + "“ – die Karte zeigt es mit Entfernung, am Rand, wenn es weiter weg ist.");
            }
            default -> {
                return false;
            }
        }
        lastFrame.remove(id);
        return true;
    }

    /** Anzeigename je Schlüssel (Schlüssel = Name klein geschrieben). */
    private final Map<String, String> names = new HashMap<>();

    static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isNumber(String s) {
        return s.matches("-?\\d{1,8}");
    }

    static String distanceText(double blocks) {
        return blocks < 1000 ? Math.round(blocks) + " m" : String.format(Locale.GERMANY, "%.1f km", blocks / 1000);
    }

    private PlaceRegistry.Place placeNamed(String name) {
        return plugin.places() == null ? null : plugin.places().find(name);
    }

    private boolean rotates(Player p) {
        return rotate.getOrDefault(p.getUniqueId(), settings.rotate());
    }

    /** Zoom aus der Eingabe, Komma oder Punkt; -1 bei Unsinn oder außerhalb 1/16–64. */
    static double parseZoom(String s) {
        try {
            double z = Double.parseDouble(s.trim().replace(',', '.'));
            return Double.isFinite(z) && z >= MinimapSettings.MIN_ZOOM && z <= MinimapSettings.MAX_ZOOM ? z : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void setZoom(Player p, double z) {
        zoom.put(p.getUniqueId(), MinimapSettings.clampZoom(z));
        lastFrame.remove(p.getUniqueId());  // sofort neu zeichnen, nicht erst nach bild-ticks
        save();
    }

    private static String zoomText(double z) {
        int seen = (int) Math.round(MinimapImage.SIZE * z);
        return String.format(Locale.GERMANY, "Karte: %.2f Blöcke je Punkt – %d × %d Blöcke", z, seen, seen);
    }

    /**
     * Mit der Baumeister-Karte in der Hand: Linksklick zoomt hinein, Rechtsklick hinaus (Nutzerwunsch 2026-10-31).
     * Der Klick wird abgefangen – sonst bräche ein Linksklick im Kreativmodus den Block vor dir sofort ab, und ein
     * Rechtsklick öffnete Truhen oder Türen. Wer mit der Karte bauen will, nimmt sie in die Zweithand: Dort zoomt
     * nur ein Klick mit leerer Haupthand.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onClick(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a == Action.PHYSICAL) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack main = p.getInventory().getItemInMainHand();
        ItemStack off = p.getInventory().getItemInOffHand();
        boolean ours = e.getHand() == EquipmentSlot.HAND ? isOurs(main)
                : e.getHand() == EquipmentSlot.OFF_HAND && main.getType().isAir() && isOurs(off);
        if (!ours) {
            return;
        }
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        // Ein Klick, eine Stufe: Linksklick auf einen Block meldet Bukkit oft zweimal (Block und Luft).
        Long last = lastZoomClick.get(p.getUniqueId());
        if (last != null && tick - last < 3) {
            return;
        }
        lastZoomClick.put(p.getUniqueId(), tick);
        double now = zoom.getOrDefault(p.getUniqueId(), settings.defaultZoom());
        boolean in = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        double next = in ? MinimapSettings.zoomIn(now, p.isSneaking()) : MinimapSettings.zoomOut(now, p.isSneaking());
        setZoom(p, next);
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(zoomText(next)));
    }

    private boolean isOurs(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !(item.getItemMeta() instanceof MapMeta m)
                || !m.hasMapView()) {
            return false;
        }
        MapView v = m.getMapView();
        return v != null && mapIds.containsValue(v.getId());
    }

    // ------------------------------------------------------------------ Karten und Ereignisse

    private MapView viewFor(Player p) {
        Integer id = mapIds.get(p.getUniqueId());
        MapView v = id == null ? null : Bukkit.getMap(id);
        if (v == null) {
            v = Bukkit.createMap(p.getWorld());
            mapIds.put(p.getUniqueId(), v.getId());
            save();
        }
        attach(v);
        return v;
    }

    /** Vanilla-Zeichner raus, unserer rein; Position nicht verfolgen (wir zeichnen die Marker selbst). */
    private void attach(MapView v) {
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) {
            if (r != renderer) {
                v.removeRenderer(r);
            }
        }
        if (!v.getRenderers().contains(renderer)) {
            v.addRenderer(renderer);
        }
        v.setScale(MapView.Scale.CLOSEST);
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        v.setLocked(true);
    }

    @EventHandler
    public void onMapInit(MapInitializeEvent e) {
        if (mapIds.containsValue(e.getMap().getId())) {
            attach(e.getMap());
        }
    }

    /** Neu geladener Chunk: Übersicht später lesen (mit Budget im Tick) – im Ereignis selbst nichts anfassen. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!settings.overview() || !settings.allows(e.getWorld())) {
            return;
        }
        LinkedHashSet<Long> q = overviewWanted.computeIfAbsent(e.getWorld().getName(), k -> new LinkedHashSet<>());
        if (q.size() < MAX_OVERVIEW_QUEUE) {
            q.add(MinimapImage.chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
        }
    }

    /**
     * Chunk geht: jetzt noch die Übersicht lesen – danach ist er weg. Gerade beim Stadt- und Weltbau steht erst jetzt
     * fest, was gebaut wurde (beim Laden war es noch das leere Gelände). 16 Spalten, gemessen ~20–50 µs je Chunk.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (!settings.overview() || !settings.allows(e.getWorld())) {
            return;
        }
        readOverview(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ());
        LinkedHashSet<Long> q = overviewWanted.get(e.getWorld().getName());
        if (q != null) {
            q.remove(MinimapImage.chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        cache.markDirty(e.getBlock().getWorld().getName(), e.getBlock().getX() >> 4, e.getBlock().getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        cache.markDirty(e.getBlock().getWorld().getName(), e.getBlock().getX() >> 4, e.getBlock().getZ() >> 4);
    }

    // ------------------------------------------------------------------ Lesen (Server-Thread, mit Budget)

    private void tickWork() {
        tick++;
        if (wanted.isEmpty() && overviewWanted.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + (long) (settings.msPerTick() * 1_000_000);
        readWanted(deadline);
        readOverviewWanted(deadline);
    }

    /** Übersicht frisch geladener Chunks, im Rest des Budgets (die Karte in der Hand hat Vorrang). */
    private void readOverviewWanted(long deadline) {
        Iterator<Map.Entry<String, LinkedHashSet<Long>>> worlds = overviewWanted.entrySet().iterator();
        while (worlds.hasNext() && System.nanoTime() < deadline) {
            Map.Entry<String, LinkedHashSet<Long>> e = worlds.next();
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) {
                worlds.remove();
                continue;
            }
            Iterator<Long> keys = e.getValue().iterator();
            while (keys.hasNext() && System.nanoTime() < deadline) {
                long key = keys.next();
                keys.remove();
                int cx = MinimapImage.chunkKeyX(key);
                int cz = MinimapImage.chunkKeyZ(key);
                if (w.isChunkLoaded(cx, cz)) {
                    readOverview(w, cx, cz);
                }
            }
            if (e.getValue().isEmpty()) {
                worlds.remove();
            }
        }
    }

    private void readWanted(long deadline) {
        Iterator<Map.Entry<String, LinkedHashSet<Long>>> worlds = wanted.entrySet().iterator();
        while (worlds.hasNext() && System.nanoTime() < deadline) {
            Map.Entry<String, LinkedHashSet<Long>> e = worlds.next();
            World w = Bukkit.getWorld(e.getKey());
            if (w == null) {
                cache.forgetWorld(e.getKey());
                worlds.remove();
                continue;
            }
            Iterator<Long> keys = e.getValue().iterator();
            while (keys.hasNext() && System.nanoTime() < deadline) {
                long key = keys.next();
                keys.remove();
                int cx = MinimapImage.chunkKeyX(key);
                int cz = MinimapImage.chunkKeyZ(key);
                // Nie laden: Ein nicht geladener Chunk bleibt Hintergrund, bis ihn jemand (Spieler, Bau) lädt.
                if (w.isChunkLoaded(cx, cz)) {
                    readTile(w, cx, cz);
                }
            }
            if (e.getValue().isEmpty()) {
                worlds.remove();
            }
        }
    }

    private void readTile(World w, int cx, int cz) {
        long t0 = System.nanoTime();
        byte[] base = new byte[256];
        short[] height = new short[256];
        boolean[] road = new boolean[256];
        int min = w.getMinHeight();
        try {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int x = (cx << 4) + dx;
                    int z = (cz << 4) + dz;
                    int i = dz * 16 + dx;
                    int[] col = readColumn(w, x, z, min);
                    base[i] = (byte) col[0];
                    height[i] = (short) Math.clamp(col[1], Short.MIN_VALUE, Short.MAX_VALUE);
                    road[i] = col[2] != 0;
                }
            }
            markRoads(base, height, road);
        } catch (RuntimeException ex) {
            // Grenze eines Leseschritts: lieber eine fehlende Kachel als eine Ausnahme im Tick (fail-open, Anzeige).
            LOG.log(Level.WARNING, "karte | kachel_fehler | welt=" + w.getName() + " chunk=" + cx + "," + cz, ex);
            return;
        }
        MinimapImage.Tile tile = new MinimapImage.Tile(base, height);
        cache.put(w.getName(), MinimapImage.chunkKey(cx, cz), tile, System.currentTimeMillis());
        if (settings.overview()) {
            overview.putFromTile(w.getName(), cx, cz, tile);
        }
        tilesRead++;
        readNanos += System.nanoTime() - t0;
    }

    /**
     * Eine Spalte: {Grundfarbe, Höhe, Straßenbelag 1/0}. Straßenbelag nur, wenn darunter kein Hohlraum ist – ein
     * Hallendach aus grauem Beton hat Luft darunter, eine Fahrbahn Erde oder Stein.
     */
    private int[] readColumn(World w, int x, int z, int min) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        for (int k = 0; k < SEARCH_DOWN && y >= min; k++, y--) {
            Block block = w.getBlockAt(x, y, z);
            BlockData data = block.getBlockData();
            int rgb = data.getMapColor().asRGB();
            if (rgb != 0) {
                boolean road = ROAD_BLOCKS.contains(data.getMaterial()) && y - 1 >= min
                        && !w.getBlockAt(x, y - 1, z).getType().isAir();
                return new int[] {baseOf(rgb), y, road ? 1 : 0};
            }
        }
        return new int[] {MinimapImage.NONE, y, 0};
    }

    /**
     * Straßenbelag zählt als Straße, wenn er höchstens ROAD_ABOVE_GROUND über dem tiefsten Punkt seines 4 × 4-Felds
     * liegt – Dächer aus Beton stehen höher als der Boden daneben. Je Feld statt je Chunk, damit Straßen am Hang
     * (Erdwelt) nicht gegen das Tal weiter unten im selben Chunk verlieren.
     */
    static void markRoads(byte[] base, short[] height, boolean[] road) {
        for (int fz = 0; fz < 16; fz += 4) {
            for (int fx = 0; fx < 16; fx += 4) {
                int ground = Integer.MAX_VALUE;
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int i = (fz + dz) * 16 + fx + dx;
                        if ((base[i] & 0x7F) != MinimapImage.NONE) {
                            ground = Math.min(ground, height[i]);
                        }
                    }
                }
                for (int dz = 0; dz < 4; dz++) {
                    for (int dx = 0; dx < 4; dx++) {
                        int i = (fz + dz) * 16 + fx + dx;
                        if (road[i] && height[i] <= ground + ROAD_ABOVE_GROUND) {
                            base[i] = (byte) (base[i] | MinimapImage.ROAD);
                        }
                    }
                }
            }
        }
    }

    /** 4 × 4 Stichproben eines geladenen Chunks in die Übersicht (Mitte jedes 4 × 4-Felds). */
    private void readOverview(World w, int cx, int cz) {
        int[] s = new int[OverviewStore.PER_CHUNK];
        int min = w.getMinHeight();
        try {
            for (int sz = 0; sz < 4; sz++) {
                for (int sx = 0; sx < 4; sx++) {
                    int x = (cx << 4) + sx * OverviewStore.SPACING + OverviewStore.SPACING / 2;
                    int z = (cz << 4) + sz * OverviewStore.SPACING + OverviewStore.SPACING / 2;
                    int[] col = readColumn(w, x, z, min);
                    // Eine Stichprobe je Feld: kein Feldvergleich möglich, der Hohlraum-Test allein hält Dächer fern.
                    s[sz * 4 + sx] = OverviewStore.pack(col[0] | (col[2] != 0 ? MinimapImage.ROAD : 0), col[1]);
                }
            }
        } catch (RuntimeException ex) {
            // fail-open wie readTile: eine fehlende Übersicht ist nur Hintergrund auf der Karte.
            LOG.log(Level.WARNING, "karte | übersicht_fehler | welt=" + w.getName() + " chunk=" + cx + "," + cz, ex);
            return;
        }
        overview.putChunk(w.getName(), cx, cz, s);
        overviewReads++;
    }

    // ------------------------------------------------------------------ Vorab-Scan der Regionsdateien

    /** Eine Welt für den Scan: Name, Bauhöhe und Regionsordner – auf dem Server-Thread ermittelt. */
    private record ScanWorld(String name, int minY, int maxY, Path regionDir) {
    }

    /**
     * Alle Kartenwelten scannen (nur geänderte Regionsdateien). Farbtabelle und Weltdaten kommen vom Server-Thread, das
     * Lesen läuft auf dem Scan-Thread, das Ergebnis je Datei geht zurück auf den Server-Thread in die Übersicht.
     */
    private void startScan() {
        if (scanState.startsWith("läuft") || !wallClockDue(lastScan, RESCAN_NANOS)) {
            return;
        }
        lastScan = System.nanoTime();
        if (scanColors == null) {
            scanColors = colorTable();
        }
        Map<String, Integer> colors = scanColors;
        Set<String> roads = new HashSet<>();
        for (Material m : ROAD_BLOCKS) {
            roads.add(m.getKey().toString());
        }
        List<ScanWorld> worlds = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (settings.allows(w)) {
                Path dir = regionDir(w.getWorldFolder().toPath(), w.getKey().getNamespace(), w.getKey().getKey());
                // Einmal je Welt und Änderung: Nach einem Versionssprung (26.x zieht Welten nach dimensions/ um) ist das
                // die einzige Stelle, an der man sieht, welchen Ordner die Karte wirklich liest.
                if (dir != null && !dir.equals(scannedDirs.put(w.getName(), dir))) {
                    LOG.info(() -> "karte | regionsordner | welt=" + w.getName() + " pfad=" + dir);
                }
                if (dir != null) {
                    worlds.add(new ScanWorld(w.getName(), w.getMinHeight(), w.getMaxHeight(), dir));
                }
            }
        }
        scanState = "läuft";
        try {
            scanner.execute(() -> scanAll(worlds, colors, Set.copyOf(roads)));
        } catch (RejectedExecutionException e) {
            scanState = "abgebrochen";
        }
    }

    /** Grundfarbe je Blockname aller Blocktypen – BlockData#getMapColor braucht Bukkit, also einmal hier. */
    private Map<String, Integer> colorTable() {
        Map<String, Integer> out = new HashMap<>();
        for (Material m : Material.values()) {
            if (!m.isBlock() || m.isLegacy()) {
                continue;
            }
            try {
                int rgb = Bukkit.createBlockData(m).getMapColor().asRGB();
                out.put(m.getKey().toString(), rgb == 0 ? 0 : baseOf(rgb));
            } catch (IllegalArgumentException e) {
                // Block ohne eigenen Zustand (sehr selten) – bleibt ohne Kartenfarbe, die Karte sucht darunter weiter.
                out.put(m.getKey().toString(), 0);
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Regionsordner einer Welt. 1.21.11: region/ in der Weltmappe, eigene Dimensionen (Erd-, Geländewelt) unter
     * dimensions/&lt;ns&gt;/&lt;name&gt;/region. 26.x (Ordner-Umzug „WorldFolderMigration“, gemessen 2026-11-07 im
     * Probelauf der dev01-Sicherung): auch die Oberwelt liegt unter dimensions/minecraft/overworld/region, daneben
     * the_end und weitere. Vorher nahm die Suche den ersten dimensions-Treffer – auf 26.x hätte die Karte einer Welt die
     * Chunks einer anderen gezeigt. Deshalb zuerst der eigene Schlüssel, dann region/, und der erste Treffer nur, wenn
     * es genau einen gibt.
     */
    static Path regionDir(Path worldFolder, String namespace, String key) {
        Path own = worldFolder.resolve("dimensions").resolve(namespace).resolve(key).resolve("region");
        if (Files.isDirectory(own)) {
            return own;
        }
        Path plain = worldFolder.resolve("region");
        if (Files.isDirectory(plain)) {
            return plain;
        }
        Path dims = worldFolder.resolve("dimensions");
        if (!Files.isDirectory(dims)) {
            return null;
        }
        List<Path> found = new ArrayList<>();
        try (var ns = Files.list(dims)) {
            for (Path n : ns.toList()) {
                if (!Files.isDirectory(n)) {
                    continue;
                }
                try (var keys = Files.list(n)) {
                    for (Path k : keys.toList()) {
                        if (Files.isDirectory(k.resolve("region"))) {
                            found.add(k.resolve("region"));
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "karte | regionsordner_unlesbar | welt=" + worldFolder, e);
        }
        if (found.size() == 1) {
            return found.getFirst();
        }
        if (found.size() > 1) {
            LOG.warning(() -> "karte | regionsordner_mehrdeutig | welt=" + worldFolder + " schlüssel=" + namespace + ":"
                    + key + " kandidaten=" + found.size() + " – Karte dieser Welt ohne Plattenscan");
        }
        return null;
    }

    /** Scan-Thread. */
    private void scanAll(List<ScanWorld> worlds, Map<String, Integer> colors, Set<String> roads) {
        long t0 = System.nanoTime();
        int files = 0;
        int chunks = 0;
        try {
            for (ScanWorld w : worlds) {
                Path indexFile = plugin.getDataFolder().toPath().resolve("minikarte")
                        .resolve(w.name().replaceAll("[^A-Za-z0-9_-]", "_")).resolve("gescannt.properties");
                Properties index = new Properties();
                if (Files.exists(indexFile)) {
                    try (var r = Files.newBufferedReader(indexFile)) {
                        index.load(r);
                    }
                }
                List<Path> mcas;
                try (var s = Files.list(w.regionDir())) {
                    mcas = s.filter(f -> RegionScanner.regionCoords(f) != null).sorted().toList();
                }
                int worldFiles = 0;
                int worldChunks = 0;
                for (Path f : mcas) {
                    if (Thread.currentThread().isInterrupted()) {
                        scanState = "abgebrochen";
                        return;
                    }
                    String stamp = Files.getLastModifiedTime(f).toMillis() + ":" + Files.size(f);
                    String name = f.getFileName().toString();
                    if (stamp.equals(index.getProperty(name))) {
                        continue;
                    }
                    RegionScanner.Result res = RegionScanner.scan(f, w.minY(), w.maxY(),
                            b -> colors.getOrDefault(b, 0), roads);
                    int[] rc = RegionScanner.regionCoords(f);
                    long rk = MinimapImage.chunkKey(rc[0], rc[1]);
                    String world = w.name();
                    if (plugin.isEnabled()) {
                        Bukkit.getScheduler().runTask(plugin, () -> overview.putScan(world, rk, res.data()));
                    }
                    index.setProperty(name, stamp);
                    worldFiles++;
                    worldChunks += res.chunks();
                    scanState = String.format(Locale.ROOT, "läuft – %s %d/%d Dateien", world, worldFiles, mcas.size());
                }
                if (worldFiles > 0) {
                    Files.createDirectories(indexFile.getParent());
                    try (var out = Files.newBufferedWriter(indexFile)) {
                        index.store(out, "Minikarte: gescannte Regionsdateien (Änderungszeit:Größe)");
                    }
                    int wf = worldFiles;
                    int wc = worldChunks;
                    LOG.info(() -> "karte | vorab_gescannt | welt=" + w.name() + " dateien=" + wf + " chunks=" + wc);
                }
                files += worldFiles;
                chunks += worldChunks;
            }
            int f = files;
            int c = chunks;
            long ms = (System.nanoTime() - t0) / 1_000_000;
            scanState = String.format(Locale.ROOT, "fertig – %d Dateien, %d Chunks in %.1f s", f, c, ms / 1000.0);
            if (f > 0) {
                LOG.info(() -> "karte | vorab_fertig | dateien=" + f + " chunks=" + c + " dauer_ms=" + ms);
            }
        } catch (IOException | RuntimeException e) {
            // Grenze des Scan-Threads: Anzeige, kein Schutz – loggen und beim nächsten Durchgang (10 min) neu versuchen.
            scanState = "Fehler: " + e.getMessage();
            LOG.log(Level.WARNING, "karte | vorab_fehler", e);
        }
    }

    // ------------------------------------------------------------------ Übersicht auf der Platte (io-Thread)

    private Path regionFile(String world, long regionKey) {
        return plugin.getDataFolder().toPath().resolve("minikarte").resolve(world.replaceAll("[^A-Za-z0-9_-]", "_"))
                .resolve("r." + MinimapImage.chunkKeyX(regionKey) + "." + MinimapImage.chunkKeyZ(regionKey) + ".bin.gz");
    }

    /** Geänderte Region zum Schreiben auf den io-Thread (der Reihe nach – keine ältere Fassung überholt eine neuere). */
    private void saveLater(OverviewStore.Saved s) {
        try {
            io.execute(() -> writeRegion(s));
        } catch (RejectedExecutionException e) {
            writeRegion(s);  // beim Abschalten: direkt schreiben statt verlieren
        }
    }

    private void writeRegion(OverviewStore.Saved s) {
        Path f = regionFile(s.world(), s.regionKey());
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, OverviewStore.encode(s.data()));
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "karte | übersicht_nicht_gespeichert | datei=" + f, e);
        }
    }

    /** Region von der Platte; null, wenn es sie nicht gibt oder sie kaputt ist (dann zählt sie als leer). */
    private int[] readRegion(String world, long regionKey) {
        Path f = regionFile(world, regionKey);
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return OverviewStore.decode(Files.readAllBytes(f));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "karte | übersicht_unlesbar | datei=" + f + " – wird beim nächsten Speichern ersetzt", e);
            return null;
        }
    }

    /** Sichtbare Regionen, die weder im Speicher noch unterwegs noch bekannt fehlend sind, von der Platte holen. */
    private void requestRegions(String world, MinimapImage.View v) {
        for (long rk : visibleRegions(v)) {
            String id = world + "|" + rk;
            if (loadingRegions.size() >= MAX_LOADING) {
                return;
            }
            if (overview.hasRegion(world, rk) || loadingRegions.contains(id) || absentRegions.contains(id)) {
                continue;
            }
            loadingRegions.add(id);
            try {
                io.execute(() -> {
                    int[] d = readRegion(world, rk);
                    // Beim Abschalten kann eine Lesung noch laufen: Dann nimmt Bukkit keine Aufgabe mehr an.
                    if (!plugin.isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        loadingRegions.remove(id);
                        if (d == null) {
                            absentRegions.add(id);
                        } else {
                            overview.putRegion(world, rk, d);
                        }
                    });
                });
            } catch (RejectedExecutionException e) {
                loadingRegions.remove(id);
                return;
            }
        }
    }

    /**
     * Regionen (32 × 32 Chunks, 512 Blöcke), die das gedrehte Bildquadrat berührt – die nächsten zuerst. Vorher der
     * ganze Umkreis View.reach(): ganz herausgezoomt über 500 Regionen, mehr als karte.regionen-im-speicher hält, und die
     * Karte hätte dauernd dieselben Regionen geladen und wieder verdrängt (nachgerechnet 2026-10-31). Das Quadrat selbst
     * berührt höchstens ~300.
     */
    static List<Long> visibleRegions(MinimapImage.View v) {
        int reach = v.reach();
        int x = (int) Math.floor(v.centerX());
        int z = (int) Math.floor(v.centerZ());
        // Rechteck der vier Regionsecken im Bild gegen das Bild selbst. Vorher Regionsmitte mit einer halben Diagonale
        // Spielraum: nordausgerichtet 18 × 18 statt 16 × 16 Regionen – auch die, die das Bild nur an der Kante berühren.
        List<long[]> found = new ArrayList<>();
        for (int rz = (z - reach) >> 9; rz <= (z + reach) >> 9; rz++) {
            for (int rx = (x - reach) >> 9; rx <= (x + reach) >> 9; rx++) {
                double minSx = Double.MAX_VALUE;
                double maxSx = -Double.MAX_VALUE;
                double minSy = Double.MAX_VALUE;
                double maxSy = -Double.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    double ex = rx * 512.0 + (k & 1) * 512;
                    double ez = rz * 512.0 + (k >> 1) * 512;
                    double sx = v.screenX(ex, ez);
                    double sy = v.screenY(ex, ez);
                    minSx = Math.min(minSx, sx);
                    maxSx = Math.max(maxSx, sx);
                    minSy = Math.min(minSy, sy);
                    maxSy = Math.max(maxSy, sy);
                }
                if (maxSx > 0 && minSx < MinimapImage.SIZE && maxSy > 0 && minSy < MinimapImage.SIZE) {
                    found.add(new long[] {MinimapImage.chunkKey(rx, rz),
                        (long) Math.hypot(rx * 512.0 + 256 - x, rz * 512.0 + 256 - z)});
                }
            }
        }
        found.sort(Comparator.comparingLong(a -> a[1]));
        List<Long> out = new ArrayList<>(found.size());
        for (long[] f : found) {
            out.add(f[0]);
        }
        return out;
    }

    /** Grundfarbe (Palettenbyte / 4) einer Blockfarbe. */
    private int baseOf(int rgb) {
        return baseOfRgb.computeIfAbsent(rgb, c -> (palette(c >> 16 & 0xFF, c >> 8 & 0xFF, c & 0xFF) & 0xFF) >> 2);
    }

    /**
     * Palettenbyte bei normaler Helligkeit. MapPalette.matchColor ist in Bukkit als veraltet markiert, weil sich die
     * Palette zwischen Versionen ändern kann – hier gerade richtig: Wir fragen zur Laufzeit die Palette des laufenden
     * Servers, und getMapColor liefert die Vanilla-Grundfarbe, die mit 220/255 genau einen Paletteneintrag trifft.
     */
    @SuppressWarnings("deprecation")
    static byte palette(int r, int g, int b) {
        return MapPalette.matchColor(r * NORMAL_BRIGHTNESS / 255, g * NORMAL_BRIGHTNESS / 255,
                b * NORMAL_BRIGHTNESS / 255);
    }

    @SuppressWarnings("deprecation")
    private static BufferedImage toImage(byte[] img) {
        BufferedImage bi = new BufferedImage(MinimapImage.SIZE, MinimapImage.SIZE, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < img.length; i++) {
            int rgb = img[i] == MinimapImage.NONE ? 0xD7C9A0 : MapPalette.getColor(img[i]).getRGB() & 0xFFFFFF;
            bi.setRGB(i % MinimapImage.SIZE, i / MinimapImage.SIZE, rgb);
        }
        return bi;
    }

    /**
     * Chunks, die ein Bild um (x, z) mit diesem Zoom berührt – samt der Zeile nördlich (Schattierung) –, aber höchstens
     * maxRadius Chunks um die Mitte. Bei 64 Blöcken je Punkt wären es sonst über 260 000 Chunks je Bild.
     */
    private static Set<Long> visibleChunks(int x, int z, int reach, int maxRadius) {
        int half = reach;
        int north = 0;
        int ccx = x >> 4;
        int ccz = z >> 4;
        Set<Long> out = new LinkedHashSet<>();
        for (int cz = Math.max((z - half - north) >> 4, ccz - maxRadius); cz <= Math.min((z + half) >> 4, ccz + maxRadius);
                cz++) {
            for (int cx = Math.max((x - half) >> 4, ccx - maxRadius);
                    cx <= Math.min((x + half) >> 4, ccx + maxRadius); cx++) {
                out.add(MinimapImage.chunkKey(cx, cz));
            }
        }
        return out;
    }

    /** Umkreis, in dem Chunks um einen Spieler überhaupt geladen sein können. */
    private static int nearRadius(World w) {
        return w.getViewDistance() + NEAR_PADDING;
    }

    // ------------------------------------------------------------------ Zeichnen

    private final class Renderer extends MapRenderer {

        Renderer() {
            super(true);  // je Spieler ein eigenes Bild: jeder sieht seine Umgebung
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player p) {
            UUID id = p.getUniqueId();
            Long last = lastFrame.get(id);
            boolean turning = rotates(p) && Math.abs(angle(p.getLocation().getYaw() - lastYaw.getOrDefault(id, 0f)))
                    >= ROTATE_DEG;
            if (last != null && tick - last < (turning ? ROTATE_TICKS : settings.frameTicks())) {
                return;
            }
            lastFrame.put(id, tick);
            lastYaw.put(id, p.getLocation().getYaw());
            World w = p.getWorld();
            if (!settings.allows(w)) {
                for (int i = 0; i < MinimapImage.SIZE * MinimapImage.SIZE; i++) {
                    canvas.setPixel(i % MinimapImage.SIZE, i / MinimapImage.SIZE, MinimapImage.NONE);
                }
                canvas.drawText(10, 56, MinecraftFont.Font, "Keine Karte in dieser Welt");
                canvas.setCursors(new MapCursorCollection());
                return;
            }
            long t0 = System.nanoTime();
            double zm = zoom.getOrDefault(id, settings.defaultZoom());
            Location l = p.getLocation();
            int cx = l.getBlockX();
            int cz = l.getBlockZ();
            MinimapImage.View v = rotates(p) ? MinimapImage.View.headingUp(l.getX(), l.getZ(), zm, l.getYaw())
                    : new MinimapImage.View(l.getX(), l.getZ(), zm, 1, 0);
            String wn = w.getName();
            Set<Long> missing = new HashSet<>();
            byte[] img = MinimapImage.compose((x, z) -> cache.get(wn, x, z), (x, z) -> overview.sample(wn, x, z), v,
                    MinimapImage.NONE, missing);
            request(wn, missing, cx, cz, v.reach(), nearRadius(w));
            if (settings.overview()) {
                requestRegions(wn, v);
            }
            MinimapImage.fillUnknown(img, MinimapImage.NONE, unknownDark, unknownLight);
            plots.at(l, id).ifPresent(site -> MinimapImage.outline(img, v, site.rect().minX(), site.rect().minZ(),
                    site.rect().maxX(), site.rect().maxZ(), plotColor));
            for (int i = 0; i < img.length; i++) {
                canvas.setPixel(i % MinimapImage.SIZE, i / MinimapImage.SIZE, img[i]);
            }
            canvas.setCursors(cursors(p, w, v));
            frames++;
            composeNanos += System.nanoTime() - t0;
        }
    }

    /**
     * Fehlende Chunks in der Nähe vorn in die Warteschlange, sichtbare veraltete hinten an. Nur im Umkreis radius um die
     * Mitte – weiter draußen ist nichts geladen, dort zeigt die Übersicht.
     */
    private void request(String world, Set<Long> missing, int x, int z, int reach, int radius) {
        LinkedHashSet<Long> q = wanted.computeIfAbsent(world, k -> new LinkedHashSet<>());
        Set<Long> near = visibleChunks(x, z, reach, radius);
        LinkedHashSet<Long> front = new LinkedHashSet<>();
        for (long key : near) {
            if (missing.contains(key)) {
                front.add(key);
            }
        }
        if (!front.isEmpty()) {
            front.addAll(q);
            q.clear();
            q.addAll(front);
        }
        long now = System.currentTimeMillis();
        long maxAge = settings.refreshSeconds() * 1000L;
        for (long key : near) {
            if (!missing.contains(key) && cache.needsRead(world, key, now, maxAge)) {
                q.add(key);
            }
        }
        if (q.isEmpty()) {
            wanted.remove(world);
        }
    }

    private MapCursorCollection cursors(Player p, World w, MinimapImage.View v) {
        MapCursorCollection c = new MapCursorCollection();
        float viewYaw = rotates(p) ? p.getLocation().getYaw() : 180f;  // 180 = Norden oben
        Location me = p.getLocation();
        add(c, v, me.getX(), me.getZ(), me.getYaw() - viewYaw + 180f, MapCursor.Type.PLAYER, null, false);
        for (Player o : w.getPlayers()) {
            if (!o.equals(p) && p.canSee(o)) {
                Location ol = o.getLocation();
                add(c, v, ol.getX(), ol.getZ(), ol.getYaw() - viewYaw + 180f, MapCursor.Type.BLUE_MARKER, o.getName(),
                        false);
            }
        }
        BuildJob job = plugin.service() == null ? null : plugin.service().activeJob();
        if (job != null && w.equals(job.center().getWorld())) {
            add(c, v, job.center().getX(), job.center().getZ(), 180f, MapCursor.Type.TARGET_X,
                    "Bau " + (job.total() == 0 ? 0 : 100L * job.done() / job.total()) + " %", false);
            for (Location h : job.workerLocations()) {
                add(c, v, h.getX(), h.getZ(), h.getYaw() - viewYaw + 180f, MapCursor.Type.FRAME, null, false);
            }
        }
        // Reisepunkte dieser Welt (Städte, Gelände, eigene Orte), nur wenn sichtbar – sonst wäre die Karte voll.
        if (plugin.places() != null) {
            for (PlaceRegistry.Place pl : plugin.places().all()) {
                if (pl.world().equals(w.getName())) {
                    add(c, v, pl.x(), pl.z(), 180f, placeType(pl.kind()), pl.name(), false);
                }
            }
        }
        // Eigene Wegpunkte (sichtbare) und das Ziel – das Ziel auch am Rand, mit Entfernung.
        String goal = target.get(p.getUniqueId());
        LinkedHashMap<String, Waypoint> mine = waypoints.getOrDefault(p.getUniqueId(), new LinkedHashMap<>());
        for (Map.Entry<String, Waypoint> e : mine.entrySet()) {
            Waypoint wp = e.getValue();
            if (wp.world().equals(w.getName()) && !e.getKey().equals(goal)) {
                add(c, v, wp.x(), wp.z(), 180f, MapCursor.Type.BANNER_YELLOW, names.getOrDefault(e.getKey(), e.getKey()),
                        false);
            }
        }
        if (goal != null) {
            double[] at = goalPosition(goal, mine, w);
            if (at != null) {
                String label = names.getOrDefault(goal, goal) + " " + distanceText(Math.hypot(at[0] - me.getX(),
                        at[1] - me.getZ()));
                add(c, v, at[0], at[1], 180f, MapCursor.Type.RED_X, label, true);
            }
        }
        return c;
    }

    /** Ort des Ziels in dieser Welt (Wegpunkt vor Reisepunkt) oder null. */
    private double[] goalPosition(String goal, Map<String, Waypoint> mine, World w) {
        Waypoint wp = mine.get(goal);
        if (wp != null) {
            return wp.world().equals(w.getName()) ? new double[] {wp.x(), wp.z()} : null;
        }
        PlaceRegistry.Place pl = placeNamed(goal);
        return pl != null && pl.world().equals(w.getName()) ? new double[] {pl.x(), pl.z()} : null;
    }

    private static MapCursor.Type placeType(PlaceRegistry.Kind k) {
        return switch (k) {
            case CITY -> MapCursor.Type.VILLAGE_PLAINS;
            case TERRAIN -> MapCursor.Type.JUNGLE_TEMPLE;
            case EARTH -> MapCursor.Type.MANSION;
            case CUSTOM -> MapCursor.Type.BANNER_WHITE;
        };
    }

    /** Winkel auf −180..180 Grad. */
    static float angle(float deg) {
        float a = deg % 360f;
        if (a > 180f) {
            a -= 360f;
        } else if (a <= -180f) {
            a += 360f;
        }
        return a;
    }

    /**
     * Marker an Weltpunkt (x, z) in der Ansicht v; mapYaw ist die Blickrichtung relativ zur Karte (180 = nach oben).
     * Liegt der Punkt außerhalb, fällt er weg – außer bei edge: Dann sitzt er am Rand in seiner Richtung (Ziel).
     * Kartenmarker zählen in halben Pixeln (−128..127).
     */
    private static void add(MapCursorCollection c, MinimapImage.View v, double x, double z, float mapYaw,
            MapCursor.Type type, String caption, boolean edge) {
        double sx = v.screenX(x, z);
        double sy = v.screenY(x, z);
        boolean inside = sx >= 0 && sx < MinimapImage.SIZE && sy >= 0 && sy < MinimapImage.SIZE;
        if (!inside) {
            if (!edge) {
                return;
            }
            // Auf den Rand ziehen, entlang der Linie von der Mitte – so zeigt der Marker die Richtung.
            double dx = sx - MinimapImage.SIZE / 2.0;
            double dy = sy - MinimapImage.SIZE / 2.0;
            double f = (MinimapImage.SIZE / 2.0 - 3) / Math.max(Math.abs(dx), Math.abs(dy));
            sx = MinimapImage.SIZE / 2.0 + dx * f;
            sy = MinimapImage.SIZE / 2.0 + dy * f;
        }
        c.addCursor(new MapCursor(cursorCoord(sx), cursorCoord(sy), direction(mapYaw), type, true, caption));
    }

    static byte cursorCoord(double screen) {
        return (byte) Math.clamp(Math.round(screen * 2 - MinimapImage.SIZE), -128, 127);
    }

    /** Richtung wie Vanilla (MapItemSavedData.addDecoration): Blickwinkel ±8° gerundet auf 16 Stufen. */
    static byte direction(float mapYaw) {
        float yaw = angle(mapYaw);
        return (byte) (((int) ((yaw < 0 ? yaw - 8 : yaw + 8) * 16 / 360)) & 15);
    }

    // ------------------------------------------------------------------ Speichern

    private void load() {
        Path f = plugin.getDataFolder().toPath().resolve(FILE);
        if (!Files.exists(f)) {
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(f.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            LOG.log(Level.WARNING, "karte | karten_yml_unlesbar | neue Karten bekommen neue IDs", e);
            return;
        }
        readMap(y, "karten", mapIds, Integer.MAX_VALUE);
        var rs = y.getConfigurationSection("drehen");
        if (rs != null) {
            for (String k : rs.getKeys(false)) {
                try {
                    rotate.put(UUID.fromString(k), rs.getBoolean(k, settings.rotate()));
                } catch (IllegalArgumentException e) {
                    LOG.warning(() -> "karte | eintrag_übersprungen | schlüssel=" + k);
                }
            }
        }
        var ps = y.getConfigurationSection("punkte");
        if (ps != null) {
            for (String u : ps.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(u);
                    var one = ps.getConfigurationSection(u);
                    if (one == null) {
                        continue;
                    }
                    LinkedHashMap<String, Waypoint> mine = waypoints.computeIfAbsent(id, k -> new LinkedHashMap<>());
                    for (String n : one.getKeys(false)) {
                        String world = one.getString(n + ".welt", "");
                        String shown = one.getString(n + ".name", n);
                        if (!world.isEmpty() && mine.size() < MAX_WAYPOINTS) {
                            mine.put(key(shown), new Waypoint(world, one.getDouble(n + ".x"), one.getDouble(n + ".z")));
                            names.put(key(shown), shown);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    LOG.warning(() -> "karte | eintrag_übersprungen | schlüssel=" + u);
                }
            }
        }
        var ts = y.getConfigurationSection("ziel");
        if (ts != null) {
            for (String k : ts.getKeys(false)) {
                try {
                    target.put(UUID.fromString(k), key(ts.getString(k, "")));
                } catch (IllegalArgumentException e) {
                    LOG.warning(() -> "karte | eintrag_übersprungen | schlüssel=" + k);
                }
            }
        }
        var zs = y.getConfigurationSection("zoom");
        if (zs != null) {
            for (String k : zs.getKeys(false)) {
                try {
                    zoom.put(UUID.fromString(k), MinimapSettings.clampZoom(zs.getDouble(k, 1.0)));
                } catch (IllegalArgumentException e) {
                    LOG.warning(() -> "karte | eintrag_übersprungen | schlüssel=" + k);
                }
            }
        }
    }

    private static void readMap(YamlConfiguration y, String section, Map<UUID, Integer> into, int max) {
        var s = y.getConfigurationSection(section);
        if (s == null) {
            return;
        }
        for (String k : s.getKeys(false)) {
            try {
                int v = s.getInt(k, -1);
                if (v >= 0 && v <= max) {
                    into.put(UUID.fromString(k), v);
                }
            } catch (IllegalArgumentException e) {
                LOG.warning(() -> "karte | eintrag_übersprungen | schlüssel=" + k);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        mapIds.forEach((u, id) -> y.set("karten." + u, id));
        zoom.forEach((u, z) -> y.set("zoom." + u, z));
        rotate.forEach((u, r) -> y.set("drehen." + u, r));
        target.forEach((u, t) -> y.set("ziel." + u, t));
        // Wegpunkte unter laufender Nummer: Namen dürfen Leerzeichen haben, YAML-Schlüssel sollen schlicht bleiben.
        waypoints.forEach((u, mine) -> {
            int n = 0;
            for (Map.Entry<String, Waypoint> e : mine.entrySet()) {
                String base = "punkte." + u + ".p" + n++;
                y.set(base + ".name", names.getOrDefault(e.getKey(), e.getKey()));
                y.set(base + ".welt", e.getValue().world());
                y.set(base + ".x", e.getValue().x());
                y.set(base + ".z", e.getValue().z());
            }
        });
        try {
            y.save(plugin.getDataFolder().toPath().resolve(FILE).toFile());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "karte | karten_yml_nicht_gespeichert", e);
        }
    }
}
