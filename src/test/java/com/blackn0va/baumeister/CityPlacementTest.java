package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.earth.EarthProjection;
import com.blackn0va.baumeister.earth.ProjectionChoice;

import org.junit.jupiter.api.Test;

/** Städte an ihrer echten Stelle (Wunsch 2026-10-30). */
class CityPlacementTest {

    private static final EarthProjection AIROCEAN = ProjectionChoice.byName(ProjectionChoice.AIROCEAN);

    @Test
    void munichAndNurembergLieWhereTheyBelongRelativeToEachOther() {
        int[] muc = CityPlacement.real(AIROCEAN, 48.1374, 11.5755, 5000).orElseThrow();
        int[] nue = CityPlacement.real(AIROCEAN, 49.4521, 11.0767, 5000).orElseThrow();
        double km = Math.hypot(muc[0] - nue[0], muc[1] - nue[1]) / 1000;
        assertTrue(km > 140 && km < 170, "Abstand wie in echt (~151 km), gemessen " + km);
        assertTrue(nue[1] < muc[1], "Nürnberg liegt nördlich (kleineres z)");
        assertTrue(nue[0] < muc[0], "und etwas westlich");
    }

    @Test
    void sameSpotAsInTheEarthWorld() {
        double[] earth = AIROCEAN.toWorld(49.8451, 7.8676);
        int[] city = CityPlacement.real(AIROCEAN, 49.8451, 7.8676, 400).orElseThrow();
        assertEquals(Math.round(earth[0]), city[0]);
        assertEquals(Math.round(earth[1]), city[1]);
    }

    @Test
    void aTownWhoseRadiusWouldCrossTheWorldBorderIsRefused() {
        EarthProjection edge = new EarthProjection() {
            @Override
            public double[] toWorld(double lat, double lon) {
                return new double[] {29_999_000, 0};
            }

            @Override
            public double[] toGeo(double x, double z) {
                return null;
            }
        };
        assertTrue(CityPlacement.real(edge, 0, 0, 2000).isEmpty());
    }
}
