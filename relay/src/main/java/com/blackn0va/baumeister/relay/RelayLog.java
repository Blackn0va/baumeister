package com.blackn0va.baumeister.relay;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

/**
 * Log des Relays: java.util.logging auf stdout (systemd legt es ins Journal und stempelt die Zeit selbst).
 * Format „relay | ereignis | schlüssel=wert …“, Ereignisse deutsch und klein.
 *
 * Was nie ins Log kommt: Inhalte von Anfragen und Antworten, ganze Pins (nur Pins.brief), Abfragen hinter „?“ (dort
 * stehen Spielernamen), Passwörter (ASVS V7.1.1, V7.1.2).
 *
 * Abweisungen von außen laufen über Throttle: höchstens 20 Zeilen je Minute, der Rest als eine Summenzeile. Sonst
 * schriebe jeder Portscanner das Journal voll – und eine echte Meldung ginge darin unter (ASVS V7.1.4).
 *
 * Threads: beliebig (JUL ist threadsicher, Throttle synchronisiert). Fehler: keine.
 */
final class RelayLog {

    static final Logger LOG = Logger.getLogger("baumeister.relay");

    private RelayLog() {
    }

    /** Einmal beim Start (Main): ein Handler auf stdout, UTF-8, jede Zeile sofort geschrieben. */
    static void toStdout(Level level) {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        StreamHandler out = new StreamHandler(System.out, new Line()) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        try {
            // Unabhängig von LANG: Unter systemd läuft der Dienst oft mit LANG=C, stdout wäre dann ASCII („?“ statt ü).
            out.setEncoding("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 fehlt im JDK", e);
        }
        out.setLevel(level);
        root.addHandler(out);
        root.setLevel(level);
    }

    /** Eine Zeile je Eintrag: Stufe, Text, bei FEHLER mit Stack-Trace (nur eigene Ausnahmen, nie Inhalte). */
    static final class Line extends Formatter {

        @Override
        public String format(LogRecord r) {
            String level = r.getLevel() == Level.SEVERE ? "FEHLER"
                    : r.getLevel() == Level.WARNING ? "WARNUNG"
                    : r.getLevel() == Level.INFO ? "INFO" : "FEIN";
            StringBuilder b = new StringBuilder(level).append(' ').append(formatMessage(r));
            Throwable t = r.getThrown();
            if (t != null) {
                b.append(" | ausnahme=").append(t.getClass().getName());
                if (r.getLevel() == Level.SEVERE) {
                    StringWriter w = new StringWriter();
                    t.printStackTrace(new PrintWriter(w));
                    b.append(System.lineSeparator()).append(w.toString().stripTrailing());
                }
            }
            return b.append(System.lineSeparator()).toString();
        }
    }

    /** Begrenzt Abweisungs-Zeilen: perWindow je Fenster, der Rest wird gezählt und mit flush() als Summe gemeldet. */
    static final class Throttle {

        private final int perWindow;
        private final long windowNanos;
        private long windowStart;
        private int used;
        private final Map<String, Long> suppressed = new LinkedHashMap<>();

        Throttle(int perWindow, long windowNanos) {
            this.perWindow = perWindow;
            this.windowNanos = windowNanos;
            this.windowStart = System.nanoTime();
        }

        /** event: Ereignis ohne Werte (zum Zählen); line: ganze Logzeile. */
        void reject(String event, String line) {
            boolean log;
            synchronized (this) {
                roll(System.nanoTime());
                log = used < perWindow;
                if (log) {
                    used++;
                } else {
                    suppressed.merge(event, 1L, Long::sum);
                }
            }
            if (log) {
                LOG.info(line);
            }
        }

        /** Vom Takt: abgelaufenes Fenster mit Summe abschließen. */
        void flush() {
            roll(System.nanoTime());
        }

        private synchronized void roll(long now) {
            if (now - windowStart < windowNanos) {
                return;
            }
            if (!suppressed.isEmpty()) {
                StringBuilder b = new StringBuilder("relay | abweisungen_zusammengefasst | ");
                suppressed.forEach((e, n) -> b.append(e).append('=').append(n).append(' '));
                LOG.info(b.toString().stripTrailing());
                suppressed.clear();
            }
            windowStart = now;
            used = 0;
        }
    }
}
