package com.blackn0va.baumeister;

import com.blackn0va.baumeister.earth.EarthProjection;

import java.util.Optional;

/**
 * Wo eine Stadt „an ihrer echten Stelle“ in der Stadtwelt steht (/bm stadt los echt).
 *
 * Vorher (bis 2026-10-30) stand jede Stadt dort, wo der Spieler beim Start stand – Nürnberg ließ sich direkt neben
 * München bauen (Wunsch). Jetzt rechnet
 * dieselbe Projektion wie die Erdwelt (EarthService, erde/welt.json; dev01: airocean) lat/lon in Blockkoordinaten um.
 * Damit liegt eine Stadt in der Stadtwelt an denselben x/z wie in der Erdwelt, und Städte untereinander stimmen:
 * gemessen München x 382 504 z 6 821 329, Nürnberg x 349 996 z 6 667 891, Abstand im Spiel 156,8 km (echt ~151 km,
 * airocean verzerrt hier um ~4 %). Weltweit bleiben die Werte unter ±16 Mio. (Sydney x 15,4 Mio.).
 *
 * Threads: beliebig (rein). Fehler: Stellen außerhalb der Weltgrenze oder in einer Lücke des Netzes → leer.
 */
final class CityPlacement {

    /** Spigot/Vanilla: Weltgrenze bei ±29 999 984; Abstand für den Radius der Stadt und einen Rand. */
    static final long WORLD_LIMIT = 29_999_984L;

    private CityPlacement() {
    }

    /** Blockkoordinaten {x, z} der Mitte; leer, wenn die Stadt samt Radius nicht in die Welt passt. */
    static Optional<int[]> real(EarthProjection projection, double lat, double lon, int radius) {
        double[] w = projection.toWorld(lat, lon);
        if (w == null || !Double.isFinite(w[0]) || !Double.isFinite(w[1])) {
            return Optional.empty();
        }
        long x = Math.round(w[0]);
        long z = Math.round(w[1]);
        long reach = WORLD_LIMIT - Math.max(0, radius) - 64;
        if (Math.abs(x) > reach || Math.abs(z) > reach) {
            return Optional.empty();
        }
        return Optional.of(new int[] {(int) x, (int) z});
    }
}
