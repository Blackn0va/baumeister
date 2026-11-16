package com.blackn0va.baumeister.portal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Alle leeren Portalrahmen in einem Bereich finden und für „alle verbinden“ ordnen (2026-11-16, /bm portal alle:
 * vorher musste man sich für jeden der 9 Rahmen der riesigen Lobby einzeln hineinstellen und das Ziel tippen).
 *
 * Gesucht wird an der unteren linken Ecke jeder Öffnung: Obsidian, darüber frei, daneben Obsidian. Erst dort fragt
 * PortalFrame.at mit seinen strengen Regeln nach – so bleibt eine Lobby von 81 × 81 bei ein paar hundert
 * Rahmenprüfungen statt einer Füllsuche je freiem Block.
 *
 * Reihenfolge: große Öffnungen zuerst (das Hauptportal der Rückwand bekommt das erste Ziel), dann im Uhrzeigersinn
 * ab der Blickrichtung – so liegt die Verteilung für den, der auf dem Platz steht, nachvollziehbar vor ihm.
 *
 * Threads: zustandslos, ohne Bukkit. Fehler: keine.
 */
public final class PortalScan {

    private PortalScan() {
    }

    /** Alle Öffnungen, deren untere Reihe im Bereich liegt (Grenzen einschließlich), jede genau einmal. */
    public static List<PortalFrame.Opening> openings(PortalFrame.Blocks w, int x0, int y0, int z0, int x1, int y1,
            int z1) {
        Set<PortalFrame.Opening> found = new LinkedHashSet<>();
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                    if (!w.frame(x, y, z) || !w.open(x, y + 1, z)) {
                        continue;
                    }
                    if (!w.frame(x - 1, y + 1, z) && !w.frame(x + 1, y + 1, z) && !w.frame(x, y + 1, z - 1)
                            && !w.frame(x, y + 1, z + 1)) {
                        continue;
                    }
                    PortalFrame.Result r = PortalFrame.at(w, x, y + 1, z);
                    if (r.found()) {
                        found.add(r.opening());
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Große zuerst, dann im Uhrzeigersinn ab yaw (Minecraft: 0 = Süden, 90 = Westen) um (cx, cz). Bei gleichem Winkel
     * das kleinere x, dann z – damit die Reihenfolge nie vom Zufall der Suche abhängt.
     */
    public static List<PortalFrame.Opening> order(List<PortalFrame.Opening> openings, double cx, double cz,
            float yaw) {
        List<PortalFrame.Opening> out = new ArrayList<>(openings);
        out.sort(Comparator.comparingInt((PortalFrame.Opening o) -> -o.width() * o.height())
                .thenComparingDouble(o -> clockwise(o, cx, cz, yaw)).thenComparingInt(PortalFrame.Opening::x0)
                .thenComparingInt(PortalFrame.Opening::z0));
        return out;
    }

    /** Winkel der Öffnungsmitte von (cx, cz) aus, gemessen im Uhrzeigersinn ab der Blickrichtung, 0 bis unter 360. */
    static double clockwise(PortalFrame.Opening o, double cx, double cz, float yaw) {
        double mx = o.axis() == PortalFrame.Axis.X ? o.x0() + o.width() / 2.0 : o.x0() + 0.5;
        double mz = o.axis() == PortalFrame.Axis.X ? o.z0() + 0.5 : o.z0() + o.width() / 2.0;
        double toward = Math.toDegrees(Math.atan2(-(mx - cx), mz - cz));
        return ((toward - yaw) % 360 + 360) % 360;
    }
}
