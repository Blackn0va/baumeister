package com.blackn0va.baumeister;

import com.blackn0va.baumeister.portal.PortalBook;
import com.blackn0va.baumeister.portal.PortalFrame;
import com.blackn0va.baumeister.warp.WarpBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Portale verbinden (2026-11-15, Wunsch: „einen frischen server einrichten … mit diesen portal hallen“): Ein Admin
 * stellt sich in einen leeren Obsidianrahmen oder schaut hindurch und sagt /bm portal &lt;ziel&gt; [anzeige]. Der
 * Dienst entzündet die Öffnung (Netherportal mit der Achse des Rahmens), hängt ein Schild darüber und schickt jeden,
 * der hineingeht, sofort zum Ziel: zu einem Warp, zum Spawn einer Welt oder zum Spawn der Hauptwelt.
 *
 * Warum Netherportal-Blöcke statt eines unsichtbaren Bereichs: Man sieht, wo es durchgeht, es klingt wie ein Portal,
 * und Minecraft meldet das Betreten selbst (EntityPortalEnterEvent) – kein Bewegungs-Event für jeden Schritt jedes
 * Spielers. An registrierten Portalen nimmt der Dienst den Weg in den Nether weg (PlayerPortalEvent, EntityPortalEvent)
 * und die Zombifizierten Piglins, die ein Netherportal in der Oberwelt sonst spawnen lässt.
 *
 * Sicher landen (fail-closed): Ohne zwei freie Blöcke über festem Boden am Ziel (Nether-Spawn im Gestein, ein Warp in
 * einer zugebauten Ecke) springt niemand – lieber eine Meldung als ein Spieler, der in der Wand erstickt.
 *
 * Gespeichert in plugins/Baumeister/portale.json, atomar wie warps.json; eine unlesbare Datei wird beiseitegelegt.
 *
 * Threads: Hauptthread. Fehler: Schreibfehler werden gemeldet (Spieler und Log), der Stand im Speicher bleibt.
 */
final class PortalService implements Listener {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Zwischen zwei Sprüngen desselben Spielers: Das Betreten meldet Minecraft jeden Tick, solange er drinsteht. */
    private static final long COOLDOWN_MS = 3_000;
    /** So weit sucht der Blick nach einem Rahmen. */
    private static final double REACH = 8;
    /** So weit sucht die Landung über und unter dem Ziel nach festem Boden. */
    private static final int SAFE_SEARCH = 24;

    private final BaumeisterPlugin plugin;
    private final Path file;
    private final NamespacedKey labelKey;
    private final Map<UUID, Long> lastJump = new HashMap<>();
    private PortalBook book = new PortalBook();

    PortalService(BaumeisterPlugin plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("portale.json");
        this.labelKey = new NamespacedKey(plugin, "portal");
    }

    void start() {
        try {
            if (Files.exists(file)) {
                book = PortalBook.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException | IllegalArgumentException e) {
            Path aside = file.resolveSibling("portale.json.defekt-" + System.currentTimeMillis());
            try {
                Files.move(file, aside);
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, "portal | beiseitelegen_fehlgeschlagen | datei=" + file, ex);
            }
            LOG.log(Level.SEVERE, "portal | datei_kaputt | beiseite=" + aside + " – starte ohne Portale", e);
            book = new PortalBook();
        }
        LOG.info(() -> "portal | bereit | portale=" + book.size());
    }

    private boolean save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("portale.json.neu");
            Files.writeString(tmp, book.toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "portal | schreiben_fehlgeschlagen | datei=" + file, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- Befehl

    /**
     * /bm portal &lt;ziel&gt; [anzeige] · /bm portal entfernen · /bm portal liste. Von der Konsole mit Ort:
     * /bm portal &lt;welt&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;ziel&gt; [anzeige] bzw. /bm portal entfernen
     * &lt;welt&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;. Das Recht (baumeister.admin) prüft BaumeisterCommand vorher.
     */
    void command(CommandSender sender, String label, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("liste")) {
            list(sender);
            return;
        }
        boolean remove = args.length >= 2 && args[1].equalsIgnoreCase("entfernen");
        if (sender instanceof Player p) {
            if (args.length < 2) {
                usage(sender, label);
                return;
            }
            PortalFrame.Result r = find(p);
            if (!r.found()) {
                Texts.info(p, "Kein leerer Portalrahmen aus Obsidian gefunden: " + r.reason()
                        + ". Stell dich in die Öffnung oder schau hindurch.");
                return;
            }
            if (remove) {
                remove(p, p.getWorld(), r.opening());
            } else {
                connect(p, p.getWorld(), r.opening(), args[1], rest(args, 2));
            }
            return;
        }
        int at = remove ? 2 : 1;
        if (args.length < at + 4 + (remove ? 0 : 1)) {
            usage(sender, label);
            return;
        }
        World w = Bukkit.getWorld(args[at]);
        if (w == null) {
            Texts.info(sender, "Welt „" + Texts.clean(args[at], 32) + "“ gibt es nicht.");
            return;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(args[at + 1]);
            y = Integer.parseInt(args[at + 2]);
            z = Integer.parseInt(args[at + 3]);
        } catch (NumberFormatException e) {
            Texts.info(sender, "x, y und z müssen ganze Zahlen sein.");
            return;
        }
        PortalFrame.Result r = PortalFrame.at(blocks(w), x, y, z);
        if (!r.found()) {
            Texts.info(sender, "Kein leerer Portalrahmen aus Obsidian bei " + x + " " + y + " " + z + ": "
                    + r.reason() + ".");
            return;
        }
        if (remove) {
            remove(sender, w, r.opening());
        } else {
            connect(sender, w, r.opening(), args[at + 4], rest(args, at + 5));
        }
    }

    private static void usage(CommandSender to, String label) {
        Texts.info(to, "Stell dich in einen leeren Obsidianrahmen oder schau hindurch: /" + label
                + " portal <warp|welt|spawn> [Anzeige]. Weg damit: /" + label + " portal entfernen. Alle: /" + label
                + " portal liste");
    }

    private static String rest(String[] args, int from) {
        if (from >= args.length) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }

    /** Erst die Öffnung, in der er steht, dann der Blick. */
    private PortalFrame.Result find(Player p) {
        PortalFrame.Blocks b = blocks(p.getWorld());
        Location feet = p.getLocation();
        PortalFrame.Result here = PortalFrame.at(b, feet.getBlockX(), feet.getBlockY(), feet.getBlockZ());
        if (here.found()) {
            return here;
        }
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection();
        return PortalFrame.along(b, eye.getX(), eye.getY(), eye.getZ(), d.getX(), d.getY(), d.getZ(), REACH);
    }

    private static PortalFrame.Blocks blocks(World w) {
        return new PortalFrame.Blocks() {
            @Override
            public boolean frame(int x, int y, int z) {
                return w.getBlockAt(x, y, z).getType() == Material.OBSIDIAN;
            }

            @Override
            public boolean open(int x, int y, int z) {
                Material m = w.getBlockAt(x, y, z).getType();
                return m.isAir() || m == Material.NETHER_PORTAL;
            }
        };
    }

    /** Eingabe → Ziel: „spawn“/„lobby“, dann ein Warp, dann eine Welt. */
    Optional<String> targetFor(String input) {
        String k = input.strip();
        if (k.equalsIgnoreCase("spawn") || k.equalsIgnoreCase("lobby")) {
            return Optional.of(PortalBook.SPAWN);
        }
        WarpService warps = plugin.warps();
        if (warps != null && warps.location(k).isPresent()) {
            return Optional.of(PortalBook.WARP + WarpBook.key(k));
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getName().equalsIgnoreCase(k)) {
                return Optional.of(PortalBook.WORLD + w.getName());
            }
        }
        return Optional.empty();
    }

    private void connect(CommandSender by, World w, PortalFrame.Opening o, String input, String shown) {
        Optional<String> target = targetFor(input);
        if (target.isEmpty() || !PortalBook.validTarget(target.get())) {
            List<String> options = new ArrayList<>(List.of("spawn"));
            WarpService warps = plugin.warps();
            if (warps != null) {
                options.addAll(warps.names());
            }
            Bukkit.getWorlds().forEach(x -> options.add(x.getName()));
            Texts.info(by, "Ein Ziel „" + Texts.clean(input, 24) + "“ kenne ich nicht. Möglich: "
                    + String.join(", ", options.stream().limit(12).toList()) + (options.size() > 12 ? " …" : ""));
            return;
        }
        if (resolve(target.get()).flatMap(PortalService::safe).isEmpty()) {
            Texts.info(by, "Am Ziel " + describe(target.get()) + " ist kein sicherer Platz zum Landen (zwei freie "
                    + "Blöcke über festem Boden). Setz dort einen Warp an eine freie Stelle und verbinde das Portal "
                    + "damit.");
            return;
        }
        String text = Texts.clean(shown.isBlank() ? niceName(target.get()) : shown, PortalBook.MAX_LABEL);
        UUID owner = by instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        PortalBook.Portal portal = new PortalBook.Portal(w.getName(), o, target.get(), text, owner, "");
        List<PortalBook.Portal> replaced = book.put(portal);
        replaced.forEach(this::removeLabel);
        light(w, o);
        String labelId = spawnLabel(w, o, text, portal.id());
        book.put(portal.withLabelEntity(labelId));
        boolean saved = save();
        LOG.info(() -> String.format("portal | verbunden | welt=%s x=%d y=%d z=%d achse=%s größe=%dx%d ziel=%s "
                + "ersetzt=%d gespeichert=%s", w.getName(), o.x0(), o.y0(), o.z0(), o.axis(), o.width(), o.height(),
                target.get(), replaced.size(), saved));
        Texts.info(by, "Portal entzündet: → " + text + " (" + describe(target.get()) + "). Wer hineingeht, landet "
                + "dort." + (replaced.isEmpty() ? "" : " Das alte Ziel dieses Rahmens ist ersetzt.")
                + (saved ? "" : " Achtung: portale.json ließ sich nicht schreiben – nach einem Neustart ist es weg."));
    }

    private void remove(CommandSender by, World w, PortalFrame.Opening o) {
        int[] first = o.cells().getFirst();
        Optional<PortalBook.Portal> portal = book.at(w.getName(), first[0], first[1], first[2]);
        if (portal.isEmpty()) {
            Texts.info(by, "Dieser Rahmen ist nicht verbunden.");
            return;
        }
        book.remove(portal.get().id());
        removeLabel(portal.get());
        extinguish(w, portal.get().opening());
        boolean saved = save();
        LOG.info(() -> "portal | entfernt | id=" + portal.get().id() + " gespeichert=" + saved);
        Texts.info(by, "Portal nach " + portal.get().label() + " entfernt, der Rahmen ist wieder leer.");
    }

    private void list(CommandSender to) {
        if (book.size() == 0) {
            Texts.info(to, "Noch kein Portal verbunden. Stell dich in einen leeren Obsidianrahmen: /bm portal <ziel>");
            return;
        }
        Texts.info(to, book.size() + " Portal(e):");
        for (PortalBook.Portal p : book.list()) {
            boolean reachable = resolve(p.target()).flatMap(PortalService::safe).isPresent();
            PortalFrame.Opening o = p.opening();
            Texts.info(to, "→ " + p.label() + " (" + describe(p.target()) + ") bei " + p.world() + " " + o.x0() + " "
                    + o.y0() + " " + o.z0() + (reachable ? "" : " – Ziel fehlt oder ohne sicheren Platz")
                    + (lit(p) ? "" : " – erloschen (Rahmen beschädigt?), in der Öffnung neu verbinden"));
        }
    }

    /**
     * Brennt das Portal noch? Bricht jemand einen Rahmenblock heraus, löscht Minecraft alle Portalblöcke – der Eintrag
     * bleibt, damit ein neu verbundener Rahmen sein Ziel behält. Geprüft nur in geladenen Chunks: Für eine Liste wird
     * nichts geladen, ungeladen gilt als entzündet.
     */
    private static boolean lit(PortalBook.Portal p) {
        World w = Bukkit.getWorld(p.world());
        PortalFrame.Opening o = p.opening();
        if (w == null || !w.isChunkLoaded(o.x0() >> 4, o.z0() >> 4)) {
            return true;
        }
        return w.getBlockAt(o.x0(), o.y0(), o.z0()).getType() == Material.NETHER_PORTAL;
    }

    // ---------------------------------------------------------------- Ziel

    Optional<Location> resolve(String target) {
        if (target.equals(PortalBook.SPAWN)) {
            List<World> worlds = Bukkit.getWorlds();
            return worlds.isEmpty() ? Optional.empty() : Optional.of(centered(worlds.getFirst().getSpawnLocation()));
        }
        if (target.startsWith(PortalBook.WARP)) {
            WarpService warps = plugin.warps();
            return warps == null ? Optional.empty() : warps.location(target.substring(PortalBook.WARP.length()));
        }
        if (target.startsWith(PortalBook.WORLD)) {
            World w = Bukkit.getWorld(target.substring(PortalBook.WORLD.length()));
            return w == null ? Optional.empty() : Optional.of(centered(w.getSpawnLocation()));
        }
        return Optional.empty();
    }

    /** Weltspawn liegt auf der Blockecke; ein Sprung dorthin stellt den Spieler halb in den Nachbarblock. */
    private static Location centered(Location l) {
        Location c = l.clone();
        if (c.getX() == Math.floor(c.getX()) && c.getZ() == Math.floor(c.getZ())) {
            c.add(0.5, 0, 0.5);
        }
        return c;
    }

    /** Zwei freie Blöcke über festem, ungefährlichem Boden – am Ziel oder bis SAFE_SEARCH darüber bzw. darunter. */
    static Optional<Location> safe(Location l) {
        World w = l.getWorld();
        if (w == null) {
            return Optional.empty();
        }
        int bx = l.getBlockX();
        int bz = l.getBlockZ();
        for (int d = 0; d <= SAFE_SEARCH; d++) {
            for (int y : d == 0 ? new int[] {l.getBlockY()} : new int[] {l.getBlockY() + d, l.getBlockY() - d}) {
                if (y <= w.getMinHeight() || y + 1 >= w.getMaxHeight()) {
                    continue;
                }
                if (standable(w.getBlockAt(bx, y - 1, bz), w.getBlockAt(bx, y, bz), w.getBlockAt(bx, y + 1, bz))) {
                    Location out = l.clone();
                    out.setY(y);
                    return Optional.of(out);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean standable(Block below, Block feet, Block head) {
        Material m = below.getType();
        return m.isSolid() && m != Material.MAGMA_BLOCK && m != Material.CACTUS && m != Material.CAMPFIRE
                && m != Material.SOUL_CAMPFIRE && feet.isPassable() && head.isPassable() && !feet.isLiquid()
                && !head.isLiquid() && feet.getType() != Material.FIRE && feet.getType() != Material.SOUL_FIRE
                && feet.getType() != Material.NETHER_PORTAL;
    }

    static String describe(String target) {
        if (target.equals(PortalBook.SPAWN)) {
            return "Spawn der Hauptwelt";
        }
        if (target.startsWith(PortalBook.WARP)) {
            return "Warp " + target.substring(PortalBook.WARP.length());
        }
        return "Spawn der Welt " + target.substring(PortalBook.WORLD.length());
    }

    /** Anzeige ohne eigene Angabe: „Lobby“, der Warpname mit großem Anfang, oder der Weltname. */
    static String niceName(String target) {
        if (target.equals(PortalBook.SPAWN)) {
            return "Lobby";
        }
        String n = target.startsWith(PortalBook.WARP) ? target.substring(PortalBook.WARP.length())
                : target.substring(PortalBook.WORLD.length());
        return n.isEmpty() ? n : n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
    }

    // ---------------------------------------------------------------- Welt

    /** Ohne Physik: Ein Update während des Setzens prüfte sonst einen halb gefüllten Rahmen und löschte ihn. */
    private static void light(World w, PortalFrame.Opening o) {
        Axis axis = o.axis() == PortalFrame.Axis.X ? Axis.X : Axis.Z;
        for (int[] c : o.cells()) {
            BlockData d = Material.NETHER_PORTAL.createBlockData();
            if (d instanceof Orientable or) {
                or.setAxis(axis);
            }
            w.getBlockAt(c[0], c[1], c[2]).setBlockData(d, false);
        }
    }

    private static void extinguish(World w, PortalFrame.Opening o) {
        for (int[] c : o.cells()) {
            Block b = w.getBlockAt(c[0], c[1], c[2]);
            if (b.getType() == Material.NETHER_PORTAL) {
                b.setType(Material.AIR, false);
            }
        }
    }

    /** Schild über dem Rahmen (oberste Rahmenreihe liegt auf y0 + height). */
    private String spawnLabel(World w, PortalFrame.Opening o, String text, String id) {
        double cx = o.axis() == PortalFrame.Axis.X ? o.x0() + o.width() / 2.0 : o.x0() + 0.5;
        double cz = o.axis() == PortalFrame.Axis.X ? o.z0() + 0.5 : o.z0() + o.width() / 2.0;
        Location at = new Location(w, cx, o.y0() + o.height() + 1.3, cz);
        TextDisplay td = w.spawn(at, TextDisplay.class, t -> {
            t.setText("§d§l" + text);
            t.setBillboard(Display.Billboard.CENTER);
            t.setShadowed(true);
            t.setPersistent(true);
            t.getPersistentDataContainer().set(labelKey, PersistentDataType.STRING, id);
        });
        return td.getUniqueId().toString();
    }

    private void removeLabel(PortalBook.Portal p) {
        if (p.labelEntity().isEmpty()) {
            return;
        }
        try {
            Entity e = Bukkit.getEntity(UUID.fromString(p.labelEntity()));
            if (e != null) {
                e.remove();
            } else {
                LOG.info(() -> "portal | schild_nicht_geladen | id=" + p.id() + " – bleibt stehen, bis der Chunk "
                        + "geladen ist und es von Hand entfernt wird");
            }
        } catch (IllegalArgumentException e) {
            LOG.warning(() -> "portal | schild_uuid_kaputt | id=" + p.id());
        }
    }

    // ---------------------------------------------------------------- Ereignisse

    @EventHandler
    public void onEnter(EntityPortalEnterEvent e) {
        if (!(e.getEntity() instanceof Player p) || book.size() == 0) {
            return;
        }
        Location at = e.getLocation();
        Optional<PortalBook.Portal> portal = book.at(at.getWorld().getName(), at.getBlockX(), at.getBlockY(),
                at.getBlockZ());
        if (portal.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastJump.get(p.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            return;
        }
        lastJump.put(p.getUniqueId(), now);
        PortalBook.Portal target = portal.get();
        // Im nächsten Tick: Das Ereignis kommt mitten aus der Kollisionsprüfung der Entity.
        Bukkit.getScheduler().runTask(plugin, () -> jump(p, target));
    }

    private void jump(Player p, PortalBook.Portal portal) {
        if (!p.isOnline()) {
            return;
        }
        Optional<Location> to = resolve(portal.target()).flatMap(PortalService::safe);
        if (to.isEmpty()) {
            Texts.info(p, "Dieses Portal führt gerade nirgendwohin: " + describe(portal.target())
                    + " gibt es nicht mehr oder dort ist kein sicherer Platz.");
            LOG.warning(() -> "portal | ziel_fehlt | id=" + portal.id() + " ziel=" + portal.target());
            return;
        }
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
        p.teleport(to.get(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.playSound(to.get(), Sound.BLOCK_PORTAL_TRAVEL, 0.25f, 1.4f);
        LOG.info(() -> "portal | sprung | spieler=" + p.getName() + " ziel=" + portal.target());
    }

    /** Den Weg in den Nether gibt es an verbundenen Portalen nicht – der Sprung ging schon zum Ziel. */
    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL && registeredAt(e.getFrom())) {
            e.setCancelled(true);
        }
    }

    /** Gegenstände und Tiere bleiben, wo sie sind, statt durch ein Lobby-Portal in den Nether zu fallen. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (registeredAt(e.getFrom())) {
            e.setCancelled(true);
        }
    }

    /** Netherportale lassen in der Oberwelt Zombifizierte Piglins spawnen – nicht in der Lobby. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NETHER_PORTAL && registeredAt(e.getLocation())) {
            e.setCancelled(true);
        }
    }

    /** Füße, Kopf oder der Block darunter in einem verbundenen Portal. */
    private boolean registeredAt(Location l) {
        if (l == null || l.getWorld() == null || book.size() == 0) {
            return false;
        }
        String w = l.getWorld().getName();
        for (int dy = -1; dy <= 1; dy++) {
            if (book.at(w, l.getBlockX(), l.getBlockY() + dy, l.getBlockZ()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Für die Vervollständigung: Ziele, die es gerade gibt. */
    List<String> targets() {
        List<String> out = new ArrayList<>(List.of("spawn", "entfernen", "liste"));
        WarpService warps = plugin.warps();
        if (warps != null) {
            out.addAll(warps.names());
        }
        Bukkit.getWorlds().forEach(w -> out.add(w.getName()));
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastJump.remove(e.getPlayer().getUniqueId());
    }
}
