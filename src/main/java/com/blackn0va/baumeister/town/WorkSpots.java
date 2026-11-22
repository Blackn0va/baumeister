package com.blackn0va.baumeister.town;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wo ein Bewohner arbeitet (Vertrag v009, AK-30). Vorher war der Arbeitsort ein zufälliger Platz 12–22 Blöcke von der
 * Wohnung – Bewohner „arbeiteten“ mitten auf der Wiese. Jetzt bevorzugt an Orten, die es schon gibt: Warps
 * (EssentialsX und Baumeister-/warp), Reisepunkte (/bm orte) und fertige Bauten aus dem Baubuch. Erst wenn keiner
 * passt, bleibt der alte Platz (TownLife.spawn).
 *
 * Passend heißt: dieselbe Welt, Stehpunkt in einem geladenen Chunk (sonst sähe ihn niemand, und Lesen lüde den Chunk –
 * PlotSquared unter Spigot) und höchstens RADIUS waagrecht vom nächsten Spieler (Population.FAR_FROM_PLAYERS nimmt
 * Bewohner ab 64 Blöcken weg; mit 48 bleibt Luft für den Weg). Höchstens MAX_PER_SPOT Bewohner je Ort, sonst stünden
 * alle zwölf an einem Warp aufeinander.
 *
 * Reihenfolge: zuerst der Ort mit den wenigsten Bewohnern, dann der nächste zur Wohnung (kurzer Weg zur Arbeit), dann
 * die Art in der Reihenfolge von Source (Warp vor Reisepunkt vor Bau), zuletzt der Name – damit die Wahl bei gleichen
 * Werten nicht vom Zufall der Listenreihenfolge abhängt.
 *
 * Ein Bau ist ein Kasten (Baubuch-Rahmen); gearbeitet wird davor, nicht darin: Der Stehpunkt ist der Punkt am um
 * BESIDE vergrößerten Rahmen, der der Wohnung am nächsten liegt (approach). Steht die Wohnung im Rahmen, rückt er an
 * die nächste Kante hinaus.
 *
 * Threads: zustandslos. Fehler: keine; nicht endliche Koordinaten fallen heraus.
 */
public final class WorkSpots {

    /** Höchstens so weit vom nächsten Spieler (waagrecht). */
    public static final double RADIUS = 48.0;
    /** Höchstens so viele Bewohner am selben Ort. */
    public static final int MAX_PER_SPOT = 2;
    /** Abstand des Stehpunkts vom Rahmen eines Baus (Blöcke). */
    public static final double BESIDE = 2.0;
    /** Höchstens so viele Orte je Quelle – ein Server mit Tausenden Warps soll den Takt nicht spüren. */
    public static final int MAX_PER_SOURCE = 200;

    /** Woher ein Arbeitsort kommt; label steht im Log und im Status. */
    public enum Source {
        WARP("warp"),
        PLACE("reisepunkt"),
        BUILD("bau"),
        NEAR("platz");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Ein Ort; Punkte (Warp, Reisepunkt) haben minX == maxX und minZ == maxZ, Bauten ihren Rahmen. */
    public record Spot(Source source, String name, String world, double minX, double y, double minZ, double maxX,
            double maxZ) {

        public static Spot point(Source source, String name, String world, double x, double y, double z) {
            return new Spot(source, name, world, x, y, z, x, z);
        }

        /** Schlüssel für die Belegung (je Art und Name, Groß-/Kleinschreibung egal). */
        public String key() {
            return source.label + ":" + name.toLowerCase(Locale.ROOT);
        }

        boolean finite() {
            return Double.isFinite(minX) && Double.isFinite(y) && Double.isFinite(minZ) && Double.isFinite(maxX)
                    && Double.isFinite(maxZ);
        }
    }

    /** Ein gewählter Ort samt Stehpunkt (waagrecht; die Höhe sucht TownLife an der Oberfläche). */
    public record Choice(Spot spot, double x, double z) {
    }

    /** Ist der Chunk geladen? (Chunk-Koordinaten.) */
    @FunctionalInterface
    public interface Loaded {
        boolean loaded(int chunkX, int chunkZ);
    }

    private WorkSpots() {
    }

    /**
     * Alle passenden Orte, bester zuerst. players: waagrechte Positionen {x, z} der Spieler in dieser Welt; taken:
     * Bewohner je Spot.key().
     */
    public static List<Choice> rank(List<Spot> spots, String world, double homeX, double homeZ,
            List<double[]> players, Loaded loaded, Map<String, Integer> taken) {
        List<Choice> out = new ArrayList<>();
        if (players.isEmpty()) {
            return out;
        }
        for (Spot s : spots) {
            if (s == null || !s.finite() || !s.world().equals(world)
                    || taken.getOrDefault(s.key(), 0) >= MAX_PER_SPOT) {
                continue;
            }
            double[] at = approach(s, homeX, homeZ);
            if (!loaded.loaded((int) Math.floor(at[0]) >> 4, (int) Math.floor(at[1]) >> 4)
                    || nearestSq(players, at[0], at[1]) > RADIUS * RADIUS) {
                continue;
            }
            out.add(new Choice(s, at[0], at[1]));
        }
        out.sort(Comparator.<Choice>comparingInt(c -> taken.getOrDefault(c.spot().key(), 0))
                .thenComparingDouble(c -> Math.hypot(c.x() - homeX, c.z() - homeZ))
                .thenComparing(c -> c.spot().source())
                .thenComparing(c -> c.spot().name()));
        return out;
    }

    /** Stehpunkt am Ort: bei einem Punkt der Punkt selbst, bei einem Bau vor dem Rahmen, der Wohnung zugewandt. */
    public static double[] approach(Spot s, double fromX, double fromZ) {
        if (s.minX() == s.maxX() && s.minZ() == s.maxZ()) {
            return new double[] {s.minX(), s.minZ()};
        }
        double lowX = s.minX() - BESIDE;
        double highX = s.maxX() + 1 + BESIDE;
        double lowZ = s.minZ() - BESIDE;
        double highZ = s.maxZ() + 1 + BESIDE;
        double x = Math.clamp(fromX, lowX, highX);
        double z = Math.clamp(fromZ, lowZ, highZ);
        if (x > lowX && x < highX && z > lowZ && z < highZ) {
            // Innen: zur nächsten Kante hinaus, sonst stünde der Bewohner im Bau.
            double toLowX = x - lowX;
            double toHighX = highX - x;
            double toLowZ = z - lowZ;
            double toHighZ = highZ - z;
            double m = Math.min(Math.min(toLowX, toHighX), Math.min(toLowZ, toHighZ));
            if (m == toLowX) {
                x = lowX;
            } else if (m == toHighX) {
                x = highX;
            } else if (m == toLowZ) {
                z = lowZ;
            } else {
                z = highZ;
            }
        }
        return new double[] {x, z};
    }

    private static double nearestSq(List<double[]> players, double x, double z) {
        double best = Double.MAX_VALUE;
        for (double[] p : players) {
            double dx = p[0] - x;
            double dz = p[1] - z;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }
}
