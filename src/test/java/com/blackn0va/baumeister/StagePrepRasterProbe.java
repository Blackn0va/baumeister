package com.blackn0va.baumeister;

import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.OsmImport;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Messprobe, kein Unit-Test (2026-11-01): Was kostet die Draufsicht für die Stadtansicht der App je Etappe? Gleiche
 * Etappe (echte OSM-Daten Bad Kreuznach, 120 m), je 30 Läufe nach Aufwärmen, mit und ohne Raster.
 *
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.StagePrepRasterProbe
 */
public final class StagePrepRasterProbe {

    public static void main(String[] args) throws Exception {
        CityModel city;
        try (InputStream in = StagePrepRasterProbe.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            city = OsmImport.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "Bad Kreuznach", 49.8451,
                    7.8676, 120);
        }
        CityPlanner.Plan plan = CityPlanner.plan(city, 50_000_000L);
        int[] cells = {0, 1, 2, 4};
        for (int warm = 0; warm < 10; warm++) {
            for (int c : cells) {
                StagePrep.prepare(plan, 0, 0, c);
            }
        }
        System.out.printf("Blöcke im Plan: %d, Ausdehnung %d x %d%n", plan.blocks(), plan.maxX() - plan.minX() + 1,
                plan.maxZ() - plan.minZ() + 1);
        for (int c : cells) {
            long best = Long.MAX_VALUE;
            long sum = 0;
            int runs = 30;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                StagePrep.prepare(plan, 0, 0, c);
                long dt = System.nanoTime() - t0;
                best = Math.min(best, dt);
                sum += dt;
            }
            System.out.printf("raster_zelle=%d  mittel=%.1f ms  bester=%.1f ms%n", c, sum / 1e6 / runs, best / 1e6);
        }
    }
}
