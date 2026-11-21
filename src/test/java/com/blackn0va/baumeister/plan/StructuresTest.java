package com.blackn0va.baumeister.plan;

import static com.blackn0va.baumeister.plan.FormChecks.assertAttached;
import static com.blackn0va.baumeister.plan.FormChecks.assertHolds;
import static com.blackn0va.baumeister.plan.FormChecks.assertNoCell;
import static com.blackn0va.baumeister.plan.FormChecks.compile;
import static com.blackn0va.baumeister.plan.FormChecks.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * Compiler-Tests der Bauformen (Structures) je Form in allen vier Richtungen: Halt (nichts schwebt), Angehängtes
 * hat Halt (lokal und in alle vier Weltrichtungen gedreht), Tor/Wehrgang/Deck begehbar, nichts außerhalb der
 * angegebenen Grenzen.
 */
class StructuresTest {

    // ---------------------------------------------------------------- Burg

    /** Grundriss 25×21 (nicht quadratisch, damit eine falsche Drehung Breite und Tiefe vertauscht). */
    private static final int CW = 25;
    private static final int CD = 21;
    private static final int CH = 6;

    private static String castle(String facing, String style) {
        return "{\"op\":\"castle\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[" + (CW - 1) + ",0," + (CD - 1)
                + "],\"height\":" + CH + ",\"facing\":\"" + facing + "\",\"style\":\"" + style + "\"}";
    }

    /** Zellen direkt vor der Torseite (außerhalb des Rechtecks), auf Laufhöhe. */
    private static List<Pos> outsideRow(String side, int w, int d, int y) {
        List<Pos> out = new ArrayList<>();
        switch (side) {
            case "front" -> {
                for (int x = 0; x < w; x++) {
                    out.add(new Pos(x, y, -1));
                }
            }
            case "back" -> {
                for (int x = 0; x < w; x++) {
                    out.add(new Pos(x, y, d));
                }
            }
            case "left" -> {
                for (int z = 0; z < d; z++) {
                    out.add(new Pos(-1, y, z));
                }
            }
            default -> {
                for (int z = 0; z < d; z++) {
                    out.add(new Pos(w, y, z));
                }
            }
        }
        return out;
    }

    private static String opposite(String side) {
        return switch (side) {
            case "front" -> "back";
            case "back" -> "front";
            case "left" -> "right";
            default -> "left";
        };
    }

    /**
     * Ein Hofpunkt, den man vom Tor aus erreichen muss. Vorher die Hofmitte – dort steht seit 2026-11-20 der Brunnen
     * (Wasser zählt nicht als Lauffläche). Die Ecke des Pflasterrings ist in jeder Drehung frei.
     */
    private static final Pos YARD = new Pos(CW / 2 + 2, 1, CD / 2 + 2);

    /** Laufbereich: das Rechteck plus die eine Zeile davor – ohne Umweg außen um die Burg herum. */
    private static Predicate<Pos> rectPlus(int w, int d, List<Pos> row) {
        Set<Pos> extra = Set.copyOf(row);
        return p -> p.x() >= 0 && p.x() < w && p.z() >= 0 && p.z() < d || extra.contains(new Pos(p.x(), row
                .getFirst().y(), p.z()));
    }

    @Test
    void castleHoldsAndStaysInsideItsRectangleInAllFourDirections() throws Exception {
        for (String style : new String[] {"round", "square"}) {
            for (String side : FormChecks.SIDES) {
                String label = "castle " + side + " " + style;
                Map<Pos, BlockSpec> s = state(compile(castle(side, style)));
                assertHolds(s, label);
                assertAttached(s, label);
                assertNoCell(s, p -> p.x() < 0 || p.x() >= CW || p.z() < 0 || p.z() >= CD || p.y() < 0
                        || p.y() > CH + 5, label);
                // Zinnen auf Wehrgang (CH+1), Turm- und Torstubenkranz (CH+5) – darüber nichts.
                assertEquals(CH + 5, FormChecks.maxY(s), label);
            }
        }
    }

    @Test
    void castleGateLeadsIntoTheCourtyardAndTheOtherSidesAreClosed() throws Exception {
        for (String style : new String[] {"round", "square"}) {
            for (String side : FormChecks.SIDES) {
                String label = "castle " + side + " " + style;
                Map<Pos, BlockSpec> s = state(compile(castle(side, style)));
                Pos yard = YARD;
                List<Pos> gateRow = outsideRow(side, CW, CD, 1);
                Set<Pos> in = new FormChecks.Walker(s, 0, false, rectPlus(CW, CD, gateRow)).reach(gateRow);
                assertTrue(in.contains(yard), label + ": Hof vom Tor aus nicht erreichbar");
                List<Pos> backRow = outsideRow(opposite(side), CW, CD, 1);
                Set<Pos> back = new FormChecks.Walker(s, 0, false, rectPlus(CW, CD, backRow)).reach(backRow);
                assertFalse(back.contains(yard), label + ": Mauer gegenüber dem Tor hat ein Loch");
            }
        }
    }

    @Test
    void castleWalkwayTowersAndPlatformsAreReachableFromTheCourtyard() throws Exception {
        for (String style : new String[] {"round", "square"}) {
            for (String side : FormChecks.SIDES) {
                String label = "castle " + side + " " + style;
                Map<Pos, BlockSpec> s = state(compile(castle(side, style)));
                Set<Pos> reach = new FormChecks.Walker(s, 0, false,
                        p -> p.x() >= 0 && p.x() < CW && p.z() >= 0 && p.z() < CD).reach(List.of(YARD));
                // Wehrgang in der Mitte jeder Seite (Innenreihe 1, Laufhöhe CH+1) – auf der Torseite die Torstube.
                for (Pos walk : List.of(new Pos(CW / 2, CH + 1, 1), new Pos(CW / 2, CH + 1, CD - 2),
                        new Pos(1, CH + 1, CD / 2), new Pos(CW - 2, CH + 1, CD / 2))) {
                    assertTrue(reach.contains(walk), label + ": Wehrgang bei " + walk + " nicht erreichbar");
                }
                // Jede Turmplattform (Laufhöhe CH+5) in jeder Ecke
                int[][] quadrants = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
                for (int[] q : quadrants) {
                    boolean found = reach.stream().anyMatch(p -> p.y() == CH + 5
                            && (p.x() < CW / 2) == (q[0] == 0) && (p.z() < CD / 2) == (q[1] == 0)
                            && (p.x() < 7 || p.x() > CW - 8) && (p.z() < 7 || p.z() > CD - 8));
                    assertTrue(found, label + ": Turmplattform in Ecke " + q[0] + "/" + q[1] + " nicht erreichbar");
                }
            }
        }
    }

    @Test
    void castleHasBattlementsOnEveryWallAndTheGateIsAPassageNotAWall() throws Exception {
        Map<Pos, BlockSpec> s = state(compile(castle("front", "round")));
        // Vorderwand: Zinnen auf der Außenreihe z=0 bei geraden x außerhalb von Türmen und Torhaus
        assertEquals("stone_bricks", s.get(new Pos(6, CH + 1, 0)).id());
        assertTrue(s.get(new Pos(7, CH + 1, 0)) == null, "Lücke zwischen den Zinnen");
        // Rückwand, linke und rechte Wand
        assertEquals("stone_bricks", s.get(new Pos(6, CH + 1, CD - 1)).id());
        assertEquals("stone_bricks", s.get(new Pos(0, CH + 1, 10)).id());
        assertEquals("stone_bricks", s.get(new Pos(CW - 1, CH + 1, 10)).id());
        // Durchfahrt 3 breit, 4 hoch, Sturz darüber
        for (int x = 11; x <= 13; x++) {
            for (int y = 1; y <= 4; y++) {
                assertTrue(s.get(new Pos(x, y, 0)).isAir(), "Durchfahrt bei " + x + "/" + y);
            }
            assertEquals("stone_bricks", s.get(new Pos(x, 5, 0)).id());
        }
    }

    @Test
    void castleRejectsTooSmallFootprintAndClampsTowerRadius() throws Exception {
        PlanException e = org.junit.jupiter.api.Assertions.assertThrows(PlanException.class, () -> compile(
                "{\"op\":\"castle\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[15,0,20]}"));
        assertTrue(e.getMessage().contains("17×17"), e.getMessage());
        // Radius 6 passt bei 17×17 nicht: gebaut mit 2, Warnung im Chat, nichts außerhalb.
        CompiledBuild b = compile("{\"op\":\"castle\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[16,0,16],"
                + "\"radius\":6}");
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("Turmradius")), () -> "" + b.warnings());
        Map<Pos, BlockSpec> s = state(b);
        assertHolds(s, "castle 17");
        assertNoCell(s, p -> p.x() < 0 || p.x() > 16 || p.z() < 0 || p.z() > 16, "castle 17");
    }

    // ---------------------------------------------------------------- Burghof

    /** Burg w×d mit Mauerhöhe h und Mauerdicke t. */
    private static String castle(int w, int d, int h, int t, String facing, String style) {
        return "{\"op\":\"castle\",\"block\":\"stone_bricks\",\"from\":[0,0,0],\"to\":[" + (w - 1) + ",0," + (d - 1)
                + "],\"height\":" + h + ",\"width\":" + t + ",\"facing\":\"" + facing + "\",\"style\":\"" + style
                + "\"}";
    }

    /**
     * Grundrisse mit Hofseite ab 9 ({w, d, h, t}): Standard, die Schwelle bei Mauerdicke 4 und 5 (dort steht der
     * Brunnen direkt hinter der Torhausleiter) und groß mit Turmradius 3. Alle Seiten ungerade: Tormitte und Hofmitte
     * sind dann in jeder Drehung dieselbe Zelle.
     */
    private static final int[][] YARDS = {{CW, CD, CH, 3}, {17, 17, 4, 4}, {19, 19, 5, 5}, {37, 29, 8, 3}};

    /**
     * Hofkoordinaten einer gedrehten Burg: k = Abstand von der Torseite nach innen, l = entlang der Torseite. Nur für
     * ungerade Seiten (siehe YARDS).
     */
    private record Yard(String side, int w, int d, int t) {

        private boolean alongX() {
            return side.equals("front") || side.equals("back");
        }

        int kc() {
            return ((alongX() ? d : w) - 1) / 2;
        }

        int lc() {
            return ((alongX() ? w : d) - 1) / 2;
        }

        int k(Pos p) {
            return switch (side) {
                case "front" -> p.z();
                case "back" -> d - 1 - p.z();
                case "left" -> p.x();
                default -> w - 1 - p.x();
            };
        }

        int l(Pos p) {
            return alongX() ? p.x() : p.z();
        }

        Pos at(int k, int y, int l) {
            return switch (side) {
                case "front" -> new Pos(l, y, k);
                case "back" -> new Pos(l, y, d - 1 - k);
                case "left" -> new Pos(k, y, l);
                default -> new Pos(w - 1 - k, y, l);
            };
        }

        /**
         * Hofzellen: Boden des Wegs (3 breit ab der Hofseite des Torhauses, Tiefe t + 2) und des Pflasterrings 5×5,
         * darüber der Brunnen 3×3 bis zum Dach – nur bei Hofseite ab 9, darunter gibt es keinen Hof. Die Burg vor dem
         * Hof setzte hier keine einzige Zelle (gezählt beim Erzeugen von CASTLE_LAYOUT: 0 in allen 32 Burgen mit
         * Hof; bei 17×17 und Mauer 5 lägen 20 Zellen von Torhaus und Leiter darin).
         */
        boolean zone(Pos p) {
            if (Math.min(w, d) - 2 * t < 9) {
                return false;
            }
            int dk = k(p) - kc();
            int dl = Math.abs(l(p) - lc());
            if (p.y() == 0) {
                return dl <= 1 && k(p) >= t + 2 && dk <= 0 || dl <= 2 && Math.abs(dk) <= 2;
            }
            return p.y() >= 1 && p.y() <= 4 && dl <= 1 && Math.abs(dk) <= 1;
        }
    }

    private static String id(Map<Pos, BlockSpec> s, Pos p) {
        BlockSpec b = s.get(p);
        return b == null ? "nichts" : b.id();
    }

    /**
     * Burg: der Hof hat einen Weg vom Tor zum Brunnen in der Mitte (Gedächtnis „Offen“: „Burg-Hof leer“). In allen
     * vier Richtungen und beiden Turmformen führt der Weg zum eigenen Tor: Läge er nach einer falschen Drehung an
     * einer anderen Seite, stünde Pflaster außerhalb von Weg und Ring.
     */
    @Test
    void courtyardHasAPavedPathFromTheGateToAWellInTheMiddle() throws Exception {
        for (int[] c : YARDS) {
            for (String style : new String[] {"round", "square"}) {
                for (String side : FormChecks.SIDES) {
                    String label = "castle " + c[0] + "x" + c[1] + " t" + c[3] + " " + side + " " + style;
                    Map<Pos, BlockSpec> s = state(compile(castle(c[0], c[1], c[2], c[3], side, style)));
                    assertHolds(s, label);
                    assertAttached(s, label);
                    Yard yard = new Yard(side, c[0], c[1], c[3]);
                    int kc = yard.kc();
                    int lc = yard.lc();
                    // Brunnen: Wasser in der Mitte auf Pflaster, Rand rundum, oben offen, Dach mit Hängelaterne
                    assertEquals("water", id(s, yard.at(kc, 1, lc)), label + ": Wasser in der Mitte");
                    assertEquals("cobblestone", id(s, yard.at(kc, 0, lc)), label + ": Boden unter dem Wasser");
                    for (int dk = -1; dk <= 1; dk++) {
                        for (int dl = -1; dl <= 1; dl++) {
                            if (dk != 0 || dl != 0) {
                                assertEquals("stone_bricks", id(s, yard.at(kc + dk, 1, lc + dl)),
                                        label + ": Brunnenrand " + dk + "/" + dl);
                            }
                            assertEquals("spruce_slab", id(s, yard.at(kc + dk, 4, lc + dl)), label + ": Dach");
                        }
                    }
                    assertEquals("nichts", id(s, yard.at(kc, 2, lc)), label + ": Wasser oben offen");
                    BlockSpec lamp = s.get(yard.at(kc, 3, lc));
                    assertTrue(lamp != null && lamp.id().equals("lantern") && "true".equals(lamp.props().get(
                            "hanging")), label + ": Laterne unter dem Dach " + lamp);
                    // Weg 3 breit wie die Durchfahrt, von der Hofseite des Torhauses bis zur Mitte, Ring 5×5
                    for (int k = c[3] + 2; k <= kc + 2; k++) {
                        int ring = k >= kc - 2 ? 2 : 1;
                        for (int dl = -ring; dl <= ring; dl++) {
                            assertEquals("cobblestone", id(s, yard.at(k, 0, lc + dl)), label + ": Pflaster bei Tiefe "
                                    + k + ", seitlich " + dl);
                        }
                    }
                    for (Map.Entry<Pos, BlockSpec> e : s.entrySet()) {
                        if (e.getValue().id().equals("cobblestone")) {
                            assertTrue(e.getKey().y() == 0 && yard.zone(e.getKey()), label + ": Pflaster abseits bei "
                                    + e.getKey());
                        }
                    }
                    // Zu Fuß: von vor dem Tor durch die Durchfahrt bis an den Brunnen
                    List<Pos> gateRow = outsideRow(side, c[0], c[1], 1);
                    Set<Pos> in = new FormChecks.Walker(s, 0, false, rectPlus(c[0], c[1], gateRow)).reach(gateRow);
                    assertTrue(in.contains(yard.at(kc - 2, 1, lc)), label + ": vom Tor nicht zum Brunnen");
                }
            }
        }
    }

    /**
     * Hof unter 9 bleibt leer: Bei Mauerdicke 5 und Seite 17 oder 18 stünde der Brunnen in der Torhausleiter. Beide
     * Seiten zählen – 18×30 hat in der Tiefe Platz, in der Breite nicht.
     */
    @Test
    void courtyardsBelowNineStayEmpty() throws Exception {
        for (int[] c : new int[][] {{17, 17}, {18, 18}, {18, 30}, {30, 18}}) {
            for (String style : new String[] {"round", "square"}) {
                for (String side : FormChecks.SIDES) {
                    String label = "castle " + c[0] + "x" + c[1] + " t5 " + side + " " + style;
                    Map<Pos, BlockSpec> s = state(compile(castle(c[0], c[1], 6, 5, side, style)));
                    assertTrue(s.values().stream().noneMatch(b -> Set.of("water", "cobblestone", "spruce_slab",
                            "spruce_fence").contains(b.id())), label + ": Hof nicht leer");
                }
            }
        }
    }

    /**
     * Fingerabdruck der Burgen ohne Hof, erzeugt 2026-11-20 mit dem Stand 2fe5a8a (vor dem Hof): je Grundriss
     * {w, d, h, t} und Turmform über alle vier Richtungen die Zellenzahl und die ersten 16 Hexzeichen von SHA-256
     * über die sortierten Zeilen „richtung x,y,z=block“ ohne die Hofzellen (Yard.zone). Vorher prüften die Tests
     * nur Stichproben (Zinnen, Durchfahrt, Wehrgang) – ein Hof, der eine Turmzelle oder die Torleiter überschreibt,
     * bliebe grün. Wer hier einen Wert ändert, hat Mauer, Turm oder Tor verändert.
     */
    private static final Map<String, String> CASTLE_LAYOUT = Map.ofEntries(
            Map.entry("25x21 h6 t3 round", "9484 fce8733e86640d36"),
            Map.entry("25x21 h6 t3 square", "9996 efc0925cd1b6cb50"),
            Map.entry("17x17 h4 t4 round", "6568 fff896e75cec2cb4"),
            Map.entry("17x17 h4 t4 square", "7016 4c086dafdcb32731"),
            Map.entry("19x19 h5 t5 round", "9300 dfc2e388ebed0bb1"),
            Map.entry("19x19 h5 t5 square", "9684 f3e617cd205a05eb"),
            Map.entry("37x29 h8 t3 round", "18260 7eeb21957d60c5c2"),
            Map.entry("37x29 h8 t3 square", "19860 a3bd355cdd8e9a46"),
            // Ohne Hof (Hofseite 7): alle Zellen, die Burg bleibt ganz wie vorher.
            Map.entry("17x17 h6 t5 round", "9332 933ca4d0191b84c8"),
            Map.entry("17x17 h6 t5 square", "9732 8f058a81dcc6dc87"));

    @Test
    void wallsTowersAndGateStayCellForCellAsBeforeTheCourtyard() throws Exception {
        List<int[]> cases = new ArrayList<>(List.of(YARDS));
        cases.add(new int[] {17, 17, 6, 5});
        Map<String, String> actual = new TreeMap<>();
        for (int[] c : cases) {
            for (String style : new String[] {"round", "square"}) {
                List<String> lines = new ArrayList<>();
                for (String side : FormChecks.SIDES) {
                    Yard yard = new Yard(side, c[0], c[1], c[3]);
                    state(compile(castle(c[0], c[1], c[2], c[3], side, style))).forEach((p, b) -> {
                        if (!yard.zone(p)) {
                            lines.add(side + " " + p.x() + "," + p.y() + "," + p.z() + "=" + b.asString());
                        }
                    });
                }
                lines.sort(null);
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
                actual.put(c[0] + "x" + c[1] + " h" + c[2] + " t" + c[3] + " " + style,
                        lines.size() + " " + HexFormat.of().formatHex(hash).substring(0, 16));
            }
        }
        assertEquals(new TreeMap<>(CASTLE_LAYOUT), actual);
    }

    /**
     * Alles, was Hof und Rampe selbst wählen, gibt es in 1.21.11 UND 26.2 (wie TunnelStyleTest): Ein Block, den
     * eine Fassung nicht kennt, würde dort als Ersatzblock gesetzt.
     */
    @Test
    void courtyardAndRampBlocksExistInBothServerVersions() throws Exception {
        Set<String> old = TunnelStyleTest.ids("1.21.11");
        Set<String> now = TunnelStyleTest.ids("26.2");
        Set<String> used = new TreeSet<>();
        for (String side : FormChecks.SIDES) {
            for (String style : new String[] {"round", "square"}) {
                state(compile(castle(CW, CD, CH, 3, side, style))).values().forEach(b -> used.add(b.id()));
            }
        }
        for (String[] kind : new String[][] {{"arch", "stone_bricks"}, {"beam", "stone_bricks"}, {"", "spruce_planks"},
                {"", "oak_planks"}}) {
            state(compile(bridge(new int[] {0, 0, 0}, new int[] {0, 0, 21}, 4, kind[0], kind[1], 3))).values()
                    .forEach(b -> used.add(b.id()));
        }
        assertTrue(used.containsAll(Set.of("water", "cobblestone", "spruce_slab", "spruce_fence", "stone_brick_stairs",
                "spruce_stairs", "oak_stairs")), "Hof und Rampen gebaut: " + used);
        for (String id : used) {
            assertTrue(old.contains(id) && now.contains(id), id + " fehlt in 1.21.11 oder 26.2");
        }
    }

    // ---------------------------------------------------------------- Mauer

    private static String wall(int[] a, int[] b, String facing, int width) {
        return "{\"op\":\"wall\",\"block\":\"stone_bricks\",\"from\":[" + a[0] + ",0," + a[2] + "],\"to\":[" + b[0]
                + ",0," + b[2] + "],\"height\":5" + (facing.isEmpty() ? "" : ",\"facing\":\"" + facing + "\"")
                + ",\"width\":" + width + "}";
    }

    @Test
    void wallHoldsAndItsWalkwayIsReachableInAllFourDirections() throws Exception {
        int[][][] lines = {
                {{0, 0, 0}, {20, 0, 0}}, {{20, 0, 0}, {0, 0, 0}}, {{0, 0, 0}, {0, 0, 20}}, {{0, 0, 20}, {0, 0, 0}},
                {{0, 0, 0}, {20, 0, 5}}, {{0, 0, 0}, {-6, 0, -19}}};
        for (int[][] line : lines) {
            for (int width : new int[] {1, 3}) {
                String label = "wall " + java.util.Arrays.deepToString(line) + " width " + width;
                Map<Pos, BlockSpec> s = state(compile(wall(line[0], line[1], "", width)));
                assertHolds(s, label);
                assertAttached(s, label);
                // Grenzen: zwischen den Endpunkten plus Dicke und Leiter, Höhe Fuß bis Zinne
                int minX = Math.min(line[0][0], line[1][0]) - width - 1;
                int maxX = Math.max(line[0][0], line[1][0]) + width + 1;
                int minZ = Math.min(line[0][2], line[1][2]) - width - 1;
                int maxZ = Math.max(line[0][2], line[1][2]) + width + 1;
                assertNoCell(s, p -> p.x() < minX || p.x() > maxX || p.z() < minZ || p.z() > maxZ || p.y() < 0
                        || p.y() > 6, label);
                // Leiter unten → Wehrgang am anderen Ende (Laufhöhe 6)
                Pos foot = s.entrySet().stream().filter(e -> e.getValue().id().equals("ladder") && e.getKey().y() == 1)
                        .map(Map.Entry::getKey).findFirst().orElseThrow(() -> new AssertionError(label + ": keine Leiter"));
                // Laufbereich begrenzt: Gelände (y ≤ 0) reicht sonst unendlich weit.
                Set<Pos> reach = new FormChecks.Walker(s, 0, false, p -> p.x() >= minX && p.x() <= maxX
                        && p.z() >= minZ && p.z() <= maxZ).reach(List.of(foot));
                Pos endTop = new Pos(line[1][0], 6, line[1][2]);
                boolean atEnd = reach.stream().anyMatch(p -> p.y() == 6 && Math.abs(p.x() - endTop.x()) <= width
                        && Math.abs(p.z() - endTop.z()) <= width);
                assertTrue(atEnd, label + ": Wehrgang am Ende " + endTop + " nicht erreichbar");
            }
        }
    }

    @Test
    void wallBattlementsFaceTheOuterSide() throws Exception {
        // Mauer entlang x, Außenseite vorn (Vorgabe): Zinnen bei z=0, Innenreihen z=1..2, Leiter z=3 in Spalte 1.
        Map<Pos, BlockSpec> s = state(compile(wall(new int[] {0, 0, 0}, new int[] {10, 0, 0}, "", 3)));
        assertEquals("stone_bricks", s.get(new Pos(0, 6, 0)).id());
        assertTrue(s.get(new Pos(1, 6, 0)) == null);
        assertTrue(s.get(new Pos(0, 6, 1)) == null && s.get(new Pos(0, 6, 2)) == null, "Wehrgang frei");
        assertEquals("ladder", s.get(new Pos(1, 1, 3)).id());
        assertEquals("back", s.get(new Pos(1, 1, 3)).props().get("facing"));
        // Außenseite hinten: Zinnen bei z=0, Mauer nach vorn (z=0..−2), Leiter z=−3 schaut nach vorn.
        Map<Pos, BlockSpec> b = state(compile(wall(new int[] {0, 0, 0}, new int[] {10, 0, 0}, "back", 3)));
        assertEquals("stone_bricks", b.get(new Pos(0, 6, 0)).id());
        assertEquals("stone_bricks", b.get(new Pos(4, 5, -2)).id());
        assertEquals("front", b.get(new Pos(1, 1, -3)).props().get("facing"));
        // Mauer entlang z: Vorgabe links (x=0 außen), Innenreihen nach +x
        Map<Pos, BlockSpec> z = state(compile(wall(new int[] {0, 0, 0}, new int[] {0, 0, 10}, "", 3)));
        assertEquals("stone_bricks", z.get(new Pos(0, 6, 0)).id());
        assertTrue(z.get(new Pos(1, 6, 0)) == null && z.get(new Pos(2, 5, 0)) != null);
    }

    @Test
    void straightWallHasAGateInTheMiddleShortAndSlantedOnesDoNot() throws Exception {
        // 2026-11-14 (Gedächtnis „Offen“: „Mauer ohne Tor“): Durchfahrt wie das Burgtor, 3 breit, durch die ganze Dicke.
        Map<Pos, BlockSpec> s = state(compile(wall(new int[] {0, 0, 0}, new int[] {20, 0, 0}, "", 3)));
        for (int x = 9; x <= 11; x++) {
            for (int z = 0; z <= 2; z++) {
                for (int y = 1; y <= 4; y++) {
                    BlockSpec c = s.get(new Pos(x, y, z));
                    assertTrue(c == null || c.id().equals("air"), "Durchfahrt bei " + x + "/" + y + "/" + z + ": " + c);
                }
                assertEquals("stone_bricks", s.get(new Pos(x, 5, z)).id(), "Sturz und Wehrgang über dem Tor");
            }
        }
        assertEquals("stone_bricks", s.get(new Pos(8, 1, 1)).id(), "Wange links");
        assertEquals("stone_bricks", s.get(new Pos(12, 1, 1)).id(), "Wange rechts");
        // Kurze Mauer: kein Tor (die Durchfahrt wäre ein Drittel der Mauer).
        Map<Pos, BlockSpec> shortWall = state(compile(wall(new int[] {0, 0, 0}, new int[] {6, 0, 0}, "", 3)));
        assertEquals("stone_bricks", shortWall.get(new Pos(3, 1, 1)).id());
        // Schräge Mauer: kein Tor, sie bleibt geschlossen.
        Map<Pos, BlockSpec> slant = state(compile(wall(new int[] {0, 0, 0}, new int[] {20, 0, 5}, "", 1)));
        assertTrue(slant.entrySet().stream().filter(e -> e.getKey().y() == 1)
                .allMatch(e -> !e.getValue().id().equals("air")), "schräg: keine Luft in der untersten Reihe");
    }

    // ---------------------------------------------------------------- Brücke

    private static String bridge(int[] a, int[] b, String style, String block, int width) {
        return bridge(a, b, 0, style, block, width);
    }

    /** Brücke mit dem Deck auf deckY (from.y und to.y). */
    private static String bridge(int[] a, int[] b, int deckY, String style, String block, int width) {
        return "{\"op\":\"bridge\",\"block\":\"" + block + "\",\"from\":[" + a[0] + "," + deckY + "," + a[2]
                + "],\"to\":[" + b[0] + "," + deckY + "," + b[2] + "]" + ",\"width\":" + width
                + (style.isEmpty() ? "" : ",\"style\":\"" + style + "\"") + "}";
    }

    @Test
    void bridgeHoldsAndItsDeckIsWalkableFromEndToEndInAllFourDirections() throws Exception {
        int[][][] lines = {{{0, 0, 0}, {21, 0, 0}}, {{21, 0, 0}, {0, 0, 0}}, {{0, 0, 0}, {0, 0, 21}},
                {{0, 0, 21}, {0, 0, 0}}};
        String[][] kinds = {{"arch", "stone_bricks"}, {"beam", "stone_bricks"}, {"", "spruce_planks"}};
        for (int[][] line : lines) {
            for (String[] kind : kinds) {
                for (int width : new int[] {1, 3, 4}) {
                    String label = "bridge " + java.util.Arrays.deepToString(line) + " " + kind[0] + " " + kind[1]
                            + " width " + width;
                    Map<Pos, BlockSpec> s = state(compile(bridge(line[0], line[1], kind[0], kind[1], width)));
                    assertHolds(s, label);
                    assertAttached(s, label);
                    boolean alongX = line[0][0] != line[1][0];
                    int lo = alongX ? Math.min(line[0][0], line[1][0]) : Math.min(line[0][2], line[1][2]);
                    int hi = alongX ? Math.max(line[0][0], line[1][0]) : Math.max(line[0][2], line[1][2]);
                    int half = (width + 2) / 2 + 1;
                    assertNoCell(s, p -> {
                        int along = alongX ? p.x() : p.z();
                        int across = alongX ? p.z() : p.x();
                        return along < lo || along > hi || Math.abs(across) > half || p.y() > 2;
                    }, label);
                    // Vom Ufer vor dem einen Ende übers Deck zum Ufer hinter dem anderen
                    Pos start = alongX ? new Pos(lo - 1, 1, 0) : new Pos(0, 1, lo - 1);
                    Pos goal = alongX ? new Pos(hi + 1, 1, 0) : new Pos(0, 1, hi + 1);
                    Set<Pos> reach = new FormChecks.Walker(s, 0, false, p -> {
                        int along = alongX ? p.x() : p.z();
                        int across = alongX ? p.z() : p.x();
                        return along >= lo - 1 && along <= hi + 1 && Math.abs(across) <= half;
                    }).reach(List.of(start));
                    assertTrue(reach.contains(goal), label + ": Deck nicht durchgehend begehbar");
                    // Geländer: auf ganzer Länge genau zwei Blöcke auf Laufhöhe, width + 1 auseinander (dazwischen
                    // das freie Deck)
                    for (int along = lo; along <= hi; along++) {
                        int at = along;
                        List<Integer> rails = s.entrySet().stream().filter(e -> e.getKey().y() == 1
                                && (alongX ? e.getKey().x() : e.getKey().z()) == at && !e.getValue().isAir())
                                .map(e -> alongX ? e.getKey().z() : e.getKey().x()).sorted().toList();
                        assertEquals(2, rails.size(), label + ": Geländer bei " + along + " " + rails);
                        assertEquals(width + 1, rails.get(1) - rails.get(0), label + ": Geländerabstand");
                    }
                }
            }
        }
    }

    @Test
    void archBridgeRestsOnBothBanksAndIsThinInTheMiddle() throws Exception {
        Map<Pos, BlockSpec> s = state(compile(bridge(new int[] {0, 0, 0}, new int[] {0, 0, 21}, "arch",
                "stone_bricks", 3)));
        int low = FormChecks.minY(s);
        assertEquals(-1 - 5, low, "Bogentiefe = Länge/4");  // 22 / 4 = 5
        // Unterste Schicht nur an den beiden Ufern
        for (Map.Entry<Pos, BlockSpec> e : FormChecks.solidOnly(s).entrySet()) {
            if (e.getKey().y() == low) {
                assertTrue(e.getKey().z() == 0 || e.getKey().z() == 21, "Auflager mitten im Fluss: " + e.getKey());
            }
        }
        // In der Mitte nur Deck und Bogenschicht
        assertTrue(s.get(new Pos(0, -2, 10)) == null && s.get(new Pos(0, -1, 10)) != null);
        // Railing aus Steinziegelmauer, Laternen darauf
        assertEquals("stone_brick_wall", s.get(new Pos(-2, 1, 3)).id());
        assertEquals("lantern", s.get(new Pos(-2, 2, 0)).id());
    }

    @Test
    void woodenBridgeBecomesABeamBridgeWithFencesAndPiers() throws Exception {
        Map<Pos, BlockSpec> s = state(compile(bridge(new int[] {0, 0, 0}, new int[] {0, 0, 13}, "", "spruce_planks",
                3)));
        assertEquals("spruce_fence", s.get(new Pos(-2, 1, 5)).id());
        // Pfeiler bei z=0, 6 und am Ende, 4 tief, aus Fichtenstamm stehend
        assertEquals("spruce_log", s.get(new Pos(0, -4, 6)).id());
        assertEquals("y", s.get(new Pos(0, -4, 6)).props().get("axis"));
        assertTrue(s.get(new Pos(0, -4, 3)) == null, "zwischen den Pfeilern frei");
        // Längsträger unter dem Rand, liegend in Laufrichtung (lokal z)
        assertEquals("z", s.get(new Pos(-2, -1, 3)).props().get("axis"));
    }

    // ---------------------------------------------------------------- Brückenrampen

    private static final int[][][] BRIDGE_LINES = {{{0, 0, 0}, {21, 0, 0}}, {{21, 0, 0}, {0, 0, 0}},
            {{0, 0, 0}, {0, 0, 21}}, {{0, 0, 21}, {0, 0, 0}}};
    private static final String[][] BRIDGE_KINDS = {{"arch", "stone_bricks", "stone_brick_stairs"},
            {"beam", "stone_bricks", "stone_brick_stairs"}, {"", "spruce_planks", "spruce_stairs"}};

    /** Planposition bei along (Laufrichtung) und across (quer) für eine Brücke entlang x oder z. */
    private static Pos onBridge(boolean alongX, int along, int y, int across) {
        return alongX ? new Pos(along, y, across) : new Pos(across, y, along);
    }

    /**
     * Bogenbrücke: an beiden Enden führt eine Rampe vom Deck auf den Boden (Gedächtnis „Offen“: „Brücke ohne
     * Rampen“). Deck auf y = 1 und 4 über dem Boden y = 0: Vom Boden vor der einen Rampe kommt man zu Fuß aufs Deck
     * und hinter der anderen wieder hinunter, jede Spalte genau eine Stufe tiefer – in allen vier Richtungen, für
     * Bogen, Balken und Holz.
     */
    @Test
    void rampsLeadFromARaisedDeckDownToTheGroundAtBothEnds() throws Exception {
        for (int[][] line : BRIDGE_LINES) {
            for (String[] kind : BRIDGE_KINDS) {
                for (int width : new int[] {1, 3}) {
                    for (int deck : new int[] {1, 4}) {
                        String label = "bridge " + java.util.Arrays.deepToString(line) + " " + kind[0] + " " + kind[1]
                                + " width " + width + " deck " + deck;
                        Map<Pos, BlockSpec> s = state(compile(bridge(line[0], line[1], deck, kind[0], kind[1],
                                width)));
                        assertHolds(s, label);
                        assertAttached(s, label);
                        boolean alongX = line[0][0] != line[1][0];
                        int lo = alongX ? Math.min(line[0][0], line[1][0]) : Math.min(line[0][2], line[1][2]);
                        int hi = alongX ? Math.max(line[0][0], line[1][0]) : Math.max(line[0][2], line[1][2]);
                        int half = (width + 2) / 2;
                        // Je Ende so viele Spalten Rampe, wie das Deck hoch liegt – darüber hinaus nichts.
                        assertNoCell(s, p -> {
                            int along = alongX ? p.x() : p.z();
                            int across = alongX ? p.z() : p.x();
                            return along < lo - deck || along > hi + deck || Math.abs(across) > half
                                    || p.y() > deck + 2;
                        }, label);
                        Predicate<Pos> area = p -> {
                            int along = alongX ? p.x() : p.z();
                            int across = alongX ? p.z() : p.x();
                            return along >= lo - deck - 1 && along <= hi + deck + 1 && Math.abs(across) <= half;
                        };
                        Pos before = onBridge(alongX, lo - deck - 1, 1, 0);
                        Pos after = onBridge(alongX, hi + deck + 1, 1, 0);
                        Pos top = onBridge(alongX, (lo + hi) / 2, deck + 1, 0);
                        Set<Pos> up = new FormChecks.Walker(s, 0, false, area).reach(List.of(before));
                        assertTrue(up.contains(top) && up.contains(after), label + ": vom Boden nicht übers Deck");
                        Set<Pos> back = new FormChecks.Walker(s, 0, false, area).reach(List.of(after));
                        assertTrue(back.contains(top) && back.contains(before), label + ": zurück nicht übers Deck");
                        for (int i = 1; i <= deck; i++) {
                            for (int along : new int[] {lo - i, hi + i}) {
                                Pos tread = onBridge(alongX, along, deck - i, 0);
                                assertEquals(kind[2], id(s, tread), label + ": Stufe bei " + tread);
                                assertEquals("nichts", id(s, tread.up()), label + ": über der Stufe frei");
                            }
                        }
                        // Stufen steigen zum Deck hin: facing (die hohe Seite) zeigt in Laufrichtung auf das Deck.
                        for (Map.Entry<Pos, BlockSpec> e : s.entrySet()) {
                            if (e.getValue().id().endsWith("_stairs")) {
                                int[] v = FormChecks.localVector(e.getValue().props().get("facing"));
                                int along = alongX ? e.getKey().x() : e.getKey().z();
                                int toDeck = Integer.signum(alongX ? v[0] : v[2]);
                                assertEquals(along < lo ? 1 : -1, toDeck, label + ": Stufe bei " + e.getKey()
                                        + " schaut " + e.getValue().props().get("facing"));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Die Stufen drehen mit der Brücke: In jeder der vier Weltrichtungen (so, wie BuildService den Plan setzt) zeigt
     * die hohe Seite jeder Stufe auf das Deck. Wer nur die Positionen dreht, baut Rampen, die vom Deck weg steigen.
     */
    @Test
    void rampStairsTurnWithTheBridgeInEveryWorldDirection() throws Exception {
        for (int[][] line : BRIDGE_LINES) {
            Map<Pos, BlockSpec> local = state(compile(bridge(line[0], line[1], 3, "arch", "stone_bricks", 3)));
            Pos mid = new Pos((line[0][0] + line[1][0]) / 2, 3, (line[0][2] + line[1][2]) / 2);
            for (Orientation.Cardinal c : Orientation.Cardinal.values()) {
                Orientation o = new Orientation(c);
                Pos deck = o.toWorldOffset(mid);
                int stairs = 0;
                for (Map.Entry<Pos, BlockSpec> e : local.entrySet()) {
                    if (!e.getValue().id().endsWith("_stairs")) {
                        continue;
                    }
                    stairs++;
                    Pos p = o.toWorldOffset(e.getKey());
                    int[] v = FormChecks.worldVector(o.toWorld(e.getValue()).props().get("facing"));
                    long toDeck = (long) v[0] * (deck.x() - p.x()) + (long) v[2] * (deck.z() - p.z());
                    assertTrue(toDeck > 0, java.util.Arrays.deepToString(line) + " Blick " + c + ": Stufe bei " + p
                            + " steigt vom Deck weg");
                }
                assertEquals(2 * 3 * 3, stairs, "zwei Rampen zu je 3 Spalten, 3 Laufreihen");
            }
        }
    }

    /** Liegt das Deck auf dem Boden (y = 0) oder darunter, entsteht keine Rampe – Zelle für Zelle wie vorher. */
    @Test
    void deckOnOrBelowTheGroundGetsNoRamp() throws Exception {
        for (int[][] line : BRIDGE_LINES) {
            for (String[] kind : BRIDGE_KINDS) {
                for (int deck : new int[] {0, -3}) {
                    String label = "bridge " + java.util.Arrays.deepToString(line) + " " + kind[1] + " deck " + deck;
                    Map<Pos, BlockSpec> s = state(compile(bridge(line[0], line[1], deck, kind[0], kind[1], 3)));
                    boolean alongX = line[0][0] != line[1][0];
                    int lo = alongX ? Math.min(line[0][0], line[1][0]) : Math.min(line[0][2], line[1][2]);
                    int hi = alongX ? Math.max(line[0][0], line[1][0]) : Math.max(line[0][2], line[1][2]);
                    assertTrue(s.values().stream().noneMatch(b -> b.id().endsWith("_stairs")), label + ": Stufen");
                    assertNoCell(s, p -> (alongX ? p.x() : p.z()) < lo || (alongX ? p.x() : p.z()) > hi, label);
                }
            }
        }
    }

    /**
     * Deck höher als 24 (MAX_RAMP_STEPS): Structures.fit kappt die Rampe mit Warnung, statt den Plan je Höhenblock um
     * eine Spalte über from..to wachsen zu lassen (ein Deck auf y = 200 wüchse sonst um 200 je Seite). Deck und Grenze
     * stehen hier fest: Abgeleitet aus der Konstante, scheiterte die Gegenprobe „ohne Kappung“ nur an der
     * Schemagrenze der Koordinate statt an der zu langen Rampe.
     */
    @Test
    void tooHighDeckGetsACappedRampAndAWarning() throws Exception {
        int max = 24;
        int deck = 30;
        CompiledBuild b = compile(bridge(new int[] {0, 0, 0}, new int[] {0, 0, 21}, deck, "arch", "stone_bricks", 3));
        Map<Pos, BlockSpec> s = state(b);
        assertHolds(s, "deck " + deck);
        assertNoCell(s, p -> p.z() < -max || p.z() > 21 + max, "deck " + deck);
        assertEquals("stone_brick_stairs", id(s, new Pos(0, deck - max, -max)), "letzte Stufe");
        assertEquals("stone_brick_stairs", id(s, new Pos(0, deck - max, 21 + max)), "letzte Stufe hinten");
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("Rampe") && w.contains("gekappt auf " + max)),
                () -> "" + b.warnings());
        // Bis 24 keine Warnung
        assertTrue(compile(bridge(new int[] {0, 0, 0}, new int[] {0, 0, 21}, max, "arch", "stone_bricks", 3))
                .warnings().isEmpty());
    }

    // ---------------------------------------------------------------- Garten

    private static String garden(int w, int d, String facing, String block) {
        return "{\"op\":\"garden\",\"block\":\"" + block + "\",\"from\":[0,0,0],\"to\":[" + (w - 1) + ",0," + (d - 1)
                + "],\"facing\":\"" + facing + "\"}";
    }

    @Test
    void gardenIsFencedWithOneGateThatLeadsInInAllFourDirections() throws Exception {
        for (int[] size : new int[][] {{5, 5}, {13, 11}, {9, 14}}) {
            for (String side : FormChecks.SIDES) {
                String label = "garden " + size[0] + "x" + size[1] + " " + side;
                int w = size[0];
                int d = size[1];
                Map<Pos, BlockSpec> s = state(compile(garden(w, d, side, "oak_fence")));
                assertHolds(s, label);
                assertAttached(s, label);
                assertNoCell(s, p -> p.x() < 0 || p.x() >= w || p.z() < 0 || p.z() >= d || p.y() < 0, label);
                List<Map.Entry<Pos, BlockSpec>> gates = s.entrySet().stream()
                        .filter(e -> e.getValue().id().endsWith("_fence_gate")).toList();
                assertEquals(1, gates.size(), label + ": Tore " + gates);
                Pos gate = gates.getFirst().getKey();
                String gf = gates.getFirst().getValue().props().get("facing");
                boolean onZSide = gate.z() == 0 || gate.z() == d - 1;
                assertTrue(onZSide ? gf.equals("front") || gf.equals("back") : gf.equals("left") || gf.equals("right"),
                        label + ": Tor quer zum Zaun gedreht (" + gf + ")");
                Pos center = new Pos(w / 2, 1, d / 2);
                List<Pos> row = outsideRow(side, w, d, 1);
                Predicate<Pos> area = rectPlus(w, d, row);
                Set<Pos> open = new FormChecks.Walker(s, 0, true, area).reach(row);
                assertTrue(open.stream().anyMatch(p -> p.x() > 0 && p.x() < w - 1 && p.z() > 0 && p.z() < d - 1),
                        label + ": durch das offene Tor kommt man nicht hinein");
                Set<Pos> closed = new FormChecks.Walker(s, 0, false, area).reach(row);
                assertFalse(closed.stream().anyMatch(p -> p.x() > 0 && p.x() < w - 1 && p.z() > 0 && p.z() < d - 1),
                        label + ": Zaun hat ein Loch");
                assertTrue(open.contains(center) || w < 7, label + ": Mitte nicht erreichbar");
            }
        }
    }

    @Test
    void gardenHasPathsBedsAndTreesAndFenceMatchesTheWood() throws Exception {
        Map<Pos, BlockSpec> s = state(compile(garden(13, 11, "front", "spruce_planks")));
        assertEquals("spruce_fence", s.get(new Pos(0, 1, 5)).id());
        assertEquals("spruce_fence_gate", s.get(new Pos(6, 1, 0)).id());
        assertEquals("dirt_path", s.get(new Pos(6, 0, 4)).id());
        assertEquals("dirt_path", s.get(new Pos(3, 0, 5)).id());
        long flowers = s.values().stream().filter(b -> FormChecks.isFlower(b.id())).count();
        assertTrue(flowers >= 10, "Blumen: " + flowers);
        assertTrue(s.values().stream().anyMatch(b -> b.id().equals("spruce_log")), "Bäume ab 11×11");
        assertTrue(s.values().stream().filter(b -> b.id().endsWith("_leaves"))
                .allMatch(b -> "true".equals(b.props().get("persistent"))));
        assertEquals("lantern", s.get(new Pos(5, 2, 0)).id());
    }

    // ---------------------------------------------------------------- Baum

    private static String tree(String block, int height, String style) {
        return "{\"op\":\"tree\",\"block\":\"" + block + "\",\"pos\":[3,0,4]" + (height > 0 ? ",\"height\":" + height
                : "") + (style.isEmpty() ? "" : ",\"style\":\"" + style + "\"") + "}";
    }

    @Test
    void treesHoldReachTheirHeightAndStayNearTheirTrunk() throws Exception {
        String[][] kinds = {{"oak_log", ""}, {"spruce_log", ""}, {"birch_planks", "round"}, {"oak_log", "cone"},
                {"spruce_leaves", "round"}};
        for (String[] kind : kinds) {
            for (int h : new int[] {4, 5, 7, 9, 13, 14, 20, 32}) {
                String label = "tree " + kind[0] + " " + kind[1] + " h" + h;
                Map<Pos, BlockSpec> s = state(compile(tree(kind[0], h, kind[1])));
                assertHolds(s, label);
                assertAttached(s, label);
                assertEquals(h, FormChecks.maxY(s), label + ": Höhe");
                assertEquals(0, FormChecks.minY(s), label + ": Erde unter dem Stamm");
                assertNoCell(s, p -> Math.abs(p.x() - 3) > 9 || Math.abs(p.z() - 4) > 9, label);
                assertEquals("dirt", s.get(new Pos(3, 0, 4)).id(), label);
                String log = s.get(new Pos(3, 1, 4)).id();
                assertTrue(log.endsWith("_log"), label + ": Stamm " + log);
                assertEquals("y", s.get(new Pos(3, 1, 4)).props().get("axis"), label);
                boolean thick = h >= Structures.THICK_TRUNK_HEIGHT;
                assertEquals(thick, s.get(new Pos(4, 1, 5)) != null && s.get(new Pos(4, 1, 5)).id().equals(log),
                        label + ": 2×2-Stamm ab " + Structures.THICK_TRUNK_HEIGHT);
                assertTrue(s.values().stream().filter(b -> b.id().endsWith("_leaves"))
                        .allMatch(b -> "true".equals(b.props().get("persistent"))), label + ": Laub zerfiele");
            }
        }
    }

    @Test
    void coniferNarrowsUpwardAndBroadleafIsWide() throws Exception {
        Map<Pos, BlockSpec> cone = state(compile(tree("spruce_log", 16, "")));
        assertEquals("spruce_leaves", cone.get(new Pos(3, 16, 4)).id());
        assertTrue(width(cone, 6) > width(cone, 13), "unten breiter als oben");
        Map<Pos, BlockSpec> round = state(compile(tree("oak_log", 0, "")));  // Vorgabe 7
        assertEquals(7, FormChecks.maxY(round));
        assertTrue(width(round, 5) >= 5, "Krone mindestens 5 breit");
    }

    private static int width(Map<Pos, BlockSpec> s, int y) {
        return (int) s.entrySet().stream().filter(e -> e.getKey().y() == y && e.getKey().z() == 4
                && e.getValue().id().endsWith("_leaves") || e.getKey().y() == y && e.getKey().z() == 4
                && e.getValue().id().endsWith("_log")).count();
    }

    // ---------------------------------------------------------------- Plan-Ebene

    @Test
    void structuresAreOneStepEachAndPassTheLinterWithoutErrors() throws Exception {
        for (String step : List.of(castle("left", "square"), wall(new int[] {0, 0, 30}, new int[] {20, 0, 30}, "", 3),
                bridge(new int[] {0, 0, 40}, new int[] {0, 0, 60}, "arch", "stone_bricks", 3),
                garden(13, 11, "front", "oak_fence"), tree("oak_log", 7, ""))) {
            BuildPlan plan = PlanParser.parse("{\"steps\":[" + step + "]}");
            CompiledBuild b = FormChecks.compiler().compile(plan);
            assertTrue(b.warnings().isEmpty(), () -> step + ": " + b.warnings());
            List<PlanLinter.Issue> issues = PlanLinter.lint(plan, b, true);
            assertTrue(issues.stream().noneMatch(i -> i.severity() == PlanLinter.Severity.ERROR),
                    () -> step + ": " + issues);
        }
    }

    @Test
    void aLaterStructureDoesNotHideForeignFloatingBlocksFromTheLinter() throws Exception {
        // Vorher ordnete der Linter jede Zelle im groben Kasten (Baum ±9, Brücke bis 25 unter dem Deck) der Bauform
        // zu, und die steht in UNCHECKED_OPS: Die schwebende Laterne des LLM blieb ungeprüft.
        String floor = "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[6,0,6]},"
                + "{\"op\":\"set\",\"block\":\"lantern\",\"pos\":[3,5,3]}";
        List<String> unchecked = new ArrayList<>();
        for (String later : new String[] {"", ",{\"op\":\"tree\",\"block\":\"oak_log\",\"pos\":[3,0,12]}",
                ",{\"op\":\"bridge\",\"block\":\"stone_bricks\",\"from\":[0,4,10],\"to\":[20,4,10]}"}) {
            BuildPlan plan = PlanParser.parse("{\"steps\":[" + floor + later + "]}");
            CompiledBuild b = FormChecks.compiler().compile(plan);
            List<PlanLinter.Issue> issues = PlanLinter.lint(plan, b, true);
            if (issues.stream().noneMatch(i -> i.severity() == PlanLinter.Severity.ERROR
                    && i.pos().equals(new Pos(3, 5, 3)))) {
                unchecked.add("mit" + later + ": " + issues);
            }
        }
        assertTrue(unchecked.isEmpty(), () -> "schwebende Laterne ungeprüft " + unchecked);
    }

    @Test
    void treeOfNonWoodIsBuiltAsOakWithAWarning() throws Exception {
        // Vorher fiel ein Baum aus Stein still auf Eiche zurück.
        CompiledBuild b = compile("{\"op\":\"tree\",\"block\":\"stone\",\"pos\":[0,0,0]}");
        assertTrue(b.warnings().stream().anyMatch(w -> w.contains("stone") && w.contains("Eiche")),
                () -> "" + b.warnings());
        assertTrue(state(b).values().stream().anyMatch(v -> v.id().equals("oak_log")));
    }

    @Test
    void facingOfTheStepNeverEndsUpOnTheWallBlock() throws Exception {
        // decorate() hängt facing an den Hauptblock; für Bauformen wäre stone_bricks[facing=left] ungültig.
        Map<Pos, BlockSpec> s = state(compile(castle("left", "round")));
        assertTrue(s.values().stream().filter(b -> b.id().equals("stone_bricks"))
                .allMatch(b -> b.props().isEmpty()), "Mauerstein mit Zustand");
    }
}
