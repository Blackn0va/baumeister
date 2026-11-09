package com.blackn0va.baumeister;

import com.blackn0va.baumeister.rucksack.RucksackFile;
import com.blackn0va.baumeister.rucksack.RucksackLogic;
import com.blackn0va.baumeister.rucksack.RucksackTeams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Rucksack: eine Kiste im Inventar mit sehr viel Platz (Vorgabe 100 Seiten × 45 = 4500 Stapel). Ersetzt
 * MinepacksStable (Wunsch 2026-11-05, danach wurde das Plugin gelöscht).
 * Dazu Teamkisten (gemeinsam für eingeladene Spieler), Einsammeln (gewählte Sorten gehen beim Aufheben direkt hinein),
 * Suche und „Vera, gib mir … aus dem Rucksack“.
 *
 * Grundentscheidungen:
 * - Der Inhalt gehört dem SPIELER (bzw. dem Team) und liegt auf dem Server (rucksack/&lt;uuid&gt;.yml,
 *   rucksack/team-&lt;name&gt;.yml), nicht im Gegenstand. Die Kiste ist nur der Schlüssel. Eine kopierte Kiste
 *   (Kreativ-Mittelklick, Fehler) kopiert so nie den Inhalt, und eine fremde Kiste öffnet nichts.
 * - Jeder Inhalt ist höchstens in EINEM Fenster offen (openBy): Zwei Fenster auf dieselben Plätze wären ein
 *   Duplikat-Weg – bei Teamkisten heißt es dann „gerade offen bei …“.
 * - Nicht im Kreativmodus (Vorgabe: nur Überleben und Abenteuer), auch nicht Einsammeln oder Herausnehmen: Sonst holt
 *   man sich Sachen im Kreativmodus und nimmt sie im Überleben wieder heraus. Wechselt der Modus bei offenem Fenster,
 *   schließt es sofort.
 * - Die Kiste lässt sich nicht abstellen, nicht wegwerfen, nicht in den Rucksack legen und geht beim Tod nicht
 *   verloren (kommt beim Wiederbeleben zurück).
 * - Kaputte Datei: fail-closed, öffnet nicht und wird nie überschrieben (RucksackFile).
 * - Geladene Inhalte bleiben bis zum Herunterfahren im Speicher: Neu aus der Datei geladen, während das Schreiben noch
 *   in der Warteschlange liegt, wäre der Stand alt und Herausgenommenes doppelt da.
 *
 * Speichern: geschrieben beim Schließen, beim Verlassen, alle 30 s für Geänderte und synchron beim Herunterfahren.
 * Das Schreiben läuft in einem Einzel-Thread, damit Fassungen derselben Datei in Reihenfolge ankommen; das Umwandeln
 * in YAML braucht den Hauptthread (ItemMeta).
 *
 * Restrisiko (wie bei Minepacks): Stürzt der Server zwischen Herausnehmen und Speichern ab, stimmen Spielerdaten und
 * Rucksack-Datei bis zu 30 s nicht überein.
 *
 * Threads: Hauptthread, nur das Dateischreiben im eigenen Thread. Fehler: Schreibfehler werden geloggt (SEVERE), der
 * Inhalt bleibt im Speicher und wird beim nächsten Anlass erneut geschrieben.
 */
final class RucksackService implements Listener, CommandExecutor, TabCompleter {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Knopf „Einsammeln“ (nur im eigenen Rucksack). */
    static final int SLOT_COLLECT = 46;
    /** So viele Teamkisten darf ein Spieler gründen (Admins unbegrenzt). */
    static final int MAX_TEAMS_CREATED = 5;

    /** Einstellungen (config.yml, Abschnitt rucksack). */
    record Settings(boolean enabled, int pages, Set<GameMode> modes, boolean giveOnJoin) {

        static Settings from(ConfigurationSection root) {
            Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
            List<String> raw = root == null ? List.of() : root.getStringList("rucksack.spielmodi");
            for (String m : raw.isEmpty() ? List.of("SURVIVAL", "ADVENTURE") : raw) {
                try {
                    modes.add(GameMode.valueOf(m.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    LOG.warning(() -> "rucksack | spielmodus_unbekannt | wert=" + m);
                }
            }
            if (root == null) {
                return new Settings(true, 100, modes, true);
            }
            return new Settings(root.getBoolean("rucksack.an", true),
                    Math.clamp(root.getInt("rucksack.seiten", 100), 1, 1000), modes,
                    root.getBoolean("rucksack.beim-beitreten-geben", true));
        }
    }

    /** Geladener Inhalt: eigener Rucksack (team == null) oder Teamkiste. */
    private static final class Pack {
        final Path file;
        final List<ItemStack> slots;
        /** Besitzer des eigenen Rucksacks, bei Teams der Gründer. */
        final UUID owner;
        final String team;
        final Set<UUID> members = new LinkedHashSet<>();
        /** Einsammel-Filter (nur eigener Rucksack). */
        final Set<Material> collect = EnumSet.noneOf(Material.class);
        boolean dirty;

        Pack(Path file, UUID owner, String team, List<ItemStack> slots) {
            this.file = file;
            this.owner = owner;
            this.team = team;
            this.slots = slots;
        }
    }

    /** Das offene Fenster: welcher Inhalt, welche Seite. */
    private static final class View implements InventoryHolder {
        final Pack pack;
        final int pages;
        int page;
        Inventory inventory;

        View(Pack pack, int pages) {
            this.pack = pack;
            this.pages = pages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final RucksackLogic.StackOps<ItemStack> OPS = new RucksackLogic.StackOps<>() {
        @Override
        public boolean similar(ItemStack a, ItemStack b) {
            return a.isSimilar(b);
        }

        @Override
        public int amount(ItemStack t) {
            return t.getAmount();
        }

        @Override
        public int max(ItemStack t) {
            return t.getMaxStackSize();
        }

        @Override
        public ItemStack withAmount(ItemStack t, int amount) {
            ItemStack c = t.clone();
            c.setAmount(amount);
            return c;
        }
    };

    private static final Comparator<ItemStack> ORDER = Comparator
            .comparing((ItemStack s) -> s.getType().name())
            .thenComparing(s -> s.hasItemMeta() && s.getItemMeta().hasDisplayName() ? s.getItemMeta().getDisplayName()
                    : "");

    private final BaumeisterPlugin plugin;
    private final Settings settings;
    private final NamespacedKey ownerKey;
    private final NamespacedKey buttonKey;
    private final Path dir;
    private final Map<UUID, Pack> packs = new HashMap<>();
    private final Map<String, Pack> teams = new TreeMap<>();
    /** Wer welchen Inhalt gerade offen hat (Schlüssel: Datei). */
    private final Map<Path, UUID> openBy = new HashMap<>();
    /** Fehlerhafte Dateien: einmal melden, nie überschreiben. */
    private final Set<Path> broken = new HashSet<>();
    /** Beim Tod abgenommene Kisten, Rückgabe beim Wiederbeleben. */
    private final Set<UUID> giveBack = new HashSet<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Baumeister-Rucksack");
        t.setDaemon(true);
        return t;
    });
    private BukkitTask autosave;

    RucksackService(BaumeisterPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.ownerKey = new NamespacedKey(plugin, "rucksack");
        this.buttonKey = new NamespacedKey(plugin, "rucksack_knopf");
        this.dir = plugin.getDataFolder().toPath().resolve("rucksack");
    }

    void start() {
        loadTeams();
        autosave = Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, 600L, 600L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            giveIfMissing(p);
        }
        LOG.info(() -> "rucksack | bereit | an=" + settings.enabled() + " seiten=" + settings.pages()
                + " plätze=" + settings.pages() * RucksackLogic.PER_PAGE + " spielmodi=" + settings.modes()
                + " teams=" + teams.size());
    }

    /** Synchron beim Herunterfahren: Fenster schließen, alles schreiben, auf den Schreib-Thread warten. */
    void shutdown() {
        if (autosave != null) {
            autosave.cancel();
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof View) {
                p.closeInventory();
            }
        }
        List<Map.Entry<Path, String>> last = new ArrayList<>();
        allPacks().forEach(pack -> {
            if (pack.dirty && !broken.contains(pack.file)) {
                last.add(Map.entry(pack.file, serialize(pack)));
                pack.dirty = false;
            }
        });
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.severe("rucksack | schreiben_hängt | beim Herunterfahren nach 10 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Map.Entry<Path, String> e : last) {
            try {
                RucksackFile.write(e.getKey(), e.getValue());
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, "rucksack | schreiben_fehlgeschlagen | datei=" + e.getKey(), ex);
            }
        }
        packs.clear();
        teams.clear();
    }

    private Stream<Pack> allPacks() {
        return Stream.concat(packs.values().stream(), teams.values().stream());
    }

    // ---------------------------------------------------------------- Gegenstand

    /** Die Kiste für p (gehört p). */
    ItemStack item(Player p) {
        ItemStack it = new ItemStack(Material.CHEST);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Rucksack");
            int slots = settings.pages() * RucksackLogic.PER_PAGE;
            meta.setLore(List.of(
                    ChatColor.GRAY + "Rechtsklick: öffnen",
                    ChatColor.GRAY + "" + settings.pages() + " Seiten · " + slots + " Plätze",
                    ChatColor.DARK_GRAY + "gehört " + p.getName()));
            meta.setEnchantmentGlintOverride(true);
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
            it.setItemMeta(meta);
        }
        return it;
    }

    /** Besitzer der Kiste, null = keine Rucksack-Kiste. */
    private UUID owner(ItemStack it) {
        if (it == null || it.getType() != Material.CHEST || !it.hasItemMeta()) {
            return null;
        }
        String id = it.getItemMeta().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isRucksack(ItemStack it) {
        return owner(it) != null;
    }

    private boolean hasRucksack(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (p.getUniqueId().equals(owner(it))) {
                return true;
            }
        }
        return p.getUniqueId().equals(owner(p.getItemOnCursor()));
    }

    /** true = jetzt eine im Inventar. */
    boolean giveIfMissing(Player p) {
        if (!settings.enabled() || !p.hasPermission("baumeister.rucksack")) {
            return false;
        }
        if (hasRucksack(p)) {
            return true;
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(item(p));
        if (!left.isEmpty()) {
            Texts.info(p, "Dein Rucksack passt gerade nicht ins Inventar – mach einen Platz frei und tippe /rucksack holen.");
            return false;
        }
        LOG.info(() -> "rucksack | gegeben | spieler=" + p.getName());
        return true;
    }

    // ---------------------------------------------------------------- öffnen

    /** null = darf; sonst der Satz, warum nicht. */
    private String blocked(Player p) {
        if (!settings.enabled()) {
            return "Der Rucksack ist auf diesem Server abgeschaltet (rucksack.an).";
        }
        if (!p.hasPermission("baumeister.rucksack")) {
            return "Dafür fehlt dir das Recht baumeister.rucksack.";
        }
        if (!settings.modes().contains(p.getGameMode())) {
            return "Im Modus " + modeName(p.getGameMode()) + " bleibt der Rucksack zu – sonst könnte man Sachen aus "
                    + "dem Kreativmodus ins Überleben mitnehmen.";
        }
        return null;
    }

    void open(Player p) {
        Pack pack = ownPack(p);
        if (pack != null) {
            open(p, pack, 0);
        }
    }

    /** Eigener Rucksack, nach allen Prüfungen; null = Meldung ist schon raus. */
    private Pack ownPack(Player p) {
        String why = blocked(p);
        if (why != null) {
            Texts.info(p, why);
            return null;
        }
        Pack pack = pack(p.getUniqueId());
        if (pack == null) {
            Texts.info(p, "Dein Rucksack lässt sich gerade nicht öffnen – die Datei ist beschädigt. Deine Sachen sind "
                    + "nicht weg: Ein Admin findet sie in plugins/Baumeister/rucksack/.");
        }
        return pack;
    }

    private void open(Player p, Pack pack, int page) {
        View current = view(p);
        if (current != null && current.pack == pack) {
            // Schon offen (z. B. Suche per Sprache): nur blättern. Ein zweites Fenster zeigte einen alten Stand.
            turnTo(p, current, page);
            return;
        }
        UUID holder = openBy.get(pack.file);
        if (holder != null && !holder.equals(p.getUniqueId())) {
            Player other = Bukkit.getPlayer(holder);
            if (other != null && view(other) != null && view(other).pack == pack) {
                Texts.info(p, "Die Teamkiste ist gerade bei " + other.getName() + " offen – gleich noch mal.");
                return;
            }
        }
        View v = new View(pack, settings.pages());
        v.page = Math.clamp(page, 0, v.pages - 1);
        Inventory inv = Bukkit.createInventory(v, 54, title(v));
        v.inventory = inv;
        show(v);
        // Erst eintragen, dann öffnen: Das Schließen eines anderen Fensters gibt nur dessen Eintrag frei.
        openBy.put(pack.file, p.getUniqueId());
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 0.7f, 1.2f);
    }

    private static View view(Player p) {
        return p.getOpenInventory().getTopInventory().getHolder() instanceof View v ? v : null;
    }

    private static String modeName(GameMode m) {
        return switch (m) {
            case CREATIVE -> "Kreativ";
            case SPECTATOR -> "Zuschauer";
            case ADVENTURE -> "Abenteuer";
            case SURVIVAL -> "Überleben";
        };
    }

    private static String title(View v) {
        return v.pack.team == null ? RucksackLogic.title(v.page, v.pages)
                : "Teamkiste " + v.pack.team + " · Seite " + (v.page + 1) + "/" + v.pages;
    }

    /** Eigener Rucksack (einmal geladen, dann aus dem Speicher). null = Datei kaputt (fail-closed). */
    private Pack pack(UUID owner) {
        Pack have = packs.get(owner);
        if (have != null) {
            return have;
        }
        Pack p = load(dir.resolve(owner + ".yml"), owner, null);
        if (p != null) {
            packs.put(owner, p);
        }
        return p;
    }

    private Pack load(Path f, UUID owner, String team) {
        if (broken.contains(f)) {
            return null;
        }
        int size = settings.pages() * RucksackLogic.PER_PAGE;
        RucksackFile.Loaded l = RucksackFile.load(f);
        List<ItemStack> slots = new ArrayList<>(Collections.nCopies(size, null));
        Pack pack = new Pack(f, owner, team, slots);
        if (l.status() == RucksackFile.Status.OK) {
            String why = RucksackFile.check(l.config(), ItemStack.class, size);
            if (why != null) {
                l = new RucksackFile.Loaded(RucksackFile.Status.BROKEN, null, why);
            } else {
                YamlConfiguration yc = l.config();
                ConfigurationSection pages = yc.getConfigurationSection("seiten");
                if (pages != null) {
                    for (String pk : pages.getKeys(false)) {
                        ConfigurationSection page = pages.getConfigurationSection(pk);
                        for (String sk : page.getKeys(false)) {
                            slots.set(Integer.parseInt(pk) * RucksackLogic.PER_PAGE + Integer.parseInt(sk),
                                    page.getItemStack(sk));
                        }
                    }
                }
                for (String m : yc.getStringList("einsammeln")) {
                    Material mat = Material.matchMaterial(m);
                    if (mat != null && mat.isItem()) {
                        pack.collect.add(mat);
                    }
                }
                for (String id : yc.getStringList("team.mitglieder")) {
                    try {
                        pack.members.add(UUID.fromString(id));
                    } catch (IllegalArgumentException e) {
                        LOG.warning(() -> "rucksack | mitglied_unlesbar | datei=" + f + " wert=" + id);
                    }
                }
            }
        }
        if (l.status() == RucksackFile.Status.BROKEN) {
            broken.add(f);
            String reason = l.reason();
            LOG.severe(() -> "rucksack | datei_kaputt | datei=" + f + " grund=" + reason
                    + " – bleibt zu und wird nicht überschrieben");
            return null;
        }
        return pack;
    }

    /** Zeigt die aktuelle Seite samt Knopfreihe. */
    private void show(View v) {
        Inventory inv = v.inventory;
        int base = v.page * RucksackLogic.PER_PAGE;
        for (int i = 0; i < RucksackLogic.PER_PAGE; i++) {
            ItemStack s = v.pack.slots.get(base + i);
            inv.setItem(i, s == null ? null : s.clone());
        }
        int used = RucksackLogic.used(v.pack.slots);
        int all = v.pack.slots.size();
        for (int i = RucksackLogic.PER_PAGE; i < 54; i++) {
            inv.setItem(i, button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of()));
        }
        inv.setItem(RucksackLogic.SLOT_PREV, v.page == 0 ? button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of())
                : button(Material.ARROW, "◀ Seite zurück", List.of("Shift-Klick: " + RucksackLogic.JUMP + " Seiten")));
        inv.setItem(RucksackLogic.SLOT_NEXT, v.page == v.pages - 1
                ? button(Material.GRAY_STAINED_GLASS_PANE, " ", List.of())
                : button(Material.ARROW, "Seite weiter ▶", List.of("Shift-Klick: " + RucksackLogic.JUMP + " Seiten")));
        inv.setItem(RucksackLogic.SLOT_DEPOSIT, button(Material.HOPPER, "Alles einlagern",
                List.of("Legt dein Inventar ohne Schnellleiste", "hinein.")));
        inv.setItem(RucksackLogic.SLOT_SORT, button(Material.COMPARATOR, "Sortieren",
                List.of("Fasst gleiche Sachen zusammen", "und sortiert alle Seiten.")));
        List<String> info = new ArrayList<>(List.of("Belegt: " + used + " von " + all + " Plätzen",
                "Klick: zur ersten Seite"));
        if (v.pack.team != null) {
            info.add("Mitglieder: " + names(v.pack.members));
        }
        inv.setItem(RucksackLogic.SLOT_INFO, button(Material.BOOK, "Seite " + (v.page + 1) + " von " + v.pages, info));
        if (v.pack.team == null) {
            List<String> lore = new ArrayList<>();
            lore.add(v.pack.collect.isEmpty() ? "Nichts ausgewählt." : "Geht beim Aufheben direkt hinein:");
            v.pack.collect.stream().limit(8).forEach(m -> lore.add("· " + materialName(m)));
            if (v.pack.collect.size() > 8) {
                lore.add("… und " + (v.pack.collect.size() - 8) + " weitere");
            }
            lore.add("");
            lore.add("Mit einem Gegenstand am Mauszeiger");
            lore.add("anklicken: an/aus. Shift-Klick: alle aus.");
            inv.setItem(SLOT_COLLECT, button(Material.BUNDLE, "Einsammeln", lore));
        }
    }

    private static String materialName(Material m) {
        return m.getKey().getKey().replace('_', ' ');
    }

    private static String names(Set<UUID> ids) {
        List<String> out = new ArrayList<>();
        for (UUID id : ids) {
            OfflinePlayer o = Bukkit.getOfflinePlayer(id);
            out.add(o.getName() != null ? o.getName() : id.toString().substring(0, 8));
        }
        return String.join(", ", out);
    }

    private ItemStack button(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.WHITE + name);
            meta.setLore(lore.stream().map(l -> ChatColor.GRAY + l).toList());
            // Eigene Marke: Knöpfe stapeln sich nie mit echten Pfeilen oder Büchern (Shift-Klick, Doppelklick).
            meta.getPersistentDataContainer().set(buttonKey, PersistentDataType.BYTE, (byte) 1);
            it.setItemMeta(meta);
        }
        return it;
    }

    private boolean isButton(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(buttonKey, PersistentDataType.BYTE);
    }

    /** Übernimmt, was gerade im Fenster liegt, in den Inhalt. Knöpfe und Rucksack-Kisten nie. */
    private void store(View v) {
        int base = v.page * RucksackLogic.PER_PAGE;
        for (int i = 0; i < RucksackLogic.PER_PAGE; i++) {
            ItemStack s = v.inventory.getItem(i);
            if (s != null && (s.getType().isAir() || isButton(s))) {
                s = null;
            }
            if (s != null && isRucksack(s)) {
                // Darf nicht hineingekommen sein (Klick-Schutz). Falls doch: zurück zum Spieler statt verschluckt.
                giveBackToViewer(v, s);
                s = null;
            }
            v.pack.slots.set(base + i, s == null ? null : s.clone());
        }
        v.pack.dirty = true;
    }

    private void giveBackToViewer(View v, ItemStack s) {
        for (org.bukkit.entity.HumanEntity h : v.inventory.getViewers()) {
            h.getInventory().addItem(s.clone()).values().forEach(r -> h.getWorld().dropItem(h.getLocation(), r));
            return;
        }
    }

    private void turnTo(Player p, View v, int target) {
        int t = Math.clamp(target, 0, v.pages - 1);
        if (t == v.page) {
            return;
        }
        store(v);
        v.page = t;
        show(v);
        retitle(p, v);
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f);
    }

    // ---------------------------------------------------------------- Suche, Herausnehmen, Einsammeln (auch für Vera)

    /** Gegenstand aus einem Wort („Fackeln“, „diamond“); leer, wenn es kein sicherer Gegenstand ist. */
    static Optional<Material> material(String word) {
        return com.blackn0va.baumeister.plan.ItemNames.resolve(word, BukkitBlockCatalog.ITEMS)
                .map(Material::matchMaterial).filter(m -> m != null && m.isItem() && !m.isAir());
    }

    /** Treffer für ein Suchwort: erkannter Gegenstand, sonst Teil des Namens oder des eigenen Anzeigenamens. */
    private static Predicate<ItemStack> matcher(String word) {
        Optional<Material> m = material(word);
        if (m.isPresent()) {
            Material mat = m.get();
            return s -> s.getType() == mat;
        }
        String w = word.trim().toLowerCase(Locale.ROOT);
        String key = w.replace(' ', '_');
        return s -> s.getType().getKey().getKey().contains(key)
                || (s.hasItemMeta() && s.getItemMeta().hasDisplayName()
                        && ChatColor.stripColor(s.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT).contains(w));
    }

    /**
     * Sucht im eigenen Rucksack, öffnet ihn auf der ersten Fundseite und liefert den Satz dazu (für Chat und Vera).
     * null = Meldung (gesperrt, kaputt) ist schon raus.
     */
    String search(Player p, String word) {
        Pack pack = ownPack(p);
        if (pack == null) {
            return null;
        }
        View v = view(p);
        if (v != null && v.pack == pack) {
            store(v);
        }
        List<Integer> hits = RucksackLogic.find(pack.slots, matcher(word));
        if (hits.isEmpty()) {
            return "Im Rucksack ist kein " + word + ".";
        }
        int total = 0;
        Set<Integer> pages = new java.util.TreeSet<>();
        for (int i : hits) {
            total += pack.slots.get(i).getAmount();
            pages.add(i / RucksackLogic.PER_PAGE + 1);
        }
        int first = hits.get(0) / RucksackLogic.PER_PAGE;
        open(p, pack, first);
        String where = pages.size() == 1 ? "Seite " + pages.iterator().next()
                : "Seiten " + pages.stream().limit(6).map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                        + (pages.size() > 6 ? " …" : "");
        LOG.info(() -> "rucksack | suche | spieler=" + p.getName() + " wort=" + Texts.clean(word, 40) + " treffer="
                + hits.size());
        return word + ": " + total + " Stück auf " + where + " – ich hab dir Seite " + (first + 1) + " aufgemacht.";
    }

    /**
     * Nimmt bis zu count Stück der Sorte aus dem eigenen Rucksack ins Inventar. Liefert die Menge, die jetzt im
     * Inventar ist (0 = keine da), -1 = gesperrt (Meldung ist raus). Was nicht ins Inventar passt, bleibt im Rucksack.
     */
    int take(Player p, Material m, int count) {
        Pack pack = ownPack(p);
        if (pack == null) {
            return -1;
        }
        View v = view(p);
        if (v != null && v.pack == pack) {
            store(v);
        }
        List<ItemStack> got = RucksackLogic.take(pack.slots, s -> s.getType() == m && !isRucksack(s), count, OPS);
        int given = 0;
        for (ItemStack s : got) {
            int amount = s.getAmount();
            Map<Integer, ItemStack> left = p.getInventory().addItem(s);
            int back = left.values().stream().mapToInt(ItemStack::getAmount).sum();
            given += amount - back;
            for (ItemStack r : left.values()) {
                int still = RucksackLogic.deposit(pack.slots, r, OPS);
                if (still > 0) {
                    // Kann nicht passieren (der Platz wurde gerade frei) – trotzdem nichts verschlucken.
                    r.setAmount(still);
                    p.getWorld().dropItem(p.getLocation(), r);
                }
            }
        }
        pack.dirty = true;
        if (v != null && v.pack == pack) {
            show(v);
        }
        int g = given;
        LOG.info(() -> "rucksack | herausgenommen | spieler=" + p.getName() + " item=" + m.getKey().getKey()
                + " wunsch=" + count + " gegeben=" + g);
        return given;
    }

    /** Schaltet eine Sorte im Einsammel-Filter um; liefert den Satz dazu. */
    String toggleCollect(Player p, Material m) {
        Pack pack = ownPack(p);
        if (pack == null) {
            return null;
        }
        boolean on = pack.collect.add(m);
        if (!on) {
            pack.collect.remove(m);
        }
        pack.dirty = true;
        save(pack);
        View v = view(p);
        if (v != null && v.pack == pack) {
            show(v);
        }
        LOG.info(() -> "rucksack | einsammeln | spieler=" + p.getName() + " item=" + m.getKey().getKey() + " an=" + on);
        return on ? materialName(m) + " geht ab jetzt beim Aufheben direkt in deinen Rucksack."
                : materialName(m) + " sammle ich nicht mehr ein.";
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        Pack pack = packs.get(p.getUniqueId());
        if (pack == null) {
            // Nur laden, wenn es überhaupt eine Datei gibt – sonst gibt es auch keinen Filter.
            if (!Files.exists(dir.resolve(p.getUniqueId() + ".yml"))) {
                return;
            }
            pack = pack(p.getUniqueId());
            if (pack == null) {
                return;
            }
        }
        ItemStack it = e.getItem().getItemStack();
        if (!pack.collect.contains(it.getType()) || blocked(p) != null || !hasRucksack(p) || isRucksack(it)) {
            return;
        }
        if (view(p) != null && view(p).pack == pack) {
            // Offenes Fenster zeigt die Plätze – hineinlegen hinter seinem Rücken überschriebe es beim Schließen.
            return;
        }
        int amount = it.getAmount();
        int left = RucksackLogic.deposit(pack.slots, it, OPS);
        if (left == amount) {
            return;
        }
        e.setCancelled(true);
        pack.dirty = true;
        if (left == 0) {
            e.getItem().remove();
        } else {
            ItemStack rest = it.clone();
            rest.setAmount(left);
            e.getItem().setItemStack(rest);
        }
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.3f, 1.4f);
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent("→ Rucksack: " + (amount - left) + " " + materialName(it.getType())));
    }

    // ---------------------------------------------------------------- Teamkisten

    private void loadTeams() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String n = f.getFileName().toString();
                if (!n.startsWith("team-") || !n.endsWith(".yml")) {
                    continue;
                }
                String name = n.substring(5, n.length() - 4);
                if (!RucksackTeams.validName(name)) {
                    continue;
                }
                YamlConfiguration head = RucksackFile.load(f).config();
                UUID creator = null;
                if (head != null) {
                    try {
                        creator = UUID.fromString(head.getString("team.besitzer", ""));
                    } catch (IllegalArgumentException e) {
                        creator = null;
                    }
                }
                Pack t = creator == null ? null : load(f, creator, name);
                if (t != null) {
                    teams.put(name, t);
                } else {
                    broken.add(f);
                    LOG.severe(() -> "rucksack | teamkiste_kaputt | datei=" + f + " – bleibt zu");
                }
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "rucksack | teams_lesen_fehlgeschlagen | ordner=" + dir, e);
        }
    }

    private void team(Player p, String[] args) {
        if (args.length < 2) {
            List<String> mine = teams.values().stream().filter(t -> t.members.contains(p.getUniqueId()))
                    .map(t -> t.team).toList();
            Texts.info(p, mine.isEmpty() ? "Du bist in keiner Teamkiste. Gründen: /rucksack team <name>"
                    : "Deine Teamkisten: " + String.join(", ", mine) + " – öffnen: /rucksack team <name>");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!RucksackTeams.validName(name)) {
            Texts.info(p, "Teamnamen: 2–16 Zeichen aus a–z, 0–9, - und _.");
            return;
        }
        Pack t = teams.get(name);
        boolean admin = p.hasPermission("baumeister.admin");
        if (args.length >= 3) {
            String action = args[2].toLowerCase(Locale.ROOT);
            if (t == null) {
                Texts.info(p, "Die Teamkiste " + name + " gibt es nicht.");
                return;
            }
            if (action.equals("verlassen")) {
                if (t.members.remove(p.getUniqueId())) {
                    t.dirty = true;
                    save(t);
                    Texts.info(p, "Du bist nicht mehr in der Teamkiste " + name + ".");
                }
                return;
            }
            if (args.length < 4 || !(action.equals("einladen") || action.equals("rauswerfen"))) {
                Texts.info(p, "/rucksack team " + name + " einladen|rauswerfen <spieler> oder … verlassen");
                return;
            }
            if (!t.owner.equals(p.getUniqueId()) && !admin) {
                Texts.info(p, "Mitglieder verwaltet nur, wer die Teamkiste gegründet hat.");
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[3]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                Texts.info(p, args[3] + " war noch nie auf dem Server.");
                return;
            }
            boolean changed = action.equals("einladen") ? t.members.add(target.getUniqueId())
                    : !target.getUniqueId().equals(t.owner) && t.members.remove(target.getUniqueId());
            if (changed) {
                t.dirty = true;
                save(t);
                LOG.info(() -> "rucksack | team_" + action + " | team=" + name + " spieler=" + target.getName()
                        + " von=" + p.getName());
                if (action.equals("rauswerfen") && target.getPlayer() != null && view(target.getPlayer()) != null
                        && view(target.getPlayer()).pack == t) {
                    target.getPlayer().closeInventory();
                }
            }
            Texts.info(p, changed ? (action.equals("einladen") ? target.getName() + " ist jetzt in der Teamkiste "
                    + name + "." : target.getName() + " ist raus.") : "Da hat sich nichts geändert.");
            return;
        }
        String why = blocked(p);
        if (why != null) {
            Texts.info(p, why);
            return;
        }
        if (t == null) {
            Path f = dir.resolve("team-" + name + ".yml");
            if (broken.contains(f) || Files.exists(f)) {
                Texts.info(p, "Die Teamkiste " + name + " lässt sich gerade nicht öffnen (Datei beschädigt).");
                return;
            }
            long created = teams.values().stream().filter(x -> x.owner.equals(p.getUniqueId())).count();
            if (!admin && created >= MAX_TEAMS_CREATED) {
                Texts.info(p, "Du hast schon " + MAX_TEAMS_CREATED + " Teamkisten gegründet – mehr geht nicht.");
                return;
            }
            t = new Pack(f, p.getUniqueId(), name,
                    new ArrayList<>(Collections.nCopies(settings.pages() * RucksackLogic.PER_PAGE, null)));
            t.members.add(p.getUniqueId());
            t.dirty = true;
            teams.put(name, t);
            save(t);
            LOG.info(() -> "rucksack | team_gegründet | team=" + name + " von=" + p.getName());
            Texts.info(p, "Teamkiste " + name + " gegründet. Lade Freunde ein: /rucksack team " + name
                    + " einladen <spieler>");
        }
        if (!t.members.contains(p.getUniqueId()) && !admin) {
            Texts.info(p, "Du bist nicht in der Teamkiste " + name + " – frag " + names(Set.of(t.owner)) + ".");
            return;
        }
        open(p, t, 0);
    }

    /** Teamkisten, in denen p Mitglied ist (für das Hilfebuch). */
    List<String> teamsOf(Player p) {
        return teams.values().stream().filter(t -> t.members.contains(p.getUniqueId())).map(t -> t.team).toList();
    }

    // ---------------------------------------------------------------- Ereignisse

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack it = e.getItem();
        UUID owner = owner(it);
        if (owner == null) {
            return;
        }
        // Nie als Kiste abstellen.
        e.setUseItemInHand(Event.Result.DENY);
        // Rechtsklick auf Türen, Truhen, Knöpfe … bleibt deren Sache (sonst öffnete der Rucksack statt der Truhe).
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable() && !e.getPlayer().isSneaking()) {
            return;
        }
        e.setUseInteractedBlock(Event.Result.DENY);
        Player p = e.getPlayer();
        if (!owner.equals(p.getUniqueId())) {
            Texts.info(p, "Dieser Rucksack gehört jemand anderem – hol dir deinen mit /rucksack holen.");
            return;
        }
        open(p);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isRucksack(e.getItemInHand())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isRucksack(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            Texts.info(e.getPlayer(), "Dein Rucksack bleibt bei dir.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof View v)) {
            // Rechtsklick auf die Kiste im eigenen Inventar öffnet den Rucksack.
            if (top.getType() == InventoryType.CRAFTING && e.getClick() == ClickType.RIGHT
                    && p.getUniqueId().equals(owner(e.getCurrentItem())) && p.getItemOnCursor().getType().isAir()) {
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> open(p));
            }
            return;
        }
        int raw = e.getRawSlot();
        boolean inTop = raw >= 0 && raw < 54;
        // Knopfreihe: nie etwas hinein oder heraus.
        if (inTop && raw >= RucksackLogic.PER_PAGE) {
            e.setCancelled(true);
            ClickType c = e.getClick();
            if (c == ClickType.LEFT || c == ClickType.SHIFT_LEFT || c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) {
                // Sorte am Mauszeiger jetzt merken: Im nächsten Takt kann der Cursor schon anders aussehen.
                ItemStack cursor = e.getCursor();
                Material held = cursor == null || cursor.getType().isAir() ? null : cursor.getType();
                Bukkit.getScheduler().runTask(plugin, () -> button(p, v, raw, c.isShiftClick(), held));
            }
            return;
        }
        // Die Kiste selbst gehört nicht in den Rucksack: Cursor, Shift-Klick, Zahlentaste, Zweithand.
        boolean cursorPack = isRucksack(e.getCursor());
        boolean shiftPack = !inTop && e.getClick().isShiftClick() && isRucksack(e.getCurrentItem());
        boolean hotbarPack = inTop && e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0
                && isRucksack(p.getInventory().getItem(e.getHotbarButton()));
        boolean offhandPack = inTop && e.getClick() == ClickType.SWAP_OFFHAND
                && isRucksack(p.getInventory().getItemInOffHand());
        if ((inTop && cursorPack) || shiftPack || hotbarPack || offhandPack) {
            e.setCancelled(true);
            Texts.info(p, "Der Rucksack passt nicht in sich selbst.");
            return;
        }
        // Doppelklick sammelt sonst auch aus der Knopfreihe (nur gleiche Sorte, Knöpfe sind markiert – trotzdem zu).
        if (e.getClick() == ClickType.DOUBLE_CLICK && isButton(e.getCursor())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View)) {
            return;
        }
        boolean intoTop = e.getRawSlots().stream().anyMatch(s -> s < 54);
        boolean intoButtons = e.getRawSlots().stream().anyMatch(s -> s >= RucksackLogic.PER_PAGE && s < 54);
        if (intoButtons || (intoTop && isRucksack(e.getOldCursor()))) {
            e.setCancelled(true);
        }
    }

    private void button(Player p, View v, int slot, boolean shift, Material held) {
        if (p.getOpenInventory().getTopInventory() != v.inventory) {
            return;
        }
        switch (slot) {
            case RucksackLogic.SLOT_PREV, RucksackLogic.SLOT_NEXT -> turnTo(p, v,
                    RucksackLogic.turn(v.page, v.pages, slot == RucksackLogic.SLOT_NEXT, shift));
            case RucksackLogic.SLOT_INFO -> turnTo(p, v, 0);
            case RucksackLogic.SLOT_DEPOSIT -> deposit(p, v);
            case RucksackLogic.SLOT_SORT -> {
                store(v);
                RucksackLogic.sort(v.pack.slots, OPS, ORDER);
                show(v);
                p.playSound(p.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.6f, 1.2f);
                Texts.info(p, "Sortiert: " + RucksackLogic.used(v.pack.slots) + " Plätze belegt.");
            }
            case SLOT_COLLECT -> {
                if (v.pack.team != null) {
                    return;
                }
                if (shift) {
                    v.pack.collect.clear();
                    v.pack.dirty = true;
                    show(v);
                    Texts.info(p, "Einsammeln aus – alles landet wieder normal im Inventar.");
                } else if (held != null && !isRucksack(new ItemStack(held))) {
                    Texts.info(p, toggleCollect(p, held));
                } else {
                    Texts.info(p, "Halte einen Gegenstand am Mauszeiger und klick auf den Beutel, dann sammle ich "
                            + "diese Sorte beim Aufheben direkt ein.");
                }
            }
            default -> {
            }
        }
    }

    /** Titel zeigt die Seite. Ohne setTitle (ältere Server) steht die Seite weiter auf dem Buch-Knopf. */
    private static void retitle(Player p, View v) {
        try {
            p.getOpenInventory().setTitle(title(v));
        } catch (UnsupportedOperationException | NoSuchMethodError e) {
            LOG.fine(() -> "rucksack | titel_unveränderbar | " + e);
        }
    }

    /** Inventar ohne Schnellleiste (Plätze 9–35) einlagern; Rucksack-Kisten bleiben. */
    private void deposit(Player p, View v) {
        store(v);
        PlayerInventory inv = p.getInventory();
        int moved = 0;
        boolean full = false;
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir() || isRucksack(s)) {
                continue;
            }
            int left = RucksackLogic.deposit(v.pack.slots, s, OPS);
            moved += s.getAmount() - left;
            if (left == 0) {
                inv.setItem(i, null);
            } else {
                s.setAmount(left);
                inv.setItem(i, s);
                full = true;
            }
        }
        v.pack.dirty = true;
        show(v);
        p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.7f, 1.0f);
        Texts.info(p, moved == 0 ? "Nichts zum Einlagern (die Schnellleiste bleibt, wie sie ist)."
                : moved + " Sachen eingelagert" + (full ? " – es ist voll." : "."));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof View v)) {
            return;
        }
        store(v);
        save(v.pack);
        openBy.remove(v.pack.file, e.getPlayer().getUniqueId());
        if (e.getPlayer() instanceof Player p) {
            p.playSound(p.getLocation(), Sound.BLOCK_BARREL_CLOSE, 0.6f, 1.2f);
        }
    }

    @EventHandler
    public void onModeChange(PlayerGameModeChangeEvent e) {
        if (!settings.modes().contains(e.getNewGameMode()) && view(e.getPlayer()) != null) {
            e.getPlayer().closeInventory();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // Einen Takt später: Andere Plugins (Essentials) stellen das Inventar beim Join noch um.
        if (settings.giveOnJoin()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    giveIfMissing(p);
                }
            });
        }
    }

    /** Speichern, aber im Speicher behalten (siehe Kopf: kein Neuladen vor dem Schreiben). */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Pack pack = packs.get(e.getPlayer().getUniqueId());
        if (pack != null) {
            save(pack);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getDrops().removeIf(this::isRucksack)) {
            giveBack.add(e.getEntity().getUniqueId());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (giveBack.remove(p.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    giveIfMissing(p);
                }
            });
        }
    }

    // ---------------------------------------------------------------- speichern

    private void saveDirty() {
        // Offene Fenster zuerst übernehmen, sonst stünde nach einem Absturz der Stand vom Öffnen in der Datei.
        for (Player p : Bukkit.getOnlinePlayers()) {
            View v = view(p);
            if (v != null) {
                store(v);
            }
        }
        allPacks().filter(pack -> pack.dirty).toList().forEach(this::save);
    }

    private void save(Pack pack) {
        if (!pack.dirty || broken.contains(pack.file)) {
            return;
        }
        pack.dirty = false;
        Path f = pack.file;
        String content = serialize(pack);
        try {
            writer.execute(() -> {
                try {
                    RucksackFile.write(f, content);
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "rucksack | schreiben_fehlgeschlagen | datei=" + f, e);
                    // Beim nächsten Anlass erneut: im Hauptthread als geändert markieren.
                    Bukkit.getScheduler().runTask(plugin, () -> pack.dirty = true);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Nur während des Herunterfahrens: dann direkt schreiben.
            try {
                RucksackFile.write(f, content);
            } catch (IOException ex) {
                LOG.log(Level.SEVERE, "rucksack | schreiben_fehlgeschlagen | datei=" + f, ex);
            }
        }
    }

    /** Hauptthread (ItemMeta). */
    private static String serialize(Pack pack) {
        YamlConfiguration yc = new YamlConfiguration();
        yc.set("version", RucksackFile.VERSION);
        if (pack.team != null) {
            yc.set("team.name", pack.team);
            yc.set("team.besitzer", pack.owner.toString());
            yc.set("team.mitglieder", pack.members.stream().map(UUID::toString).toList());
        }
        if (!pack.collect.isEmpty()) {
            yc.set("einsammeln", pack.collect.stream().map(m -> m.getKey().getKey()).toList());
        }
        for (int i = 0; i < pack.slots.size(); i++) {
            ItemStack s = pack.slots.get(i);
            if (s != null) {
                yc.set("seiten." + i / RucksackLogic.PER_PAGE + "." + i % RucksackLogic.PER_PAGE, s);
            }
        }
        return yc.saveToString();
    }

    // ---------------------------------------------------------------- Befehl /rucksack

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Der Rucksack gehört einem Spieler – von der Konsole aus geht das nicht.");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String rest = args.length < 2 ? "" : String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        switch (sub) {
            case "holen" -> {
                if (giveIfMissing(p)) {
                    Texts.info(p, "Dein Rucksack ist in deinem Inventar – Rechtsklick öffnet ihn.");
                }
            }
            case "suche", "suchen", "such" -> {
                if (rest.isBlank()) {
                    Texts.info(p, "/rucksack suche <gegenstand>, z. B. /rucksack suche diamant");
                } else {
                    String answer = search(p, rest);
                    if (answer != null) {
                        Texts.info(p, answer);
                    }
                }
            }
            case "einsammeln" -> collectCommand(p, rest);
            case "team" -> team(p, args);
            case "" -> open(p);
            default -> Texts.info(p, "/rucksack [holen | suche <gegenstand> | einsammeln [<gegenstand>|aus] | "
                    + "team [<name> [einladen|rauswerfen <spieler> | verlassen]]]");
        }
        return true;
    }

    private void collectCommand(Player p, String rest) {
        Pack pack = ownPack(p);
        if (pack == null) {
            return;
        }
        if (rest.isBlank()) {
            Texts.info(p, pack.collect.isEmpty() ? "Einsammeln ist aus. /rucksack einsammeln <gegenstand> schaltet "
                    + "eine Sorte ein." : "Ich sammle ein: " + String.join(", ", pack.collect.stream()
                            .map(RucksackService::materialName).toList()));
            return;
        }
        if (rest.equalsIgnoreCase("aus")) {
            pack.collect.clear();
            pack.dirty = true;
            save(pack);
            Texts.info(p, "Einsammeln aus.");
            return;
        }
        Optional<Material> m = material(rest);
        if (m.isEmpty()) {
            Texts.info(p, "„" + Texts.clean(rest, 40) + "“ kenne ich nicht als Gegenstand.");
            return;
        }
        Texts.info(p, toggleCollect(p, m.get()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String a = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 1 -> List.of("holen", "suche", "einsammeln", "team");
            case 2 -> args[0].equalsIgnoreCase("team") && sender instanceof Player p
                    ? teams.values().stream().filter(t -> t.members.contains(p.getUniqueId())).map(t -> t.team).toList()
                    : args[0].equalsIgnoreCase("einsammeln") ? List.of("aus") : List.of();
            case 3 -> args[0].equalsIgnoreCase("team") ? List.of("einladen", "rauswerfen", "verlassen") : List.of();
            case 4 -> args[0].equalsIgnoreCase("team") ? Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .toList() : List.of();
            default -> List.of();
        };
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(a)).toList();
    }
}
