package com.blackn0va.baumeister;

import java.util.function.LongSupplier;

/**
 * Wann ein Ausfall des Hosts ins Log gehört: beim ersten Fehler, danach nur alle 15 Minuten als Erinnerung, und einmal,
 * wenn er wieder antwortet.
 *
 * Vorfall dev01 2026-11-04 19:26–20:19: Die App lief nicht, Begleiter-Abholung und GUI-Bericht schrieben je eine Zeile
 * pro Minute – 106 gleiche Zeilen „host_nicht_erreichbar“ in 53 Minuten, dazwischen gingen die echten Meldungen unter.
 * Die Minutensperre (vorher in CompanionVoiceLink und HostReporter) begrenzte nur die Rate, nicht die Wiederholung.
 * Wer das auf eine Zeitsperre zurückdreht, bekommt das volle Log zurück; ohne die Erinnerung sähe man einem stundenlangen
 * Ausfall im Log nicht an, dass er noch andauert.
 *
 * Threads: aus dem HttpClient-Pool, deshalb synchronisiert. Fehler: keine.
 */
final class OutageLog {

    static final long REMIND_NANOS = 15L * 60 * 1_000_000_000L;
    static final int UP = -1;

    private final LongSupplier clock;
    private boolean down;
    private long downSince;
    private long lastLog;

    OutageLog(LongSupplier clock) {
        this.clock = clock;
    }

    /** Fehler: Minuten seit Beginn des Ausfalls, wenn geloggt werden soll (0 = gerade ausgefallen), sonst UP. */
    synchronized long failure() {
        long now = clock.getAsLong();
        if (!down) {
            down = true;
            downSince = now;
            lastLog = now;
            return 0;
        }
        if (now - lastLog >= REMIND_NANOS) {
            lastLog = now;
            return (now - downSince) / 60_000_000_000L;
        }
        return UP;
    }

    /** Erfolg: Minuten des eben beendeten Ausfalls, wenn einer lief (einmal loggen), sonst UP. */
    synchronized long success() {
        if (!down) {
            return UP;
        }
        down = false;
        return (clock.getAsLong() - downSince) / 60_000_000_000L;
    }
}
