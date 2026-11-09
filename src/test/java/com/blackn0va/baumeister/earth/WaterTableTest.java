package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.earth.WaterTable.Kind;
import com.blackn0va.baumeister.terrain.DemGenerator;
import com.blackn0va.baumeister.terrain.ElevationTiles;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Wasser je Säule (WaterTable) und im gemalten Chunk (EarthTerrain mit Wasserkarte): Senken trocken, Seen auf eigener
 * Höhe, Meer bleibt Meer – und die Oberkante bleibt Block für Block dieselbe wie ohne Wasserkarte.
 */
class WaterTableTest {

    private static final WaterTable.Basins OPEN = (lat, lon) -> true;
    private static final WaterTable.Basins CLOSED = (lat, lon) -> false;
    private static final WaterTable.Basins NEVER = (lat, lon) -> {
        throw new AssertionError("BasinMap darf hier nicht gefragt werden");
    };

    private static double[] px(double a, double b, double c, double d) {
        return new double[] {a, b, c, d};
    }

    @Test
    void oceanWithoutDepthDataBecomesAWaterSurfaceAtZero() throws IOException {
        // Mittelmeer, Biskaya, Fjorde: Terrarium liefert bei Zoom 15 genau 0 – vorher eine Sandebene auf y 0.
        assertEquals(Kind.SURFACE, WaterTable.decide(0, px(0, 0, 0, 0), false, NEVER, 0, 0));
        assertEquals(Kind.NONE, WaterTable.decide(0.2, px(0, 0, 0, 0.8), false, NEVER, 0, 0));
    }

    @Test
    void landAboveSeaLevelIsNeverAskedAboutBasins() throws IOException {
        assertEquals(Kind.NONE, WaterTable.decide(425.3, px(425.1, 425.4, 425.2, 425.6), false, NEVER, 0, 0));
    }

    @Test
    void deepWaterInTheOpenSeaIsSea() throws IOException {
        // Kaputte Pixel im Atlantik (−16 109,11) sind Bruchwerte, aber tief.
        assertEquals(Kind.FILL, WaterTable.decide(-5772.6, px(0, -16109.11, 0, -16109.11), true, OPEN, 0, 0));
    }

    @Test
    void brokenPixelsInAClosedLakeGetWaterOnlyUpToTheLake() throws IOException {
        // Kaspisches Meer 15/20980/12164: Fläche −29, dazwischen Pixelzeilen um −9 935. Vorher Meer bis 0 (DEEP_FILL
        // vor der Senken-Prüfung) – eine Wasserwand 29 Blöcke über dem See.
        WaterTable.Water w = WaterTable.decideWater(-4982, px(-29, -29, -9935.2, -9935.2), true, CLOSED, 0, 0, null);
        assertEquals(Kind.LAKE, w.kind());
        assertEquals(-29, w.level());
        assertTrue(w.lake());
        // Nur kaputte Pixel ringsum: keine Seehöhe bekannt – trocken statt Meer bis 0.
        assertEquals(Kind.NONE, WaterTable.decide(-9935, px(-9935, -9935, -9935, -9935), true, CLOSED, 0, 0));
    }

    @Test
    void integerLandInAClosedBasinNeedsAFlatWindowToBecomeALake() throws IOException {
        // Atyrau (SRTM): Land ist ganzzahlig, alle vier Ecken auf −26 – aber das Fenster drumherum ist nicht flach.
        WaterTable.Window rough = () -> Double.NaN;
        assertEquals(Kind.NONE, WaterTable.decideWater(-26, px(-26, -26, -26, -26), true, CLOSED, 0, 0, rough)
                .kind());
        assertEquals(Kind.NONE, WaterTable.decideWater(-26.2, px(-26, -27, -26, -26), true, CLOSED, 0, 0, rough)
                .kind());
        // Flaches Fenster auf −29, die Säule liegt aber am Ufer (eine Ecke −28): trocken auf Seehöhe.
        WaterTable.Window lake = () -> -29;
        assertEquals(Kind.NONE, WaterTable.decideWater(-28.7, px(-29, -28, -29, -29), true, CLOSED, 0, 0, lake)
                .kind());
        assertEquals(Kind.SURFACE, WaterTable.decideWater(-29, px(-29, -29, -29, -29), true, CLOSED, 0, 0, lake)
                .kind());
    }

    @Test
    void zeroSurfaceDoesNotNeedAFlatWindow() throws IOException {
        // Mississippi bei New Orleans: genau 0 mit eingestreuten Bruchwerten – das Fenster wäre nie flach, das Wasser
        // muss trotzdem bleiben (mit Fenster gemessen 39,1 → 4,6 % Wasser).
        assertEquals(Kind.SURFACE, WaterTable.decideWater(0, px(0, 0, 0, 0), false, NEVER, 0, 0, () -> Double.NaN)
                .kind());
    }

    @Test
    void brokenPixelInTheOpenSeaIsWaterEvenNextToTheShore() throws IOException {
        // IJsselmeer 15/16875/10749: zwei kaputte Pixel und zwei Uferpixel (Bruchwerte um 0,1) – vorher eine trockene
        // Grube bis zum Meeresboden mitten im See.
        assertEquals(Kind.FILL, WaterTable.decide(-20.75, px(-4816.56, 0.10, -4816.56, 0.10), true, OPEN, 0, 0));
    }

    @Test
    void flatLevelReadsTheWindowAroundTheColumn() throws IOException {
        int z = ElevationTiles.ZOOM;
        double lat = 47.1;
        double lon = 51.9;
        int gx = (int) Math.floor(ElevationTiles.tileX(lon, z) * 256 - 0.5);
        int gy = (int) Math.floor(ElevationTiles.tileY(lat, z) * 256 - 0.5);
        // Flach auf −29 mit einer kaputten Zeile: Seehöhe −29.
        PixelWorld flat = new PixelWorld((x, y) -> y == gy ? -9935.2f : -29f);
        assertEquals(-29, new WaterTable(CLOSED, flat, z).flatLevel(lat, lon));
        // Ein abweichendes Pixel am Fensterrand (5 Pixel neben der Ecke) genügt, um „nicht flach“ zu sein.
        PixelWorld edge = new PixelWorld((x, y) -> x == gx + 6 && y == gy - 5 ? -28f : -29f);
        assertTrue(Double.isNaN(new WaterTable(CLOSED, edge, z).flatLevel(lat, lon)));
        // Ein Pixel knapp außerhalb des Fensters stört nicht.
        PixelWorld outside = new PixelWorld((x, y) -> x == gx + 7 ? -28f : -29f);
        assertEquals(-29, new WaterTable(CLOSED, outside, z).flatLevel(lat, lon));
        // Bruchwerte (Land) sind nie flach.
        PixelWorld land = new PixelWorld((x, y) -> -29.4f);
        assertTrue(Double.isNaN(new WaterTable(CLOSED, land, z).flatLevel(lat, lon)));
    }

    @Test
    void polderBehindTheDikeStaysDry() throws IOException {
        // Haarlemmermeer: offen (über Kanäle mit der Nordsee verbunden), aber Land – Bruchwerte um −4 m.
        assertEquals(Kind.NONE, WaterTable.decide(-4.3, px(-4.12, -4.41, -4.37, -4.29), true, OPEN, 0, 0));
    }

    @Test
    void shallowSeaWithDepthDataStaysSea() throws IOException {
        // Wattenmeer −5 m, ganzzahlige Tiefenblöcke.
        assertEquals(Kind.FILL, WaterTable.decide(-5, px(-5, -5, -5, -5), true, OPEN, 0, 0));
        // Nordsee am Strand: Übergang zweier Tiefenblöcke.
        assertEquals(Kind.FILL, WaterTable.decide(-4.6, px(-4, -5, -4, -5), true, OPEN, 0, 0));
    }

    @Test
    void smoothedShoreBarelyBelowZeroStaysSea() throws IOException {
        // Puget Sound: −0,6 m als Bruchwert – zu flach, um einen Polder anzunehmen.
        assertEquals(Kind.FILL, WaterTable.decide(-0.6, px(-0.6, -0.55, -0.62, -0.58), true, OPEN, 0, 0));
    }

    @Test
    void lakeInAClosedBasinGetsItsOwnSurface() throws IOException {
        assertEquals(Kind.SURFACE, WaterTable.decide(-412, px(-412, -412, -412, -412), true, CLOSED, 0, 0));
    }

    @Test
    void dryBasinStaysDry() throws IOException {
        // Badwater (Death Valley): Bruchwerte um −78 m, geschlossen.
        assertEquals(Kind.NONE, WaterTable.decide(-78.2, px(-78.13, -78.36, -78.02, -78.4), true, CLOSED, 0, 0));
    }

    @Test
    void oneIntegerPixelDoesNotMakeLandWet() {
        assertFalse(WaterTable.waterLike(px(-4, -4.4, -4.3, -4.2)));
        assertFalse(WaterTable.waterLike(px(-4, -5, -4.3, -4.2)));
        assertTrue(WaterTable.waterLike(px(-4, -5, -6, -4.2)));
    }

    @Test
    void missingFineDataFallsBackToTheOldSea() throws IOException {
        assertEquals(Kind.FILL, WaterTable.decide(-10, null, true, OPEN, 0, 0));
        assertEquals(Kind.NONE, WaterTable.decide(-10, null, true, CLOSED, 0, 0));
    }

    // ------------------------------------------------------------------ im gemalten Chunk

    /** Projektion ohne Verzerrung um (48°, 11°): 1 Block = 1 m. */
    private static final EarthProjection FLAT = new EarthProjection() {
        @Override
        public double[] toWorld(double lat, double lon) {
            return new double[] {(lon - 11) * 111_320 * Math.cos(Math.toRadians(48)), (48 - lat) * 110_540};
        }

        @Override
        public double[] toGeo(double x, double z) {
            return new double[] {48 - z / 110_540, 11 + x / (111_320 * Math.cos(Math.toRadians(48)))};
        }
    };

    private static EarthTerrain terrain(Elevation e, WaterTable water) {
        Elevation none = (lat, lon) -> {
            throw new IOException("keine groben Daten");
        };
        LocalRelief relief = (lat, lon) -> new double[] {-100, 100};
        return new EarthTerrain(FLAT, e, none, new HeightModel(relief, 200), 64, -2032, 2032, water);
    }

    private static final class Recorder implements DemGenerator.Sink {
        final Map<String, Material> blocks = new HashMap<>();

        @Override
        public void set(int x, int y, int z, Material m) {
            blocks.put(x + "/" + y + "/" + z, m);
        }

        @Override
        public void fill(int x, int y0, int z, int y1, Material m) {
            for (int y = y0; y < y1; y++) {
                set(x, y, z, m);
            }
        }

        Material at(int x, int y, int z) {
            return blocks.get(x + "/" + y + "/" + z);
        }

        long count(Material m) {
            return blocks.values().stream().filter(v -> v == m).count();
        }
    }

    /** Senke: x 0..15 des Chunks auf −80,4 m (Bruchwert, Land), ringsum +100 m. */
    private static final Elevation BASIN = (lat, lon) -> {
        double x = FLAT.toWorld(lat, lon)[0];
        double z = FLAT.toWorld(lat, lon)[1];
        return x >= 0 && x < 16 && z >= 0 && z < 16 ? -80.4 : 100.3;
    };

    @Test
    void inlandDepressionIsPaintedWithoutWater() {
        EarthTerrain t = terrain(BASIN, new WaterTable(CLOSED));
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        assertEquals(0, r.count(Material.WATER), "Death Valley darf nicht voll Wasser laufen");
        assertNull(r.at(5, -79, 5));
        assertTrue(t.column(5.5, 5.5).dryBasin());
        assertFalse(t.column(5.5, 5.5).underwater());
    }

    @Test
    void waterTableNeverMovesTheSurface() {
        // Gedächtnis: Höhenmodell einer erkundeten Erdwelt nicht ändern – nur das Wasser darf sich ändern.
        Elevation mixed = (lat, lon) -> {
            double x = FLAT.toWorld(lat, lon)[0];
            double z = FLAT.toWorld(lat, lon)[1];
            if (x < 20) {
                return Math.floor(-30 + z / 7);  // ganzzahlig: Meeresboden
            }
            if (x < 40) {
                return -4.3 + Math.sin(z) * 0.2;  // Polder
            }
            return x < 60 ? 0 : 12.7 + z / 9;  // Meer ohne Tiefe, dann Land
        };
        EarthTerrain plain = terrain(mixed, null);
        EarthTerrain wet = terrain(mixed, new WaterTable(OPEN));
        for (int x = -16; x < 80; x++) {
            for (int z = -16; z < 48; z++) {
                assertEquals(plain.surfaceY(x, z), wet.surfaceY(x, z), "Oberkante verschoben bei " + x + "/" + z);
            }
        }
    }

    @Test
    void lakeSurfaceReplacesTheTopBlock() {
        Elevation lake = (lat, lon) -> {
            double x = FLAT.toWorld(lat, lon)[0];
            double z = FLAT.toWorld(lat, lon)[1];
            return x >= 2 && x < 14 && z >= 2 && z < 14 ? -69 : -60.6;  // Saltonsee, Ufer als Bruchwert
        };
        EarthTerrain t = terrain(lake, new WaterTable(CLOSED));
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        assertEquals(Material.WATER, r.at(8, -69, 8), "Seefläche auf −69");
        assertNull(r.at(8, -68, 8));
        assertEquals(Material.SAND, r.at(8, -70, 8));
        assertEquals(12L * 12, r.count(Material.WATER));
    }

    @Test
    void openSeaWithoutDepthGetsWaterAtZero() {
        EarthTerrain t = terrain((lat, lon) -> 0, new WaterTable(NEVER));
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        assertEquals(Material.WATER, r.at(3, 0, 3));
        assertEquals(Material.SAND, r.at(3, -1, 3));
        assertEquals(256L, r.count(Material.WATER));
        assertTrue(t.column(3.5, 3.5).underwater());
    }

    @Test
    void waterNeverStandsNextToAirAfterTheShoreRule() {
        // Kanal auf −1 m (ganzzahlig, Meer bis 0) direkt neben einem Polder auf −4,3 m, ohne Deich in den Daten.
        Elevation e = (lat, lon) -> FLAT.toWorld(lat, lon)[0] < 6 ? -1 : -4.3;
        EarthTerrain t = terrain(e, new WaterTable(OPEN));
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        int leaks = 0;
        for (Map.Entry<String, Material> b : r.blocks.entrySet()) {
            if (b.getValue() != Material.WATER) {
                continue;
            }
            String[] p = b.getKey().split("/");
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : nb) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16 && r.at(nx, y, nz) == null) {
                    leaks++;
                }
            }
        }
        assertEquals(0, leaks, "Wasserblöcke neben Luft");
        assertEquals(Material.WATER, r.at(2, 0, 8), "der Kanal selbst behält sein Wasser");
        assertEquals(Material.GRASS_BLOCK, r.at(5, 0, 8), "am Polder ein Deich statt Wasser");
        assertNotEquals(Material.WATER, r.at(6, 0, 8));
    }

    /** Rohpixel bei Zoom 15 aus einer Formel; Höhe bilinear wie ElevationTiles.elevation. */
    private static final class PixelWorld implements WaterTable.Raw, Elevation {
        interface F {
            float at(int gx, int gy);
        }

        final F f;

        PixelWorld(F f) {
            this.f = f;
        }

        @Override
        public float[] tile(int tx, int ty) {
            float[] t = new float[256 * 256];
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    t[y * 256 + x] = f.at(tx * 256 + x, ty * 256 + y);
                }
            }
            return t;
        }

        @Override
        public double at(double lat, double lon) {
            return at(lat, lon, null);
        }

        @Override
        public double at(double lat, double lon, double[] corners) {
            double fx = ElevationTiles.tileX(lon, ElevationTiles.ZOOM) * 256 - 0.5;
            double fy = ElevationTiles.tileY(lat, ElevationTiles.ZOOM) * 256 - 0.5;
            int px = (int) Math.floor(fx);
            int py = (int) Math.floor(fy);
            double ax = fx - px;
            double ay = fy - py;
            double h00 = f.at(px, py);
            double h10 = f.at(px + 1, py);
            double h01 = f.at(px, py + 1);
            double h11 = f.at(px + 1, py + 1);
            if (corners != null) {
                corners[0] = h00;
                corners[1] = h10;
                corners[2] = h01;
                corners[3] = h11;
            }
            return (h00 * (1 - ax) + h10 * ax) * (1 - ay) + (h01 * (1 - ax) + h11 * ax) * ay;
        }
    }

    /** Wasserblöcke, die seitlich an Luft grenzen (innerhalb des Chunks). */
    private static int leaks(Recorder r) {
        int leaks = 0;
        for (Map.Entry<String, Material> b : r.blocks.entrySet()) {
            if (b.getValue() != Material.WATER) {
                continue;
            }
            String[] p = b.getKey().split("/");
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : nb) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16 && r.at(nx, y, nz) == null) {
                    leaks++;
                }
            }
        }
        return leaks;
    }

    @Test
    void caspianProfileHasNoWaterAboveTheLake() {
        // Geschlossenes Becken, Seefläche −29, quer durch den Chunk eine kaputte Pixelzeile um −9 935.
        int gy = (int) Math.floor(ElevationTiles.tileY(48 - 8 / 110_540.0, ElevationTiles.ZOOM) * 256 - 0.5);
        PixelWorld caspian = new PixelWorld((x, y) -> y == gy ? -9935.2f : -29f);
        EarthTerrain t = terrain(caspian, new WaterTable(CLOSED, caspian, ElevationTiles.ZOOM));
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        long above = r.blocks.entrySet().stream().filter(b -> b.getValue() == Material.WATER)
                .filter(b -> Integer.parseInt(b.getKey().split("/")[1]) > -29).count();
        assertEquals(0, above, "Wasser über dem Seespiegel −29");
        assertEquals(0, leaks(r), "Wasserblöcke neben Luft");
        assertEquals(Material.WATER, r.at(8, -29, 8), "über der kaputten Zeile steht der See");
        assertEquals(Material.WATER, r.at(8, -150, 8), "die kaputte Zeile ist bis zum Boden Wasser");
        assertEquals(Material.WATER, r.at(3, -29, 2), "Seefläche");
    }

    @Test
    void srtmLandNextToALakeHasNoPuddles() {
        // Atyrau: Land ganzzahlig, in Flecken von 3 × 3 Pixeln gleich hoch (−27..−24) – die vier Ecken einer Säule
        // sind oft gleich, ein Fenster von 12 × 12 nie. Vorher Wasserflächen auf vier Höhen.
        PixelWorld atyrau = new PixelWorld((x, y) -> -27f + Math.floorMod(Math.floorDiv(x, 3) * 7
                + Math.floorDiv(y, 3) * 13, 4));
        EarthTerrain t = terrain(atyrau, new WaterTable(CLOSED, atyrau, ElevationTiles.ZOOM));
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        assertEquals(0, r.count(Material.WATER), "Pfützen auf SRTM-Land");
    }

    @Test
    void puddleNextToALowerLakeIsDikedNotLeaking() {
        // Pfütze (Seefläche −26) neben dem See auf −29: vorher galt der nasse Nachbar als dicht.
        EarthTerrain.Column[][] cols = new EarthTerrain.Column[18][18];
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                cols[x][z] = x < 9 ? lakeColumn(-26) : lakeColumn(-29);
            }
        }
        int[][] dike = EarthTerrain.shore(cols);
        assertEquals(-26, dike[7][5], "die höhere Pfütze am Seeufer wird Land");
        assertEquals(Integer.MIN_VALUE, dike[8][5], "der See selbst bleibt");
        assertEquals(Integer.MIN_VALUE, dike[3][5], "Pfütze ohne tieferen Nachbarn bleibt");
        // Meer gegen Meer (Stufe von einem Block) bleibt wie vorher ohne Deich.
        EarthTerrain.Column[][] sea = new EarthTerrain.Column[18][18];
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                sea[x][z] = new EarthTerrain.Column(0, 0, -20, -20, 0, -20, x < 9 ? 1 : 0, false, false, false,
                        false);
            }
        }
        assertEquals(Integer.MIN_VALUE, EarthTerrain.shore(sea)[7][5]);
    }

    private static EarthTerrain.Column lakeColumn(int level) {
        return new EarthTerrain.Column(0, 0, level, level, 0, level, level, false, false, false, false, true, false,
                true);
    }

    @Test
    void paintReportsEstimatedChunks() {
        Elevation offline = (lat, lon) -> {
            throw new IOException("offline");
        };
        assertTrue(terrain(offline, new WaterTable(OPEN)).paintChecked(0, 0, new Recorder()));
        assertFalse(terrain((lat, lon) -> 300.5, new WaterTable(OPEN)).paintChecked(0, 0, new Recorder()));
    }
}
