package com.blackn0va.baumeister.town;

/**
 * Tagesablauf der Bewohner nach Weltzeit (Vertrag v008, AK-23): morgens zur Arbeit, mittags bummeln, nachmittags
 * wieder arbeiten, abends heim, nachts schlafen – dann sind sie nicht da.
 *
 * Weltzeit 0 ist 6:00 Uhr, 1000 Ticks sind eine Stunde (Minecraft). Gerechnet wird mit der Weltzeit, nicht mit der
 * Wanduhr: Wer /time set day tippt, sieht sofort Morgenbetrieb, und ein Tag dauert 20 Minuten wie im Spiel.
 *
 * Warum Nacht = keine Bewohner: Draußen ist es dunkel und voller Monster, Bewohner kämpfen in v008 nicht (Nicht-Ziel)
 * und sollen keine Opfer sein. Außerdem spart es Takt und KI-Zeit in der Hälfte des Tages.
 *
 * Threads: zustandslos. Fehler: keine (negative Zeiten werden in den Tag gefaltet).
 */
public final class DayPlan {

    public enum Phase {
        /** 6–9 Uhr: auf dem Weg zur Arbeit. */
        MORNING(Goal.WORK),
        /** 9–12 Uhr: arbeiten. */
        WORK(Goal.WORK),
        /** 12–14 Uhr: bummeln, dorthin, wo Spieler sind. */
        NOON(Goal.PLAZA),
        /** 14–17 Uhr: wieder arbeiten. */
        AFTERNOON(Goal.WORK),
        /** 17–19 Uhr: Feierabend, nach Hause. */
        EVENING(Goal.HOME),
        /** 19–6 Uhr: schlafen – Bewohner gehen heim und verschwinden. */
        NIGHT(Goal.SLEEP);

        private final Goal goal;

        Phase(Goal goal) {
            this.goal = goal;
        }

        public Goal goal() {
            return goal;
        }

        /** Für Status und KI-Kontext. */
        public String label() {
            return switch (this) {
                case MORNING -> "auf dem Weg zur Arbeit";
                case WORK, AFTERNOON -> "bei der Arbeit";
                case NOON -> "Mittagspause, bummelt";
                case EVENING -> "Feierabend, auf dem Heimweg";
                case NIGHT -> "schläft";
            };
        }
    }

    /** Wohin ein Bewohner in einer Phase will. */
    public enum Goal {
        WORK, PLAZA, HOME, SLEEP
    }

    private DayPlan() {
    }

    /** Uhrzeit 0–23 aus der Weltzeit (0 = 6 Uhr). */
    public static int hour(long worldTime) {
        long t = Math.floorMod(worldTime, 24_000L);
        return (int) ((t / 1000 + 6) % 24);
    }

    public static Phase phase(long worldTime) {
        int h = hour(worldTime);
        if (h >= 6 && h < 9) {
            return Phase.MORNING;
        }
        if (h >= 9 && h < 12) {
            return Phase.WORK;
        }
        if (h >= 12 && h < 14) {
            return Phase.NOON;
        }
        if (h >= 14 && h < 17) {
            return Phase.AFTERNOON;
        }
        if (h >= 17 && h < 19) {
            return Phase.EVENING;
        }
        return Phase.NIGHT;
    }
}
