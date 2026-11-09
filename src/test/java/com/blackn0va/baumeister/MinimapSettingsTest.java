package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Set;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

class MinimapSettingsTest {

    /** Welt mit Name und Umgebung – nur diese beiden Methoden braucht allows(). */
    private static World world(String name, World.Environment env) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "getEnvironment" -> env;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static MinimapSettings settings(boolean on, Set<String> worlds) {
        return new MinimapSettings(on, worlds, 10, 2.0, 15, 4096, 1, true, 400, true);
    }

    @Test
    void defaultCoversTheBaumeistersOwnDimensionsButNotNetherOrEnd() {
        MinimapSettings s = settings(true, Set.of());
        assertTrue(s.allows(world("plotwelt", World.Environment.NORMAL)));
        // Erd- und Geländewelt sind eigene Dimensionen – genau die fehlten vorher (dev01, 2026-10-31).
        assertTrue(s.allows(world("world_baumeister_erde", World.Environment.CUSTOM)));
        assertTrue(s.allows(world("world_baumeister_gelaende", World.Environment.CUSTOM)));
        assertFalse(s.allows(world("world_nether", World.Environment.NETHER)));
        assertFalse(s.allows(world("world_the_end", World.Environment.THE_END)));
    }

    @Test
    void anExplicitListDecidesAloneAndIgnoresCase() {
        MinimapSettings s = settings(true, Set.of("plotwelt"));
        assertTrue(s.allows(world("PlotWelt", World.Environment.NORMAL)));
        assertFalse(s.allows(world("stadtwelt", World.Environment.NORMAL)));
    }

    @Test
    void switchedOffMeansNowhere() {
        assertFalse(settings(false, Set.of()).allows(world("plotwelt", World.Environment.NORMAL)));
        assertFalse(settings(true, Set.of()).allows(null));
    }

    @Test
    void clicksStepGentlyBetweenTheLimitsAndNeverSkipOneBlockPerPoint() {
        double z = 1.0;
        int clicks = 0;
        while (z < MinimapSettings.MAX_ZOOM) {
            z = MinimapSettings.zoomOut(z);
            clicks++;
        }
        assertEquals(MinimapSettings.MAX_ZOOM, z);
        assertEquals(19, clicks, "1 → 64 mit ×1,25");
        assertEquals(MinimapSettings.MAX_ZOOM, MinimapSettings.zoomOut(z), "oben ist Schluss");
        // Zurück nach unten rastet der Weg über 1 ein, statt bei 0,84 zu landen.
        boolean sawOne = false;
        while (z > MinimapSettings.MIN_ZOOM) {
            z = MinimapSettings.zoomIn(z);
            sawOne |= z == 1.0;
        }
        assertTrue(sawOne);
        assertEquals(MinimapSettings.MIN_ZOOM, z);
        assertEquals(MinimapSettings.MIN_ZOOM, MinimapSettings.zoomIn(z), "unten ist Schluss");
        assertEquals(1.0, MinimapSettings.clampZoom(Double.NaN));
        // Schleichen + Klick: ×2 – von 1 bis ganz hinaus in sechs Klicks, hinein bis 1/16 in vier.
        double f = 1.0;
        for (int i = 0; i < 6; i++) {
            f = MinimapSettings.zoomOut(f, true);
        }
        assertEquals(MinimapSettings.MAX_ZOOM, f);
        f = 1.0;
        for (int i = 0; i < 4; i++) {
            f = MinimapSettings.zoomIn(f, true);
        }
        assertEquals(MinimapSettings.MIN_ZOOM, f);
    }

    @Test
    void typedZoomAcceptsCommaAndRejectsNonsense() {
        assertEquals(1.5, Minimap.parseZoom("1,5"));
        assertEquals(0.25, Minimap.parseZoom("0.25"));
        assertEquals(5.0, Minimap.parseZoom("5"));
        assertEquals(0.0625, Minimap.parseZoom("0,0625"));
        assertEquals(-1, Minimap.parseZoom("100"));
        assertEquals(-1, Minimap.parseZoom("0,01"));
        assertEquals(-1, Minimap.parseZoom("viel"));
        assertEquals(-1, Minimap.parseZoom("NaN"));
    }
}
