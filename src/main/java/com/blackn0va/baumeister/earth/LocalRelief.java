package com.blackn0va.baumeister.earth;

import java.io.IOException;

/**
 * Tiefster und höchster Punkt im Umkreis einer Stelle, mit Abstand abgeschwächt – Grundlage des Höhenbezugs
 * (HeightModel). Im Plugin TileRelief über grobe Kacheln, in Tests synthetisch.
 *
 * Threads: threadsicher. Fehler: IOException, wenn die Daten fehlen.
 */
@FunctionalInterface
public interface LocalRelief {

    /** {min, max} in Metern über dem Meer. */
    double[] minMax(double lat, double lon) throws IOException;
}
