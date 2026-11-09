package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.terrain.DemGenerator;
import com.blackn0va.baumeister.terrain.ElevationTiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Säulen der Erdwelt aus synthetischen Höhen: Land, Meer, Lücke, Steilwand, Kappung – ohne Server. */
class EarthTerrainTest {

    /** Projektion ohne Verzerrung um (48°, 11°): 1 Block = 1 m; x ≥ 1 000 000 ist eine Lücke. */
    private static final EarthProjection FLAT = new EarthProjection() {
        @Override
        public double[] toWorld(double lat, double lon) {
            return new double[] {(lon - 11) * 111_320 * Math.cos(Math.toRadians(48)), (48 - lat) * 110_540};
        }

        @Override
        public double[] toGeo(double x, double z) {
            if (x >= 1_000_000) {
                return null;
            }
            return new double[] {48 - z / 110_540, 11 + x / (111_320 * Math.cos(Math.toRadians(48)))};
        }
    };

    private static EarthTerrain terrain(Elevation e, LocalRelief relief) {
        Elevation none = (lat, lon) -> {
            throw new IOException("keine groben Daten");
        };
        return new EarthTerrain(FLAT, e, none, new HeightModel(relief, 200), 64, -2032, 2032);
    }

    /** Schreibt jeden gesetzten Block in eine Tabelle (Chunk-Koordinaten). */
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

    @Test
    void lowlandIsPaintedAtItsRealHeightWithBedrock64Below() {
        EarthTerrain t = terrain((lat, lon) -> 400, (lat, lon) -> new double[] {350, 450});
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        assertEquals(Material.GRASS_BLOCK, r.at(5, 400, 5));
        assertEquals(Material.DIRT, r.at(5, 399, 5));
        assertEquals(Material.BEDROCK, r.at(5, 336, 5));
        assertEquals(null, r.at(5, 335, 5), "unter dem Grundgestein bleibt Luft");
        assertEquals(null, r.at(5, 401, 5));
        assertEquals(0, r.count(Material.WATER));
        assertEquals(400, t.surfaceY(5, 5));
    }

    @Test
    void deepSeaIsRaisedTo200MetresOfWater() {
        EarthTerrain t = terrain((lat, lon) -> -4000, (lat, lon) -> new double[] {-4000, -3000});
        EarthTerrain.Column c = t.column(8.5, 8.5);
        assertEquals(-4000, c.realElevation());
        assertEquals(-200, c.top());
        assertEquals(0, c.seaY());
        assertTrue(c.underwater());
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        assertEquals(Material.SAND, r.at(3, -200, 3));
        assertEquals(Material.WATER, r.at(3, -199, 3));
        assertEquals(Material.WATER, r.at(3, 0, 3));
        assertEquals(null, r.at(3, 1, 3));
        assertEquals(256L * 200, r.count(Material.WATER));
    }

    @Test
    void steepSeaFloorIsCoveredWithWaterToo() {
        // Stufe im Meeresgrund: westlich −150 m, östlich −20 m – die Kante ist „steil“ und bekam vorher kein Wasser.
        Elevation e = (lat, lon) -> FLAT.toWorld(lat, lon)[0] < 8 ? -150 : -20;
        EarthTerrain t = terrain(e, (lat, lon) -> new double[] {-150, -20});
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        for (int y = -19; y <= 0; y++) {
            assertEquals(Material.WATER, r.at(8, y, 4), "Luft im Meer bei y " + y);
        }
        assertEquals(Material.WATER, r.at(7, -149, 4));
    }

    @Test
    void gapsInTheNetAreSea() {
        EarthTerrain t = terrain((lat, lon) -> 500, (lat, lon) -> new double[] {500, 500});
        EarthTerrain.Column c = t.column(2_000_000.5, 0.5);
        assertTrue(c.gap());
        assertEquals(-200, c.top());
        assertEquals(0, c.seaY());
        Recorder r = new Recorder();
        t.paint(2_000_000 / 16, 0, r);
        assertEquals(256L * 200, r.count(Material.WATER));
    }

    @Test
    void cliffKeepsBedrockBelowTheFootOfTheWall() {
        // Westlich von x = 8 auf 100 m, östlich auf 300 m: 200 m hohe Wand mitten im Chunk.
        Elevation e = (lat, lon) -> FLAT.toWorld(lat, lon)[0] < 8 ? 100 : 300;
        EarthTerrain t = terrain(e, (lat, lon) -> new double[] {100, 300});
        Recorder r = new Recorder();
        t.paint(0, 0, r);
        // Säule x = 8 ist die Wandkante: Grundgestein 64 unter dem Fuß der Wand, nicht unter ihrer Krone.
        assertEquals(Material.BEDROCK, r.at(8, 100 - 64, 4));
        for (int y = 100 - 63; y <= 300; y++) {
            assertTrue(r.at(8, y, 4) != null, "Loch in der Wand bei y " + y);
        }
        assertEquals(Material.BEDROCK, r.at(12, 300 - 64, 4));
    }

    @Test
    void summitsAboveTheCeilingAreClippedAndReported() {
        EarthTerrain t = terrain((lat, lon) -> 9000, (lat, lon) -> new double[] {9000, 9000});
        EarthTerrain.Column c = t.column(0.5, 0.5);
        // Relief 0 im Fenster: shift = 9000 − 1990 → Oberfläche genau 1 990, nichts gekappt.
        assertEquals(1990, c.top());
        assertFalse(c.clippedTop());
        EarthTerrain steep = terrain((lat, lon) -> 9000, (lat, lon) -> new double[] {0, 9000});
        EarthTerrain.Column k = steep.column(0.5, 0.5);
        assertTrue(k.clippedTop());
        // Höchster Block 2031, darüber nichts; die Oberkante bleibt eine Lage darunter (wie DemGenerator).
        assertEquals(2030, k.top());
    }

    @Test
    void missingDataIsFlaggedAsEstimated() {
        EarthTerrain t = terrain((lat, lon) -> {
            throw new IOException("HTTP 503");
        }, (lat, lon) -> new double[] {0, 0});
        EarthTerrain.Column c = t.column(0.5, 0.5);
        assertTrue(c.estimated());
        assertEquals(0, c.top());
    }

    @Test
    void aChunkWithADeadTileSourceAsksEachTileAtMostOnce(@TempDir Path dir) {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        ElevationTiles.TileSource dead = (zoom, tx, ty) -> {
            calls.computeIfAbsent(zoom + "/" + tx + "/" + ty, k -> new AtomicInteger()).incrementAndGet();
            throw new IOException("offline");
        };
        // Echte Kachelquellen wie im Plugin (fein Zoom 15, grob Zoom 10, Relief über die groben Kacheln).
        ElevationTiles fine = new ElevationTiles(dir, 15, 64, dead);
        ElevationTiles coarse = new ElevationTiles(dir, 10, 64, dead);
        EarthTerrain t = new EarthTerrain(FLAT, fine::elevation, coarse::elevation,
                new HeightModel(new TileRelief(coarse), 200), 64, -2032, 2032);
        t.paint(0, 0, new Recorder());
        int total = calls.values().stream().mapToInt(AtomicInteger::get).sum();
        // Vorher (ohne Fehlersperre) 972 Abrufe: 324 Säulen × fein, grob, Relief.
        assertTrue(calls.values().stream().allMatch(n -> n.get() == 1), calls.toString());
        assertTrue(total <= 8, "Abrufe für einen Chunk: " + total);
        assertTrue(t.column(5.5, 5.5).estimated());
    }

    @Test
    void snowLineFallsWithLatitude() {
        assertEquals(5000, EarthTerrain.snowLine(0));
        assertEquals(5000, EarthTerrain.snowLine(-25));
        assertEquals(0, EarthTerrain.snowLine(78));
        assertTrue(EarthTerrain.snowLine(48.82) > 2000 && EarthTerrain.snowLine(48.82) < 2600);
    }
}
