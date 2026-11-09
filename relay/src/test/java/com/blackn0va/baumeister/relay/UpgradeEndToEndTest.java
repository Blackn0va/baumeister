package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Upgrade und hallo über die echte TLS-Verbindung: falsche Upgrades bekommen 400/404/426, falsche Rahmen und ein
 * falsches hallo ein Close mit dem Grund aus der Spezifikation, und ohne hallo endet die Verbindung nach der Frist.
 */
class UpgradeEndToEndTest {

    private static String request(int port, String path, String protocol) {
        return "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                + (protocol == null ? "" : "Sec-WebSocket-Protocol: " + protocol + "\r\n") + "\r\n";
    }

    /** Jede Ablehnung sperrt die Adresse (auch nach gültigem Pin) – hier ohne Sperre, damit drei Versuche gehen. */
    @Test
    void wrongUpgradesAreAnsweredWith404Or400() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.banMin = Duration.ZERO;
        b.banMax = Duration.ZERO;
        try (RelayFixture f = RelayFixture.start(b.build())) {
            FakeApp.Identity id = f.identity("app");
            assertTrue(FakeApp.rawUpgrade(id, f.external(), request(f.external(), "/kanal/v2",
                    "baumeister-kanal.v1")).startsWith("HTTP/1.1 404 "));
            assertTrue(FakeApp.rawUpgrade(id, f.external(), request(f.external(), "/kanal/v1", null))
                    .startsWith("HTTP/1.1 400 "));
            String v8 = FakeApp.rawUpgrade(id, f.external(), request(f.external(), "/kanal/v1",
                    "baumeister-kanal.v1").replace("Version: 13", "Version: 8"));
            assertTrue(v8.startsWith("HTTP/1.1 426 ") && v8.contains("Sec-WebSocket-Version: 13"), v8);
            assertFalse(f.relay.hub().hasApp());
        }
    }

    private static FakeApp.Closed closeAfterUpgrade(RelayFixture f, byte[] frame) throws Exception {
        try (FakeApp a = FakeApp.upgraded(f.identity("app"), f.external())) {
            a.sendFrame(frame);
            return assertThrows(FakeApp.Closed.class, a::message);
        }
    }

    private static final byte[] MASK = {1, 2, 3, 4};

    private static byte[] hello(String json) {
        return Frames.client(Frames.BINARY, CodecTest.payload(json, new byte[0]), MASK);
    }

    @Test
    void helloWithAnotherVersionIsClosedWith1008() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            FakeApp.Closed c = closeAfterUpgrade(f, hello("{\"t\":\"hallo\",\"v\":2,\"app\":\"x\",\"max\":6}"));
            assertEquals(1008, c.code);
            assertEquals("version_ungueltig", c.reason);  // umlaut-ok: Protokollcode
            assertFalse(f.relay.hub().hasApp());
        }
    }

    @Test
    void helloWithMoreThanSixParallelRequestsIsRejected() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            FakeApp.Closed c = closeAfterUpgrade(f, hello("{\"t\":\"hallo\",\"v\":1,\"app\":\"x\",\"max\":7}"));
            assertEquals(1002, c.code);
            assertEquals("max_ungueltig", c.reason);  // umlaut-ok: Protokollcode wie in der App
        }
    }

    @Test
    void anyMessageBeforeTheHelloIsRejected() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            FakeApp.Closed c = closeAfterUpgrade(f, Frames.client(Frames.BINARY, Codec.encode(new Message.Abort(1)),
                    MASK));
            assertEquals("hallo_fehlt", c.reason);
        }
    }

    @Test
    void textFrameIsClosedWith1003() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp a = f.app("app", 6)) {
            a.sendFrame(Frames.client(Frames.TEXT, "{\"t\":\"hallo\"}".getBytes(StandardCharsets.UTF_8), MASK));
            FakeApp.Closed c = assertThrows(FakeApp.Closed.class, a::message);
            assertEquals(1003, c.code);
            assertEquals("textrahmen", c.reason);
            RelayFixture.await(() -> !f.relay.hub().hasApp(), "Sitzung beendet");
        }
    }

    @Test
    void unmaskedFrameFromTheAppIsClosedWith1002() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp a = f.app("app", 6)) {
            a.sendFrame(Frames.server(Frames.BINARY, Codec.encode(new Message.Abort(1))));
            FakeApp.Closed c = assertThrows(FakeApp.Closed.class, a::message);
            assertEquals(1002, c.code);
            assertEquals("maske_fehlt", c.reason);
        }
    }

    @Test
    void secondHelloIsAProtocolError() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp a = f.app("app", 6)) {
            a.send(new Message.Hello(Codec.Direction.APP_TO_RELAY, "FakeApp/test", 6));
            assertEquals("hallo_doppelt", assertThrows(FakeApp.Closed.class, a::message).reason);
        }
    }

    /** Ping vor dem hallo ist erlaubt (Steuerrahmen sind keine Nachrichten) und bekommt sein Pong. */
    @Test
    void pingBeforeTheHelloIsAnswered() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build());
                FakeApp a = FakeApp.upgraded(f.identity("app"), f.external())) {
            a.sendFrame(Frames.client(Frames.PING, new byte[] {7, 7}, MASK));
            Frames.Frame pong = a.frame();
            assertEquals(Frames.PONG, pong.opcode());
            assertEquals(2, pong.payload().length);
            a.send(new Message.Hello(Codec.Direction.APP_TO_RELAY, "FakeApp/test", 6));
            assertTrue(a.message() instanceof Message.Hello);
        }
    }

    @Test
    void connectionWithoutHelloIsCutAtTheDeadline() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.hello = Duration.ofMillis(400);
        try (RelayFixture f = RelayFixture.start(b.build());
                FakeApp a = FakeApp.upgraded(f.identity("app"), f.external())) {
            long start = System.nanoTime();
            assertThrows(IOException.class, a::message);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms >= 300 && ms < 5_000, "Frist nicht eingehalten: " + ms + " ms");
            assertFalse(f.relay.hub().hasApp());
        }
    }
}
