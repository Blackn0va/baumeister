package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.OsmImport;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Etappenbau: Auslegung, Planen in Ausschnitten (echte OSM-Daten) und Rückgängig auf der Platte. */
class CityStagesTest {

    private static CityModel badKreuznach() throws Exception {
        try (InputStream in = CityStagesTest.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            return OsmImport.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "Bad Kreuznach", 49.8451,
                    7.8676, 120);
        }
    }

    @Test
    void layoutCoversTheCircleExactlyOnceAndStartsInTheMiddle() {
        for (int radius : new int[] {120, 700, 2500}) {
            List<CityStages.Stage> stages = CityStages.layout(radius);
            assertEquals(0, stages.getFirst().cx());
            assertEquals(0, stages.getFirst().cz());
            for (int x = -radius; x <= radius; x += 37) {
                for (int z = -radius; z <= radius; z += 41) {
                    if ((long) x * x + (long) z * z > (long) radius * radius) {
                        continue;
                    }
                    int fx = x;
                    int fz = z;
                    long hits = stages.stream().filter(s -> s.clip().contains(fx, fz)).count();
                    assertEquals(1, hits, "Punkt " + x + "/" + z + " bei Radius " + radius);
                }
            }
        }
    }

    @Test
    void stageLiesInsideItsMapTile() {
        for (CityStages.Stage s : CityStages.layout(3000)) {
            int[] box = CityStages.tileBox(s.tileX(), s.tileZ());
            CityPlanner.Clip c = s.clip();
            assertTrue(c.x0() >= box[0] && c.x1() <= box[2] && c.z0() >= box[1] && c.z1() <= box[3],
                    "Etappe " + s.cx() + "/" + s.cz() + " ragt aus Kachel " + s.tileX() + "/" + s.tileZ());
        }
    }

    @Test
    void stagesStayTogetherPerMapTileSoEachTileIsLoadedOnce() {
        List<CityStages.Stage> stages = CityStages.layout(2500);
        Set<Long> finished = new HashSet<>();
        long current = Long.MIN_VALUE;
        for (CityStages.Stage s : stages) {
            long tile = ((long) s.tileX() << 32) ^ (s.tileZ() & 0xffffffffL);
            if (tile != current) {
                assertFalse(finished.contains(tile), "Kachel " + s.tileX() + "/" + s.tileZ() + " zweimal");
                finished.add(current);
                current = tile;
            }
        }
        assertEquals(1, CityStages.layout(150).stream().map(s -> s.tileX() + "/" + s.tileZ()).distinct().count());
    }

    @Test
    void plannedInStagesEveryBuildingOnceAndNoGroundUnderHouses() throws Exception {
        CityModel city = badKreuznach();
        CityPlanner planner = new CityPlanner();
        // Kleine Etappen (40 m), damit viele Gebäude und Straßen über Etappengrenzen ragen.
        int stage = 40;
        int radius = 120;
        Map<String, Integer> buildingSections = new HashMap<>();
        Set<Long> houseColumns = new HashSet<>();
        Set<Long> groundColumns = new HashSet<>();
        Set<String> groundCells = new HashSet<>();
        List<String> palette = List.of();
        for (int x0 = -radius - stage; x0 <= radius; x0 += stage) {
            for (int z0 = -radius - stage; z0 <= radius; z0 += stage) {
                CityPlanner.Plan p = planner.plan(city, new CityPlanner.Clip(x0, z0, x0 + stage, z0 + stage, radius),
                        5_000_000);
                assertTrue(p.palette().size() >= palette.size() && p.palette().subList(0, palette.size())
                        .equals(palette), "Palette darf nur anwachsen");
                palette = p.palette();
                for (CityPlanner.Section s : p.sections()) {
                    boolean ground = s.label().startsWith("Boden");
                    if (!ground) {
                        buildingSections.merge(s.label() + "@" + s.cx() + "/" + s.cz(), 1, Integer::sum);
                    }
                    for (CityPlanner.Cell c : s.cells()) {
                        long col = ((long) c.x() << 32) ^ (c.z() & 0xffffffffL);
                        if (ground) {
                            groundColumns.add(col);
                            assertTrue(groundCells.add(c.x() + "/" + c.y() + "/" + c.z()), "Boden doppelt: " + c);
                        } else if (c.y() == 0) {
                            houseColumns.add(col);
                        }
                    }
                }
            }
        }
        buildingSections.forEach((k, n) -> assertEquals(1, n, "Gebäude mehrfach geplant: " + k));
        long inCircle = city.buildings().stream().filter(b -> {
            CityModel.P c = centroidOf(b.outline());
            return (long) c.x() * c.x() + (long) c.z() * c.z() <= (long) radius * radius;
        }).count();
        assertTrue(buildingSections.size() >= inCircle - 2 && buildingSections.size() <= inCircle,
                buildingSections.size() + " Gebäudeabschnitte, " + inCircle + " Gebäude im Kreis");
        houseColumns.retainAll(groundColumns);
        assertTrue(houseColumns.isEmpty(), houseColumns.size() + " Bodenzellen unter Häusern");
    }

    private static CityModel.P centroidOf(List<CityModel.P> ring) {
        long sx = 0;
        long sz = 0;
        for (CityModel.P p : ring) {
            sx += p.x();
            sz += p.z();
        }
        return new CityModel.P((int) (sx / ring.size()), (int) (sz / ring.size()));
    }

    @Test
    void undoSpoolRoundTripsAcrossPartsNewestFirst(@TempDir Path tmp) throws Exception {
        UndoSpool spool = new UndoSpool(tmp.resolve("bau"));
        int n = UndoSpool.PART * 2 + 1234;
        for (int i = 0; i < n; i++) {
            spool.record(i, i % 300 - 64, -i, i % 3 == 0 ? "minecraft:air" : "minecraft:grass_block[snowy=false]");
        }
        spool.finish();
        assertEquals(n, spool.count());
        List<Path> parts = spool.partsNewestFirst();
        assertEquals(3, parts.size());
        UndoSpool.Part last = UndoSpool.read(parts.getFirst());
        assertEquals(1234, last.size());
        int i = n - 1;
        assertEquals(i, last.x()[last.size() - 1]);
        assertEquals(-i, last.z()[last.size() - 1]);
        assertEquals(i % 300 - 64, last.y()[last.size() - 1]);
        UndoSpool.Part first = UndoSpool.read(parts.getLast());
        assertEquals(UndoSpool.PART, first.size());
        assertEquals("minecraft:air", first.state()[0]);
        assertEquals("minecraft:grass_block[snowy=false]", first.state()[1]);
        spool.delete();
        assertFalse(java.nio.file.Files.exists(tmp.resolve("bau")));
    }
}
