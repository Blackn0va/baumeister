package com.blackn0va.baumeister.earth;

import com.blackn0va.baumeister.terrain.DemGenerator;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

/**
 * Füllt die Erdwelt (Datapack-Dimension baumeister:erde, leerer Flachgenerator) Chunk für Chunk mit der echten Erde.
 *
 * Warum ein Populator statt eines Generators: wie TerrainPopulator – Spigot gibt einer Datapack-Dimension einen
 * Plugin-Generator nur über bukkit.yml mit load: STARTUP; Populatoren laufen dagegen in jeder Welt, auch ohne
 * Plugin-Generator (gemessen 2026-10-28, ChunkGenerator.applyBiomeDecoration). Anders als TerrainPopulator gibt es
 * keine Plätze: jeder Chunk ist ein Stück Erde (oder Meer in den Lücken des Netzes).
 *
 * Tempo: Ein Chunk sind ~16 700 gesetzte Blöcke (Grundgestein 64 unter der Oberfläche). Vorher ging jeder über
 * region.setType(Material) – Spigot baut dafür bei JEDEM Block ein neues BlockData (Material → Registry → Zustand).
 * Jetzt wird je Material einmal ein BlockData gebaut und wiederverwendet (gleiche Blöcke, gleicher Zustand), und jede
 * Säule wird von oben nach unten geschrieben (RegionSink). Das Rechnen der Säulen selbst kostet warm ~0,15 ms je Chunk
 * (PaintBench, gemessen 2026-10-29) – der Rest ist Spigot (Palette, Höhenkarten, POI-Prüfung je Block).
 *
 * Messung im Betrieb: alle REPORT_EVERY Chunks eine Logzeile „erde | chunks | …“ mit der mittleren Zeit je Chunk im
 * Populator (ohne Vanilla-Arbeit wie Licht und Speichern).
 *
 * Threads: populate läuft auf den Weltgenerator-Threads; fehlende Kacheln lädt EarthTerrain dort nach (blockiert den
 * Chunk, nicht den Hauptthread – /bm reise lädt deshalb vorher im Hintergrund vor).
 */
public final class EarthPopulator extends BlockPopulator {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final long REPORT_EVERY = 1024;

    /** Wer einen geschätzten Chunk gemalt hat, sagt es hier (EarthRepaint); Generator-Thread, muss threadsicher sein. */
    @FunctionalInterface
    public interface EstimatedListener {
        void estimated(int chunkX, int chunkZ, int[] minY, int[] maxY, Material[] top);
    }

    private final EarthTerrain terrain;
    private final EstimatedListener onEstimated;
    private final AtomicLong chunks = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicLong estimatedChunks = new AtomicLong();

    public EarthPopulator(EarthTerrain terrain) {
        this(terrain, null);
    }

    /** @param onEstimated null = geschätzte Chunks nicht melden */
    public EarthPopulator(EarthTerrain terrain, EstimatedListener onEstimated) {
        this.terrain = terrain;
        this.onEstimated = onEstimated;
    }

    public EarthTerrain terrain() {
        return terrain;
    }

    @Override
    public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        long t0 = System.nanoTime();
        RegionSink sink = new RegionSink(chunkX * 16, chunkZ * 16, region::setBlockData, Material::createBlockData);
        boolean estimated = terrain.paintChecked(chunkX, chunkZ, sink);
        sink.flush();
        if (estimated) {
            estimatedChunks.incrementAndGet();
            if (onEstimated != null) {
                onEstimated.estimated(chunkX, chunkZ, sink.minY, sink.maxY, sink.top);
            }
        }
        long n = chunks.incrementAndGet();
        long total = nanos.addAndGet(System.nanoTime() - t0);
        if (n % REPORT_EVERY == 0) {
            LOG.info(() -> String.format(Locale.ROOT, "erde | chunks | anzahl=%d ms_je_chunk=%.2f geschätzt=%d", n,
                    total / 1e6 / n, estimatedChunks.get()));
        }
    }

    /** Schreibziel eines Blocks (im Plugin LimitedRegion::setBlockData). */
    @FunctionalInterface
    interface BlockWriter {
        void set(int x, int y, int z, BlockData data);
    }

    /**
     * Sink in die LimitedRegion: sammelt je Säule die Schreibbefehle (spätere überschreiben frühere, wie vorher beim
     * direkten Setzen) und schreibt erst in flush() – jede Säule VON OBEN NACH UNTEN, jeden Block genau einmal, mit
     * einem BlockData je Material.
     *
     * Warum von oben: Spigot führt bei jedem gesetzten Block die Höhenkarten und die Himmelslicht-Quellen des Chunks
     * nach (ProtoChunk). Von unten gemalt war jeder Block der neue höchste und löste die volle Nachführung aus
     * (gemessen 2026-10-29, JFR auf dem Testserver: HeightMap 10 % der Generator-Zeit, createBlockData 6 %); von oben
     * ist nur der erste Block einer Säule neu oben, alle weiteren nimmt Spigot über den kurzen Weg „liegt darunter“.
     * Das Ergebnis ist Block für Block dasselbe (EarthRepaintTest vergleicht mit dem direkten Malen).
     *
     * Merkt je Säule den tiefsten und höchsten Block und das Material ganz oben – der Fingerabdruck, an dem
     * EarthRepaint später erkennt, ob ein Spieler die Oberfläche verändert hat.
     */
    static final class RegionSink implements DemGenerator.Sink {
        private final int baseX;
        private final int baseZ;
        private final BlockWriter writer;
        private final Function<Material, BlockData> factory;
        private final Map<Material, BlockData> cache = new EnumMap<>(Material.class);
        /** Je Säule: Befehle {y0, y1} und ihr Material, in Reihenfolge. */
        private final int[][] ranges = new int[256][];
        private final Material[][] mats = new Material[256][];
        private final int[] count = new int[256];
        final int[] minY = new int[256];
        final int[] maxY = new int[256];
        final Material[] top = new Material[256];

        RegionSink(int baseX, int baseZ, BlockWriter writer, Function<Material, BlockData> factory) {
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.writer = writer;
            this.factory = factory;
            Arrays.fill(minY, Integer.MAX_VALUE);
            Arrays.fill(maxY, Integer.MIN_VALUE);
        }

        private BlockData data(Material m) {
            BlockData d = cache.get(m);
            if (d == null) {
                d = factory.apply(m);
                cache.put(m, d);
            }
            return d;
        }

        @Override
        public void set(int x, int y, int z, Material m) {
            fill(x, y, z, y + 1, m);
        }

        @Override
        public void fill(int x, int y0, int z, int y1, Material m) {
            if (y1 <= y0) {
                return;
            }
            int i = z * 16 + x;
            int n = count[i];
            if (ranges[i] == null) {
                ranges[i] = new int[16];
                mats[i] = new Material[8];
            } else if (n == mats[i].length) {
                ranges[i] = Arrays.copyOf(ranges[i], n * 4);
                mats[i] = Arrays.copyOf(mats[i], n * 2);
            }
            ranges[i][2 * n] = y0;
            ranges[i][2 * n + 1] = y1;
            mats[i][n] = m;
            count[i] = n + 1;
            minY[i] = Math.min(minY[i], y0);
            maxY[i] = Math.max(maxY[i], y1 - 1);
        }

        /** Alle Säulen schreiben (von oben nach unten); danach ist top[] gefüllt. */
        void flush() {
            Material[] column = new Material[0];
            for (int i = 0; i < 256; i++) {
                int n = count[i];
                if (n == 0) {
                    continue;
                }
                int lo = minY[i];
                int hi = maxY[i];
                if (column.length < hi - lo + 1) {
                    column = new Material[hi - lo + 1];
                } else {
                    Arrays.fill(column, 0, hi - lo + 1, null);
                }
                for (int k = 0; k < n; k++) {
                    Material m = mats[i][k];
                    for (int y = ranges[i][2 * k]; y < ranges[i][2 * k + 1]; y++) {
                        column[y - lo] = m;
                    }
                }
                top[i] = column[hi - lo];
                int wx = baseX + (i & 15);
                int wz = baseZ + (i >> 4);
                for (int y = hi; y >= lo; y--) {
                    Material m = column[y - lo];
                    if (m != null) {
                        writer.set(wx, y, wz, data(m));
                    }
                }
                count[i] = 0;
            }
        }
    }
}
