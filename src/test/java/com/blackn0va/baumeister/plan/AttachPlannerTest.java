package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.AttachPlanner.Side;
import com.blackn0va.baumeister.plan.AttachPlanner.Wall;
import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.blackn0va.baumeister.plan.PlanReview.Accept;
import com.blackn0va.baumeister.plan.PlanReview.LintMode;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Anbau deterministisch: Wandwahl, Ausrichtung des LLM-Plans an die Wand in allen vier Richtungen, Klemmen der
 * gemeinsamen Wand. Haus x 2..6, z 8..12, y 0..3 (HouseSite).
 */
class AttachPlannerTest {

    /**
     * Der gemessene Fehler (Testserver 2026-10-28): Das 7B traf das Haus, baute aber AUF statt neben die Wand –
     * Boden y4 und Wände y5..7 genau über dem Grundriss.
     */
    private static final String ON_TOP = HouseSite.plan(
            "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[2,4,8],\"to\":[6,4,12]}",
            "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[2,5,8],\"to\":[6,7,12]}");

    private static Accept review(String prompt, String plan) {
        PlanReview r = new PlanReview(HouseSite.COMPILER, ModuleLibrary.EMPTY, new PlanReview.Context(prompt,
                RequestHints.parse(prompt, Set.of()), HouseSite.withHouse(), false, 12, 1, LintMode.STRICT));
        PlanReview.Verdict v = r.judge(plan, 0);
        return assertInstanceOf(Accept.class, v, () -> v.toString());
    }

    /** Jeder feste Block des Anbaus liegt außerhalb des Altbaus auf der verlangten Seite, Boden auf y0. */
    private static void assertBesideTheWall(Side side, Accept a) {
        SiteSurvey s = HouseSite.withHouse();
        int minY = Integer.MAX_VALUE;
        for (Action act : a.build().actions()) {
            for (Change c : act.changes()) {
                if (c.block().isAir()) {
                    continue;
                }
                Pos p = c.pos();
                minY = Math.min(minY, p.y());
                boolean outside = switch (side) {
                    case RIGHT -> p.x() >= 6;
                    case LEFT -> p.x() <= 2;
                    case BACK -> p.z() >= 12;
                    case FRONT -> p.z() <= 8;
                };
                assertTrue(outside, side + ": " + p + " liegt im oder auf dem Altbau");
            }
        }
        assertEquals(0, minY, side + ": Boden auf Höhe des Altbaus");
        assertTrue(s.touchesStructure(a.build()), side + ": berührt den Altbau");
        assertTrue(s.overbuild(a.build()).count() <= 12, side + ": " + s.overbuild(a.build()));
        assertTrue(a.fit() != null && a.fit().moved(), side + ": verschoben");
    }

    @Test
    void onTopOfTheHouseIsMovedBesideItOnEverySide() {
        Map<String, Side> words = Map.of("rechts", Side.RIGHT, "links", Side.LEFT, "hinten", Side.BACK,
                "vorne", Side.FRONT);
        for (Map.Entry<String, Side> e : words.entrySet()) {
            String prompt = "bau " + e.getKey() + " eine Werkstatt an das Haus an";
            assertEquals(e.getValue(), AttachPlanner.choose(HouseSite.withHouse(), prompt).orElseThrow().side());
            assertBesideTheWall(e.getValue(), review(prompt, ON_TOP));
        }
    }

    @Test
    void shiftPerSideIsExact() {
        int[][] expected = {{4, -4, 0}, {-4, -4, 0}, {0, -4, 4}, {0, -4, -4}};
        String[] words = {"rechts", "links", "hinten", "vorne"};
        for (int i = 0; i < words.length; i++) {
            Accept a = review("bau " + words[i] + " eine Werkstatt an", ON_TOP);
            assertEquals(java.util.List.of(expected[i][0], expected[i][1], expected[i][2]),
                    java.util.List.of(a.fit().dx(), a.fit().dy(), a.fit().dz()), words[i]);
        }
    }

    /** „5 Blöcke neben das Haus“ (gemessen 2026-10-28 ohne Anschlussstellen) wird an die Wand gezogen. */
    @Test
    void gapIsClosed() {
        String apart = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[12,0,8],\"to\":[16,0,12]}",
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[12,1,8],\"to\":[16,3,12]}");
        Accept a = review("bau daneben eine Werkstatt", apart);
        assertEquals(-6, a.fit().dx());
        assertBesideTheWall(Side.RIGHT, a);
    }

    @Test
    void cleanAnnexOnAnotherSideStaysWhereItIs() {
        String left = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[-2,0,8],\"to\":[2,0,12]}",
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[-2,1,8],\"to\":[2,3,12]}");
        Accept a = review("bau eine Garage an das Haus an", left);
        assertFalse(a.fit().moved(), "sauber links angebaut, Vorgabe war rechts – nicht verschieben");
    }

    /** Ein Plan, der den Altbau mitzeichnet, ändert ihn nicht – er darf nicht weggeschoben werden (Haus doppelt). */
    @Test
    void planThatRedrawsTheHouseIsNotMoved() {
        String redraw = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"cobblestone\",\"from\":[2,0,8],\"to\":[6,0,12]}",
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[2,1,8],\"to\":[6,3,12]}",
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[6,1,8],\"to\":[10,3,12]}");
        Accept a = review("bau rechts etwas an", redraw);
        assertFalse(a.fit().moved());
    }

    /**
     * Anbau aus anderem Material mit eigener Wand auf der Wandebene x=6: Ohne Klemmen tauscht er 15 Steinziegel
     * gegen Eichenbretter (> max-überbauen 12) und scheitert am Überbau-Schutz. Mit Klemmen bleibt die alte Wand.
     */
    @Test
    void sharedWallKeepsTheOldMaterial() {
        String annex = HouseSite.plan(
                "{\"op\":\"walls\",\"block\":\"oak_planks\",\"from\":[6,1,8],\"to\":[10,3,12]}",
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[7,0,9],\"to\":[9,0,11]}");
        Accept a = review("bau rechts eine Werkstatt an", annex);
        assertEquals(15, a.fit().clamped());
        assertEquals(0, HouseSite.withHouse().overbuild(a.build()).count());
    }

    @Test
    void wallChoicePrefersRightAndAvoidsBlockedSides() {
        Wall w = AttachPlanner.choose(HouseSite.withHouse(), "bau eine Garage an das Haus an").orElseThrow();
        assertEquals(Side.RIGHT, w.side());
        assertEquals(6, w.plane());
        assertEquals(0, w.floorY());
        assertEquals(14, w.freeDepth(), "x 7..20 frei bis zum Scanrand");

        // Rechts ein zweites Haus mit einem Block Abstand: dort ist kein Platz mehr.
        Map<Pos, String> m = HouseSite.flatGround();
        HouseSite.house(m, 2, 6, 8, 12);
        HouseSite.house(m, 8, 9, 8, 11);
        Wall blocked = AttachPlanner.choose(HouseSite.scan(m), "bau eine Garage an das Haus an").orElseThrow();
        assertNotEquals(Side.RIGHT, blocked.side());
        assertEquals(Side.BACK, blocked.side(), "hinten 8 frei, links nur 6");
    }

    @Test
    void separatedVerbAtTheEndIsAnAttachOrder() {
        assertTrue(AttachPlanner.wanted("bau rechts eine Werkstatt an"));
        assertTrue(AttachPlanner.wanted("Bau eine Garage an!"));
        assertTrue(AttachPlanner.wanted("bau an das Haus an"));
        assertFalse(AttachPlanner.wanted("bau einen Turm an der Ecke"));
        assertFalse(AttachPlanner.wanted("bau ein Haus aus Fichtenholz"));
    }

    /**
     * Prüferbefund 2026-10-29: „anlegen“, „anpflanzen“, „anmachen“ enden ebenfalls auf ein abgetrenntes „an“. Ohne
     * Bau-Verb davor schob die Ausrichtung einen frei geplanten Teich oder Garten an die Hauswand.
     */
    @Test
    void separableVerbsWithoutBuildingAreNoAttachOrder() {
        for (String prompt : new String[] {"Leg hinter dem Haus einen Teich an", "pflanz Blumen an",
                "mach das Licht an"}) {
            assertFalse(AttachPlanner.wanted(prompt), prompt);
            assertTrue(AttachPlanner.choose(HouseSite.withHouse(), prompt).isEmpty(), prompt);
        }
    }

    /**
     * Prüferbefund 2026-10-29: „erweitere um ein Stockwerk“ ist kein Anbau daneben. Vorher wurde der Plan auf dem
     * Dach (das Stockwerk) zwangsweise neben das Haus auf den Boden gerückt.
     */
    @Test
    void storeyOnTopStaysOnTop() {
        for (String prompt : new String[] {"Erweitere das Haus um ein Stockwerk", "bau oben ein Stockwerk an"}) {
            assertTrue(AttachPlanner.choose(HouseSite.withHouse(), prompt).isEmpty(), prompt);
            Accept a = review(prompt, ON_TOP);
            assertEquals(null, a.fit(), prompt);
            assertEquals(4, a.build().min().y(), prompt + ": bleibt auf dem Dach");
        }
    }

    /**
     * „erweitern“ sagt nicht, wohin: Ein Keller unter dem Haus berührt es und ändert keinen Bestandsblock. Mit dem
     * breiten Muster für die Ausrichtung wurde er (Boden y-4 statt y0) auf den Boden neben das Haus gehoben.
     */
    @Test
    void cellarUnderTheHouseStaysBelow() {
        String cellar = HouseSite.plan(
                "{\"op\":\"walls\",\"block\":\"stone_bricks\",\"from\":[2,-4,8],\"to\":[6,-1,12]}",
                "{\"op\":\"fill\",\"block\":\"stone_bricks\",\"from\":[2,-5,8],\"to\":[6,-5,12]}");
        String prompt = "Erweitere das Haus um einen Keller";
        Accept a = review(prompt, cellar);
        assertEquals(-5, a.build().min().y(), () -> "Keller bleibt unter dem Haus, " + a.fit());
        assertEquals(null, a.fit());
        assertTrue(AttachPlanner.choose(HouseSite.withHouse(), prompt).isEmpty());
    }

    /** Zwei Häuser: Haus 1 wie immer, Haus 2 x 12..16, z 8..12; Brücke auf y3 dazwischen. */
    private static final String BRIDGE = HouseSite.plan(
            "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[7,3,10],\"to\":[11,3,10]}");

    private static SiteSurvey twoHouses() {
        Map<Pos, String> m = HouseSite.flatGround();
        HouseSite.house(m, 2, 6, 8, 12);
        HouseSite.house(m, 12, 16, 8, 12);
        return HouseSite.scan(m);
    }

    private static PlanReview.Verdict reviewOn(SiteSurvey survey, String prompt, String plan) {
        return new PlanReview(HouseSite.COMPILER, ModuleLibrary.EMPTY, new PlanReview.Context(prompt,
                RequestHints.parse(prompt, Set.of()), survey, false, 12, 1, LintMode.STRICT)).judge(plan, 0);
    }

    /**
     * Prüferbefund 2026-10-29: Die Brücke zwischen zwei Häusern wurde auf y0 vor Haus 1 gerückt und ersetzte dort 5
     * Bruchsteine des Bodens. Eine Verbindung berührt zwei Bauwerke – sie bleibt, wo sie ist, auch bei „daneben“.
     */
    @Test
    void bridgeBetweenTwoHousesIsNotMoved() {
        PlanReview.Verdict v = reviewOn(twoHouses(), "Verbinde die beiden Häuser mit einer Brücke", BRIDGE);
        Accept a = assertInstanceOf(Accept.class, v, () -> v.toString());
        assertEquals(3, a.build().min().y(), "Brücke bleibt auf y3");
        assertEquals(0, twoHouses().overbuild(a.build()).count());

        PlanReview.Verdict v2 = reviewOn(twoHouses(), "bau daneben einen Gang, der beide Häuser verbindet", BRIDGE);
        Accept a2 = assertInstanceOf(Accept.class, v2, () -> v2.toString());
        assertFalse(a2.fit() != null && a2.fit().moved(), "berührt zwei Bauwerke: nicht verschieben");
        assertEquals(3, a2.build().min().y());
    }

    /**
     * Prüferbefund 2026-10-29: Liegt der ganze Plan in der Altbauwand, ließ clamp nichts übrig, gab aber den
     * UNGEKLEMMTEN Bau zurück und meldete trotzdem „geklemmt“. Jetzt geht das als Fehler in die Reparaturrunde.
     */
    @Test
    void planInsideTheSharedWallGoesToRepair() {
        String inWall = HouseSite.plan(
                "{\"op\":\"fill\",\"block\":\"oak_planks\",\"from\":[6,1,9],\"to\":[6,2,11]}");
        PlanReview.Verdict v = reviewOn(HouseSite.withHouse(), "bau rechts eine Werkstatt an", inWall);
        PlanReview.Repair r = assertInstanceOf(PlanReview.Repair.class, v, () -> v.toString());
        assertTrue(r.reason().contains("lies entirely in the existing wall"), r.reason());
    }

    @Test
    void noWallWithoutAttachWordsOrBuildings() {
        assertTrue(AttachPlanner.choose(HouseSite.withHouse(), "bau einen Brunnen").isEmpty());
        assertTrue(AttachPlanner.choose(HouseSite.free(), "bau eine Garage an das Haus an").isEmpty());
    }

    @Test
    void instructionNamesOneWallWithNumbersToCopy() {
        String text = AttachPlanner.choose(HouseSite.withHouse(), "bau eine Garage an").orElseThrow().instruction();
        assertTrue(text.contains("right side of structure 1"), text);
        assertTrue(text.contains("Shared wall: x=6, z 8..12, y 0..3"), text);
        assertTrue(text.contains("x within 6..20"), text);
        assertTrue(text.contains("Doorway: fill air [6,1,10] to [6,2,10]"), text);
        assertTrue(text.length() < 600, "kurz halten: " + text.length());
    }
}
