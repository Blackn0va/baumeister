package com.blackn0va.baumeister;

import java.io.IOException;
import java.nio.file.Path;

import com.blackn0va.baumeister.terrain.ElevationTiles;

/**
 * Messprobe: Rohhöhen (Terrarium, Zoom 15) als Zeichenraster – nur aus dem lokalen Zwischenspeicher, nie aus dem Netz.
 * Frage 2026-11-01: Sieht das Höhenmodell Sydneys Hafenbuchten (Cockle Bay, Port Jackson) überhaupt?
 *
 * Aufruf: TerrariumGridProbe <ordner> <latNord> <lonWest> <latSüd> <lonOst> <schritte>
 * Zeichen: ~ genau 0 (Meer ohne Tiefe), = unter 0 (Meeresboden), . 0–5 m, : 5–20 m, + 20–50 m, # über 50 m, ? fehlt.
 */
public final class TerrariumGridProbe {

    private TerrariumGridProbe() {
    }

    public static void main(String[] args) throws IOException {
        ElevationTiles tiles = new ElevationTiles(Path.of(args[0]), 15, 64, (zoom, tx, ty) -> {
            throw new IOException("nicht im Zwischenspeicher: " + zoom + "/" + tx + "/" + ty);
        });
        double n = Double.parseDouble(args[1]);
        double w = Double.parseDouble(args[2]);
        double s = Double.parseDouble(args[3]);
        double e = Double.parseDouble(args[4]);
        int steps = Integer.parseInt(args[5]);
        int zero = 0;
        int below = 0;
        int total = 0;
        for (int r = 0; r <= steps; r++) {
            double lat = n + (s - n) * r / steps;
            StringBuilder line = new StringBuilder(String.format("%9.5f ", lat));
            for (int c = 0; c <= steps * 2; c++) {
                double lon = w + (e - w) * c / (steps * 2.0);
                char ch;
                try {
                    double v = tiles.elevation(lat, lon);
                    total++;
                    if (v == 0) {
                        ch = '~';
                        zero++;
                    } else if (v < 0) {
                        ch = '=';
                        below++;
                    } else {
                        ch = v < 5 ? '.' : v < 20 ? ':' : v < 50 ? '+' : '#';
                    }
                } catch (IOException ex) {
                    ch = '?';
                }
                line.append(ch);
            }
            System.out.println(line);
        }
        System.out.printf("Proben %d: genau 0 → %d, unter 0 → %d%n", total, zero, below);
        System.out.printf("Cockle Bay -33.8735,151.2010: %.2f m · Circular Quay -33.8600,151.2110: %.2f m · "
                + "Port Jackson -33.8530,151.2200: %.2f m%n", safe(tiles, -33.8735, 151.2010),
                safe(tiles, -33.8600, 151.2110), safe(tiles, -33.8530, 151.2200));
    }

    private static double safe(ElevationTiles t, double lat, double lon) {
        try {
            return t.elevation(lat, lon);
        } catch (IOException e) {
            return Double.NaN;
        }
    }
}
