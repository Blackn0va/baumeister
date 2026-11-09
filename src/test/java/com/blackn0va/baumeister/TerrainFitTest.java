package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;
import com.blackn0va.baumeister.osm.OsmImport;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Höhenrechnung des Stadtbaus auf Gelände (CityJob mit HeightField) gegen erfundene Höhenfelder: Boden folgt den
 * Säulen, Häuser stehen auf ihrem höchsten Geländepunkt mit Sockel talseitig, Bäume hängen an ihrem Stamm.
 */
class TerrainFitTest {

    /** Hang nach Osten: jede Spalte einen Block höher (1:1), y = 60 + x. */
    private static final HeightField SLOPE = (x, z) -> 60 + x;
    private static final int BASE_X = 100;
    private static final int BASE_Z = -40;

    /** Haus 5 × 3: Boden y=0, Wände y=1..3 auf dem Rand, Dach y=4. */
    private static Section house() {
        List<Cell> cells = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 3; z++) {
                cells.add(new Cell(x, 0, z, 1));
                for (int y = 1; y <= 3; y++) {
                    if (x == 0 || x == 4 || z == 0 || z == 2) {
                        cells.add(new Cell(x, y, z, 2));
                    }
                }
                cells.add(new Cell(x, 4, z, 3));
            }
        }
        return new Section("Haus", 2, 1, cells, true);
    }

    @Test
    void groundFollowsEveryColumn() {
        Section road = new Section("Boden 0/0", 0, 0, List.of(new Cell(0, 0, 0, 1), new Cell(3, 0, 0, 1),
                new Cell(3, 1, 0, 2), new Cell(3, -1, 7, 4)));
        List<Cell> fit = TerrainFit.fit(road, SLOPE, BASE_X, BASE_Z);
        assertEquals(List.of(new Cell(0, 160, 0, 1), new Cell(3, 163, 0, 1), new Cell(3, 164, 0, 2),
                new Cell(3, 162, 7, 4)), fit);
    }

    @Test
    void buildingStandsOnItsHighestPointWithAPlinthDownhill() {
        Section h = house();
        // Grundriss x = 0..4 → Gelände 160..164, Basis = Maximum = 164.
        assertEquals(164, TerrainFit.buildingBase(h, SLOPE, BASE_X, BASE_Z));
        List<Cell> fit = TerrainFit.fit(h, SLOPE, BASE_X, BASE_Z);
        Map<String, Integer> byPos = new HashMap<>();
        int plinth = 0;
        for (Cell c : fit) {
            if (c.mat() == TerrainFit.PLINTH) {
                plinth++;
                int top = 60 + BASE_X + c.x();
                assertTrue(c.y() > top && c.y() < 164, "Sockel bei " + c + " nicht zwischen Gelände und Basis");
            } else {
                byPos.merge(c.x() + "/" + c.z(), 1, Integer::sum);
            }
        }
        // Säule x=0: 161..163 (3), x=1: 162..163 (2), x=2: 163 (1), x=3 und x=4: keiner – je 3 Säulen in z.
        assertEquals((3 + 2 + 1) * 3, plinth);
        // Das Haus selbst bleibt ein Stück: Boden überall auf 164, Dach überall auf 168, keine Stufe.
        for (Cell c : fit) {
            if (c.mat() == 1) {
                assertEquals(164, c.y(), c.toString());
            }
            if (c.mat() == 3) {
                assertEquals(168, c.y(), c.toString());
            }
        }
        assertEquals(h.cells().size() + plinth, fit.size());
        // Sockel zuerst und von unten nach oben – wie der Rest einer Etappe.
        for (int i = 1; i < plinth; i++) {
            assertTrue(fit.get(i - 1).y() <= fit.get(i).y());
        }
        assertEquals(TerrainFit.PLINTH, fit.getFirst().mat());
    }

    @Test
    void nothingSticksIntoTheHillside() {
        // Steiler Hang in z statt x: Basis über dem höchsten Punkt – kein Hausblock unter der Geländeoberkante.
        HeightField steep = (x, z) -> 70 - 3 * (z - BASE_Z);
        List<Cell> fit = TerrainFit.fit(house(), steep, BASE_X, BASE_Z);
        for (Cell c : fit) {
            int top = steep.surfaceY(BASE_X + c.x(), BASE_Z + c.z());
            assertTrue(c.y() > top || c.mat() == 1 && c.y() == top, "im Berg: " + c + " Gelände " + top);
        }
    }

    @Test
    void partAboveTheGroundGetsNoPlinth() {
        // Turmhelm mit Unterkante über dem Schiff (min_height): keine Zelle in y=0, also kein Sockel darunter.
        Section helm = new Section("Turm", 0, 0, List.of(new Cell(0, 20, 0, 1), new Cell(1, 20, 0, 1),
                new Cell(0, 21, 0, 2)), true);
        List<Cell> fit = TerrainFit.fit(helm, SLOPE, BASE_X, BASE_Z);
        assertEquals(3, fit.size());
        assertTrue(fit.stream().noneMatch(c -> c.mat() == TerrainFit.PLINTH));
        assertEquals(161 + 20, fit.getFirst().y());
    }

    @Test
    void treeCrownHangsOnItsTrunk() {
        // Baum bei (10, 10): die ganze Krone (±2) liegt in einer Bodenkachel (32 × 32), also in einem Abschnitt.
        CityModel m = new CityModel("T", 0, 0, 50, List.of(), List.of(), List.of(),
                List.of(new CityModel.Tree(10, 10)));
        CityPlanner.Plan p = CityPlanner.plan(m, 10_000);
        assertEquals(1, p.sections().size());
        Section s = p.sections().getFirst();
        List<Cell> fit = TerrainFit.fit(s, SLOPE, BASE_X, BASE_Z);
        int trunkTop = 60 + BASE_X + 10;
        assertEquals(s.cells().size(), fit.size());
        for (int i = 0; i < fit.size(); i++) {
            // Jede Zelle des Baums – Stamm und Laub, auch zwei Spalten weiter hangaufwärts – über dem Stammfuß.
            assertEquals(trunkTop + s.cells().get(i).y(), fit.get(i).y(), s.cells().get(i).toString());
        }
        assertTrue(s.cells().stream().anyMatch(c -> c.anchorDx() == -2 && c.x() == 12), "Laub ohne Anker");
    }

    @Test
    void columnsWithoutTerrainAreLeftOut() {
        HeightField half = (x, z) -> x < BASE_X + 2 ? Integer.MIN_VALUE : 50;
        Section road = new Section("Boden", 0, 0, List.of(new Cell(0, 0, 0, 1), new Cell(1, 0, 0, 1),
                new Cell(2, 0, 0, 1)));
        assertEquals(List.of(new Cell(2, 50, 0, 1)), TerrainFit.fit(road, half, BASE_X, BASE_Z));
        // Haus halb über dem Nichts: Basis aus den Säulen mit Gelände, Sockel nur dort.
        List<Cell> fit = TerrainFit.fit(house(), half, BASE_X, BASE_Z);
        assertFalse(fit.isEmpty());
        assertTrue(fit.stream().noneMatch(c -> c.mat() == TerrainFit.PLINTH));
        assertTrue(fit.stream().filter(c -> c.mat() == 1).allMatch(c -> c.y() == 50));
        HeightField nothing = (x, z) -> Integer.MIN_VALUE;
        assertTrue(TerrainFit.fit(house(), nothing, BASE_X, BASE_Z).isEmpty());
    }

    @Test
    void flatTerrainBuildsExactlyLikeTheFixedHeight() throws Exception {
        // Gegenstück zum alten CityJob (y = baseY + y): Auf ebenem Gelände muss jede Zelle eines echten Plans genau
        // dort landen, ohne einen einzigen Sockelblock.
        CityModel city;
        try (InputStream in = TerrainFitTest.class.getResourceAsStream("/osm/bad-kreuznach-120m.json")) {
            city = OsmImport.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "Bad Kreuznach", 49.8451,
                    7.8676, 120);
        }
        CityPlanner.Plan p = CityPlanner.plan(city, 3_000_000);
        int baseY = 64;
        HeightField flat = (x, z) -> baseY;
        long cells = 0;
        for (Section s : p.sections()) {
            List<Cell> fit = TerrainFit.fit(s, flat, BASE_X, BASE_Z);
            assertEquals(s.cells().size(), fit.size(), s.label());
            for (int i = 0; i < fit.size(); i++) {
                Cell c = s.cells().get(i);
                assertEquals(new Cell(c.x(), baseY + c.y(), c.z(), c.mat()), fit.get(i));
            }
            cells += fit.size();
        }
        assertEquals(p.blocks(), cells);
        assertTrue(p.sections().stream().anyMatch(Section::building), "Planer markiert keine Gebäude");
        assertTrue(p.sections().stream().anyMatch(s -> !s.building()), "Planer markiert keinen Boden");
    }
}
