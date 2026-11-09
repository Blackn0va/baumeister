package com.blackn0va.baumeister.plan;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bauplatz für Anbau- und Prüfketten-Tests: flaches Gras auf y=-1, ein Steinziegelhaus x 2..6, z 8..12 (Boden
 * Bruchstein y0, Wände y1..3, Tür vorne bei x4) – dasselbe Haus wie in SiteSurveyTest.
 */
final class HouseSite {

    static final Pos MIN = new Pos(-4, -2, -2);
    static final Pos MAX = new Pos(20, 10, 20);

    /** Wie PlanLinterTest: TestCatalog plus Wasser und Hebel, Grenzen wie in Settings. */
    static final PlanCompiler COMPILER = new PlanCompiler(id -> TestCatalog.KNOWN.contains(id)
            || Set.of("water", "lever").contains(id), "stone", new PlanCompiler.Limits(150_000, 192));

    private HouseSite() {
    }

    static Map<Pos, String> flatGround() {
        Map<Pos, String> w = new HashMap<>();
        for (int x = MIN.x(); x <= MAX.x(); x++) {
            for (int z = MIN.z(); z <= MAX.z(); z++) {
                w.put(new Pos(x, -1, z), "grass_block");
                w.put(new Pos(x, -2, z), "dirt");
            }
        }
        return w;
    }

    static void house(Map<Pos, String> w, int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                w.put(new Pos(x, 0, z), "cobblestone");
                for (int y = 1; y <= 3; y++) {
                    if (x == x0 || x == x1 || z == z0 || z == z1) {
                        w.put(new Pos(x, y, z), "stone_bricks");
                    }
                }
            }
        }
    }

    static SiteSurvey withHouse() {
        Map<Pos, String> w = flatGround();
        house(w, 2, 6, 8, 12);
        w.put(new Pos(4, 1, 8), "oak_door");
        w.put(new Pos(4, 2, 8), "oak_door");
        return scan(w);
    }

    static SiteSurvey free() {
        return scan(flatGround());
    }

    static SiteSurvey scan(Map<Pos, String> w) {
        return SiteSurvey.scan(MIN, MAX, p -> w.getOrDefault(p, "air"));
    }

    static String plan(String... steps) {
        return "{\"design\":\"t\",\"reply\":\"r\",\"mode\":\"new\",\"steps\":[" + String.join(",", steps) + "]}";
    }
}
