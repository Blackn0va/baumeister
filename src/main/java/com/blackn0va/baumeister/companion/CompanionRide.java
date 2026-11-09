package com.blackn0va.baumeister.companion;

/**
 * Mitfahren (Wunsch 2026-11-07): Sitzt der Spieler in einem
 * Fahrzeug mit freiem Platz, steigt Vera dazu, wie ein Mitspieler. Vorher schwebte sie neben einem Boot in der Luft
 * hinterher (CompanionService.follow: „reitend hinter ihm in der Luft“) – auf dem Wasser sah das aus wie ein Geist und
 * verlor das Boot bei voller Fahrt (8 Blöcke/s gegen ihre 6,8).
 *
 * Plätze nach Vanilla: Boot und Floß 2, Kamel 2, Glücklicher Ghast 4; Truhenboot, Lore, Pferd, Schwein, Schreiter 1
 * – dort bleibt es beim Folgen. Reine Rechnung ohne Bukkit; CompanionService setzt sie um.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class CompanionRide {

    /** So nah muss sie sein, um einzusteigen; weiter weg folgt sie erst (sonst stiege sie über 100 Blöcke ein). */
    public static final double BOARD_FROM = 12.0;

    private CompanionRide() {
    }

    /** Plätze eines Fahrzeugs nach seinem Entity-Schlüssel ohne Namensraum („oak_boat“, „camel“). */
    public static int seats(String typeKey) {
        if (typeKey == null) {
            return 1;
        }
        if (typeKey.endsWith("_chest_boat") || typeKey.endsWith("_chest_raft")) {
            return 1;
        }
        if (typeKey.endsWith("_boat") || typeKey.endsWith("_raft") || typeKey.equals("boat")
                || typeKey.equals("camel")) {
            return 2;
        }
        return typeKey.equals("happy_ghast") ? 4 : 1;
    }

    /** Einsteigen? Freier Platz, sie ist nah genug und sitzt noch nirgends. */
    public static boolean board(String typeKey, int passengers, double distance, boolean alreadyRiding) {
        return !alreadyRiding && distance <= BOARD_FROM && passengers < seats(typeKey);
    }
}
