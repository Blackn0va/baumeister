package com.blackn0va.baumeister;

import com.blackn0va.baumeister.help.HelpBook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/**
 * Hilfe für neue Spieler: /hilfe öffnet ein Buch (HelpBook) mit Inhaltsverzeichnis und anklickbaren Befehlen. Beim
 * allerersten Beitreten öffnet es sich einmal von selbst; danach erinnert eine Chatzeile an /hilfe.
 *
 * Geöffnet über Player.openBook – das Buch landet nie im Inventar (wie das Abenteuer-Tagebuch).
 *
 * „Schon gesehen“ steht in hilfe-gesehen.json (atomar), damit das Buch nicht bei jedem Beitreten aufspringt. Eine
 * unlesbare Datei heißt: niemand hat es gesehen – schlimmstenfalls öffnet es sich ein zweites Mal (bewusst fail-open,
 * es ist nur Hilfe).
 *
 * Threads: Hauptthread. Fehler: Schreibfehler werden geloggt.
 */
final class HelpService implements Listener, CommandExecutor {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** inInventory: jeder bekommt das Buch als Gegenstand (Wunsch 2026-11-05). */
    record Settings(boolean openOnFirstJoin, boolean inInventory) {
        static Settings from(ConfigurationSection root) {
            return new Settings(root == null || root.getBoolean("hilfe.beim-ersten-beitreten", true),
                    root == null || root.getBoolean("hilfe.buch-im-inventar", true));
        }
    }

    private final BaumeisterPlugin plugin;
    private final Settings settings;
    private final Path file;
    private final Set<UUID> seen = new TreeSet<>();
    /** Marke des Hilfebuch-Gegenstands (PDC baumeister:hilfebuch). */
    private final org.bukkit.NamespacedKey bookKey;
    /** Beim Tod abgenommene Bücher, Rückgabe beim Wiederbeleben (wie beim Rucksack). */
    private final Set<UUID> giveBack = new java.util.HashSet<>();

    HelpService(BaumeisterPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.file = plugin.getDataFolder().toPath().resolve("hilfe-gesehen.json");
        this.bookKey = new org.bukkit.NamespacedKey(plugin, "hilfebuch");
    }

    void start() {
        if (Files.exists(file)) {
            try {
                com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()
                        .forEach(e -> seen.add(UUID.fromString(e.getAsString())));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "hilfe | gesehen_unlesbar | datei=" + file + " – zeigt es notfalls noch einmal",
                        e);
            }
        }
        PluginCommand pc = plugin.getCommand("hilfe");
        if (pc == null) {
            throw new IllegalStateException("Befehl hilfe fehlt in plugin.yml");
        }
        pc.setExecutor(this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            giveIfMissing(p);
        }
        probe();
        LOG.info(() -> "hilfe | bereit | gesehen=" + seen.size() + " beim_ersten_beitreten="
                + settings.openOnFirstJoin() + " im_inventar=" + settings.inInventory());
    }

    // ---------------------------------------------------------------- Buch als Gegenstand

    /**
     * Das Buch im Inventar. Sein eigener Inhalt ist nur ein Platzhalter: Ein Rechtsklick öffnet immer das frisch
     * gebaute Buch (open), damit Listen und Aufgaben stimmen – ein im Gegenstand gespeichertes Buch wäre nach einer
     * Minute veraltet.
     */
    ItemStack item() {
        ItemStack it = new ItemStack(Material.WRITTEN_BOOK);
        if (it.getItemMeta() instanceof BookMeta meta) {
            meta.setTitle("Hilfe");
            meta.setAuthor(companionName());
            meta.setDisplayName(org.bukkit.ChatColor.GOLD + "" + org.bukkit.ChatColor.BOLD + "Hilfe & Bedienung");
            meta.setLore(List.of(org.bukkit.ChatColor.GRAY + "Rechtsklick: aufschlagen",
                    org.bukkit.ChatColor.GRAY + "Alles hier geht per Klick."));
            meta.setPages("Rechtsklick mit dem Buch in der Hand öffnet die aktuelle Hilfe.");
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(bookKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    boolean isHelpBook(ItemStack it) {
        return it != null && it.getType() == Material.WRITTEN_BOOK && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(bookKey,
                        org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** true = jetzt eins im Inventar. */
    boolean giveIfMissing(Player p) {
        if (!settings.inInventory()) {
            return false;
        }
        for (ItemStack it : p.getInventory().getContents()) {
            if (isHelpBook(it)) {
                return true;
            }
        }
        if (isHelpBook(p.getItemOnCursor())) {
            return true;
        }
        if (!p.getInventory().addItem(item()).isEmpty()) {
            Texts.info(p, "Dein Hilfebuch passt gerade nicht ins Inventar – mach Platz und tippe /hilfe holen.");
            return false;
        }
        LOG.info(() -> "hilfe | buch_gegeben | spieler=" + p.getName());
        return true;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isHelpBook(e.getItem())) {
            return;
        }
        // Rechtsklick auf Türen, Truhen … bleibt deren Sache.
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable() && !e.getPlayer().isSneaking()) {
            return;
        }
        // Nicht das gespeicherte Platzhalter-Buch zeigen, sondern das aktuelle.
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> open(p));
    }

    /** Rechtsklick auf das Buch im eigenen Inventar öffnet es ebenfalls (wie beim Rucksack). */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p
                && e.getView().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
                && e.getClick() == org.bukkit.event.inventory.ClickType.RIGHT && isHelpBook(e.getCurrentItem())
                && p.getItemOnCursor().getType().isAir()) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.closeInventory();
                open(p);
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (isHelpBook(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            Texts.info(e.getPlayer(), "Dein Hilfebuch bleibt bei dir.");
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        if (e.getDrops().removeIf(this::isHelpBook)) {
            giveBack.add(e.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (giveBack.remove(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    giveIfMissing(p);
                }
            });
        }
    }

    private String companionName() {
        CompanionService c = plugin.companion();
        return c != null ? c.name() : CompanionSettings.DEFAULT_NAME;
    }

    /** Was das Buch beim Aufschlagen über Spieler und Server wissen muss (Listen für die Knöpfe). */
    private HelpBook.Context context(Player p) {
        List<String> others = Bukkit.getOnlinePlayers().stream().filter(o -> !o.equals(p) && p.canSee(o))
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<String> warps = plugin.warps() != null ? plugin.warps().names() : List.of();
        List<String> teams = plugin.rucksack() != null ? plugin.rucksack().teamsOf(p) : List.of();
        List<String> quests = plugin.quests() != null ? plugin.quests().plainLines(p) : List.of();
        List<String> modules = plugin.schematics() instanceof SchematicLibrary lib
                ? lib.all().stream().map(com.blackn0va.baumeister.plan.ModuleLibrary.Module::name).sorted().toList()
                : List.of();
        // Sammlung und Stimme (2026-11-08): Kapitel nur, wo es etwas zu bedienen gibt; die Stimme nur für Admins.
        FileSchematics files = plugin.fileSchematics();
        int schematics = files == null ? 0 : files.catalog().size();
        HelpBook.Extras extras = new HelpBook.Extras(schematics,
                p.hasPermission("baumeister.admin") ? VoiceControl.panel(plugin) : null);
        CompanionService c = plugin.companion();
        if (c == null) {
            return new HelpBook.Context(companionName(), p::hasPermission, others, warps, teams, quests, modules, null,
                    List.of(), List.of(), List.of(), plots(), extras);
        }
        CompanionRights r = c.rights();
        // Veras Fähigkeiten zählen im Buch so, wie sie wirklich gelten (Stufe, Eintrag, Recht) – nicht nur das
        // Bukkit-Recht, das seit 2026-11-07 „default: false“ hat.
        java.util.function.Predicate<String> can = node -> {
            for (com.blackn0va.baumeister.companion.VeraRights.Ability a
                    : com.blackn0va.baumeister.companion.VeraRights.Ability.values()) {
                if (a.vera() && a.node().equals(node)) {
                    return r.allowed(p, a);
                }
            }
            return p.hasPermission(node);
        };
        List<HelpBook.Right> rights = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        List<String> rightPlayers = List.of();
        if (p.hasPermission("baumeister.admin")) {
            for (com.blackn0va.baumeister.companion.VeraRights.Ability a
                    : com.blackn0va.baumeister.companion.VeraRights.Ability.values()) {
                if (a.vera()) {
                    rights.add(new HelpBook.Right(a.key(), a.label(), r.view().level(a).label()));
                }
            }
            r.view().entries().forEach((name, m) -> m.forEach((a, v) -> exceptions.add(name + ": " + a.label() + " "
                    + (v ? "✔" : "✘"))));
            rightPlayers = rightPlayers(p, r);
        }
        return new HelpBook.Context(companionName(), can, others, warps, teams, quests, modules,
                c.character(p.getUniqueId()).label(), rights, exceptions, rightPlayers, plots(), extras);
    }

    /** Läuft PlotSquared? Nur dann zeigt das Buch /plot auto und /plot home. */
    private static boolean plots() {
        return Bukkit.getPluginManager().isPluginEnabled("PlotSquared");
    }

    /** Spieler für die Rechte-Liste: online, dann mit Einträgen, dann zuletzt gesehene – höchstens 24. */
    private static List<String> rightPlayers(Player admin, CompanionRights r) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(names::add);
        names.addAll(r.view().entries().keySet());
        java.util.Arrays.stream(Bukkit.getOfflinePlayers()).filter(o -> o.getName() != null)
                .sorted((a, b) -> Long.compare(b.getLastPlayed(), a.getLastPlayed())).limit(40)
                .forEach(o -> names.add(o.getName()));
        return names.stream().limit(24).toList();
    }

    /** Buch auf dem ersten Blatt des Kapitels aufschlagen (nach einem Klick auf Rechte oder Charakter). */
    void openChapter(Player p, String chapter) {
        List<HelpBook.Page> pages = HelpBook.pages(context(p));
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).chapter().equals(chapter)) {
                open(p, i + 1);
                return;
            }
        }
        open(p, 1);
    }

    /** Rechte-Seiten eines Spielers (Admin): alle Rechte des Plugins mit dem, was gilt, und den Einträgen. */
    void openPlayerRights(Player admin, org.bukkit.OfflinePlayer target) {
        CompanionService c = plugin.companion();
        if (c == null || target.getName() == null) {
            return;
        }
        CompanionRights r = c.rights();
        Player on = target.getPlayer();
        List<HelpBook.PlayerRight> list = new ArrayList<>();
        for (com.blackn0va.baumeister.companion.VeraRights.Ability a
                : com.blackn0va.baumeister.companion.VeraRights.Ability.values()) {
            Boolean own = r.view().player(target.getUniqueId(), a);
            // Offline lässt sich nicht fragen, was LuckPerms sagt – dann zählt der Eintrag oder die Stufe/Vorgabe.
            boolean effective = on != null ? (a.vera() ? r.allowed(on, a) : on.hasPermission(a.node()))
                    : own != null ? own : a.vera() ? r.view().level(a) == com.blackn0va.baumeister.companion
                            .VeraRights.Level.ALLE || r.view().level(a) == com.blackn0va.baumeister.companion
                            .VeraRights.Level.OPS && target.isOp() : a.fallback() == com.blackn0va.baumeister
                            .companion.VeraRights.Level.ALLE || target.isOp();
            list.add(new HelpBook.PlayerRight(a.key(), a.label(), own, effective));
        }
        List<HelpBook.Page> pages = HelpBook.playerRights(target.getName(), list);
        admin.openBook(build(pages));
        LOG.info(() -> "hilfe | rechte_seite | admin=" + admin.getName() + " spieler=" + target.getName());
    }

    /** Öffnet das Hilfebuch für p. */
    void open(Player p) {
        open(p, 1);
    }

    /** Aufschlagen auf Seite page (1-basiert) – für ◀/▶ und das Inhaltsverzeichnis (HelpBook.PAGE_COMMAND). */
    void open(Player p, int page) {
        List<HelpBook.Page> pages = HelpBook.rotate(HelpBook.pages(context(p)), page);
        ItemStack book = build(pages);
        p.openBook(book);
        LOG.info(() -> "hilfe | geöffnet | spieler=" + p.getName() + " seiten=" + pages.size()
                + " im_buch=" + (book.getItemMeta() instanceof BookMeta m ? m.getPageCount() : -1));
    }

    private ItemStack build(List<HelpBook.Page> pages) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (book.getItemMeta() instanceof BookMeta meta) {
            meta.setTitle("Hilfe");
            meta.setAuthor(companionName());
            List<BaseComponent[]> out = new ArrayList<>();
            for (HelpBook.Page page : pages) {
                out.add(components(page));
            }
            meta.spigot().setPages(out);
            book.setItemMeta(meta);
        }
        return book;
    }

    /**
     * Messprobe (Wunsch 2026-11-05): Wie sieht das Buch aus, das an den Client
     * geht? Einmal beim Start ins Log – Seitenzahl und die Komponenten-Form der Klick-Ereignisse (change_page).
     */
    void probe() {
        List<HelpBook.Page> pages = HelpBook.pages(new HelpBook.Context(companionName(), s -> true, List.of("Alex"),
                List.of("farmwelt"), List.of(), List.of("· Probe 0/1"), List.of()));
        ItemStack book = build(pages);
        if (book.getItemMeta() instanceof BookMeta meta) {
            // Gemessen 2026-11-05 20:18: 12 Seiten, change_page korrekt als {action: "change_page", page: 2} – der
            // Server liefert richtig. Seitdem blättert das Buch per Befehl (HelpBook.PAGE_COMMAND); hier bleibt nur die
            // Kurzfassung, damit ein Fehler beim Bauen des Buchs sofort im Startlog steht.
            String snbt = meta.getAsComponentString();
            long commands = snbt.split("run_command", -1).length - 1L;
            LOG.info(() -> "hilfe | probe | seiten_geplant=" + pages.size() + " seiten_im_buch=" + meta.getPageCount()
                    + " klickbefehle=" + commands);
        }
    }

    private static BaseComponent[] components(HelpBook.Page page) {
        List<BaseComponent> parts = new ArrayList<>();
        for (HelpBook.Part part : page.parts()) {
            TextComponent t = new TextComponent(part.text());
            t.setBold(part.bold());
            t.setColor(part.bold() ? ChatColor.DARK_PURPLE : ChatColor.BLACK);
            boolean arrow = part.text().equals(HelpBook.FOOTER_BACK) || part.text().equals(HelpBook.FOOTER_NEXT);
            if (arrow && part.command() == null) {
                // Erste/letzte Seite: Pfeil grau und ohne Klick – er hält nur den Platz, damit „Inhalt“ nicht rückt.
                t.setColor(ChatColor.GRAY);
            }
            if (part.command() != null) {
                t.setColor(ChatColor.DARK_BLUE);
                t.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, part.command()));
                t.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Klick: " + part.command())));
            } else if (part.page() > 0) {
                t.setColor(ChatColor.DARK_BLUE);
                t.setClickEvent(new ClickEvent(ClickEvent.Action.CHANGE_PAGE, String.valueOf(part.page())));
            }
            parts.add(t);
        }
        return parts.toArray(BaseComponent[]::new);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean first = !seen.contains(p.getUniqueId());
        // Ein paar Sekunden warten: Beim Beitreten lädt die Welt noch, ein Buch sofort ginge unter.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            // Nach dem Rucksack (ein Takt nach dem Join): beide landen nebeneinander im Inventar.
            giveIfMissing(p);
            if (first && settings.openOnFirstJoin()) {
                seen.add(p.getUniqueId());
                save();
                open(p);
            }
            // Eine Willkommenszeile statt vier (Aufgaben, Hilfe, Rucksack je eigene).
            String quests = plugin.quests() != null ? plugin.quests().joinHint(p) : "";
            Texts.infoLink(p, "Hilfebuch", "/hilfe", "Hilfebuch aufschlagen", " – alles per Klick"
                    + (quests.isEmpty() ? "" : " · " + quests) + ". Das Buch und dein Rucksack liegen im Inventar.");
        }, 80L);
    }

    private void save() {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        seen.forEach(id -> a.add(id.toString()));
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("hilfe-gesehen.json.neu");
            Files.writeString(tmp, a.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "hilfe | schreiben_fehlgeschlagen | datei=" + file, ex);
        }
    }

    /**
     * /hilfe [tippen &lt;befehl&gt;]. „tippen“ kommt aus dem Buch: Ein Buchklick kann nichts in die Chatzeile legen,
     * eine Chatzeile schon (suggest_command) – der Spieler klickt sie und ergänzt nur noch den Namen.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Das Hilfebuch gibt es nur im Spiel.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("seite") && args[1].matches("\\d{1,3}")) {
            open(p, Integer.parseInt(args[1]));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("holen")) {
            if (!settings.inInventory()) {
                Texts.info(p, "Das Hilfebuch gibt es hier nur über /hilfe (hilfe.buch-im-inventar ist aus).");
            } else if (giveIfMissing(p)) {
                Texts.info(p, "Dein Hilfebuch ist im Inventar – Rechtsklick schlägt es auf.");
            }
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("tippen") && args[1].startsWith("/")) {
            String suggestion = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) + " ";
            TextComponent head = new TextComponent("[Baumeister] ");
            head.setColor(ChatColor.GOLD);
            TextComponent click = new TextComponent("✎ Hier klicken: " + suggestion.strip() + " …");
            click.setColor(ChatColor.AQUA);
            click.setUnderlined(true);
            click.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggestion));
            click.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text("Legt den Befehl in deine Chatzeile – ergänze nur noch den Rest.")));
            TextComponent tail = new TextComponent(" (dann ergänzen und Enter)");
            tail.setColor(ChatColor.GRAY);
            p.spigot().sendMessage(head, click, tail);
            return true;
        }
        open(p);
        return true;
    }
}
