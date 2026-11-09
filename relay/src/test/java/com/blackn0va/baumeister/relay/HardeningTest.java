package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.Test;

/**
 * Härtungen aus der zweiten Sicherheitsprüfung (2026-11-08): N1 Anmeldungen je Pin vor dem hallo, N2 18080 bleibt
 * belegt, auch wenn 25580 nicht bindbar ist, N4 eine Änderung zwischen Lesen und Start geht nicht verloren.
 */
class HardeningTest {

    /** N1: Höchstens zwei Anmeldungen eines Pins zwischen TLS-Handshake und hallo; die dritte endet sofort. */
    @Test
    void thirdPendingLoginOfAPinIsCutOff() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            FakeApp.Identity id = f.identity("app");
            SSLSocket first = FakeApp.tls(id, f.external(), null, null);
            SSLSocket second = FakeApp.tls(id, f.external(), null, null);
            try {
                assertThrows(IOException.class, () -> FakeApp.upgraded(id, f.external()),
                        "dritte offene Anmeldung desselben Pins");
            } finally {
                first.close();
                second.close();
            }
            // Ob danach wieder eine Anmeldung geht, prüft dieser Test nicht: Die zwei abgebrochenen Anmeldungen zählen
            // bei der Sperre je Adresse (HandshakeGate) als Fehlversuche – so gewollt.
        }
    }

    /** N2: 25580 belegt → 18080 trotzdem belegt; wird 25580 frei, bindet das Relay es im Takt. */
    @Test
    void occupiedPublicPortKeepsTheLocalPortAndRetries() throws Exception {
        TestKeys keys = TestKeys.get();
        Path dir = Files.createTempDirectory("relay-n2-");
        ServerSocket blocker = new ServerSocket();
        blocker.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        int port = blocker.getLocalPort();
        Path config = dir.resolve("relay.properties");
        Files.writeString(config, "lokal.adresse=127.0.0.1\nlokal.port=0\nöffentlich.adresse=127.0.0.1\n"
                + "öffentlich.port=" + port + "\nschlüssel.datei=credential:relay.p12\n"
                + "schlüssel.passwort=credential:relay.pass\nstatus.datei=" + dir.resolve("status").toString()
                .replace('\\', '/') + "\nerlaubt.gpu-pc=" + keys.pin("app") + "\n", StandardCharsets.UTF_8);
        Map<String, String> env = Map.of("CREDENTIALS_DIRECTORY", keys.dir.toString());
        RelayConfig c = RelayConfig.load(config, env);
        Relay relay = new Relay(c, KeyMaterial.load(c.keystore(), c.password()), RelayFixture.fast().build(),
                new PinStore(c, () -> env), m -> { });
        try {
            relay.start();
            assertTrue(relay.localPort() > 0, "18080 belegt, obwohl 25580 belegt ist");
            assertEquals(-1, relay.externalPort());
            blocker.close();
            RelayFixture.await(() -> relay.externalPort() == port, "25580 im Takt gebunden");
        } finally {
            blocker.close();
            relay.close();
        }
    }

    /** N4: Wird die Datei zwischen Lesen der Konfiguration und Start des Relays geändert, gilt die Änderung trotzdem. */
    @Test
    void changeBetweenReadingAndStartIsPickedUp() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            Map<String, String> env = Map.of("CREDENTIALS_DIRECTORY", f.keys.dir.toString());
            RelayConfig before = RelayConfig.load(f.config, env);
            Map<String, String> revoked = new LinkedHashMap<>();
            revoked.put("laptop", f.keys.pin("app2"));
            f.writeConfig(revoked);
            PinStore store = new PinStore(before, () -> env);
            assertEquals("gpu-pc", store.nameFor(f.keys.pin("app")), "noch der Stand beim Lesen");
            assertTrue(store.refresh(), "erster refresh liest die Datei sicher neu");
            assertEquals(null, store.nameFor(f.keys.pin("app")), "entzogen");
            assertEquals("laptop", store.nameFor(f.keys.pin("app2")));
        }
    }
}
