package com.blackn0va.baumeister;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Die Materialkiste beim Graben und Brückenbau (Wunsch 2026-11-16: „wenn ich Admin bin, baut er einfach, ansonsten
 * braucht er eine Kiste mit Materialien“; Abgebautes „alles in die Kiste“). Eine Kiste, Falle-Kiste oder ein Fass
 * neben dem Spieler – zuerst die, auf die er schaut, sonst die nächste in {@link #RADIUS} Blöcken. Doppelkisten zählen
 * als eine (Bukkit liefert das gemeinsame Inventar).
 *
 * Nur schlichte Gegenstände: Benanntes oder Verzaubertes nimmt der Baumeister nicht (wie Vera beim Craften,
 * CompanionCraft) – sonst verbaute er das Lieblingsschwert-Material oder einen umbenannten Stein. Zugriff nur, wo der
 * Spieler die Kiste auch abbauen dürfte (RegionCheck.mayBreakQuietly, still für CoreProtect): Eine Kiste im fremden
 * Grundstück neben dem Tunneleingang bleibt zu (ASVS V4.1.3).
 *
 * Inhalt wird jedes Mal frisch gelesen – der Spieler legt nach, während gebaut wird. Was nicht mehr hineinpasst, fällt
 * neben der Kiste heraus.
 *
 * Threads: Hauptthread. Fehler: keine (fehlende Kiste = present() false).
 */
final class MaterialChest {

    /** So weit um die Füße sucht er eine Kiste. */
    static final int RADIUS = 5;
    private static final Set<Material> KINDS = Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL);

    private final Block block;

    private MaterialChest(Block block) {
        this.block = block;
    }

    /** Die Kiste, auf die p schaut, sonst die nächste um feet; null, wenn keine da ist oder keine erlaubt. */
    static MaterialChest find(Location feet, Player p) {
        if (p != null) {
            Block looked = p.getTargetBlockExact(6);
            if (looked != null && usable(looked, p)) {
                return new MaterialChest(looked);
            }
        }
        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    Block b = feet.getBlock().getRelative(dx, dy, dz);
                    if (!KINDS.contains(b.getType())) {
                        continue;
                    }
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bestDist && usable(b, p)) {
                        best = b;
                        bestDist = d;
                    }
                }
            }
        }
        return best == null ? null : new MaterialChest(best);
    }

    private static boolean usable(Block b, Player p) {
        return KINDS.contains(b.getType()) && b.getState() instanceof Container
                && (p == null || RegionCheck.mayBreakQuietly(p, b));
    }

    Location location() {
        return block.getLocation();
    }

    Block block() {
        return block;
    }

    /** Steht die Kiste noch? (abgebaut, ersetzt, Chunk entladen → false; dann wartet der Bau) */
    boolean present() {
        return KINDS.contains(block.getType()) && block.getState() instanceof Container;
    }

    private Inventory inventory() {
        BlockState s = block.getState();
        return s instanceof Container c ? c.getInventory() : null;
    }

    /** Schlichte Gegenstände je ID. */
    Map<String, Integer> counts() {
        Map<String, Integer> out = new HashMap<>();
        Inventory inv = inventory();
        if (inv == null) {
            return out;
        }
        for (ItemStack s : inv.getContents()) {
            if (plain(s)) {
                out.merge(s.getType().getKey().getKey(), s.getAmount(), Integer::sum);
            }
        }
        return out;
    }

    boolean has(Material m) {
        Inventory inv = inventory();
        if (inv == null) {
            return false;
        }
        for (ItemStack s : inv.getContents()) {
            if (plain(s) && s.getType() == m) {
                return true;
            }
        }
        return false;
    }

    /** Einen schlichten Gegenstand m nehmen; false, wenn keiner da ist. */
    boolean take(Material m) {
        Inventory inv = inventory();
        if (inv == null) {
            return false;
        }
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (plain(s) && s.getType() == m) {
                if (s.getAmount() > 1) {
                    s.setAmount(s.getAmount() - 1);
                    inv.setItem(i, s);
                } else {
                    inv.setItem(i, null);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Abgebautes hinein; was nicht passt (oder ohne Kiste), fällt neben ihr heraus. true = etwas lag daneben.
     */
    boolean deposit(Collection<ItemStack> items) {
        if (items.isEmpty()) {
            return false;
        }
        Inventory inv = inventory();
        boolean spilled = false;
        Location out = block.getLocation().add(0.5, 1.1, 0.5);
        for (ItemStack s : items) {
            Map<Integer, ItemStack> left = inv == null ? Map.of(0, s) : inv.addItem(s);
            for (ItemStack rest : left.values()) {
                block.getWorld().dropItem(out, rest);
                spilled = true;
            }
        }
        return spilled;
    }

    /** Ohne Namen, Verzauberung, Beschreibung – wie frisch abgebaut oder aus dem Kreativinventar. */
    private static boolean plain(ItemStack s) {
        return s != null && !s.getType().isAir() && s.isSimilar(new ItemStack(s.getType()));
    }
}
