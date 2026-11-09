package com.blackn0va.baumeister.earth;

import com.blackn0va.baumeister.osm.OsmClient;
import com.blackn0va.baumeister.terrain.ElevationTiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Die ganze Erde 1:1 als eine zusammenhängende Welt, die beim Erkunden entsteht (Nutzerwunsch 2026-10-28).
 *
 * Welt: Datapack-Dimension baumeister:erde (TerrainService.writePack, selber dimension_type wie die Geländewelt,
 * y −2032..2031), Spigot lädt sie als „&lt;Hauptwelt&gt;_baumeister_erde“. Gefüllt wird sie vom EarthPopulator. Den
 * Spawn setzt das Plugin NICHT: World.setSpawnLocation ist in Datapack-Dimensionen wirkungslos (gemessen 2026-10-28).
 *
 * Projektion: config erde.projektion (airocean | sinus6), beim ersten Start mit geladener Erdwelt in erde/welt.json
 * festgeschrieben (ProjectionChoice). Höhe: HeightModel (1:1 mit örtlichem Bezug), Meere bis erde.meerestiefe-max,
 * Grundgestein erde.tiefe unter der Oberfläche.
 *
 * Threads: Befehle auf dem Hauptthread; Ortssuche, Vorladen der Kacheln und Messen im eigenen Pool, Teleport wieder
 * auf dem Hauptthread. Der Populator läuft auf den Weltgenerator-Threads.
 */
public final class EarthService implements EarthWorld, Listener {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Dimension im Datapack; Datei data/baumeister/dimension/erde.json. */
    public static final String DIMENSION = "baumeister:erde";
    /** Spigot benennt Datapack-Dimensionen „&lt;Hauptwelt&gt;_&lt;namensraum&gt;_&lt;pfad&gt;“ (gemessen an der Geländewelt). */
    public static final String WORLD_SUFFIX = "_baumeister_erde";
    /** Heimat und Ziel von /bm erde ohne frühere Stelle. */
    public static final double HOME_LAT = AiroceanProjection.HOME_LAT;
    public static final double HOME_LON = AiroceanProjection.HOME_LON;
    private static final int HIGH_WORLD_MIN_HEIGHT = 1024;
    private static final int FINE_ZOOM = ElevationTiles.ZOOM;
    /** Zoom 10 ≈ 153 m je Pixel: genug für den Höhenbezug, höchstens vier Kacheln je Fenster. */
    private static final int COARSE_ZOOM = 10;
    private static final int COARSE_CAPACITY = 64;
    /** Zoom 7 ≈ 1,2 km je Pixel für die ferne Stufe (700 km Fenster ≈ 50 Kacheln, je ~256 KB). */
    private static final int FAR_ZOOM = 7;
    private static final int FAR_CAPACITY = 96;
    /** Abstand der Vorlade-Stichproben: kleiner als eine Zoom-15-Kachel (≥ 600 m bis 70° Breite). */
    private static final int PRELOAD_STEP = 64;
    /** /bm reise nimmt Koordinaten bis an die Pole (Gelände je Ort: nur bis 85°). */
    private static final double MAX_TARGET_LAT = 90;

    /** Senken: so tief (m) gilt eine Zelle auf Zoom 7 als Ozean – tiefer als das Kaspische Meer in den Daten (−773). */
    static final double BASIN_DEEP_SEED = 1500;
    /** Senken: Komponenten, die nirgends so tief reichen, gelten als abgeschnittene Lagune (offen). */
    static final double BASIN_SHALLOW = 10;
    /** Senken: größere Komponenten gelten als Meer (Kaspisches Becken ≈ 0,5 Mio. Pixel, Ostsibirische See weit mehr). */
    static final long BASIN_MAX_PIXELS = 2_000_000;

    /** Werte aus config.yml, auf sinnvolle Bereiche begrenzt. */
    record Config(String projection, int seaDepthMax, int bedrockDepth, int tilesInMemory, boolean basins) {

        static Config from(ConfigurationSection c) {
            return new Config(
                    c.getString("erde.projektion", ProjectionChoice.DEFAULT),
                    Math.max(0, Math.min(1000, c.getInt("erde.meerestiefe-max", 200))),
                    Math.max(4, Math.min(512, c.getInt("erde.tiefe", 64))),
                    Math.max(16, Math.min(4096, c.getInt("erde.kacheln-im-speicher", ElevationTiles.DEFAULT_CAPACITY))),
                    c.getBoolean("erde.senken", true));
        }
    }

    private final JavaPlugin plugin;
    private final BiConsumer<CommandSender, String> info;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Baumeister-Erde");
        t.setDaemon(true);
        return t;
    });
    /** Letzte Stelle je Spieler in der Erdwelt. */
    private final VisitStore visits;
    /** Neumalen geschätzter Chunks (erde.neu-malen) oder null. */
    private EarthRepaint repaint;
    /** Spieler mit laufender Reise (nur Hauptthread). */
    private final Set<UUID> travelling = new HashSet<>();
    private volatile EarthProjection projection;
    private volatile EarthTerrain terrain;
    private String projectionName;
    private String configuredProjection;
    private boolean persisted;
    private World attached;
    private String disabledReason = "noch nicht gestartet";
    /** Nach jeder Reise (Hauptthread): Name und Ziel – im Plugin der Eintrag in die Reisepunkte. */
    private BiConsumer<String, Location> arrived = (name, where) -> { };

    /** @param info Nutzertexte (im Plugin Texts::info) */
    public EarthService(JavaPlugin plugin, BiConsumer<CommandSender, String> info) {
        this.plugin = plugin;
        this.info = info;
        this.visits = new VisitStore(dir().resolve("besuche.json"));
    }

    /** Wer nach einer Reise benachrichtigt wird (Reisepunkte); vor start() setzen. */
    public void onArrival(BiConsumer<String, Location> listener) {
        this.arrived = listener;
    }

    private Path dir() {
        return plugin.getDataFolder().toPath().resolve("erde");
    }

    /** Beim Plugin-Start nach dem Gelände: Projektion festlegen, Höhenquellen anlegen, Welt suchen, Populator anhängen. */
    public void start() {
        Config c = Config.from(plugin.getConfig());
        configuredProjection = c.projection();
        World w = findWorld();
        ProjectionChoice.Result r;
        try {
            r = ProjectionChoice.resolve(dir().resolve("welt.json"), c.projection(), w != null);
        } catch (IOException | IllegalArgumentException e) {
            // fail-closed: lieber keine Erdwelt als Chunks nach einer geratenen Karte.
            disabledReason = e.getMessage();
            LOG.log(Level.SEVERE, "erde | aus | grund=" + e.getMessage(), e);
            return;
        }
        if (r.warning() != null) {
            LOG.warning(() -> "erde | projektion_abweichend | " + r.warning());
        }
        if (r.written()) {
            LOG.info(() -> "erde | projektion_festgelegt | projektion=" + r.name() + " datei=erde/welt.json");
        }
        projection = r.projection();
        projectionName = r.name();
        persisted = Files.isRegularFile(dir().resolve("welt.json"));
        Path heights = plugin.getDataFolder().toPath().resolve("höhen");
        ElevationTiles fine = new ElevationTiles(heights, FINE_ZOOM, c.tilesInMemory());
        ElevationTiles coarse = new ElevationTiles(heights, COARSE_ZOOM, COARSE_CAPACITY);
        ElevationTiles far = new ElevationTiles(heights, FAR_ZOOM, FAR_CAPACITY);
        HeightModel model = new HeightModel(new ReliefField(new TileRelief(coarse), TileRelief.far(far)),
                c.seaDepthMax());
        // Senken (WaterTable) auf denselben Zoom-7-Kacheln wie die ferne Stufe des Bezugs: Die liegen beim Erkunden
        // ohnehin schon auf der Platte (700-km-Fenster), es kommt kaum ein Download dazu.
        // Seeflächen im geschlossenen Becken prüfen ein Fenster aus Rohpixeln derselben feinen Kacheln (WaterTable).
        WaterTable water = c.basins() ? new WaterTable(new BasinMap(far::tile, FAR_ZOOM, BASIN_DEEP_SEED,
                BASIN_SHALLOW, BASIN_MAX_PIXELS)::open, fine::tile, FINE_ZOOM) : null;
        Elevation fineWithCorners = new Elevation() {
            @Override
            public double at(double lat, double lon) throws IOException {
                return fine.elevation(lat, lon);
            }

            @Override
            public double at(double lat, double lon, double[] corners) throws IOException {
                return fine.elevation(lat, lon, corners);
            }
        };
        terrain = new EarthTerrain(projection, fineWithCorners, coarse::elevation, model, c.bedrockDepth(),
                EarthTerrain.DEFAULT_MIN_Y, EarthTerrain.DEFAULT_MAX_Y, water);
        LOG.info(() -> "erde | senken | an=" + c.basins());
        disabledReason = null;
        visits.load();
        if (plugin.getConfig().getBoolean("erde.neu-malen", true)) {
            EstimatedChunks store = new EstimatedChunks(dir().resolve("geschätzt.txt"));
            try {
                store.load();
            } catch (IOException e) {
                // fail-open bewusst: ohne Liste wird nur nichts neu gemalt – die Welt selbst ist davon unberührt.
                LOG.log(Level.WARNING, "erde | geschätzt_lesen_fehlgeschlagen | grund=" + e.getMessage(), e);
            }
            repaint = new EarthRepaint(plugin, store, pool, this::world, () -> terrain);
            repaint.start();
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (world() == null) {
            LOG.info(() -> "erde | welt_fehlt | erwartet=" + expectedName()
                    + " – /bm gelaende einrichten, dann Neustart");
        }
    }

    public void shutdown() {
        // Reisen dürfen abbrechen; die letzten Stellen schreibt VisitStore.close() noch synchron (Prüferbefund
        // 2026-10-28: vorher verwarf shutdownNow() ein eingereihtes Speichern).
        pool.shutdownNow();
        visits.close();
        if (repaint != null) {
            repaint.stop();
        }
    }

    private static String expectedName() {
        return Bukkit.getWorlds().isEmpty() ? "?" + WORLD_SUFFIX : Bukkit.getWorlds().get(0).getName() + WORLD_SUFFIX;
    }

    private static World findWorld() {
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        World w = Bukkit.getWorld(expectedName());
        if (w == null || w.getMaxHeight() - w.getMinHeight() < HIGH_WORLD_MIN_HEIGHT) {
            return null;
        }
        return w;
    }

    @Override
    public World world() {
        if (terrain == null) {
            return null;
        }
        World w = findWorld();
        if (w != null && attached != w) {
            attach(w);
        }
        return w;
    }

    private void attach(World w) {
        if (!persisted) {
            // Die Welt ist erst jetzt da (sonst schrieb start() schon): Wahl jetzt festschreiben.
            try {
                ProjectionChoice.resolve(dir().resolve("welt.json"), configuredProjection, true);
                persisted = true;
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(Level.WARNING, "erde | festschreiben_fehlgeschlagen | grund=" + e.getMessage(), e);
            }
        }
        if (terrain.minY() != w.getMinHeight() || terrain.maxY() != w.getMaxHeight()) {
            terrain = terrain.withRange(w.getMinHeight(), w.getMaxHeight());
        }
        EarthRepaint r = repaint;
        w.getPopulators().add(new EarthPopulator(terrain, r != null ? r::remember : null));
        w.setSpawnFlags(false, true);
        attached = w;
        LOG.info(() -> "erde | welt | welt=" + w.getName() + " y=" + w.getMinHeight() + ".." + (w.getMaxHeight() - 1)
                + " projektion=" + projectionName);
    }

    @Override
    public EarthProjection projection() {
        return projection;
    }

    @Override
    public HeightField ground() {
        return terrain;
    }

    /** Grund, warum es keine Erdwelt gibt – oder null. */
    private String missing() {
        if (terrain == null) {
            return "Die Erdwelt ist abgeschaltet: " + disabledReason + " – siehe Serverlog.";
        }
        if (world() == null) {
            return "Die Erdwelt gibt es noch nicht: /bm gelaende einrichten, dann den Server neu starten (Welt "
                    + expectedName() + ").";
        }
        return null;
    }

    private boolean isEarth(World w) {
        return w != null && attached != null && w.getUID().equals(attached.getUID());
    }

    // ---------------- /bm erde ----------------

    /** In die Erdwelt: an die zuletzt besuchte Stelle, sonst nach Hepberg. */
    public void goHome(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            info.accept(sender, "Nur im Spiel – /bm erde bringt dich selbst in die Erdwelt.");
            return;
        }
        String why = missing();
        if (why != null) {
            info.accept(sender, why);
            return;
        }
        World w = world();
        if (isEarth(p.getWorld())) {
            info.accept(sender, "Du bist schon in der Erdwelt – /bm reise <Ort|lat,lon> oder /bm wo.");
            return;
        }
        double[] v = visits.get(p.getUniqueId());
        if (v != null) {
            p.teleport(new Location(w, v[0], v[1], v[2], (float) v[3], (float) v[4]));
            info.accept(sender, "Zurück an deine letzte Stelle in der Erdwelt (" + geoText(v[0], v[2]) + ").");
            return;
        }
        travelTo(p, CompletableFuture.completedFuture(new OsmClient.Place("Hepberg", HOME_LAT, HOME_LON, 0)),
                "Hepberg");
    }

    // ---------------- /bm reise ----------------

    /** Ort suchen (Nominatim oder lat,lon), Kacheln im Hintergrund vorladen, dann auf die Oberfläche teleportieren. */
    public void travel(CommandSender sender, OsmClient osm, String query) {
        if (!(sender instanceof Player p)) {
            info.accept(sender, "Nur im Spiel – /bm reise teleportiert dich selbst.");
            return;
        }
        String why = missing();
        if (why != null) {
            info.accept(sender, why);
            return;
        }
        OsmClient.Place direct = target(query);
        if (direct != null) {
            travelTo(p, CompletableFuture.completedFuture(direct), query);
            return;
        }
        if (OsmClient.looksLikeCoordinates(query)) {
            // Nominatim nie mit reinen Zahlen fragen: Das gäbe einen Zufallstreffer statt einer klaren Absage.
            info.accept(sender, "Koordinaten außerhalb der Erde: Breite −90 bis 90, Länge −180 bis 180.");
            return;
        }
        info.accept(sender, "Suche „" + query + "“ …");
        travelTo(p, osm.resolve(query), query);
    }

    /**
     * Ziel aus „lat, lon“ oder null (dann Ortsname). Vorher ging /bm reise direkt an OsmClient.resolve, das nur bis
     * 85° Breite Koordinaten annimmt (Grenze der Mercator-Kacheln im Gelände je Ort) – Südpol oder Nordspitzbergen
     * landeten als Freitext bei Nominatim (Prüferbefund 2026-10-28). Die Erdwelt deckt beide Pole ab; Höhen jenseits
     * 85,05° liefert ElevationTiles aus der Randzeile.
     */
    static OsmClient.Place target(String query) {
        return OsmClient.coordinates(query, MAX_TARGET_LAT);
    }

    /** Ergebnis der Reisevorbereitung. */
    private record Arrival(OsmClient.Place place, double x, double z, EarthTerrain.Column column,
            EarthMetrics.Local local) {
    }

    private void travelTo(Player p, CompletableFuture<OsmClient.Place> place, String label) {
        UUID id = p.getUniqueId();
        if (!travelling.add(id)) {
            info.accept(p, "Deine letzte Reise läuft noch – einen Moment.");
            return;
        }
        World w = world();
        int radius = (w.getViewDistance() + 1) * 16;
        place.thenComposeAsync(pl -> prepare(pl, radius), pool)
                .whenComplete((a, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    travelling.remove(id);
                    if (err != null) {
                        Throwable c = err.getCause() != null ? err.getCause() : err;
                        LOG.log(Level.WARNING, "erde | reise_fehlgeschlagen | ziel=" + label, c);
                        info.accept(p, "Das ging nicht: " + c.getMessage());
                        return;
                    }
                    arrive(p, a);
                }));
    }

    /** Hintergrund: Zielpunkt, Kacheln und Höhenbezug rund um das Ziel laden (parallel), dann das Ziel messen. */
    private CompletableFuture<Arrival> prepare(OsmClient.Place place, int radius) {
        EarthProjection proj = projection;
        EarthTerrain t = terrain;
        double[] xz = proj.toWorld(place.lat(), place.lon());
        List<CompletableFuture<EarthTerrain.Column>> jobs = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += PRELOAD_STEP) {
            for (int dz = -radius; dz <= radius; dz += PRELOAD_STEP) {
                double px = xz[0] + dx;
                double pz = xz[1] + dz;
                jobs.add(CompletableFuture.supplyAsync(() -> t.column(px, pz), pool));
            }
        }
        return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).thenApplyAsync(v -> {
            double bx = Math.floor(xz[0]) + 0.5;
            double bz = Math.floor(xz[1]) + 0.5;
            EarthTerrain.Column c = t.column(bx, bz);
            if (c.estimated()) {
                // fail-closed für den Teleport: sonst stünde der Spieler auf geratenem Gelände (und der Chunk bliebe so).
                throw new IllegalStateException("Höhendaten für " + place.name() + " sind nicht ladbar (Netz?) – kein "
                        + "Teleport. Später noch einmal versuchen.");
            }
            EarthMetrics.Local local = c.gap() ? null : EarthMetrics.local(proj, c.lat(), c.lon());
            return new Arrival(place, bx, bz, c, local);
        }, pool);
    }

    private void arrive(Player p, Arrival a) {
        World w = world();
        if (w == null) {
            info.accept(p, "Die Erdwelt ist weg – siehe Serverlog.");
            return;
        }
        EarthTerrain.Column c = a.column();
        int y = (c.underwater() ? c.seaY() : c.top()) + 1;
        float yaw = a.local() != null ? EarthMetrics.yawTowardsNorth(a.local()) : 180f;
        Location target = new Location(w, a.x(), y, a.z(), yaw, 0f);
        p.teleport(target);
        String where = shortName(a.place().name());
        // Jedes Reiseziel wird ein Reisepunkt (/bm orte) – Wunsch 2026-10-28.
        arrived.accept(where, target);
        String height = c.gap() ? "Meer (Lücke im Netz)"
                : String.format(Locale.GERMAN, "echte Höhe %.0f m, y %d%s", c.realElevation(), c.top(),
                c.shift() >= 0.5 ? String.format(Locale.GERMAN, " (Bezug −%.0f m)", c.shift()) : "");
        String sea = c.underwater() ? " Du schwimmst – hier ist Wasser." : "";
        String clip = clipText(c);
        info.accept(p, String.format(Locale.GERMAN, "%s: %.5f, %.5f – %s. Blick nach geografisch Nord.%s%s", where,
                a.place().lat(), a.place().lon(), height, sea, clip));
        LOG.info(() -> String.format(Locale.ROOT, "erde | reise | spieler=%s lat=%.5f lon=%.5f x=%.0f z=%.0f y=%d "
                + "höhe=%.0f bezug=%.0f", p.getName(), a.place().lat(), a.place().lon(), a.x(), a.z(), y,
                c.realElevation(), c.shift()));
    }

    // ---------------- /bm wo ----------------

    /** lat/lon, echte Höhe, Bezug, Maßstab und Nordrichtung an der eigenen Stelle bzw. an (x, z). */
    public void where(CommandSender sender, Double x, Double z) {
        if (terrain == null) {
            info.accept(sender, missing());
            return;
        }
        double qx;
        double qz;
        if (x != null && z != null) {
            qx = x;
            qz = z;
        } else if (sender instanceof Player p && isEarth(p.getWorld())) {
            qx = p.getLocation().getX();
            qz = p.getLocation().getZ();
        } else {
            info.accept(sender, "Du bist nicht in der Erdwelt – /bm wo <x> <z> oder erst /bm erde.");
            return;
        }
        EarthProjection proj = projection;
        EarthTerrain t = terrain;
        CompletableFuture.supplyAsync(() -> {
            EarthTerrain.Column c = t.column(Math.floor(qx) + 0.5, Math.floor(qz) + 0.5);
            EarthMetrics.Local l = c.gap() ? null : EarthMetrics.local(proj, c.lat(), c.lon());
            return whereLines(qx, qz, c, l, projectionName);
        }, pool).whenComplete((lines, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                Throwable c = err.getCause() != null ? err.getCause() : err;
                LOG.log(Level.WARNING, "erde | wo_fehlgeschlagen", c);
                info.accept(sender, "Das ging nicht: " + c.getMessage());
                return;
            }
            lines.forEach(line -> info.accept(sender, line));
        }));
    }

    /** Ausgabe von /bm wo, ohne Bukkit testbar. */
    static List<String> whereLines(double x, double z, EarthTerrain.Column c, EarthMetrics.Local l,
            String projectionName) {
        List<String> out = new ArrayList<>();
        String pos = String.format(Locale.GERMAN, "x %.0f, z %.0f", Math.floor(x), Math.floor(z));
        if (c.gap()) {
            out.add(pos + ": Lücke im Netz (" + projectionName + ") – hier ist Meer, keine Stelle der Erde.");
            return out;
        }
        out.add(String.format(Locale.GERMAN, "%s = %.5f° %s, %.5f° %s (%s)", pos, Math.abs(c.lat()),
                c.lat() >= 0 ? "N" : "S", Math.abs(c.lon()), c.lon() >= 0 ? "O" : "W", projectionName));
        String seabed = c.realElevation() < c.elevation() ? String.format(Locale.GERMAN,
                " (Meeresboden auf %.0f m angehoben)", c.elevation()) : "";
        String water = c.surfaceWater() ? " · Wasserfläche" : c.dryBasin() ? " · trockene Senke (kein Meer)" : "";
        out.add(String.format(Locale.GERMAN, "Echte Höhe %.0f m%s · Bezug %.0f m · Oberfläche y %d%s%s%s",
                c.realElevation(), seabed, c.shift(), c.top(), water,
                c.estimated() ? " · GESCHÄTZT (Daten fehlen)" : "", clipText(c)));
        if (l != null) {
            out.add(String.format(Locale.GERMAN, "Maßstab: 1 m Nord-Süd = %.3f Blöcke (h), 1 m Ost-West = %.3f Blöcke "
                    + "(k)", l.h(), l.k()));
            out.add(String.format(Locale.GERMAN, "Geografisch Nord zeigt hier nach %s (%.1f° %s von −z).",
                    compass(l.northAzimuth()), Math.abs(l.northAzimuth()),
                    l.northAzimuth() >= 0 ? "im Uhrzeigersinn" : "gegen den Uhrzeigersinn"));
        }
        return out;
    }

    /** Minecraft-Richtung zu einem Azimut ab −z im Uhrzeigersinn. */
    static String compass(double az) {
        String[] names = {"−z (Minecraft-Nord)", "+x −z (Nordost)", "+x (Minecraft-Ost)", "+x +z (Südost)",
            "+z (Minecraft-Süd)", "−x +z (Südwest)", "−x (Minecraft-West)", "−x −z (Nordwest)"};
        int i = (int) Math.floorMod(Math.round(az / 45.0), 8L);
        return names[i];
    }

    private static String clipText(EarthTerrain.Column c) {
        if (c.clippedTop()) {
            return " Achtung: Gipfel an der Weltdecke gekappt.";
        }
        if (c.clippedBottom()) {
            return " Achtung: Talgrund am Weltboden angehoben.";
        }
        return "";
    }

    private String geoText(double x, double z) {
        double[] g = projection.toGeo(x, z);
        return g == null ? "Meer" : String.format(Locale.GERMAN, "%.4f, %.4f", g[0], g[1]);
    }

    private static String shortName(String display) {
        int comma = display.indexOf(',');
        return comma > 0 && !display.matches("^-?\\d.*") ? display.substring(0, comma) : display;
    }

    // ---------------- letzte Stelle je Spieler ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (isEarth(e.getFrom().getWorld()) && (to == null || !isEarth(to.getWorld()))) {
            remember(e.getPlayer().getUniqueId(), e.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        if (isEarth(e.getPlayer().getWorld())) {
            remember(e.getPlayer().getUniqueId(), e.getPlayer().getLocation());
        }
    }

    private void remember(UUID id, Location l) {
        visits.remember(id, new double[] {l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()});
    }
}
