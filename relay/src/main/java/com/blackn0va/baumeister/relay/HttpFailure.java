package com.blackn0va.baumeister.relay;

/**
 * Anfrage abgelehnt, bevor sie weitergeht: Das Relay antwortet selbst mit status (4xx) und schließt. code ist der
 * Grund fürs Log (snake_case, nie mit Inhalt der Anfrage).
 *
 * Nie 5xx: Das Relay erfindet keine 502/503/504 (docs/kanal-v1.md, Abschnitt 6) – der LlmClient des Plugins schickte
 * sonst die ganze Generierung noch einmal.
 */
final class HttpFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;

    HttpFailure(int status, String code) {
        super(code, null, false, false);
        if (status < 400 || status > 499) {
            throw new IllegalArgumentException("Das Relay antwortet selbst nur mit 4xx: " + status);
        }
        this.status = status;
    }

    int status() {
        return status;
    }

    String code() {
        return getMessage();
    }
}
