package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SiteSurveyTest {

    private static final Pos MIN = new Pos(-4, -2, -2);
    private static final Pos MAX = new Pos(20, 10, 20);

    /** Flaches Gelände: Gras auf y=-1, darunter Erde – man steht auf y=0. */
    private static Map<Pos, String> flatGround() {
        Map<Pos, String> w = new HashMap<>();
        for (int x = MIN.x(); x <= MAX.x(); x++) {
            for (int z = MIN.z(); z <= MAX.z(); z++) {
                w.put(new Pos(x, -1, z), "grass_block");
                w.put(new Pos(x, -2, z), "dirt");
            }
        }
        return w;
    }

    /** Kleines Haus x 2..6, z 8..12: Boden y0, Wände y1..3, Tür vorne bei x4. */
    private static void house(Map<Pos, String> w) {
        for (int x = 2; x <= 6; x++) {
            for (int z = 8; z <= 12; z++) {
                w.put(new Pos(x, 0, z), "cobblestone");
                for (int y = 1; y <= 3; y++) {
                    if (x == 2 || x == 6 || z == 8 || z == 12) {
                        w.put(new Pos(x, y, z), "stone_bricks");
                    }
                }
            }
        }
        w.put(new Pos(4, 1, 8), "oak_door");
        w.put(new Pos(4, 2, 8), "oak_door");
    }

    private static SiteSurvey survey(Map<Pos, String> w) {
        return SiteSurvey.scan(MIN, MAX, p -> w.getOrDefault(p, "air"));
    }

    @Test
    void flatFreeSiteHasNoStructuresAndGroundZero() {
        SiteSurvey s = survey(flatGround());
        assertFalse(s.hasStructures());
        String text = s.describe(4000, -3);
        assertTrue(text.contains("flat, you stand on y=0"), text);
        assertTrue(text.contains("site is free"));
    }

    @Test
    void findsHouseWithBoxMaterialsAndDoor() {
        Map<Pos, String> w = flatGround();
        house(w);
        SiteSurvey s = survey(w);
        assertEquals(1, s.structures().size());
        SiteSurvey.Structure h = s.structures().getFirst();
        assertEquals(new Pos(2, 0, 8), h.min());
        assertEquals(new Pos(6, 3, 12), h.max());
        assertEquals("stone_bricks", h.materials().keySet().iterator().next());
        assertEquals(1, h.doors().size(), "Tür einmal (untere Hälfte)");
        assertEquals(new Pos(4, 1, 8), h.doors().getFirst());
        String text = s.describe(4000, -3);
        assertTrue(text.contains("Structure 1: x 2..6, y 0..3, z 8..12"), text);
        assertTrue(text.contains("Doors at [4,1,8]"), text);
        assertTrue(text.contains("layers"), "Grundriss passt ins Budget");
    }

    @Test
    void treesAndBuriedBlocksAreNotBuildings() {
        Map<Pos, String> w = flatGround();
        for (int y = 0; y <= 4; y++) {
            w.put(new Pos(10, y, 2), "oak_log");
        }
        for (int x = 8; x <= 12; x++) {
            for (int z = 0; z <= 4; z++) {
                w.put(new Pos(x, 5, z), "oak_leaves");
                w.put(new Pos(x, 6, z), "oak_leaves");
            }
        }
        w.put(new Pos(0, -2, 0), "oak_planks");
        w.put(new Pos(1, -2, 0), "oak_planks");
        w.put(new Pos(2, -2, 0), "oak_planks");
        w.put(new Pos(3, -2, 0), "oak_planks");
        assertFalse(survey(w).hasStructures());
    }

    @Test
    void attachPointsNameTheSharedWallPlanes() {
        Map<Pos, String> w = flatGround();
        house(w);
        String text = survey(w).describe(4000, -3);
        assertTrue(text.contains("right side: shared wall plane x=6"), text);
        assertTrue(text.contains("left side: shared wall plane x=2"), text);
        assertTrue(text.contains("back side: shared wall plane z=12"), text);
        assertTrue(text.contains("doorway: fill air [6,1,10] to [6,2,10]"), text);
    }

    @Test
    void touchesStructureOnlyWhenAdjacentOrShared() throws Exception {
        Map<Pos, String> w = flatGround();
        house(w);
        SiteSurvey s = survey(w);
        PlanCompiler c = TestCatalog.compiler();
        CompiledBuild apart = c.compile(PlanParser.parse("{\"steps\":[{\"op\":\"walls\",\"block\":\"stone\","
                + "\"from\":[10,1,8],\"to\":[15,3,12]}]}"));
        CompiledBuild shared = c.compile(PlanParser.parse("{\"steps\":[{\"op\":\"walls\",\"block\":\"stone\","
                + "\"from\":[6,1,8],\"to\":[11,3,12]}]}"));
        assertFalse(s.touchesStructure(apart), "5 Blöcke Abstand ist kein Anbau");
        assertTrue(s.touchesStructure(shared));
    }

    @Test
    void overbuildCountsChangedStructureBlocksOnly() throws Exception {
        Map<Pos, String> w = flatGround();
        house(w);
        SiteSurvey s = survey(w);
        PlanCompiler c = TestCatalog.compiler();
        // Anbau rechts mit gemeinsamer Wand x=6 und einem Durchgang: 2 geänderte Blöcke.
        CompiledBuild annex = c.compile(PlanParser.parse("{\"steps\":["
                + "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[6,1,8],\"to\":[10,3,12]},"
                + "{\"op\":\"fill\",\"block\":\"air\",\"from\":[6,1,10],\"to\":[6,2,10]}]}"));
        assertEquals(2, s.overbuild(annex).count());
        // Quer durchs Haus: viele Blöcke.
        CompiledBuild through = c.compile(PlanParser.parse("{\"steps\":["
                + "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[0,1,10],\"to\":[12,3,10]}]}"));
        SiteSurvey.Overbuild ob = s.overbuild(through);
        assertTrue(ob.count() >= 6, "gezählt: " + ob.count());
        assertEquals(1, ob.structure());
        assertEquals("oak_planks", ob.after());
    }
}
