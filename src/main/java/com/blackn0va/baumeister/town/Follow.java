package com.blackn0va.baumeister.town;

import java.util.UUID;

/**
 * Ein Bewohner folgt einem Spieler, der ihn darum gebeten hat (Vertrag v013, AK-42, „Lotte, komm mit“).
 *
 * Endet von selbst, damit kein Bewohner für immer an einem Spieler hängt: nach MAX_MS, beim Weltwechsel des Spielers,
 * wenn er offline geht oder weiter als LOSE Blöcke weg ist (Teleport, Elytra, Reittier). Ein Bewohner folgt immer nur
 * einem Spieler; ein zweiter „komm mit“ übernimmt ihn (start ersetzt).
 *
 * Warum er hinter dem Spieler bleibt (KEEP) statt auf ihn zuzulaufen: Ziel auf dem Spieler hieße, dass der Bewohner
 * in ihn hineinläuft und dann von yieldTo wieder weggeschoben wird – sichtbares Hin und Her.
 *
 * Threads: Hauptthread, je Bewohner einer. Fehler: keine.
 */
public final class Follow {

    /** Höchstens so lange folgt ein Bewohner (10 min), dann geht er seinem Tagesablauf nach. */
    public static final long MAX_MS = 10 * 60_000L;
    /** Weiter weg (waagrecht) gilt der Spieler als verloren. */
    public static final double LOSE = 48.0;
    /** Abstand, den er hinter dem Spieler hält. */
    public static final double KEEP = 2.5;

    public enum End {
        NONE, TIMEOUT, WORLD, OFFLINE, LOST;

        /** Für Log und Status. */
        public String label() {
            return switch (this) {
                case NONE -> "";
                case TIMEOUT -> "höchstzeit";
                case WORLD -> "weltwechsel";
                case OFFLINE -> "offline";
                case LOST -> "zu_weit";
            };
        }
    }

    private UUID player;
    private String world;
    private long since;

    public void start(UUID who, String inWorld, long now) {
        player = who;
        world = inWorld;
        since = now;
    }

    public void stop() {
        player = null;
        world = null;
    }

    public boolean active() {
        return player != null;
    }

    /** Wem er folgt; null = niemandem. */
    public UUID player() {
        return player;
    }

    /** Muss das Folgen enden? online/playerWorld/distance = Lage des Spielers in diesem Takt. */
    public End check(boolean online, String playerWorld, double distance, long now) {
        if (player == null) {
            return End.NONE;
        }
        if (!online) {
            return End.OFFLINE;
        }
        if (world == null || !world.equals(playerWorld)) {
            return End.WORLD;
        }
        if (now - since >= MAX_MS) {
            return End.TIMEOUT;
        }
        if (distance > LOSE) {
            return End.LOST;
        }
        return End.NONE;
    }

    /** Nah genug am Spieler – dann bleibt er stehen (zählt als Stehen mit Grund). */
    public static boolean close(double distance) {
        return distance <= KEEP + 0.8;
    }

    /** Punkt KEEP Blöcke vor dem Spieler auf der Seite des Bewohners: dorthin läuft er. */
    public static double[] behind(double px, double pz, double rx, double rz) {
        double dx = rx - px;
        double dz = rz - pz;
        double d = Math.hypot(dx, dz);
        if (d < 1.0e-6) {
            return new double[] {px + KEEP, pz};
        }
        return new double[] {px + dx / d * KEEP, pz + dz / d * KEEP};
    }
}
