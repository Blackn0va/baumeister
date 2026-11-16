package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.TunnelShape.Ground;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /**
     * Blick in Himmelsrichtung d, die Teststelle als Welt (der Gang läuft nach +z = Süden), mit Oberfläche – so, wie
     * TunnelService jede Richtung für TunnelScan.around ansieht.
     */
    private static TunnelScan.Look toward(TunnelTestSite site, Map<Pos, BlockSpec> w, Pos feet, Cardinal d) {
        Orientation o = new Orientation(d);
        return new TunnelScan.Look() {
            @Override
            public BlockSpec at(Pos p) {
                Pos q = o.toWorldOffset(p);
                return site.block(w, new Pos(feet.x() + q.x(), feet.y() + q.y(), feet.z() + q.z()));
            }

            @Override
            public boolean sky(Pos p) {
                Pos q = o.toWorldOffset(p);
                return site.sky(new Pos(feet.x() + q.x(), feet.y() + q.y(), feet.z() + q.z()));
            }
        };
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
        assertEquals("chiseled_stone_bricks", f.rib(), "Gurtbogen, nicht der Sockel");
        assertEquals("polished_andesite", f.trim(), "Sockel (edel, 2026-11-16)");
        assertEquals("smooth_stone", f.runner(), "Mittelstreifen");
        assertEquals("stone_brick_stairs", f.arch());
        assertEquals("lantern", f.light());
        // Letztes Licht bei 14 = 4 vor dem Spieler → Anker 4 - 9 - 2: das nächste käme bei 20.
        assertEquals(-7, f.anchor());
        TunnelScan.Found atFace = TunnelScan.scan(look(site, w, new Pos(1, 0, 19))).orElseThrow();
        assertEquals(new Pos(-1, 0, 0), atFace.end(), "am Rand stehend: Mitte eins links, Ende direkt hier");
        assertEquals(1, atFace.half());
    }

    /**
     * Wer einen Tunnel immer wieder fortsetzt, steht direkt am Ende. Ist der letzte Abschnitt ein Gurtbogen, darf
     * dessen Stein nicht zur Wand des neuen Stücks werden (Probe 2026-11-16: Fortsetzung aus poliertem Tiefenschiefer
     * statt Tiefenschieferziegeln) – Steine und Lichttakt kommen auch aus den Abschnitten hinter dem Spieler.
     */
    @Test
    void standingAtTheEndTheStonesComeFromTheSlicesBehindToo() {
        TunnelTestSite site = new TunnelTestSite();
        Map<Pos, BlockSpec> w = site.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 21,
                192, STONE, site));
        TunnelScan.Found f = TunnelScan.scan(look(site, w, new Pos(0, 0, 19))).orElseThrow();
        assertEquals(new Pos(0, 0, 1), f.end(), "letzter Abschnitt 20 ist ein Gurtbogen");
        assertEquals("stone_bricks", f.wall());
        assertEquals("chiseled_stone_bricks", f.rib());
        // Licht im letzten Abschnitt (20 = Ende): das nächste 6 weiter, also Anker -2 (6 - (-2) = 8 ≡ LIGHT_AT).
        assertEquals(-TunnelShape.LIGHT_AT, f.anchor());
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
     * Wunsch 2026-11-16, „ein Tunnel muss gerade gehen“: „bau weiter“ folgt dem Gang, nicht dem Blick. Quer geschaut
     * gilt das nächste Ende am Fels; ein offenes Ende (Eingang zur Wiese) nur, wenn es kein Ende am Fels gibt. Vorher
     * galt nur die Blickrichtung, quer geschaut kam ein neuer Tunnel quer zum alten heraus.
     */
    @Test
    void aroundFollowsTheTunnelNotTheGaze() {
        TunnelTestSite site = new TunnelTestSite();
        Map<Pos, BlockSpec> w = site.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 20,
                192, STONE, site));
        Pos feet = new Pos(0, 0, 10);
        TunnelScan.Directed across = TunnelScan.around(Cardinal.EAST, d -> toward(site, w, feet, d)).orElseThrow();
        assertEquals(Cardinal.SOUTH, across.dir(), "Ende im Süden 9 Blöcke weit, im Norden 10");
        assertEquals(new Pos(0, 0, 9), across.found().end());
        // Nach Westen geschaut prüft er zuerst Norden (im Uhrzeigersinn) – trotzdem gilt das nähere Ende im Süden.
        assertEquals(Cardinal.SOUTH, TunnelScan.around(Cardinal.WEST, d -> toward(site, w, feet, d)).orElseThrow()
                .dir(), "das nähere Ende, nicht das zuerst gefundene");
        TunnelScan.Directed back = TunnelScan.around(Cardinal.NORTH, d -> toward(site, w, feet, d)).orElseThrow();
        assertEquals(Cardinal.NORTH, back.dir(), "Blick auf ein Ende am Fels: dorthin");
        // Eingang zur Wiese (offen) im Norden, Fels am Ende im Süden: auch mit Blick nach Norden geht es im Süden
        // weiter.
        TunnelTestSite field = new TunnelTestSite();
        field.top = z -> z < 0 ? -12 : 50;
        Map<Pos, BlockSpec> f = field.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 20,
                192, STONE, field));
        TunnelScan.Directed toFace = TunnelScan.around(Cardinal.NORTH, d -> toward(field, f, feet, d)).orElseThrow();
        assertEquals(Cardinal.SOUTH, toFace.dir());
        assertFalse(toFace.found().open());
    }

    /**
     * Ein Gang, der ins Freie mündet (Durchbruch „bis ins Freie“ oder von früher), hat dort ein offenes Ende – „bau
     * weiter“ setzt die Röhre an. Mündet er in eine Höhle, ist das kein Ende (dort stünde die Röhre in fremdem
     * Hohlraum).
     */
    @Test
    void aMouthIntoTheOpenIsAnOpenEndButACaveIsNot() {
        TunnelTestSite cliff = new TunnelTestSite();
        cliff.top = z -> z < 10 ? 50 : -12;
        TunnelShape.Result through = TunnelShape.plan(new TunnelShape.Spec(TunnelOrder.Kind.TUNNEL, 1, 3, 40, 192, 0,
                TunnelShape.NO_ANCHOR, true, STONE, true, TunnelShape.NO_DEPTH), cliff);
        assertEquals(TunnelShape.Stop.THROUGH, through.stop());
        Map<Pos, BlockSpec> w = cliff.built(through);
        TunnelScan.Found f = TunnelScan.scan(toward(cliff, w, new Pos(0, 0, 5), Cardinal.SOUTH)).orElseThrow();
        assertTrue(f.open());
        assertEquals(new Pos(0, 0, 4), f.end(), "letzter Abschnitt 9");
        assertTrue(TunnelScan.scan(look(cliff, w, new Pos(0, 0, 5))).isEmpty(), "ohne Wissen um die Oberfläche nichts");
        TunnelTestSite cave = new TunnelTestSite();
        Map<Pos, BlockSpec> c = cave.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 10, 192,
                STONE, cave));
        cave.box(-4, 4, -1, 5, 10, 16, Ground.OPEN, "cave_air");
        assertTrue(TunnelScan.scan(toward(cave, c, new Pos(0, 0, 5), Cardinal.SOUTH)).isEmpty(), "Höhle");
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

    /**
     * Treppe aus einem Tunnel heraus (Nutzer 2026-11-16, „wie mache ich die Treppe nach oben?“): Vorher hielt sie
     * sofort an der eigenen Laterne bzw. am Bogen des Gangs (gemessen: 0 Stufen, „Gebautes“). Jetzt durchbricht sie
     * dessen Hülle, setzt aber keine eigenen Wände quer in den Gang.
     */
    @Test
    void stairsLeaveATunnelThroughItsOwnShellWithoutWallingItOff() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> 40;
        Map<Pos, BlockSpec> w = site.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 30,
                192, STONE, site));
        Pos feet = new Pos(0, 0, 15);
        TunnelScan.Directed hit = TunnelScan.around(Cardinal.EAST, d -> toward(site, w, feet, d)).orElseThrow();
        TunnelScan.Room room = TunnelScan.room(hit, Cardinal.EAST, toward(site, w, feet, hit.dir()), 8);
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 0, 192, STONE,
                stairsView(site, w, feet, Cardinal.EAST, room));
        assertEquals(TunnelShape.Stop.SURFACE, r.stop(), "bis oben, nicht am eigenen Stein vorbei");
        Map<Pos, BlockSpec> after = new java.util.HashMap<>(w);
        Orientation east = new Orientation(Cardinal.EAST);
        for (CompiledBuild.Action a : r.build().actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                Pos q = east.toWorldOffset(c.pos());
                after.put(new Pos(feet.x() + q.x(), feet.y() + q.y(), feet.z() + q.z()), c.block());
            }
        }
        // Gang links und rechts der Treppe bleibt frei (keine Treppenwände quer im Gang).
        for (int z : new int[] {13, 17}) {
            for (int x = -1; x <= 1; x++) {
                for (int y = 0; y <= 1; y++) {
                    assertTrue(site.block(after, new Pos(x, y, z)).isAir(), "Gang frei bei " + x + " " + y + " " + z);
                }
            }
        }
        assertTrue(site.block(after, new Pos(1, 3, 15)).isAir(), "Decke über der ersten Stufe geöffnet");
        // Ohne Raum (wie vorher): sofort zu Ende.
        TunnelShape.Result before = TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 0, 192,
                STONE, stairsView(site, w, feet, Cardinal.EAST, new TunnelScan.Room(Set.of(), Set.of(), Set.of())));
        assertEquals(TunnelShape.Stop.BUILT, before.stop());
    }

    /** Eine fertige Treppe nach unten wird erkannt – „bau weiter“ setzt sie eine Stufe tiefer fort. */
    @Test
    void stairsDownAreRecognisedToTheirLowestStep() {
        TunnelTestSite site = new TunnelTestSite();
        TunnelShape.Result r = TunnelShape.plan(new TunnelShape.Spec(TunnelOrder.Kind.STAIRS_DOWN, 1, 3, 10, 192, 0,
                TunnelShape.NO_ANCHOR, true, STONE, false, TunnelShape.NO_DEPTH), site);
        assertEquals(10, r.last());
        Map<Pos, BlockSpec> w = site.built(r);
        TunnelScan.Found f = TunnelScan.scan(look(site, w, new Pos(0, -4, 4))).orElseThrow();
        assertEquals(TunnelOrder.Kind.STAIRS_DOWN, f.kind());
        assertEquals(new Pos(0, -6, 6), f.end(), "unterste Stufe 10 bei Füßen -10");
        assertEquals(1, f.half());
    }

    /**
     * Mitten in einem langen Tunnel (Nutzer 2026-11-16 in seinem Tunnel quer über die Karte, „wie mache ich die Treppe
     * nach oben?“): around braucht ein Ende höchstens MAX_WALK Blöcke vor dem Spieler und findet dort keins – dann
     * hielt die Treppe sofort an der Decke. passage erkennt den Gang ohne Ende.
     */
    @Test
    void stairsLeaveTheMiddleOfATunnelLongerThanTheScanReach() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> 40;
        int length = 2 * TunnelScan.MAX_WALK + 40;
        Map<Pos, BlockSpec> w = site.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, length,
                length + 10, STONE, site));
        Pos feet = new Pos(0, 0, length / 2);
        assertTrue(TunnelScan.around(Cardinal.EAST, d -> toward(site, w, feet, d)).isEmpty(),
                "around: kein Ende in Reichweite (so hielt die Treppe vorher an der Decke)");
        TunnelScan.Directed hit = TunnelScan.passage(Cardinal.EAST, d -> toward(site, w, feet, d)).orElseThrow();
        assertEquals(Cardinal.SOUTH, hit.dir(), "längs des Gangs, nicht in Blickrichtung zur Wand");
        assertEquals(1, hit.found().half());
        assertEquals(3, hit.found().height());
        assertEquals("stone_bricks", hit.found().wall());
        TunnelScan.Room room = TunnelScan.room(hit, Cardinal.EAST, toward(site, w, feet, hit.dir()), 8);
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 0, 192, STONE,
                stairsView(site, w, feet, Cardinal.EAST, room));
        assertEquals(TunnelShape.Stop.SURFACE, r.stop());
        TunnelShape.Result before = TunnelShape.plan(TunnelOrder.Kind.STAIRS_UP, TunnelOrder.Size.NORMAL, 0, 192,
                STONE, stairsView(site, w, feet, Cardinal.EAST, new TunnelScan.Room(Set.of(), Set.of(), Set.of())));
        assertEquals(TunnelShape.Stop.BUILT, before.stop(), "ohne Raum: sofort an der Decke");
        assertTrue(TunnelScan.passage(Cardinal.EAST, d -> toward(site, w, new Pos(0, 5, 20), d)).isEmpty(),
                "im Fels kein Gang");
    }

    /**
     * „trotzdem“ durch einen Gang (Wunsch 2026-11-16): Vorher setzte der neue Tunnel seine Wände quer in den alten –
     * der war an der Stelle zu. Mit TunnelScan.crossing und dem Raum des alten Gangs kreuzt er ihn: Dessen Innenraum
     * bleibt frei, der neue geht dahinter weiter.
     */
    @Test
    void aTunnelCrossesAnotherInsteadOfWallingItOff() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> 40;
        Map<Pos, BlockSpec> w = site.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 30,
                192, STONE, site));
        Pos feet = new Pos(-12, 0, 15);
        Orientation east = new Orientation(Cardinal.EAST);
        TunnelScan.Room none = new TunnelScan.Room(Set.of(), Set.of(), Set.of());
        Set<Pos> forced = new HashSet<>();
        TunnelShape.Result plain = TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 24, 192, STONE,
                forceView(site, w, feet, Cardinal.EAST, none, forced));
        assertFalse(laneFree(site, apply(w, plain, feet, east)), "Gegenprobe: so wie vorher – Wand quer im alten Gang");

        int k = forced.stream().mapToInt(Pos::z).min().orElseThrow();
        assertEquals(10, k, "erste Wand des alten Gangs (x = -2)");
        TunnelScan.Crossing x = TunnelScan.crossing(toward(site, w, feet, Cardinal.EAST), TunnelOrder.Kind.TUNNEL, k,
                local -> TunnelScan.passage(Cardinal.EAST, d -> toward(site, w, worldOf(feet, east, local), d)))
                .orElseThrow();
        assertEquals(Cardinal.SOUTH, x.tunnel().dir());
        assertEquals(new Pos(0, 0, 11), x.feet(), "Füße im alten Gang, lokal zum neuen");
        TunnelScan.Room room = TunnelScan.room(x.tunnel(), Cardinal.EAST,
                toward(site, w, worldOf(feet, east, x.feet()), x.tunnel().dir()), 8).shifted(x.feet());
        TunnelShape.Result r = TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 24, 192, STONE,
                forceView(site, w, feet, Cardinal.EAST, room, new HashSet<>()));
        assertEquals(TunnelShape.Stop.LENGTH, r.stop());
        assertEquals(23, r.last(), "24 Blöcke, durch den alten Gang hindurch");
        Map<Pos, BlockSpec> after = apply(w, r, feet, east);
        assertTrue(laneFree(site, after), "alter Gang frei – keine Wand quer");
        for (int y = 0; y <= 1; y++) {
            assertTrue(site.block(after, new Pos(6, y, 15)).isAir(), "neuer Gang hinter dem alten bei y " + y);
        }
        assertFalse(site.block(after, new Pos(0, 3, 15)).isAir(), "Decke über der Kreuzung bleibt zu");
    }

    /** Treppe nach unten, die an einen Gang stößt: crossing findet ihn – TunnelService baut nicht quer hindurch. */
    @Test
    void stairsDownThatHitATunnelFindItBelow() {
        TunnelTestSite site = new TunnelTestSite();
        site.top = z -> 40;
        Map<Pos, BlockSpec> w = site.built(TunnelShape.plan(TunnelOrder.Kind.TUNNEL, TunnelOrder.Size.NORMAL, 30,
                192, STONE, site));
        Pos feet = new Pos(-12, 9, 15);
        Orientation east = new Orientation(Cardinal.EAST);
        TunnelShape.Result r = TunnelShape.plan(new TunnelShape.Spec(TunnelOrder.Kind.STAIRS_DOWN, 1, 3, 0, 192, 0,
                TunnelShape.NO_ANCHOR, true, STONE, false, TunnelShape.NO_DEPTH),
                stairsView(site, w, feet, Cardinal.EAST, new TunnelScan.Room(Set.of(), Set.of(), Set.of())));
        assertEquals(TunnelShape.Stop.BUILT, r.stop());
        TunnelScan.Crossing x = TunnelScan.crossing(toward(site, w, feet, Cardinal.EAST), TunnelOrder.Kind.STAIRS_DOWN,
                r.stopAt().z(), local -> TunnelScan.passage(Cardinal.EAST,
                        d -> toward(site, w, worldOf(feet, east, local), d))).orElseThrow();
        assertEquals(new Pos(-1, 0, 15), worldOf(feet, east, x.feet()), "Füße auf dem Boden des Gangs");
    }

    /** Der alte Gang (x -1..1) neben dem neuen, der bei z 14..16 kreuzt: auf Fuß- und Kopfhöhe frei? */
    private static boolean laneFree(TunnelTestSite site, Map<Pos, BlockSpec> w) {
        for (int z : new int[] {13, 17}) {
            for (int x = -1; x <= 1; x++) {
                for (int y = 0; y <= 1; y++) {
                    if (!site.block(w, new Pos(x, y, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Pos worldOf(Pos feet, Orientation o, Pos local) {
        Pos q = o.toWorldOffset(local);
        return new Pos(feet.x() + q.x(), feet.y() + q.y(), feet.z() + q.z());
    }

    /** Welt w mit dem Bau r darüber (r lokal zu feet in Richtung o). */
    private static Map<Pos, BlockSpec> apply(Map<Pos, BlockSpec> w, TunnelShape.Result r, Pos feet, Orientation o) {
        Map<Pos, BlockSpec> out = new HashMap<>(w);
        for (CompiledBuild.Action a : r.build().actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                out.put(worldOf(feet, o, c.pos()), c.block());
            }
        }
        return out;
    }

    /** Wie stairsView, aber mit „trotzdem“: Gebautes gilt als Fels und kommt in forced (wie TunnelSite). */
    private static TunnelShape.Site forceView(TunnelTestSite site, Map<Pos, BlockSpec> w, Pos feet, Cardinal d,
            TunnelScan.Room room, Set<Pos> forced) {
        TunnelShape.Site plain = stairsView(site, w, feet, d, room);
        return new TunnelShape.Site() {
            @Override
            public Ground at(Pos p) {
                Ground g = plain.at(p);
                if (g == Ground.BUILT) {
                    forced.add(p);
                    return Ground.SOLID;
                }
                return g;
            }

            @Override
            public boolean sky(Pos p) {
                return plain.sky(p);
            }

            @Override
            public String id(Pos p) {
                return plain.id(p);
            }

            @Override
            public boolean room(Pos p) {
                return plain.room(p);
            }
        };
    }

    /** Sicht für eine Treppe in Blickrichtung d: eigene Steine des Gangs abbaubar, sein Innenraum bleibt frei. */
    private static TunnelShape.Site stairsView(TunnelTestSite site, Map<Pos, BlockSpec> w, Pos feet, Cardinal d,
            TunnelScan.Room room) {
        Orientation o = new Orientation(d);
        return new TunnelShape.Site() {
            private Pos world(Pos p) {
                Pos q = o.toWorldOffset(p);
                return new Pos(feet.x() + q.x(), feet.y() + q.y(), feet.z() + q.z());
            }

            @Override
            public Ground at(Pos p) {
                Pos q = world(p);
                BlockSpec b = site.block(w, q);
                if (room.own().contains(p) && room.ids().contains(b.id())) {
                    return Ground.SOLID;
                }
                if (w.containsKey(q)) {
                    return b.isAir() ? Ground.OPEN : Ground.BUILT;
                }
                return site.at(q);
            }

            @Override
            public boolean sky(Pos p) {
                return site.sky(world(p));
            }

            @Override
            public String id(Pos p) {
                return site.block(w, world(p)).id();
            }

            @Override
            public boolean room(Pos p) {
                return room.inside().contains(p);
            }
        };
    }
}
