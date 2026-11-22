package com.blackn0va.baumeister.town;

/**
 * Wie viele Bewohner es gerade geben soll und wie die übrigen gehen (Vertrag v008, AK-22).
 *
 * - Modus aus oder Host nicht da: sofort alle weg (REMOVE_NOW). Vorgabe des Vertrags: „binnen 60 s“; sofort ist die
 *   einfachste Art, das einzuhalten, und ohne KI wären Bewohner stumme Puppen.
 * - Nacht: niemand Neues; wer da ist, geht heim und verschwindet dort, spätestens nach NIGHT_LEAVE_MS (GO_HOME).
 * - Sonst: count Bewohner, verteilt auf die online Spieler; ohne Spieler keiner (niemand, der sie sähe).
 *
 * Neue Bewohner höchstens einer je SPAWN_GAP_TICKS, damit das Erscheinen von 12 nicht in einen Tick fällt.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class Population {

    public static final int SPAWN_GAP_TICKS = 40;
    /** Nachts so lange heimgehen, dann verschwinden, auch wenn das Zuhause nicht erreicht ist. */
    public static final long NIGHT_LEAVE_MS = 45_000L;
    /** Weiter als das von jedem Spieler der Welt: weg (dort sieht ihn keiner, und Chunks können entladen). */
    public static final double FAR_FROM_PLAYERS = 64.0;

    public enum Leave {
        STAY, GO_HOME, REMOVE_NOW
    }

    private Population() {
    }

    /** Sollzahl. */
    public static int target(boolean enabled, boolean hostUp, DayPlan.Phase phase, int count, int playersOnline) {
        if (!enabled || !hostUp || playersOnline <= 0 || phase == DayPlan.Phase.NIGHT) {
            return 0;
        }
        return Math.clamp(count, 0, TownSettings.MAX_COUNT);
    }

    /** Was mit den Bewohnern geschieht, die schon da sind. */
    public static Leave leave(boolean enabled, boolean hostUp, DayPlan.Phase phase) {
        if (!enabled || !hostUp) {
            return Leave.REMOVE_NOW;
        }
        return phase == DayPlan.Phase.NIGHT ? Leave.GO_HOME : Leave.STAY;
    }

    /** Darf jetzt einer erscheinen? */
    public static boolean maySpawn(int active, int target, long tick, long lastSpawnTick) {
        return active < target && tick - lastSpawnTick >= SPAWN_GAP_TICKS;
    }
}
