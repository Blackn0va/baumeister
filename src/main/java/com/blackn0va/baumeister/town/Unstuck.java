package com.blackn0va.baumeister.town;

/**
 * Was ein Bewohner tut, der festhängt (Vertrag v013, AK-42): erst neu planen, dann seitlich ausweichen und springen,
 * dann ein Umweg-Ziel, und erst zuletzt teleportieren – und das nur, wenn kein Spieler hinsieht.
 *
 * Vorher (bis T-035) teleportierte TownLife beim ersten Feststecken (3 s ohne Annäherung, Progress) sofort aufs
 * Teilziel. Ein Bewohner, der um eine Mauer herumging, entfernte sich dabei vom Teilziel und galt als festgesteckt –
 * er sprang sichtbar ans Ziel, obwohl er auf dem Weg war. Jede Stufe hier kommt erst nach erneuten Progress.STUCK_TICKS
 * ohne Annäherung; wer vorankommt (progressed), fängt von vorn an.
 *
 * Gesehen = ein Spieler in VIEW Blöcken. Dann nie Teleport, stattdessen PAUSE: kurz stehen und ein anderes Ziel
 * nehmen. Wer das zurückdreht, lässt Bewohner vor den Augen der Spieler springen.
 *
 * Threads: Hauptthread, je Bewohner eines. Fehler: keine.
 */
public final class Unstuck {

    /** In diesem Abstand sieht ein Spieler den Bewohner (waagrecht, Blöcke). */
    public static final double VIEW = 24.0;

    public enum Action {
        /** Weg neu suchen (alter Weg verworfen). */
        REPLAN,
        /** 2–3 Blöcke zur Seite auf festen Boden, mit Sprung. */
        SIDESTEP,
        /** Teilziel um 60° gedreht, halb so weit: an einem Hindernis vorbei. */
        DETOUR,
        /** Letzter Ausweg, nur ungesehen. */
        TELEPORT,
        /** Gesehen und alles versucht: kurz stehen, anderes Ziel. */
        PAUSE
    }

    private int level;

    /** Wieder festgesteckt: die nächste Stufe. watched = ein Spieler in VIEW. */
    public Action next(boolean watched) {
        level++;
        return switch (level) {
            case 1 -> Action.REPLAN;
            case 2 -> Action.SIDESTEP;
            case 3 -> Action.DETOUR;
            default -> {
                level = 0;
                yield watched ? Action.PAUSE : Action.TELEPORT;
            }
        };
    }

    /** Er kommt wieder voran: von vorn. */
    public void progressed() {
        level = 0;
    }

    public int level() {
        return level;
    }
}
