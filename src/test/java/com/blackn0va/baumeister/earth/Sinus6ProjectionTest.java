package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** Sinus-Streifen: Rundweg, Norden oben, Lücken, Ausdehnung. */
class Sinus6ProjectionTest {

    private static final EarthProjection P = new Sinus6Projection();

    @Test
    void roundTripStaysBelowOneCentimetre() {
        double worst = 0;
        for (double lat = -89.5; lat <= 89.5; lat += 1) {
            for (double lon = -180; lon < 180; lon += 0.7) {
                worst = Math.max(worst, AiroceanProjectionTest.roundTrip(P, lat, lon));
            }
        }
        Random r = new Random(6);
        for (int i = 0; i < 20_000; i++) {
            worst = Math.max(worst, AiroceanProjectionTest.roundTrip(P, r.nextDouble() * 179 - 89.5,
                    r.nextDouble() * 360 - 180));
        }
        assertTrue(worst < 0.01, "größter Rundweg-Fehler " + worst + " m");
    }

    @Test
    void northIsUpOnEveryCentralMeridianAndNearlyUpElsewhere() {
        // Mittelmeridian des Streifens um Hepberg: 9° O.
        assertEquals(0, EarthMetrics.local(P, 48.82, 9).northAzimuth(), 1e-6);
        // Am Streifenrand neigen sich die Meridiane: höchstens atan(3° · sin φ) ≈ 2,6° bei 60°.
        assertEquals(0, EarthMetrics.local(P, 48.82, 11.46).northAzimuth(), 3);
        assertEquals(0, EarthMetrics.local(P, 60, 11.99).northAzimuth(), 3);
    }

    @Test
    void wedgesBetweenStripsAreGaps() {
        // Grenze der Streifen 6..12 und 12..18 bei 12° O, auf 80° N: dazwischen liegt ein Keil ohne Erde.
        double[] left = P.toWorld(80, 11.999);
        double[] right = P.toWorld(80, 12.001);
        assertTrue(right[0] - left[0] > 100_000, "Keil " + (right[0] - left[0]) + " m");
        assertNull(P.toGeo((left[0] + right[0]) / 2, left[1]));
        assertNotNull(P.toGeo(left[0], left[1]));
    }

    @Test
    void wholeEarthFitsInsideTheWorldBorder() {
        double maxAbs = 0;
        for (double lat = -90; lat <= 90; lat += 0.5) {
            for (double lon = -180; lon <= 180; lon += 0.5) {
                double[] w = P.toWorld(lat, lon);
                maxAbs = Math.max(maxAbs, Math.max(Math.abs(w[0]), Math.abs(w[1])));
            }
        }
        assertTrue(maxAbs <= 29_999_000, "größte Koordinate " + maxAbs);
    }
}
