package com.blackn0va.baumeister.earth;

import java.util.ArrayDeque;
import java.util.List;

import org.bukkit.Material;

/**
 * Setzen eines geplanten Neumalens, Säule für Säule – ohne Server (die Welt kommt über Live herein).
 *
 * Warum eigene Klasse: Der Plan (RepaintPlan) wird jetzt im Erdwelt-Pool gegen einen ChunkSnapshot gerechnet. Vorher
 * lief er ganz auf dem Hauptthread und las je Säule jeden Block von lo bis hi zweimal über chunk.getBlock() – im
 * gemessenen Norwegen-Fall (y 0 → 1 349) ~700 000 Lesezugriffe je Chunk in EINEM Tick, die Grenze
 * MAX_BLOCKS_PER_TICK galt nur fürs Setzen (Prüfer-Befund 2026-10-29). Zwischen Schnappschuss und Setzen vergehen aber
 * Ticks, in denen ein Spieler bauen kann – deshalb prüft step() vor jeder Säule den Fingerabdruck in der echten Welt
 * erneut (oberster gemerkter Block und der Block darüber, zwei Lesezugriffe). Wer diese Prüfung herausnimmt,
 * überschreibt, was ein Spieler in dieser Zeit auf die Oberfläche gestellt hat.
 *
 * Threads: nur Hauptthread (step); einmal angelegt, danach nicht geteilt.
 */
final class RepaintJob {

    /** Die echte Welt im Chunk: lesen und setzen (x, z im Chunk). */
    interface Live extends RepaintPlan.BlockReader {
        void set(int x, int y, int z, Material m);
    }

    private final long key;
    private final EstimatedChunks.Print old;
    private final ArrayDeque<RepaintPlan.ColumnChanges> pending;
    private final int worldMaxY;
    private int skipped;

    /** @param old Fingerabdruck, gegen den geplant wurde (Kopie – die Liste wird beim Setzen nachgeführt) */
    RepaintJob(long key, EstimatedChunks.Print old, List<RepaintPlan.ColumnChanges> columns, int worldMaxY) {
        this.key = key;
        this.old = old;
        this.pending = new ArrayDeque<>(columns);
        this.worldMaxY = worldMaxY;
    }

    long key() {
        return key;
    }

    boolean done() {
        return pending.isEmpty();
    }

    /** Säulen, die seit dem Schnappschuss verändert wurden und deshalb bleiben. */
    int skipped() {
        return skipped;
    }

    /**
     * Setzt die nächsten Säulen (jede ganz, nie halb), höchstens maxBlocks Blöcke (mindestens eine Säule). Nach jeder
     * Säule bekommt die Liste ihren neuen Fingerabdruck: Wird der Chunk mittendrin entladen oder der Server beendet,
     * prüft der nächste Plan die fertigen Säulen gegen das, was jetzt wirklich dort steht.
     *
     * @return gesetzte Blöcke
     */
    int step(Live live, EstimatedChunks store, int maxBlocks) {
        int n = 0;
        while (!pending.isEmpty() && (n == 0 || n + pending.peek().changes().size() <= maxBlocks)) {
            RepaintPlan.ColumnChanges col = pending.poll();
            if (!stillUntouched(live, col.index())) {
                skipped++;
                continue;
            }
            for (RepaintPlan.Change c : col.changes()) {
                live.set(c.x(), c.y(), c.z(), c.material());
            }
            n += col.changes().size();
            store.updateColumn(key, col.index(), col.minY(), col.maxY(), col.top());
        }
        return n;
    }

    /** Oberster gemerkter Block noch da und darüber Luft (wie RepaintPlan, nur die zwei entscheidenden Blöcke). */
    private boolean stillUntouched(RepaintPlan.BlockReader live, int i) {
        int x = i & 15;
        int z = i >> 4;
        int top = old.maxY()[i];
        if (live.at(x, top, z) != old.top()[i]) {
            return false;
        }
        return top + 1 >= worldMaxY || live.at(x, top + 1, z) == Material.AIR;
    }
}
