package com.blackn0va.baumeister.companion;

/**
 * Veras Körpersprache: welche Pose, ob sie im Dunkeln eine Fackel hält. Reine Regeln ohne Bukkit, damit testbar.
 *
 * Gemessen 2026-11-06 auf Paper 1.21.11-132 (probes/mannequin/PoseProbe): Ein Mannequin nimmt genau STANDING,
 * SNEAKING, SWIMMING, FALL_FLYING und SLEEPING an (Mannequin.VALID_POSES, javap) und behält jede über 40 Ticks, wenn
 * sie fest gesetzt wird (Paper setPose(Pose, true)). Vorher stand Vera immer – auch neben einem schleichenden, einem
 * schwimmenden oder einem schlafenden Spieler.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class CompanionBody {

    /** Unter dieser Lichtstärke (0–15) hält sie eine Fackel – ab 7 dürfen Monster entstehen. */
    public static final int DARK = 8;

    public enum Pose {
        STANDING, SNEAKING, SWIMMING, FALL_FLYING, SLEEPING
    }

    /** Was der Spieler gerade tut und wo Vera ist. hover = sie schwebt (Spieler fliegt, CompanionService.walkMode). */
    public record Situation(boolean playerSneaking, boolean playerSwimming, boolean playerGliding,
            boolean playerSleeping, boolean veraInWater, boolean veraMoving, boolean hover) {
    }

    private CompanionBody() {
    }

    public static Pose pose(Situation s) {
        if (s.playerSleeping() && !s.veraMoving()) {
            return Pose.SLEEPING;
        }
        if (s.playerGliding() && s.hover()) {
            return Pose.FALL_FLYING;
        }
        // Schwimmpose nur in Bewegung im Wasser: stehend im seichten Wasser sähe sie aus wie umgefallen.
        if (s.veraInWater() && s.veraMoving() && (s.playerSwimming() || !s.hover())) {
            return Pose.SWIMMING;
        }
        if (s.playerSneaking()) {
            return Pose.SNEAKING;
        }
        return Pose.STANDING;
    }

    /** Fackel in die Nebenhand bei Dunkelheit, nicht beim Schlafen und nicht unter Wasser (dort erlischt sie). */
    public static boolean torch(int light, Pose pose, boolean inWater) {
        return light < DARK && pose != Pose.SLEEPING && !inWater;
    }
}
