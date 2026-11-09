package com.blackn0va.baumeister.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Wo der Bildschirm hängt: Rückwand aus Blöcken und davor die Rahmen, Kachel je Rahmen – ohne Bukkit (testbar).
 *
 * Der Bildschirm steht DIST Blöcke vor dem Spieler, ihm zugewandt, die untere Reihe auf Fußhöhe + 1. Kachel (0, 0) ist
 * oben links, so wie der Spieler davorsteht; nach rechts zählt tx, nach unten ty. Die Blickrichtung wird auf eine der
 * vier Himmelsrichtungen gerundet (Rahmen hängen nur gerade).
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class ScreenWall {

    /** Abstand der Rahmen vor dem Spieler (Blöcke). */
    static final int DIST = 3;
    public static final int MAX_W = 8;
    public static final int MAX_H = 6;

    /** Himmelsrichtung, in die der Spieler schaut: dx/dz eines Schritts nach vorn. */
    public enum Facing {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);

        public final int dx;
        public final int dz;

        Facing(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        /** Aus Minecraft-Yaw (0 = Süden, 90 = Westen, 180 = Norden, 270 = Osten). */
        public static Facing ofYaw(float yaw) {
            float y = ((yaw % 360) + 360) % 360;
            if (y >= 45 && y < 135) {
                return WEST;
            }
            if (y >= 135 && y < 225) {
                return NORTH;
            }
            if (y >= 225 && y < 315) {
                return EAST;
            }
            return SOUTH;
        }
    }

    /** Ein Platz: Rückwand-Block (wall*), Rahmen-Block davor (frame*), Kachel. */
    public record Slot(int wallX, int wallY, int wallZ, int frameX, int frameY, int frameZ, int tx, int ty) {
    }

    private ScreenWall() {
    }

    /**
     * Teilen sich zwei Bildschirme einen Rahmen- oder Wandplatz? Gemessen 2026-11-07 am Spigot-Testserver: Ein zweiter
     * Bildschirm an derselben Stelle wurde gebaut (Rahmen sind Entities, der Block bleibt Luft) – 30 statt 24 Rahmen.
     */
    public static boolean overlaps(List<Slot> a, List<Slot> b) {
        java.util.Set<Long> used = new java.util.HashSet<>();
        for (Slot s : a) {
            used.add(key(s.frameX(), s.frameY(), s.frameZ()));
            used.add(key(s.wallX(), s.wallY(), s.wallZ()));
        }
        for (Slot s : b) {
            if (used.contains(key(s.frameX(), s.frameY(), s.frameZ()))
                    || used.contains(key(s.wallX(), s.wallY(), s.wallZ()))) {
                return true;
            }
        }
        return false;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    /**
     * Plätze für w × h Karten vor dem Spieler an (px, py, pz) mit Blick f. Die Rahmen zeigen zum Spieler (Gegenrichtung
     * von f), die Rückwand steht einen Block weiter weg.
     */
    public static List<Slot> slots(int px, int py, int pz, Facing f, int w, int h) {
        w = Math.max(1, Math.min(MAX_W, w));
        h = Math.max(1, Math.min(MAX_H, h));
        // Rechts aus Sicht des Spielers: Blick nach Norden (0,-1) → rechts ist Osten (+1, 0).
        int rx = -f.dz;
        int rz = f.dx;
        int cx = px + f.dx * DIST;
        int cz = pz + f.dz * DIST;
        int left = -(w - 1) / 2;
        List<Slot> out = new ArrayList<>(w * h);
        for (int ty = 0; ty < h; ty++) {
            int y = py + h - ty;
            for (int tx = 0; tx < w; tx++) {
                int fx = cx + rx * (left + tx);
                int fz = cz + rz * (left + tx);
                out.add(new Slot(fx + f.dx, y, fz + f.dz, fx, y, fz, tx, ty));
            }
        }
        return out;
    }
}
