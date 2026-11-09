package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Airocean nach Gray mit Drehung: Rundweg, Nordrichtung in Hepberg, Ausdehnung. Vergleichswerte aus der Messprobe der
 * Hauptsitzung (proj/airocean/final_gray.txt, 2026-10-28); die Drehung darf an Maßstab und Rundweg nichts ändern.
 */
class AiroceanProjectionTest {

    private static final EarthProjection P = new AiroceanProjection();
    /** Weltgrenze von Minecraft (29 999 984) mit Abstand. */
    private static final double LIMIT = 29_999_000;

    static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_008.8 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    static double roundTrip(EarthProjection p, double lat, double lon) {
        double[] w = p.toWorld(lat, lon);
        double[] g = p.toGeo(w[0], w[1]);
        assertNotNull(g, "Rundweg fällt in eine Lücke: " + lat + "," + lon);
        return distanceMetres(lat, lon, g[0], g[1]);
    }

    @Test
    void roundTripStaysBelowOneCentimetre() {
        double worst = 0;
        for (double lat = -89.5; lat <= 89.5; lat += 1) {
            for (double lon = -180; lon < 180; lon += 1) {
                worst = Math.max(worst, roundTrip(P, lat, lon));
            }
        }
        Random r = new Random(20261028);
        for (int i = 0; i < 50_000; i++) {
            double lat = Math.toDegrees(Math.asin(2 * r.nextDouble() - 1));
            double lon = r.nextDouble() * 360 - 180;
            worst = Math.max(worst, roundTrip(P, lat, lon));
        }
        assertTrue(worst < 0.01, "größter Rundweg-Fehler " + worst + " m");
    }

    @Test
    void northPointsToMinusZInHepberg() {
        EarthMetrics.Local l = EarthMetrics.local(P, 48.82, 11.46);
        assertEquals(0, l.northAzimuth(), 0.5);
        // Nicht gespiegelt: Osten liegt rechts von Nord, also bei +x.
        double[] home = P.toWorld(48.82, 11.46);
        double[] east = P.toWorld(48.82, 11.47);
        assertTrue(east[0] - home[0] > 0, "Ost = +x");
    }

    @Test
    void rotationKeepsTheMeasuredScale() {
        // final_gray.txt: Hepberg h = 1,036668, k = 0,897597 (ungedreht gemessen).
        EarthMetrics.Local l = EarthMetrics.local(P, 48.82, 11.46);
        assertEquals(1.036668, l.h(), 0.002);
        assertEquals(0.897597, l.k(), 0.002);
        EarthMetrics.Local everest = EarthMetrics.local(P, 27.9881, 86.9250);
        assertEquals(0.941522, everest.h(), 0.002);
        assertEquals(1.007483, everest.k(), 0.002);
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
        assertTrue(maxAbs <= LIMIT, "größte Koordinate " + maxAbs);
        // Ungedreht reichte das Netz bis 21,98 Mio. in x und 9,55 Mio. in z – gedreht höchstens der Radius 23,97 Mio.
        assertTrue(maxAbs < 24_000_000, "größte Koordinate " + maxAbs);
    }

    @Test
    void outsideTheNetIsAGap() {
        assertNull(P.toGeo(29_000_000, 29_000_000));
        assertNull(P.toGeo(-29_000_000, 29_000_000));
        double[] home = P.toWorld(48.82, 11.46);
        assertNotNull(P.toGeo(home[0], home[1]));
    }

    @Test
    void vertexFixMovesCornersOnlyAFewMetres() {
        double metres = AiroceanProjection.VERTEX_SHIFT_MAX_RAD * AiroceanProjection.R_SPHERE;
        assertTrue(metres < 5, "Eck-Fix verschiebt " + metres + " m");
    }
}
