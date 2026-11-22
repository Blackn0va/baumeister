package com.blackn0va.baumeister;

import com.blackn0va.baumeister.module.ModuleDef;
import com.blackn0va.baumeister.module.ModuleEntry;
import com.blackn0va.baumeister.module.ModuleState;
import com.blackn0va.baumeister.module.ModuleSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Kistenmenü „Module“ (/bm module menü, Vertrag v012 AK-37) im Stil von SchematicMenu: je Modul ein Gegenstand, Name
 * in der Farbe des Zustands, Beschreibung, Zustand und Grund in der Beschreibung des Gegenstands.
 *
 * Ein Klick schaltet NICHT selbst, sondern ruft performCommand("bm module an|aus &lt;id&gt;") – denselben Weg wie der
 * Befehl, mit derselben Rechteprüfung in ModuleSwitch (fail-closed, ASVS V4.1.1). Das Menü blendet den Klick für
 * Nicht-Admins nur aus (clickCommand); wer das Ausblenden entfernt, gibt trotzdem kein Recht frei, weil ModuleSwitch
 * ohne baumeister.admin ablehnt. Wie in SchematicMenu wird jeder Klick im Fenster abgebrochen (auch im eigenen
 * Inventar darunter), damit nichts hinein- oder herauswandert.
 *
 * Threads: Hauptthread. Fehler: keine.
 */
final class ModuleMenu implements Listener {

    static final int SIZE = 36;
    static final int MODULE_SLOTS = 27;
    private static final int INFO = 31;
    private static final int CLOSE = 35;

    private final BaumeisterPlugin plugin;
    private final Supplier<List<ModuleEntry>> entries;

    ModuleMenu(BaumeisterPlugin plugin, Supplier<List<ModuleEntry>> entries) {
        this.plugin = plugin;
        this.entries = entries;
    }

    private static final class View implements InventoryHolder {
        final List<ModuleEntry> shown = new ArrayList<>();
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Bild je Modul; was fehlt, wird Papier. */
    private static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("bau-ki", "bricks"), Map.entry("begleiter", "allay_spawn_egg"),
            Map.entry("abenteuer", "diamond_sword"), Map.entry("stadtleben", "villager_spawn_egg"),
            Map.entry("stadt", "filled_map"), Map.entry("erde", "grass_block"), Map.entry("weltbau", "compass"),
            Map.entry("karte", "map"), Map.entry("rucksack", "chest"), Map.entry("sprache", "note_block"),
            Map.entry("voicechat", "jukebox"), Map.entry("aufgaben", "writable_book"),
            Map.entry("warp", "ender_pearl"), Map.entry("portale", "obsidian"), Map.entry("hilfe", "book"),
            Map.entry("bauteile", "wooden_axe"), Map.entry("reparatur", "anvil"),
            Map.entry("mods-download", "hopper"), Map.entry("fernverbindung", "lightning_rod"),
            Map.entry("host-anzeige", "painting"));

    static String icon(String id) {
        return ICONS.getOrDefault(id, "paper");
    }

    /** Befehl für einen Klick, oder leer: nur Admins, nur Module mit eigenem Schalter. */
    static Optional<String> clickCommand(ModuleEntry m, boolean admin) {
        if (!admin || !m.def().switchable()) {
            return Optional.empty();
        }
        return Optional.of("bm module " + (m.on() ? "aus" : "an") + " " + m.def().id());
    }

    /** Beschreibung des Gegenstands. Ohne Bukkit-Inventar, damit ModuleMenuTest sie prüfen kann. */
    static List<String> lore(ModuleEntry m, boolean admin) {
        ModuleDef d = m.def();
        List<String> lore = new ArrayList<>(SchematicMenu.wrap(d.description(), 36, 4));
        lore.add("");
        lore.add(ChatColor.GRAY + "Zustand: " + m.state().color() + m.state().wire());
        if (!m.reason().isEmpty()) {
            lore.addAll(SchematicMenu.wrap(m.reason(), 36, 3));
        }
        if (!d.needs().isEmpty()) {
            lore.add(ChatColor.DARK_GRAY + "Braucht: " + String.join(", ", d.needs()));
        }
        if (d.switchable()) {
            lore.add(ChatColor.DARK_GRAY + "Schalter: " + d.key() + " = " + (m.on() ? "an" : "aus")
                    + (d.effect() == ModuleDef.Effect.SOFORT ? " (wirkt sofort)" : " (wirkt nach Neustart)"));
        } else {
            lore.add(ChatColor.DARK_GRAY + "Fester Teil – kein eigener Schalter");
        }
        lore.add("");
        Optional<String> cmd = clickCommand(m, admin);
        if (cmd.isPresent()) {
            lore.add(ChatColor.YELLOW + "Klick: " + (m.on() ? "ausschalten" : "einschalten"));
        } else if (d.switchable()) {
            lore.add(ChatColor.DARK_GRAY + "Schalten nur mit " + ModuleSwitch.ADMIN);
        }
        return lore;
    }

    void open(Player p) {
        List<ModuleEntry> all = entries.get();
        boolean admin = p.hasPermission(ModuleSwitch.ADMIN);
        View v = new View();
        Inventory inv = Bukkit.createInventory(v, SIZE, "Module");
        v.inventory = inv;
        int slot = 0;
        for (ModuleEntry m : all) {
            if (slot >= MODULE_SLOTS) {
                break;
            }
            v.shown.add(m);
            inv.setItem(slot++, item(icon(m.def().id()), m.state().color() + m.def().name(), lore(m, admin)));
        }
        ItemStack pane = item("gray_stained_glass_pane", " ", List.of());
        for (int s = MODULE_SLOTS; s < SIZE; s++) {
            inv.setItem(s, pane);
        }
        long active = all.stream().filter(m -> m.state() == ModuleState.AKTIV).count();
        inv.setItem(INFO, item("book", ChatColor.GOLD + "Module: " + active + " von " + all.size() + " aktiv",
                List.of(ChatColor.GRAY + "grün aktiv · grau aus", ChatColor.GRAY + "gelb wartet · rot Fehler",
                        ChatColor.GRAY + (admin ? "Klick auf ein Modul schaltet es." : "Schalten dürfen nur Admins."))));
        inv.setItem(CLOSE, item("barrier", ChatColor.RED + "Schließen", List.of()));
        p.openInventory(inv);
    }

    private static ItemStack item(String material, String name, List<String> lore) {
        Material m = Material.matchMaterial(material);
        ItemStack it = new ItemStack(m == null ? Material.PAPER : m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View v) || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot == CLOSE) {
            later(p, p::closeInventory);
            return;
        }
        if (slot < 0 || slot >= v.shown.size()) {
            return;
        }
        ModuleEntry m = v.shown.get(slot);
        Optional<String> cmd = clickCommand(m, p.hasPermission(ModuleSwitch.ADMIN));
        if (cmd.isEmpty()) {
            return;
        }
        later(p, () -> {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
            p.performCommand(cmd.get());
            open(p);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) {
            e.setCancelled(true);
        }
    }

    /** Erst im nächsten Takt – mitten im Klick-Ereignis ein Fenster öffnen bringt Bukkit durcheinander. */
    private void later(Player p, Runnable r) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                r.run();
            }
        });
    }
}
