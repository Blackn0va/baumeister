package com.blackn0va.baumeister.earth;

import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Neumalen geschätzter Erdwelt-Chunks (Gedächtnis „Neumalen fehlt“): Fehlt beim Erzeugen eine Höhenkachel (Netz weg,
 * Kachel 30 s gesperrt), malt EarthTerrain den Chunk mit grober Höhe (Zoom 10) oder 0 m – ein Chunk entsteht nur
 * einmal, und vorher blieb er so für immer. Jetzt merkt der Populator solche Chunks (EstimatedChunks, auf der Platte),
 * und dieser Dienst malt sie neu, sobald die Daten ladbar sind.
 *
 * Ablauf (alle CHECK_TICKS): geladene Chunks aus der Liste der Reihe nach; auf dem Hauptthread nur einen ChunkSnapshot
 * nehmen; im Erdwelt-Thread die Säulen neu rechnen (fehlen die Daten noch: später wieder, RETRY_MILLIS) und gegen den
 * Schnappschuss planen (RepaintPlan: nur Säulen, deren Oberfläche noch unverändert ist); auf dem Hauptthread setzen
 * (RepaintJob, prüft jede Säule vorher noch einmal) – höchstens MAX_BLOCKS_PER_TICK Blöcke je Tick, ohne Physik,
 * ein Chunk zur Zeit. Steht ein Spieler im Chunk, wird gewartet (sonst stünde er im neuen Berg).
 * Gemessen 2026-10-29 (Testserver, Netz per totem Proxy gekappt, dann wieder da): 81 Chunks geschätzt (y 0, Sand),
 * nach dem Neustart neu gemalt, Oberfläche y 1 349 = Rechnung ohne Server; je Chunk 33 280 Blöcke. Mit 4 000 Blöcken
 * alle 40 Ticks dauerte das 18 s je Chunk – jetzt 500 je Tick (~3 s je Chunk, Stadtbau auf dev01: 500 je Tick).
 * Nicht geladene Chunks werden nicht geladen: Sie kommen dran, sobald jemand hinkommt.
 *
 * Speichern: alle SAVE_MILLIS im Pool, wenn sich etwas geändert hat. Vorher nur bei stop(), beim Entladen mitten im
 * Malen und nach fertigem Neumalen – gerade bei einem Netzausfall entstehen viele geschätzte Chunks, und ein Absturz
 * danach hätte die Liste gekostet (die Chunks blieben für immer grob).
 *
 * Threads: Timer, Schnappschuss und Setzen auf dem Hauptthread, Rechnen, Planen und Speichern im Pool der Erdwelt; die
 * Liste ist synchronisiert. Fehler: Speichern der Liste scheitert → Warnung, die Liste im Speicher bleibt.
 */
final class EarthRepaint {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final long CHECK_TICKS = 40;
    static final long RETRY_MILLIS = 60_000;
    /** Setzen ohne Physik ~40–50 k Blöcke/s auf dem Laptop (Stadtbau, gemessen) → ~10 ms je Tick, auf dev01 mehr. */
    static final int MAX_BLOCKS_PER_TICK = 500;
    /** Merkliste spätestens so oft auf die Platte (nur wenn sich etwas geändert hat). */
    static final long SAVE_MILLIS = 120_000;

    private final JavaPlugin plugin;
    private final EstimatedChunks store;
    private final ExecutorService pool;
    private final Supplier<World> world;
    private final Supplier<EarthTerrain> terrain;
    private final Map<Long, Long> retryAt = new HashMap<>();
    private final Map<Material, BlockData> data = new EnumMap<>(Material.class);
    /** Laufender Chunk (nur Hauptthread). */
    private RepaintJob pending;
    private boolean computing;
    private long ticks;
    private BukkitTask timer;

    EarthRepaint(JavaPlugin plugin, EstimatedChunks store, ExecutorService pool, Supplier<World> world,
            Supplier<EarthTerrain> terrain) {
        this.plugin = plugin;
        this.store = store;
        this.pool = pool;
        this.world = world;
        this.terrain = terrain;
    }

    void start() {
        timer = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGuarded, CHECK_TICKS, 1L);
        LOG.info(() -> "erde | neumalen | bereit | geschätzt=" + store.size());
    }

    void stop() {
        if (timer != null) {
            timer.cancel();
        }
        save();
    }

    /** Vom Populator (Generator-Thread): Chunk mit Fingerabdruck merken. */
    void remember(int cx, int cz, int[] minY, int[] maxY, Material[] top) {
        for (Material m : top) {
            if (m == null) {
                return;  // Säule ohne Block – kann der Populator nicht hinterlassen; nichts Falsches merken
            }
        }
        store.add(cx, cz, new EstimatedChunks.Print(minY.clone(), maxY.clone(), top.clone()));
        LOG.info(() -> "erde | chunk_geschätzt | cx=" + cx + " cz=" + cz + " gemerkt=" + store.size());
    }

    private void tickGuarded() {
        try {
            tick();
        } catch (RuntimeException e) {
            // Breiter catch an der Taktgrenze: Ein Fehler beim Neumalen darf den Timer nicht beenden.
            LOG.log(Level.WARNING, "erde | neumalen_fehler", e);
            pending = null;
            computing = false;
        }
    }

    private void tick() {
        World w = world.get();
        if (w == null) {
            return;
        }
        if (pending != null) {
            apply(w);
            return;
        }
        // Setzen jeden Tick, Suchen nach dem nächsten Chunk nur alle CHECK_TICKS.
        if (computing || ++ticks % CHECK_TICKS != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - store.lastSaveMillis() >= SAVE_MILLIS) {
            pool.execute(() -> store.saveIfDue(System.currentTimeMillis(), SAVE_MILLIS, EarthRepaint::reportSave));
        }
        for (long k : store.keys()) {
            Long retry = retryAt.get(k);
            if (retry != null && retry > now) {
                continue;
            }
            int cx = EstimatedChunks.cx(k);
            int cz = EstimatedChunks.cz(k);
            if (!w.isChunkLoaded(cx, cz) || playerInside(w, cx, cz)) {
                continue;
            }
            compute(w, k);
            return;
        }
    }

    private static void reportSave(IOException e) {
        LOG.log(Level.WARNING, "erde | geschätzt_speichern_fehlgeschlagen", e);
    }

    private static boolean playerInside(World w, int cx, int cz) {
        for (Player p : w.getPlayers()) {
            int px = p.getLocation().getBlockX() >> 4;
            int pz = p.getLocation().getBlockZ() >> 4;
            if (Math.abs(px - cx) <= 1 && Math.abs(pz - cz) <= 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Auf dem Hauptthread nur den Schnappschuss nehmen; Rechnen und Planen im Pool. Fehlen die Daten noch, später
     * wieder.
     */
    private void compute(World w, long k) {
        EarthTerrain t = terrain.get();
        EstimatedChunks.Print stored = store.get(k);
        if (t == null || stored == null) {
            return;
        }
        // Kopie: Die Liste wird beim Setzen säulenweise nachgeführt, geplant und geprüft wird gegen diesen Stand.
        EstimatedChunks.Print old = new EstimatedChunks.Print(stored.minY().clone(), stored.maxY().clone(),
                stored.top().clone());
        computing = true;
        int cx = EstimatedChunks.cx(k);
        int cz = EstimatedChunks.cz(k);
        ChunkSnapshot snap = w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
        int maxY = w.getMaxHeight();
        CompletableFuture.supplyAsync(() -> {
            RepaintPlan.Recording rec = new RepaintPlan.Recording();
            if (t.paintChecked(cx, cz, rec)) {
                return null;
            }
            return RepaintPlan.plan(old, rec, snap::getBlockType, maxY);
        }, pool).whenComplete((r, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            computing = false;
            if (err != null) {
                LOG.log(Level.WARNING, "erde | neumalen_rechnen_fehlgeschlagen | cx=" + cx + " cz=" + cz, err);
                retryAt.put(k, System.currentTimeMillis() + RETRY_MILLIS);
                return;
            }
            if (r == null) {
                retryAt.put(k, System.currentTimeMillis() + RETRY_MILLIS);  // Daten fehlen weiter
                return;
            }
            World now = world.get();
            if (now == null || !now.isChunkLoaded(cx, cz) || playerInside(now, cx, cz)) {
                return;  // nächster Durchgang
            }
            LOG.info(() -> "erde | neumalen | cx=" + cx + " cz=" + cz + " säulen_neu=" + r.repainted()
                    + " säulen_behalten=" + r.kept() + " blöcke=" + r.blocks());
            pending = new RepaintJob(k, old, r.columns(), maxY);
            apply(now);
        }));
    }

    /** Setzt die nächsten Säulen (RepaintJob); fertig → Chunk aus der Liste. */
    private void apply(World w) {
        long k = pending.key();
        int cx = EstimatedChunks.cx(k);
        int cz = EstimatedChunks.cz(k);
        if (!w.isChunkLoaded(cx, cz)) {
            pending = null;  // entladen: später neu planen
            save();
            return;
        }
        Chunk chunk = w.getChunkAt(cx, cz);
        pending.step(new RepaintJob.Live() {
            @Override
            public Material at(int x, int y, int z) {
                return chunk.getBlock(x, y, z).getType();
            }

            @Override
            public void set(int x, int y, int z, Material m) {
                chunk.getBlock(x, y, z).setBlockData(data(m), false);
            }
        }, store, MAX_BLOCKS_PER_TICK);
        if (pending.done()) {
            int skipped = pending.skipped();
            if (skipped > 0) {
                LOG.info(() -> "erde | neumalen | cx=" + cx + " cz=" + cz + " säulen_seit_plan_verändert=" + skipped);
            }
            pending = null;
            store.remove(k);
            retryAt.remove(k);
            save();
        }
    }

    private BlockData data(Material m) {
        return data.computeIfAbsent(m, Material::createBlockData);
    }

    private void save() {
        try {
            store.save();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "erde | geschätzt_speichern_fehlgeschlagen", e);
        }
    }

    /** Für Tests und /bm wo: so viele Chunks warten aufs Neumalen. */
    int waiting() {
        return store.size();
    }

    List<Long> keys() {
        return store.keys();
    }
}
