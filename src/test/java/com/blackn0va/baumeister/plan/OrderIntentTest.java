package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Neues Bauwerk oder Änderung am letzten Bau – Vorfall 2026-11-04 13:49 (zweimal „update“ statt neu). */
class OrderIntentTest {

    @Test
    void newStandaloneBuildingsGetAFreshSite() {
        for (String s : new String[] {"ein Haus", "ein neues Haus", "baue mir ein Haus.", "Bau mir noch ein Haus",
                "ein kleines gemütliches Holzhaus", "einen Redstone Computer", "eine Brücke über den Fluss",
                "Baue einen Wachturm", "eine Zuckerrohrfarm", "ein Baumhaus", "baue ein Haus auf dem Hügel"}) {
            assertTrue(OrderIntent.isNewStructure(s), s);
        }
    }

    @Test
    void changesToTheLastBuildKeepTheMemory() {
        for (String s : new String[] {"mach das Dach rot", "noch ein Stockwerk", "größere Fenster",
                "entferne den Zaun", "noch einen Turm daneben", "bau an das Haus eine Garage", "ein Fenster mehr",
                "neben das Haus einen Brunnen", "eine Tür", "", "mach weiter", "rot"}) {
            assertFalse(OrderIntent.isNewStructure(s), s);
        }
    }
}
