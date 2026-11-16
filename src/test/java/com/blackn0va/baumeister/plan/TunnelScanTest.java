package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.TunnelShape.Ground;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Erkennen und Weiterbauen (Wunsch 2026-11-16, „er muss Tunnel und Brücken erkennen und dort weiter bauen“): Was
 * TunnelShape baut, muss TunnelScan wiedererkennen – mit Maßen, Steinen, Licht und Ende. Dazu ein selbst gegrabener
 * Gang, ein Keller und ein Blick quer zum Gang.
 */
class TunnelScanTest {

    private static final TunnelStyle STONE = TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 30, null,
            TunnelOrder.Size.NORMAL);
    private static final TunnelStyle BRIDGE = TunnelStyle.forPlace(TunnelStyle.Realm.OVERWORLD, 64, null,
            TunnelOrder.Kind.BRIDGE, TunnelOrder.Size.NORMAL);

    /** Was der Spieler sieht: Füße bei feet, Blick nach +z (wie der Bau). */
    private static TunnelScan.Look look(TunnelTestSite site, Map<Pos, BlockSpec> w, Pos feet) {
        return p -> site.block(w, new Pos(feet.x() + p.x(), feet.y() + p.y(), feet.z() + p.z()));
    }

    /** Derselbe Spieler, aber nach +x gedreht: lokal z läuft dann quer zum Gang. */
    private static TunnelScan.Look across(TunnelTestSite site, Map<Pos, BlockSpec> w, Pos feet) {
        return p -> site.block(w, new Pos(feet.x() + p.z(), feet.y() + p.y(), feet.z() - p.x()));
    }

    @Test
    void aTunnelOfOursIsRecognisedWithItsStonesLightsAndEnd() {
        TunnelTestSite site = new TunnelTestSite();
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 20, 192, STONE,
                site);
        Map<Pos, BlockSpec> w = site.built(r);
        TunnelScan.Found f = TunnelScan.scan(look(site, w, new Pos(0, 0, 10))).orElseThrow();
        assertEquals(TunnelOrder.Kind.TUNNEL, f.kind());
        assertEquals(1, f.half());
        assertEquals(3, f.height());
        assertEquals(new Pos(0, 0, 9), f.end(), "Ende 9 Abschnitte vor dem Spieler (Abschnitt 19)");
        assertEquals("stone_bricks", f.wall());
        assertEquals("polished_andesite", f.floor());
        assertEquals("chiseled_stone_bricks", f.rib());
        assertEquals("stone_brick_stairs", f.arch());
        assertEquals("lantern", f.light());
        // Letztes Licht bei 14 = 4 vor dem Spieler → Anker 4 - 9 - 2: das nächste käme bei 20.
        assertEquals(-7, f.anchor());
        TunnelScan.Found atFace = TunnelScan.scan(look(site, w, new Pos(1, 0, 19))).orElseThrow();
        assertEquals(new Pos(-1, 0, 0), atFace.end(), "am Rand stehend: Mitte eins links, Ende direkt hier");
        assertEquals(1, atFace.half());
    }

    @Test
    void aSelfDugCorridorIsASmallTunnelOfRawStone() {
        TunnelTestSite site = new TunnelTestSite();
        site.box(0, 0, 0, 1, -6, 0, Ground.OPEN, "air");
        TunnelScan.Found f = TunnelScan.scan(look(site, Map.of(), new Pos(0, 0, 0))).orElseThrow();
        assertEquals(TunnelOrder.Kind.TUNNEL, f.kind());
        assertEquals(0, f.half());
        assertEquals(2, f.height());
        assertEquals(new Pos(0, 0, 0), f.end());
        assertTrue(TunnelStyle.isRaw(f.wall()), f.wall());
        assertEquals(TunnelShape.NO_ANCHOR, f.anchor());
    }

    @Test
    void aCellarAnOpenFieldOrLookingAcrossIsNothingToContinue() {
        TunnelTestSite cellar = new TunnelTestSite();
        cellar.box(-3, 3, 0, 2, -3, 3, Ground.OPEN, "air");
        assertTrue(TunnelScan.scan(look(cellar, Map.of(), new Pos(0, 0, 0))).isEmpty(), "Keller 7 × 7");
        TunnelTestSite field = new TunnelTestSite();
        field.top = z -> -1;
        assertTrue(TunnelScan.scan(look(field, Map.of(), new Pos(0, 0, 0))).isEmpty(), "Wiese");
        TunnelTestSite site = new TunnelTestSite();
        Map<Pos, BlockSpec> w = site.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 20,
                192, STONE, site));
        assertTrue(TunnelScan.scan(across(site, w, new Pos(0, 0, 10))).isEmpty(), "quer zum Gang");
    }

    @Test
    void stairsAreFollowedToTheirLastStep() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> 25;
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 0, 192, STONE,
                site);
        assertEquals(26, r.last());
        Map<Pos, BlockSpec> w = site.built(r);
        TunnelScan.Found f = TunnelScan.scan(look(site, w, new Pos(0, 5, 5))).orElseThrow();
        assertEquals(TunnelOrder.Kind.STAIRS_UP, f.kind());
        assertEquals(1, f.half());
        assertEquals(3, f.height());
        assertEquals(new Pos(0, 21, 21), f.end(), "auf der letzten Stufe (26)");
        assertEquals("polished_andesite_stairs", f.step());
        assertEquals("stone_bricks", f.wall());
    }

    @Test
    void aBridgeIsRecognisedOnItsDeckWithRailingAndLights() {
        TunnelTestSite site = new TunnelTestSite().sea(-10, 10, 0, 60, -12, -2);
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.BRIDGE, TunnelOrder.Size.NORMAL, 30, 192, BRIDGE,
                site);
        Map<Pos, BlockSpec> w = site.built(r);
        TunnelScan.Found f = TunnelScan.scan(look(site, w, new Pos(0, 0, 10))).orElseThrow();
        assertEquals(TunnelOrder.Kind.BRIDGE, f.kind());
        assertEquals(1, f.half());
        assertEquals(new Pos(0, 0, 19), f.end());
        assertEquals("stone_bricks", f.floor());
        assertEquals("stone_brick_wall", f.railing());
        assertEquals("lantern", f.light());
        // Letztes Licht bei 26 = 16 vor dem Spieler → Anker 16 - 19 - 2 = -5: das nächste bei 32 (Ende 29 + 3).
        assertEquals(-5, f.anchor());
    }

    /**
     * Paper-Probe 2026-11-16: Liegt das Deck auf Höhe der Wasseroberfläche, galt das Wasser hinter dem Ende als Deck –
     * die Erkennung lief bis zum Beckenrand (z 7065 statt 7037), und „bau die Brücke weiter“ setzte 28 Blöcke weiter
     * hinten neu an.
     */
    @Test
    void aBridgeAtWaterLevelEndsWhereTheDeckEnds() {
        TunnelTestSite site = new TunnelTestSite().sea(-10, 10, 0, 80, -12, -1);
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.BRIDGE, TunnelOrder.Size.NORMAL, 30, 192, BRIDGE,
                site);
        assertEquals(29, r.last());
        Map<Pos, BlockSpec> w = site.built(r);
        TunnelScan.Found f = TunnelScan.scan(look(site, w, new Pos(0, 0, 10))).orElseThrow();
        assertEquals(new Pos(0, 0, 19), f.end(), "Ende beim letzten Deck (29), nicht am Rand des Wassers");
    }
}
