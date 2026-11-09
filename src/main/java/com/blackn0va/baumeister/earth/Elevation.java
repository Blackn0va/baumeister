package com.blackn0va.baumeister.earth;

import java.io.IOException;
import java.util.Arrays;

/**
 * Meereshöhe in Metern an (lat, lon) – im Plugin ElevationTiles::elevation, in Tests eine Formel.
 *
 * Threads: aus Weltgenerator-Threads aufgerufen, muss threadsicher sein. Fehler: IOException, wenn die Daten fehlen.
 */
@FunctionalInterface
public interface Elevation {

    double at(double lat, double lon) throws IOException;

    /**
     * Höhe samt den vier Rohpixeln, aus denen sie gemischt ist (WaterTable unterscheidet daran Wasser von Land).
     * Vorgabe für Quellen ohne Pixel (Formeln in Tests): alle vier = Ergebnis.
     */
    default double at(double lat, double lon, double[] corners) throws IOException {
        double e = at(lat, lon);
        if (corners != null) {
            Arrays.fill(corners, e);
        }
        return e;
    }
}
