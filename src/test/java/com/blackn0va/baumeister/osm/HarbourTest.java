package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.blackn0va.baumeister.osm.CityModel.Area;
import com.blackn0va.baumeister.osm.CityModel.P;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

/**
 * Hafen als Wasser-Relation (2026-11-01): Sydney hat an Circular Quay keine Küstenlinie, der Hafen ist die Relation
 * „Port Jackson“ (natural=water, water=harbour). Erfundene Relation: Außenring aus zwei Teilstücken, eine Insel innen.
 */
class HarbourTest {

    /** Rechteck lat 0…0,002, lon 0…0,004 aus zwei Teilstücken; Insel um lat 0,001 / lon 0,002. */
    private static String relation(String water) {
        return "{\"elements\":[{\"type\":\"relation\",\"id\":15522136,\"tags\":{\"natural\":\"water\",\"type\":"
                + "\"multipolygon\",\"water\":\"" + water + "\",\"name\":\"Port Jackson\"},\"members\":["
                + "{\"type\":\"way\",\"ref\":1,\"role\":\"outer\",\"geometry\":[{\"lat\":0.0,\"lon\":0.0},"
                + "{\"lat\":0.0,\"lon\":0.004},{\"lat\":0.002,\"lon\":0.004}]},"
                + "{\"type\":\"way\",\"ref\":2,\"role\":\"outer\",\"geometry\":[{\"lat\":0.002,\"lon\":0.004},"
                + "{\"lat\":0.002,\"lon\":0.0},{\"lat\":0.0,\"lon\":0.0}]},"
                + "{\"type\":\"way\",\"ref\":3,\"role\":\"inner\",\"geometry\":[{\"lat\":0.0009,\"lon\":0.0019},"
                + "{\"lat\":0.0009,\"lon\":0.0021},{\"lat\":0.0011,\"lon\":0.0021},{\"lat\":0.0011,\"lon\":0.0019},"
                + "{\"lat\":0.0009,\"lon\":0.0019}]}]}]}";
    }

    private static CityModel parse(String water) {
        return OsmImport.parse(relation(water), "Test", 0.001, 0.002, 500);
    }

    @Test
    void harbourRelationBecomesSeaWithTheIslandAsHole() {
        List<Area> areas = parse("harbour").areas();
        assertEquals(1, areas.size(), "zwei Teilstücke ergeben EINEN Ring");
        Area a = areas.getFirst();
        assertEquals("sea", a.kind());
        assertEquals(1, a.holes().size(), "die Insel");
    }

    @Test
    void lakeRelationStaysOrdinaryWater() {
        assertEquals("water", parse("lake").areas().getFirst().kind());
    }

    @Test
    void marineAreaWithoutCoastlineMakesSeaAndKeepsTheIslandDry() {
        Area a = parse("harbour").areas().getFirst();
        Set<P> cells = CityPlanner.fill(a.outline(), a.holes(), CityPlanner.Clip.all());
        SeaMask m = SeaMask.of(List.of(), cells, -300, -200, 300, 200);
        assertNotNull(m);
        assertFalse(m.sea(0, 0), "Inselmitte (Projektionsmitte) bleibt Land");
        assertTrue(m.sea(150, 0), "Hafenwasser östlich der Insel");
        assertFalse(m.sea(280, 0), "außerhalb des Hafens Land");
        assertTrue(m.toLand(150, 0) > m.toLand(20, 0), "mitten im Hafen tiefer als an der Insel");
    }

    @Test
    void plannerMarksTheHarbourAsSeaOnlyWhenShoreIsOn() {
        CityModel city = parse("harbour");
        CityPlanner.Clip clip = new CityPlanner.Clip(100, -60, 220, 60, Integer.MAX_VALUE / 2);
        CityPlanner shore = new CityPlanner();
        shore.shore(true);
        Set<Integer> mats = mats(shore.plan(city, clip, 10_000_000).sections());
        assertTrue(mats.stream().anyMatch(CityPlanner::isSeaMark), "Weltbau: Meer am Meeresspiegel");
        CityPlanner flat = new CityPlanner();
        Set<Integer> flatMats = mats(flat.plan(city, clip, 10_000_000).sections());
        assertFalse(flatMats.stream().anyMatch(CityPlanner::isSeaMark), "/bm stadt: keine Marker");
        assertFalse(flatMats.contains(CityPlanner.BEACH_MARK));
    }

    private static Set<Integer> mats(List<Section> sections) {
        Set<Integer> out = new HashSet<>();
        for (Section s : sections) {
            for (Cell c : s.cells()) {
                out.add(c.mat());
            }
        }
        return out;
    }
}
