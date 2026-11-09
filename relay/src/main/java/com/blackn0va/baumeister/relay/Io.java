package com.blackn0va.baumeister.relay;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kleine Helfer für Sockets und Threads.
 *
 * Threads: zustandslos. Fehler: Schließfehler werden auf FEIN geloggt – beim Schließen ist die Gegenseite oft schon
 * weg, und genau das ist dann das Ziel.
 */
final class Io {

    private static final Logger LOG = RelayLog.LOG;

    private Io() {
    }

    /**
     * Verbindung mit RST beenden (SO_LINGER 0): Die Gegenseite sieht „connection reset“ statt eines sauberen Endes.
     * So schließt das Relay alles, was ohne Antwort endet (docs/kanal-v1.md, Abschnitt 6) – das Plugin behandelt es
     * wie einen weggebrochenen Tunnel. Schließen aus einem anderen Thread weckt einen blockierten Lese- oder
     * Schreibvorgang; darauf bauen die Fristen.
     */
    static void reset(Socket s) {
        if (s == null || s.isClosed()) {
            return;
        }
        try {
            s.setSoLinger(true, 0);
        } catch (IOException e) {
            LOG.log(Level.FINE, "relay | linger_nicht_gesetzt", e);
        }
        close(s);
    }

    /**
     * Sauberes Ende nach einer Ablehnung: FIN senden, dann höchstens millis lang lesen und verwerfen, bis die
     * Gegenseite schließt. Erst danach schließen – mit ungelesenen Bytes im Puffer schickte close() ein RST.
     */
    static void lingerClose(Socket s, long millis) {
        try {
            s.shutdownOutput();
            long until = System.nanoTime() + millis * 1_000_000L;
            byte[] sink = new byte[4096];
            InputStream in = s.getInputStream();
            while (true) {
                long left = (until - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    break;
                }
                s.setSoTimeout((int) left);
                if (in.read(sink) < 0) {
                    break;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "relay | nachlauf_beendet", e);
        } finally {
            close(s);
        }
    }

    /**
     * Frist oder Riegel planen. Ist der Takt schon beendet (das Relay fährt herunter), läuft die Aufgabe sofort, statt
     * dass schedule() wirft: Vorher flog dann eine RejectedExecutionException aus dem Leser-Thread einer Sitzung, und
     * der Socket blieb offen.
     */
    static Future<?> schedule(ScheduledExecutorService scheduler, Runnable task, long millis) {
        try {
            return scheduler.schedule(task, millis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }
    }

    static void close(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "relay | schließen_fehlgeschlagen", e);
        }
    }

    /**
     * Eigener Thread: Daemon (der JVM-Ausgang hängt nie an ihm), benannt (jstack, Log), und eine Ausnahme bis zur
     * Thread-Grenze landet im Log statt still zu verschwinden.
     */
    static Thread start(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((th, e) -> LOG.log(Level.SEVERE, "relay | thread_abgestürzt | thread="
                + th.getName(), e));
        t.start();
        return t;
    }
}
