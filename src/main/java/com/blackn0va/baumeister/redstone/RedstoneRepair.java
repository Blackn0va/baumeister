package com.blackn0va.baumeister.redstone;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

/**
 * Setzt die Umbauten aus RedstoneReader.Finding.fix in die Welt. Nach dem Setzen wird der neue Verstärker angestupst:
 * Ein vom Plugin gesetzter Verstärker prüft seinen Eingang sonst nicht (gemessen 2026-10-28 beim Logik-Compiler,
 * BuildJob.refreshRedstone) und bliebe aus.
 *
 * Threads: Hauptthread. Fehler: Darf der Spieler einen Block nicht ändern, wird gar nichts umgebaut (alles oder nichts).
 */
public final class RedstoneRepair {

    /** Was vorher dort stand – für „mach das rückgängig“ und den Rückbau, wenn es nicht geholfen hat. */
    public record Applied(RedstoneReader.Finding finding, BlockState before) {
    }

    private RedstoneRepair() {
    }

    /** Höchstens so viele Umbauten auf einmal – mehr Fehler in einer Schaltung sind eher Absicht als Versehen. */
    public static final int MAX_FIXES = 5;

    /** Leer, wenn nichts umzubauen ist oder ein Block nicht geändert werden darf (dann denied = true im Aufrufer). */
    public static List<Applied> apply(World w, List<RedstoneReader.Finding> findings, Predicate<Block> mayChange) {
        List<RedstoneReader.Finding> fixes = findings.stream().filter(f -> f.fix() != null).limit(MAX_FIXES).toList();
        for (RedstoneReader.Finding f : fixes) {
            if (!mayChange.test(block(w, f))) {
                return List.of();
            }
        }
        List<Applied> out = new ArrayList<>();
        for (RedstoneReader.Finding f : fixes) {
            Block b = block(w, f);
            out.add(new Applied(f, b.getState()));
            b.setBlockData(Bukkit.createBlockData(f.fix()), true);
            RedstoneLab.nudge(b);
        }
        return out;
    }

    /** Umbau zurücknehmen (Zustand vorher, mit Physik, damit die Schaltung neu rechnet). */
    public static void revert(Applied a) {
        a.before().update(true, true);
    }

    static Block block(World w, RedstoneReader.Finding f) {
        return w.getBlockAt(f.pos().x(), f.pos().y(), f.pos().z());
    }
}
