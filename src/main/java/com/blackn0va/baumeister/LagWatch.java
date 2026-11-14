package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

/**
 * Lag-Wächter (Wunsch 2026-11-14: „es sollen keine Lag-Maschinen verwendet werden“): zählt in einem Bereich, was dort
 * OHNE Eingabe von selbst passiert – Redstone-Schaltvorgänge, Kolbenbewegungen, Werfer/Spender, Trichter-Transfers und
 * neue Objekte (Gegenstände, Mobs). Eine Lag-Maschine ist eine, die das im Leerlauf in hohem Takt tut (Dauertakt,
 * Beobachter-Uhr, Kolbenschleife, Objekt-Spam). Ein Spieler, der im Bereich einen Hebel umlegt, ist Eingabe: Dann gilt
 * die Messung nicht.
 *
 * Gemessen statt geraten: Die Grenze (LIMIT_PER_SECOND) kommt aus der Leerlauf-Messung aller eingebauten Maschinen
 * und Schematics (/bm lagprobe, Messprobe LagProbe) – siehe Kommentar dort.
 *
 * Threads: nur Server-Thread (Ereignisse und Scheduler). Fehler: keine; ein Bereich ohne Welt misst nichts.
 */
final class LagWatch implements Listener {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /**
     * Grenze Schaltvorgänge je Sekunde im Leerlauf (Vorgabe von bau.lag-grenze-je-sekunde). Gemessen 2026-11-14
     * (LagProbe, Spigot 1.21.11 und Paper 26.2, je 6–8 s ohne Eingabe): eingebaute Maschinen – takt 18,3, nachtlicht
     * 0,3, die übrigen 45 bei 0. 804 Technik-Wiki-Bauten – Taktgeber, Zufalls-/Impulsgeber, TNT-Kanonen und
     * Flugmaschinen 0 (laufen erst auf Knopfdruck); Farmen mit Wachstums-Beobachter 22–36 (Obsidian, Basalt,
     * Bruchstein, Bambus, Spieler-Drop); darüber eine abgesetzte Gruppe von Dauerläufern: Moosfarm 2 53,3,
     * Stapeltrenner 3 58,7, Kombinationsschloss 1 74,0, Objektzähler 2 118,3. 50 trennt diese Gruppe von allem, was
     * auf Wachstum oder Eingabe reagiert. Keiner der 804 erreicht die Tausende je Sekunde einer echten Lag-Maschine –
     * die Grenze hält Dauerläufer fern, bevor daraus eine wird. Wer sie hebt, lässt Dauerläufer wieder zu; wer sie
     * unter ~40 senkt, baut Farmen wieder ab.
     */
    static final double LIMIT_PER_SECOND = 50;

    /** Bereich: Welt und Ecken (einschließlich). */
    record Box(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        static Box of(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Box(w.getUID(), Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2),
                    Math.max(y1, y2), Math.max(z1, z2));
        }

        boolean contains(UUID w, int x, int y, int z) {
            return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /** Zählerstand einer Messung; perSecond = alles außer Eingriffen, je Sekunde. */
    record Result(Box box, double seconds, long redstone, long pistons, long dispensed, long hopper, long spawned,
            long interactions) {

        long activity() {
            return redstone + pistons + dispensed + hopper + spawned;
        }

        double perSecond() {
            return seconds <= 0 ? 0 : activity() / seconds;
        }

        /** Ohne Eingabe und über der Grenze: eine Lag-Maschine. */
        boolean lagging(double limit) {
            return interactions == 0 && perSecond() > limit;
        }

        String line() {
            return String.format(Locale.ROOT, "redstone=%d kolben=%d werfer=%d trichter=%d objekte=%d eingriffe=%d "
                    + "sekunden=%.1f je_s=%.1f", redstone, pistons, dispensed, hopper, spawned, interactions, seconds,
                    perSecond());
        }
    }

    /** Eine laufende Messung (Zähler, paketweit für den Test). */
    static final class Counter {
        final Box box;
        long redstone;
        long pistons;
        long dispensed;
        long hopper;
        long spawned;
        long interactions;

        Counter(Box box) {
            this.box = box;
        }

        Result result(double seconds) {
            return new Result(box, seconds, redstone, pistons, dispensed, hopper, spawned, interactions);
        }
    }

    private final Plugin plugin;
    private final List<Counter> running = new ArrayList<>();

    LagWatch(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Messung starten; done bekommt nach ticks Ticks das Ergebnis (Server-Thread). */
    void watch(Box box, int ticks, Consumer<Result> done) {
        Counter c = new Counter(box);
        running.add(c);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            running.remove(c);
            Result r = c.result(ticks / 20.0);
            LOG.info(() -> "lag | gemessen | " + r.line());
            done.accept(r);
        }, Math.max(1, ticks));
    }

    int active() {
        return running.size();
    }

    private void count(Location at, java.util.function.Consumer<Counter> add) {
        if (running.isEmpty() || at == null || at.getWorld() == null) {
            return;
        }
        UUID w = at.getWorld().getUID();
        for (Counter c : running) {
            if (c.box.contains(w, at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
                add.accept(c);
            }
        }
    }

    private void count(Block b, java.util.function.Consumer<Counter> add) {
        if (running.isEmpty()) {
            return;
        }
        UUID w = b.getWorld().getUID();
        for (Counter c : running) {
            if (c.box.contains(w, b.getX(), b.getY(), b.getZ())) {
                add.accept(c);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        count(e.getBlock(), c -> c.redstone++);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        count(e.getBlock(), c -> c.pistons++);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        count(e.getBlock(), c -> c.pistons++);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        count(e.getBlock(), c -> c.dispensed++);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (running.isEmpty()) {
            return;
        }
        count(e.getSource().getLocation(), c -> c.hopper++);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent e) {
        count(e.getLocation(), c -> c.spawned++);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() != null) {
            count(e.getClickedBlock(), c -> c.interactions++);
        }
    }
}
