package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;

/**
 * Kein Gegenstand fällt im Baubereich, solange gebaut wird (Plan B3: „Was beim Nachbar-Update abfällt, lässt keine
 * Gegenstände im Baubereich fallen“). Eine Fackel, die beim Setzen des Nachbarn kurz ohne Halt ist, ein halbes Bett,
 * ein Kaktus neben dem Glas – Minecraft lässt sie als Gegenstand fallen, und der Bau verstreut sie über die Baustelle.
 *
 * Unterdrückt wird nur, was höchstens einen Block neben einer Zelle fällt, die der Bau in den letzten
 * {@link #WINDOW_MS} ms gesetzt hat ({@link #touched}), und nur innerhalb des Kastens der Baustelle. Vorher galt der
 * ganze Kasten (Hülle aller Zellen plus 1) für die ganze Bauzeit (Sicherheitsprüfung 2026-11-08, Befund 8): Bei weit
 * auseinanderliegenden Bauteilen deckte er fremde Farmen ab, und Abbau-Drops anderer Spieler verschwanden minutenlang.
 * Wer wieder den ganzen Kasten nimmt, lässt diese Gegenstände wieder verschwinden.
 *
 * Threads: nur Hauptthread (Ereignisse und BuildJob). Fehler: keine.
 */
final class BuildDropGuard implements Listener {

    /** So lange nach dem Setzen gilt eine Zelle als „gerade gebaut“ – Nachbar-Updates und der nächste Takt. */
    static final long WINDOW_MS = 2_000;

    /** Baubereich: Welt, Ecken (einschließlich). */
    record Box(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        boolean contains(UUID w, int x, int y, int z) {
            return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    private final List<Box> active = new ArrayList<>();
    /** Welt → gepackte Zelle → bis wann (ms) Abfallendes daneben unterdrückt wird. */
    private final Map<UUID, Map<Long, Long>> recent = new HashMap<>();
    private final LongSupplier clock;
    private long suppressed;
    private long writes;

    BuildDropGuard() {
        this(System::currentTimeMillis);
    }

    /** Für Tests: eigene Uhr in ms. */
    BuildDropGuard(LongSupplier clock) {
        this.clock = clock;
    }

    void open(Box box) {
        active.add(box);
    }

    void close(Box box) {
        active.remove(box);
        if (active.stream().noneMatch(b -> b.world().equals(box.world()))) {
            recent.remove(box.world());
        }
    }

    /** Der Bau hat diese Zelle eben gesetzt (oder geräumt). */
    void touched(UUID world, int x, int y, int z) {
        recent.computeIfAbsent(world, w -> new HashMap<>()).put(pack(x, y, z), clock.getAsLong() + WINDOW_MS);
        if (++writes % 4096 == 0) {
            prune();
        }
    }

    /** Wie viele Gegenstände seit dem Start nicht gefallen sind (für das Ende-Log des Baus). */
    long suppressed() {
        return suppressed;
    }

    boolean covers(Location l) {
        return l.getWorld() != null && covers(l.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** Im Kasten einer laufenden Baustelle UND höchstens einen Block neben einer gerade gesetzten Zelle. */
    boolean covers(UUID w, int x, int y, int z) {
        boolean inBox = false;
        for (Box b : active) {
            if (b.contains(w, x, y, z)) {
                inBox = true;
                break;
            }
        }
        Map<Long, Long> cells = inBox ? recent.get(w) : null;
        if (cells == null) {
            return false;
        }
        long now = clock.getAsLong();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Long until = cells.get(pack(x + dx, y + dy, z + dz));
                    if (until != null && until >= now) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!active.isEmpty() && covers(event.getLocation())) {
            event.setCancelled(true);
            suppressed++;
        }
    }

    private void prune() {
        long now = clock.getAsLong();
        for (Map<Long, Long> cells : recent.values()) {
            for (Iterator<Long> it = cells.values().iterator(); it.hasNext();) {
                if (it.next() < now) {
                    it.remove();
                }
            }
        }
    }

    /** Wie BlockPos.asLong: x und z je 26 Bit, y 12 Bit. */
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
