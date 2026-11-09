package com.blackn0va.baumeister.relay;

import java.util.regex.Pattern;

/**
 * Freigabeliste der Pfade (docs/kanal-v1.md, Abschnitt 5): Das Relay prüft vor dem Senden, die App prüft erneut.
 *
 * Erlaubt sind genau /health sowie /v1/… und /baumeister/v1/… mit Segmenten aus [A-Za-z0-9_-], optional eine Abfrage.
 * Eine Freigabeliste statt einer Sperrliste: Alles, woran beim Schreiben keiner gedacht hat („/v1/%2e%2e“, „//v1“,
 * „http://…“, Rückstrich, Raute), fällt von selbst durch (OWASP A01, ASVS V12.3.1).
 *
 * Strenger als Abschnitt 5, mit Absicht:
 * - Nach „?“ muss mindestens ein Zeichen stehen.
 * - „%“ in der Abfrage nur als vollständiges %XX und nie %2e/%2E – genau so prüft die fertige Host-App (P2,
 *   2026-11-08). Ließe das Relay etwas durch, das die App ablehnt, schlösse die App die GANZE Verbindung (alle
 *   laufenden Aufträge verloren); hier wird es dagegen eine einzelne 404.
 * - Zusätzlich nie ein kodiertes Steuerzeichen (%00–%1F, %7F): Ein kodiertes NUL kann im C++-Server der App einen
 *   String abschneiden. Das Plugin schickt so etwas nie (Spielernamen sind [A-Za-z0-9_], URLEncoder kodiert
 *   Leerzeichen als „+“ und Umlaute als %C3%xx).
 *
 * Das Muster läuft linear: Die Segmentklasse enthält kein „/“, die beiden Abfrage-Zweige beginnen verschieden.
 *
 * Threads: zustandslos. Fehler: keine (false).
 */
final class PathRules {

    /** Längste erlaubte Anfrage-Adresse (Pfad samt Abfrage), Abschnitt 5. */
    static final int MAX_LENGTH = 2048;

    private static final Pattern ALLOWED = Pattern.compile("/health"
            + "|/(?:v1|baumeister/v1)(?:/[A-Za-z0-9_-]+)+"
            + "(?:\\?(?:[A-Za-z0-9_.~=&+,-]|%(?:2[0-9A-Da-dFf]|[3-6][0-9A-Fa-f]|7[0-9A-Ea-e]|[89A-Fa-f][0-9A-Fa-f]))+)?");

    private PathRules() {
    }

    static boolean allowed(String target) {
        return target != null && target.length() <= MAX_LENGTH && ALLOWED.matcher(target).matches();
    }

    /** Pfad ohne Abfrage – fürs Log: Die Abfrage kann Spielernamen tragen (?spieler=…), die gehören nicht ins Journal. */
    static String withoutQuery(String target) {
        int q = target.indexOf('?');
        return q < 0 ? target : target.substring(0, q);
    }
}
