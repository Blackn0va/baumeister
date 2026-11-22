package com.blackn0va.baumeister;

import com.blackn0va.baumeister.town.work.Field;
import com.blackn0va.baumeister.town.work.FieldJob;
import com.blackn0va.baumeister.town.work.FieldWork;
import com.blackn0va.baumeister.town.work.Plot;
import com.blackn0va.baumeister.town.work.Task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Brücke der Feldarbeit (town.work) zu Bukkit (Vertrag v013, AK-43): Blöcke lesen, Blöcke ändern – jede Änderung im
 * Namen eines Spielers über die Ereignisse, die auch ein Spieler auslöst, damit WorldGuard, PlotSquared & Co. sie
 * verbieten können und CoreProtect sie protokolliert.
 *
 * Reihenfolge wie Vanilla (nicht wie RegionCheck.allowed beim Bau, das nur vorab fragt):
 * - Abbau: BlockBreakEvent VOR dem Abbau; abgesagt → nichts geändert.
 * - Setzen: Block setzen, DANN BlockPlaceEvent mit dem alten Zustand; abgesagt oder canBuild=false → alter Zustand
 *   zurück (ohne Physik), nichts bleibt. So liest CoreProtect auf MONITOR den gesetzten Block und nicht die Luft
 *   davor. Wer das vertauscht, protokolliert „Luft gesetzt“ – und ein Schutz-Plugin, das den Block prüft, sähe Luft.
 * CoreProtect braucht nichts weiter: Es hört auf genau diese Ereignisse (ignoreCancelled), Verursacher ist der Spieler,
 * für den der Bewohner arbeitet (TownLife.actor).
 *
 * Ernte: Drops wie Vanilla (Block.getDrops mit dem Werkzeug des Berufs), eine Saat geht ins Nachpflanzen. Liegt eine
 * Kiste höchstens CHEST_REACH Blöcke von der Feldmitte (dort stand der Admin beim Setzen), kommt die Ernte hinein;
 * passt sie nicht oder gibt es keine, fällt sie an der Stelle zu Boden. Andere Behälter fasst das nie an.
 *
 * Threads: Hauptthread. Fehler: keine eigenen; Bukkit-Ausnahmen gehen an TownLife (Takt-Grenze).
 */
final class TownWork {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** So weit (je Achse) von der Feldmitte sucht die Ernte ihre Kiste. */
    static final int CHEST_REACH = 3;

    private TownWork() {
    }

    /** Blöcke lesen, nur in geladenen Chunks (sonst „unloaded“ – nie ein Ziel, lädt nichts nach). */
    static Plot plot(World w) {
        return new Plot() {
            private Block at(int x, int y, int z) {
                if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) {
                    return null;
                }
                return w.getBlockAt(x, y, z);
            }

            @Override
            public String type(int x, int y, int z) {
                Block b = at(x, y, z);
                return b == null ? "unloaded" : b.getType().getKey().getKey();
            }

            @Override
            public int age(int x, int y, int z) {
                Block b = at(x, y, z);
                return b != null && b.getBlockData() instanceof Ageable a ? a.getAge() : -1;
            }

            @Override
            public int maxAge(int x, int y, int z) {
                Block b = at(x, y, z);
                return b != null && b.getBlockData() instanceof Ageable a ? a.getMaximumAge() : -1;
            }

            @Override
            public boolean persistent(int x, int y, int z) {
                Block b = at(x, y, z);
                return b != null && b.getBlockData() instanceof Leaves l && l.isPersistent();
            }
        };
    }

    /** Saat zum Nachpflanzen: Block → Gegenstand (den die Ernte abgibt und den ein Spieler in der Hand hielte). */
    static final Map<String, String> SEED = Map.of("wheat", "wheat_seeds", "carrots", "carrot", "potatoes", "potato",
            "beetroots", "beetroot_seeds");

    /**
     * Hände für einen Bewohner: ändern im Namen von actor, Ereignisse über call (im Betrieb PluginManager.callEvent).
     * log bekommt je Änderung eine Zeile (Bewohner, Feld, Art, Block, Ort) – das Protokoll ohne CoreProtect.
     */
    static FieldWork.Hands hands(World w, Player actor, Field f, FieldJob job, Consumer<Event> call,
            Consumer<String> log) {
        Material toolType = Material.matchMaterial(job.tool());
        ItemStack tool = toolType == null ? null : new ItemStack(toolType);
        return new FieldWork.Hands() {
            @Override
            public boolean breakBlock(Task.Change c) {
                Block b = w.getBlockAt(c.x(), c.y(), c.z());
                BlockBreakEvent e = new BlockBreakEvent(b, actor);
                if (!asked(call, e) || e.isCancelled()) {
                    return false;
                }
                List<ItemStack> drops = new ArrayList<>();
                if (e.isDropItems()) {
                    drops.addAll(tool == null ? b.getDrops() : b.getDrops(tool));
                }
                // Mit Physik: Ohne Nachbar-Update merkt das Laub nicht, dass der Stamm fehlt, und zerfiele nie.
                b.setType(Material.AIR, true);
                String seed = SEED.get(c.expect());
                if (seed != null) {
                    takeOne(drops, Material.matchMaterial(seed));
                }
                store(w, f, drops, b.getLocation().add(0.5, 0.5, 0.5));
                log.accept("art=abbau block=" + c.expect());
                return true;
            }

            @Override
            public boolean place(Task.Change c) {
                // Ohne isBlock/isItem: Beide fragen in 1.21 die Server-Registry (ohne Server nicht prüfbar, TownWorkTest).
                // Nötig sind sie nicht – Safety.allowed lässt nur Saat, Setzlinge, Blumen und Trampelpfad zu, alles
                // Blöcke mit gleichnamigem Gegenstand oder Saat in SEED.
                Material m = Material.matchMaterial(c.type());
                if (m == null) {
                    return false;
                }
                Block b = w.getBlockAt(c.x(), c.y(), c.z());
                BlockState before = b.getState();
                b.setType(m, true);
                String itemName = SEED.getOrDefault(c.type(), c.type());
                Material item = Material.matchMaterial(itemName);
                BlockPlaceEvent e = new BlockPlaceEvent(b, before, b.getRelative(BlockFace.DOWN),
                        new ItemStack(item == null ? Material.STONE : item), actor, true,
                        EquipmentSlot.HAND);
                if (!asked(call, e) || e.isCancelled() || !e.canBuild()) {
                    // Zurück ohne Physik: Der Block stand nur innerhalb dieses Takts, kein Nachbar soll reagieren.
                    before.update(true, false);
                    return false;
                }
                log.accept("art=setzen block=" + c.type());
                return true;
            }
        };
    }

    /**
     * Ereignis stellen; false = beim Fragen geworfen. Gilt als abgelehnt (fail-closed, ASVS V4.1.5): Ein Schutz-Plugin,
     * das beim Prüfen abstürzt, hat nicht erlaubt. Vorher lief die Ausnahme bis zur Takt-Grenze – beim Setzen blieb
     * der schon gesetzte Block dann stehen, ohne dass jemand ihn erlaubt hatte (Gegenprobe des Dirigenten T-035).
     * Bukkits PluginManager fängt Ausnahmen einzelner Listener selbst; das hier greift für alles, was trotzdem
     * durchkommt (fremder callEvent-Ersatz, Ausnahme vor den Listenern).
     */
    static boolean asked(Consumer<Event> call, Event e) {
        try {
            call.accept(e);
            return true;
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "stadtleben | feld_prüfung_fehlgeschlagen | ereignis=" + e.getEventName()
                    + " – gilt als verboten", ex);
            return false;
        }
    }

    private static void takeOne(List<ItemStack> drops, Material m) {
        if (m == null) {
            return;
        }
        for (ItemStack s : drops) {
            if (s.getType() == m && s.getAmount() > 0) {
                s.setAmount(s.getAmount() - 1);
                return;
            }
        }
    }

    /** Ernte in die Kiste am Feld, sonst an der Stelle fallen lassen. */
    static void store(World w, Field f, Collection<ItemStack> items, Location at) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack s : items) {
            if (s != null && s.getAmount() > 0) {
                left.add(s);
            }
        }
        if (left.isEmpty()) {
            return;
        }
        Block chest = chest(w, f);
        if (chest != null && chest.getState() instanceof Chest c) {
            Map<Integer, ItemStack> rest = c.getInventory().addItem(left.toArray(new ItemStack[0]));
            left = new ArrayList<>(rest.values());
        }
        for (ItemStack s : left) {
            w.dropItemNaturally(at, s);
        }
    }

    /**
     * Die eine Ablage: eine gewöhnliche Kiste (nicht Fass, nicht Falle) nahe der Feldmitte, die nächste zuerst.
     * Nur geladene Chunks; null = keine.
     */
    static Block chest(World w, Field f) {
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -CHEST_REACH; dx <= CHEST_REACH; dx++) {
            for (int dz = -CHEST_REACH; dz <= CHEST_REACH; dz++) {
                int x = f.x() + dx;
                int z = f.z() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -CHEST_REACH; dy <= CHEST_REACH; dy++) {
                    Block b = w.getBlockAt(x, f.y() + dy, z);
                    double d = dx * dx + dy * dy + dz * dz;
                    if (b.getType() == Material.CHEST && d < bestD) {
                        best = b;
                        bestD = d;
                    }
                }
            }
        }
        return best;
    }

    /** Für Status und Liste: Hat das Feld eine Ablage? null = Mitte nicht geladen. */
    static Boolean hasChest(World w, Field f) {
        if (w == null || !w.isChunkLoaded(f.x() >> 4, f.z() >> 4)) {
            return null;
        }
        return chest(w, f) != null;
    }
}
