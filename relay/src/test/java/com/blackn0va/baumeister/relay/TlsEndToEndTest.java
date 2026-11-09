package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.Test;

/**
 * Öffentliche Seite Ende-zu-Ende auf 127.0.0.1 mit je Lauf erzeugten Schlüsseln: Pin-Prüfung, keine Wiederaufnahme,
 * TLS-Parameter, Fristen und Grenzen ohne Anmeldung, eine Sitzung je Pin.
 */
class TlsEndToEndTest {

    /**
     * Die volle Anmeldung (TLS, Upgrade, hallo) muss scheitern – beim Handshake oder spätestens beim ersten Lesen (TLS
     * 1.3 meldet die Ablehnung des Client-Zertifikats erst nach dem Finished des Clients). Vorher prüfte die Hilfe nur
     * „irgendeine IOException“: Ein Client, der durchkam und dann auf das hallo wartete, lief in die Lesefrist und galt
     * als abgewiesen.
     */
    private static void assertRejected(FakeApp.Identity id, int port) {
        assertThrows(IOException.class, () -> FakeApp.connect(id, port, 6).close());
    }

    /** Roher TCP-Socket, der nichts sendet: belegt einen Handshake-Platz. */
    private static Socket idle(int port) throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(5_000);
        return s;
    }

    /** Liest bis EOF/RST; true, wenn die Gegenseite binnen ms Millisekunden geschlossen hat. */
    private static boolean closedWithin(Socket s, long ms) throws IOException {
        s.setSoTimeout((int) ms);
        InputStream in = s.getInputStream();
        try {
            while (in.read() >= 0) {
                // Bytes (z. B. ein TLS-Alert) überlesen
            }
            return true;
        } catch (java.net.SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    @Test
    void pinnedClientIsAcceptedAndItsAnswerReachesThePlugin() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            assertEquals(f.relay.id(), app.relayHello);
            CompletableFuture<RelayFixture.Reply> reply = CompletableFuture.supplyAsync(() -> {
                try {
                    return f.plugin(RelayFixture.post(f.local(), "/v1/chat/completions", "application/json",
                            "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            FakeApp.Request r = app.request();
            assertEquals("POST", r.method());
            assertEquals("/v1/chat/completions", r.path());
            assertEquals("application/json", r.contentType());
            assertEquals("{\"a\":1}", new String(r.body(), StandardCharsets.UTF_8));
            app.answer(r.id(), 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), 65_536);
            RelayFixture.Reply rep = reply.get(10, TimeUnit.SECONDS);
            assertEquals(200, rep.status());
            assertEquals("gpu-pc", rep.header("X-Baumeister-App"));
            assertEquals("close", rep.header("Connection"));
            assertEquals("2", rep.header("Content-Length"));
            assertEquals("application/json", rep.header("Content-Type"));
            assertEquals("{}", new String(rep.body(), StandardCharsets.UTF_8));
            assertEquals(1, f.relay.trust().checks());
        }
    }

    @Test
    void clientWithoutCertificateIsRejected() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            assertRejected(new FakeApp.Identity(null, null, f.keys.pin("relay")), f.external());
            assertFalse(f.relay.hub().hasApp());
        }
    }

    @Test
    void clientWithAnUnpinnedCertificateIsRejected() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            assertRejected(f.identity("fremd"), f.external());
            assertEquals(1, f.relay.trust().checks());
            assertFalse(f.relay.hub().hasApp());
        }
    }

    /** Erlaubtes Blatt, aber eine Kette der Länge 2: Die App schickt immer genau ihr eines Zertifikat. */
    @Test
    void chainOfTwoIsRejectedEvenWithAPinnedLeaf() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            FakeApp.Identity id = new FakeApp.Identity(f.keys.key("app"),
                    new X509Certificate[] {f.keys.cert("app"), f.keys.cert("app2")}, f.keys.pin("relay"));
            assertRejected(id, f.external());
            assertFalse(f.relay.hub().hasApp());
        }
    }

    /**
     * Jede Verbindung durchläuft den vollen Handshake (der Client wählt jedes Mal neu seinen Schlüssel, der
     * Trust-Manager prüft jedes Mal) – obwohl derselbe Client-Kontext Sitzungen zwischenspeichert und eine
     * Wiederaufnahme versuchen würde. Riegel: keine zustandslosen Tickets, Cache 1/1 s, invalidate() nach dem Handshake.
     */
    @Test
    void everyConnectionRunsAFullHandshakeWithoutResumption() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            FakeApp.Identity id = f.identity("app");
            for (int i = 1; i <= 3; i++) {
                FakeApp.connect(id, f.external(), 6).close();
                assertEquals(i, id.chooseCount.get(), "Verbindung " + i + " ohne vollen Handshake (Wiederaufnahme)");
                assertEquals(i, f.relay.trust().checks(), "Verbindung " + i + " ohne Prüfung durch den Trust-Manager");
            }
        }
    }

    /**
     * Ein entzogener Pin gilt sofort: Die laufende Sitzung endet mit 1008, die nächste Verbindung scheitert – auch wenn
     * sie eine Sitzung wiederaufnähme. Dafür prüft PublicListener den Pin des Peers nach JEDEM Handshake selbst (bei
     * einer Wiederaufnahme ruft JSSE den Trust-Manager nicht auf).
     */
    @Test
    void removedPinIsRejectedAtTheNextConnection() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            FakeApp.Identity id = f.identity("app");
            FakeApp running = FakeApp.connect(id, f.external(), 6);
            Map<String, String> pins = new LinkedHashMap<>();
            pins.put("laptop", f.keys.pin("app2"));
            f.writeConfig(pins);
            FakeApp.Closed revoked = assertThrows(FakeApp.Closed.class, running::message);
            assertEquals(1008, revoked.code);
            assertEquals("pin_entzogen", revoked.reason);
            running.close();
            assertRejected(id, f.external());
            assertFalse(f.relay.hub().hasApp());
        }
    }

    @Test
    void tls13IsNegotiatedByDefault() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build());
                SSLSocket s = FakeApp.tls(f.identity("app"), f.external(), null, null)) {
            assertEquals("TLSv1.3", s.getSession().getProtocol());
            assertTrue(List.of(Tls.CIPHER_SUITES).contains(s.getSession().getCipherSuite()));
        }
    }

    /** Windows 10 kann kein TLS 1.3 in Schannel: TLS 1.2 mit ECDHE-ECDSA und AES-GCM muss gehen. */
    @Test
    void tls12WithAnAeadSuiteIsAccepted() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build());
                SSLSocket s = FakeApp.tls(f.identity("app"), f.external(), new String[] {"TLSv1.2"},
                        new String[] {"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"})) {
            assertEquals("TLSv1.2", s.getSession().getProtocol());
            assertEquals(1, f.relay.trust().checks());
        }
    }

    @Test
    void tls12WithACbcSuiteIsRejected() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            assertThrows(IOException.class, () -> FakeApp.tls(f.identity("app"), f.external(),
                    new String[] {"TLSv1.2"}, new String[] {"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"}).close());
            assertEquals(0, f.relay.trust().checks());
        }
    }

    @Test
    void stalledHandshakeIsCutAtTheDeadline() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.handshake = Duration.ofMillis(300);
        try (RelayFixture f = RelayFixture.start(b.build()); Socket s = idle(f.external())) {
            long start = System.nanoTime();
            assertTrue(closedWithin(s, 3_000), "Handshake ohne Daten nicht abgebrochen");
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms >= 250, "zu früh abgebrochen: " + ms + " ms");
        }
    }

    /**
     * Wer alle 100 ms ein Byte schickt, hält SO_TIMEOUT immer frisch (Slowloris) – nur die harte Frist (Riegel im Takt)
     * beendet ihn. Ohne sie hielte er einen der Plätze ohne Anmeldung beliebig lange.
     */
    @Test
    void tricklingHandshakeIsCutAtTheHardDeadline() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.handshake = Duration.ofMillis(800);
        try (RelayFixture f = RelayFixture.start(b.build()); Socket s = idle(f.external())) {
            OutputStream out = s.getOutputStream();
            // TLS-Satzkopf: Handshake, Version 3.3, 16 000 Bytes angekündigt – der Rest tröpfelt.
            out.write(new byte[] {0x16, 0x03, 0x03, 0x3E, (byte) 0x80});
            long start = System.nanoTime();
            boolean closed = false;
            while (!closed && System.nanoTime() - start < 4_000_000_000L) {
                try {
                    out.write(0x01);
                    out.flush();
                    closed = closedWithin(s, 100);
                } catch (IOException e) {
                    closed = true;
                }
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(closed, "tröpfelnder Handshake nach " + ms + " ms nicht abgebrochen");
            assertTrue(ms >= 600 && ms < 3_000, "Frist nicht eingehalten: " + ms + " ms");
        }
    }

    /** Geänderte, aber kaputte Konfiguration: Bis sie wieder stimmt, gilt kein Pin (fail-closed beim Neuladen). */
    @Test
    void brokenConfigurationOnReloadLocksOutEveryApp() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            Files.writeString(f.config, Files.readString(f.config, StandardCharsets.UTF_8) + "lokal.adrese=127.0.0.1\n",
                    StandardCharsets.UTF_8);
            FakeApp.Closed c = assertThrows(FakeApp.Closed.class, app::message);
            assertEquals(1008, c.code);
            assertEquals("pin_entzogen", c.reason);
            assertRejected(f.identity("app2"), f.external());
            assertFalse(f.relay.hub().hasApp());
        }
    }

    /**
     * TLS 1.2: Eine vom Client angestoßene Neuverhandlung wird abgelehnt (jdk.tls.rejectClientInitiatedRenegotiation) –
     * mit ihr könnte ein Client mitten in der Verbindung ein anderes Zertifikat vorlegen.
     */
    @Test
    void clientInitiatedRenegotiationIsRefused() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build());
                SSLSocket s = FakeApp.tls(f.identity("app"), f.external(), new String[] {"TLSv1.2"},
                        new String[] {"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"})) {
            assertThrows(IOException.class, () -> {
                s.startHandshake();
                s.getOutputStream().write(("GET /kanal/v1 HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\n"
                        + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: baumeister-kanal.v1\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
                if (s.getInputStream().read() < 0) {
                    throw new java.io.EOFException("Relay hat geschlossen");
                }
            });
            assertFalse(f.relay.hub().hasApp());
        }
    }

    @Test
    void moreHandshakesFromOneAddressThanAllowedAreRefused() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.perAddress = 2;
        try (RelayFixture f = RelayFixture.start(b.build()); Socket one = idle(f.external());
                Socket two = idle(f.external())) {
            RelayFixture.await(() -> f.relay.gate().activeCount() == 2, "zwei Handshakes offen");
            try (Socket three = idle(f.external())) {
                assertTrue(closedWithin(three, 1_000), "dritter Handshake nicht abgewiesen");
            }
            assertFalse(closedWithin(one, 200), "erster Handshake fälschlich beendet");
            assertFalse(closedWithin(two, 50), "zweiter Handshake fälschlich beendet");
        }
    }

    @Test
    void unauthenticatedConnectionsAreCappedInTotal() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.unauthenticated = 3;
        try (RelayFixture f = RelayFixture.start(b.build())) {
            List<Socket> open = new ArrayList<>();
            try {
                for (int i = 0; i < 3; i++) {
                    open.add(idle(f.external()));
                }
                RelayFixture.await(() -> f.relay.gate().activeCount() == 3, "drei Handshakes offen");
                try (Socket fourth = idle(f.external())) {
                    assertTrue(closedWithin(fourth, 1_000), "vierte Verbindung ohne Anmeldung nicht abgewiesen");
                }
            } finally {
                for (Socket s : open) {
                    s.close();
                }
            }
        }
    }

    /** Nach einem Fehlschlag ist die Adresse gesperrt – auch für ein gültiges Zertifikat, bis die Sperre abläuft. */
    @Test
    void failedHandshakeBansTheAddressUntilTheBanExpires() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.banMin = Duration.ofMillis(1_500);
        try (RelayFixture f = RelayFixture.start(b.build())) {
            assertRejected(f.identity("fremd"), f.external());
            long checks = f.relay.trust().checks();
            assertRejected(f.identity("app"), f.external());
            assertEquals(checks, f.relay.trust().checks(), "gesperrte Adresse kam bis zum Trust-Manager");
            Thread.sleep(1_700);
            try (FakeApp a = FakeApp.connect(f.identity("app"), f.external(), 6)) {
                assertTrue(a.relayHello.startsWith("baumeister-relay/"));
            }
        }
    }

    /** Angemeldete Sitzungen zählen nicht gegen die Grenze je Adresse. */
    @Test
    void authenticatedSessionsDoNotCountAgainstTheAddressLimit() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.perAddress = 1;
        try (RelayFixture f = RelayFixture.start(b.build()); FakeApp one = f.app("app", 6);
                FakeApp two = f.app("app2", 6)) {
            assertEquals(2, f.relay.hub().sessions().size());
            assertEquals(0, f.relay.gate().activeCount());
            assertTrue(one.socket().isConnected() && two.socket().isConnected());
        }
    }

    @Test
    void newSessionWithTheSamePinReplacesTheOldOne() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp old = f.app("app", 6)) {
            try (FakeApp fresh = FakeApp.connect(f.identity("app"), f.external(), 6)) {
                FakeApp.Closed c = assertThrows(FakeApp.Closed.class, old::message);
                assertEquals(1001, c.code);
                assertEquals("ersetzt", c.reason);
                RelayFixture.await(() -> f.relay.hub().sessions().size() == 1, "eine Sitzung");
                CompletableFuture<RelayFixture.Reply> reply = CompletableFuture.supplyAsync(() -> {
                    try {
                        return f.plugin(RelayFixture.get(f.local(), "/health"));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                FakeApp.Request r = fresh.request();
                fresh.answer(r.id(), 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), 65_536);
                assertEquals(200, reply.get(10, TimeUnit.SECONDS).status());
            }
        }
    }
}
