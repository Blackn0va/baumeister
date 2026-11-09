package com.blackn0va.baumeister.companion;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Welcher Begleiter antwortet wem – auch Mitspielern ohne eigenen Begleiter.
 *
 * Wunsch 2026-11-04. Vorher hörte ein Begleiter nur seinen Besitzer:
 * Simple Voice Chat schnitt nur Besitzer mit, und sprach ein Mitspieler „Vera, …“ im Chat, rief er sich eine eigene
 * Vera, statt mit der zu reden, die neben ihm stand. Jetzt gilt: der eigene Begleiter zuerst (egal wo), sonst der
 * nächste fremde in Hörweite derselben Welt. Wer das zurückdreht, lässt Mitspieler neben Vera wieder ins Leere reden.
 *
 * Rein, ohne Bukkit-Typen, damit es ohne Server testbar ist. Threads: zustandslos. Fehler: keine.
 */
public final class CompanionRouting {

    /** Ein Begleiter an seinem Platz. */
    public record Spot(UUID owner, UUID world, double x, double y, double z) {
    }

    /** Ein Spieler an seinem Platz. */
    public record Speaker(UUID id, UUID world, double x, double y, double z) {
    }

    private CompanionRouting() {
    }

    /** Besitzer des Begleiters, der dem Sprecher antwortet; leer = keiner (dann ruft er sich ggf. einen eigenen). */
    public static Optional<UUID> pick(Speaker s, Collection<Spot> spots, double range) {
        UUID best = null;
        double bestDist = range * range;
        for (Spot spot : spots) {
            if (spot.owner().equals(s.id())) {
                return Optional.of(spot.owner());
            }
            if (!spot.world().equals(s.world())) {
                continue;
            }
            double d = sq(spot.x() - s.x()) + sq(spot.y() - s.y()) + sq(spot.z() - s.z());
            if (d <= bestDist) {
                bestDist = d;
                best = spot.owner();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Wessen Sprache Simple Voice Chat mitschneiden soll: Besitzer immer, andere nur in Hörweite eines Begleiters.
     * Weiter weg Gesprochenes verlässt den Server nie (geht nicht an die Erkennung im Host).
     */
    public static Set<UUID> listeners(Collection<Speaker> players, Collection<Spot> spots, double range) {
        Set<UUID> out = new HashSet<>();
        for (Speaker p : players) {
            if (pick(p, spots, range).isPresent()) {
                out.add(p.id());
            }
        }
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }
}
