package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Grabaufträge ohne LLM (Wunsch 2026-11-16): Tunnel mitgraben, im Schnellgang, Treppe bis zur Oberfläche. */
class TunnelOrderTest {

    private static TunnelOrder order(String prompt) {
        return TunnelOrder.parse(prompt).orElseThrow(() -> new AssertionError("kein Grabauftrag: " + prompt));
    }

    @Test
    void withoutLengthHeDigsAlongWithThePlayer() {
        for (String p : List.of("grab mit mir einen Tunnel", "lass uns einen Tunnel graben", "bau mir einen Tunnel",
                "grab einen Stollen", "Tunnel", "wir graben zusammen einen Tunnel")) {
            TunnelOrder o = order(p);
            assertEquals(TunnelOrder.Kind.TUNNEL, o.kind(), p);
            assertEquals(0, o.length(), p);
            assertTrue(o.follow(), p);
            assertFalse(o.fast(), p);
        }
        assertTrue(order("grab mit mir einen Tunnel").together());
        assertTrue(order("lass uns einen Tunnel graben").together());
        assertFalse(order("bau mir einen Tunnel").together(), "„mir“ allein heißt für mich, nicht mit mir");
    }

    @Test
    void fastOrALengthMeansHeDigsAlone() {
        TunnelOrder fast = order("grab schnell einen Tunnel, 40 Blöcke");
        assertTrue(fast.fast());
        assertEquals(40, fast.length());
        assertFalse(fast.follow());
        TunnelOrder fixed = order("bau einen Tunnel 30 Blöcke lang");
        assertEquals(30, fixed.length());
        assertFalse(fixed.fast());
        assertFalse(fixed.follow());
        assertTrue(order("Tunnel im Schnellverfahren").fast());
        assertTrue(order("tunnel 40 schnell").fast(), "so kommt /bm tunnel 40 schnell an");
        assertEquals(20, order("grab einen Tunnel zwanzig Meter weit").length());
        // Mit Länge und „mit mir“: mitgraben, aber höchstens so weit.
        TunnelOrder together = order("grab mit mir einen Tunnel 50 Blöcke");
        assertTrue(together.follow());
        assertEquals(50, together.length());
    }

    @Test
    void sizeDirectionAndMaterialAreRead() {
        assertEquals(TunnelOrder.Size.LARGE, order("lass uns einen großen Tunnel nach Norden graben").size());
        assertEquals(TunnelOrder.Turn.NORTH, order("lass uns einen großen Tunnel nach Norden graben").turn());
        assertEquals(TunnelOrder.Size.SMALL, order("grab einen kleinen Stollen nach links").size());
        assertEquals(TunnelOrder.Turn.LEFT, order("grab einen kleinen Stollen nach links").turn());
        assertEquals(TunnelOrder.Size.NORMAL, order("grab einen Tunnel 3 mal 3").size());
        assertEquals(TunnelOrder.Turn.SOUTH, order("bau einen Tunnel Richtung Süden").turn());
        assertEquals("deepslate_bricks", order("grab einen Tunnel aus Tiefenschiefer").material());
        assertTrue(order("grab einen Tunnel aus der Kiste").fromChest());
        assertFalse(order("grab einen Tunnel").fromChest());
    }

    @Test
    void stairsUpAreStairsOnlyWithAWayUp() {
        for (String p : List.of("bau mir eine Treppe nach oben", "bau eine Treppe bis zur Oberfläche",
                "grab einen Tunnel nach oben", "Treppe ans Tageslicht", "grab mir eine Treppe hoch",
                "bau einen Aufgang nach draußen", "treppe nach oben")) {
            assertEquals(TunnelOrder.Kind.STAIRS_UP, order(p).kind(), p);
            assertFalse(order(p).follow(), "Treppen laufen bis oben durch: " + p);
        }
        assertEquals(30, order("bau eine Treppe nach oben, 30 Stufen").length());
    }

    @Test
    void ordersWithOtherWishesStayWithTheLlm() {
        for (String p : List.of("bau eine Treppe", "bau eine Treppe zum Dach", "bau eine Wendeltreppe nach oben",
                "bau einen Tunnel unter dem Fluss", "grab ein Loch", "bau einen Gang", "bau einen Tunnel mit Schienen",
                "grab einen Tunnel 40 und 50 Blöcke", "bau einen großen kleinen Tunnel",
                "grab einen Tunnel nach Norden und Süden", "grab einen Tunnel 0 Blöcke", "grab einen Tunnel 10001",
                "bau mir ein Haus", "grab einen Tunnel aus Holz und Stein")) {
            assertTrue(TunnelOrder.parse(p).isEmpty(), p);
        }
    }

    @Test
    void bridgesGoOnLikeTunnelsAndOverTheSea() {
        for (String p : List.of("bau eine Brücke", "bau eine Brücke übers Meer", "bau mir eine endlose Brücke",
                "bau eine Brücke ohne Ende über das Wasser", "lass uns einen Steg bauen")) {
            TunnelOrder o = order(p);
            assertEquals(TunnelOrder.Kind.BRIDGE, o.kind(), p);
            assertTrue(o.follow(), p);
        }
        TunnelOrder wood = order("bau eine Holzbrücke 40 Blöcke schnell");
        assertEquals("oak_planks", wood.material());
        assertEquals(40, wood.length());
        assertTrue(wood.fast());
        assertEquals(20, order("bau eine Brücke über 20 Blöcke").length());
        for (String p : List.of("bau eine Bogenbrücke", "bau eine Hängebrücke", "bau einen Tunnel und eine Brücke",
                "bau eine Brücke nach oben", "bau eine Brücke aus Holz und Stein")) {
            assertTrue(TunnelOrder.parse(p).isEmpty(), p);
        }
    }

    /**
     * Wunsch 2026-11-16, „er wird irgendwann durch ca. die komplette Karte gehen“: lange Längen (Stück für Stück
     * gebaut), auch mit Tausenderpunkt; „durch die ganze Karte“ im Schnellgang heißt so weit wie erlaubt.
     */
    @Test
    void longTunnelsAcrossTheMap() {
        assertEquals(5000, order("grab einen Tunnel 5000 Blöcke schnell").length());
        assertEquals(10_000, order("grab einen Tunnel 10.000 Blöcke schnell").length());
        assertEquals(2000, order("tunnel 2 000 schnell").length());
        assertEquals(999, order("grab einen Tunnel 999").length());
        TunnelOrder across = order("grab einen Tunnel durch die ganze Karte im Schnellverfahren");
        assertEquals(TunnelOrder.MAX_LENGTH, across.length());
        assertTrue(across.fast());
        TunnelOrder walk = order("grab mit mir einen Tunnel quer durch die komplette Welt");
        assertTrue(walk.follow(), "ohne „schnell“: mitgraben, solange der Spieler geht");
        assertEquals(0, walk.length());
        assertFalse(walk.daylight());
    }

    /** „durch den Berg“ und „bis ins Freie“: Ende am Durchbruch – sonst geht der Tunnel dort als Röhre weiter. */
    @Test
    void throughTheMountainEndsAtDaylight() {
        for (String p : List.of("grab einen Tunnel durch den Berg", "grab einen Tunnel bis ins Freie",
                "grab mit mir einen Tunnel durch den Hügel", "bau einen Tunnel auf die andere Seite",
                "grab den Tunnel weiter bis ins Freie")) {
            assertTrue(order(p).daylight(), p);
        }
        for (String p : List.of("grab einen Tunnel", "grab einen Tunnel in den Berg", "grab einen Tunnel 40 schnell",
                "grab einen Tunnel durch die ganze Karte")) {
            assertFalse(order(p).daylight(), p);
        }
    }

    @Test
    void continuingNamesWhatOrLetsTheSiteDecide() {
        TunnelOrder bridge = order("bau die Brücke weiter");
        assertEquals(TunnelOrder.Kind.BRIDGE, bridge.kind());
        assertTrue(bridge.resume());
        assertEquals(TunnelOrder.Kind.TUNNEL, order("grab den Tunnel weiter").kind());
        assertTrue(order("grab den Tunnel weiter").resume());
        assertEquals(TunnelOrder.Kind.STAIRS_UP, order("bau die Treppe nach oben weiter").kind());
        TunnelOrder any = order("bau weiter");
        assertTrue(any.resume());
        assertEquals(null, any.kind(), "was weitergeht, sagt erst der Blick auf die Stelle");
        assertTrue(order("verlängere die Brücke um 30 Blöcke schnell").fast());
        assertFalse(order("grab einen Tunnel").resume());
        assertEquals(null, order("grab einen Tunnel").size(), "ohne Angabe gilt beim Weiterbauen die erkannte Größe");
        assertEquals(TunnelOrder.Size.NORMAL, order("grab einen Tunnel").sizeOrNormal());
    }
}
