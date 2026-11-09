package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.BuildPlan.Step;

import org.junit.jupiter.api.Test;

/** PlanParser: die Felder width und style und die Grenzen der Bauformen castle, wall, bridge, garden, tree. */
class PlanParserFormsTest {

    private static Step parse(String step) throws PlanException {
        return PlanParser.parse("{\"steps\":[" + step + "]}").steps().getFirst();
    }

    private static void rejects(String step, String expected) {
        PlanException e = assertThrows(PlanException.class, () -> parse(step), step);
        assertTrue(e.getMessage().contains(expected), () -> step + " → " + e.getMessage());
    }

    @Test
    void readsWidthAndStyle() throws Exception {
        Step s = parse("{\"op\":\"bridge\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[0,0,20],\"width\":5,"
                + "\"style\":\"Beam\"}");
        assertEquals(Op.BRIDGE, s.op());
        assertEquals(5, s.width());
        assertEquals("beam", s.style());
    }

    @Test
    void formsHaveTheirOwnDefaults() throws Exception {
        Step castle = parse("{\"op\":\"castle\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[24,0,24]}");
        assertEquals(6, castle.height());
        assertEquals(3, castle.width());
        assertEquals("", castle.style());
        Step wall = parse("{\"op\":\"wall\",\"block\":\"cobblestone\",\"from\":[0,0,0],\"to\":[9,0,0]}");
        assertEquals(5, wall.height());
        assertEquals(3, wall.width());
        // Brücke und Baum: 0 = rechnet die Form selbst
        assertEquals(0, parse("{\"op\":\"bridge\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[9,0,0]}")
                .height());
        Step tree = parse("{\"op\":\"tree\",\"block\":\"oak_log\",\"from\":[2,0,3]}");
        assertEquals(0, tree.height());
        assertEquals(new Pos(2, 0, 3), tree.pos(), "from statt pos wie bei set");
        // Alte Ops unverändert: height 1, width 0
        Step fill = parse("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[1,0,1]}");
        assertEquals(1, fill.height());
        assertEquals(0, fill.width());
    }

    @Test
    void rejectsOutOfRangeFormsWithARepairHint() {
        String c = "{\"op\":\"castle\",\"block\":\"stone_bricks\",\"from\":[0,0,0],";
        rejects(c + "\"to\":[15,0,30]}", "17×17");
        rejects(c + "\"to\":[96,0,30]}", "96×96");
        rejects(c + "\"to\":[20,0,20],\"width\":10}", "width muss 1 bis 9");
        rejects(c + "\"to\":[20,0,20],\"style\":\"arch\"}", "round oder square");
        rejects(c + "\"to\":[20,0,20],\"facing\":\"up\"}", "facing muss front, back, left oder right");
        String w = "{\"op\":\"wall\",\"block\":\"stone_bricks\",\"from\":[0,0,0],";
        rejects(w + "\"to\":[0,3,0]}", "zwei verschiedene Endpunkte");
        rejects(w + "\"to\":[9,0,0],\"width\":0}", "width muss 1 bis 9");
        String b = "{\"op\":\"bridge\",\"block\":\"stone_bricks\",\"from\":[0,0,0],";
        rejects(b + "\"to\":[0,0,2]}", "mindestens 4");
        rejects(b + "\"to\":[0,0,9],\"width\":10}", "width muss 1 bis 9");
        rejects(b + "\"to\":[0,0,9],\"style\":\"round\"}", "arch oder beam");
        rejects(b + "\"to\":[0,0,9],\"style\":\"gotisch\"}", "arch oder beam");
        String g = "{\"op\":\"garden\",\"block\":\"oak_fence\",\"from\":[0,0,0],";
        rejects(g + "\"to\":[3,0,9]}", "5×5");
        rejects(g + "\"to\":[64,0,9]}", "64×64");
        String t = "{\"op\":\"tree\",\"block\":\"oak_log\",\"pos\":[0,0,0],";
        rejects(t + "\"style\":\"beam\"}", "cone oder round");
        rejects("{\"op\":\"tree\",\"block\":\"oak_log\"}", "pos fehlt");
        rejects("{\"op\":\"castle\",\"block\":\"stone_bricks\",\"pos\":[0,0,0]}", "from und to fehlen");
    }

    @Test
    void wallBetweenDifferentHeightsIsTheRoomWallTypoAndIsRejected() {
        // „wall“ statt „walls“: Vorher wurde aus Raumwänden 1..3 still eine 5 hohe Wehrmauer quer durch das Haus.
        rejects("{\"op\":\"wall\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[6,3,6]}", "op walls");
    }

    @Test
    void sizesOutsideTheFormButInsideTheSchemaAreClampedWithAWarning() throws Exception {
        // Vorher PlanException → Reparaturrunde (1–3 min auf der CPU), obwohl die Grammatik den Wert zuließ.
        String c = "{\"op\":\"castle\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[24,0,24],";
        String[][] cases = {
            {c + "\"width\":1}", "width 1"},
            {c + "\"height\":3}", "height 3"},
            {c + "\"height\":30}", "height 30"},
            {c + "\"radius\":7}", "radius 7"},
            {"{\"op\":\"wall\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[9,0,0],\"width\":7}", "width 7"},
            {"{\"op\":\"wall\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[9,0,0],\"height\":30}",
                "height 30"},
            {"{\"op\":\"bridge\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[0,0,20],\"height\":30}",
                "height 30"},
            {"{\"op\":\"tree\",\"block\":\"oak_log\",\"pos\":[0,0,0],\"height\":40}", "height 40"},
            {"{\"op\":\"tree\",\"block\":\"oak_log\",\"pos\":[0,0,0],\"height\":3}", "height 3"},
        };
        for (String[] k : cases) {
            CompiledBuild b = FormChecks.compile(k[0]);
            assertTrue(b.warnings().stream().anyMatch(w -> w.contains(k[1]) && w.contains("gekappt")),
                    () -> k[0] + " → " + b.warnings());
            FormChecks.assertHolds(FormChecks.state(b), k[0]);
        }
        // Der Baum mit height 40 ist wirklich auf 32 gekappt
        int top = FormChecks.state(FormChecks.compile(cases[7][0])).keySet().stream().mapToInt(Pos::y).max()
                .orElseThrow();
        assertTrue(top <= Structures.MAX_TREE_HEIGHT, "Baum reicht bis y=" + top);
    }

    @Test
    void styleOnOrdinaryOpsIsIgnoredNotRejected() throws Exception {
        // Ein Modell, das style an einen fill hängt, soll nicht in die Reparaturrunde – der Schritt braucht es nicht.
        Step s = parse("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[1,0,1],\"style\":\"arch\"}");
        assertEquals(Op.FILL, s.op());
    }
}
