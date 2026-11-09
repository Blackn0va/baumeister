package com.blackn0va.baumeister.relay;

import java.time.Duration;

/**
 * Alle Fristen und Grenzen des Relays. standard() sind die Werte aus docs/kanal-v1.md (Abschnitte 4 und 6) und dem
 * Plan; Tests bauen kürzere über den Builder.
 *
 * Byte-Budget (Abschnitt 4: „≤ 48 MiB“): min(48 MiB, 40 % von -Xmx). Mit den Optionen der Unit (-Xmx32m, SerialGC)
 * meldet die JVM maxMemory = 30 MiB, das Budget ist also 12,4 MiB. 48 MiB gepufferte Antworten passten gar nicht in
 * diesen Heap – das Relay stiege mit OutOfMemoryError aus (ExitOnOutOfMemoryError), und 18080 wäre bis zum Neustart
 * frei. Gemessen 2026-11-08 (RelayMeasurementTest, Windows, JDK 21.0.12, mit einem Plugin, das nicht liest, bis das
 * Budget griff): Heap danach 17 MiB von 26 MiB belegt, Arbeitsspeicher höchstens 76 MiB. Wer -Xmx hebt, bekommt mehr
 * Budget, höchstens 48 MiB.
 *
 * Threads: unveränderlich. Fehler: keine.
 */
record Limits(Duration handshake, Duration hello, Duration queueWait, Duration response, Duration ping,
        Duration silence, Duration localHeader, Duration localRead, Duration tick, Duration pinReload,
        long byteBudget, int maxQueue, int maxLocal, int perAddress, int unauthenticated,
        Duration banMin, Duration banMax, int banEntries) {

    static final long SPEC_BUDGET = 48L * 1024 * 1024;

    static Limits standard() {
        return new Builder().build();
    }

    /** Budget aus der Heap-Obergrenze, höchstens der Wert der Spezifikation. */
    static long budgetFor(long maxHeap) {
        return Math.min(SPEC_BUDGET, maxHeap / 10 * 4);
    }

    /** Für Tests: Werte einzeln ändern. */
    static final class Builder {

        /** TLS-Handshake ab Annahme (Abschnitt 1, Plan). */
        Duration handshake = Duration.ofSeconds(10);
        /** Upgrade und hallo nach dem Handshake. */
        Duration hello = Duration.ofSeconds(10);
        /** So lange darf eine Anfrage auf eine App warten (Abschnitt 4). */
        Duration queueWait = Duration.ofSeconds(30);
        /** Längste Wartezeit auf das Ende einer Antwort (Abschnitt 4: Plugin erlaubt bis 1800 s). */
        Duration response = Duration.ofSeconds(1900);
        Duration ping = Duration.ofSeconds(20);
        /** Kein Rahmen so lange: Verbindung tot (Abschnitt 4). */
        Duration silence = Duration.ofSeconds(45);
        /** Kopf einer Plugin-Anfrage komplett. */
        Duration localHeader = Duration.ofSeconds(10);
        /** Je Lesevorgang beim Body des Plugins. */
        Duration localRead = Duration.ofSeconds(10);
        /** Takt für Pings, Stille, Pins und Aufräumen. */
        Duration tick = Duration.ofSeconds(1);
        Duration pinReload = Duration.ofSeconds(2);
        long byteBudget = budgetFor(Runtime.getRuntime().maxMemory());
        /** Wartende Anfragen (Abschnitt 4). */
        int maxQueue = 64;
        /** Gleichzeitige Plugin-Verbindungen; darüber RST. 64 wartende + 6 je App laufende + Reserve. */
        int maxLocal = 128;
        /** Gleichzeitige Handshakes je IPv4-Adresse bzw. IPv6-/64. */
        int perAddress = 4;
        /** Gleichzeitige Verbindungen ohne Anmeldung insgesamt. */
        int unauthenticated = 32;
        Duration banMin = Duration.ofSeconds(10);
        Duration banMax = Duration.ofMinutes(10);
        /** Höchstens so viele Adressen merkt sich die Sperrliste. */
        int banEntries = 4096;

        Limits build() {
            return new Limits(handshake, hello, queueWait, response, ping, silence, localHeader, localRead, tick,
                    pinReload, byteBudget, maxQueue, maxLocal, perAddress, unauthenticated, banMin, banMax,
                    banEntries);
        }
    }
}
