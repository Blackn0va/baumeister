package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.WorldTiles.GeoBox;
import com.blackn0va.baumeister.WorldTiles.Tile;
import com.blackn0va.baumeister.earth.EarthProjection;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.Projector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Kachelrechnung des Weltbaus gegen eine erfundene, lineare Erdprojektion (mit und ohne Netzlücke). */
class WorldTilesTest {

    private static final double LAT0 = 49.8451;
    private static final double LON0 = 7.8676;
    private static final double M_LAT = 110_540.0;
    private static final double M_LON = 111_320.0 * Math.cos(Math.toRadians(LAT0));

    /** Gleichabständig um (LAT0, LON0), x Osten, z Süden; gap: alles westlich von x = 0 ist eine Netzlücke. */
    private static EarthProjection projection(boolean gap) {
        return new EarthProjection() {
            @Override
            public double[] toWorld(double lat, double lon) {
                double x = (lon - LON0) * M_LON;
                return gap && x < 0 ? null : new double[] {x, -(lat - LAT0) * M_LAT};
            }

            @Override
            public double[] toGeo(double x, double z) {
                return gap && x < 0 ? null : new double[] {LAT0 - z / M_LAT, LON0 + x / M_LON};
            }
        };
    }

    @Test
    void tileOfAPointRoundsDownAlsoBelowZero() {
        assertEquals(new Tile(0, 0), WorldTiles.at(0, 0));
        assertEquals(new Tile(0, 0), WorldTiles.at(1023.9, 1023.9));
        assertEquals(new Tile(1, 0), WorldTiles.at(1024, 5));
        assertEquals(new Tile(-1, -1), WorldTiles.at(-0.1, -1));
        assertEquals(new Tile(-2, 3), WorldTiles.at(-1025, 3072));
        Tile t = new Tile(-2, 3);
        assertEquals(-2048, t.x0());
        assertEquals(3072 + 512, t.centerZ());
    }

    @Test
    void boxCoversTheTileAndItsMargin() {
        Tile t = new Tile(2, -3);
        GeoBox b = WorldTiles.box(projection(false), t, 64);
        assertNotNull(b);
        assertEquals(LAT0 - (t.z0() + 1023 + 64) / M_LAT, b.south(), 1e-9);
        assertEquals(LAT0 - (t.z0() - 64) / M_LAT, b.north(), 1e-9);
        assertEquals(LON0 + (t.x0() - 64) / M_LON, b.west(), 1e-9);
        assertEquals(LON0 + (t.x0() + 1023 + 64) / M_LON, b.east(), 1e-9);
        // Gegenrichtung: Die vier Ecken des Rechtecks liegen außerhalb der Kachel samt Rand – nichts fehlt.
        double[] sw = projection(false).toWorld(b.south(), b.west());
        double[] ne = projection(false).toWorld(b.north(), b.east());
        assertTrue(sw[0] <= t.x0() - 63 && sw[1] >= t.z0() + 1023 + 63, sw[0] + "/" + sw[1]);
        assertTrue(ne[0] >= t.x0() + 1023 + 63 && ne[1] <= t.z0() - 63, ne[0] + "/" + ne[1]);
    }

    @Test
    void tileInAGapHasNoBoxButItsNeighbourKeepsOne() {
        EarthProjection p = projection(true);
        assertNull(WorldTiles.box(p, new Tile(-1, 0), 64));
        GeoBox b = WorldTiles.box(p, new Tile(0, 0), 64);
        assertNotNull(b, "Kachel direkt neben der Lücke fiel mit weg");
        // Westlich reicht die Abfrage nur bis an die Lücke, nicht 64 m hinein.
        assertEquals(LON0, b.west(), 1e-9);
        assertEquals(LON0 + (1023 + 64) / M_LON, b.east(), 1e-9);
    }

    /**
     * Über lon 180 stetig wie Airocean im Pazifik: x = 0 liegt auf der Datumsgrenze bei lat −17,8 (Fidschi),
     * x &gt; 0 ist östlich davon (lon −179,99 …), x &lt; 0 westlich (lon 179,99 …).
     */
    private static EarthProjection pacific() {
        double lat0 = -17.8;
        double mLon = 111_320.0 * Math.cos(Math.toRadians(lat0));
        return new EarthProjection() {
            @Override
            public double[] toWorld(double lat, double lon) {
                double d = ((lon - 180) % 360 + 540) % 360 - 180;
                return new double[] {d * mLon, -(lat - lat0) * M_LAT};
            }

            @Override
            public double[] toGeo(double x, double z) {
                double lon = ((180 + x / mLon) % 360 + 540) % 360 - 180;
                return new double[] {lat0 - z / M_LAT, lon};
            }
        };
    }

    @Test
    void tileAcrossTheDateLineIsQueriedInTwoNarrowParts() {
        EarthProjection p = pacific();
        // Prüfbefund 2026-10-28: vorher West −179,99x / Ost 179,99x – ein Streifen um die ganze Erde. Danach (bis
        // 2026-10-29) gar keine Abfrage: Kacheln über der Datumsgrenze wurden nie gebaut. Jetzt zwei schmale Teile.
        for (Tile t : List.of(new Tile(0, 0), new Tile(-1, 0), new Tile(0, -1))) {
            GeoBox b = WorldTiles.box(p, t, 64);
            assertNotNull(b, "Kachel " + t + " über der Datumsgrenze wird nicht gebaut");
            assertTrue(b.crossesDateline(), b.toString());
            List<GeoBox> parts = b.parts();
            assertEquals(2, parts.size(), parts.toString());
            double width = 0;
            for (GeoBox q : parts) {
                assertTrue(q.west() >= -180 && q.east() <= 180 && q.west() < q.east(), q.toString());
                assertTrue(q.east() - q.west() < 0.02, "Teil um die ganze Erde: " + q);
                assertEquals(b.south(), q.south());
                assertEquals(b.north(), q.north());
                width += q.east() - q.west();
            }
            assertEquals(b.east() - b.west(), width, 1e-9, "beide Teile zusammen = das ganze Rechteck");
        }
        // Daneben, ganz auf einer Seite: kleine Rechtecke wie überall sonst.
        GeoBox east = WorldTiles.box(p, new Tile(1, 0), 64);
        assertNotNull(east);
        assertTrue(east.west() > -180 && east.east() < -179.9 && east.east() - east.west() < 0.02, east.toString());
        GeoBox west = WorldTiles.box(p, new Tile(-2, 0), 64);
        assertNotNull(west);
        assertTrue(west.west() > 179.9 && west.east() < 180 && west.east() - west.west() < 0.02, west.toString());
        assertEquals(1, east.parts().size());
        // Und die Automatik reiht die Kachel über der Datumsgrenze ein, statt sie als Lücke zu merken.
        Set<Long> gaps = new HashSet<>();
        List<Tile> todo = WorldTiles.todo(List.of(new Tile(0, 0), new Tile(1, 0)), p, Set.of(), gaps);
        assertEquals(List.of(new Tile(0, 0), new Tile(1, 0)), todo);
        assertEquals(Set.of(), gaps);
    }

    @Test
    void realAiroceanTileAtFijiCrossesTheDateLineInTwoParts() {
        // Mit der echten Projektion: die Kachel, in der lat −17,8 / lon 180 liegt (Prüfbefund 2026-10-28).
        EarthProjection p = new com.blackn0va.baumeister.earth.AiroceanProjection();
        double[] w = p.toWorld(-17.8, 180);
        Tile t = WorldTiles.at(w[0], w[1]);
        GeoBox b = WorldTiles.box(p, t, WorldTiles.MARGIN);
        assertNotNull(b);
        assertTrue(b.crossesDateline(), b.toString());
        for (GeoBox q : b.parts()) {
            assertTrue(q.east() - q.west() < 0.05 && q.north() - q.south() < 0.05, q.toString());
        }
    }

    @Test
    void implausiblyLargeBoxIsRejected() {
        // Kaputte Projektion: 100 m je Grad statt ~110 km – die Kachel wäre ein Rechteck von 10 × 10 Grad.
        EarthProjection broken = new EarthProjection() {
            @Override
            public double[] toWorld(double lat, double lon) {
                return new double[] {lon * 100, -lat * 100};
            }

            @Override
            public double[] toGeo(double x, double z) {
                return new double[] {-z / 100, x / 100};
            }
        };
        assertNull(WorldTiles.box(broken, new Tile(0, 0), 64));
    }

    @Test
    void aroundAPlaceNearestTileComesFirst() {
        assertEquals(List.of(new Tile(0, 0)), WorldTiles.around(100, 100, 0));
        // Ecke von vier Kacheln, 100 m Umkreis: alle vier, nächste Kachelmitte zuerst, Gleichstand nach tx.
        assertEquals(List.of(new Tile(0, 0), new Tile(0, 1), new Tile(1, 0), new Tile(1, 1)),
                WorldTiles.around(1000, 1000, 100));
    }

    @Test
    void aroundCoversTheCircleAndNothingFarther() {
        double cx = 300;
        double cz = -700;
        int r = 2500;
        List<Tile> tiles = WorldTiles.around(cx, cz, r);
        Set<Tile> set = new HashSet<>(tiles);
        assertEquals(tiles.size(), set.size());
        for (double x = cx - r; x <= cx + r; x += 97) {
            for (double z = cz - r; z <= cz + r; z += 89) {
                if (Math.hypot(x - cx, z - cz) <= r) {
                    assertTrue(set.contains(WorldTiles.at(x, z)), "fehlt: " + WorldTiles.at(x, z));
                }
            }
        }
        for (Tile t : tiles) {
            double nx = Math.max(t.x0(), Math.min(cx, t.x0() + 1023));
            double nz = Math.max(t.z0(), Math.min(cz, t.z0() + 1023));
            assertTrue(Math.hypot(nx - cx, nz - cz) <= r, "zu weit: " + t);
        }
        for (int i = 1; i < tiles.size(); i++) {
            assertTrue(dist(tiles.get(i - 1), cx, cz) <= dist(tiles.get(i), cx, cz) + 1e-9, "Reihenfolge bei " + i);
        }
    }

    private static double dist(Tile t, double x, double z) {
        return Math.hypot(t.centerX() + 0.5 - x, t.centerZ() + 0.5 - z);
    }

    @Test
    void neighbourhoodOfPlayersIsThreeByThreeWithoutDuplicatesNearestFirst() {
        List<Tile> one = WorldTiles.near(List.of(new double[] {10, 10}), 1);
        assertEquals(9, one.size());
        assertEquals(new Tile(0, 0), one.getFirst());
        // Zwei Spieler in derselben Kachel: dieselben neun, keine doppelt.
        assertEquals(9, WorldTiles.near(List.of(new double[] {10, 10}, new double[] {900, 900}), 1).size());
        // Zwei weit auseinander: 18 Kacheln; die Kachel des näher an ihrer Mitte stehenden Spielers zuerst, und die
        // Kacheln beider Spieler gemischt nach Abstand – nicht erst alle des einen.
        List<Tile> two = WorldTiles.near(List.of(new double[] {10, 10}, new double[] {5000, 10}), 1);
        assertEquals(18, two.size());
        assertEquals(new Tile(4, 0), two.get(0));
        assertTrue(two.indexOf(new Tile(0, 0)) < two.indexOf(new Tile(5, 0)), two.toString());
        assertTrue(two.indexOf(new Tile(0, 0)) < two.indexOf(new Tile(-1, 0)), two.toString());
        assertEquals(25, WorldTiles.near(List.of(new double[] {-1, -1}), 2).size());
        assertEquals(List.of(new Tile(-1, -1)), WorldTiles.near(List.of(new double[] {-1, -1}), 0));
    }

    @Test
    void todoSkipsFinishedTilesAndTilesInAGap() {
        EarthProjection p = projection(true);
        List<Tile> near = WorldTiles.near(List.of(new double[] {10, 10}), 1);
        Set<Long> gaps = new HashSet<>();
        List<Tile> todo = WorldTiles.todo(near, p, Set.of(new Tile(0, 0).key()), gaps);
        // tx = −1 liegt in der Lücke (3 Kacheln), (0,0) ist fertig: bleiben 5, Reihenfolge wie vorher.
        assertEquals(List.of(new Tile(0, -1), new Tile(0, 1), new Tile(1, 0), new Tile(1, -1), new Tile(1, 1)), todo);
        assertEquals(Set.of(new Tile(-1, -1).key(), new Tile(-1, 0).key(), new Tile(-1, 1).key()), gaps);
        // Zweiter Durchlauf: Lücken stehen schon in gaps, Ergebnis gleich.
        assertEquals(todo, WorldTiles.todo(near, p, Set.of(new Tile(0, 0).key()), gaps));
    }

    @Test
    void stagesCoverTheTileExactlyOnceMiddleFirst() {
        List<CityPlanner.Clip> clips = WorldTiles.stages();
        assertEquals(16, clips.size());
        for (int x = -512; x < 512; x += 31) {
            for (int z = -512; z < 512; z += 29) {
                int fx = x;
                int fz = z;
                assertEquals(1, clips.stream().filter(c -> c.contains(fx, fz)).count(), x + "/" + z);
            }
        }
        assertTrue(clips.stream().noneMatch(c -> c.contains(512, 0) || c.contains(0, 512)));
        for (int i = 0; i < 4; i++) {
            CityPlanner.Clip c = clips.get(i);
            assertTrue(c.x0() >= -256 && c.x1() <= 256 && c.z0() >= -256 && c.z1() <= 256, "Mitte nicht zuerst: " + c);
        }
    }

    @Test
    void projectorPutsAWorldPointOnTheSameBlockInEveryTile() {
        EarthProjection p = projection(true);
        Tile a = new Tile(0, 0);
        Tile b = new Tile(1, 0);
        Projector pa = WorldTiles.projector(p, a);
        Projector pb = WorldTiles.projector(p, b);
        double[] geo = p.toGeo(1023.6, 77.2);
        P inA = pa.project(geo[0], geo[1]);
        P inB = pb.project(geo[0], geo[1]);
        assertEquals(new P(1024 - 512, 77 - 512), inA);
        assertEquals(inA.x() + a.centerX(), inB.x() + b.centerX());
        assertEquals(inA.z() + a.centerZ(), inB.z() + b.centerZ());
        assertNull(pa.project(LAT0, LON0 - 0.01), "Punkt in der Lücke");
    }

    @Test
    void doneListLinesRoundTrip() {
        Tile t = new Tile(-3, 17);
        assertEquals(t, WorldTiles.parseLine(WorldTiles.formatLine(t)));
        assertEquals("-3 17", WorldTiles.formatLine(t));
        assertNull(WorldTiles.parseLine("# Kommentar"));
        assertNull(WorldTiles.parseLine("   "));
        assertEquals(new Tile(4, 5), WorldTiles.parseLine("  4\t5 "));
        assertThrows(IllegalArgumentException.class, () -> WorldTiles.parseLine("4"));
        assertThrows(IllegalArgumentException.class, () -> WorldTiles.parseLine("a b"));
    }
}
