package com.blackn0va.baumeister;

import com.blackn0va.baumeister.osm.CityPlanner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Schreibt die Texte der Straßenschilder eines Stadt- bzw. Weltbaus, sobald CityJob den Schild-Block gesetzt hat
 * (siehe {@link SignQueue} zum Warum). Je Lauf (alle 5 Ticks) höchstens 16 Versuche: ein isChunkLoaded, ein
 * getBlockAt und für ein gefundenes Schild ein getState – gegen die Tausenden Blöcke je Tick von CityJob nichts. Der
 * Takt läuft nur, solange Schilder warten.
 *
 * Gefunden wird das Schild an seiner geplanten Stelle; passt der Block dort nicht (Höhe aus einem Höhenfeld, das erst
 * CityJob anlegt), sucht es die Säule ab der obersten Oberfläche 12 Blöcke abwärts. Erkannt wird es am BlockData (Art
 * und Drehung) – ein fremdes Schild anderer Drehung bleibt unberührt.
 *
 * Gewachst: Spieler können den Text nicht per Rechtsklick ändern (ein Stadtbau ist Kulisse, kein Schwarzes Brett).
 *
 * Bauende (close): Ein Durchgang über alle wartenden Schilder sofort, noch bevor CityJob.finish() die Chunk-Tickets
 * freigibt. Vorher (bis zur Nachbesserung 2026-10-29) wartete close() nur auf den nächsten Takt – die Tickets gingen
 * aber im selben Aufruf frei, der Server entlud die Chunks der letzten Abschnitte, und deren Schilder blieben ohne Text
 * (nur der Zähler verworfen= verriet es). Wer den Durchgang entfernt, verliert genau die Schilder, die zuletzt gebaut
 * wurden. Was danach noch wartet (Chunk nicht geladen, Block nie gebaut), bekommt die Gnadenfrist im Takt.
 *
 * Threads: nur Hauptthread. Fehler: ein unbekannter Schild-Block wird verworfen und geloggt; Ausnahmen beim Schreiben
 * an der Taktgrenze geloggt, der Takt läuft weiter.
 */
final class SignWriter {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final int PER_RUN = 16;
    private static final long PERIOD_TICKS = 5;
    /** Nach dem Bauende: so lange warten Schilder noch auf ihren Block (die letzten Zellen sind gerade gesetzt). */
    private static final long GRACE_NANOS = 30_000_000_000L;
    private static final int SCAN_DEPTH = 12;

    private final Plugin plugin;
    private final World world;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final boolean absolute;
    private final SignQueue<CityPlanner.Sign> queue = new SignQueue<>(PER_RUN, GRACE_NANOS);
    private final Map<String, BlockData> expected = new HashMap<>();
    private final Function<String, BlockData> blockData;
    private BukkitTask task;

    /** absolute: Schilder tragen schon die Welthöhe (Weltbau, TerrainFit.fitSigns), sonst relativ zu baseY. */
    SignWriter(Plugin plugin, World world, int baseX, int baseY, int baseZ, boolean absolute) {
        this(plugin, world, baseX, baseY, baseZ, absolute, Bukkit::createBlockData);
    }

    /** blockData: „minecraft:…“ → BlockData; im Test ohne Server ersetzbar (Bukkit.createBlockData braucht einen). */
    SignWriter(Plugin plugin, World world, int baseX, int baseY, int baseZ, boolean absolute,
            Function<String, BlockData> blockData) {
        this.blockData = blockData;
        this.plugin = plugin;
        this.world = world;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.absolute = absolute;
    }

    void add(List<CityPlanner.Sign> signs) {
        if (signs.isEmpty()) {
            return;
        }
        queue.add(signs);
        ensureTask();
    }

    /** Bau zu Ende (fertig oder gestoppt): restliche Schilder bekommen noch die Gnadenfrist. */
    void close() {
        queue.close(System.nanoTime());
        try {
            queue.drain(this::write, System.nanoTime());
        } catch (RuntimeException e) {
            // Grenze zu CityJob.finish(): Danach gibt finish() die Chunk-Tickets frei und schließt den
            // Rückgängig-Speicher ab – eine Ausnahme aus einem Schild darf das nicht überspringen. Bewusst fail-open
            // für die Schildtexte (Kulisse), nicht für den Bau.
            LOG.log(Level.WARNING, "stadt | schild_fehler", e);
        }
        if (queue.pending() > 0) {
            ensureTask();
            return;
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (queue.written() + queue.dropped() > 0) {
            LOG.info(() -> "stadt | schilder_fertig | beschriftet=" + queue.written() + " verworfen="
                    + queue.dropped());
        }
    }

    private void ensureTask() {
        // Nur bei aktivem Plugin: Beim Herunterfahren endet CityJob über finish() → close() aus onDisable heraus, und
        // ein neuer Takt würfe dort IllegalPluginAccessException – mitten in finish(), vor dem Abschluss des
        // Rückgängig-Speichers. Die restlichen Schilder bleiben dann ohne Text (der Server geht ohnehin aus).
        if (task == null && queue.pending() > 0 && plugin.isEnabled()) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::runGuarded, PERIOD_TICKS, PERIOD_TICKS);
        }
    }

    private void runGuarded() {
        try {
            queue.run(this::write, System.nanoTime());
        } catch (RuntimeException e) {
            // Taktgrenze: Ein kaputtes Schild darf den Server-Takt nicht mit Ausnahmen fluten – geloggt, weiter.
            LOG.log(Level.WARNING, "stadt | schild_fehler", e);
        }
        if (queue.pending() == 0 && task != null) {
            task.cancel();
            task = null;
            if (queue.closed()) {
                LOG.info(() -> "stadt | schilder_fertig | beschriftet=" + queue.written() + " verworfen="
                        + queue.dropped());
            }
        }
    }

    private SignQueue.Result write(CityPlanner.Sign s) {
        int x = baseX + s.x();
        int z = baseZ + s.z();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return SignQueue.Result.NOT_READY;  // nie selbst laden – das Laden steuert CityJob (Lastregelung)
        }
        BlockData want = expected.computeIfAbsent(s.block(), b -> {
            try {
                return blockData.apply("minecraft:" + b);
            } catch (IllegalArgumentException e) {
                LOG.warning("stadt | schild_block_unbekannt | block=" + b);
                return null;
            }
        });
        if (want == null) {
            return SignQueue.Result.DROP;
        }
        Block b = find(x, absolute ? s.y() : baseY + s.y(), z, want);
        if (b == null || !(b.getState() instanceof Sign sign)) {
            return SignQueue.Result.NOT_READY;
        }
        for (Side side : Side.values()) {
            SignSide face = sign.getSide(side);
            for (int i = 0; i < 4; i++) {
                face.setLine(i, i < s.lines().size() ? s.lines().get(i) : "");
            }
        }
        sign.setWaxed(true);
        sign.update(true, false);
        return SignQueue.Result.WRITTEN;
    }

    private Block find(int x, int y, int z, BlockData want) {
        if (y >= world.getMinHeight() && y < world.getMaxHeight()) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getBlockData().equals(want)) {
                return b;
            }
        }
        int top = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        for (int yy = top; yy > top - SCAN_DEPTH && yy >= world.getMinHeight(); yy--) {
            Block b = world.getBlockAt(x, yy, z);
            if (b.getBlockData().equals(want)) {
                return b;
            }
        }
        return null;
    }
}
