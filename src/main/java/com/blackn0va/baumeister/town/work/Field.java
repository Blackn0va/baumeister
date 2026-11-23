package com.blackn0va.baumeister.town.work;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ein Arbeitsfeld (Vertrag v013, AK-43): Quadrat mit Seitenlänge 2·radius+1 um (x, z), von DOWN unter bis UP über
 * der Höhe y, in der ein Admin es gesetzt hat (/bm stadtleben feld setzen). Nur hier ändern Bewohner Blöcke.
 *
 * Quadrat statt Kreis: Felder und Baumreihen liegen achsparallel, und „radius 8“ heißt für den Admin „8 Blöcke in jede
 * Richtung“ – das trifft das Quadrat. Die Höhe ist begrenzt, damit ein Feld auf dem Boden nicht den Keller darunter
 * oder eine Brücke darüber einschließt. UP reicht für Eiche, Birke und kleine Fichten; ragt ein Stamm über das Feld
 * hinaus, fällt der Planner den Baum gar nicht (lieber stehen lassen als halb fällen).
 *
 * build (AK-45): Baufeld – hier bauen Bewohner auch kleine Dinge (TownBuilds). Nur ausdrücklich per Befehl, ein neu
 * gesetztes Feld ist nie eins.
 *
 * Threads: unveränderlich. Fehler: keine; check() sagt, was an Name oder Radius nicht passt.
 */
public record Field(String name, String world, int x, int y, int z, int radius, UUID owner, boolean build) {

    /** Ohne Baufreigabe (AK-43): so legt /bm stadtleben feld setzen jedes Feld an. */
    public Field(String name, String world, int x, int y, int z, int radius, UUID owner) {
        this(name, world, x, y, z, radius, owner, false);
    }

    /** Dasselbe Feld mit Baufreigabe an oder aus (AK-45, /bm stadtleben feld bauen). */
    public Field withBuild(boolean on) {
        return new Field(name, world, x, y, z, radius, owner, on);
    }

    public static final int MIN_RADIUS = 2;
    public static final int MAX_RADIUS = 24;
    /** So tief unter der Setzhöhe zählt noch zum Feld (Mulde, Bewässerungsgraben). */
    public static final int DOWN = 4;
    /** So hoch darüber (Baumkronen). */
    public static final int UP = 16;
    /** Wie Teamnamen und Warps: nur Kleinbuchstaben, Ziffern, _ und - (auch Dateiname, Log, Tab-Vervollständigung). */
    static final Pattern NAME = Pattern.compile("[a-z0-9_-]{2,24}");

    /** Name in Kleinschreibung, wie er gespeichert wird. */
    public static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** null = in Ordnung, sonst der Satz für den Admin. */
    public static String check(String name, int radius) {
        if (!NAME.matcher(key(name)).matches()) {
            return "Feldname: 2–24 Zeichen, nur a–z, 0–9, _ und -.";
        }
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            return "Radius " + MIN_RADIUS + "–" + MAX_RADIUS + " Blöcke.";
        }
        return null;
    }

    /** Liegt der Block (bx, by, bz) in diesem Feld? */
    public boolean contains(String inWorld, int bx, int by, int bz) {
        return world.equals(inWorld) && Math.abs(bx - x) <= radius && Math.abs(bz - z) <= radius && by >= y - DOWN
                && by <= y + UP;
    }

    /** Waagrechter Abstand der Mitte zu (px, pz). */
    public double distance(double px, double pz) {
        return Math.hypot(px - (x + 0.5), pz - (z + 0.5));
    }

    /** Anzahl Spalten des Feldes. */
    public int columns() {
        int side = 2 * radius + 1;
        return side * side;
    }

    /** Spalte i (0 … columns()−1) reihenweise: {bx, bz}. */
    public int[] column(int i) {
        int side = 2 * radius + 1;
        int k = Math.floorMod(i, columns());
        return new int[] {x - radius + k % side, z - radius + k / side};
    }
}
