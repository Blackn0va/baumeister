package com.blackn0va.baumeister.earth;

/**
 * Erde → Blockkoordinaten der Erdwelt (x Osten, z Süden, 1 Block ≈ 1 m). Implementierungen sind zustandslos und
 * threadsicher (Weltgenerator-Threads).
 */
public interface EarthProjection {

    /** Blockkoordinaten {x, z} eines Punkts der Erde. */
    double[] toWorld(double lat, double lon);

    /** Umkehrung: {lat, lon}, oder null für Stellen in einer Lücke des Netzes. */
    double[] toGeo(double x, double z);
}
