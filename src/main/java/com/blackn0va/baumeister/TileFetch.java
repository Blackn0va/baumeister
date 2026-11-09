package com.blackn0va.baumeister;

import com.blackn0va.baumeister.WorldTiles.GeoBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Abfragefolge einer Weltbau-Kachel: eine Overpass-Abfrage, über der Datumsgrenze zwei (GeoBox.parts), mit Versuchen
 * je Teil. Rein, ohne Server – WorldBuildService führt sie im Bukkit-Takt, die Drossel liegt dort.
 *
 * Warum eigene Klasse: Die Zwei-Teil-Folge (zweiter Teil erst nach Ablauf der Drossel, Versuche je Teil neu gezählt,
 * Aufgeben, wenn ein Teil endgültig scheitert, Zusammenführen erst mit allen Teilen) war in WorldBuildService
 * verstreut und ungetestet (Prüfer-Befund 2026-10-29). Wer answered() schon nach dem ersten Teil true liefern lässt,
 * baut über der Datumsgrenze nur die halbe Kachel und legt sie so in den Zwischenspeicher.
 *
 * Threads: nur Hauptthread.
 */
final class TileFetch {

    private List<GeoBox> parts = List.of();
    private int part;
    private final List<String> answers = new ArrayList<>();
    private int attempts;

    /** Neue Kachel: Teile der Box, alte Antworten und Versuche verworfen. */
    void start(GeoBox box) {
        parts = box.parts();
        part = 0;
        answers.clear();
        attempts = 0;
    }

    /** Die nächste Abfrage (Teil der Box). */
    GeoBox query() {
        return parts.get(part);
    }

    /** Fehlversuche des laufenden Teils (wählt den Spiegel und steht in der Statusmeldung). */
    int attempts() {
        return attempts;
    }

    /** Für die Logzeile: „ teil=2/2“ über der Datumsgrenze, sonst leer. */
    String partText() {
        return parts.size() > 1 ? " teil=" + (part + 1) + "/" + parts.size() : "";
    }

    /**
     * Antwort des laufenden Teils.
     *
     * @return true, wenn alle Teile da sind (dann merged()); false: nächster Teil, wieder mit dem ersten Versuch
     */
    boolean answered(String answer) {
        answers.add(answer);
        if (part + 1 < parts.size()) {
            part++;
            attempts = 0;
            return false;
        }
        return true;
    }

    /**
     * Fehlschlag des laufenden Teils.
     *
     * @return true: denselben Teil noch einmal (nächster Spiegel); false: aufgeben – auch ein schon geladener erster
     *         Teil wird dann nicht gebaut und nicht gespeichert
     */
    boolean failed(boolean retryable, int maxAttempts) {
        attempts++;
        return retryable && attempts < maxAttempts;
    }

    /** Die Antworten aller Teile (erst nach answered() == true vollständig). */
    List<String> answers() {
        return List.copyOf(answers);
    }

    /** Eine Antwort für Zwischenspeicher und Import: unverändert oder zusammengeführt (OverpassMerge). */
    static String merged(List<String> answers) {
        return answers.size() == 1 ? answers.get(0) : OverpassMerge.merge(answers);
    }
}
