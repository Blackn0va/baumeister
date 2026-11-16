package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.blackn0va.baumeister.plan.TunnelShape.Ground;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Tunnel und Treppe Block für Block (Wunsch 2026-11-16) – geprüft an einem Boden aus Stein mit Ausnahmen. */
class TunnelShapeTest {

    private static final TunnelStyle STONE = TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 30, null,
            TunnelOrder.Size.NORMAL);
    private static final Set<String> FLUIDS = Set.of("water", "lava");

    /**
     * Welt nach dem Bau, Aktion für Aktion. Wird eine feste Zelle Luft, darf daneben nichts Flüssiges stehen – sonst
     * liefe Wasser oder Lava in den Gang. Wird Flüssiges selbst Luft, darf daneben nur Flüssiges stehen, das im selben
     * Abschnitt gleich danach ebenfalls ausgeräumt wird (zwei Quellblöcke nebeneinander gehen nicht anders).
     */
    private static Map<Pos, String> simulate(TunnelTestSite site, TunnelShape.Result r) {
        Map<Pos, String> world = new HashMap<>();
        List<Action> actions = r.build().actions();
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            for (Change c : a.changes()) {
                boolean wasFluid = FLUIDS.contains(world.computeIfAbsent(c.pos(), site::id));
                world.put(c.pos(), c.block().id());
                if (!c.block().isAir()) {
                    continue;
                }
                for (Pos n : neighbours(c.pos())) {
                    if (FLUIDS.contains(world.computeIfAbsent(n, site::id))) {
                        assertTrue(wasFluid && airLaterInSlice(actions, i, n),
                                "Flüssiges neben offener Zelle " + c.pos() + ": " + n);
                    }
                }
            }
        }
        return world;
    }

    private static boolean airLaterInSlice(List<Action> actions, int from, Pos p) {
        int slice = actions.get(from).step();
        for (int j = from + 1; j < actions.size() && actions.get(j).step() == slice; j++) {
            Change c = actions.get(j).changes().getFirst();
            if (c.pos().equals(p) && c.block().isAir()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pos> neighbours(Pos p) {
        return List.of(p.add(1, 0, 0), p.add(-1, 0, 0), p.add(0, 1, 0), p.add(0, -1, 0), p.add(0, 0, 1),
                p.add(0, 0, -1));
    }

    private static String at(Map<Pos, String> world, TunnelTestSite site, int x, int y, int z) {
        return world.getOrDefault(new Pos(x, y, z), site.id(new Pos(x, y, z)));
    }

    private static BlockSpec specAt(TunnelShape.Result r, Pos p) {
        BlockSpec last = null;
        for (Action a : r.build().actions()) {
            for (Change c : a.changes()) {
                if (c.pos().equals(p)) {
                    last = c.block();
                }
            }
        }
        return last;
    }

    private static TunnelShape.Result tunnel(TunnelTestSite site, int length) {
        return TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, length, 192, STONE, site);
    }

    /** „bis ins Freie“: das alte Ende am Durchbruch und vor Wasser von oben. */
    private static TunnelShape.Result daylight(TunnelTestSite site, int length) {
        return TunnelShape.plan(new TunnelShape.Spec(TunnelOrder.Kind.TUNNEL, 1, 3, length, 192, 0,
                TunnelShape.NO_ANCHOR, true, STONE, true, TunnelShape.NO_DEPTH), site);
    }

    @Test
    void aTunnelInRockIsLinedArchedAndLitWithLanterns() {
        TunnelTestSite site = new TunnelTestSite();
        TunnelShape.Result r = tunnel(site, 12);
        assertEquals(0, r.first());
        assertEquals(11, r.last());
        assertEquals(TunnelShape.Stop.LENGTH, r.stop());
        Map<Pos, String> w = simulate(site, r);
        for (int z = 0; z <= 11; z++) {
            for (int y = 0; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    assertEquals("air", at(w, site, x, y, z), "Gang bei " + x + " " + y + " " + z);
                }
            }
            assertEquals("stone_brick_stairs", at(w, site, 1, 2, z));
            assertEquals("stone_brick_stairs", at(w, site, -1, 2, z));
            // Edel (Wunsch 2026-11-16): Boden mit Mittelstreifen, Wand mit Sockel – außer im Gurtbogen.
            assertEquals("smooth_stone", at(w, site, 0, -1, z), "Mittelstreifen");
            assertEquals("polished_andesite", at(w, site, 1, -1, z), "Boden");
            assertEquals("polished_andesite", at(w, site, -1, -1, z), "Boden");
            boolean ring = z == 0 || z % 6 == 2;
            assertEquals(ring ? "chiseled_stone_bricks" : "polished_andesite", at(w, site, 2, 0, z), "Sockel " + z);
            assertEquals(ring ? "chiseled_stone_bricks" : "stone_bricks", at(w, site, -2, 1, z), "Wand " + z);
            assertEquals("stone", at(w, site, 0, 0, 12), "dahinter bleibt der Fels");
        }
        BlockSpec arch = specAt(r, new Pos(1, 2, 5));
        assertEquals("right", arch.props().get("facing"));
        assertEquals("top", arch.props().get("half"));
        assertEquals("left", specAt(r, new Pos(-1, 2, 5)).props().get("facing"));
        // Laternen unter dem Gurtbogen im 3. und 9. Abschnitt, sonst Luft in der Mitte oben.
        assertEquals("lantern", at(w, site, 0, 2, 2));
        assertEquals("true", specAt(r, new Pos(0, 2, 2)).props().get("hanging"));
        assertEquals("lantern", at(w, site, 0, 2, 8));
        assertEquals("air", at(w, site, 0, 2, 5));
        assertEquals("chiseled_stone_bricks", at(w, site, 0, 3, 2), "Gurtbogen über der Laterne");
        assertEquals("chiseled_stone_bricks", at(w, site, 2, 0, 0), "Eingang als Gurtbogen");
        assertEquals(2, r.lights());
        for (Action a : r.build().actions()) {
            assertFalse(a.changes().getFirst().block().id().contains("torch"), "nie Fackeln");
        }
    }

    @Test
    void eachSliceGetsItsShellBeforeAnythingIsDugOpen() {
        TunnelShape.Result r = tunnel(new TunnelTestSite(), 8);
        List<Action> actions = r.build().actions();
        for (int k = 0; k <= r.last(); k++) {
            int lastShell = -1;
            int firstAir = Integer.MAX_VALUE;
            for (int i = 0; i < actions.size(); i++) {
                Action a = actions.get(i);
                if (a.step() != k) {
                    continue;
                }
                Pos p = a.anchor();
                boolean shell = p.y() == -1 || p.y() == 3 || Math.abs(p.x()) == 2;
                if (shell) {
                    lastShell = i;
                }
                if (a.isRemoval()) {
                    firstAir = Math.min(firstAir, i);
                }
            }
            assertTrue(lastShell < firstAir, "Abschnitt " + k + ": Hülle " + lastShell + ", erste Luft " + firstAir);
        }
    }

    @Test
    void lavaWaterAndCavesNeverReachTheWalkway() {
        TunnelTestSite site = new TunnelTestSite();
        site.set(new Pos(2, 1, 7), Ground.FLUID, "water");          // Wasser in der Wand
        site.box(-1, 1, 0, 2, 9, 10, Ground.FLUID, "lava");          // Lavasee voraus
        site.box(-3, 3, 3, 6, 4, 4, Ground.OPEN, "cave_air");        // Höhle über dem Gang
        site.set(new Pos(0, 3, 5), Ground.OPEN, "cave_air");         // Loch in der Decke
        site.box(-1, 1, 0, 2, 0, 0, Ground.FLUID, "water");          // erster Abschnitt unter Wasser
        TunnelShape.Result r = tunnel(site, 14);
        assertEquals(13, r.last());
        Map<Pos, String> w = simulate(site, r);
        for (int z = 0; z <= 13; z++) {
            for (int y = 0; y <= 2; y++) {
                for (int x = -1; x <= 1; x++) {
                    assertFalse(FLUIDS.contains(at(w, site, x, y, z)), "Flüssiges im Gang " + x + " " + y + " " + z);
                }
            }
        }
        assertEquals("stone_bricks", at(w, site, 2, 1, 7), "Wasser in der Wand ersetzt");
        assertNotEquals("cave_air", at(w, site, 0, 3, 5), "Deckenloch zu");
        assertNotEquals("cave_air", at(w, site, 0, 3, 4), "Decke unter der Höhle zu");
    }

    @Test
    void theFirstSliceDrainsWaterBeforeOpeningTheRockAroundIt() {
        TunnelTestSite site = new TunnelTestSite();
        site.set(new Pos(0, 1, 0), Ground.FLUID, "water");
        site.set(new Pos(1, 0, 0), Ground.FLUID, "lava");
        TunnelShape.Result r = tunnel(site, 3);
        Map<Pos, String> w = simulate(site, r);
        assertEquals("air", at(w, site, 0, 1, 0));
        assertEquals("air", at(w, site, 1, 0, 0));
    }

    @Test
    void theTunnelEndsSealedWhereItStops() {
        TunnelTestSite site = new TunnelTestSite();
        site.box(-1, 1, 0, 2, 6, 7, Ground.FLUID, "lava");
        TunnelShape.Result r = tunnel(site, 6);
        assertEquals(5, r.last());
        Map<Pos, String> w = simulate(site, r);
        for (int y = 0; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                assertEquals("stone_bricks", at(w, site, x, y, 6), "Abschluss vor der Lava " + x + " " + y);
            }
        }
    }

    @Test
    void builtThingsInsideStopTheTunnelAndInTheWallTheyStay() {
        TunnelTestSite site = new TunnelTestSite();
        site.set(new Pos(2, 1, 3), Ground.BUILT, "oak_planks");
        site.set(new Pos(0, 1, 7), Ground.BUILT, "chest");
        TunnelShape.Result r = tunnel(site, 20);
        assertEquals(TunnelShape.Stop.BUILT, r.stop());
        assertEquals(6, r.last());
        assertEquals(new Pos(0, 1, 7), r.stopAt());
        assertEquals("chest", r.stopId());
        assertNull(specAt(r, new Pos(2, 1, 3)), "fremde Wand bleibt unberührt");
        assertNull(specAt(r, new Pos(0, 1, 7)));
        TunnelTestSite bedrock = new TunnelTestSite();
        bedrock.box(-3, 3, -1, -1, 0, 30, Ground.FORBIDDEN, "bedrock");
        TunnelShape.Result low = tunnel(bedrock, 10);
        assertEquals(9, low.last(), "Grundgestein im Boden dient als Boden");
        assertNull(specAt(low, new Pos(0, -1, 4)));
    }

    @Test
    void aCellarOrMeadowInFrontIsLeftAloneAndUpToDaylightEndsAtTheOpen() {
        TunnelTestSite cellar = new TunnelTestSite();
        cellar.box(-1, 1, 0, 2, 0, 2, Ground.OPEN, "air");
        cellar.box(-3, 3, -1, -1, 0, 2, Ground.BUILT, "stone_bricks");
        TunnelShape.Result r = tunnel(cellar, 5);
        assertEquals(3, r.first());
        assertEquals(7, r.last());
        for (Action a : r.build().actions()) {
            assertTrue(a.anchor().z() >= 3, "nichts im Keller: " + a.anchor());
        }
        TunnelTestSite hill = new TunnelTestSite();
        hill.top = z -> z < 10 ? 50 : 1;
        TunnelShape.Result through = daylight(hill, 40);
        assertEquals(TunnelShape.Stop.THROUGH, through.stop());
        assertEquals(9, through.last());
        TunnelTestSite lake = new TunnelTestSite();
        lake.top = z -> z < 10 ? 50 : 1;
        lake.box(-1, 1, 2, 2, 10, 12, Ground.FLUID, "water");
        TunnelShape.Result wet = daylight(lake, 40);
        assertEquals(TunnelShape.Stop.WATER, wet.stop());
        assertEquals(8, wet.last(), "kein Abschnitt, dessen Front an den See grenzt");
        // Ohne „bis ins Freie“ geht es an beiden Stellen geradeaus weiter (Röhre, siehe unten).
        assertEquals(TunnelShape.Stop.LENGTH, tunnel(hill, 40).stop());
        assertEquals(TunnelShape.Stop.LENGTH, tunnel(lake, 40).stop());
    }

    /**
     * Wunsch 2026-11-16, „ein Tunnel muss gerade gehen – er wird irgendwann durch ca. die komplette Karte gehen“: Über
     * ein Tal läuft er auf derselben Höhe und Linie weiter, als geschlossene Röhre mit Fenstern in Augenhöhe (nicht im
     * Gurtbogen) und Pfeilern bis zum Grund unter jedem zweiten Licht. Vorher endete er an der Talkante (THROUGH).
     */
    @Test
    void aTunnelCrossesAValleyStraightAsAClosedTubeWithWindowsAndPillars() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> z >= 10 && z < 30 ? -12 : 50;
        TunnelShape.Result r = tunnel(site, 40);
        assertEquals(TunnelShape.Stop.LENGTH, r.stop());
        assertEquals(0, r.first());
        assertEquals(39, r.last());
        assertEquals(10, r.openFrom(), "erster Abschnitt im Freien");
        Map<Pos, String> w = simulate(site, r);
        for (int z = 10; z < 30; z++) {
            assertEquals("smooth_stone", at(w, site, 0, -1, z), "Mittelstreifen der Röhre bei " + z);
            assertEquals("polished_andesite", at(w, site, 1, -1, z), "Boden der Röhre bei " + z);
            for (int x = -1; x <= 1; x++) {
                assertNotEquals("air", at(w, site, x, 3, z), "Decke " + x + " " + z);
                for (int y = 0; y <= 1; y++) {
                    assertEquals("air", at(w, site, x, y, z), "Gang " + x + " " + y + " " + z);
                }
            }
            for (int side = -2; side <= 2; side += 4) {
                for (int y = 0; y <= 2; y++) {
                    assertNotEquals("air", at(w, site, side, y, z), "Wand " + side + " " + y + " " + z);
                }
            }
        }
        assertEquals("glass", at(w, site, 2, 1, 12), "Fenster in Augenhöhe");
        assertEquals("glass", at(w, site, -2, 1, 12));
        assertEquals("polished_andesite", at(w, site, 2, 0, 12), "unten der Sockel");
        assertEquals("stone_bricks", at(w, site, 2, 2, 12), "oben Wand");
        assertEquals("chiseled_stone_bricks", at(w, site, 2, 1, 14), "kein Fenster im Gurtbogen");
        assertEquals("lantern", at(w, site, 0, 2, 14), "Licht auch in der Röhre");
        assertEquals("stone_bricks", at(w, site, 2, 1, 5), "im Fels keine Fenster");
        // Pfeiler unter dem Licht bei 20 (jedes zweite: 8, 20, 32 – nur 20 liegt im Tal) bis zum Talboden (-12).
        for (int y = -11; y <= -2; y++) {
            assertEquals("stone_bricks", at(w, site, 2, y, 20), "Pfeiler rechts bei y " + y);
            assertEquals("stone_bricks", at(w, site, -2, y, 20), "Pfeiler links bei y " + y);
        }
        assertEquals("chiseled_stone_bricks", at(w, site, 2, -1, 20), "Kapitell oben auf dem Pfeiler");
        assertEquals("chiseled_stone_bricks", at(w, site, 0, -2, 20), "Querbalken unter dem Boden");
        assertEquals("air", at(w, site, 2, -5, 19), "zwischen den Pfeilern frei");
        // Gerade: nichts weicht seitlich oder in der Höhe ab (außer den Pfeilern nach unten).
        for (Action a : r.build().actions()) {
            Pos p = a.anchor();
            assertTrue(Math.abs(p.x()) <= 2 && p.y() <= 3 && p.y() >= -11, "außerhalb der Linie: " + p);
        }
    }

    /** Durch einen See: Hülle und Front setzen das Wasser zu, bevor innen etwas offen ist – der Gang bleibt trocken. */
    @Test
    void throughALakeTheTubeStaysDryAndSealed() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> z >= 10 && z < 30 ? -12 : 50;
        site.box(-6, 6, -11, 5, 10, 29, Ground.FLUID, "water");
        TunnelShape.Result r = tunnel(site, 40);
        assertEquals(TunnelShape.Stop.LENGTH, r.stop());
        assertEquals(39, r.last());
        Map<Pos, String> w = simulate(site, r);
        for (int z = 0; z <= 39; z++) {
            for (int y = 0; y <= 2; y++) {
                for (int x = -1; x <= 1; x++) {
                    assertFalse(FLUIDS.contains(at(w, site, x, y, z)), "Wasser im Gang " + x + " " + y + " " + z);
                }
            }
        }
        assertEquals("glass", at(w, site, 2, 1, 12), "Fenster unter Wasser");
        assertEquals("polished_andesite", at(w, site, 2, 0, 12), "Sockel");
        assertEquals("stone_bricks", at(w, site, 2, 2, 12));
        assertNotEquals("water", at(w, site, 0, 3, 12), "Decke");
        assertEquals("stone_bricks", at(w, site, -2, -6, 20), "Pfeiler durchs Wasser bis zum Grund");
    }

    /** Am Ende der Röhre im Freien steht ein Abschluss – daran erkennt TunnelScan das Ende zum Weiterbauen. */
    @Test
    void aTubeThatStopsInTheOpenEndsWithAFace() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> z >= 10 ? -12 : 50;
        TunnelShape.Result r = tunnel(site, 15);
        assertEquals(14, r.last());
        Map<Pos, String> w = simulate(site, r);
        for (int y = 0; y <= 2; y++) {
            for (int x = -1; x <= 1; x++) {
                assertEquals("stone_bricks", at(w, site, x, y, 15), "Abschluss " + x + " " + y);
            }
        }
    }

    @Test
    void smallTunnelsHangTheirLanternsInANicheAndLargeOnesOnAChain() {
        TunnelStyle small = TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 30, null, TunnelOrder.Size.SMALL);
        TunnelTestSite site = new TunnelTestSite();
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.SMALL, 10, 192, small,
                site);
        Map<Pos, String> w = simulate(site, r);
        assertEquals("lantern", at(w, site, 1, 1, 2), "Nische rechts");
        assertEquals("stone_bricks", at(w, site, 1, 2, 2), "Halt über der Nische");
        assertEquals("lantern", at(w, site, -1, 1, 8), "Nische links");
        for (int z = 0; z < 10; z++) {
            assertEquals("air", at(w, site, 0, 0, z));
            assertEquals("air", at(w, site, 0, 1, z), "Kopfhöhe frei");
        }
        TunnelStyle large = TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 30, null, TunnelOrder.Size.LARGE);
        TunnelTestSite big = new TunnelTestSite();
        TunnelShape.Result l = TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.LARGE, 10, 192, large, big);
        Map<Pos, String> lw = simulate(big, l);
        assertEquals("iron_chain", at(lw, big, 0, 3, 2));
        assertEquals("lantern", at(lw, big, 0, 2, 2));
        assertEquals("air", at(lw, big, 0, 1, 2), "unter der Laterne frei");
        assertEquals("stone_brick_stairs", at(lw, big, 2, 3, 4));
    }

    @Test
    void stairsClimbStepByStepToTheSurfaceWithRoomOverhead() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> 9;
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 0, 192, STONE,
                site);
        assertEquals(TunnelShape.Stop.SURFACE, r.stop());
        assertEquals(0, r.first());
        assertEquals(10, r.last(), "auf Stufe 10 steht man über der Oberfläche (9)");
        Map<Pos, String> w = simulate(site, r);
        assertEquals("smooth_stone", at(w, site, 0, -1, 0), "Absatz mit Mittelstreifen");
        assertEquals("polished_andesite", at(w, site, 1, -1, 0), "Absatz");
        for (int k = 1; k <= 10; k++) {
            BlockSpec step = specAt(r, new Pos(0, k - 1, k));
            assertEquals("polished_andesite_stairs", step.id(), "Stufe " + k);
            assertEquals("back", step.props().get("facing"));
            assertEquals("bottom", step.props().get("half"));
            assertEquals("air", at(w, site, 0, k, k), "Füße auf Stufe " + k);
            assertEquals("air", at(w, site, 0, k + 1, k), "Kopf auf Stufe " + k);
        }
        for (Action a : r.build().actions()) {
            Change c = a.changes().getFirst();
            assertFalse(!c.block().isAir() && c.pos().y() > 9, "nichts Gebautes über der Oberfläche: " + c.pos());
        }
    }

    @Test
    void stairsKnowWhenThereIsNoWayUp() {
        TunnelTestSite up = new TunnelTestSite();
        up.top = z -> -1;
        assertEquals(TunnelShape.Stop.ALREADY_UP, TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP,
                TunnelOrder.Size.NORMAL, 0, 192, STONE, up).stop());
        TunnelTestSite deep = new TunnelTestSite();
        deep.top = z -> 500;
        assertEquals(TunnelShape.Stop.NO_SURFACE, TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP,
                TunnelOrder.Size.NORMAL, 0, 20, STONE, deep).stop());
        TunnelShape.Result five = TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 5, 192,
                STONE, deep);
        assertEquals(TunnelShape.Stop.LENGTH, five.stop());
        assertEquals(5, five.last(), "Absatz und 5 Stufen");
        TunnelTestSite lake = new TunnelTestSite();
        lake.top = z -> 9;
        lake.box(-3, 3, 10, 12, 0, 40, Ground.FLUID, "water");
        assertEquals(TunnelShape.Stop.WATER, TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 0,
                192, STONE, lake).stop(), "unter einem See kein Ausgang");
    }

    @Test
    void theWorkerStandsInTheSliceBehind() {
        assertEquals(new Pos(0, 0, 2), TunnelShape.stand(TunnelOrder.Kind.TUNNEL, 3));
        assertEquals(new Pos(0, 0, -1), TunnelShape.stand(TunnelOrder.Kind.TUNNEL, 0));
        assertEquals(new Pos(0, 4, 4), TunnelShape.stand(TunnelOrder.Kind.STAIRS_UP, 5));
        assertEquals(new Pos(0, 0, 0), TunnelShape.stand(TunnelOrder.Kind.STAIRS_UP, 1));
        List<Integer> steps = new ArrayList<>();
        for (Action a : tunnel(new TunnelTestSite(), 3).build().actions()) {
            steps.add(a.step());
        }
        assertEquals(steps.stream().sorted().toList(), steps, "Abschnitte der Reihe nach");
    }

    private static final TunnelStyle BRIDGE = TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 64, null,
            TunnelOrder.Kind.BRIDGE, TunnelOrder.Size.NORMAL);

    private static TunnelShape.Result bridge(TunnelTestSite site, int length) {
        return TunnelShape.plan(TunnelOrder.Kind.BRIDGE, TunnelOrder.Size.NORMAL, length, 192, BRIDGE, site);
    }

    @Test
    void aBridgeCrossesTheSeaWithRailingLanternsAndPillarsDownToTheBottom() {
        TunnelTestSite site = new TunnelTestSite().sea(-10, 10, 0, 60, -12, -2);
        TunnelShape.Result r = bridge(site, 30);
        assertEquals(TunnelShape.Stop.LENGTH, r.stop());
        assertEquals(0, r.first());
        assertEquals(29, r.last());
        Map<Pos, BlockSpec> w = site.built(r);
        for (int z = 0; z <= 29; z++) {
            for (int x = -2; x <= 2; x++) {
                assertEquals(x == 0 ? "polished_andesite" : "stone_bricks", site.block(w, new Pos(x, -1, z)).id(),
                        "Deck mit Mittelstreifen " + x + " " + z);
            }
            // Balustrade (Wunsch 2026-11-16, „edel“): alle 3 Blöcke ein Pfosten, die Laternen stehen darauf.
            String rail = z % 3 == 2 ? "chiseled_stone_bricks" : "stone_brick_wall";
            assertEquals(rail, site.block(w, new Pos(2, 0, z)).id(), "Geländer rechts " + z);
            assertEquals(rail, site.block(w, new Pos(-2, 0, z)).id(), "Geländer links " + z);
            assertTrue(site.block(w, new Pos(0, 0, z)).isAir() && site.block(w, new Pos(0, 1, z)).isAir(),
                    "Gehweg " + z);
            boolean light = z % 6 == 2;
            assertEquals(light ? "lantern" : "air", site.block(w, new Pos(2, 1, z)).id(), "Laterne " + z);
            boolean pillar = z % 12 == 8;
            assertEquals(pillar ? "stone_bricks" : "water", site.block(w, new Pos(2, -6, z)).id(), "Pfeiler " + z);
            assertEquals(pillar ? "stone_bricks" : "water", site.block(w, new Pos(-2, -11, z)).id(), "Pfeilerfuß " + z);
            assertEquals(pillar ? "chiseled_stone_bricks" : "water", site.block(w, new Pos(0, -2, z)).id(),
                    "Querbalken " + z);
            assertEquals(pillar ? "chiseled_stone_bricks" : "water", site.block(w, new Pos(2, -2, z)).id(),
                    "Kapitell " + z);
        }
        assertEquals("false", w.get(new Pos(2, 1, 8)).props().get("hanging"), "Laterne steht auf dem Geländer");
        assertEquals(10, r.lights());
        for (Action a : r.build().actions()) {
            assertFalse(a.changes().getFirst().block().id().contains("torch"), "nie Fackeln");
        }
    }

    @Test
    void deepWaterGetsNoPillarsAndHillsOrWaterAboveTheDeckEndTheBridge() {
        TunnelTestSite deep = new TunnelTestSite().sea(-10, 10, 0, 60, -70, -2);
        Map<Pos, BlockSpec> dw = deep.built(bridge(deep, 30));
        assertEquals("water", deep.block(dw, new Pos(2, -5, 8)).id(), "68 tief: kein Pfeiler");
        assertEquals("stone_bricks", deep.block(dw, new Pos(1, -1, 8)).id(), "das Deck trotzdem");

        TunnelTestSite hill = new TunnelTestSite();
        hill.top = z -> z < 15 ? -2 : 5;
        TunnelShape.Result toHill = bridge(hill, 40);
        assertEquals(TunnelShape.Stop.HILL, toHill.stop());
        assertEquals(14, toHill.last());

        TunnelTestSite bump = new TunnelTestSite();
        bump.top = z -> z == 5 || z == 6 ? 0 : -2;
        TunnelShape.Result cut = bridge(bump, 10);
        assertEquals(TunnelShape.Stop.LENGTH, cut.stop(), "ein Block Hügel wird zum Hohlweg");
        assertTrue(bump.block(bump.built(cut), new Pos(0, 0, 5)).isAir());

        TunnelTestSite flood = new TunnelTestSite().sea(-10, 10, 0, 60, -12, -2);
        flood.box(-10, 10, 0, 0, 10, 60, Ground.FLUID, "water");
        TunnelShape.Result wet = bridge(flood, 40);
        assertEquals(TunnelShape.Stop.WATER, wet.stop());
        assertEquals(9, wet.last());

        TunnelTestSite house = new TunnelTestSite().sea(-10, 10, 0, 60, -12, -2);
        house.set(new Pos(0, 1, 12), Ground.BUILT, "oak_planks");
        TunnelShape.Result built = bridge(house, 40);
        assertEquals(TunnelShape.Stop.BUILT, built.stop());
        assertEquals(11, built.last());
    }

    @Test
    void continuingKeepsTheLightRhythmAndBuildsNoEntranceFrame() {
        TunnelShape.Spec next = new TunnelShape.Spec(TunnelOrder.Kind.TUNNEL, 1, 3, 10, 192, 1, -5, false, STONE,
                false, TunnelShape.NO_DEPTH);
        TunnelShape.Result r = TunnelShape.plan(next, new TunnelTestSite());
        assertEquals(1, r.first());
        assertEquals(10, r.last());
        Map<Pos, BlockSpec> w = new TunnelTestSite().built(r);
        assertEquals("lantern", w.get(new Pos(0, 2, 3)).id(), "Licht 6 nach dem letzten (dort bei -3)");
        assertEquals("lantern", w.get(new Pos(0, 2, 9)).id());
        assertEquals("stone_bricks", w.get(new Pos(2, 1, 1)).id(), "kein Eingangsbogen beim Weiterbauen");
        for (Action a : r.build().actions()) {
            assertTrue(a.step() >= 1 && a.anchor().z() >= 1, "das fertige Stück bleibt unberührt: " + a.anchor());
        }
    }

    private static TunnelShape.Result down(TunnelTestSite site, int length, int depth) {
        return TunnelShape.plan(new TunnelShape.Spec(TunnelOrder.Kind.STAIRS_DOWN, 1, 3, length, 192, 0,
                TunnelShape.NO_ANCHOR, true, STONE, false, depth), site);
    }

    /**
     * Treppe nach unten (Wunsch 2026-11-16): je Abschnitt eine Stufe tiefer, die volle Seite zum Spieler
     * (facing=front), Kopfhöhe frei, bis zur gewünschten Höhe.
     */
    @Test
    void stairsDownGoOneStepLowerPerSliceToTheTargetHeight() {
        TunnelTestSite site = new TunnelTestSite();
        TunnelShape.Result r = down(site, 0, -10);
        assertEquals(TunnelShape.Stop.DEPTH, r.stop());
        assertEquals(10, r.last(), "Füße auf Stufe 10 bei -10");
        Map<Pos, String> w = simulate(site, r);
        for (int k = 1; k <= 10; k++) {
            BlockSpec step = specAt(r, new Pos(0, -k - 1, k));
            assertEquals("polished_andesite_stairs", step.id(), "Stufe " + k);
            assertEquals("front", step.props().get("facing"), "fällt nach vorn " + k);
            assertEquals("bottom", step.props().get("half"));
            for (int y = -k; y <= -k + 1; y++) {
                assertEquals("air", at(w, site, 0, y, k), "Kopfhöhe " + k + " bei " + y);
            }
            String top = at(w, site, 0, -k + 2, k);
            assertTrue(top.equals("air") || top.equals("lantern"), "oben frei oder Laterne: " + top);
            assertNotEquals("air", at(w, site, 2, -k, k), "Wand " + k);
        }
        TunnelShape.Result steps = down(site, 6, TunnelShape.NO_DEPTH);
        assertEquals(TunnelShape.Stop.LENGTH, steps.stop());
        assertEquals(6, steps.last(), "6 Stufen");
    }

    /**
     * Höhle unterwegs: abgedichtet durchquert wie im Tunnel (kein Halt davor – ein erster Entwurf ließ die Treppe dort
     * über der Höhlendecke enden). Ende auf der letzten Schicht Grundgestein.
     */
    @Test
    void stairsDownSealACaveOnTheWayAndEndAtTheLastBedrockLayer() {
        TunnelTestSite cave = new TunnelTestSite();
        cave.box(-3, 3, -8, -6, 5, 12, Ground.OPEN, "cave_air");
        cave.set(new Pos(-2, -7, 6), Ground.FLUID, "lava");
        TunnelShape.Result r = down(cave, 20, TunnelShape.NO_DEPTH);
        assertEquals(TunnelShape.Stop.LENGTH, r.stop());
        assertEquals(20, r.last());
        Map<Pos, String> w = simulate(cave, r);
        assertEquals("polished_andesite", at(w, cave, -2, -7, 6), "Sockel statt Lava neben der Stufe");
        assertEquals("stone_bricks", at(w, cave, 2, -6, 6), "Wand in der Höhle zu");
        assertEquals("polished_andesite_stairs", at(w, cave, 0, -7, 6), "Stufe über der Höhle");
        TunnelTestSite bottom = new TunnelTestSite();
        bottom.box(-6, 6, -40, -20, 0, 60, Ground.FORBIDDEN, "bedrock");
        TunnelShape.Result b = down(bottom, 0, TunnelShape.NO_DEPTH);
        assertEquals(TunnelShape.Stop.FORBIDDEN, b.stop());
        assertTrue(b.last() < 19, "vor dem Grundgestein: " + b.last());
    }
}
