/*
 * Airocean-/Dymaxion-Projektion (Buckminster Fuller) nach Robert W. Grays exakten Gleichungen.
 *
 * Herkunft: portiert aus BuildTheEarth/terraplusplus (Branch master, abgerufen 2026-10-28),
 *   src/main/java/net/buildtheearth/terraplusplus/projection/dymaxion/DymaxionProjection.java
 *   src/main/java/net/buildtheearth/terraplusplus/util/MathUtils.java
 * Änderungen: nur Grays Variante (ohne konformes Vektorfeld und ohne BTE-Umlegung), ohne Jackson/Lombok,
 * Ausnahmen → null, Achsen auf Minecraft (x Ost, z Süd), Ecken auf ein exaktes Ikosaeder gezogen, Maßstab in
 * Blöcken und die ganze Ebene so gedreht, dass in Hepberg geografisch Nord genau −z ist. Mathematik sonst unverändert.
 *
 * ---- Lizenz der Vorlage ----
 * MIT License
 *
 * Copyright (c) 2020 BuildTheEarth.net
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.blackn0va.baumeister.earth;

/**
 * Die ganze Erde als ein zusammenhängendes Netz aus 20 Dreiecken (Fullers Airocean), Nähte durch die Ozeane.
 *
 * Warum Airocean (Messung der Hauptsitzung 2026-10-28, Probe proj/airocean, final_gray.txt): Längen weichen weltweit
 * höchstens ±11,3 % ab (h/k 0,887–1,113 auf dem 1°-Raster), Winkel im Median 4,3°, und alle Kontinente liegen am
 * Stück. Eine Sinus-Projektion (Sinus6Projection) ist zwar winkeltreuer, zerreißt aber jeden Kontinent in Streifen.
 *
 * Maßstab: Minimax – Faktor 1,04265 · R (= 6 642 732 Blöcke je Einheit). Bei reinem R wären es −14,9 %, beim Median 1
 * +15,9 %. Wer den Faktor ändert, verschiebt jeden Ort der Erdwelt: Alte Chunks passten nicht mehr zu neuen.
 *
 * Drehung: Fullers Netz liegt schräg – in Hepberg zeigte geografisch Nord 134,7° neben −z (gemessen). Die ganze Ebene
 * ist deshalb um den Ursprung gedreht, bis dort Nord genau −z ist (Nutzerwunsch: Hepberg ist die Heimat). Eine
 * Drehung ist eine Isometrie: Verzerrung und Rundweg bleiben gleich; anderswo zeigt Nord weiter in andere Richtungen.
 *
 * Eck-Fix: Fullers Gradwerte bilden kein exaktes Ikosaeder (10.447378 gegen −10.447350 für ein Gegenpunktpaar).
 * Gemessen: Rundweg-Fehler bis 0,33 m an Flächenkanten (Perth 0,27 m), weil Hin- und Rückweg dort verschiedene Flächen
 * wählen. Die Ecken werden deshalb auf ein echtes Ikosaeder gezogen (höchstens 3,6 m Verschiebung), danach Rundweg
 * ≤ 1,1·10⁻⁴ m bei 2 Mio. Zufallspunkten.
 *
 * Threads: nur statische, unveränderliche Tabellen – beliebig parallel nutzbar.
 */
public final class AiroceanProjection implements EarthProjection {

    /** Kugelradius der Messvorgabe (mittlerer Erdradius). */
    static final double R_SPHERE = 6_371_008.8;
    /** Blöcke je Projektionseinheit: Minimax-Faktor 1,04265 · R, siehe Klassenkommentar. */
    static final double SCALE = 6_642_732.0;
    /** Heimatort: hier zeigt geografisch Nord genau nach −z. */
    static final double HOME_LAT = 48.82;
    static final double HOME_LON = 11.46;

    private static final double ROOT3 = Math.sqrt(3);

    // Ikosaeder-Konstanten aus Grays Gleichungen (Kantenbogen, Gnomon-Abstand usw.)
    private static final double ARC = 2 * Math.asin(Math.sqrt(5 - Math.sqrt(5)) / Math.sqrt(10));
    private static final double Z = Math.sqrt(5 + 2 * Math.sqrt(5)) / Math.sqrt(15);
    private static final double EL = Math.sqrt(8) / Math.sqrt(5 + Math.sqrt(5));
    private static final double EL6 = EL / 6;
    private static final double DVE = Math.sqrt(3 + Math.sqrt(5)) / Math.sqrt(5 + Math.sqrt(5));
    private static final double TAN_SUM = -3 * EL6 / DVE;

    /** Newton-Schritte der Umkehrung; 5 wie in der Vorlage, gemessen reicht das für 10⁻⁴ m. */
    private static final int NEWTON = 5;

    // Fullers Eckpunkte {lon, lat} in Grad – das ist Fullers eigene Orientierung, bei der die Nähte
    // durch die Ozeane laufen. Wer sie ändert, zerschneidet Kontinente.
    private static final double[][] VERTICES_GEO = {
        {10.536199, 64.700000},
        {-5.245390, 2.300882},
        {58.157706, 10.447378},
        {122.300000, 39.100000},
        {-143.478490, 50.103201},
        {-67.132330, 23.717925},
        {36.521510, -50.103200},
        {112.867673, -23.717930},
        {174.754610, -2.300882},
        {-121.842290, -10.447350},
        {-57.700000, -39.100000},
        {-169.463800, -64.700000},
    };

    private static final int[][] ISO = {
        {2, 1, 6}, {1, 0, 2}, {0, 1, 5}, {1, 5, 10}, {1, 6, 10},
        {7, 2, 6}, {2, 3, 7}, {3, 0, 2}, {0, 3, 4}, {4, 0, 5},
        {5, 4, 9}, {9, 5, 10}, {10, 9, 11}, {11, 6, 10}, {6, 7, 11},
        {8, 3, 7}, {8, 3, 4}, {8, 4, 9}, {9, 8, 11}, {7, 8, 11},
        {11, 6, 7}, // Teilstück von 14
        {3, 7, 8}   // Teilstück von 15
    };

    private static final double[][] CENTER_MAP = {
        {-3, 7}, {-2, 5}, {-1, 7}, {2, 5}, {4, 5},
        {-4, 1}, {-3, -1}, {-2, 1}, {-1, -1}, {0, 1},
        {1, -1}, {2, 1}, {3, -1}, {4, 1}, {5, -1},
        {-3, -5}, {-1, -5}, {1, -5}, {2, -7}, {-4, -7},
        {-5, -5}, {-2, -7}
    };

    private static final boolean[] FLIP_TRIANGLE = {
        true, false, true, false, false,
        true, false, true, false, true, false, true, false, true, false,
        true, true, true, false, false,
        true, false
    };

    private static final int[] FACE_ON_GRID = {
        -1, -1, 0, 1, 2, -1, -1, 3, -1, 4, -1,
        -1, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
        20, 19, 15, 21, 16, -1, 17, 18, -1, -1, -1,
    };

    private static final double[][] CENTERS = new double[22][2];
    private static final double[][] CENTROIDS = new double[22][3];
    private static final double[][][] ROT = new double[22][][];
    private static final double[][][] INV_ROT = new double[22][][];
    /** Größte Verschiebung einer Ecke durch den Eck-Fix, im Bogenmaß (für den Test). */
    static final double VERTEX_SHIFT_MAX_RAD;

    static {
        for (int i = 0; i < 22; i++) {
            CENTERS[i][0] = CENTER_MAP[i][0] * 0.5 * ARC;
            CENTERS[i][1] = CENTER_MAP[i][1] * ARC * ROOT3 / 12;
        }
        double[][] verticesCart = new double[VERTICES_GEO.length][];
        for (int i = 0; i < VERTICES_GEO.length; i++) {
            verticesCart[i] = spherical2Cartesian(geo2Spherical(VERTICES_GEO[i][0], VERTICES_GEO[i][1]));
        }
        // Eck-Fix (siehe Klassenkommentar): Ecke 0 fest, Ecke 1 nur in der Ebene 0/1 nachgeführt, alle übrigen
        // auf das Norm-Ikosaeder im selben Rahmen gezogen. Wer das abschaltet, bekommt wieder 0,33 m Rundweg-Fehler.
        double phi = (1 + Math.sqrt(5)) / 2;
        double[][] std = new double[12][];
        int idx = 0;
        for (int s1 = -1; s1 <= 1; s1 += 2) {
            for (int s2 = -1; s2 <= 1; s2 += 2) {
                std[idx++] = new double[] {0, s1, s2 * phi};
                std[idx++] = new double[] {s1, s2 * phi, 0};
                std[idx++] = new double[] {s2 * phi, 0, s1};
            }
        }
        for (double[] v : std) {
            double n = Math.sqrt(dot(v, v));
            v[0] /= n;
            v[1] /= n;
            v[2] /= n;
        }
        double[] sa = std[0];
        double[] sb = null;
        for (double[] v : std) {
            if (v != sa && Math.abs(dot(v, sa) - Math.cos(ARC)) < 1e-9) {
                sb = v;
                break;
            }
        }
        if (sb == null) {
            throw new IllegalStateException("Ikosaeder: keine Nachbarecke gefunden");
        }
        double[][] fs = frame(sa, sb);
        double[][] ft = frame(verticesCart[0], verticesCart[1]);
        double shiftMax = 0;
        double[][] snapped = new double[12][];
        for (int i = 0; i < 12; i++) {
            double best = -2;
            for (double[] v : std) {
                // Koordinaten im Rahmen des Norm-Ikosaeders, dann mit denselben Koordinaten in den Zielrahmen
                double c0 = dot(fs[0], v);
                double c1 = dot(fs[1], v);
                double c2 = dot(fs[2], v);
                double[] r = new double[3];
                for (int k = 0; k < 3; k++) {
                    r[k] = ft[0][k] * c0 + ft[1][k] * c1 + ft[2][k] * c2;
                }
                double d = dot(r, verticesCart[i]);
                if (d > best) {
                    best = d;
                    snapped[i] = r;
                }
            }
            shiftMax = Math.max(shiftMax, Math.acos(Math.min(1, best)));
        }
        double[][] verticesSph = new double[12][];
        for (int i = 0; i < 12; i++) {
            verticesCart[i] = snapped[i];
            verticesSph[i] = cartesian2Spherical(snapped[i]);
        }
        VERTEX_SHIFT_MAX_RAD = shiftMax;
        for (int i = 0; i < 22; i++) {
            double[] a = verticesCart[ISO[i][0]];
            double[] b = verticesCart[ISO[i][1]];
            double[] c = verticesCart[ISO[i][2]];
            double xs = a[0] + b[0] + c[0];
            double ys = a[1] + b[1] + c[1];
            double zs = a[2] + b[2] + c[2];
            double mag = Math.sqrt(xs * xs + ys * ys + zs * zs);
            CENTROIDS[i] = new double[] {xs / mag, ys / mag, zs / mag};

            double[] cs = cartesian2Spherical(CENTROIDS[i]);
            double cLambda = cs[0];
            double cPhi = cs[1];
            double[] vertex = verticesSph[ISO[i][0]];
            double[] v = yRot(new double[] {vertex[0] - cLambda, vertex[1]}, -cPhi);
            ROT[i] = zyz(-cLambda, -cPhi, (Math.PI / 2) - v[0]);
            INV_ROT[i] = zyz(v[0] - (Math.PI / 2), cPhi, cLambda);
        }
    }

    // Drehung der Ebene um den Ursprung (nach den Tabellen oben berechnet – die Reihenfolge der static-Blöcke zählt).
    private static final double ROT_COS;
    private static final double ROT_SIN;

    static {
        // Bild-Nordrichtung in Hepberg über einen Schritt von 0,0001° (≈ 11 m) nach Norden.
        double[] home = unrotated(HOME_LAT, HOME_LON);
        double[] north = unrotated(HOME_LAT + 1e-4, HOME_LON);
        double alpha = Math.atan2(north[1] - home[1], north[0] - home[0]);
        // Ziel: Richtung (0, −1) = Winkel −π/2.
        double angle = -Math.PI / 2 - alpha;
        ROT_COS = Math.cos(angle);
        ROT_SIN = Math.sin(angle);
    }

    @Override
    public double[] toWorld(double lat, double lon) {
        if (!(Math.abs(lat) <= 90) || !(Math.abs(lon) <= 180)) {
            throw new IllegalArgumentException("lat/lon außerhalb: " + lat + "," + lon);
        }
        double[] p = unrotated(lat, lon);
        return new double[] {ROT_COS * p[0] - ROT_SIN * p[1], ROT_SIN * p[0] + ROT_COS * p[1]};
    }

    @Override
    public double[] toGeo(double x, double z) {
        double ux = ROT_COS * x + ROT_SIN * z;
        double uz = -ROT_SIN * x + ROT_COS * z;
        double[] g = dymaxionToGeo(ux / SCALE, -uz / SCALE);
        if (g == null || !Double.isFinite(g[0]) || !Double.isFinite(g[1])) {
            return null;
        }
        return new double[] {g[1], g[0]};
    }

    /** Fullers Netz in Blöcken, vor der Drehung; Projektions-y zeigt nach oben, Minecraft-z nach Süden. */
    private static double[] unrotated(double lat, double lon) {
        double[] p = dymaxionFromGeo(lon, lat);
        return new double[] {p[0] * SCALE, -p[1] * SCALE};
    }

    // ---------------- Dymaxion (Gray) ----------------

    private static int findTriangle(double[] vector) {
        double min = Double.MAX_VALUE;
        int face = 0;
        for (int i = 0; i < 20; i++) {
            double xd = CENTROIDS[i][0] - vector[0];
            double yd = CENTROIDS[i][1] - vector[1];
            double zd = CENTROIDS[i][2] - vector[2];
            double dissq = xd * xd + yd * yd + zd * zd;
            if (dissq < min) {
                // Unter 0,1 liegt der Punkt sicher im Inkreis dieser Fläche – früh aussteigen
                if (dissq < 0.1) {
                    return i;
                }
                face = i;
                min = dissq;
            }
        }
        return face;
    }

    private static int findTriangleGrid(double x, double y) {
        double xp = x / ARC;
        double yp = y / (ARC * ROOT3);
        int row;
        if (yp > -0.25) {
            if (yp < 0.25) {
                row = 1;
            } else if (yp <= 0.75) {
                row = 0;
                yp = 0.5 - yp;
            } else {
                return -1;
            }
        } else if (yp >= -0.75) {
            row = 2;
            yp = -yp - 0.5;
        } else {
            return -1;
        }
        yp += 0.25;
        double xr = xp - yp;
        double yr = xp + yp;
        int gx = (int) Math.floor(xr);
        int gy = (int) Math.floor(yr);
        int col = 2 * gx + (gy != gx ? 1 : 0) + 6;
        if (col < 0 || col >= 11) {
            return -1;
        }
        return FACE_ON_GRID[row * 11 + col];
    }

    /** Grays Vorwärtstransformation: Punkt auf der Bezugsfläche → ebenes Bezugsdreieck. */
    private static double[] grayTriangleTransform(double[] vec) {
        double s = Z / vec[2];
        double xp = s * vec[0];
        double yp = s * vec[1];
        double a = Math.atan((2 * yp / ROOT3 - EL6) / DVE);
        double b = Math.atan((xp - yp / ROOT3 - EL6) / DVE);
        double c = Math.atan((-xp - yp / ROOT3 - EL6) / DVE);
        return new double[] {0.5 * (b - c), (2 * a - b - c) / (2 * ROOT3)};
    }

    /** Grays Umkehrung: Newton auf tan(c), weil tan(a)+tan(b)+tan(c) konstant ist. */
    private static double[] grayInverseTriangleTransform(double xpp, double ypp) {
        double tanaoff = Math.tan(ROOT3 * ypp + xpp);
        double tanboff = Math.tan(2 * xpp);
        double anumer = tanaoff * tanaoff + 1;
        double bnumer = tanboff * tanboff + 1;
        double tana = tanaoff;
        double tanb = tanboff;
        double tanc = 0;
        double adenom = 1;
        double bdenom = 1;
        for (int i = 0; i < NEWTON; i++) {
            double f = tana + tanb + tanc - TAN_SUM;
            double fp = anumer * adenom * adenom + bnumer * bdenom * bdenom + 1;
            tanc -= f / fp;
            adenom = 1 / (1 - tanc * tanaoff);
            bdenom = 1 / (1 - tanc * tanboff);
            tana = (tanc + tanaoff) * adenom;
            tanb = (tanc + tanboff) * bdenom;
        }
        double yp = ROOT3 * (DVE * tana + EL6) / 2;
        double xp = DVE * tanb + yp / ROOT3 + EL6;
        double xpoZ = xp / Z;
        double ypoZ = yp / Z;
        double z = 1 / Math.sqrt(1 + xpoZ * xpoZ + ypoZ * ypoZ);
        return new double[] {z * xpoZ, z * ypoZ, z};
    }

    private static double[] dymaxionFromGeo(double lon, double lat) {
        double[] vector = spherical2Cartesian(geo2Spherical(lon, lat));
        int face = findTriangle(vector);
        double[] pvec = matVec(ROT[face], vector);
        double[] pv = grayTriangleTransform(pvec);
        if (FLIP_TRIANGLE[face]) {
            pv[0] = -pv[0];
            pv[1] = -pv[1];
        }
        double v0 = pv[0];
        // Fullers geteilte Flächen 14 und 15: die rechte Hälfte wandert als 20/21 ans andere Netzende
        if (((face == 15 && v0 > pv[1] * ROOT3) || face == 14) && v0 > 0) {
            pv[0] = 0.5 * v0 - 0.5 * ROOT3 * pv[1];
            pv[1] = 0.5 * ROOT3 * v0 + 0.5 * pv[1];
            face += 6;
        }
        pv[0] += CENTERS[face][0];
        pv[1] += CENTERS[face][1];
        return pv;
    }

    private static double[] dymaxionToGeo(double x, double y) {
        int face = findTriangleGrid(x, y);
        if (face == -1) {
            return null;
        }
        x -= CENTERS[face][0];
        y -= CENTERS[face][1];
        switch (face) {
            case 14 -> {
                if (x > 0) {
                    return null;
                }
            }
            case 20 -> {
                if (-y * ROOT3 > x) {
                    return null;
                }
            }
            case 15 -> {
                if (x > 0 && x > y * ROOT3) {
                    return null;
                }
            }
            case 21 -> {
                if (x < 0 || -y * ROOT3 > x) {
                    return null;
                }
            }
            default -> {
                // Übrige Flächen sind ungeteilt – keine Zusatzprüfung.
            }
        }
        if (FLIP_TRIANGLE[face]) {
            x = -x;
            y = -y;
        }
        double[] c = grayInverseTriangleTransform(x, y);
        double[] vecp = matVec(INV_ROT[face], c);
        double[] sph = cartesian2Spherical(vecp);
        return new double[] {Math.toDegrees(sph[0]), 90 - Math.toDegrees(sph[1])};
    }

    // ---------------- Hilfen (MathUtils der Vorlage) ----------------

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** Orthonormaler Rahmen {e1, e2, e3} aus Ecke a und Nachbarecke b. */
    private static double[][] frame(double[] a, double[] b) {
        double d = dot(a, b);
        double[] e2 = {b[0] - d * a[0], b[1] - d * a[1], b[2] - d * a[2]};
        double n = Math.sqrt(dot(e2, e2));
        e2[0] /= n;
        e2[1] /= n;
        e2[2] /= n;
        double[] e3 = {a[1] * e2[2] - a[2] * e2[1], a[2] * e2[0] - a[0] * e2[2], a[0] * e2[1] - a[1] * e2[0]};
        return new double[][] {a.clone(), e2, e3};
    }

    private static double[] geo2Spherical(double lon, double lat) {
        return new double[] {Math.toRadians(lon), Math.toRadians(90 - lat)};
    }

    private static double[] spherical2Cartesian(double[] s) {
        double sinphi = Math.sin(s[1]);
        return new double[] {sinphi * Math.cos(s[0]), sinphi * Math.sin(s[0]), Math.cos(s[1])};
    }

    private static double[] cartesian2Spherical(double[] c) {
        return new double[] {Math.atan2(c[1], c[0]), Math.atan2(Math.sqrt(c[0] * c[0] + c[1] * c[1]), c[2])};
    }

    private static double[] yRot(double[] spherical, double rot) {
        double[] c = spherical2Cartesian(spherical);
        double x = c[0];
        c[0] = c[2] * Math.sin(rot) + x * Math.cos(rot);
        c[2] = c[2] * Math.cos(rot) - x * Math.sin(rot);
        double mag = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
        c[0] /= mag;
        c[1] /= mag;
        c[2] /= mag;
        return new double[] {Math.atan2(c[1], c[0]), Math.atan2(Math.sqrt(c[0] * c[0] + c[1] * c[1]), c[2])};
    }

    private static double[][] zyz(double a, double b, double c) {
        double sina = Math.sin(a);
        double cosa = Math.cos(a);
        double sinb = Math.sin(b);
        double cosb = Math.cos(b);
        double sinc = Math.sin(c);
        double cosc = Math.cos(c);
        double[][] m = new double[3][3];
        m[0][0] = cosa * cosb * cosc - sinc * sina;
        m[0][1] = -sina * cosb * cosc - sinc * cosa;
        m[0][2] = cosc * sinb;
        m[1][0] = sinc * cosb * cosa + cosc * sina;
        m[1][1] = cosc * cosa - sinc * cosb * sina;
        m[1][2] = sinc * sinb;
        m[2][0] = -sinb * cosa;
        m[2][1] = sinb * sina;
        m[2][2] = cosb;
        return m;
    }

    private static double[] matVec(double[][] m, double[] v) {
        return new double[] {
            m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }
}
