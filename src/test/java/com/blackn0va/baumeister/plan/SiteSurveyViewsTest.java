package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Zeichenansichten nur, wenn sie helfen, und nur der Ausschnitt um die Bauwerke. Scan-Kasten wie SiteFrame
 * (x −16..32, y −4..20, z −6..36) – so groß ist der echte Scan, und so lang wurde der Prompt.
 */
class SiteSurveyViewsTest {

    private static final Pos MIN = new Pos(-16, -4, -6);
    private static final Pos MAX = new Pos(32, 20, 36);

    private static Map<Pos, String> ground(boolean hilly) {
        Map<Pos, String> w = new HashMap<>();
        for (int x = MIN.x(); x <= MAX.x(); x++) {
            for (int z = MIN.z(); z <= MAX.z(); z++) {
                int top = hilly ? -1 + Math.floorMod(x / 6 + z / 9, 3) : -1;
                for (int y = MIN.y(); y <= top; y++) {
                    w.put(new Pos(x, y, z), y == top ? "grass_block" : "dirt");
                }
            }
        }
        return w;
    }

    private static SiteSurvey scan(Map<Pos, String> w) {
        return SiteSurvey.scan(MIN, MAX, p -> w.getOrDefault(p, "air"));
    }

    /** Haus 9 × 11 (x 0..8, z 4..14), Boden y0, Wände y1..4. */
    private static Map<Pos, String> withHouse() {
        Map<Pos, String> w = ground(false);
        for (int x = 0; x <= 8; x++) {
            for (int z = 4; z <= 14; z++) {
                w.put(new Pos(x, 0, z), "oak_planks");
                for (int y = 1; y <= 4; y++) {
                    if (x == 0 || x == 8 || z == 4 || z == 14) {
                        w.put(new Pos(x, y, z), "stone_bricks");
                    }
                }
            }
        }
        return w;
    }

    @Test
    void viewsShowOnlyTheAreaAroundTheBuildings() {
        String views = scan(withHouse()).views();
        System.out.println("SiteSurveyViewsTest | ansichten | zeichen=" + views.length());
        assertTrue(views.contains("columns x=-8..16"), views);
        assertTrue(views.contains("first row = back z=22, last row = front z=-4"), views);
        String top = views.substring(views.indexOf("):\n") + 3, views.indexOf("FRONT VIEW"));
        assertEquals(27, top.split("\n").length, top);
        assertTrue(views.contains("#########"), "Hauswand sichtbar");
        assertTrue(views.length() < 1600, "Ansichten zu lang: " + views.length());
    }

    @Test
    void freeSiteGetsNoViewsEvenOnHillyGround() {
        SiteSurvey s = scan(ground(true));
        assertFalse(s.hasStructures());
        assertEquals("", s.views());
    }
}
