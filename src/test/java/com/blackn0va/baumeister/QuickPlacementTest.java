package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.PlanReview;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.QuickOrders;
import com.blackn0va.baumeister.plan.QuickPlacement;
import com.blackn0va.baumeister.plan.SiteSurvey;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Schnellauftrag neben dem Bestand statt verworfen (dev01 2026-11-04 20:22: Fort Knox neben dem Haus). */
class QuickPlacementTest {

    private static final Machines LIB = new Machines();
    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    /** Haus wie im Vorfall: Holzwände mitten vor dem Spieler, flacher Grasboden. */
    private static SiteSurvey houseInFront() {
        return SiteSurvey.scan(SiteFrame.SCAN_MIN, SiteFrame.SCAN_MAX, p -> {
            if (p.y() == -1) {
                return "grass_block";
            }
            if (p.y() < -1) {
                return "dirt";
            }
            boolean wall = p.y() >= 0 && p.y() <= 4 && p.x() >= -4 && p.x() <= 4 && p.z() >= 2 && p.z() <= 9
                    && (p.x() == -4 || p.x() == 4 || p.z() == 2 || p.z() == 9);
            return wall ? "spruce_planks" : "air";
        });
    }

    @Test
    void fortKnoxNextToTheHouseMovesInsteadOfBeingDropped() throws PlanException {
        SiteSurvey site = houseInFront();
        String json = QuickOrders.match("bau mir Fort Knox", LIB, 192).orElseThrow();
        CompiledBuild build = COMPILER.compile(PlanParser.parse(json));
        // Vorher: an der festen Stelle verworfen – genau der Vorfall.
        assertThrows(PlanException.class, () -> PlanReview.checkOverbuild(site, "bau mir Fort Knox", build, 12));
        Optional<int[]> shift = QuickPlacement.shift(site.structures(), build.min(), build.max(), -3);
        assertTrue(shift.isPresent());
        int[] s = shift.get();
        assertTrue(s[0] != 0 || s[1] != 0);
        CompiledBuild moved = COMPILER.compile(PlanParser.parse(BuildService.translate(json, s[0], s[1])));
        assertEquals(0, site.overbuild(moved).count(), "verschoben berührt das Haus nicht mehr");
        PlanReview.checkOverbuild(site, "bau mir Fort Knox", moved, 12);
    }

    @Test
    void theNearestFreeSpotWinsAndThePlayerStaysOutside() {
        SiteSurvey.Structure house = new SiteSurvey.Structure(1, new Pos(-4, 0, 2), new Pos(4, 4, 9), 100,
                java.util.Map.of(), List.of());
        // 5 breiter Bau direkt auf dem Haus: rechts daneben (x ab 4 + Abstand 2 + 1) ist am nächsten.
        int[] s = QuickPlacement.shift(List.of(house), new Pos(-2, 0, 3), new Pos(2, 3, 7), -3).orElseThrow();
        assertEquals(0, s[1]);
        assertEquals(9, Math.abs(s[0]), "x " + s[0]);
        // Ohne Bestand bleibt alles, wo es ist.
        int[] none = QuickPlacement.shift(List.of(), new Pos(0, 0, 0), new Pos(3, 3, 3), -3).orElseThrow();
        assertEquals(0, none[0]);
        assertEquals(0, none[1]);
        // Spieler (0, z −3) nie im Bau.
        assertFalse(QuickPlacement.free(List.of(), new Pos(-2, 0, -5), new Pos(2, 0, -1), 0, 0, -3));
    }

    @Test
    void theSpokenHintNamesTheDirection() {
        assertEquals("9 Blöcke weiter rechts", QuickPlacement.describe(9, 0));
        assertEquals("4 Blöcke weiter hinten", QuickPlacement.describe(0, 4));
        assertEquals("3 Blöcke weiter links und 2 weiter vorn", QuickPlacement.describe(-3, -2));
    }
}
