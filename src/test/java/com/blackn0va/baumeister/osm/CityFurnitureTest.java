package com.blackn0va.baumeister.osm;

import static com.blackn0va.baumeister.osm.CityMultipolygonTest.LAT0;
import static com.blackn0va.baumeister.osm.CityMultipolygonTest.LON0;
import static com.blackn0va.baumeister.osm.CityMultipolygonTest.elements;
import static com.blackn0va.baumeister.osm.CityMultipolygonTest.way;
import static com.blackn0va.baumeister.osm.CityMultipolygonTest.world;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityModel.Road;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Stadtausstattung (2026-10-29): Laternen, Brücken, Gleise, Zäune/Hecken/Mauern, Parkplätze, Flüsse, Bäume, Wald und
 * Straßennamen-Schilder – je Element über parse/plan an erfundenen Ways in Blockkoordinaten, ohne Netz.
 */
class CityFurnitureTest {

    private static CityPlanner.Plan plan(String... elements) {
        return CityPlanner.plan(OsmImport.parse(elements(elements), "Test", LAT0, LON0, 400), 3_000_000);
    }

    private static CityModel model(String... elements) {
        return OsmImport.parse(elements(elements), "Test", LAT0, LON0, 400);
    }

    private static String node(long id, String tags, int x, int z) {
        double cos = Math.cos(Math.toRadians(LAT0));
        double lon = LON0 + x / (cos * 111_320.0);
        double lat = LAT0 - z / 110_540.0;
        return String.format(Locale.ROOT, "{\"type\":\"node\",\"id\":%d,\"lat\":%.9f,\"lon\":%.9f,\"tags\":{%s}}", id,
                lat, lon, tags);
    }

    /** Alle Stellen x/z mit einem Block, dessen Name mit prefix beginnt (beliebiges y). */
    private static Set<String> columns(Map<String, String> w, String prefix) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : w.entrySet()) {
            if (e.getValue().startsWith(prefix)) {
                String[] k = e.getKey().split("/");
                out.add(k[0] + "/" + k[2]);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Laternen

    private static final String MAIN = way(10, "\"highway\":\"residential\",\"name\":\"Lindenweg\"", -80, 0, 80, 0);

    @Test
    void lampsStandOnTheCurbAlternatingSides() {
        Map<String, String> w = world(plan(MAIN));
        Set<String> lamps = columns(w, "lantern");
        assertTrue(lamps.size() >= 8, "zu wenige Laternen: " + lamps);
        Set<Integer> sides = new HashSet<>();
        for (String c : lamps) {
            int x = Integer.parseInt(c.split("/")[0]);
            int z = Integer.parseInt(c.split("/")[1]);
            // Breite 6: Bordstein ist der äußere Ring, also |z| = 3; Laterne oben, Mast darunter.
            assertEquals(3, Math.abs(z), "nicht auf dem Bordstein: " + c);
            sides.add(Integer.signum(z));
            assertEquals("lantern[hanging=false]", w.get(x + "/4/" + z));
            for (int y = 1; y <= 3; y++) {
                assertEquals("polished_blackstone_wall", w.get(x + "/" + y + "/" + z));
            }
        }
        assertEquals(Set.of(-1, 1), sides, "Laternen nur auf einer Seite");
        // Abwechselnd: Nachbarn entlang x stehen auf verschiedenen Seiten.
        List<int[]> sorted = lamps.stream().map(c -> new int[] {Integer.parseInt(c.split("/")[0]),
                Integer.parseInt(c.split("/")[1])}).sorted((a, b) -> Integer.compare(a[0], b[0])).toList();
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i)[0] - sorted.get(i - 1)[0] >= StreetFurniture.LAMP_SPACING - 2);
            assertTrue(sorted.get(i)[1] != sorted.get(i - 1)[1], "zwei Laternen nacheinander auf derselben Seite");
        }
    }

    @Test
    void noLampAtACrossingOrDriveway() {
        // Einfahrt (Breite 4) bei x = −5 nach Süden, belegt x = −7 … −3. Die Laterne bei s = 72 (x = −8, Südseite)
        // stünde direkt neben ihrer Kante – nicht auf ihr, das fängt schon die Belegung ab; hier zählt der Abstand.
        String drive = way(11, "\"highway\":\"service\"", -5, 0, -5, 20);
        CityPlanner.Plan p = plan(MAIN, drive);
        Map<String, String> w = world(p);
        for (String c : columns(w, "lantern")) {
            int x = Integer.parseInt(c.split("/")[0]);
            int z = Integer.parseInt(c.split("/")[1]);
            assertFalse(x >= -9 && x <= -1 && z > 0, "Laterne an der Einfahrt: " + c);
        }
        // Die übrigen bleiben.
        assertTrue(columns(w, "lantern").size() >= 7);
    }

    @Test
    void noLampInsideAHouseOrOnItsRoof() {
        // Garage (3 hoch, Flachdach in y = 3) ragt über den Bordstein der Südseite – die Laterne bei x = 24 fiele
        // hinein bzw. stünde auf dem Dach.
        String garage = way(12, "\"building\":\"garage\"", 18, 2, 30, 2, 30, 10, 18, 10, 18, 2);
        Map<String, String> w = world(plan(MAIN, garage));
        for (String c : columns(w, "lantern")) {
            int x = Integer.parseInt(c.split("/")[0]);
            int z = Integer.parseInt(c.split("/")[1]);
            assertFalse(x >= 18 && x <= 30 && z >= 2 && z <= 10, "Laterne in/auf der Garage: " + c);
        }
        for (String c : columns(w, "polished_blackstone_wall")) {
            int x = Integer.parseInt(c.split("/")[0]);
            int z = Integer.parseInt(c.split("/")[1]);
            assertFalse(x >= 18 && x <= 30 && z >= 2 && z <= 10, "Laternenmast in der Garage: " + c);
        }
        assertTrue(columns(w, "lantern").size() >= 7, "Laternen außerhalb der Garage fehlen");
    }

    @Test
    void separateSidewalksAlongTheStreetDoNotSuppressTheLamps() {
        // sidewalk=separate (Prüfbefund 2026-10-29): Gehwege als eigene Ways 6 m neben der Achse, also direkt am
        // Bordstein (Zellen |z| = 5 … 7). Vorher zählten sie im Freiraum von ±2 um die Laterne als „andere Straße“ –
        // in gut kartierten Innenstädten fehlten ganze Straßenzüge. Ein querender Gehweg (Fußgängerüberweg bei
        // x = −8, genau an einer Laternenstelle) bleibt eine Kreuzung.
        String north = way(15, "\"highway\":\"footway\",\"footway\":\"sidewalk\"", -80, -6, 80, -6);
        String south = way(16, "\"highway\":\"footway\",\"footway\":\"sidewalk\"", -80, 6, 80, 6);
        String crossing = way(17, "\"highway\":\"footway\",\"footway\":\"crossing\"", -8, -6, -8, 6);
        Map<String, String> w = world(plan(MAIN, north, south, crossing));
        Set<String> lamps = columns(w, "lantern");
        assertTrue(lamps.size() >= 8, "Laternen fehlen neben separaten Gehwegen: " + lamps);
        for (String c : lamps) {
            int x = Integer.parseInt(c.split("/")[0]);
            int z = Integer.parseInt(c.split("/")[1]);
            assertEquals(3, Math.abs(z), "nicht auf dem Bordstein: " + c);
            assertFalse(x >= -10 && x <= -6, "Laterne am Fußgängerüberweg: " + c);
        }
    }

    @Test
    void footpathsAndMotorwaysGetNoLamps() {
        Map<String, String> w = world(plan(way(13, "\"highway\":\"footway\"", -60, 0, 60, 0),
                way(14, "\"highway\":\"motorway\"", -60, 40, 60, 40)));
        assertTrue(columns(w, "lantern").isEmpty());
    }

    // ------------------------------------------------------------------ Brücken

    private static final String RIVER = way(20, "\"waterway\":\"river\",\"width\":\"10\"", 0, -40, 0, 40);

    @Test
    void bridgeOverTheRiverIsRaisedWithRailingAndTheWaterFlowsBelow() {
        String bridge = way(21, "\"highway\":\"residential\",\"bridge\":\"yes\",\"layer\":\"1\"", -20, 0, 20, 0);
        CityPlanner.Plan p = plan(RIVER, bridge);
        Map<String, String> w = world(p);
        // Über Wasser: H = 2. Mitte (s = 20, u = 4): Fahrbahn in y = 2, darunter Luft, ganz unten der Fluss –
        // unterbrochen nur von Pfeilern (alle 12 m an der Achse).
        int water = 0;
        for (int z = -3; z <= 3; z++) {
            for (int x = -4; x <= 4; x++) {
                String b = w.get(x + "/0/" + z);
                assertTrue(b.equals("water") || b.equals("stone_bricks") && Math.abs(z) <= 1,
                        "Fluss unter der Brücke bei " + x + "/" + z + ": " + b);
                water += b.equals("water") ? 1 : 0;
            }
        }
        assertTrue(water >= 55, "Wasser unter der Brücke: " + water);
        assertEquals("stone_bricks", w.get("-2/0/0"), "Pfeiler bei s = 18");
        assertEquals("gray_concrete", w.get("0/2/0"));
        assertEquals("air", w.get("0/1/0"));
        // Geländer eine Zelle außerhalb der Fahrbahn (Breite 6 → |z| = 4), auf dem Randblock.
        assertTrue(w.get("0/3/4").startsWith("stone_brick_wall"), "Geländer Süd: " + w.get("0/3/4"));
        assertTrue(w.get("0/3/-4").startsWith("stone_brick_wall"), "Geländer Nord: " + w.get("0/3/-4"));
        assertEquals("stone_brick_wall[east=low,north=none,south=none,up=false,west=low]", w.get("0/3/4"),
                "gerades Geländer verbindet sich entlang der Brücke");
        // Rampe: am Ende Bodenhöhe mit Platte, einen Meter weiter ein voller Block.
        assertEquals("smooth_stone_slab[type=bottom]", w.get("-19/1/0"));
        assertEquals("gray_concrete", w.get("-18/1/0"));
        // Damm unter der Rampe auf festem Boden, nicht über dem Fluss.
        assertEquals("stone_bricks", w.get("-16/1/0"));
    }

    @Test
    void bridgeOverARoadLeavesFourBlocksHeadroom() {
        String below = way(22, "\"highway\":\"residential\"", 0, -40, 0, 40);
        String bridge = way(23, "\"highway\":\"tertiary\",\"bridge\":\"yes\"", -30, 0, 30, 0);
        Map<String, String> w = world(plan(below, bridge));
        CityModel m = model(below, bridge);
        assertEquals(Bridges.OVER_ROAD, Bridges.height(m.roads().get(1), m));
        for (int y = 1; y <= 4; y++) {
            assertEquals("air", w.get("0/" + y + "/0"), "lichte Höhe y=" + y);
        }
        assertEquals("gray_concrete", w.get("0/5/0"), "Fahrbahn über der Straße");
        assertTrue(Set.of("gray_concrete", "white_concrete").contains(w.get("0/0/0")), "Straße darunter bleibt");
    }

    @Test
    void footbridgeIsWoodWithAFence() {
        String bridge = way(24, "\"highway\":\"footway\",\"bridge\":\"yes\",\"width\":\"2\"", -20, 0, 20, 0);
        Map<String, String> w = world(plan(RIVER, bridge));
        assertEquals("spruce_planks", w.get("0/2/0"));
        assertTrue(w.get("0/3/2").startsWith("spruce_fence[east=true"), w.get("0/3/2"));
        assertEquals("water", w.get("0/0/0"));
    }

    @Test
    void noLampsOnOrUnderABridge() {
        String bridge = way(25, "\"highway\":\"residential\",\"bridge\":\"yes\"", -40, 0, 40, 0);
        Map<String, String> w = world(plan(RIVER, bridge));
        assertTrue(columns(w, "lantern").isEmpty(), "Laterne auf der Brücke: " + columns(w, "lantern"));
    }

    // ------------------------------------------------------------------ Gleise

    @Test
    void railTrackRunsConnectedOnBallast() {
        Map<String, String> w = world(plan(way(30, "\"railway\":\"rail\"", 0, 0, 20, 9)));
        Map<String, String> rails = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : w.entrySet()) {
            if (e.getValue().startsWith("rail[")) {
                String[] k = e.getKey().split("/");
                assertEquals("1", k[1], "Schiene nicht in y=1: " + e);
                rails.put(k[0] + "/" + k[2], e.getValue());
                assertEquals("gravel", w.get(k[0] + "/0/" + k[2]), "kein Schotter unter " + e);
            }
        }
        assertEquals(20 + 9 + 1, rails.size(), "4er-Pfad: je Schritt eine Achse");
        // Jede Schiene zeigt mit ihren Enden auf Nachbarschienen (außer an den Gleisenden).
        for (Map.Entry<String, String> e : rails.entrySet()) {
            int x = Integer.parseInt(e.getKey().split("/")[0]);
            int z = Integer.parseInt(e.getKey().split("/")[1]);
            String shape = e.getValue().substring("rail[shape=".length(), e.getValue().length() - 1);
            int linked = 0;
            for (String d : shape.split("_")) {
                int nx = x + (d.equals("east") ? 1 : d.equals("west") ? -1 : 0);
                int nz = z + (d.equals("south") ? 1 : d.equals("north") ? -1 : 0);
                linked += rails.containsKey(nx + "/" + nz) ? 1 : 0;
            }
            boolean end = x == 0 && z == 0 || x == 20 && z == 9;
            assertTrue(linked == 2 || end && linked == 1, "Schiene ohne Anschluss: " + e);
        }
    }

    @Test
    void levelCrossingKeepsTheRoadSurface() {
        Map<String, String> w = world(plan(way(31, "\"highway\":\"residential\"", -20, 0, 20, 0),
                way(32, "\"railway\":\"rail\"", 0, -20, 0, 20)));
        assertEquals("rail[shape=north_south]", w.get("0/1/0"));
        assertTrue(Set.of("gray_concrete", "white_concrete").contains(w.get("0/0/0")), "Straße unter dem Gleis");
        assertEquals("gravel", w.get("0/0/10"));
    }

    @Test
    void railShapesFromNeighbours() {
        int[] c = {5, 5};
        assertEquals("north_south", LineFeatures.railShape(c, new int[] {5, 4}, new int[] {5, 6}));
        assertEquals("east_west", LineFeatures.railShape(c, new int[] {4, 5}, null));
        assertEquals("south_east", LineFeatures.railShape(c, new int[] {5, 6}, new int[] {6, 5}));
        assertEquals("north_west", LineFeatures.railShape(c, new int[] {4, 5}, new int[] {5, 4}));
    }

    // ------------------------------------------------------------------ Begrenzungen

    @Test
    void fenceAroundAGardenConnectsAndLeavesTheDriveOpen() {
        String fence = way(40, "\"barrier\":\"fence\"", 0, 0, 10, 0, 10, 10, 0, 10, 0, 0);
        String drive = way(41, "\"highway\":\"service\",\"width\":\"3\"", 5, -10, 5, 20);
        Map<String, String> w = world(plan(fence, drive));
        assertEquals("oak_fence[east=false,north=true,south=true,west=false]", w.get("0/1/5"));
        assertEquals("oak_fence[east=true,north=false,south=true,west=false]", w.get("0/1/0"), "Ecke");
        assertFalse(String.valueOf(w.get("5/1/0")).startsWith("oak_fence"), "Zaun quer über der Einfahrt");
        assertEquals("oak_fence[east=false,north=false,south=false,west=true]", w.get("3/1/0"),
                "Zaunende an der Einfahrt verbindet sich nur nach innen");
    }

    @Test
    void hedgeAndWallHaveTheirOwnBlocks() {
        Map<String, String> w = world(plan(way(42, "\"barrier\":\"hedge\"", 0, 0, 10, 0),
                way(43, "\"barrier\":\"wall\"", 0, 20, 10, 20), way(44, "\"barrier\":\"city_wall\"", 0, 40, 10, 40)));
        assertEquals("oak_leaves[persistent=true]", w.get("5/1/0"));
        assertEquals("oak_leaves[persistent=true]", w.get("5/2/0"));
        assertEquals("cobblestone_wall[east=low,north=none,south=none,up=false,west=low]", w.get("5/1/20"));
        assertEquals("stone_bricks", w.get("5/4/40"));
    }

    // ------------------------------------------------------------------ Parkplätze, Gewässer

    @Test
    void parkingLotHasAsphaltCurbAndMarkings() {
        Map<String, String> w = world(plan(way(50, "\"amenity\":\"parking\"", 0, 0, 20, 0, 20, 20, 0, 20, 0, 0)));
        assertEquals("smooth_stone", w.get("0/0/5"), "Bordstein am Rand");
        assertEquals("white_concrete", w.get("9/0/2"), "Stellplatzlinie");
        assertEquals("gray_concrete", w.get("10/0/2"), "Stellplatz");
        assertEquals("gray_concrete", w.get("9/0/7"), "Fahrgasse ohne Linie");
        assertEquals("air", w.get("10/1/2"));
    }

    @Test
    void undergroundParkingIsNoSurface() {
        CityModel m = model(way(51, "\"amenity\":\"parking\",\"parking\":\"underground\"", 0, 0, 20, 0, 20, 20, 0,
                20, 0, 0));
        assertTrue(m.areas().isEmpty());
    }

    @Test
    void riverHasItsWidthAndWeirsAreNoWater() {
        Map<String, String> w = world(plan(way(52, "\"waterway\":\"river\"", -50, 0, 50, 0),
                way(53, "\"waterway\":\"weir\"", 0, -30, 0, 30)));
        // river ohne width: 20 m.
        int wet = 0;
        for (int z = -15; z <= 15; z++) {
            wet += "water".equals(w.get("20/0/" + z)) ? 1 : 0;
        }
        assertEquals(21, wet, "Breite des Flusses (|z| ≤ 10)");
        assertEquals("water", w.get("20/-1/0"), "Fluss ist zwei tief");
        assertNull(w.get("0/0/25"), "Wehr als Wasserlinie gezeichnet");
    }

    @Test
    void riverbankIsAWaterArea() {
        CityModel m = model(way(54, "\"waterway\":\"riverbank\"", 0, 0, 30, 0, 30, 30, 0, 30, 0, 0));
        assertEquals(List.of("water"), m.areas().stream().map(CityModel.Area::kind).toList());
        assertTrue(m.roads().isEmpty());
    }

    // ------------------------------------------------------------------ Bäume, Wald

    @Test
    void singleTreesKnowTheirKind() {
        Map<String, String> w = world(plan(node(60, "\"natural\":\"tree\",\"leaf_type\":\"needleleaved\"", 0, 0),
                node(61, "\"natural\":\"tree\",\"genus\":\"Betula\"", 20, 0), node(62, "\"natural\":\"tree\"", 40, 0)));
        assertEquals("spruce_log", w.get("0/6/0"));
        assertEquals("birch_log", w.get("20/5/0"));
        assertEquals("oak_log", w.get("40/4/0"));
        assertFalse(String.valueOf(w.get("40/5/0")).endsWith("_log"), "Eiche nur 4 hoch");
    }

    @Test
    void forestTreesNeverStandOnTheRoad() {
        String forest = way(63, "\"landuse\":\"forest\"", 0, 0, 120, 0, 120, 120, 0, 120, 0, 0);
        String road = way(64, "\"highway\":\"track\"", -10, 60, 130, 60);
        Map<String, String> w = world(plan(forest, road));
        Set<String> logs = columns(w, "oak_log");
        logs.addAll(columns(w, "birch_log"));
        logs.addAll(columns(w, "spruce_log"));
        assertTrue(logs.size() > 100, "Wald zu dünn: " + logs.size());
        for (String c : logs) {
            int z = Integer.parseInt(c.split("/")[1]);
            assertTrue(Math.abs(z - 60) > 1, "Stamm auf dem Weg: " + c);
        }
        // Locker: keine zwei Stämme näher als 3 Blöcke (Schachbrettabstand).
        List<int[]> pts = logs.stream().map(c -> new int[] {Integer.parseInt(c.split("/")[0]),
                Integer.parseInt(c.split("/")[1])}).toList();
        for (int i = 0; i < pts.size(); i++) {
            for (int j = i + 1; j < pts.size(); j++) {
                int d = Math.max(Math.abs(pts.get(i)[0] - pts.get(j)[0]), Math.abs(pts.get(i)[1] - pts.get(j)[1]));
                assertTrue(d >= 3, "Stämme zu dicht: " + List.of(pts.get(i)[0], pts.get(i)[1]) + " "
                        + List.of(pts.get(j)[0], pts.get(j)[1]));
            }
        }
    }

    @Test
    void needleForestIsSpruce() {
        Map<String, String> w = world(plan(way(65, "\"natural\":\"wood\",\"leaf_type\":\"needleleaved\"", 0, 0,
                60, 0, 60, 60, 0, 60, 0, 0)));
        assertFalse(columns(w, "spruce_log").isEmpty());
        assertTrue(columns(w, "oak_log").isEmpty() && columns(w, "birch_log").isEmpty());
    }

    // ------------------------------------------------------------------ Schilder

    private static final String HAUPT = way(70, "\"highway\":\"residential\",\"name\":\"Hauptstraße\"", -40, 0, 0, 0,
            40, 0);
    private static final String KIRCH = way(71, "\"highway\":\"residential\",\"name\":\"Kirchgasse\"", 0, -40, 0, 0,
            0, 40);

    @Test
    void crossingNamedStreetsGetOneSignEachParallelToTheirStreet() {
        CityPlanner.Plan p = plan(HAUPT, KIRCH);
        Map<String, String> w = world(p);
        assertEquals(2, p.signs().size(), "Schilder: " + p.signs());
        Map<String, CityPlanner.Sign> byName = new java.util.HashMap<>();
        for (CityPlanner.Sign s : p.signs()) {
            byName.put(String.join(" ", s.lines()), s);
            assertEquals(s.block(), w.get(s.x() + "/" + s.y() + "/" + s.z()), "Schild-Block an der Stelle");
            assertEquals("dark_oak_fence", w.get(s.x() + "/1/" + s.z()), "Pfosten");
            assertEquals("dark_oak_fence", w.get(s.x() + "/2/" + s.z()), "Pfosten");
            // Nicht auf der Fahrbahn: |x| bzw. |z| größer als die halbe Breite (3) minus Bordstein.
            assertTrue(Math.abs(s.x()) >= 3 && Math.abs(s.z()) >= 3, "Pfosten auf der Fahrbahn: " + s);
        }
        CityPlanner.Sign haupt = byName.get("Hauptstraße");
        CityPlanner.Sign kirch = byName.get("Kirchgasse");
        assertNotNull(haupt, byName.keySet().toString());
        assertNotNull(kirch, byName.keySet().toString());
        // Hauptstraße läuft in x: Schild parallel dazu, Vorderseite nach Süden oder Norden (0 bzw. 8).
        assertTrue(haupt.block().equals("birch_sign[rotation=0]") || haupt.block().equals("birch_sign[rotation=8]"),
                haupt.block());
        assertTrue(kirch.block().equals("birch_sign[rotation=4]") || kirch.block().equals("birch_sign[rotation=12]"),
                kirch.block());
    }

    @Test
    void sameNameOnBothWaysGivesNoSign() {
        CityPlanner.Plan p = plan(way(72, "\"highway\":\"residential\",\"name\":\"Ring\"", -40, 0, 0, 0),
                way(73, "\"highway\":\"residential\",\"name\":\"Ring\"", 0, 0, 0, 40));
        assertTrue(p.signs().isEmpty());
    }

    @Test
    void signsAtAStageBoundaryAreBuiltExactlyOnce() {
        CityModel m = model(HAUPT, KIRCH);
        CityPlanner planner = new CityPlanner();
        List<CityPlanner.Sign> all = new ArrayList<>();
        all.addAll(planner.plan(m, new CityPlanner.Clip(-100, -100, 0, 100, 1000), 3_000_000).signs());
        all.addAll(planner.plan(m, new CityPlanner.Clip(0, -100, 100, 100, 1000), 3_000_000).signs());
        assertEquals(2, all.size(), "Schilder über beide Etappen: " + all);
        assertEquals(2, all.stream().map(s -> s.x() + "/" + s.z()).distinct().count());
    }

    @Test
    void dualCarriagewaySignsAtAStageBoundaryMatchTheSinglePlan() {
        // Prüfbefund 2026-10-29: Doppelfahrbahn „Allee“ (z = 5 und z = 24) kreuzt den 12 m breiten „Querweg“. Der
        // Kreuzungspunkt z = 24 kommt in der Reihenfolge zuerst. Vorher (Abstand 20, nur gegen behaltene Punkte
        // geprüft) unterdrückte die Etappe z ≥ 0 den Punkt z = 5 wegen z = 24; die Etappe z < 0 sah z = 24 nicht
        // (außerhalb ihres Rands) und setzte für z = 5 einen Pfosten bei z = −2 – ein Schilderpaar mehr als im Plan aus
        // einem Stück.
        String quer = way(74, "\"highway\":\"residential\",\"name\":\"Querweg\",\"width\":\"12\"", 0, 60, 0, 24,
                0, 5, 0, -60);
        String north = way(75, "\"highway\":\"residential\",\"name\":\"Allee\",\"oneway\":\"yes\"", -60, 24, 0, 24,
                60, 24);
        String south = way(76, "\"highway\":\"residential\",\"name\":\"Allee\",\"oneway\":\"yes\"", -60, 5, 0, 5,
                60, 5);
        CityModel m = model(quer, north, south);
        Set<String> whole = new HashSet<>();
        for (CityPlanner.Sign s : CityPlanner.plan(m, 3_000_000).signs()) {
            whole.add(s.x() + "/" + s.z() + " " + s.lines());
        }
        CityPlanner planner = new CityPlanner();
        List<String> staged = new ArrayList<>();
        for (CityPlanner.Clip c : List.of(new CityPlanner.Clip(-100, -100, 100, 0, 1000),
                new CityPlanner.Clip(-100, 0, 100, 100, 1000))) {
            for (CityPlanner.Sign s : planner.plan(m, c, 3_000_000).signs()) {
                staged.add(s.x() + "/" + s.z() + " " + s.lines());
            }
        }
        assertEquals(whole, new HashSet<>(staged), "Etappen setzen andere Schilder als ein Plan");
        assertEquals(staged.size(), new HashSet<>(staged).size(), "doppelt gesetzt: " + staged);
    }

    @Test
    void closeDualCarriagewayGetsOnlyOnePairOfSigns() {
        // Fahrbahnen 10 m auseinander (innerhalb SIGN_DEDUPE): ein Schilderpaar, nicht zwei – auch über Etappen.
        String quer = way(77, "\"highway\":\"residential\",\"name\":\"Querweg\"", 0, 60, 0, 15, 0, 5, 0, -60);
        String north = way(78, "\"highway\":\"residential\",\"name\":\"Allee\"", -60, 15, 0, 15, 60, 15);
        String south = way(79, "\"highway\":\"residential\",\"name\":\"Allee\"", -60, 5, 0, 5, 60, 5);
        CityModel m = model(quer, north, south);
        assertEquals(2, CityPlanner.plan(m, 3_000_000).signs().size());
        CityPlanner planner = new CityPlanner();
        int staged = planner.plan(m, new CityPlanner.Clip(-100, -100, 100, 10, 1000), 3_000_000).signs().size()
                + planner.plan(m, new CityPlanner.Clip(-100, 10, 100, 100, 1000), 3_000_000).signs().size();
        assertEquals(2, staged);
    }

    @Test
    void lampsAtAStageBoundaryAreNotDoubled() {
        CityModel m = model(MAIN);
        CityPlanner planner = new CityPlanner();
        Set<String> lamps = new HashSet<>();
        int count = 0;
        for (CityPlanner.Clip c : List.of(new CityPlanner.Clip(-100, -100, 8, 100, 1000),
                new CityPlanner.Clip(8, -100, 100, 100, 1000))) {
            Set<String> l = columns(world(planner.plan(m, c, 3_000_000)), "lantern");
            count += l.size();
            lamps.addAll(l);
        }
        assertEquals(columns(world(plan(MAIN)), "lantern"), lamps, "Etappen setzen dieselben Laternen wie ein Plan");
        assertEquals(lamps.size(), count, "doppelt gesetzt");
    }

    @Test
    void signTextWrapsLikeAStreetSign() {
        assertEquals(List.of("Am Markt"), StreetFurniture.lines("Am Markt"));
        assertEquals(List.of("Kurfürsten-", "straße"), StreetFurniture.lines("Kurfürstenstraße"));
        assertEquals(List.of("Konrad-", "Adenauer-Straße"), StreetFurniture.lines("Konrad-Adenauer-Straße"));
        assertEquals(List.of("Straße des 17.", "Juni"), StreetFurniture.lines("Straße des 17. Juni"));
        List<String> longName = StreetFurniture.lines("Sehr lange Bezeichnung einer kleinen Gasse hinter dem alten "
                + "Rathaus am Markt");
        assertEquals(4, longName.size());
        assertTrue(longName.getLast().endsWith("…"), longName.toString());
        for (String l : longName) {
            assertTrue(l.length() <= 15, l);
        }
    }

    @Test
    void signRotationFollowsTheFace() {
        assertEquals(0, StreetFurniture.rotation(0, 1));
        assertEquals(4, StreetFurniture.rotation(-1, 0));
        assertEquals(8, StreetFurniture.rotation(0, -1));
        assertEquals(12, StreetFurniture.rotation(1, 0));
    }

    // ------------------------------------------------------------------ Import

    @Test
    void importReadsBridgesRailsBarriersAndParking() {
        CityModel m = model(way(80, "\"highway\":\"primary\",\"bridge\":\"viaduct\",\"layer\":\"2\"", 0, 0, 50, 0),
                way(81, "\"highway\":\"residential\",\"layer\":\"1\"", 0, 10, 50, 10),
                way(82, "\"highway\":\"residential\",\"bridge\":\"no\",\"layer\":\"1\"", 0, 20, 50, 20),
                way(83, "\"railway\":\"tram\"", 0, 30, 50, 30), way(84, "\"railway\":\"abandoned\"", 0, 40, 50, 40),
                way(85, "\"barrier\":\"hedge\"", 0, 50, 50, 50), way(86, "\"barrier\":\"bollard\"", 0, 60, 50, 60),
                way(87, "\"railway\":\"rail\",\"bridge\":\"yes\"", 0, 70, 50, 70));
        List<Road> roads = m.roads();
        assertTrue(roads.get(0).bridge());
        assertEquals(2, roads.get(0).layer());
        // layer=1 ohne bridge-Angabe und ohne Querung bleibt am Boden (siehe layerWithoutACrossing…).
        assertFalse(roads.get(1).bridge(), "layer>0 ohne Querung gilt als Brücke");
        assertFalse(roads.get(2).bridge(), "bridge=no");
        assertEquals(List.of("tram", "hedge", "rail"), m.lines().stream().map(CityModel.Line::kind).toList());
        assertTrue(m.lines().get(2).bridge());
    }

    @Test
    void layerWithoutACrossingStaysOnTheGroundWithLamps() {
        // Prüfbefund 2026-10-29: layer=1 heißt laut OSM-Wiki nicht Brücke (Rampen, Wege über Tunneln, Dämme). Ohne
        // Querung wurde die Straße bisher mit Rampe und Geländer gehoben und bekam keine Laternen.
        String raised = way(92, "\"highway\":\"residential\",\"name\":\"Lindenweg\",\"layer\":\"1\"", -80, 0,
                80, 0);
        CityModel m = model(raised);
        assertFalse(m.roads().getFirst().bridge(), "layer=1 ohne Querung als Brücke gehoben");
        assertEquals(1, m.roads().getFirst().layer(), "layer bleibt erhalten");
        Map<String, String> w = world(plan(raised));
        assertTrue(columns(w, "lantern").size() >= 8, "keine Laternen auf der Straße mit layer=1");
        assertTrue(Set.of("gray_concrete", "white_concrete").contains(w.get("0/0/0")), "Fahrbahn am Boden");
        assertFalse(Set.of("gray_concrete", "stone_bricks", "smooth_stone_slab[type=bottom]").contains(w.get("0/1/0")),
                "Fahrbahn gehoben: " + w.get("0/1/0"));
        // Mit Querung bleibt es eine Brücke – auch ohne bridge-Angabe: über einer Straße und über einem Bach.
        CityModel over = model(raised, way(93, "\"highway\":\"residential\"", 0, -40, 0, 40));
        assertTrue(over.roads().getFirst().bridge(), "layer=1 über einer Straße ist eine Brücke");
        CityModel overWater = model(raised, way(94, "\"waterway\":\"stream\"", 30, -40, 30, 40));
        assertTrue(overWater.roads().getFirst().bridge(), "layer=1 über einem Bach ist eine Brücke");
        // Ein Gleis mit layer=1 ohne Querung ebenso.
        assertFalse(model(way(95, "\"railway\":\"rail\",\"layer\":\"1\"", 0, 70, 50, 70)).lines().getFirst()
                .bridge());
    }

    @Test
    void gardenWithAFenceOnTheSameWayKeepsItsLawn() {
        // Prüfbefund 2026-10-29: Fläche und Begrenzung auf demselben Way (leisure=garden + barrier=fence). Vorher kam
        // nur der Zaun, die Fläche fiel weg.
        String garden = way(96, "\"leisure\":\"garden\",\"barrier\":\"fence\"", 0, 0, 10, 0, 10, 10, 0, 10, 0, 0);
        CityModel m = model(garden);
        assertEquals(List.of("grass"), m.areas().stream().map(CityModel.Area::kind).toList());
        assertEquals(List.of("fence"), m.lines().stream().map(CityModel.Line::kind).toList());
        Map<String, String> w = world(plan(garden));
        assertEquals("grass_block", w.get("5/0/5"), "Rasen im Garten");
        assertTrue(String.valueOf(w.get("0/1/5")).startsWith("oak_fence"), "Zaun am Rand: " + w.get("0/1/5"));
    }

    @Test
    void bridgeHeightIsTheSameInEveryStage() {
        // Die Brücke kreuzt die Straße bei x = 0; die Etappe x ≥ 10 sieht die Straße nicht, baut aber dieselbe Höhe.
        String below = way(90, "\"highway\":\"residential\"", 0, -40, 0, 40);
        String bridge = way(91, "\"highway\":\"tertiary\",\"bridge\":\"yes\"", -30, 0, 30, 0);
        CityModel m = model(below, bridge);
        CityPlanner planner = new CityPlanner();
        Map<String, String> east = world(planner.plan(m, new CityPlanner.Clip(10, -100, 100, 100, 1000), 3_000_000));
        Map<String, String> whole = world(plan(below, bridge));
        assertEquals(whole.get("15/5/0"), east.get("15/5/0"));
        assertEquals("gray_concrete", east.get("15/5/0"));
    }

    @Test
    void roadsWithoutNamesOrPartsKeepWorking() {
        // Altes Modell ohne Linien und ohne Brückenangaben plant wie bisher.
        Building b = new Building(1, List.of(new P(0, 0), new P(9, 0), new P(9, 9), new P(0, 9), new P(0, 0)), 7,
                "flat", "house", "");
        CityModel m = new CityModel("T", 0, 0, 50, List.of(b), List.of(), List.of(), List.of());
        CityPlanner.Plan p = CityPlanner.plan(m, 100_000);
        assertEquals(1, p.buildings());
        assertTrue(p.signs().isEmpty());
        assertTrue(p.sections().stream().allMatch(s -> s.basis().isEmpty()));
    }
}
