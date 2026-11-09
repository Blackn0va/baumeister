package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.SiteSurvey;

import org.junit.jupiter.api.Test;

/** Nachricht an das LLM: beim Anbau genau eine festgelegte Wand statt drei Anschlussstellen je Bauwerk. */
class AttachMessageTest {

    private static SiteSurvey houseSite() {
        return SiteSurvey.scan(new Pos(-4, -1, -2), new Pos(16, 6, 16), p -> {
            if (p.y() == -1) {
                return "grass_block";
            }
            boolean wall = p.y() >= 0 && p.y() <= 3 && p.x() >= 0 && p.x() <= 4 && p.z() >= 2 && p.z() <= 6
                    && (p.x() == 0 || p.x() == 4 || p.z() == 2 || p.z() == 6);
            return wall ? "oak_planks" : "air";
        });
    }

    @Test
    void attachOrderGetsOneFixedWallBeforeTheFacts() {
        String msg = BuildService.userMessage("bau rechts eine Werkstatt an das Haus an", houseSite(), 3,
                ModuleLibrary.EMPTY);
        assertTrue(msg.contains("ATTACH (fixed by the server"), msg);
        assertTrue(msg.contains("Shared wall: x=4"), msg);
        assertFalse(msg.contains("To attach to structure"), "keine drei Seiten mehr zur Auswahl");
        assertTrue(msg.indexOf("ATTACH (fixed") < msg.indexOf("REQUEST FACTS"), "Vorgabe direkt vor den Fakten");
    }

    /**
     * Mit Gedächtnis antwortet das LLM meist mit mode update, und dann richtet PlanReview bewusst nicht aus – die
     * Vorgabe „fixed by the server“ wäre unwahr und kostete nur Prompt-Token (Prüferbefund 2026-10-29).
     */
    @Test
    void followUpWithMemoryGetsNoAttachText() {
        String msg = BuildService.userMessage("bau rechts eine Werkstatt an", houseSite(), 3, ModuleLibrary.EMPTY,
                "{\"design\":\"Haus\",\"mode\":\"new\",\"steps\":[]}", "bau ein Haus", 2);
        assertFalse(msg.contains("ATTACH"), msg);
        assertTrue(msg.contains("REQUEST FACTS"), msg);
    }

    @Test
    void ordinaryOrderNearAHouseGetsNoAttachText() {
        String msg = BuildService.userMessage("bau einen Brunnen", houseSite(), 3, ModuleLibrary.EMPTY);
        assertFalse(msg.contains("ATTACH"), msg);
        assertFalse(msg.contains("To attach to structure"), msg);
        assertTrue(msg.contains("Structure 1"), msg);
    }
}
