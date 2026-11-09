package com.blackn0va.baumeister;

import com.blackn0va.baumeister.quest.DailyQuests;
import com.blackn0va.baumeister.quest.QuestBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tagesaufgaben (/aufgaben, Vera „was sind meine Aufgaben?“): drei Aufgaben je Tag für alle (DailyQuests), Punkte,
 * Serie und Rangliste (QuestBook), Belohnung in Erfahrung. Vorschlag 8 vom 2026-11-05.
 *
 * Zählregeln gegen Abkürzungen: nur Überleben/Abenteuer, nur in den Welten aus aufgaben.welten (Vorgabe: Farmwelt),
 * Abbau selbst gesetzter Blöcke zählt nicht (Setzen und Abbauen im Wechsel wäre sonst eine Punktemaschine; gemerkt
 * im Speicher bis zum Neustart), Ernte nur reifer Pflanzen, Besiegen nur durch den Spieler selbst.
 *
 * Tag = Kalendertag in Europe/Berlin (die Spieler sind dort). Gespeichert in aufgaben.json (atomar) bei jeder
 * geschafften Aufgabe, alle 60 s bei Fortschritt und beim Herunterfahren. Unlesbare Datei: beiseitelegen, leer starten.
 *
 * Threads: Hauptthread. Fehler: Schreibfehler werden geloggt, der Stand im Speicher bleibt.
 */
final class QuestService implements Listener, CommandExecutor, TabCompleter {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Erfahrungspunkte je Aufgabenpunkt (10 Punkte ≈ ein paar Level am Anfang). */
    static final int XP_PER_POINT = 5;

    record Settings(boolean enabled, List<String> worlds) {

        static Settings from(ConfigurationSection root) {
            // Vorher fest die Farmwelt von dev01 (world …); jetzt die Hauptwelt des Servers samt Nether und End.
            List<String> farm = MainWorlds.DEFAULT;
            if (root == null) {
                return new Settings(true, farm);
            }
            return new Settings(root.getBoolean("aufgaben.an", true),
                    root.isList("aufgaben.welten") ? List.copyOf(root.getStringList("aufgaben.welten")) : farm);
        }
    }

    private final BaumeisterPlugin plugin;
    private final Settings settings;
    private final Path file;
    /** Selbst gesetzte Blöcke (Welt-UID + Koordinaten), deren Abbau nicht zählt. */
    private final Set<String> placed = new HashSet<>();
    private QuestBook book;
    private boolean dirty;
    private BukkitTask autosave;

    /** settings.worlds() mit aufgelöstem „hauptwelt“ (MainWorlds), beim ersten Zählen berechnet. */
    private Set<String> worldSet;

    private Set<String> worlds() {
        if (worldSet == null) {
            worldSet = MainWorlds.expand(settings.worlds(), MainWorlds.mainName());
        }
        return worldSet;
    }

    QuestService(BaumeisterPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.file = plugin.getDataFolder().toPath().resolve("aufgaben.json");
    }

    private static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    void start() {
        book = new QuestBook(today());
        if (Files.exists(file)) {
            try {
                book = QuestBook.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | IllegalArgumentException e) {
                Path aside = file.resolveSibling("aufgaben.json.defekt-" + System.currentTimeMillis());
                try {
                    Files.move(file, aside);
                } catch (IOException ex) {
                    LOG.log(Level.SEVERE, "aufgaben | beiseitelegen_fehlgeschlagen | datei=" + file, ex);
                }
                LOG.log(Level.SEVERE, "aufgaben | datei_kaputt | beiseite=" + aside + " – starte leer", e);
            }
        }
        book.roll(today());
        PluginCommand pc = plugin.getCommand("aufgaben");
        if (pc == null) {
            throw new IllegalStateException("Befehl aufgaben fehlt in plugin.yml");
        }
        pc.setExecutor(this);
        pc.setTabCompleter(this);
        autosave = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) {
                save();
            }
        }, 1200L, 1200L);
        LOG.info(() -> "aufgaben | bereit | an=" + settings.enabled() + " tag=" + book.day() + " aufgaben="
                + book.quests().stream().map(DailyQuests.Quest::title).toList());
    }

    void shutdown() {
        if (autosave != null) {
            autosave.cancel();
        }
        if (dirty && book != null) {
            save();
        }
    }

    private void save() {
        try {
            Path tmp = file.resolveSibling("aufgaben.json.neu");
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, book.toJson(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "aufgaben | schreiben_fehlgeschlagen | datei=" + file, e);
        }
    }

    private boolean counts(Player p) {
        return settings.enabled() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && (settings.worlds().isEmpty() || worlds().contains(p.getWorld().getName().toLowerCase(
                        java.util.Locale.ROOT)));
    }

    private void count(Player p, DailyQuests.Kind kind, String key, int n) {
        if (!counts(p)) {
            return;
        }
        if (book.roll(today())) {
            LOG.info(() -> "aufgaben | neuer_tag | tag=" + book.day());
        }
        QuestBook.Gain g = book.add(p.getUniqueId(), p.getName(), kind, key, n);
        dirty = true;
        if (g.finished().isEmpty()) {
            return;
        }
        List<DailyQuests.Quest> qs = book.quests();
        for (int i : g.finished()) {
            Texts.info(p, ChatColor.GREEN + "Aufgabe geschafft: " + qs.get(i).title() + " (+" + qs.get(i).points()
                    + " Punkte)");
        }
        if (g.allDone()) {
            p.sendTitle(ChatColor.GOLD + "Alle Tagesaufgaben!", ChatColor.YELLOW + "+" + DailyQuests.ALL_DONE_BONUS
                    + " Bonus · Serie " + book.streak(p.getUniqueId()) + " Tag(e)", 10, 60, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        } else {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        }
        p.giveExp(g.points() * XP_PER_POINT);
        LOG.info(() -> "aufgaben | geschafft | spieler=" + p.getName() + " aufgaben=" + g.finished() + " punkte="
                + g.points() + " alle=" + g.allDone());
        save();
    }

    private static String key(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        placed.add(key(e.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        boolean selfPlaced = placed.remove(key(b));
        String type = b.getType().getKey().getKey();
        // Ernte vor der Gesetzt-Prüfung: Saat setzt immer der Spieler. Reif gesetzt werden kann sie nicht, und
        // Zuckerrohr zählt nur nachgewachsen (auf Zuckerrohr) – Setzen und Abbrechen bringt so nichts.
        if (b.getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge()) {
            count(e.getPlayer(), DailyQuests.Kind.HARVEST, type, 1);
            return;
        }
        if (type.equals("sugar_cane")) {
            if (b.getRelative(org.bukkit.block.BlockFace.DOWN).getType() == b.getType() && !selfPlaced) {
                count(e.getPlayer(), DailyQuests.Kind.HARVEST, type, 1);
            }
            return;
        }
        if (!selfPlaced) {
            count(e.getPlayer(), DailyQuests.Kind.MINE, type, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            count(killer, DailyQuests.Kind.KILL, e.getEntityType().getKey().getKey(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            count(e.getPlayer(), DailyQuests.Kind.FISH, "", 1);
        }
    }

    /**
     * Kurzfassung fürs Beitreten (HelpService.onJoin baut daraus EINE Willkommenszeile): Vorher schickten Aufgaben,
     * Hilfe und Rucksack je eine eigene Zeile, beim Beitreten kamen vier Hinweise untereinander (2026-11-05).
     */
    String joinHint(Player p) {
        if (!settings.enabled()) {
            return "";
        }
        book.roll(today());
        int open = 0;
        for (int i = 0; i < book.quests().size(); i++) {
            if (!book.done(p.getUniqueId(), i)) {
                open++;
            }
        }
        return open == 0 ? "alle Tagesaufgaben geschafft" : open + " Tagesaufgabe" + (open == 1 ? "" : "n") + " offen";
    }

    /** Heutige Aufgaben mit Stand, als Zeilen (für /aufgaben und Vera). */
    List<String> overview(Player p) {
        book.roll(today());
        List<DailyQuests.Quest> qs = book.quests();
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            DailyQuests.Quest q = qs.get(i);
            boolean d = book.done(p.getUniqueId(), i);
            lines.add((d ? ChatColor.GREEN + "✔ " : ChatColor.YELLOW + "· ") + q.title() + ChatColor.GRAY + " – "
                    + (d ? "geschafft" : book.progress(p.getUniqueId(), i) + "/" + q.amount()) + " (" + q.points()
                    + " Punkte)");
        }
        return lines;
    }

    /** Heutige Aufgaben ohne Farben, kurz – fürs Hilfebuch („✔ Baue 16 Kupfererz ab“ / „· … 3/16“). */
    List<String> plainLines(Player p) {
        book.roll(today());
        List<DailyQuests.Quest> qs = book.quests();
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            boolean d = book.done(p.getUniqueId(), i);
            lines.add((d ? "✔ " : "· ") + qs.get(i).title() + (d ? "" : " " + book.progress(p.getUniqueId(), i) + "/"
                    + qs.get(i).amount()));
        }
        return lines;
    }

    /** Ein Satz für Vera. */
    String sentence(Player p) {
        book.roll(today());
        List<DailyQuests.Quest> qs = book.quests();
        List<String> open = new java.util.ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            if (!book.done(p.getUniqueId(), i)) {
                open.add(qs.get(i).title() + " (" + book.progress(p.getUniqueId(), i) + " von " + qs.get(i).amount()
                        + ")");
            }
        }
        if (open.isEmpty()) {
            return "Du hast heute alle Aufgaben geschafft – stark! Morgen gibt es neue.";
        }
        return "Heute: " + String.join(", ", open) + ". Du hast " + book.points(p.getUniqueId()) + " Punkte.";
    }

    String rankingText(Player p) {
        List<QuestBook.Rank> all = book.ranking();
        if (all.isEmpty()) {
            return "Noch hat niemand eine Tagesaufgabe geschafft – sei die oder der Erste!";
        }
        StringBuilder s = new StringBuilder("Die meisten Punkte: ");
        for (int i = 0; i < Math.min(5, all.size()); i++) {
            QuestBook.Rank r = all.get(i);
            s.append(i == 0 ? "" : ", ").append(i + 1).append(". ").append(r.name()).append(" ").append(r.points())
                    .append(r.streak() > 1 ? " (Serie " + r.streak() + ")" : "");
        }
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).player().equals(p.getUniqueId()) && i >= 5) {
                s.append(". Du bist auf Platz ").append(i + 1).append(" mit ").append(all.get(i).points());
            }
        }
        return s.append(".").toString();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Aufgaben gibt es nur im Spiel.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("rangliste")) {
            Texts.info(p, rankingText(p));
            return true;
        }
        Texts.info(p, "Tagesaufgaben für " + book.day() + " – du hast " + book.points(p.getUniqueId()) + " Punkte"
                + (book.streak(p.getUniqueId()) > 0 ? ", Serie " + book.streak(p.getUniqueId()) + " Tag(e)" : "")
                + ":");
        overview(p).forEach(p::sendMessage);
        if (!counts(p)) {
            Texts.info(p, "Hier zählt gerade nichts – nur im Überleben in der " + MainWorlds.label(plugin.getConfig())
                    + ".");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 && "rangliste".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))
                ? List.of("rangliste") : List.of();
    }
}
