package com.blackn0va.baumeister;

import com.blackn0va.baumeister.warp.WarpBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Öffentliche Warps (/warp, /warps, /setwarp, /delwarp) – ersetzt GWarp (2026-11-05). Beim ersten Start übernimmt der
 * Dienst plugins/GWarp/Warps.data (auf dev01: farmwelt, plotwelt), danach gilt nur noch plugins/Baumeister/warps.json.
 *
 * Abklingzeit zwischen zwei Sprüngen wie bei GWarp (10 s), Recht baumeister.warp.sofort hebt sie auf. Vera bringt dich
 * auf Zuruf ebenfalls zu einem Warp („bring mich zur Farmwelt“), mit demselben Recht wie /warp.
 *
 * Schreiben atomar (warps.json.neu → umbenennen). Eine unlesbare warps.json wird beiseitegelegt (…defekt-&lt;ms&gt;),
 * nicht stumm überschrieben; der Dienst startet dann leer und meldet das (SEVERE).
 *
 * Threads: Hauptthread. Fehler: Schreibfehler werden gemeldet (Spieler und Log), der Stand im Speicher bleibt.
 */
final class WarpService implements CommandExecutor, TabCompleter {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    record Settings(boolean enabled, int perPlayer, long cooldownMillis) {

        static Settings from(ConfigurationSection root) {
            if (root == null) {
                return new Settings(true, 1, 10_000);
            }
            return new Settings(root.getBoolean("warp.an", true),
                    Math.clamp(root.getInt("warp.anzahl-je-spieler", 1), 0, 100),
                    Math.clamp(root.getLong("warp.abklingzeit-sekunden", 10), 0, 3600) * 1000);
        }
    }

    private final BaumeisterPlugin plugin;
    private final Settings settings;
    private final Path file;
    private final Map<UUID, Long> lastJump = new HashMap<>();
    private WarpBook book = new WarpBook();

    WarpService(BaumeisterPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.file = plugin.getDataFolder().toPath().resolve("warps.json");
    }

    void start() {
        try {
            if (Files.exists(file)) {
                book = WarpBook.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            } else {
                importGWarp();
            }
        } catch (IOException | IllegalArgumentException e) {
            if (Files.exists(file)) {
                Path aside = file.resolveSibling("warps.json.defekt-" + System.currentTimeMillis());
                try {
                    Files.move(file, aside);
                } catch (IOException ex) {
                    LOG.log(Level.SEVERE, "warp | beiseitelegen_fehlgeschlagen | datei=" + file, ex);
                }
                LOG.log(Level.SEVERE, "warp | datei_kaputt | beiseite=" + aside + " – starte ohne Warps", e);
            } else {
                LOG.log(Level.SEVERE, "warp | gwarp_übernahme_fehlgeschlagen | – starte ohne Warps", e);
            }
            book = new WarpBook();
        }
        for (String c : List.of("warp", "warps", "setwarp", "delwarp")) {
            PluginCommand pc = plugin.getCommand(c);
            if (pc == null) {
                throw new IllegalStateException("Befehl " + c + " fehlt in plugin.yml");
            }
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
        LOG.info(() -> "warp | bereit | an=" + settings.enabled() + " warps=" + book.size() + " je_spieler="
                + settings.perPlayer());
        // Essentials hat auch /warp und besitzt den Namen in Bukkit (Log dev01 2026-11-05 19:45: „gehört Essentials“).
        // Gemessen per javap am EssentialsX-2.22.0.jar: Essentials.onCommand fragt isCommandOverridden und reicht
        // sonst über AlternativeCommandsHandler an das andere Plugin weiter – /warp landet so beim Baumeister, solange
        // warp nicht in Essentials' overridden-commands steht. Wer es dort einträgt, bekommt die Essentials-Warps.
        Bukkit.getScheduler().runTask(plugin, () -> {
            PluginCommand owner = Bukkit.getPluginCommand("warp");
            String by = owner == null ? "niemand" : owner.getPlugin().getName();
            LOG.info(() -> "warp | befehl | /warp registriert von " + by + (by.equals("Essentials")
                    ? " – Essentials reicht weiter (warp nicht in overridden-commands), sonst /baumeister:warp" : ""));
        });
    }

    private void importGWarp() throws IOException {
        Path g = plugin.getDataFolder().toPath().getParent().resolve("GWarp").resolve("Warps.data");
        if (!Files.exists(g)) {
            return;
        }
        WarpBook.Imported imp = WarpBook.importGWarp(Files.readString(g, StandardCharsets.UTF_8));
        book = imp.book();
        save();
        LOG.info(() -> "warp | gwarp_übernommen | warps=" + imp.book().size() + " übersprungen=" + imp.skipped()
                + " quelle=" + g);
    }

    private boolean save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("warps.json.neu");
            Files.writeString(tmp, book.toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "warp | schreiben_fehlgeschlagen | datei=" + file, e);
            return false;
        }
    }

    /** Ort eines Warps, leer = unbekannt oder Welt nicht geladen (für Vera). */
    Optional<Location> location(String name) {
        return book.find(name).map(w -> {
            World world = Bukkit.getWorld(w.world());
            return world == null ? null : new Location(world, w.x(), w.y(), w.z(), w.yaw(), w.pitch());
        });
    }

    List<String> names() {
        return book.list().stream().map(WarpBook.Warp::name).toList();
    }

    /** Springt; true = gesprungen. Meldungen gehen an p. */
    boolean jump(Player p, String name) {
        if (!settings.enabled()) {
            Texts.info(p, "Warps sind auf diesem Server abgeschaltet (warp.an).");
            return false;
        }
        Optional<WarpBook.Warp> w = book.find(name);
        if (w.isEmpty()) {
            Texts.info(p, "Den Warp „" + Texts.clean(name, 24) + "“ gibt es nicht. Alle: /warps");
            return false;
        }
        Location to = location(name).orElse(null);
        if (to == null) {
            Texts.info(p, "Die Welt " + w.get().world() + " von „" + w.get().name() + "“ ist gerade nicht geladen.");
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastJump.get(p.getUniqueId());
        if (last != null && now - last < settings.cooldownMillis() && !p.hasPermission("baumeister.warp.sofort")) {
            Texts.info(p, "Noch " + ((settings.cooldownMillis() - (now - last)) / 1000 + 1) + " s bis zum nächsten "
                    + "Warp.");
            return false;
        }
        lastJump.put(p.getUniqueId(), now);
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
        p.teleport(to);
        p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        to.getWorld().spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
        book.visit(name, p.getUniqueId());
        save();
        LOG.info(() -> "warp | gesprungen | spieler=" + p.getName() + " warp=" + w.get().name());
        Texts.info(p, "Willkommen bei " + w.get().name() + " (von " + w.get().ownerName() + ")!");
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("warps") || (cmd.equals("warp") && args.length == 0)) {
            list(sender);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Warps gehen nur im Spiel.");
            return true;
        }
        if (args.length == 0) {
            Texts.info(p, "/" + cmd + " <name>");
            return true;
        }
        String name = args[0];
        boolean admin = p.hasPermission("baumeister.admin");
        switch (cmd) {
            case "warp" -> jump(p, name);
            case "setwarp" -> {
                int limit = p.hasPermission("baumeister.warp.unbegrenzt") ? 0 : settings.perPlayer();
                Location l = p.getLocation();
                WarpBook.Result r = book.set(new WarpBook.Warp(name.strip(), p.getUniqueId(), p.getName(),
                        l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), 0), limit, admin);
                String text = switch (r) {
                    case OK -> "Warp „" + name + "“ gesetzt – jeder kommt jetzt mit /warp " + name + " hierher.";
                    case UPDATED -> "Warp „" + name + "“ steht jetzt hier.";
                    case BAD_NAME -> "Warpnamen: 1–20 Zeichen aus Buchstaben, Ziffern, - und _.";
                    case LIMIT -> "Du hast schon " + settings.perPlayer() + " Warp(s). Lösch erst einen mit /delwarp.";
                    case TAKEN -> "„" + name + "“ gehört schon jemand anderem.";
                    default -> "Das ging nicht.";
                };
                if ((r == WarpBook.Result.OK || r == WarpBook.Result.UPDATED) && !save()) {
                    text = "Gesetzt, aber nicht gespeichert – nach einem Neustart wäre er weg. Ein Admin sieht ins Log.";
                }
                if (r == WarpBook.Result.OK || r == WarpBook.Result.UPDATED) {
                    LOG.info(() -> "warp | gesetzt | spieler=" + p.getName() + " warp=" + name);
                }
                Texts.info(p, text);
            }
            case "delwarp" -> {
                WarpBook.Result r = book.delete(name, p.getUniqueId(), admin);
                if (r == WarpBook.Result.OK) {
                    save();
                    LOG.info(() -> "warp | gelöscht | spieler=" + p.getName() + " warp=" + name);
                }
                Texts.info(p, switch (r) {
                    case OK -> "Warp „" + name + "“ gelöscht.";
                    case MISSING -> "Den Warp „" + Texts.clean(name, 24) + "“ gibt es nicht.";
                    case NOT_OWNER -> "Den Warp hat jemand anderes gesetzt.";
                    default -> "Das ging nicht.";
                });
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void list(CommandSender to) {
        List<WarpBook.Warp> all = book.list();
        if (all.isEmpty()) {
            Texts.info(to, "Noch keine Warps. Setzen: /setwarp <name>");
            return;
        }
        Texts.info(to, all.size() + " Warp(s) – anklicken zum Hinspringen:");
        for (WarpBook.Warp w : all) {
            Texts.infoLink(to, w.name(), "/warp " + w.name(), "Zu " + w.name() + " springen",
                    " · " + w.world() + " · von " + w.ownerName() + (w.visits() > 0 ? " · " + w.visits()
                            + " Besuche" : ""));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || command.getName().equalsIgnoreCase("setwarp")) {
            return List.of();
        }
        String a = args[0].toLowerCase(Locale.ROOT);
        return names().stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(a)).toList();
    }
}
