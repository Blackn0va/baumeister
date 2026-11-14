package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.SiteSurvey;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Das gemeinsame Koordinatensystem von Scan und Plan: lokal (0,0,0) liegt abstand Blöcke vor den
 * Füßen des Spielers, x nach rechts, z von ihm weg. Vorher setzte das Plugin jeden Plan mittig vor
 * den Spieler – mit einem Nachbarhaus im Scan hätte das LLM dann in anderen Koordinaten geplant,
 * als gebaut wurde. Wer die Zentrierung wieder für alle Fälle einschaltet, zerstört den Anbau.
 */
record SiteFrame(World world, int feetX, int feetY, int feetZ, Orientation orientation, int ahead) {

    /** Scan-Kasten: links/rechts des Spielers, ein Stück hinter ihm bis weit vor ihn. */
    static final Pos SCAN_MIN = new Pos(-16, -4, -6);
    static final Pos SCAN_MAX = new Pos(32, 20, 36);

    /** Chunks (ChunkPrefetch.key) unter dem Scan-Kasten – die vier Ecken gedreht, dazwischen alles. */
    long[] scanChunks() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int x : new int[] {SCAN_MIN.x(), SCAN_MAX.x()}) {
            for (int z : new int[] {SCAN_MIN.z(), SCAN_MAX.z()}) {
                Pos w = orientation.toWorldOffset(new Pos(x, 0, z + ahead));
                minX = Math.min(minX, (feetX + w.x()) >> 4);
                minZ = Math.min(minZ, (feetZ + w.z()) >> 4);
                maxX = Math.max(maxX, (feetX + w.x()) >> 4);
                maxZ = Math.max(maxZ, (feetZ + w.z()) >> 4);
            }
        }
        long[] out = new long[(maxX - minX + 1) * (maxZ - minZ + 1)];
        int n = 0;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                out[n++] = ChunkPrefetch.key(cx, cz);
            }
        }
        return out;
    }

    Block blockAt(Pos local) {
        Pos w = orientation.toWorldOffset(local.add(0, 0, ahead));
        return world.getBlockAt(feetX + w.x(), feetY + w.y(), feetZ + w.z());
    }

    /**
     * Liest ~50 000 Blöcke auf dem Hauptthread. Auf dem Celeron von dev01 nicht gemessen; erwartet
     * werden einige zehn Millisekunden, einmal je Auftrag. Ungeladene Chunks werden dabei geladen.
     */
    SiteSurvey survey() {
        return SiteSurvey.scan(SCAN_MIN, SCAN_MAX, p -> {
            Pos w = orientation.toWorldOffset(p.add(0, 0, ahead));
            int y = feetY + w.y();
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return "air";
            }
            return world.getBlockAt(feetX + w.x(), y, feetZ + w.z()).getType().getKey().getKey();
        });
    }
}
