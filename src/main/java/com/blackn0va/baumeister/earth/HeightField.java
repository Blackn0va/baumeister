package com.blackn0va.baumeister.earth;

/**
 * Geländeoberkante einer Welt: Blockkoordinate y des obersten festen Blocks an (x, z), berechnet aus denselben Daten
 * wie der Weltgenerator – ohne Weltzugriff, threadsicher. Integer.MIN_VALUE, wenn dort kein Gelände ist.
 */
public interface HeightField {

    int surfaceY(int x, int z);

    /**
     * Blockkoordinate y des Meeresspiegels an (x, z) – in der Erdwelt verschoben um den örtlichen Höhenbezug
     * (EarthTerrain: round(−shift)). Für die Küste des Weltbaus (TerrainFit, SeaMask). Vorgabe 0 für Felder ohne Bezug.
     */
    default int seaLevelY(int x, int z) {
        return 0;
    }
}
