package com.blackn0va.baumeister;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * Bukkit-Seite der Bauvorschau: Umriss als Partikel zeichnen und verfallene Vorschauen melden. Der Takt (alle 10 Ticks)
 * gehört BuildService, weil er nur läuft, solange es Vorschauen gibt.
 *
 * Nur der Auftraggeber sieht den Umriss (Player.spawnParticle statt World.spawnParticle) – andere Spieler sollen nicht
 * rätseln, was das violette Gitter bedeutet. Höchstens POINTS Partikel je Vorschau und Takt, nur bis RANGE Blöcke weit.
 *
 * Threads: nur Hauptthread. Fehler: keine eigenen; der Aufrufer fängt an der Scheduler-Grenze.
 */
final class PreviewDisplay {

    static final int POINTS = 400;
    static final double RANGE = 160;
    /** Akzentfarbe des Hauses (#8b5cf6), Größe 1,3 – auf 30 Blöcke noch gut sichtbar. */
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(0x8b5cf6), 1.3f);

    private PreviewDisplay() {
    }

    static void tick(BuildPreview<?> previews) {
        for (BuildPreview.Pending<?> p : previews.expire()) {
            Player pl = Bukkit.getPlayer(p.owner());
            if (pl != null) {
                Texts.info(pl, "Die Vorschau ist verfallen – nichts gebaut. Neu: /bm vorschau <auftrag>");
            }
        }
        for (BuildPreview.Pending<?> p : previews.live()) {
            Player pl = Bukkit.getPlayer(p.owner());
            if (pl == null || !pl.getWorld().getName().equals(p.world())) {
                continue;
            }
            Location l = pl.getLocation();
            double dx = l.getX() - p.box().centerX();
            double dz = l.getZ() - p.box().centerZ();
            if (dx * dx + dz * dz > RANGE * RANGE) {
                continue;
            }
            List<double[]> pts = BuildPreview.outline(p.box(), POINTS);
            for (double[] pt : pts) {
                pl.spawnParticle(Particle.DUST, pt[0], pt[1], pt[2], 1, 0, 0, 0, 0, DUST);
            }
        }
    }
}
