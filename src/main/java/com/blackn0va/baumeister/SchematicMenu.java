package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.RequestHints;
import com.blackn0va.baumeister.plan.SchematicCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

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
 * Katalog der Bauwerk-Dateien als Kistenfenster (/bm katalog [suchwort]). Wunsch 2026-11-08. Im Hilfebuch passen 19
 * Zeichen in eine Zeile
 * und 24 Einträge in eine Liste – bei Hunderten Schematics (Technik-Wiki allein über 800) wäre das Buch ein
 * Telefonbuch. Hier: 45 Familien je Seite, Klick baut, Familien mit mehreren Varianten öffnen erst ihre Varianten.
 *
 * Gebaut wird über performCommand("bm bau &lt;Familie&gt; Variante &lt;n&gt;") – denselben Weg wie der Chat, mit allen Rechte-,
 * Grundstücks- und Größenprüfungen von BuildService. Das Fenster selbst prüft nichts und setzt nichts. „Variante n“
 * steht immer dabei: Ohne sie fiele „Burg“ zuerst auf die Bauform von QuickForms statt auf die Datei.
 *
 * Jeder Klick in einem Katalogfenster wird abgebrochen, auch im eigenen Inventar darunter (Umschalt-Klick legte sonst
 * Gegenstände hinein, die beim Schließen verschwänden).
 *
 * Threads: Hauptthread. Fehler: keine – fehlende Sammlung heißt eine Chatzeile.
 */
final class SchematicMenu implements Listener {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final int PER_PAGE = 45;
    private static final int PREV = 45;
    private static final int SEARCH = 47;
    private static final int INFO = 49;
    private static final int CLOSE = 51;
    private static final int NEXT = 53;

    private final BaumeisterPlugin plugin;

    SchematicMenu(BaumeisterPlugin plugin) {
        this.plugin = plugin;
    }

    /** Ein offenes Katalogfenster: Suchwort, Seite und – in der Variantenansicht – die Familie. */
    private static final class View implements InventoryHolder {
        final String filter;
        final int page;
        final String family;
        final int backPage;
        final List<String> families = new ArrayList<>();
        final List<SchematicCatalog.Entry> entries = new ArrayList<>();
        Inventory inventory;

        View(String filter, int page, String family, int backPage) {
            this.filter = filter;
            this.page = page;
            this.family = family;
            this.backPage = backPage;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Seitenzahl für n Einträge, mindestens 1. */
    static int pages(int n) {
        return Math.max(1, (n + PER_PAGE - 1) / PER_PAGE);
    }

    /** Einträge der Seite page (0-basiert, geklemmt). */
    static <T> List<T> slice(List<T> all, int page) {
        int p = Math.clamp(page, 0, pages(all.size()) - 1);
        return all.subList(Math.min(all.size(), p * PER_PAGE), Math.min(all.size(), (p + 1) * PER_PAGE));
    }

    /** Bild für eine Familie nach Stichwort (Material-Name); sonst Ziegel. */
    static String icon(String family) {
        String f = " " + RequestHints.normalize(family) + " ";
        String[][] words = {
            {"taktgeber", "clock"}, {"uhr", "clock"}, {"tuer", "iron_door"}, {"tor", "iron_door"}, // umlaut-ok
            {"aufzug", "slime_block"}, {"lift", "slime_block"}, {"farm", "wheat"}, {"lampe", "redstone_lamp"},
            {"licht", "redstone_lamp"}, {"zaehler", "comparator"}, {"addierer", "comparator"}, // umlaut-ok
            {"speicher", "repeater"}, {"flipflop", "repeater"}, {"logik", "redstone_torch"},
            {"gatter", "redstone_torch"}, {"kanone", "tnt"}, {"bahn", "rail"}, {"lore", "minecart"},
            {"kanal", "water_bucket"}, {"wasser", "water_bucket"}, {"bruecke", "stone_bricks"}, // umlaut-ok
            {"burg", "stone_bricks"}, {"haus", "oak_planks"}, {"turm", "cobblestone"}, {"schiff", "oak_boat"},
            {"kolben", "piston"}, {"sortier", "hopper"}, {"trichter", "hopper"}, {"anzeige", "redstone_lamp"}};
        for (String[] w : words) {
            if (f.contains(w[0])) {
                return w[1];
            }
        }
        return "bricks";
    }

    /** Katalog öffnen (Seite 0); filter leer = alle. */
    void open(Player p, String filter) {
        openFamilies(p, filter == null ? "" : filter.strip(), 0);
    }

    private SchematicCatalog catalog() {
        FileSchematics f = plugin.fileSchematics();
        return f == null ? null : f.catalog();
    }

    private void openFamilies(Player p, String filter, int page) {
        SchematicCatalog cat = catalog();
        if (cat == null || cat.size() == 0) {
            Texts.info(p, "Noch keine Schematics. Der Admin legt .schem, .schematic, .litematic oder .nbt in "
                    + "plugins/Baumeister/schematics/.");
            return;
        }
        List<Map.Entry<String, Integer>> fams = cat.families(filter);
        if (fams.isEmpty()) {
            Texts.info(p, "Zu „" + Texts.clean(filter, 30) + "“ habe ich nichts – /bm katalog zeigt alle.");
            return;
        }
        int pg = Math.clamp(page, 0, pages(fams.size()) - 1);
        View v = new View(filter, pg, null, 0);
        String title = "Sammlung" + (filter.isEmpty() ? "" : " „" + Texts.clean(filter, 12) + "“") + " · "
                + (pg + 1) + "/" + pages(fams.size());
        Inventory inv = Bukkit.createInventory(v, 54, title);
        v.inventory = inv;
        int slot = 0;
        for (Map.Entry<String, Integer> f : slice(fams, pg)) {
            v.families.add(f.getKey());
            List<SchematicCatalog.Entry> vars = cat.variants(f.getKey());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + (f.getValue() > 1 ? f.getValue() + " Varianten" : "1 Bauwerk"));
            String desc = vars.isEmpty() ? "" : vars.getFirst().description();
            if (!desc.isBlank()) {
                lore.addAll(wrap(desc, 36, 3));
            }
            lore.add("");
            lore.add(ChatColor.YELLOW + (f.getValue() > 1 ? "Klick: Varianten zeigen" : "Klick: vor dir bauen"));
            inv.setItem(slot++, item(icon(f.getKey()), ChatColor.GOLD + f.getKey(), lore));
        }
        controls(inv, pg, pages(fams.size()), cat.size() + " Bauwerke, " + cat.families("").size() + " Familien",
                false);
        p.openInventory(inv);
    }

    private void openVariants(Player p, String family, String filter, int backPage, int page) {
        SchematicCatalog cat = catalog();
        List<SchematicCatalog.Entry> vars = cat == null ? List.of() : cat.variants(family);
        if (vars.isEmpty()) {
            openFamilies(p, filter, backPage);
            return;
        }
        int pg = Math.clamp(page, 0, pages(vars.size()) - 1);
        View v = new View(filter, pg, family, backPage);
        Inventory inv = Bukkit.createInventory(v, 54, Texts.clean(family, 24) + " · " + (pg + 1) + "/"
                + pages(vars.size()));
        v.inventory = inv;
        int slot = 0;
        for (SchematicCatalog.Entry e : slice(vars, pg)) {
            v.entries.add(e);
            List<String> lore = new ArrayList<>();
            if (!e.description().isBlank()) {
                lore.addAll(wrap(e.description(), 36, 4));
            }
            if (!e.source().isBlank()) {
                lore.add(ChatColor.DARK_GRAY + "Quelle: " + Texts.clean(e.source(), 30));
            }
            lore.add("");
            lore.add(ChatColor.YELLOW + "Klick: vor dir bauen");
            inv.setItem(slot++, item(icon(family), ChatColor.GOLD + "Variante " + e.variant(), lore));
        }
        controls(inv, pg, pages(vars.size()), vars.size() + " Varianten von " + Texts.clean(family, 24), true);
        p.openInventory(inv);
    }

    private static void controls(Inventory inv, int page, int pages, String info, boolean back) {
        ItemStack pane = item("gray_stained_glass_pane", " ", List.of());
        for (int s = PER_PAGE; s < 54; s++) {
            inv.setItem(s, pane);
        }
        if (page > 0) {
            inv.setItem(PREV, item("arrow", ChatColor.WHITE + "◀ Seite " + page, List.of()));
        }
        if (page + 1 < pages) {
            inv.setItem(NEXT, item("arrow", ChatColor.WHITE + "Seite " + (page + 2) + " ▶", List.of()));
        }
        inv.setItem(INFO, item("book", ChatColor.GOLD + "Seite " + (page + 1) + "/" + pages,
                List.of(ChatColor.GRAY + info)));
        inv.setItem(SEARCH, back ? item("oak_door", ChatColor.WHITE + "Zurück zur Übersicht", List.of())
                : item("oak_sign", ChatColor.WHITE + "Suchen …",
                        List.of(ChatColor.GRAY + "Legt /bm katalog in die Chatzeile –", ChatColor.GRAY
                                + "dann Suchwort ergänzen.")));
        inv.setItem(CLOSE, item("barrier", ChatColor.RED + "Schließen", List.of()));
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

    /** Text auf Zeilen zu höchstens width Zeichen (nach Wörtern), höchstens max Zeilen, grau. */
    static List<String> wrap(String text, int width, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : Texts.clean(text, width * max).split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + w.length() > width) {
                out.add(ChatColor.GRAY + line.toString());
                line.setLength(0);
                if (out.size() == max) {
                    return out;
                }
            }
            line.append(line.length() == 0 ? "" : " ").append(w);
        }
        if (line.length() > 0 && out.size() < max) {
            out.add(ChatColor.GRAY + line.toString());
        }
        return out;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof View v) || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        e.setCancelled(true);
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) {
            return;
        }
        switch (slot) {
            case PREV -> later(p, () -> reopen(p, v, v.page - 1));
            case NEXT -> later(p, () -> reopen(p, v, v.page + 1));
            case CLOSE -> later(p, p::closeInventory);
            case SEARCH -> later(p, () -> {
                if (v.family != null) {
                    openFamilies(p, v.filter, v.backPage);
                } else {
                    p.closeInventory();
                    p.performCommand("hilfe tippen /bm katalog");
                }
            });
            default -> {
                if (slot >= PER_PAGE) {
                    return;
                }
                if (v.family == null && slot < v.families.size()) {
                    String fam = v.families.get(slot);
                    SchematicCatalog cat = catalog();
                    List<SchematicCatalog.Entry> vars = cat == null ? List.of() : cat.variants(fam);
                    if (vars.size() > 1) {
                        later(p, () -> openVariants(p, fam, v.filter, v.page, 0));
                    } else if (vars.size() == 1) {
                        later(p, () -> build(p, fam, vars.getFirst().variant()));
                    }
                } else if (v.family != null && slot < v.entries.size()) {
                    SchematicCatalog.Entry en = v.entries.get(slot);
                    later(p, () -> build(p, v.family, en.variant()));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof View) {
            e.setCancelled(true);
        }
    }

    private void reopen(Player p, View v, int page) {
        if (v.family == null) {
            openFamilies(p, v.filter, page);
        } else {
            openVariants(p, v.family, v.filter, v.backPage, page);
        }
    }

    /** Fenster wechseln erst im nächsten Takt – mitten im Klick-Ereignis öffnen bringt Bukkit durcheinander. */
    private void later(Player p, Runnable r) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                r.run();
            }
        });
    }

    private static void build(Player p, String family, int variant) {
        p.closeInventory();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        String order = family + " Variante " + variant;
        LOG.info(() -> "katalog | bauen | spieler=" + p.getName() + " auftrag=" + Texts.clean(order, 60));
        p.performCommand("bm bau " + order);
    }
}
