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
