package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class UpdateGuardTest {

    @Test
    void additionThatDropsMostOfTheBunkerKeepsIt() {
        // dev01 2026-11-04 18:05: 6 410 von ~13 600 Bunkerblöcken weggeräumt.
        assertTrue(UpdateGuard.keepOld("Baue mir zu dem Bunker noch einen Pool.", 13630, 6410));
        assertTrue(UpdateGuard.keepOld("Kannst du mir noch einen Pool hinbauen zu dem Bunker?", 13630, 13000));
    }

    @Test
    void explicitRemovalStillReplaces() {
        assertFalse(UpdateGuard.keepOld("entferne den Zaun und die Mauer", 1000, 600));
        assertFalse(UpdateGuard.keepOld("mach das Haus kleiner", 1000, 600));
        assertFalse(UpdateGuard.keepOld("baue das Dach aus Glas statt aus Holz", 1000, 400));
        assertFalse(UpdateGuard.keepOld("reiß den Turm ab", 1000, 900));
        assertFalse(UpdateGuard.keepOld("mach den Zaun weg.", 1000, 600));
        assertTrue(UpdateGuard.keepOld("bau noch Wege zum Pool dazu", 1000, 600), "„Wege“ ist kein Abriss");
    }

    @Test
    void smallChangesStillReplace() {
        assertFalse(UpdateGuard.keepOld("mach die Tür breiter", 1000, 20), "ein paar Blöcke sind ein Umbau");
        assertFalse(UpdateGuard.keepOld("noch ein Stockwerk", 1000, 200), "unter einem Viertel");
    }

    @Test
    void planWithOnlyTheNewPartKeepsEvenSmallBuilds() {
        assertTrue(UpdateGuard.keepOld("stell noch eine Laterne daneben", 30, 30));
        assertFalse(UpdateGuard.keepOld("entferne die Hütte", 30, 30));
    }

    @Test
    void mergeKeepsOldAndNewSteps() {
        String merged = UpdateGuard.merge("{\"design\":\"bunker\",\"mode\":\"new\",\"steps\":[{\"op\":\"fill\"}]}",
                "{\"design\":\"pool\",\"reply\":\"ok\",\"mode\":\"update\",\"steps\":[{\"op\":\"line\"},{\"op\":\"set\"}]}");
        var o = JsonParser.parseString(merged).getAsJsonObject();
        assertEquals(3, o.getAsJsonArray("steps").size());
        assertEquals("fill", o.getAsJsonArray("steps").get(0).getAsJsonObject().get("op").getAsString());
        assertEquals("pool", o.get("design").getAsString());
        assertEquals("new", o.get("mode").getAsString());
    }
}
