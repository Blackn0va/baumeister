package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Die strengen HTTP-Leser: Kopf allgemein (HttpHead), Plugin-Anfrage (LocalRequest) und WebSocket-Upgrade (Upgrade) –
 * jede Form von kaputtem oder mehrdeutigem Kopf wird abgelehnt.
 */
class HttpHeadTest {

    private static final int PORT = 18_765;
    private static final String KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private static HttpHead head(String raw, int max, int tooLarge) throws IOException, HttpFailure {
        return HttpHead.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)), max, tooLarge);
    }

    private static int status(String raw) {
        try {
            LocalRequest.check(head(raw, LocalRequest.MAX_HEADER, 431), PORT);
        } catch (HttpFailure f) {
            return f.status();
        } catch (IOException e) {
            return -1;
        }
        return 200;
    }

    private static int upgradeStatus(String raw) {
        try {
            Upgrade.check(head(raw, Upgrade.MAX_HEADER, 400));
        } catch (HttpFailure f) {
            return f.status();
        } catch (IOException e) {
            return -1;
        }
        return 101;
    }

    /** So schickt der Java-HttpClient des Plugins (LlmClient, HTTP/1.1). */
    @Test
    void requestOfTheJavaHttpClientIsAccepted() throws Exception {
        String raw = "POST /v1/chat/completions HTTP/1.1\r\nContent-Length: 15\r\nHost: 127.0.0.1:" + PORT
                + "\r\nUser-Agent: Java-http-client/21.0.12\r\nContent-Type: application/json\r\n\r\n";
        LocalRequest r = LocalRequest.check(head(raw, LocalRequest.MAX_HEADER, 431), PORT);
        assertEquals("POST", r.method());
        assertEquals("/v1/chat/completions", r.target());
        assertEquals("application/json", r.contentType());
        assertEquals(15, r.length());
        assertEquals(200, status("GET /health HTTP/1.1\r\nHost: 127.0.0.1:" + PORT + "\r\nContent-Length: 0\r\n\r\n"));
    }

    @Test
    void ambiguousOrMalformedHeadsAreRejectedWith400() {
        String host = "Host: 127.0.0.1:" + PORT + "\r\n";
        for (String raw : List.of(
                "GET /health HTTP/1.1\n" + host + "\r\n",
                "GET /health HTTP/1.1\r\n" + host + "X-A: 1\nX-B: 2\r\n\r\n",
                "GET /health HTTP/1.1\r" + host + "\r\n",
                "\r\nGET /health HTTP/1.1\r\n" + host + "\r\n",
                "GET  /health HTTP/1.1\r\n" + host + "\r\n",
                "GET /health  HTTP/1.1\r\n" + host + "\r\n",
                "GET\t/health HTTP/1.1\r\n" + host + "\r\n",
                "GET /health\r\n" + host + "\r\n",
                "get /health HTTP/1.1\r\n" + host + "\r\n",
                "GET /health HTTP/1.1 \r\n" + host + "\r\n",
                "GET /health HTTP/1.0\r\n" + host + "\r\n",
                "GET /health HTTP/2.0\r\n" + host + "\r\n",
                "GET /he" + (char) 0xE4 + "lth HTTP/1.1\r\n" + host + "\r\n",
                "GET /health HTTP/1.1\r\n" + host + " X-Folded: yes\r\n\r\n",
                "GET /health HTTP/1.1\r\n" + host + "X-Space : yes\r\n\r\n",
                "GET /health HTTP/1.1\r\n" + host + ": empty\r\n\r\n",
                "GET /health HTTP/1.1\r\n" + host + "X(Bad): yes\r\n\r\n",
                "GET /health HTTP/1.1\r\n" + host + "X-Ctl: a" + (char) 1 + "b\r\n\r\n",
                "GET /health HTTP/1.1\r\n" + host + "X-High: a" + (char) 0xE4 + "b\r\n\r\n",
                "GET /health HTTP/1.1\r\n" + host + "NoColon\r\n\r\n",
                "GET /health HTTP/1.1\r\n" + host + "host: 127.0.0.1:" + PORT + "\r\n\r\n",
                "GET /health HTTP/1.1\r\n" + host + "X-A: 1\r\nx-a: 2\r\n\r\n")) {
            assertEquals(400, status(raw), raw);
        }
    }

    @Test
    void headLimitsAreExactAndUseTheGivenStatus() throws Exception {
        String start = "GET /health HTTP/1.1\r\nHost: 127.0.0.1:" + PORT + "\r\nX-Fill: ";
        String end = "\r\n\r\n";
        String exact = start + "a".repeat(LocalRequest.MAX_HEADER - start.length() - end.length()) + end;
        assertEquals(LocalRequest.MAX_HEADER, exact.length());
        assertEquals(200, status(exact));
        String over = start + "a".repeat(LocalRequest.MAX_HEADER - start.length() - end.length() + 1) + end;
        assertEquals(431, status(over));
        StringBuilder many = new StringBuilder("GET /health HTTP/1.1\r\nHost: 127.0.0.1:" + PORT + "\r\n");
        for (int i = 0; i < HttpHead.MAX_HEADERS; i++) {
            many.append("X-").append(i).append(": 1\r\n");
        }
        assertEquals(431, status(many.append("\r\n").toString()));
    }

    @Test
    void truncatedHeadEndsWithEof() {
        assertThrows(EOFException.class, () -> head("", 100, 400));
        assertThrows(EOFException.class, () -> head("GET /health HTTP/1.1\r\nHost: x\r\n", 100, 400));
    }

    @Test
    void localRequestRulesFollowSection6() {
        String host = "Host: 127.0.0.1:" + PORT + "\r\n";
        assertEquals(405, status("PUT /health HTTP/1.1\r\n" + host + "\r\n"));
        assertEquals(405, status("HEAD /health HTTP/1.1\r\n" + host + "\r\n"));
        assertEquals(405, status("CONNECT 127.0.0.1:22 HTTP/1.1\r\n" + host + "\r\n"));
        assertEquals(404, status("GET http://127.0.0.1/health HTTP/1.1\r\n" + host + "\r\n"));
        assertEquals(404, status("GET * HTTP/1.1\r\n" + host + "\r\n"));
        assertEquals(400, status("POST /health HTTP/1.1\r\n" + host + "Transfer-Encoding: chunked\r\n\r\n"));
        assertEquals(400, status("POST /health HTTP/1.1\r\n" + host + "Content-Length: 1\r\nTransfer-Encoding: "
                + "identity\r\n\r\n"));
        assertEquals(417, status("POST /health HTTP/1.1\r\n" + host + "Content-Length: 1\r\nExpect: 100-continue"
                + "\r\n\r\n"));
        assertEquals(411, status("POST /health HTTP/1.1\r\n" + host + "\r\n"));
        for (String cl : List.of("+5", "-1", "5,5", "5 5", "0x10", "12345678901", "")) {
            assertEquals(400, status("POST /health HTTP/1.1\r\n" + host + "Content-Length: " + cl + "\r\n\r\n"), cl);
        }
        assertEquals(200, status("POST /health HTTP/1.1\r\n" + host + "Content-Length:  7 \r\n\r\n"));
        assertEquals(400, status("GET /health HTTP/1.1\r\n" + host + "Content-Length: 3\r\n\r\n"));
        assertEquals(413, status("POST /health HTTP/1.1\r\n" + host + "Content-Length: 2097153\r\n\r\n"));
        assertEquals(200, status("POST /health HTTP/1.1\r\n" + host + "Content-Length: 2097152\r\n\r\n"));
        assertEquals(400, status("POST /health HTTP/1.1\r\n" + host + "Content-Length: 1\r\nContent-Type: "
                + "a".repeat(129) + "\r\n\r\n"));
    }

    /** Nur die eigene Loopback-Adresse samt eigenem Port – Schutz gegen DNS-Rebinding. */
    @Test
    void hostMustBeLoopbackWithTheOwnPort() {
        for (String ok : List.of("127.0.0.1", "127.0.0.1:" + PORT, "localhost", "LOCALHOST:" + PORT, "[::1]",
                "[::1]:" + PORT)) {
            assertTrue(LocalRequest.hostAllowed(ok, PORT), ok);
        }
        for (String bad : List.of("127.0.0.1:18080", "evil.example", "evil.example:" + PORT, "127.0.0.2:" + PORT,
                "localhost.evil:" + PORT, "", "127.0.0.1:" + PORT + "x")) {
            assertFalse(LocalRequest.hostAllowed(bad, PORT), bad);
        }
        assertFalse(LocalRequest.hostAllowed(null, PORT));
        assertEquals(400, status("GET /health HTTP/1.1\r\n\r\n"));
    }

    private static String upgrade(String... replace) {
        String r = "GET /kanal/v1 HTTP/1.1\r\nHost: dein-server.example.net:25580\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + KEY + "\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: baumeister-kanal.v1\r\n\r\n";
        for (int i = 0; i < replace.length; i += 2) {
            if (!r.contains(replace[i])) {
                fail("Vorlage enthält „" + replace[i] + "“ nicht");
            }
            r = r.replace(replace[i], replace[i + 1]);
        }
        return r;
    }

    @Test
    void upgradeRulesFollowSection1() {
        assertEquals(101, upgradeStatus(upgrade()));
        assertEquals(101, upgradeStatus(upgrade("Connection: Upgrade", "Connection: keep-alive, Upgrade")));
        assertEquals(101, upgradeStatus(upgrade("Protocol: baumeister-kanal.v1", "Protocol: x, baumeister-kanal.v1")));
        assertEquals(404, upgradeStatus(upgrade("/kanal/v1", "/kanal/v2")));
        assertEquals(404, upgradeStatus(upgrade("/kanal/v1", "/kanal/v1?x=1")));
        assertEquals(400, upgradeStatus(upgrade("GET ", "POST ")));
        assertEquals(400, upgradeStatus(upgrade("HTTP/1.1", "HTTP/1.0")));
        assertEquals(400, upgradeStatus(upgrade("Host: dein-server.example.net:25580\r\n", "")));
        assertEquals(400, upgradeStatus(upgrade("Upgrade: websocket\r\n", "")));
        assertEquals(400, upgradeStatus(upgrade("Upgrade: websocket", "Upgrade: h2c")));
        assertEquals(400, upgradeStatus(upgrade("Connection: Upgrade", "Connection: keep-alive")));
        assertEquals(426, upgradeStatus(upgrade("Version: 13", "Version: 8")));
        assertEquals(426, upgradeStatus(upgrade("Sec-WebSocket-Version: 13\r\n", "")));
        assertEquals(400, upgradeStatus(upgrade(KEY, "dGhlIHNhbXBsZSBub25jZQ")));
        assertEquals(400, upgradeStatus(upgrade(KEY, "dGhlIHNhbXBsZSBub25jZR==")));
        assertEquals(400, upgradeStatus(upgrade(KEY, "dGhlIHNhbXBsZSBub25jZQ==dGhl")));
        assertEquals(400, upgradeStatus(upgrade("Sec-WebSocket-Protocol: baumeister-kanal.v1\r\n", "")));
        assertEquals(400, upgradeStatus(upgrade("Protocol: baumeister-kanal.v1", "Protocol: baumeister-kanal.v2")));
        assertEquals(400, upgradeStatus(upgrade("\r\n\r\n", "\r\nContent-Length: 5\r\n\r\n")));
        assertEquals(400, upgradeStatus(upgrade("\r\n\r\n", "\r\nTransfer-Encoding: chunked\r\n\r\n")));
        assertEquals(101, upgradeStatus(upgrade("\r\n\r\n", "\r\nContent-Length: 0\r\n\r\n")));
        String big = upgrade("\r\n\r\n", "\r\nX-Fill: " + "a".repeat(Upgrade.MAX_HEADER) + "\r\n\r\n");
        assertEquals(400, upgradeStatus(big));
    }

    @Test
    void refusalsCloseTheConnectionAnd426NamesVersion13() {
        String r426 = new String(Upgrade.refusal(426), StandardCharsets.US_ASCII);
        assertTrue(r426.startsWith("HTTP/1.1 426 Upgrade Required\r\n"));
        assertTrue(r426.contains("\r\nSec-WebSocket-Version: 13\r\n"));
        assertTrue(r426.contains("\r\nConnection: close\r\n"));
        String ok = new String(Upgrade.switching(KEY), StandardCharsets.US_ASCII);
        assertTrue(ok.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"));
        assertTrue(ok.contains("Sec-WebSocket-Protocol: baumeister-kanal.v1\r\n"));
    }

    /** Das Relay antwortet selbst nur mit 4xx – nie 5xx (Abschnitt 6). */
    @Test
    void relayCanNeverProduceA5xxOfItsOwn() {
        assertThrows(IllegalArgumentException.class, () -> new HttpFailure(502, "x"));
        assertThrows(IllegalArgumentException.class, () -> new HttpFailure(503, "x"));
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.empty(504, null));
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.empty(200, null));
    }
}
