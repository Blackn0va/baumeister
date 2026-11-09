package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionUse.Target;
import com.blackn0va.baumeister.companion.CompanionUse.Use;

import org.junit.jupiter.api.Test;

/** Benutzen: Sätze verstehen, Blöcke zuordnen, von selbst nur Harmloses. */
class CompanionUseTest {

    @Test
    void understandsOrders() {
        assertEquals(new Use(Target.DOOR, true), CompanionUse.parse("mach die Tür auf"));
        assertEquals(new Use(Target.DOOR, false), CompanionUse.parse("mach die Tür zu"));
        assertEquals(new Use(Target.DOOR, false), CompanionUse.parse("schließ die Tür"));
        assertEquals(new Use(Target.DOOR, true), CompanionUse.parse("kannst du die Tür aufmachen"));
        assertEquals(new Use(Target.GATE, true), CompanionUse.parse("öffne das Tor"));
        assertEquals(new Use(Target.TRAPDOOR, false), CompanionUse.parse("mach die Falltür zu"));
        assertEquals(new Use(Target.LEVER, null), CompanionUse.parse("leg den Hebel um"));
        assertEquals(new Use(Target.LEVER, false), CompanionUse.parse("schalte den Hebel aus"));
        assertEquals(new Use(Target.LEVER, true), CompanionUse.parse("mach den Hebel an"));
        assertEquals(new Use(Target.BUTTON, null), CompanionUse.parse("drück mal den Knopf"));
        assertEquals(new Use(Target.BELL, null), CompanionUse.parse("läute die Glocke"));
        assertEquals(new Use(Target.NOTE, null), CompanionUse.parse("spiel den Notenblock"));
        assertEquals(new Use(Target.CHEST, true), CompanionUse.parse("öffne die Kiste"));
    }

    @Test
    void ignoresBuildingAndQuestions() {
        assertNull(CompanionUse.parse("bau mir eine Tür"));
        assertNull(CompanionUse.parse("wo ist die Kiste"));
        assertNull(CompanionUse.parse("die Tür ist schön"));
        assertNull(CompanionUse.parse("mach Tag"));
    }

    @Test
    void matchesBlocks() {
        assertTrue(CompanionUse.matches(Target.DOOR, "OAK_DOOR"));
        assertTrue(CompanionUse.matches(Target.DOOR, "IRON_DOOR"));
        assertTrue(CompanionUse.needsRedstone("IRON_DOOR"));
        assertFalse(CompanionUse.matches(Target.DOOR, "OAK_TRAPDOOR"));
        assertTrue(CompanionUse.matches(Target.BUTTON, "STONE_BUTTON"));
        assertTrue(CompanionUse.matches(Target.CHEST, "BARREL"));
    }

    @Test
    void modelWordsMapToTargetsAndDirections() {
        assertEquals(Target.DOOR, CompanionUse.target("door"));
        assertEquals(Target.LEVER, CompanionUse.target("Hebel"));
        assertEquals(Target.NOTE, CompanionUse.target("note_block"));
        assertNull(CompanionUse.target("tnt"), "Unbekanntes nie raten");
        assertEquals(Boolean.TRUE, CompanionUse.direction("open"));
        assertEquals(Boolean.FALSE, CompanionUse.direction("aus"));
        assertNull(CompanionUse.direction(""), "leer = umschalten");
    }

    @Test
    void curiosityNeverTouchesSwitches() {
        assertEquals(Target.BELL, CompanionUse.harmless("BELL"));
        assertEquals(Target.DOOR, CompanionUse.harmless("SPRUCE_DOOR"));
        assertNull(CompanionUse.harmless("LEVER"), "dahinter kann TNT hängen");
        assertNull(CompanionUse.harmless("STONE_BUTTON"));
        assertNull(CompanionUse.harmless("CHEST"));
        assertNull(CompanionUse.harmless("IRON_TRAPDOOR"));
    }
}
