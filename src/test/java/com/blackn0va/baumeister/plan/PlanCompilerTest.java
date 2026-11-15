package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class PlanCompilerTest {

    private static CompiledBuild compile(String stepsJson) throws PlanException {
        return TestCatalog.compiler().compile(PlanParser.parse("{\"steps\":[" + stepsJson + "]}"));
    }

    private static Map<Pos, BlockSpec> finalState(CompiledBuild b) {
        Map<Pos, BlockSpec> m = new HashMap<>();
        for (Action a : b.actions()) {
            for (Change c : a.changes()) {
                m.put(c.pos(), c.block());
            }
        }
        return m;
    }

    @Test
    void wallsLeaveInteriorOpen() throws Exception {
        CompiledBuild b = compile("{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[6,3,6]}");
        assertEquals(24 * 3, b.blockCount());
        assertFalse(finalState(b).containsKey(new Pos(3, 2, 3)));
    }

    @Test
    void hollowBoxHasFloorAndCeiling() throws Exception {
        CompiledBuild b = compile("{\"op\":\"hollow\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,4,4]}");
        assertEquals(125 - 27, b.blockCount());
    }

    /**
     * Linterlücke (Gedaechtnis „Offen“): PlanLinter.solidBodies lässt fill mit hollow:true als hohl durch – also muss
     * der Compiler es auch hohl bauen. Vorher entstand ein massiver Klotz, den der Linter nie bemängelt hatte.
     */
    @Test
    void fillWithHollowFlagBuildsAShellLikeHollow() throws Exception {
        CompiledBuild b = compile("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,4,4],"
                + "\"hollow\":true}");
        assertEquals(125 - 27, b.blockCount());
        assertFalse(finalState(b).containsKey(new Pos(2, 2, 2)), "Kern bleibt leer");
    }

    @Test
    void fillAirWithHollowFlagStillClearsTheWholeBox() throws Exception {
        CompiledBuild b = compile("{\"op\":\"fill\",\"block\":\"air\",\"from\":[0,0,0],\"to\":[2,2,2],"
                + "\"hollow\":true}");
        assertEquals(27, b.blockCount(), "Ausräumen bleibt vollständig");
    }

    @Test
    void buildsBottomUpWithinStep() throws Exception {
        CompiledBuild b = compile("{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[3,5,3]}");
        int lastY = Integer.MIN_VALUE;
        for (Action a : b.actions()) {
            assertTrue(a.anchor().y() >= lastY, "Schicht darf nicht sinken");
            lastY = a.anchor().y();
        }
    }

    @Test
    void removalsRunTopDown() throws Exception {
        CompiledBuild b = compile("{\"op\":\"fill\",\"block\":\"air\",\"from\":[0,0,0],\"to\":[2,4,2]}");
        assertEquals(4, b.actions().getFirst().anchor().y());
        assertEquals(0, b.actions().getLast().anchor().y());
    }

    @Test
    void nearestNeighbourWalksAlongTheWall() throws Exception {
        CompiledBuild b = compile("{\"op\":\"walls\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[8,0,8]}");
        int jumps = 0;
        List<Action> as = b.actions();
        for (int i = 1; i < as.size(); i++) {
            if (as.get(i).anchor().distanceSquared(as.get(i - 1).anchor()) > 2) {
                jumps++;
            }
        }
        // Ein Ring lässt sich ohne Sprung ablaufen; ein Sprung am Ende (Ring schließen) ist erlaubt.
        assertTrue(jumps <= 1, "Sprünge: " + jumps);
    }

    @Test
    void windowOverridesWallButIsPlacedDuringWallStep() throws Exception {
        CompiledBuild b = compile(
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[4,3,4]},"
                        + "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[2,1,2]},"
                        + "{\"op\":\"set\",\"block\":\"glass_pane\",\"pos\":[2,2,0]}");
        Action window = b.actions().stream()
                .filter(a -> a.anchor().equals(new Pos(2, 2, 0))).findFirst().orElseThrow();
        assertEquals("glass_pane", window.changes().getFirst().block().id());
        assertEquals(0, window.step(), "gehört zum Wand-Schritt");
        int windowIndex = b.actions().indexOf(window);
        int lanternIndex = b.actions().indexOf(b.actions().stream()
                .filter(a -> a.anchor().equals(new Pos(2, 1, 2))).findFirst().orElseThrow());
        assertTrue(windowIndex < lanternIndex, "Fenster kommt mit der Wand, nicht danach");
    }

    @Test
    void doorIsOneActionWithBothHalvesFacingInward() throws Exception {
        CompiledBuild b = compile(
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[0,1,0],\"to\":[4,3,4]},"
                        + "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[2,1,0],\"facing\":\"front\"}");
        Action door = b.actions().stream()
                .filter(a -> a.anchor().equals(new Pos(2, 1, 0))).findFirst().orElseThrow();
        assertEquals(2, door.changes().size());
        Change lower = door.changes().get(0);
        Change upper = door.changes().get(1);
        assertEquals("lower", lower.block().props().get("half"));
        assertEquals("upper", upper.block().props().get("half"));
        assertEquals(new Pos(2, 2, 0), upper.pos());
        assertEquals("back", lower.block().props().get("facing"), "von außen gesetzt");
        long uppersAlone = b.actions().stream()
                .filter(a -> a.anchor().equals(new Pos(2, 2, 0))).count();
        assertEquals(0, uppersAlone);
    }

    @Test
    void doorWhoseLowerHalfGetsOverwrittenDisappearsCompletely() throws Exception {
        CompiledBuild b = compile(
                "{\"op\":\"door\",\"block\":\"oak_door\",\"pos\":[0,1,0]},"
                        + "{\"op\":\"set\",\"block\":\"stone\",\"pos\":[0,1,0]}");
        Map<Pos, BlockSpec> state = finalState(b);
        assertEquals("stone", state.get(new Pos(0, 1, 0)).id());
        assertFalse(state.containsKey(new Pos(0, 2, 0)));
    }

    /**
     * Plan B1 (Nutzer 2026-11-08): Gefährliches bleibt im Bau – ob es kommt, entscheidet die Stufe des Auftraggebers beim
     * Setzen (BuildJob, BuildRights). Vorher ließ der Compiler Lava, TNT und Betten mit „Gesperrter Block“ fallen,
     * auch für Admins, und Betten kamen nie (zweiteilig).
     */
    @Test
    void dangerousBlocksAndBedsStayInTheBuildForThePlacementCheck() throws Exception {
        CompiledBuild b = compile(
                "{\"op\":\"set\",\"block\":\"stone\",\"pos\":[0,0,0]},"
                        + "{\"op\":\"fill\",\"block\":\"tnt\",\"from\":[1,0,0],\"to\":[3,0,0]},"
                        + "{\"op\":\"set\",\"block\":\"minecraft:LAVA\",\"pos\":[4,0,0]},"
                        + "{\"op\":\"set\",\"block\":\"red_bed\",\"pos\":[5,0,0]}");
        Map<Pos, BlockSpec> s = finalState(b);
        assertEquals(7, b.blockCount(), "Stein, 3 TNT, Lava, Bett mit Fuß und Kopf");
        assertTrue(b.warnings().isEmpty(), () -> b.warnings().toString());
        BlockTiers.WorldFacts overworld = BlockTiers.WorldFacts.OVERWORLD;
        assertEquals(Tier.DANGEROUS, BlockTiers.place(s.get(new Pos(1, 0, 0)), overworld).tier());
        assertEquals(Tier.DANGEROUS, BlockTiers.place(s.get(new Pos(4, 0, 0)), overworld).tier());
        assertEquals(Tier.EVERYONE, BlockTiers.place(s.get(new Pos(5, 0, 0)), overworld).tier());
        assertEquals("head", s.get(new Pos(5, 0, 1)).props().get("part"), "Kopf vom Spieler weg");
    }

    @Test
    void unknownBlocksUseAliasOrFallback() throws Exception {
        CompiledBuild b = compile(
                "{\"op\":\"set\",\"block\":\"wooden_planks\",\"pos\":[0,0,0]},"
                        + "{\"op\":\"set\",\"block\":\"stone_brick\",\"pos\":[1,0,0]},"
                        + "{\"op\":\"set\",\"block\":\"unobtainium\",\"pos\":[2,0,0]}");
        Map<Pos, BlockSpec> s = finalState(b);
        assertEquals("oak_planks", s.get(new Pos(0, 0, 0)).id());
        assertEquals("stone_bricks", s.get(new Pos(1, 0, 0)).id());
        assertEquals("stone", s.get(new Pos(2, 0, 0)).id());
    }

    @Test
    void leavesBecomePersistent() throws Exception {
        CompiledBuild b = compile("{\"op\":\"set\",\"block\":\"oak_leaves\",\"pos\":[0,0,0]}");
        assertEquals("true", finalState(b).get(new Pos(0, 0, 0)).props().get("persistent"));
    }

    @Test
    void rejectsTooManyBlocks() {
        PlanCompiler small = new PlanCompiler(new TestCatalog(), "stone", new PlanCompiler.Limits(100, 48));
        assertThrows(PlanException.class, () -> small.compile(PlanParser.parse(
                "{\"steps\":[{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[9,0,10]}]}")));
    }

    @Test
    void skyscraperFitsTheDefaultLimits() throws Exception {
        // dev01 2026-10-28: „40 stöckiges Hochhaus“ → „Ein Schritt ist zu groß (16810 Blöcke, erlaubt 6000)“.
        // Mit den Vorgaben aus Settings (150 000 Blöcke, 192 je Richtung) muss ein 20×120×20-Turm durchgehen.
        PlanCompiler defaults = new PlanCompiler(new TestCatalog(), "stone", new PlanCompiler.Limits(150_000, 192));
        CompiledBuild b = defaults.compile(PlanParser.parse(
                "{\"steps\":[{\"op\":\"hollow\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[19,119,19]}]}"));
        assertTrue(finalState(b).size() > 9000, "Hülle: " + finalState(b).size());
    }

    @Test
    void skyscraperHasStoreysWindowsLadderEntranceAndRoof() throws Exception {
        PlanCompiler defaults = new PlanCompiler(new TestCatalog(), "stone", new PlanCompiler.Limits(150_000, 192));
        CompiledBuild b = defaults.compile(PlanParser.parse("{\"steps\":[{\"op\":\"skyscraper\",\"block\":\"stone_bricks\","
                + "\"window_block\":\"glass\",\"from\":[0,0,0],\"to\":[15,0,15],\"floors\":40}]}"));
        Map<Pos, BlockSpec> s = finalState(b);
        int top = 40 * PlanCompiler.FLOOR_HEIGHT;
        // Geschossdecken alle 4 Blöcke, dazwischen 3 Reihen Raum
        assertEquals("polished_andesite", s.get(new Pos(5, 4, 5)).id());
        assertEquals("air", s.get(new Pos(5, 5, 5)).id());
        assertEquals("polished_andesite", s.get(new Pos(5, top - 4, 5)).id());
        // Fassade: Fenster zwischen den Pfeilern, Pfeiler alle 4 Blöcke, Deckenband
        assertEquals("glass", s.get(new Pos(2, 6, 0)).id());
        assertEquals("stone_bricks", s.get(new Pos(4, 6, 0)).id());
        assertEquals("stone_bricks", s.get(new Pos(2, 8, 0)).id());
        // Leiter durchgehend vom Erdgeschoss bis aufs Dach, an der Rückwand
        for (int y = 1; y <= top; y++) {
            assertEquals("ladder", s.get(new Pos(1, y, 14)).id(), "Leiter bei y=" + y);
        }
        assertEquals("front", s.get(new Pos(1, 10, 14)).props().get("facing"));
        // Eingang vorn Mitte, Licht in der Decke, Dach mit Brüstung
        assertEquals("air", s.get(new Pos(7, 1, 0)).id());
        assertEquals("sea_lantern", s.get(new Pos(7, 8, 7)).id());
        assertEquals("stone_bricks", s.get(new Pos(5, top, 5)).id());
        assertEquals("stone_bricks", s.get(new Pos(0, top + 1, 7)).id());
        assertEquals(null, s.get(new Pos(5, top + 1, 5)));
    }

    @Test
    void skyscraperRejectsMoreFloorsThanFitTheHeightLimit() {
        assertThrows(PlanException.class, () -> PlanParser.parse("{\"steps\":[{\"op\":\"skyscraper\",\"block\":\"stone\","
                + "\"from\":[0,0,0],\"to\":[9,0,9],\"floors\":46}]}"));
    }

    /** Gang Tür → Leiter: Türachse nach hinten bis vor die Leiter, dann davor nach links; dazu die Leiterzelle. */
    private static List<Pos> houseFreeCells(int x0, int x1, int z0, int z1) {
        int midX = (x0 + x1) / 2;
        int ladderX = x0 + 1;
        int ladderZ = z1 - 1;
        List<Pos> out = new ArrayList<>();
        out.add(new Pos(ladderX, 0, ladderZ));
        for (int z = z0 + 1; z <= ladderZ - 1; z++) {
            out.add(new Pos(midX, 0, z));
        }
        for (int x = ladderX; x <= midX; x++) {
            out.add(new Pos(x, 0, ladderZ - 1));
        }
        return out;
    }

    private static long countAt(Map<Pos, BlockSpec> s, int y, String id) {
        return s.entrySet().stream().filter(e -> e.getKey().y() == y && e.getValue().id().equals(id)).count();
    }

    @Test
    void houseHasDoorWindowsRoofInteriorAndLadder() throws Exception {
        CompiledBuild b = compile("{\"op\":\"house\",\"block\":\"spruce_planks\",\"from\":[0,0,0],\"to\":[8,0,10],"
                + "\"floors\":2}");
        Map<Pos, BlockSpec> s = finalState(b);
        assertTrue(b.warnings().isEmpty(), () -> "Warnungen: " + b.warnings());
        assertTrue(s.values().stream().noneMatch(spec -> BlockRules.isRestricted(spec.id())), "eingeschränkter Block");
        int top = 2 * PlanCompiler.FLOOR_HEIGHT;

        // Tür vorn Mitte: eine Aktion mit beiden Hälften, von außen gesetzt; dahinter 2 Zellen Luft
        Action door = b.actions().stream()
                .filter(a -> a.anchor().equals(new Pos(4, 1, 0))).findFirst().orElseThrow();
        assertEquals(2, door.changes().size());
        assertEquals("spruce_door", door.changes().get(0).block().id());
        assertEquals("lower", door.changes().get(0).block().props().get("half"));
        assertEquals("upper", door.changes().get(1).block().props().get("half"));
        assertEquals("back", door.changes().get(0).block().props().get("facing"));
        for (int z = 1; z <= 2; z++) {
            for (int y = 1; y <= 2; y++) {
                assertEquals("air", s.get(new Pos(4, y, z)).id(), "hinter der Tür z=" + z + " y=" + y);
            }
        }

        // Leiter hinten links durchgehend, Loch in der Zwischendecke, oberste Decke zu
        for (int y = 1; y < top; y++) {
            assertEquals("ladder", s.get(new Pos(1, y, 9)).id(), "Leiter bei y=" + y);
            assertEquals("front", s.get(new Pos(1, y, 9)).props().get("facing"));
            assertFalse(s.get(new Pos(1, y, 10)).id().endsWith("_pane"), "Wand hinter der Leiter y=" + y);
            assertFalse(s.get(new Pos(1, y, 10)).isAir(), "Wand hinter der Leiter y=" + y);
        }
        assertEquals("ladder", s.get(new Pos(1, PlanCompiler.FLOOR_HEIGHT, 9)).id(), "Deckenloch");
        assertEquals("spruce_planks", s.get(new Pos(1, top, 9)).id(), "oberste Decke ohne Loch");

        for (int f = 0; f < 2; f++) {
            int yb = f * PlanCompiler.FLOOR_HEIGHT;
            assertEquals("spruce_planks", s.get(new Pos(4, yb, 5)).id(), "Boden/Decke y=" + yb);
            assertEquals(1, countAt(s, yb + 1, "crafting_table"), "Werkbank im Geschoss " + f);
            assertEquals(1, countAt(s, yb + 1, "chest"), "Truhe im Geschoss " + f);
            // ≥ 2 Fenster je Seite und Geschoss, auf Augenhöhe
            int wy = yb + 2;
            assertTrue(countSide(s, wy, p -> p.z() == 0) >= 2, "Fenster vorn, Geschoss " + f);
            assertTrue(countSide(s, wy, p -> p.z() == 10) >= 2, "Fenster hinten, Geschoss " + f);
            assertTrue(countSide(s, wy, p -> p.x() == 0) >= 2, "Fenster links, Geschoss " + f);
            assertTrue(countSide(s, wy, p -> p.x() == 8) >= 2, "Fenster rechts, Geschoss " + f);
            // Keine Einrichtung auf Leiter- oder Gangzellen
            for (Pos cell : houseFreeCells(0, 8, 0, 10)) {
                for (int dy = 1; dy <= 2; dy++) {
                    BlockSpec at = s.get(new Pos(cell.x(), yb + dy, cell.z()));
                    assertTrue(at.isAir() || at.id().equals("ladder"),
                            "Gang/Leiter belegt bei " + cell.x() + "/" + (yb + dy) + "/" + cell.z() + ": " + at.id());
                }
            }
        }
        // Eckstamm, Satteldach aus der passenden Treppe über der obersten Decke
        assertEquals("spruce_log", s.get(new Pos(0, 1, 0)).id());
        assertEquals("spruce_log", s.get(new Pos(8, PlanCompiler.FLOOR_HEIGHT, 10)).id(), "Stamm durch die Decke");
        assertTrue(s.entrySet().stream().anyMatch(e -> e.getKey().y() > top
                && e.getValue().id().equals("spruce_stairs")), "Dach aus spruce_stairs");
    }

    private static long countSide(Map<Pos, BlockSpec> s, int y, Predicate<Pos> side) {
        return s.entrySet().stream()
                .filter(e -> e.getKey().y() == y && side.test(e.getKey()) && e.getValue().id().equals("glass_pane"))
                .count();
    }

    @Test
    void smallestHouseCompilesWithoutOverlap() throws Exception {
        CompiledBuild b = compile("{\"op\":\"house\",\"block\":\"oak_planks\",\"from\":[0,0,0],\"to\":[4,0,4],"
                + "\"floors\":2}");
        Map<Pos, BlockSpec> s = finalState(b);
        assertTrue(b.warnings().isEmpty(), () -> "Warnungen: " + b.warnings());
        for (int f = 0; f < 2; f++) {
            int yb = f * PlanCompiler.FLOOR_HEIGHT;
            // Innen 3×3: Werkbank und Truhe hinten rechts, Leiter hinten links, Gang frei – nichts überschreibt sich
            assertEquals("crafting_table", s.get(new Pos(3, yb + 1, 3)).id());
            assertEquals("chest", s.get(new Pos(2, yb + 1, 3)).id());
            assertEquals("ladder", s.get(new Pos(1, yb + 1, 3)).id());
            for (Pos cell : houseFreeCells(0, 4, 0, 4)) {
                if (cell.x() == 1 && cell.z() == 3) {
                    continue;
                }
                assertTrue(s.get(new Pos(cell.x(), yb + 1, cell.z())).isAir(), "Gang bei " + cell);
            }
        }
        assertEquals("oak_door", s.get(new Pos(2, 1, 0)).id());
        assertEquals("oak_log", s.get(new Pos(4, 1, 4)).id());
    }

    @Test
    void houseFurnitureGrowsWithInteriorSize() throws Exception {
        // 11×13 → innen 9×11: alle Stufen. Rechte Innenwand x=9, Rückwand innen z=11, Tisch bei z=8.
        CompiledBuild b = compile("{\"op\":\"house\",\"block\":\"spruce_planks\",\"from\":[0,0,0],\"to\":[10,0,12]}");
        Map<Pos, BlockSpec> s = finalState(b);
        assertTrue(b.warnings().isEmpty(), () -> "Warnungen: " + b.warnings());
        assertEquals("crafting_table", s.get(new Pos(9, 1, 11)).id());
        assertEquals("chest", s.get(new Pos(8, 1, 11)).id());
        assertEquals("furnace", s.get(new Pos(7, 1, 11)).id());
        assertEquals("front", s.get(new Pos(7, 1, 11)).props().get("facing"));
        BlockSpec lantern = s.get(new Pos(6, 3, 6));
        assertEquals("lantern", lantern.id());
        assertEquals("true", lantern.props().get("hanging"));
        assertEquals("spruce_fence", s.get(new Pos(9, 1, 8)).id());
        assertEquals("spruce_pressure_plate", s.get(new Pos(9, 2, 8)).id());
        assertEquals("front", s.get(new Pos(9, 1, 7)).props().get("facing"), "Stuhl vor dem Tisch, Lehne vorn");
        assertEquals("back", s.get(new Pos(9, 1, 9)).props().get("facing"), "Stuhl hinter dem Tisch, Lehne hinten");
        assertEquals("bookshelf", s.get(new Pos(9, 1, 1)).id());
        assertEquals("red_carpet", s.get(new Pos(8, 1, 8)).id());
        // Einstöckig: keine Leiter, die ins Nichts führt
        assertTrue(s.values().stream().noneMatch(spec -> spec.id().equals("ladder")));
    }

    /**
     * Knapp zu groß wird gekürzt (dev01 2026-10-31: 13×201×5 und 35×3×241 bei 192, beide verworfen), weit darüber
     * bleibt es ein Planfehler. Grenze im Test 48 → bis 62 gekürzt.
     */
    @Test
    void tooWideBuildIsCroppedOnlyWhenCloseToTheLimit() throws Exception {
        CompiledBuild b = compile("{\"op\":\"line\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[60,0,0]}");
        assertEquals(47, b.max().x());
        assertEquals(48, b.actions().stream().mapToInt(a -> a.changes().size()).sum());
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("gekürzt")), b.warnings()::toString);
        assertThrows(PlanException.class, () -> compile(
                "{\"op\":\"line\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[70,0,0]}"));
    }

    @Test
    void gableRoofStairsClimbTowardRidgeAndGableIsFilled() throws Exception {
        CompiledBuild b = compile("{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,4],"
                + "\"shape\":\"gable\",\"overhang\":1,\"gable_block\":\"oak_planks\"}");
        Map<Pos, BlockSpec> s = finalState(b);
        // Überstand 1: z läuft -1..5, First entlang x (die längere Seite).
        assertEquals("back", s.get(new Pos(3, 4, -1)).props().get("facing"));
        assertEquals("front", s.get(new Pos(3, 4, 5)).props().get("facing"));
        // 7 Reihen quer → mittlere Reihe (z=2) auf Schicht 3 ist die Platte am First.
        assertEquals("oak_slab", s.get(new Pos(3, 7, 2)).id());
        // Giebeldreieck sitzt in der Wandebene x=0 und x=6, nicht im Überstand.
        assertEquals("oak_planks", s.get(new Pos(0, 5, 2)).id());
        assertEquals("oak_planks", s.get(new Pos(6, 5, 2)).id());
        assertFalse(s.containsKey(new Pos(-1, 5, 2)));
    }

    @Test
    void hipRoofClimbsFromAllFourSides() throws Exception {
        CompiledBuild b = compile("{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[4,4,4],"
                + "\"shape\":\"hip\",\"overhang\":0}");
        Map<Pos, BlockSpec> s = finalState(b);
        assertEquals("right", s.get(new Pos(0, 4, 2)).props().get("facing"));
        assertEquals("left", s.get(new Pos(4, 4, 2)).props().get("facing"));
        assertEquals("oak_slab", s.get(new Pos(2, 6, 2)).id());
    }

    @Test
    void secondRoofOverSameAreaIsRejected() {
        PlanException e = assertThrows(PlanException.class, () -> compile(
                "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,6]},"
                        + "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,5,0],\"to\":[6,5,6]}"));
        assertTrue(e.getMessage().contains("second roof"));
    }

    @Test
    void roofsOverSeparateWingsAreFine() throws Exception {
        compile("{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[0,4,0],\"to\":[6,4,6]},"
                + "{\"op\":\"roof\",\"block\":\"oak_stairs\",\"from\":[7,4,0],\"to\":[12,4,6]}");
    }

    @Test
    void hollowCylinderIsARing() throws Exception {
        CompiledBuild b = compile("{\"op\":\"cylinder\",\"block\":\"stone\",\"pos\":[0,0,0],\"radius\":3,"
                + "\"height\":1,\"hollow\":true}");
        Map<Pos, BlockSpec> s = finalState(b);
        assertTrue(s.containsKey(new Pos(3, 0, 0)));
        assertTrue(s.containsKey(new Pos(0, 0, -3)));
        assertFalse(s.containsKey(new Pos(0, 0, 0)));
        assertFalse(s.containsKey(new Pos(1, 0, 1)));
    }

    @Test
    void domeKeepsUpperHalf() throws Exception {
        CompiledBuild b = compile("{\"op\":\"sphere\",\"block\":\"glass\",\"pos\":[0,0,0],\"radius\":4,"
                + "\"hollow\":true,\"dome\":true}");
        assertTrue(b.actions().stream().allMatch(a -> a.anchor().y() >= 0));
        assertTrue(finalState(b).containsKey(new Pos(0, 4, 0)));
    }

    private static Action action(CompiledBuild b, Pos anchor) {
        return b.actions().stream().filter(a -> a.anchor().equals(anchor)).findFirst().orElseThrow();
    }

    private static List<String> parts(Action a, String key) {
        return a.changes().stream().map(c -> c.block().props().get(key)).toList();
    }

    /** Plan B3: zweiteilige Blöcke als Paar in einer Aktion; der Compiler ergänzt die zweite Hälfte. */
    @Test
    void twoPartBlocksAreOneActionWithBothHalves() throws Exception {
        PlanCompiler all = new PlanCompiler(id -> true, "stone", new PlanCompiler.Limits(6000, 48));
        CompiledBuild b = all.compile(PlanParser.parse("{\"steps\":["
                + "{\"op\":\"set\",\"block\":\"sunflower\",\"pos\":[0,1,0]},"
                + "{\"op\":\"set\",\"block\":\"red_bed[facing=left]\",\"pos\":[3,1,0]},"
                + "{\"op\":\"set\",\"block\":\"chest[facing=front,type=left]\",\"pos\":[6,1,0]},"
                + "{\"op\":\"set\",\"block\":\"chest[facing=front,type=right]\",\"pos\":[5,1,0]},"
                + "{\"op\":\"set\",\"block\":\"chest[facing=front,type=left]\",\"pos\":[9,1,0]}]}"));
        Action plant = action(b, new Pos(0, 1, 0));
        assertEquals(List.of("lower", "upper"), parts(plant, "half"));
        assertEquals(new Pos(0, 2, 0), plant.changes().get(1).pos());
        Action bed = action(b, new Pos(3, 1, 0));
        assertEquals(List.of("foot", "head"), parts(bed, "part"));
        assertEquals(new Pos(2, 1, 0), bed.changes().get(1).pos(), "Kopf in Blickrichtung links");
        Action chest = action(b, new Pos(6, 1, 0));
        assertEquals(new Pos(5, 1, 0), chest.changes().get(1).pos(), "Doppeltruhe in einem Klick");
        assertEquals(1, action(b, new Pos(9, 1, 0)).changes().size(), "Truhe ohne Partner bleibt einzeln");
        assertTrue(b.warnings().isEmpty(), () -> b.warnings().toString());
    }

    @Test
    void halfABedIsDroppedWithAWarning() throws Exception {
        CompiledBuild b = compile("{\"op\":\"set\",\"block\":\"red_bed\",\"pos\":[0,1,0]},"
                + "{\"op\":\"set\",\"block\":\"stone\",\"pos\":[0,1,1]}");
        assertEquals(1, b.blockCount(), "nur der Stein – ein Fuß ohne Kopf zerfiele");
        assertTrue(b.warnings().getFirst().contains("halbe"), b.warnings().toString());
    }

    /** Spiel-Vorlagen („sp_…“) Zelle für Zelle statt ganz oder gar nicht (Review 2026-11-15, TNT im Wüstentempel). */
    @Test
    void gameTemplatesAreCheckedCellByCellMachinesStayWhole() throws Exception {
        List<Change> cells = List.of(new Change(new Pos(0, 0, 0), BlockSpec.of("sandstone")),
                new Change(new Pos(1, 0, 0), BlockSpec.of("tnt")));
        ModuleLibrary.Module temple = new ModuleLibrary.Module("sp_wuestentempel_1", "", new Pos(2, 1, 1), cells);
        ModuleLibrary.Module farm = new ModuleLibrary.Module("eisenfarm", "", new Pos(2, 1, 1), cells);
        ModuleLibrary lib = new ModuleLibrary() {
            @Override
            public java.util.Optional<Module> find(String name) {
                return name.equals(temple.name()) ? java.util.Optional.of(temple)
                        : name.equals(farm.name()) ? java.util.Optional.of(farm) : java.util.Optional.empty();
            }

            @Override
            public List<Module> all() {
                return List.of(temple, farm);
            }
        };
        PlanCompiler c = new PlanCompiler(id -> true, "stone", new PlanCompiler.Limits(6000, 48), lib);
        for (String name : List.of("sp_wuestentempel_1", "eisenfarm")) {
            CompiledBuild b = c.compile(PlanParser.parse(QuickOrders.moduleJson(name, "x", -1)));
            int group = b.actions().getFirst().changes().getFirst().group();
            assertEquals(name.startsWith("sp_") ? 0 : 1, group, name);
            assertEquals(-1, b.min().y(), "Bodenversatz: unterste Lage einen Block unter den Füßen");
        }
    }

    /**
     * Bauteile ganz oder gar nicht (Plan B6): ihre Zellen tragen den Schritt als Gruppe. Platzhalter im Befehl drehen
     * und verschieben sich mit den Zellen – {1,0,0} zeigt danach weiter auf den Knopf.
     */
    @Test
    void moduleCellsShareTheirStepAsGroupAndPlaceholdersTurnWithThem() throws Exception {
        BlockSpec cmd = BlockSpec.parseTrusted("command_block[facing=front]{Command:\"setblock {1,0,0} stone\"}");
        ModuleLibrary.Module knopf = new ModuleLibrary.Module("knopf", "", new Pos(2, 1, 1), List.of(
                new Change(new Pos(0, 0, 0), cmd), new Change(new Pos(1, 0, 0), BlockSpec.of("stone_button"))));
        ModuleLibrary lib = new ModuleLibrary() {
            @Override
            public java.util.Optional<Module> find(String name) {
                return name.equals("knopf") ? java.util.Optional.of(knopf) : java.util.Optional.empty();
            }

            @Override
            public List<Module> all() {
                return List.of(knopf);
            }
        };
        PlanCompiler c = new PlanCompiler(id -> true, "stone", new PlanCompiler.Limits(6000, 48), lib);
        CompiledBuild b = c.compile(PlanParser.parse("{\"steps\":[{\"op\":\"set\",\"block\":\"stone\","
                + "\"pos\":[9,0,9]},{\"op\":\"module\",\"name\":\"knopf\",\"pos\":[0,0,0],"
                + "\"facing\":\"left\"}]}"));
        Map<Pos, Change> cells = new HashMap<>();
        b.actions().forEach(a -> a.changes().forEach(ch -> cells.put(ch.pos(), ch)));
        // Eine Viertelumdrehung: (x, z) → (z, −x), dann auf die kleinste Ecke geschoben.
        Change command = cells.get(new Pos(0, 0, 1));
        assertEquals("command_block", command.block().id());
        assertEquals("setblock {0,0,0} stone", ((Nbt.Str) command.block().nbt().get("Command")).value());
        assertEquals("stone_button", cells.get(new Pos(0, 0, 0)).block().id());
        assertEquals(2, command.group());
        assertEquals(2, cells.get(new Pos(0, 0, 0)).group());
        assertEquals(0, cells.get(new Pos(9, 0, 9)).group());
    }
}
