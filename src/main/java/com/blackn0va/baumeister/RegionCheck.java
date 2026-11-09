package com.blackn0va.baumeister;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Fragt WorldGuard, PlotSquared & Co. so, als setze oder entferne der Spieler den Block selbst. Das Ereignis wird nur
 * ausgelöst, nicht ausgeführt – der Block bleibt unverändert. ASVS V4.1.3 (geringste Rechte): Der NPC baut nie mehr,
 * als sein Auftraggeber dürfte.
 *
 * Vorher stand das nur in BuildJob. Seit 2026-11-08 (Plan B6/B8) prüfen damit auch „/bm befehlsblock“ die Stelle und
 * Veras „merk dir das Bauwerk“ den ganzen Bereich.
 *
 * Threads: nur Hauptthread (Ereignisse). Fehler: keine.
 */
final class RegionCheck {

    private RegionCheck() {
    }

    /** data = was hin soll; Luft heißt abbauen. */
    static boolean allowed(Player p, Block b, BlockData data) {
        if (data.getMaterial().isAir()) {
            return mayBreak(p, b);
        }
        Material item = data.getPlacementMaterial();
        if (item == null || item.isAir() || !item.isItem()) {
            item = Material.STONE;
        }
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN), new ItemStack(item), p,
                true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(e);
        return !e.isCancelled() && e.canBuild();
    }

    static boolean mayBreak(Player p, Block b) {
        BlockBreakEvent e = new BlockBreakEvent(b, p);
        Bukkit.getPluginManager().callEvent(e);
        return !e.isCancelled();
    }

    /**
     * Wie mayBreak, aber still für Protokoll-Plugins (Sicherheitsprüfung 2026-11-08, Befund 5): Veras „merk dir das
     * Bauwerk“ fragt bis zu 32 768 Blöcke. Jedes gewöhnliche Abbau-Ereignis landete in CoreProtect als echter Abbau des
     * Spielers – nichts wurde abgebaut, aber Protokoll und Kistenauskunft stimmten nicht mehr. Hier entscheiden
     * WorldGuard und PlotSquared wie sonst; {@link QuietProbes} merkt sich das Urteil auf HIGHEST und bricht das
     * Ereignis dann ab, sodass MONITOR-Hörer (CoreProtect, ignoreCancelled) es nie sehen. Ohne registrierten Hörer
     * (Tests) gilt das gewöhnliche Urteil.
     */
    static boolean mayBreakQuietly(Player p, Block b) {
        ProbeBreakEvent e = new ProbeBreakEvent(b, p);
        Bukkit.getPluginManager().callEvent(e);
        return e.verdict() != null ? e.verdict() : !e.isCancelled();
    }

    /** Abbau-Ereignis, das nur fragt (mayBreakQuietly). */
    static final class ProbeBreakEvent extends BlockBreakEvent {

        private Boolean verdict;

        ProbeBreakEvent(Block b, Player p) {
            super(b, p);
        }

        /** Urteil der Schutz-Plugins, gemerkt auf HIGHEST; null = kein Hörer registriert. */
        Boolean verdict() {
            return verdict;
        }
    }

    /**
     * HIGHEST: nach den Schutz-Plugins (PlotSquared früh, WorldGuard NORMAL/HIGH), vor MONITOR. Ein Schutz-Plugin, das
     * erst auf HIGHEST nach diesem Hörer entscheidet, wird nicht mehr gefragt – auf dev01 gibt es keins (gelistet
     * 2026-11-08: WorldGuard, PlotSquared, CoreProtect, EssentialsX ohne Protect).
     */
    static final class QuietProbes implements org.bukkit.event.Listener {

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
        public void onProbe(BlockBreakEvent e) {
            if (e instanceof ProbeBreakEvent probe) {
                probe.verdict = !e.isCancelled();
                e.setCancelled(true);
            }
        }
    }
}
