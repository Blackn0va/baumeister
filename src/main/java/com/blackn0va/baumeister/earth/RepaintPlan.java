package com.blackn0va.baumeister.earth;

import com.blackn0va.baumeister.terrain.DemGenerator;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;

/**
 * Was beim Neumalen eines geschätzten Chunks zu setzen ist – rein, ohne Server. Je Säule: Ist die Oberfläche noch so,
 * wie der Populator sie hinterlassen hat (oberster Block = gemerktes Material, darüber bis zur neuen Oberkante nur
 * Luft), wird die Säule auf den neuen Stand gebracht: jeder Block zwischen dem tiefsten und höchsten der beiden Stände,
 * der nicht schon stimmt. Sonst hat dort ein Spieler gebaut oder gegraben – die Säule bleibt, wie sie ist.
 *
 * Grenze, bewusst: Gänge unter der Oberfläche sieht der Fingerabdruck nicht; sie werden beim Neumalen gefüllt. Ein
 * vollständiger Vergleich bräuchte den alten Chunk Block für Block (~17 000 Blöcke je Chunk gespeichert).
 *
 * Threads: rein; Recording wird in einem Thread gefüllt und danach nur gelesen.
 */
final class RepaintPlan {

    private RepaintPlan() {
    }

    /** Liest den Block an (x, y, z) der Welt; x, z innerhalb des Chunks (0..15). */
    @FunctionalInterface
    interface BlockReader {
        Material at(int x, int y, int z);
    }

    /** Ein zu setzender Block (x, z im Chunk). */
    record Change(int x, int y, int z, Material material) {
    }

    /**
     * Neue Säule i: ihre Änderungen und ihr neuer Fingerabdruck. Säulen werden nur ganz gesetzt – wird der Chunk
     * mittendrin entladen, stimmt der Fingerabdruck jeder fertigen Säule schon mit der Welt überein.
     */
    record ColumnChanges(int index, List<Change> changes, int minY, int maxY, Material top) {
    }

    /** Ergebnis: Säulen mit Änderungen und wie viele Säulen neu gemalt bzw. wegen Spielerspuren behalten wurden. */
    record Result(List<ColumnChanges> columns, int repainted, int kept) {

        int blocks() {
            int n = 0;
            for (ColumnChanges c : columns) {
                n += c.changes().size();
            }
            return n;
        }
    }

    /** Neu gemalter Chunk im Speicher: je Säule die Schreibbefehle in Reihenfolge (spätere überschreiben frühere). */
    static final class Recording implements DemGenerator.Sink {
        private final List<List<int[]>> ops = new ArrayList<>(256);
        private final List<List<Material>> mats = new ArrayList<>(256);
        private final int[] minY = new int[256];
        private final int[] maxY = new int[256];

        Recording() {
            for (int i = 0; i < 256; i++) {
                ops.add(new ArrayList<>());
                mats.add(new ArrayList<>());
                minY[i] = Integer.MAX_VALUE;
                maxY[i] = Integer.MIN_VALUE;
            }
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
            ops.get(i).add(new int[] {y0, y1});
            mats.get(i).add(m);
            minY[i] = Math.min(minY[i], y0);
            maxY[i] = Math.max(maxY[i], y1 - 1);
        }

        /** Material der Säule i an y oder null (Luft). */
        Material[] column(int i, int lo, int hi) {
            Material[] out = new Material[hi - lo + 1];
            List<int[]> o = ops.get(i);
            List<Material> m = mats.get(i);
            for (int k = 0; k < o.size(); k++) {
                int a = Math.max(lo, o.get(k)[0]);
                int b = Math.min(hi + 1, o.get(k)[1]);
                for (int y = a; y < b; y++) {
                    out[y - lo] = m.get(k);
                }
            }
            return out;
        }
    }

    static Result plan(EstimatedChunks.Print old, Recording fresh, BlockReader world, int worldMaxY) {
        List<ColumnChanges> columns = new ArrayList<>();
        int repainted = 0;
        int kept = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x;
                int oldMax = old.maxY()[i];
                int lo = Math.min(old.minY()[i], fresh.minY[i]);
                int hi = Math.max(oldMax, fresh.maxY[i]);
                if (!untouched(old, i, x, z, world, Math.max(hi, oldMax + 1), worldMaxY)) {
                    kept++;
                    continue;
                }
                Material[] target = fresh.column(i, lo, hi);
                List<Change> changes = new ArrayList<>();
                for (int y = lo; y <= hi; y++) {
                    Material want = target[y - lo] != null ? target[y - lo] : Material.AIR;
                    if (world.at(x, y, z) != want) {
                        changes.add(new Change(x, y, z, want));
                    }
                }
                columns.add(new ColumnChanges(i, changes, fresh.minY[i], fresh.maxY[i],
                        target[fresh.maxY[i] - lo]));
                repainted++;
            }
        }
        return new Result(columns, repainted, kept);
    }

    /** Oberster Block wie gemerkt, darüber bis checkTo nur Luft (ein Spieler hätte dort gebaut). */
    private static boolean untouched(EstimatedChunks.Print old, int i, int x, int z, BlockReader world, int checkTo,
            int worldMaxY) {
        int oldMax = old.maxY()[i];
        if (world.at(x, oldMax, z) != old.top()[i]) {
            return false;
        }
        for (int y = oldMax + 1; y <= checkTo && y < worldMaxY; y++) {
            if (world.at(x, y, z) != Material.AIR) {
                return false;
            }
        }
        return true;
    }
}
