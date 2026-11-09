package com.blackn0va.baumeister.relay;

import java.io.IOException;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Plugin-Seite 127.0.0.1:18080 (docs/kanal-v1.md, Abschnitt 6).
 *
 * IMMER gebunden, solange der Prozess lebt – auch ohne App. Ein freier Port hieße: Jeder lokale Prozess kann sich dort
 * hinsetzen und dem Plugin Antworten unterschieben; über „gehört“ kommen Sätze, die Vera als Worte eines Spielers
 * behandelt. Ohne angemeldete App wird jede Verbindung angenommen und sofort mit RST geschlossen – für das Plugin
 * dasselbe Bild wie ein fehlender Tunnel. Geht der Port trotzdem verloren (Socket von außen geschlossen), endet der
 * Prozess (Relay.fatal), und systemd startet ihn neu, statt dass er ohne Port weiterläuft.
 *
 * Wächter: Bei festem Port bindet das Relay denselben Port auch auf der anderen Loopback-Familie (::1 bzw.
 * 127.0.0.1). Stünde in llm.url einmal „localhost“, löste Java das zuerst zu ::1 auf – ein fremder Prozess dort bekäme
 * die Anfragen. Ist ::1 nicht verfügbar (IPv6 aus), reicht ein Hinweis; ist er BELEGT, warnt das Log laut.
 *
 * Threads: ein Annahme-Thread je Socket, ein Thread je Anfrage (höchstens maxLocal, darüber RST).
 * Fehler: Annahmefehler werden gedrosselt geloggt; Verlust des Ports ist fatal.
 */
final class LocalListener {

    private static final Logger LOG = RelayLog.LOG;

    private final Relay relay;
    private final List<ServerSocket> servers = new ArrayList<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong counter = new AtomicLong();

    LocalListener(Relay relay) {
        this.relay = relay;
    }

    /** Bindet sofort (vor allem anderen): Ab hier gehört der Port dem Relay. */
    void bind(InetSocketAddress address) throws IOException {
        ServerSocket main = new ServerSocket();
        main.bind(address, 128);
        servers.add(main);
        if (address.getPort() != 0) {
            guard(address);
        }
    }

    private void guard(InetSocketAddress address) {
        InetSocketAddress other;
        try {
            other = new InetSocketAddress(InetAddress.getByName(
                    address.getAddress() instanceof Inet4Address ? "::1" : "127.0.0.1"), address.getPort());
        } catch (IOException e) {
            LOG.info("relay | lokal_wächter_aus | grund=" + e.getClass().getSimpleName());
            return;
        }
        ServerSocket g = null;
        try {
            g = new ServerSocket();
            g.bind(other, 128);
            servers.add(g);
            LOG.info("relay | lokal_wächter | adresse=" + other.getAddress().getHostAddress() + ":" + other.getPort());
        } catch (BindException e) {
            Io.close(g);
            String m = String.valueOf(e.getMessage());
            if (m.contains("in use") || m.contains("bereits")) {
                LOG.warning("relay | lokal_wächter_belegt | adresse=" + other.getAddress().getHostAddress() + ":"
                        + other.getPort() + " (ein anderer Prozess hält den Port – llm.url muss 127.0.0.1 nennen)");
            } else {
                LOG.info("relay | lokal_wächter_aus | adresse=" + other.getAddress().getHostAddress()
                        + " grund=nicht_verfügbar");
            }
        } catch (IOException e) {
            Io.close(g);
            LOG.info("relay | lokal_wächter_aus | grund=" + e.getClass().getSimpleName());
        }
    }

    int port() {
        return servers.get(0).getLocalPort();
    }

    String describe() {
        StringBuilder b = new StringBuilder();
        for (ServerSocket s : servers) {
            b.append(b.isEmpty() ? "" : ",").append(s.getInetAddress().getHostAddress()).append(':')
                    .append(s.getLocalPort());
        }
        return b.toString();
    }

    void start() {
        for (ServerSocket s : servers) {
            Io.start("relay-lokal-annahme-" + s.getInetAddress().getHostAddress(), () -> acceptLoop(s));
        }
    }

    void close() {
        for (ServerSocket s : servers) {
            Io.close(s);
        }
    }

    int active() {
        return active.get();
    }

    private void acceptLoop(ServerSocket server) {
        while (true) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (relay.closing()) {
                    return;
                }
                if (server.isClosed()) {
                    relay.fatal("lokaler_port_verloren");
                    return;
                }
                // Etwa EMFILE (zu viele offene Dateien): kurz warten statt im Kreis zu drehen; der Port bleibt.
                relay.throttle().reject("lokal_annahmefehler", "relay | lokal_annahmefehler | grund="
                        + e.getClass().getSimpleName());
                pause();
                continue;
            }
            if (!relay.hub().hasApp()) {
                Io.reset(s);
                relay.throttle().reject("lokal_ohne_app", "relay | lokal_ohne_app | aktion=rst");
                continue;
            }
            if (active.incrementAndGet() > relay.limits().maxLocal()) {
                active.decrementAndGet();
                Io.reset(s);
                relay.throttle().reject("lokal_zu_viele", "relay | lokal_zu_viele | aktion=rst grenze="
                        + relay.limits().maxLocal());
                continue;
            }
            try {
                Io.start("relay-lokal-" + counter.incrementAndGet(), new LocalExchange(relay, s,
                        active::decrementAndGet));
            } catch (RuntimeException | OutOfMemoryError e) {
                // Thread ließ sich nicht starten (Grenze des Systems): diese Verbindung ohne Antwort beenden.
                active.decrementAndGet();
                Io.reset(s);
                relay.throttle().reject("lokal_kein_thread", "relay | lokal_kein_thread | grund="
                        + e.getClass().getSimpleName());
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
