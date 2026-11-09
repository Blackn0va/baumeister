package com.blackn0va.baumeister.earth;

/**
 * Örtliche Verzerrung einer Projektion, numerisch über ±10 m nach Nord und Ost – für /bm wo und die Tests. Dieselbe
 * Rechnung wie die Messprobe der Hauptsitzung (Measure.local), nur mittig statt einseitig differenziert.
 *
 * Threads: zustandslos.
 */
public final class EarthMetrics {

    /** Meter je Grad auf der Kugel mit R = 6 371 008,8 m. */
    private static final double M_PER_DEG = 6_371_008.8 * Math.PI / 180;
    private static final double STEP_M = 10;

    /**
     * @param h            Blöcke je Meter in Nord-Süd-Richtung
     * @param k            Blöcke je Meter in Ost-West-Richtung
     * @param northAzimuth Richtung von geografisch Nord in Grad, im Uhrzeigersinn ab Minecraft-Nord (−z), −180..180
     */
    public record Local(double h, double k, double northAzimuth) {
    }

    private EarthMetrics() {
    }

    public static Local local(EarthProjection p, double lat, double lon) {
        double dLat = STEP_M / M_PER_DEG;
        double latN = Math.min(90, lat + dLat);
        double latS = Math.max(-90, lat - dLat);
        double[] n = p.toWorld(latN, lon);
        double[] s = p.toWorld(latS, lon);
        double cos = Math.max(Math.cos(Math.toRadians(lat)), 1e-9);
        double dLon = STEP_M / (M_PER_DEG * cos);
        double[] e = p.toWorld(lat, wrap(lon + dLon));
        double[] w = p.toWorld(lat, wrap(lon - dLon));
        double nx = n[0] - s[0];
        double nz = n[1] - s[1];
        double h = Math.hypot(nx, nz) / ((latN - latS) * M_PER_DEG);
        double k = Math.hypot(e[0] - w[0], e[1] - w[1]) / (2 * STEP_M);
        double az = Math.toDegrees(Math.atan2(nx, -nz));
        return new Local(h, k, az);
    }

    /** Minecraft-Blickwinkel (yaw), der nach geografisch Nord schaut: 180 = −z, −90 = +x. */
    public static float yawTowardsNorth(Local l) {
        return (float) (180 + l.northAzimuth());
    }

    private static double wrap(double lon) {
        double l = ((lon + 180) % 360 + 360) % 360 - 180;
        return l == -180 && lon > 0 ? 180 : l;
    }
}
