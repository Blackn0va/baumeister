package com.blackn0va.baumeister;

import com.blackn0va.baumeister.screen.ScreenContent;
import com.blackn0va.baumeister.screen.ScreenImages;
import com.blackn0va.baumeister.screen.ScreenPalette;
import com.blackn0va.baumeister.screen.ScreenRequest;
import com.blackn0va.baumeister.screen.ScreenWall;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Bildschirm mit bewegten Bildern aus Karten in Rahmen (Wunsch 2026-11-07).
 *
 * Aufbau: Rückwand (schwarzer Beton, nur in Luft; vorhandene feste Blöcke werden mitbenutzt und nie entfernt), davor
 * unsichtbare leuchtende Rahmen, je Rahmen eine Karte mit eigenem Zeichner (Tile) für eine 128 × 128-Kachel. Inhalte:
 * ScreenContent (Animation, Uhr, Laufschrift) und ScreenImages (Bild/GIF aus plugins/Baumeister/bildschirm/).
 *
 * Last und Leitung:
 * - Bilder rechnet EIN Hintergrund-Faden (worker), der Server-Thread kopiert nur fertige Kacheln (setPixel nur für
 *   geänderte Punkte; die Karte schickt nur den geänderten Ausschnitt).
 * - Gerechnet wird nur, wenn ein Spieler in VIEW_RANGE Blöcken ist; sonst steht das Bild.
 * - Bildrate nach ScreenContent.fps (Budget 30 Karten-Bilder/s je Bildschirm) – extern verbundene Spieler.
 * Schutz (ASVS V4.1.3, OWASP A01): Bauen nur mit baumeister.bauen und nur, wo der Spieler selbst bauen dürfte
 * (simulierter BlockPlaceEvent je Block, WorldGuard/PlotSquared); Rahmen fest, unzerstörbar, Karten nicht entnehmbar;
 * Entfernen nur Besitzer oder Admin (/bm bildschirm weg). Dateien nur aus dem eigenen Ordner, Namen geprüft
 * (ScreenImages.safeName) – kein Netz.
 *
 * Gespeichert in bildschirme.yml (Karten-IDs, Rahmen, gesetzte Rückwand); nach einem Neustart hängt
 * MapInitializeEvent die Zeichner wieder an (wie Minimap).
 *
 * Threads: Bukkit im Hauptthread; worker rechnet Bilder und dekodiert GIFs, schreibt nur die volatilen Felder des
 * Screens. Fehler: kaputte Datei → leere Liste und Warnung (fail-open: Anzeige, kein Schutz); Bau-Fehler → deutscher Satz
 * an den Spieler, angefangene Teile werden zurückgenommen.
 */
final class ScreenService implements Listener {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final String FILE = "bildschirme.yml";
    static final String DIR = "bildschirm";
    static final int MAX_SCREENS = 20;
    static final int MAX_PER_PLAYER = 3;
    static final double VIEW_RANGE = 64;
    static final String TITLE = "BAUMEISTERLAND";

    private final BaumeisterPlugin plugin;
    private final NamespacedKey key;
    private final Map<UUID, Screen> screens = new ConcurrentHashMap<>();
    private final Map<Integer, Tile> tiles = new ConcurrentHashMap<>();
    @SuppressWarnings("deprecation")
    private final ScreenPalette palette = new ScreenPalette(rgb -> MapPalette.matchColor(rgb >> 16 & 0xFF,
            rgb >> 8 & 0xFF, rgb & 0xFF));
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "baumeister-bildschirm");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((th, e) -> LOG.log(Level.WARNING, "bildschirm | faden_fehler", e));
        return t;
    });
    private BukkitTask ticker;

    ScreenService(BaumeisterPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "bildschirm");
    }

    /** Ein Bildschirm. Felder unter „Bild“ schreibt der worker, gelesen von den Zeichnern (Hauptthread). */
    private static final class Screen {
        final UUID id;
        final UUID owner;
        final UUID world;
        final ScreenWall.Facing facing;
        final int px;
        final int py;
        final int pz;
        final int w;
        final int h;
        final ScreenContent.Kind kind;
        final String text;
        final String file;
        final List<Integer> maps = new ArrayList<>();
        final List<UUID> frames = new ArrayList<>();
        final List<int[]> placed = new ArrayList<>();
        // Bild
        volatile byte[] current;
        volatile int version;
        volatile boolean busy;
        volatile List<byte[]> gif;
        volatile List<Integer> gifDelays;
        volatile String lastClock = "";
        int frame;
        long nextAt;

        Screen(UUID id, UUID owner, UUID world, ScreenWall.Facing facing, int px, int py, int pz, int w, int h,
                ScreenContent.Kind kind, String text, String file) {
            this.id = id;
            this.owner = owner;
            this.world = world;
            this.facing = facing;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.w = w;
            this.h = h;
            this.kind = kind;
            this.text = text;
            this.file = file;
        }

        int width() {
            return w * ScreenPalette.TILE;
        }

        int height() {
            return h * ScreenPalette.TILE;
        }

        List<ScreenWall.Slot> slots() {
            return ScreenWall.slots(px, py, pz, facing, w, h);
        }
    }

    private record Tile(Screen screen, int tx, int ty) {
    }

    // ---------------------------------------------------------------- Start und Ende

    void start() {
        load();
        for (Integer id : tiles.keySet()) {
            MapView v = Bukkit.getMap(id);
            if (v != null) {
                attach(v);
            }
        }
        for (Screen s : screens.values()) {
            if (s.kind == ScreenContent.Kind.BILD) {
                loadImage(s);
            }
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
        LOG.info(() -> "bildschirm | bereit | anzahl=" + screens.size());
    }

    void stop() {
        if (ticker != null) {
            ticker.cancel();
        }
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- Bauen und Entfernen

    /** Bildschirm vor dem Spieler bauen. Rückgabe: Satz für den Spieler. */
    String build(Player p, ScreenRequest.Request r) {
        if (!p.hasPermission("baumeister.bauen")) {
            return "Einen Bildschirm bauen darfst du nicht (baumeister.bauen).";
        }
        if (screens.size() >= MAX_SCREENS) {
            return "Auf dem Server hängen schon " + MAX_SCREENS + " Bildschirme – erst einen abbauen.";
        }
        long mine = screens.values().stream().filter(s -> s.owner.equals(p.getUniqueId())).count();
        if (mine >= MAX_PER_PLAYER && !p.hasPermission("baumeister.admin")) {
            return "Du hast schon " + MAX_PER_PLAYER + " Bildschirme. „/bm bildschirm weg“ baut den nächsten ab.";
        }
        Location l = p.getLocation();
        return buildAt(p.getUniqueId(), p.getName(), l, ScreenWall.Facing.ofYaw(l.getYaw()), r, b -> mayBuild(p, b));
    }

    /**
     * Bildschirm für owner bauen, als stünde er bei l mit Blick f. may: darf dort gebaut werden (Spieler: simulierter
     * BlockPlaceEvent; Konsole/Admin über „bei“: immer). Rückgabe: Satz für den Auslöser.
     */
    String buildAt(UUID ownerId, String ownerName, Location l, ScreenWall.Facing f, ScreenRequest.Request r,
            java.util.function.Predicate<Block> may) {
        if (r.kind() == ScreenContent.Kind.BILD && !Files.isRegularFile(imageDir().resolve(r.file()))) {
            return "Die Datei „" + r.file() + "“ gibt es nicht. " + listText();
        }
        World w = l.getWorld();
        List<ScreenWall.Slot> slots = ScreenWall.slots(l.getBlockX(), l.getBlockY(), l.getBlockZ(), f, r.width(),
                r.height());
        // Erst alles prüfen, dann bauen – nichts halb.
        for (Screen o : screens.values()) {
            if (o.world.equals(w.getUID()) && ScreenWall.overlaps(o.slots(), slots)) {
                return "Da hängt schon ein Bildschirm.";
            }
        }
        List<Block> wallToPlace = new ArrayList<>();
        for (ScreenWall.Slot s : slots) {
            Block frame = w.getBlockAt(s.frameX(), s.frameY(), s.frameZ());
            Block wall = w.getBlockAt(s.wallX(), s.wallY(), s.wallZ());
            if (!frame.getType().isAir()) {
                return "Vor dir ist nicht genug Platz (" + r.width() + " × " + r.height() + " frei, 3 Blöcke vor dir).";
            }
            // Rahmen und Bilder sind Entities – der Block bleibt Luft. Ohne diese Prüfung hing ein zweiter Bildschirm
            // auf dem ersten (Testserver 2026-11-07).
            if (!w.getNearbyEntities(org.bukkit.util.BoundingBox.of(frame), e -> e instanceof org.bukkit.entity.Hanging)
                    .isEmpty()) {
                return "Da hängt schon etwas (Rahmen oder Bild).";
            }
            if (wall.getType().isAir()) {
                wallToPlace.add(wall);
            } else if (!wall.getType().isSolid()) {
                return "Hinter dem Bildschirm steht etwas, das ich nicht als Wand nehmen kann (" + wall.getType()
                        .getKey().getKey() + ").";
            }
            if (!may.test(frame)) {
                return "Hier darfst du nicht bauen – also hänge ich auch keinen Bildschirm auf.";
            }
        }
        for (Block b : wallToPlace) {
            if (!may.test(b)) {
                return "Hier darfst du nicht bauen – also hänge ich auch keinen Bildschirm auf.";
            }
        }
        Screen s = new Screen(UUID.randomUUID(), ownerId, w.getUID(), f, l.getBlockX(), l.getBlockY(),
                l.getBlockZ(), r.width(), r.height(), r.kind(), r.text(), r.file());
        for (Block b : wallToPlace) {
            b.setType(Material.BLACK_CONCRETE, false);
            s.placed.add(new int[] {b.getX(), b.getY(), b.getZ()});
        }
        BlockFace toward = switch (f) {
            case NORTH -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.NORTH;
            case EAST -> BlockFace.WEST;
            case WEST -> BlockFace.EAST;
        };
        try {
            for (ScreenWall.Slot slot : slots) {
                MapView v = Bukkit.createMap(w);
                s.maps.add(v.getId());
                tiles.put(v.getId(), new Tile(s, slot.tx(), slot.ty()));
                attach(v);
                ItemStack map = new ItemStack(Material.FILLED_MAP);
                if (map.getItemMeta() instanceof MapMeta mm) {
                    mm.setMapView(v);
                    map.setItemMeta(mm);
                }
                Location at = new Location(w, slot.frameX(), slot.frameY(), slot.frameZ());
                GlowItemFrame frame = w.spawn(at, GlowItemFrame.class, e -> {
                    e.setFacingDirection(toward, true);
                    e.setItem(map, false);
                    e.setFixed(true);
                    e.setVisible(false);
                    e.setInvulnerable(true);
                    e.setPersistent(true);
                    e.getPersistentDataContainer().set(key, PersistentDataType.STRING, s.id.toString());
                });
                s.frames.add(frame.getUniqueId());
            }
        } catch (RuntimeException e) {
            // Rahmen ließ sich nicht aufhängen (z. B. Platz durch Entity belegt): alles zurück.
            LOG.log(Level.WARNING, "bildschirm | bau_fehlgeschlagen | spieler=" + ownerName, e);
            removeParts(s);
            return "Der Bildschirm ließ sich hier nicht aufhängen. Versuch es an einer freien Stelle.";
        }
        screens.put(s.id, s);
        save();
        if (s.kind == ScreenContent.Kind.BILD) {
            loadImage(s);
        }
        LOG.info(() -> "bildschirm | gebaut | spieler=" + ownerName + " was=" + ScreenRequest.describe(r) + " bei="
                + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + " karten=" + s.maps.size());
        return "Hier ist dein Bildschirm (" + ScreenRequest.describe(r) + ")! Abbauen: „/bm bildschirm weg“.";
    }

    /** Nächsten eigenen (Admin: jeden) Bildschirm in 12 Blöcken abbauen. */
    String remove(Player p) {
        Screen best = null;
        double bestD = 12 * 12;
        for (Screen s : screens.values()) {
            if (!s.world.equals(p.getWorld().getUID())) {
                continue;
            }
            ScreenWall.Slot first = s.slots().get(0);
            double d = p.getLocation().distanceSquared(new Location(p.getWorld(), first.frameX(), first.frameY(),
                    first.frameZ()));
            if (d < bestD && (s.owner.equals(p.getUniqueId()) || p.hasPermission("baumeister.admin"))) {
                bestD = d;
                best = s;
            }
        }
        if (best == null) {
            return "In der Nähe hängt kein Bildschirm, den du abbauen darfst.";
        }
        removeParts(best);
        screens.remove(best.id);
        save();
        Screen gone = best;
        LOG.info(() -> "bildschirm | abgebaut | spieler=" + p.getName() + " id=" + gone.id);
        return "Bildschirm abgebaut.";
    }

    private void removeParts(Screen s) {
        for (UUID id : s.frames) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        for (Integer id : s.maps) {
            tiles.remove(id);
        }
        World w = Bukkit.getWorld(s.world);
        if (w != null) {
            for (int[] b : s.placed) {
                Block blk = w.getBlockAt(b[0], b[1], b[2]);
                // Nur zurücknehmen, was noch unser schwarzer Beton ist – nie etwas, das ein Spieler inzwischen setzte.
                if (blk.getType() == Material.BLACK_CONCRETE) {
                    blk.setType(Material.AIR, false);
                }
            }
        }
    }

    private static final java.util.regex.Pattern AT = java.util.regex.Pattern.compile(
            "(?iu)^bei\\s+(\\S+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([nosw])\\b\\s*(.*)$");

    /**
     * /bm bildschirm [weg|liste|…Auftrag], für Admins und die Konsole zusätzlich „bei &lt;welt&gt; &lt;x&gt; &lt;y&gt;
     * &lt;z&gt; &lt;n|o|s|w&gt; &lt;inhalt&gt;“ (wie bauenbei: als stünde man dort und schaute in die Richtung).
     */
    void command(org.bukkit.command.CommandSender sender, String rest) {
        String t = rest == null ? "" : rest.strip();
        java.util.regex.Matcher at = AT.matcher(t);
        if (at.matches()) {
            if (sender instanceof Player pl && !pl.hasPermission("baumeister.admin")) {
                Texts.info(sender, "„bei …“ ist für Admins.");
                return;
            }
            World w = Bukkit.getWorld(at.group(1));
            if (w == null) {
                Texts.info(sender, "Welt „" + at.group(1) + "“ gibt es nicht.");
                return;
            }
            ScreenWall.Facing f = switch (at.group(5).toLowerCase(Locale.ROOT)) {
                case "n" -> ScreenWall.Facing.NORTH;
                case "o" -> ScreenWall.Facing.EAST;
                case "s" -> ScreenWall.Facing.SOUTH;
                default -> ScreenWall.Facing.WEST;
            };
            ScreenRequest.Request r = ScreenRequest.parse("bau einen Bildschirm " + at.group(6));
            UUID owner = sender instanceof Player pl ? pl.getUniqueId() : new UUID(0, 0);
            Texts.info(sender, buildAt(owner, sender.getName(), new Location(w, Integer.parseInt(at.group(2)),
                    Integer.parseInt(at.group(3)), Integer.parseInt(at.group(4))), f, r != null ? r
                    : ScreenRequest.parse("bau einen Bildschirm"), b -> true));
            return;
        }
        if (!(sender instanceof Player p)) {
            Texts.info(sender, "Von der Konsole: /bm bildschirm bei <welt> <x> <y> <z> <n|o|s|w> [inhalt]");
            return;
        }
        if (t.equalsIgnoreCase("weg") || t.equalsIgnoreCase("abbauen") || t.equalsIgnoreCase("entfernen")) {
            Texts.info(p, remove(p));
            return;
        }
        if (t.equalsIgnoreCase("liste") || t.equalsIgnoreCase("dateien")) {
            Texts.info(p, listText());
            return;
        }
        ScreenRequest.Request r = ScreenRequest.parse("bau einen Bildschirm " + t);
        Texts.info(p, build(p, r != null ? r : ScreenRequest.parse("bau einen Bildschirm")));
    }

    private String listText() {
        try (var files = Files.list(imageDir())) {
            List<String> names = files.map(f -> f.getFileName().toString()).filter(ScreenImages::safeName).sorted()
                    .limit(20).toList();
            return names.isEmpty() ? "Noch keine Bilder in plugins/Baumeister/" + DIR + "/."
                    : "Bilder: " + String.join(", ", names) + " – „/bm bildschirm zeig <datei>“.";
        } catch (IOException e) {
            return "Noch keine Bilder in plugins/Baumeister/" + DIR + "/.";
        }
    }

    private Path imageDir() {
        Path d = plugin.getDataFolder().toPath().resolve(DIR);
        try {
            Files.createDirectories(d);
        } catch (IOException e) {
            LOG.warning(() -> "bildschirm | ordner_fehlt | pfad=" + d + " grund=" + e);
        }
        return d;
    }

    private static boolean mayBuild(Player p, Block blk) {
        org.bukkit.event.block.BlockPlaceEvent e = new org.bukkit.event.block.BlockPlaceEvent(blk, blk.getState(),
                blk.getRelative(BlockFace.DOWN), new ItemStack(Material.BLACK_CONCRETE), p, true,
                org.bukkit.inventory.EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(e);
        return !e.isCancelled() && e.canBuild();
    }

    // ---------------------------------------------------------------- Rahmen schützen

    private boolean ours(Entity e) {
        return e instanceof GlowItemFrame f && f.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(HangingBreakEvent e) {
        // Abbauen nur über den Befehl; bricht die Wand weg (PHYSICS), darf der Rahmen fallen.
        if (ours(e.getEntity()) && e.getCause() != HangingBreakEvent.RemoveCause.PHYSICS) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakBy(HangingBreakByEntityEvent e) {
        if (ours(e.getEntity())) {
            e.setCancelled(true);
            if (e.getRemover() instanceof Player p) {
                Texts.info(p, "Das ist ein Bildschirm – abbauen mit „/bm bildschirm weg“.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUse(PlayerInteractEntityEvent e) {
        if (ours(e.getRightClicked())) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    @EventHandler
    public void onMapInit(MapInitializeEvent e) {
        if (tiles.containsKey(e.getMap().getId())) {
            attach(e.getMap());
        }
    }

    private void attach(MapView v) {
        Tile t = tiles.get(v.getId());
        if (t == null) {
            return;
        }
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) {
            if (!(r instanceof TileRenderer)) {
                v.removeRenderer(r);
            }
        }
        if (v.getRenderers().isEmpty()) {
            v.addRenderer(new TileRenderer(t));
        }
        v.setScale(MapView.Scale.CLOSEST);
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        v.setLocked(true);
    }

    /** Eine Kachel: kopiert nur geänderte Punkte (die Karte schickt dann nur den geänderten Ausschnitt). */
    private static final class TileRenderer extends MapRenderer {
        private final Tile tile;
        private final byte[] drawn = new byte[ScreenPalette.TILE * ScreenPalette.TILE];
        private int version = -1;

        TileRenderer(Tile tile) {
            super(false);
            this.tile = tile;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player p) {
            Screen s = tile.screen;
            byte[] f = s.current;
            int v = s.version;
            if (f == null || v == version) {
                return;
            }
            int fullW = s.width();
            int ox = tile.tx * ScreenPalette.TILE;
            int oy = tile.ty * ScreenPalette.TILE;
            boolean first = version < 0;
            for (int y = 0; y < ScreenPalette.TILE; y++) {
                int row = (oy + y) * fullW + ox;
                for (int x = 0; x < ScreenPalette.TILE; x++) {
                    byte b = f[row + x];
                    int i = y * ScreenPalette.TILE + x;
                    if (first || drawn[i] != b) {
                        canvas.setPixel(x, y, b);
                        drawn[i] = b;
                    }
                }
            }
            version = v;
        }
    }

    /** Alle 2 Ticks: fällige Bildschirme mit Zuschauer in der Nähe bekommen ihr nächstes Bild (im worker). */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Screen s : screens.values()) {
            if (s.busy || now < s.nextAt || !watched(s)) {
                continue;
            }
            int frame = s.frame++;
            long delay = 1000L / ScreenContent.fps(s.kind, s.w * s.h);
            if (s.kind == ScreenContent.Kind.BILD) {
                List<Integer> d = s.gifDelays;
                if (d == null) {
                    continue;
                }
                delay = Math.max(delay, d.get(Math.floorMod(frame, d.size())));
            }
            s.nextAt = now + delay;
            s.busy = true;
            try {
                worker.execute(() -> {
                    try {
                        render(s, frame);
                    } finally {
                        s.busy = false;
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                s.busy = false;
            }
        }
    }

    private boolean watched(Screen s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null) {
            return false;
        }
        Location c = new Location(w, s.px, s.py, s.pz);
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(c) <= VIEW_RANGE * VIEW_RANGE) {
                return true;
            }
        }
        return false;
    }

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN);

    /** Im worker: Bild Nummer frame rechnen und setzen. */
    private void render(Screen s, int frame) {
        int w = s.width();
        int h = s.height();
        byte[] out;
        switch (s.kind) {
            case UHR -> {
                LocalDateTime now = LocalDateTime.now();
                String time = now.format(HM);
                if (time.equals(s.lastClock) && s.current != null) {
                    return;
                }
                s.lastClock = time;
                out = palette.convert(ScreenContent.clock(w, h, time, now.format(DAY)));
            }
            case LAUFSCHRIFT -> {
                int scale = ScreenContent.tickerScale(h);
                int fps = ScreenContent.fps(s.kind, s.w * s.h);
                // Etwa 40 Punkte je Sekunde, gleich schnell bei jeder Bildrate.
                int step = Math.max(2, 40 / fps);
                out = palette.convert(ScreenContent.ticker(w, h, s.text.isBlank() ? TITLE : s.text, frame * step,
                        scale));
            }
            case BILD -> {
                List<byte[]> g = s.gif;
                if (g == null || g.isEmpty()) {
                    return;
                }
                out = g.get(Math.floorMod(frame, g.size()));
            }
            default -> out = palette.convert(ScreenContent.animation(w, h, frame, TITLE));
        }
        s.current = out;
        s.version++;
    }

    private void loadImage(Screen s) {
        Path f = imageDir().resolve(s.file);
        if (!ScreenImages.safeName(s.file) || !f.normalize().startsWith(imageDir().normalize())) {
            LOG.warning(() -> "bildschirm | datei_abgelehnt | name=" + s.file);
            return;
        }
        worker.execute(() -> {
            try (InputStream in = Files.newInputStream(f)) {
                ScreenImages.Frames frames = ScreenImages.load(in, s.file, s.width(), s.height());
                List<byte[]> conv = new ArrayList<>(frames.rgb().size());
                for (int[] rgb : frames.rgb()) {
                    conv.add(palette.convert(rgb));
                }
                s.gif = List.copyOf(conv);
                s.gifDelays = frames.delays();
                LOG.info(() -> "bildschirm | bild_geladen | datei=" + s.file + " bilder=" + conv.size());
            } catch (IOException e) {
                LOG.warning(() -> "bildschirm | bild_unlesbar | datei=" + s.file + " grund=" + e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- Speichern

    private Path file() {
        return plugin.getDataFolder().toPath().resolve(FILE);
    }

    private void load() {
        Path f = file();
        if (!Files.exists(f)) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
        ConfigurationSection all = y.getConfigurationSection("bildschirme");
        if (all == null) {
            return;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection c = all.getConfigurationSection(id);
            try {
                Screen s = new Screen(UUID.fromString(id), UUID.fromString(c.getString("besitzer")),
                        UUID.fromString(c.getString("welt")), ScreenWall.Facing.valueOf(c.getString("blick")),
                        c.getInt("x"), c.getInt("y"), c.getInt("z"), c.getInt("breite"), c.getInt("hoch"),
                        ScreenContent.Kind.valueOf(c.getString("inhalt")), c.getString("text", ""),
                        c.getString("datei", ""));
                List<ScreenWall.Slot> slots = s.slots();
                List<Integer> maps = c.getIntegerList("karten");
                for (int i = 0; i < maps.size() && i < slots.size(); i++) {
                    s.maps.add(maps.get(i));
                    tiles.put(maps.get(i), new Tile(s, slots.get(i).tx(), slots.get(i).ty()));
                }
                for (String fr : c.getStringList("rahmen")) {
                    s.frames.add(UUID.fromString(fr));
                }
                for (String b : c.getStringList("wand")) {
                    String[] p = b.split(",");
                    s.placed.add(new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                }
                screens.put(s.id, s);
            } catch (RuntimeException e) {
                // Anzeige, kein Schutz: kaputter Eintrag wird übersprungen (bleibt in der Datei bis zum nächsten Speichern).
                LOG.warning(() -> "bildschirm | eintrag_verworfen | id=" + id + " grund=" + e);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Screen s : screens.values()) {
            String p = "bildschirme." + s.id;
            y.set(p + ".besitzer", s.owner.toString());
            y.set(p + ".welt", s.world.toString());
            y.set(p + ".blick", s.facing.name());
            y.set(p + ".x", s.px);
            y.set(p + ".y", s.py);
            y.set(p + ".z", s.pz);
            y.set(p + ".breite", s.w);
            y.set(p + ".hoch", s.h);
            y.set(p + ".inhalt", s.kind.name());
            y.set(p + ".text", s.text);
            y.set(p + ".datei", s.file);
            y.set(p + ".karten", s.maps);
            y.set(p + ".rahmen", s.frames.stream().map(UUID::toString).toList());
            y.set(p + ".wand", s.placed.stream().map(b -> b[0] + "," + b[1] + "," + b[2]).toList());
        }
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(FILE + ".neu");
            Files.writeString(tmp, y.saveToString());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warning(() -> "bildschirm | speichern_fehlgeschlagen | datei=" + f + " grund=" + e);
        }
    }
}
