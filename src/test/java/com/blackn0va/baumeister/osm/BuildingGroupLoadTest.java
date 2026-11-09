package com.blackn0va.baumeister.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.blackn0va.baumeister.osm.CityModel.Building;
import com.blackn0va.baumeister.osm.CityModel.P;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Gebäudegruppen (Section.basis) unter Last: Großstädte mit 3D-Kartierung (München-Mitte, Köln) haben Hunderte bis
 * Tausende building:part je Kachel. Vorher (Prüfbefund 2026-10-29) suchte groupBasis in JEDER Etappe für jedes eigene
 * Gebäude über alle Teile deren Gebäude – und jede Suche lief über alle Gebäude: Gebäude × Teile × Gebäude. Gemessen am
 * Münchner Cache mit 300 Teilen: 57–86 s statt 11–14 s für 25 Etappen; mit 1 000 Teilen nach 5 min nicht fertig.
 */
class BuildingGroupLoadTest {

    private static List<P> rect(int x0, int z0, int x1, int z1) {
        return List.of(new P(x0, z0), new P(x1, z0), new P(x1, z1), new P(x0, z1), new P(x0, z0));
    }

    /** 2 025 kleine Häuser im 14er-Raster (630 × 630 m), in jedem vierten ein Turm als building:part: 506 Teile. */
    private static CityModel denseCity() {
        List<Building> buildings = new ArrayList<>();
        long id = 1;
        int parts = 0;
        for (int i = 0; i < 45; i++) {
            for (int j = 0; j < 45; j++) {
                int x = -315 + i * 14;
                int z = -315 + j * 14;
                buildings.add(new Building(id++, rect(x, z, x + 8, z + 8), 4, "flat", "house", ""));
                if ((i * 45 + j) % 4 == 0) {
                    buildings.add(new Building(id++, rect(x + 1, z + 1, x + 4, z + 4), 8, "flat", "tower", "", "",
                            "", 0, true));
                    parts++;
                }
            }
        }
        assertEquals(507, parts);
        return new CityModel("Test", 0, 0, 450, buildings, List.of(), List.of(), List.of());
    }

    @Test
    void manyBuildingPartsDoNotStallTheStages() {
        CityModel city = denseCity();
        // Neun Etappen à 256 m wie im Stadtbau (CityStages). Mit dem alten Aufwand: weit über eine Minute.
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            CityPlanner planner = new CityPlanner();
            int grouped = 0;
            for (int sx = -1; sx <= 1; sx++) {
                for (int sz = -1; sz <= 1; sz++) {
                    CityPlanner.Clip clip = new CityPlanner.Clip(sx * 256 - 128, sz * 256 - 128, sx * 256 + 128,
                            sz * 256 + 128, 1_000);
                    for (CityPlanner.Section s : planner.plan(city, clip, 20_000_000).sections()) {
                        if (s.building() && !s.basis().isEmpty()) {
                            grouped++;
                        }
                    }
                }
            }
            // Jeder Turm teilt sich die Basis mit seinem Haus (507 Häuser + 507 Türme) – jedes genau in einer Etappe.
            assertEquals(1014, grouped);
        });
    }
}
