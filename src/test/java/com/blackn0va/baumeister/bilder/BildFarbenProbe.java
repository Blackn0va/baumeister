package com.blackn0va.baumeister.bilder;

import com.blackn0va.baumeister.plan.BlockSpec;

import java.util.Map;
import java.util.TreeMap;

/**
 * Messprobe (2026-11-19): Welche Block-IDs kommen in den Tunnelbildern vor, und welche haben keine eigene Farbe in
 * ReadmeBilder (dann Ersatzfarbe aus dem Namen – im ersten Bild lila Gurtbögen im Tiefenschiefer)?
 * Aufruf wie ReadmeBilder, ohne Argumente.
 */
public final class BildFarbenProbe {

    private BildFarbenProbe() {
    }

    public static void main(String[] args) {
        Map<String, Integer> ids = new TreeMap<>();
        for (TunnelBilder.Scene s : TunnelBilder.scenes()) {
            for (BlockSpec b : s.cells().values()) {
                String id = b.id().startsWith("minecraft:") ? b.id().substring(10) : b.id();
                int cut = id.indexOf('[');
                ids.merge(cut < 0 ? id : id.substring(0, cut), 1, Integer::sum);
            }
        }
        ids.forEach((id, n) -> System.out.printf("%-40s %6d %s%n", id, n,
                ReadmeBilder.hasColor(id) ? "" : "OHNE FARBE"));
    }
}
