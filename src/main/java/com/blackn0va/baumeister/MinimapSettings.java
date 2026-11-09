package com.blackn0va.baumeister;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Einstellungen der Minikarte (config.yml, Abschnitt karte). Grenzen so, dass eine verstellte Zahl den Server-Thread
 * nicht auffrisst: höchstens 10 ms je Tick fürs Lesen, Bild höchstens jeden zweiten Tick.
 */
record MinimapSettings(boolean enabled, Set<String> worlds, int frameTicks, double msPerTick, int refreshSeconds,
        int maxTiles, double defaultZoom, boolean overview, int maxRegions, boolean rotate) {

    /**
     * Stufenloser Zoom (Links-/Rechtsklick mit der Karte): Blöcke je Kartenpunkt von 1/16 (8 × 8 Blöcke, ein Block
     * 16 Pixel groß) bis 64 (8 192 × 8 192 Blöcke). Vorher 0,25 bis 4 – gewünscht war viel mehr
     * Spielraum (2026-10-31). Über ~4 hinaus kommen die Pixel aus der Übersicht (OverviewStore), nicht aus geladenen Chunks:
     * Die Karte lädt nie selbst, und die Sichtweite des Servers endet bei ~330 Blöcken.
     */
    static final double MIN_ZOOM = 1.0 / 16;
    static final double MAX_ZOOM = 64.0;
    /** Je Klick ×1,25 bzw. ÷1,25 – fein genug, dass es sich stufenlos anfühlt. */
    static final double ZOOM_STEP = 1.25;
    /** Schleichen + Klick: ×2 – von ganz nah bis ganz weit in 10 statt 31 Klicks. */
    static final double FAST_STEP = 2.0;

    static MinimapSettings from(ConfigurationSection root) {
        if (root == null) {
            return new MinimapSettings(true, Set.of(), 10, 2.0, 15, 4096, 1, true, 512, true);
        }
        Set<String> worlds = new HashSet<>();
        for (String w : root.getStringList("karte.welten")) {
            if (!w.isBlank()) {
                worlds.add(w.trim().toLowerCase(Locale.ROOT));
            }
        }
        double zoom = root.getDouble("karte.zoom", 1.0);
        return new MinimapSettings(
                root.getBoolean("karte.an", true),
                Set.copyOf(worlds),
                Math.clamp(root.getInt("karte.bild-ticks", 10), 2, 200),
                Math.clamp(root.getDouble("karte.ms-pro-tick", 2.0), 0.2, 10.0),
                Math.clamp(root.getInt("karte.auffrischen-sekunden", 15), 2, 3600),
                Math.clamp(root.getInt("karte.kacheln-im-speicher", 4096), 64, 65536),
                clampZoom(zoom),
                root.getBoolean("karte.übersicht", true),
                Math.clamp(root.getInt("karte.regionen-im-speicher", 512), 16, 4096),
                root.getBoolean("karte.drehen", true));
    }

    static double clampZoom(double z) {
        return Double.isFinite(z) ? Math.clamp(z, MIN_ZOOM, MAX_ZOOM) : 1.0;
    }

    /** Hineinzoomen: weniger Blöcke je Kartenpunkt. Rastet auf 1 ein, wenn ein Schritt darüber hinweggeht. */
    static double zoomIn(double z) {
        return zoomIn(z, false);
    }

    static double zoomOut(double z) {
        return zoomOut(z, false);
    }

    static double zoomIn(double z, boolean fast) {
        return snap(z, clampZoom(z / (fast ? FAST_STEP : ZOOM_STEP)));
    }

    static double zoomOut(double z, boolean fast) {
        return snap(z, clampZoom(z * (fast ? FAST_STEP : ZOOM_STEP)));
    }

    /** Maßstab 1 (ein Block je Punkt, das schärfste Bild ohne Vergrößerung) nie überspringen. */
    private static double snap(double from, double to) {
        return from > 1.0 && to < 1.0 || from < 1.0 && to > 1.0 ? 1.0 : to;
    }

    /**
     * Karte in dieser Welt? Leere Liste = jede Welt außer Nether und End. Vorher „nur NORMAL“ – damit fielen ausgerechnet
     * die Bauwelten des Baumeisters heraus: Erd- und Geländewelt sind eigene Dimensionen (Environment CUSTOM), der
     * Wunsch (dev01, 2026-10-31).
     */
    boolean allows(World w) {
        if (!enabled || w == null) {
            return false;
        }
        if (!worlds.isEmpty()) {
            return worlds.contains(w.getName().toLowerCase(Locale.ROOT));
        }
        World.Environment e = w.getEnvironment();
        return e != World.Environment.NETHER && e != World.Environment.THE_END;
    }
}
