package com.blackn0va.baumeister.companion;

/**
 * Stimmung eines Satzes von Vera für ihren kurzen Plapperton (CompanionService.say). Wunsch 2026-11-06 –
 * Trickfilmfiguren „quieken“ beim Reden; ein kurzer, heller Laut je
 * Satz macht Vera lebendig, auch ohne Sprachausgabe.
 *
 * Tonhöhen für Minecraft-Klänge (0,5–2,0): Frage steigt (1,6), Ausruf hüpft (1,8), sonst ruhig (1,3).
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class CompanionMood {

    public enum Mood {
        QUESTION(1.6f), EXCITED(1.8f), CALM(1.3f);

        private final float pitch;

        Mood(float pitch) {
            this.pitch = pitch;
        }

        public float pitch() {
            return pitch;
        }
    }

    private CompanionMood() {
    }

    /** Letztes Satzzeichen entscheidet; Ausrufe gewinnen gegen Fragen („Echt?!“ ist aufgeregt). */
    public static Mood of(String text) {
        if (text == null) {
            return Mood.CALM;
        }
        String t = text.strip();
        int end = t.length();
        while (end > 0 && "\"“”')".indexOf(t.charAt(end - 1)) >= 0) {
            end--;
        }
        String tail = t.substring(Math.max(0, end - 2), end);
        if (tail.contains("!")) {
            return Mood.EXCITED;
        }
        if (tail.contains("?")) {
            return Mood.QUESTION;
        }
        return Mood.CALM;
    }
}
