package com.blackn0va.baumeister.earth;

/**
 * Unterbrochene Sinusprojektion: 60 Streifen zu je 6° Länge, jeder mit eigenem Mittelmeridian (−177, −171, …, 177).
 * Formel: Sinusoidal (Sanson-Flamsteed), Snyder USGS PP 1395, Kap. 30 (gemeinfrei).
 *
 * Warum es sie neben Airocean gibt (Messung der Hauptsitzung 2026-10-28, Probe proj/aeqd, sinus6.txt): flächentreu,
 * Norden überall genau −z, Längen höchstens 0,09 % daneben, Winkel im Median 0,6°. Dafür entstehen zu den Polen hin
 * keilförmige Lücken zwischen den Streifen (in Hepberg 200 km bis zur nächsten Naht), Kontinente zerfallen in Streifen.
 * Die Lücken liefert toGeo als null – die Erdwelt füllt sie mit Meer.
 *
 * Threads: zustandslos.
 */
public final class Sinus6Projection implements EarthProjection {

    static final double R = 6_371_008.8;
    static final int STRIPS = 60;
    static final double WIDTH_DEG = 6.0;

    @Override
    public double[] toWorld(double lat, double lon) {
        double l = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        int i = (int) Math.floor((l + 180.0) / WIDTH_DEG);
        i = Math.max(0, Math.min(STRIPS - 1, i));
        double centerDeg = -180.0 + WIDTH_DEG * (i + 0.5);
        double phi = Math.toRadians(lat);
        double x = R * Math.toRadians(centerDeg) + R * Math.toRadians(l - centerDeg) * Math.cos(phi);
        double z = -R * phi;
        return new double[] {x, z};
    }

    @Override
    public double[] toGeo(double x, double z) {
        double phi = -z / R;
        if (Math.abs(phi) > Math.PI / 2) {
            return null;
        }
        double lonRaw = Math.toDegrees(x / R);
        int i = (int) Math.floor((lonRaw + 180.0) / WIDTH_DEG);
        // Gemessen: lat 0, lon ±180 landet durch Rundung bei lonRaw = −180 − 1e−14 → i = −1 → null.
        // Rundungsrand deshalb auf den Randstreifen klemmen; die dl-Prüfung unten fängt echte Außenpunkte.
        if (i == -1 && lonRaw > -180.0 - 1e-9) {
            i = 0;
        }
        if (i == STRIPS && lonRaw < 180.0 + 1e-9) {
            i = STRIPS - 1;
        }
        if (i < 0 || i >= STRIPS) {
            return null;
        }
        double centerDeg = -180.0 + WIDTH_DEG * (i + 0.5);
        double cosPhi = Math.cos(phi);
        double dx = x - R * Math.toRadians(centerDeg);
        double dl;
        if (cosPhi < 1e-12) {
            if (Math.abs(dx) > 1e-6) {
                return null;
            }
            dl = 0.0;
        } else {
            dl = Math.toDegrees(dx / (R * cosPhi));
        }
        if (Math.abs(dl) > WIDTH_DEG / 2 + 1e-12) {
            // Lücke zwischen zwei Streifen
            return null;
        }
        return new double[] {Math.toDegrees(phi), centerDeg + dl};
    }
}
