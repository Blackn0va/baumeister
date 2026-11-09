package com.blackn0va.baumeister.relay;

/**
 * Konfiguration oder Schlüssel unbrauchbar: Das Relay startet nicht (fail-closed) und nennt den Grund auf Deutsch.
 * Main beendet sich dann mit {@link Main#EXIT_CONFIG}; die Unit startet bei diesem Code nicht im Kreis neu.
 */
final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    ConfigException(String message) {
        super(message);
    }

    ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
