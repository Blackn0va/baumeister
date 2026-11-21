package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Plugin-Seite und Verteilung Ende-zu-Ende: Anfrage → App → Antwort, große Bodys in Stücken, Abbruch in beide
 * Richtungen, Tod der App, eigene 4xx des Relays, Warteschlange, Byte-Budget, Lebenszeichen, Statusdatei.
 *
 * Ein Teil läuft mit dem Java-HttpClient wie im Plugin (LlmClient: HTTP/1.1, Redirect.NEVER) – so ist belegt, dass das
 * echte Plugin mit den Antworten des Relays umgehen kann.
 */
class RoundTripTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    private static CompletableFuture<RelayFixture.Reply> async(RelayFixture f, byte[] request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return f.plugin(request);
            } catch (IOException e) {
                return new RelayFixture.Reply(new byte[0], true);
            }
        });
    }

    private static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + seed);
        }
        return b;
    }

    @Test
    void withoutAnAppEveryPluginConnectionIsResetImmediately() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            long start = System.nanoTime();
            RelayFixture.Reply r = f.plugin(RelayFixture.get(f.local(), "/health"));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertEquals(0, r.bytes().length, "ohne App darf keine Antwort kommen");
            assertTrue(r.reset(), "ohne App: RST erwartet, nicht ein sauberes Ende");
            assertTrue(ms < 2_000, "RST kam erst nach " + ms + " ms");
            assertEquals("app=-\n", f.statusText());
        }
    }

    @Test
    void javaHttpClientLikeThePluginGetsTheAnswerOfTheApp() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.local()
                    + "/v1/chat/completions")).timeout(Duration.ofSeconds(10)).header("Content-Type",
                    "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"messages\":[]}")).build();
            CompletableFuture<HttpResponse<String>> resp = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            FakeApp.Request r = app.request();
            assertEquals("{\"messages\":[]}", new String(r.body(), StandardCharsets.UTF_8));
            app.answer(r.id(), 200, "application/json; charset=utf-8", "{\"choices\":[]}".getBytes(
                    StandardCharsets.UTF_8), 65_536);
            HttpResponse<String> h = resp.get(10, TimeUnit.SECONDS);
            assertEquals(200, h.statusCode());
            assertEquals("{\"choices\":[]}", h.body());
            assertEquals("gpu-pc", h.headers().firstValue("X-Baumeister-App").orElse(null));
            assertEquals("application/json; charset=utf-8", h.headers().firstValue("Content-Type").orElse(null));
        }
    }

    /** Status der App unverändert – auch 503 (der LlmClient wiederholt bei 503; erfinden darf das Relay sie nie). */
    @Test
    void statusAndContentTypeOfTheAppPassUnchanged() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            CompletableFuture<RelayFixture.Reply> busy = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r = app.request();
            app.answer(r.id(), 503, "application/json", "{\"error\":\"beschaeftigt\"}".getBytes(  // umlaut-ok: JSON
                    StandardCharsets.UTF_8), 65_536);
            RelayFixture.Reply b = busy.get(10, TimeUnit.SECONDS);
            assertEquals(503, b.status());
            assertEquals("application/json", b.header("Content-Type"));

            CompletableFuture<RelayFixture.Reply> created = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r2 = app.request();
            app.answer(r2.id(), 201, null, new byte[0], 65_536);
            RelayFixture.Reply c = created.get(10, TimeUnit.SECONDS);
            assertEquals(201, c.status());
            assertNull(c.header("Content-Type"), "ohne ct der App kein Content-Type");
            assertEquals("0", c.header("Content-Length"));
        }
    }

    @Test
    void largeResponseIsStreamedInChunksOfAtMost64KiB() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            byte[] body = pattern(3 * 1024 * 1024 + 517 * 1024, 7);
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + f.local()
                    + "/baumeister/v1/begleiter/stimme")).timeout(Duration.ofSeconds(20)).header("Content-Type",
                    "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            CompletableFuture<HttpResponse<byte[]>> resp = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray());
            FakeApp.Request r = app.request();
            app.answer(r.id(), 200, "application/octet-stream", body, 65_536);
            HttpResponse<byte[]> h = resp.get(20, TimeUnit.SECONDS);
            assertEquals(200, h.statusCode());
            assertArrayEquals(body, h.body());
            RelayFixture.await(() -> f.relay.budget().used() == 0, "Budget wieder frei");
        }
    }

    @Test
    void largeRequestBodyArrivesInChunksOfAtMost64KiB() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            byte[] body = pattern(2 * 1024 * 1024, 3);
            CompletableFuture<RelayFixture.Reply> reply = async(f, RelayFixture.post(f.local(),
                    "/baumeister/v1/begleiter/hoeren?spieler=Alex", "application/octet-stream", body));  // umlaut-ok
            Message first = app.message();
            assertTrue(first instanceof Message.Request q && q.chunk().length() == 65_536 && q.length() == body.length);
            Message.Request q = (Message.Request) first;
            assertEquals("/baumeister/v1/begleiter/hoeren?spieler=Alex", q.path());  // umlaut-ok: URL-Pfad
            java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
            got.write(q.chunk().buffer(), q.chunk().offset(), q.chunk().length());
            int frames = 1;
            while (got.size() < body.length) {
                Message.Data d = (Message.Data) app.message();
                assertTrue(d.chunk().length() <= 65_536);
                got.write(d.chunk().buffer(), d.chunk().offset(), d.chunk().length());
                frames++;
            }
            assertArrayEquals(body, got.toByteArray());
            assertEquals(32, frames);
            app.answer(q.id(), 200, "application/json", "{\"text\":\"\"}".getBytes(StandardCharsets.UTF_8), 65_536);
            assertEquals(200, reply.get(10, TimeUnit.SECONDS).status());
            RelayFixture.await(() -> f.relay.budget().used() == 0, "Budget wieder frei");
        }
    }

    @Test
    void queryReachesTheAppUnchanged() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            String path = "/baumeister/v1/begleiter/gehoert?imspiel=J%C3%BCrgen";  // umlaut-ok: URL-Pfad
            CompletableFuture<RelayFixture.Reply> reply = async(f, RelayFixture.get(f.local(), path));
            FakeApp.Request r = app.request();
            assertEquals("GET", r.method());
            assertEquals(path, r.path());
            assertNull(r.contentType());
            app.answer(r.id(), 200, "application/json", "{\"saetze\":[]}".getBytes(StandardCharsets.UTF_8),  // umlaut-ok
                    65_536);
            assertEquals(200, reply.get(10, TimeUnit.SECONDS).status());
        }
    }

    /** Plugin legt auf, bevor die Antwort da ist: Die App bekommt abbruch mit derselben ID (Abschnitt 6). */
    @Test
    void pluginHangUpSendsAbbruchToTheApp() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            long id;
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), f.local())) {
                s.getOutputStream().write(RelayFixture.post(f.local(), "/v1/chat/completions", "application/json",
                        "{}".getBytes(StandardCharsets.UTF_8)));
                s.getOutputStream().flush();
                id = app.request().id();
            }
            Message m = app.message();
            assertTrue(m instanceof Message.Abort a && a.id() == id, "abbruch erwartet, kam " + m);
            RelayFixture.await(() -> f.relay.hub().exchanges().isEmpty(), "Anfrage aufgeräumt");
            assertEquals(0, f.relay.budget().used());
        }
    }

    /** App stirbt mitten in der Anfrage: Das Plugin bekommt keine Antwort – nie eine erfundene 502/503/504. */
    @Test
    void appDeathClosesThePluginConnectionWithoutAnswer() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            FakeApp app = f.app("app", 6);
            CompletableFuture<RelayFixture.Reply> reply = async(f, RelayFixture.get(f.local(), "/health"));
            app.request();
            app.socket().setSoLinger(true, 0);
            app.socket().close();
            RelayFixture.Reply r = reply.get(10, TimeUnit.SECONDS);
            assertEquals(0, r.bytes().length, "ohne App darf keine Antwort kommen: " + r.text());
            RelayFixture.await(() -> !f.relay.hub().hasApp(), "Sitzung weg");
            RelayFixture.await(() -> f.statusIs("app=-\n"), "Statusdatei zurück auf app=-");
        }
    }

    @Test
    void abbruchFromTheAppClosesThePluginConnectionWithoutAnswer() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            CompletableFuture<RelayFixture.Reply> reply = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r = app.request();
            app.send(new Message.Abort(r.id()));
            RelayFixture.Reply got = reply.get(10, TimeUnit.SECONDS);
            assertEquals(0, got.bytes().length);
            assertTrue(got.reset());
            // Die Sitzung lebt weiter: nächste Anfrage geht durch.
            CompletableFuture<RelayFixture.Reply> next = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r2 = app.request();
            app.answer(r2.id(), 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), 65_536);
            assertEquals(200, next.get(10, TimeUnit.SECONDS).status());
        }
    }

    /** Eigene Antworten des Relays: nur 4xx, die App sieht davon nichts. */
    @Test
    void relayAnswersForbiddenOrMalformedRequestsItselfWith4xx() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            int p = f.local();
            String host = "Host: 127.0.0.1:" + p + "\r\n";
            assertEquals(404, f.plugin(RelayFixture.get(p, "/v1/../health")).status());
            assertEquals(404, f.plugin(RelayFixture.get(p, "/healthz")).status());
            assertEquals(404, f.plugin(RelayFixture.get(p, "/v1/chat%2e%2e/x")).status());
            // %2e auch in der Abfrage: Die Host-App schlösse dafür die ganze Verbindung – hier nur diese eine Anfrage.
            assertEquals(404, f.plugin(RelayFixture.get(p, "/baumeister/v1/stadt?x=%2e%2e")).status());
            assertEquals(404, f.plugin(RelayFixture.get(p, "/baumeister/v1/stadt?x=%2E")).status());
            assertEquals(405, f.plugin(("DELETE /health HTTP/1.1\r\n" + host + "\r\n").getBytes(
                    StandardCharsets.US_ASCII)).status());
            assertEquals(411, f.plugin(("POST /v1/chat/completions HTTP/1.1\r\n" + host + "\r\n").getBytes(
                    StandardCharsets.US_ASCII)).status());
            assertEquals(400, f.plugin(("POST /v1/chat/completions HTTP/1.1\r\n" + host
                    + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n").getBytes(StandardCharsets.US_ASCII)).status());
            assertEquals(413, f.plugin(("POST /v1/chat/completions HTTP/1.1\r\n" + host + "Content-Length: "
                    + (2 * 1024 * 1024 + 1) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)).status());
            assertEquals(400, f.plugin(("GET /health HTTP/1.1\r\nHost: evil.example:" + p + "\r\n\r\n").getBytes(
                    StandardCharsets.US_ASCII)).status());
            assertEquals(431, f.plugin(("GET /health HTTP/1.1\r\n" + host + "X-Big: " + "a".repeat(17_000)
                    + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)).status());
            assertEquals(400, f.plugin(("GET /health HTTP/1.1\n" + host + "\r\n").getBytes(
                    StandardCharsets.US_ASCII)).status());
            RelayFixture.Reply r = f.plugin(RelayFixture.get(p, "/healthz"));
            assertEquals("close", r.header("Connection"));
            assertNull(r.header("X-Baumeister-App"), "eigene Antwort des Relays trägt keinen App-Namen");
            // Die App hat keine dieser Anfragen gesehen: Die nächste Nachricht ist die nächste echte Anfrage.
            CompletableFuture<RelayFixture.Reply> ok = async(f, RelayFixture.get(p, "/health"));
            FakeApp.Request real = app.request();
            assertEquals("/health", real.path());
            app.answer(real.id(), 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), 65_536);
            assertEquals(200, ok.get(10, TimeUnit.SECONDS).status());
        }
    }

    /** max der App begrenzt die laufenden Anfragen; weitere warten, bis ein Platz frei ist. */
    @Test
    void atMostMaxRequestsRunAtOnceAndTheRestWaitInOrder() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 2)) {
            List<CompletableFuture<RelayFixture.Reply>> replies = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                replies.add(async(f, RelayFixture.get(f.local(), "/baumeister/v1/x" + i)));
                RelayFixture.await(() -> f.relay.hub().exchanges().size() >= replies.size(), "Anfrage angenommen");
            }
            FakeApp.Request a = app.request();
            FakeApp.Request b = app.request();
            RelayFixture.await(() -> f.relay.hub().waitingCount() == 2, "zwei wartend");
            assertEquals(1, a.id());
            assertEquals(2, b.id());
            app.answer(a.id(), 200, "text/plain", new byte[0], 65_536);
            FakeApp.Request c = app.request();
            assertEquals(3, c.id(), "IDs je Verbindung aufsteigend");
            for (FakeApp.Request r : List.of(b, c)) {
                app.answer(r.id(), 200, "text/plain", new byte[0], 65_536);
            }
            FakeApp.Request d = app.request();
            app.answer(d.id(), 200, "text/plain", new byte[0], 65_536);
            for (CompletableFuture<RelayFixture.Reply> r : replies) {
                assertEquals(200, r.get(10, TimeUnit.SECONDS).status());
            }
        }
    }

    @Test
    void fullQueueResetsTheNextRequest() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.maxQueue = 2;
        try (RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 1)) {
            List<CompletableFuture<RelayFixture.Reply>> held = new ArrayList<>();
            held.add(async(f, RelayFixture.get(f.local(), "/health")));
            FakeApp.Request running = app.request();
            for (int i = 0; i < 2; i++) {
                held.add(async(f, RelayFixture.get(f.local(), "/health")));
            }
            RelayFixture.await(() -> f.relay.hub().waitingCount() == 2, "Schlange voll");
            RelayFixture.Reply overflow = f.plugin(RelayFixture.get(f.local(), "/health"));
            assertEquals(0, overflow.bytes().length);
            assertTrue(overflow.reset());
            app.answer(running.id(), 200, "text/plain", new byte[0], 65_536);
            for (int i = 0; i < 2; i++) {
                FakeApp.Request r = app.request();
                app.answer(r.id(), 200, "text/plain", new byte[0], 65_536);
            }
            for (CompletableFuture<RelayFixture.Reply> r : held) {
                assertEquals(200, r.get(10, TimeUnit.SECONDS).status());
            }
        }
    }

    /** Konnte eine Anfrage die Frist lang an keine App gehen: RST (Abschnitt 4, im Betrieb 30 s). */
    @Test
    void requestThatFindsNoFreeAppWithinTheDeadlineIsReset() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.queueWait = Duration.ofMillis(400);
        try (RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 1)) {
            CompletableFuture<RelayFixture.Reply> first = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request running = app.request();
            long start = System.nanoTime();
            RelayFixture.Reply waited = f.plugin(RelayFixture.get(f.local(), "/health"));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertEquals(0, waited.bytes().length);
            assertTrue(waited.reset());
            assertTrue(ms >= 350 && ms < 5_000, "Frist nicht eingehalten: " + ms + " ms");
            app.answer(running.id(), 200, "text/plain", new byte[0], 65_536);
            assertEquals(200, first.get(10, TimeUnit.SECONDS).status());
        }
    }

    /**
     * Byte-Budget: Ein Plugin, das nicht liest, staut die Antwort im Relay. Über dem Budget wird die größte offene
     * Antwort abgebrochen – abbruch an die App, RST zum Plugin – und das Budget ist danach wieder frei.
     */
    @Test
    void byteBudgetAbortsTheLargestOpenResponse() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.byteBudget = 512 * 1024;
        try (RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6);
                Socket slow = new Socket(InetAddress.getLoopbackAddress(), f.local())) {
            slow.setReceiveBufferSize(4096);
            slow.getOutputStream().write(RelayFixture.get(f.local(), "/baumeister/v1/stadt"));
            slow.getOutputStream().flush();
            FakeApp.Request r = app.request();
            byte[] big = pattern(16 * 1024 * 1024, 1);
            CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> {
                try {
                    app.answer(r.id(), 200, "application/octet-stream", big, 65_536);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            Message m = app.message();
            assertTrue(m instanceof Message.Abort a && a.id() == r.id(), "abbruch erwartet, kam " + m);
            sending.get(30, TimeUnit.SECONDS);
            assertTrue(f.relay.budget().peak() <= 512 * 1024, "Budget überschritten: " + f.relay.budget().peak());
            RelayFixture.await(() -> f.relay.budget().used() == 0, "Budget wieder frei");
            RelayFixture.Reply got = RelayFixture.readAll(slow.getInputStream());
            assertTrue(got.reset() || got.bytes().length < big.length, "Antwort hätte abbrechen müssen");
        }
    }

    /** antwort/daten zu einer unbekannten ID (z. B. nach einem abbruch unterwegs): verwerfen, kein Close. */
    @Test
    void unknownIdFromTheAppIsDiscardedWithoutClosing() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            app.send(new Message.Response(999, 200, null, 3, Message.Chunk.of(new byte[] {1, 2, 3})));
            app.send(new Message.Data(998, Message.Chunk.of(new byte[] {1})));
            app.send(new Message.Abort(997));
            CompletableFuture<RelayFixture.Reply> reply = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r = app.request();
            app.answer(r.id(), 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), 65_536);
            assertEquals(200, reply.get(10, TimeUnit.SECONDS).status());
            assertEquals(1, f.relay.hub().sessions().size());
        }
    }

    @Test
    void moreBytesThanAnnouncedIsAProtocolError() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            CompletableFuture<RelayFixture.Reply> reply = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r = app.request();
            app.send(new Message.Response(r.id(), 200, null, 2, Message.Chunk.of(new byte[] {1})));
            app.send(new Message.Data(r.id(), Message.Chunk.of(new byte[] {2, 3})));
            FakeApp.Closed c = assertThrows(FakeApp.Closed.class, app::message);
            assertEquals(1002, c.code);
            assertEquals("laenge_ungueltig", c.reason);  // umlaut-ok: Protokollcode
            RelayFixture.Reply got = reply.get(10, TimeUnit.SECONDS);
            assertFalse(got.text().contains("\r\n\r\n") && got.body().length == 2, "unvollständige Antwort komplett?");
        }
    }

    @Test
    void relayPingsTheAppAndClosesASilentSession() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.ping = Duration.ofMillis(100);
        b.silence = Duration.ofMillis(700);
        try (RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6)) {
            Frames.Frame ping = app.frame();
            assertEquals(Frames.PING, ping.opcode());
            // Die App antwortet nicht mehr (liest nicht): nach der Stille-Frist ist die Sitzung tot.
            long start = System.nanoTime();
            RelayFixture.await(() -> !f.relay.hub().hasApp(), "stille Sitzung geschlossen");
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms >= 400, "zu früh für tot erklärt: " + ms + " ms");
        }
    }

    @Test
    void pongsKeepTheSessionAlive() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.ping = Duration.ofMillis(100);
        b.silence = Duration.ofMillis(700);
        try (RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6)) {
            AtomicInteger pings = new AtomicInteger();
            int quiet = 0;
            long until = System.nanoTime() + Duration.ofMillis(2_000).toNanos();
            app.socket().setSoTimeout(300);
            while (System.nanoTime() < until) {
                try {
                    Frames.Frame fr = app.frame();
                    if (fr.opcode() == Frames.PING) {
                        pings.incrementAndGet();
                        app.sendFrame(Frames.client(Frames.PONG, fr.payload(), new byte[] {1, 2, 3, 4}));
                    }
                } catch (java.net.SocketTimeoutException e) {
                    // 300 ms ohne Rahmen – gezählt für die Meldung unten, dann weiter warten.
                    quiet++;
                }
            }
            assertTrue(pings.get() >= 5, "zu wenige Pings: " + pings.get() + " (Pausen ohne Rahmen: " + quiet + ")");
            assertTrue(f.relay.hub().hasApp(), "Sitzung trotz Pongs geschlossen");
        }
    }

    /**
     * Die Host-App (WinHTTP) kann keine Pings senden: Sie schickt alle 20 s ein unaufgefordertes Pong und liest die
     * Pings des Relays womöglich gar nicht. Jedes Pong zählt als Lebenszeichen – ohne Protokollfehler, ohne Tod nach
     * der Stille-Frist.
     */
    @Test
    void unsolicitedPongsAloneKeepTheSessionAlive() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.ping = Duration.ofMillis(100);
        b.silence = Duration.ofMillis(600);
        try (RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6)) {
            long until = System.nanoTime() + Duration.ofMillis(2_000).toNanos();
            while (System.nanoTime() < until) {
                app.sendFrame(Frames.client(Frames.PONG, new byte[0], new byte[] {9, 8, 7, 6}));
                Thread.sleep(150);
            }
            assertTrue(f.relay.hub().hasApp(), "Sitzung trotz Pongs geschlossen");
            // Und sie trägt noch Anfragen: Erst jetzt liest die App (die gestauten Pings beantwortet message()).
            CompletableFuture<RelayFixture.Reply> reply = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r = app.request();
            app.answer(r.id(), 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), 65_536);
            assertEquals(200, reply.get(10, TimeUnit.SECONDS).status());
        }
    }

    /** Close der App mit ihrem Grund (z. B. hallo_doppelt): Das Relay antwortet mit Close 1000 und loggt den Grund. */
    @Test
    void closeFromTheAppIsAnsweredAndItsReasonLogged() throws Exception {
        List<String> logged = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord r) {
                logged.add(r.getMessage());
            }

            @Override
            public void flush() {
                // nichts zu leeren
            }

            @Override
            public void close() {
                // nichts zu schließen
            }
        };
        RelayLog.LOG.addHandler(capture);
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6)) {
            app.sendFrame(Frames.client(Frames.CLOSE, Frames.closePayload(1002, "hallo_doppelt"),
                    new byte[] {1, 1, 2, 2}));
            FakeApp.Closed echo = assertThrows(FakeApp.Closed.class, app::message);
            assertEquals(1000, echo.code);
            RelayFixture.await(() -> !f.relay.hub().hasApp(), "Sitzung beendet");
            RelayFixture.await(() -> logged.stream().anyMatch(l -> l.contains("grund=app_schließt_1002_hallo_doppelt")),
                    "Grund der App im Log: " + logged);
        } finally {
            RelayLog.LOG.removeHandler(capture);
        }
    }

    @Test
    void statusFileFollowsTheApp() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build())) {
            assertEquals("app=-\n", f.statusText());
            FakeApp app = f.app("app", 6);
            // Der Hub schreibt die Datei kurz nach dem Eintrag der Sitzung – darauf warten, nicht raten.
            RelayFixture.await(() -> {
                try {
                    return f.statusText().matches("app=gpu-pc seit=\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ\n");
                } catch (IOException e) {
                    return false;
                }
            }, "Statusdatei zeigt gpu-pc");
            app.close();
            RelayFixture.await(() -> f.statusIs("app=-\n"), "Statusdatei zurück auf app=-");
        }
    }

    /** Zwei Apps: Die zuerst angemeldete bekommt die Anfragen, bis ihre Plätze voll sind. */
    @Test
    void secondAppTakesOverWhenTheFirstIsFull() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp gpu = f.app("app", 1);
                FakeApp laptop = f.app("app2", 1)) {
            CompletableFuture<RelayFixture.Reply> one = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r1 = gpu.request();
            CompletableFuture<RelayFixture.Reply> two = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r2 = laptop.request();
            gpu.answer(r1.id(), 200, "text/plain", new byte[0], 65_536);
            laptop.answer(r2.id(), 200, "text/plain", new byte[0], 65_536);
            assertEquals("gpu-pc", one.get(10, TimeUnit.SECONDS).header("X-Baumeister-App"));
            assertEquals("laptop", two.get(10, TimeUnit.SECONDS).header("X-Baumeister-App"));
        }
    }

    /** Ein Plugin-Client, der nach der Anfrage weitere Bytes schickt (zweite Anfrage), verliert die Anfrage. */
    @Test
    void extraBytesAfterTheRequestAbortIt() throws Exception {
        try (RelayFixture f = RelayFixture.start(RelayFixture.fast().build()); FakeApp app = f.app("app", 6);
                Socket s = new Socket(InetAddress.getLoopbackAddress(), f.local())) {
            OutputStream out = s.getOutputStream();
            out.write(RelayFixture.get(f.local(), "/health"));
            out.flush();
            long id = app.request().id();
            out.write(RelayFixture.get(f.local(), "/health"));
            out.flush();
            Message m = app.message();
            assertTrue(m instanceof Message.Abort a && a.id() == id, "abbruch erwartet, kam " + m);
            InputStream in = s.getInputStream();
            assertEquals(0, RelayFixture.readAll(in).bytes().length);
        }
    }

    // ------------------------------------------------------------------ Grenzen (T-005, Sichtung 2026-11-20)

    /** Wie eine Plugin-Verbindung endet: RST, sauberes FIN oder gar nicht (noch offen nach der Wartezeit). */
    private enum End { RESET, EOF, OPEN }

    /** Liest bis zum Ende oder höchstens millis lang. */
    private static End endOf(Socket s, int millis) {
        try {
            s.setSoTimeout(millis);
            InputStream in = s.getInputStream();
            byte[] buf = new byte[8192];
            while (in.read(buf) >= 0) {
                // Antwortbytes interessieren hier nicht – nur, wie die Verbindung endet.
            }
            return End.EOF;
        } catch (SocketTimeoutException e) {
            return End.OPEN;
        } catch (IOException e) {
            return End.RESET;
        }
    }

    /** Namen der laufenden Anfrage-Threads der Plugin-Seite (ohne die Annahme-Threads). */
    private static Set<String> exchangeThreads() {
        Set<String> names = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = t.getName();
            if (t.isAlive() && n.startsWith("relay-lokal-") && !n.startsWith("relay-lokal-annahme")) {
                names.add(n);
            }
        }
        return names;
    }

    /** Nach einer Grenze: kein Austausch mehr, Budget leer, kein Anfrage-Thread, der vorher nicht schon lief. */
    private static void assertFreed(RelayFixture f, Set<String> threadsBefore) {
        RelayFixture.await(() -> f.relay.hub().exchanges().isEmpty(), "Austausch aufgeräumt");
        RelayFixture.await(() -> f.relay.budget().used() == 0, "Budget wieder frei");
        RelayFixture.await(() -> threadsBefore.containsAll(exchangeThreads()), "Anfrage-Thread beendet");
    }

    /**
     * Schickt alle 150 ms ein unaufgefordertes Pong (wie die Host-App), damit die Stille-Erkennung die Sitzung nicht
     * schließt, während ein Test die Schreib-Frist mit derselben kurzen Dauer prüft. Endet, wenn der Socket zu ist.
     */
    private static final class Pongs implements AutoCloseable {
        private final Thread thread;
        private final AtomicInteger sent = new AtomicInteger();
        private volatile boolean stop;

        Pongs(FakeApp app) {
            thread = new Thread(() -> {
                while (!stop) {
                    try {
                        app.sendFrame(Frames.client(Frames.PONG, new byte[0], new byte[] {5, 6, 7, 8}));
                        sent.incrementAndGet();
                        Thread.sleep(150);
                    } catch (IOException e) {
                        return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "test-pongs");
            thread.setDaemon(true);
            thread.start();
        }

        /** Gesendete Pongs: Ohne sie hätte die Stille-Frist gegriffen statt der geprüften Grenze. */
        int sent() {
            return sent.get();
        }

        @Override
        public void close() {
            stop = true;
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Antwortfrist (LocalExchange.receive, im Betrieb 1900 s): Die App schickt den Kopf und einen Teil, aber nie den
     * Rest. Nach der Frist bekommt sie abbruch, das Plugin RST, und das Log nennt grund=frist.
     */
    @Test
    void responseThatNeverCompletesIsAbortedAtTheResponseDeadline() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.response = Duration.ofMillis(500);
        try (RelayFixture.LogCapture log = new RelayFixture.LogCapture();
                RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6);
                Socket plugin = new Socket(InetAddress.getLoopbackAddress(), f.local())) {
            Set<String> before = exchangeThreads();
            plugin.getOutputStream().write(RelayFixture.get(f.local(), "/health"));
            plugin.getOutputStream().flush();
            FakeApp.Request r = app.request();
            long start = System.nanoTime();
            app.send(new Message.Response(r.id(), 200, "text/plain", 10, Message.Chunk.of(
                    "abc".getBytes(StandardCharsets.US_ASCII))));
            Message m = app.message();
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(m instanceof Message.Abort a && a.id() == r.id(), "abbruch erwartet, kam " + m);
            assertTrue(ms >= 300 && ms < 5_000, "Frist nicht eingehalten: " + ms + " ms");
            plugin.setSoTimeout(5_000);
            RelayFixture.Reply got = RelayFixture.readAll(plugin.getInputStream());
            assertTrue(got.reset(), "Plugin muss RST sehen, kein sauberes Ende: " + got.text());
            assertTrue(got.body().length < 10, "unvollständige Antwort darf nicht komplett wirken");
            log.await("anfrage_ohne_antwort", "grund=frist");
            assertFreed(f, before);
        }
    }

    /**
     * Plugin liest nicht (Relay.tick): Hängt das Schreiben ans Plugin länger als die Stille-Frist, bricht das Relay
     * ab – abbruch an die App, RST zum Plugin, Thread und Budget frei. Ohne die Grenze hielte die Anfrage beides bis
     * zur Antwortfrist (1900 s).
     */
    @Test
    void pluginThatStopsReadingIsAbortedAndFreesThreadAndBudget() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.silence = Duration.ofMillis(700);
        try (RelayFixture.LogCapture log = new RelayFixture.LogCapture();
                RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6);
                Pongs pongs = new Pongs(app); Socket plugin = new Socket()) {
            Set<String> before = exchangeThreads();
            plugin.setReceiveBufferSize(4096);
            plugin.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), f.local()));
            plugin.getOutputStream().write(RelayFixture.get(f.local(), "/baumeister/v1/stadt"));
            plugin.getOutputStream().flush();
            FakeApp.Request r = app.request();
            // 16 MiB angekündigt (Höchstwert in Kanal v1), 8 MiB geschickt: Die Antwort bleibt auf der App-Seite
            // offen (wie ein Server, der noch rechnet). Hätte die App schon alles übergeben, wäre die Anfrage für den
            // Kanal erledigt, und abbruch ginge zu Recht nicht mehr raus (Session.abort: nur offene Anfragen) –
            // gemessen 2026-11-21.
            byte[] piece = pattern(65_536, 5);
            int pieces = 16 * 1024 * 1024 / piece.length;
            CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> {
                try {
                    app.send(new Message.Response(r.id(), 200, "application/octet-stream", (long) pieces * piece.length,
                            Message.Chunk.of(piece)));
                    for (int i = 1; i < pieces / 2; i++) {
                        app.send(new Message.Data(r.id(), Message.Chunk.of(piece)));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            Message m = app.message();
            assertTrue(m instanceof Message.Abort a && a.id() == r.id(), "abbruch erwartet, kam " + m);
            sending.get(30, TimeUnit.SECONDS);
            log.await("anfrage_abgebrochen", "grund=plugin_liest_nicht");
            assertFreed(f, before);
            assertTrue(pongs.sent() > 0, "ohne Pongs hätte die Stille gegriffen");
            assertTrue(f.relay.hub().hasApp(), "die Sitzung der App bleibt");
            plugin.setSoTimeout(5_000);
            RelayFixture.Reply got = RelayFixture.readAll(plugin.getInputStream());
            assertTrue(got.reset() || got.bytes().length < pieces / 2 * piece.length, "Antwort hätte abbrechen müssen");
        }
    }

    /**
     * Schreiben zur App hängt (Relay.tick): Die App liest nicht mehr, hält die Sitzung aber mit Pongs am Leben. Nach
     * der Stille-Frist schließt das Relay die Sitzung (grund=schreiben_hängt); die Plugins bekommen RST, Budget frei.
     */
    @Test
    void appThatStopsReadingIsDisconnectedWhenWritingHangs() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.silence = Duration.ofMillis(700);
        try (RelayFixture.LogCapture log = new RelayFixture.LogCapture();
                RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6);
                Pongs pongs = new Pongs(app)) {
            Set<String> before = exchangeThreads();
            app.socket().setReceiveBufferSize(4096);
            List<CompletableFuture<RelayFixture.Reply>> replies = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                replies.add(async(f, RelayFixture.post(f.local(), "/v1/chat/completions", "application/json",
                        pattern(2 * 1024 * 1024, i))));
            }
            log.await("app_getrennt", "grund=schreiben_hängt");
            assertTrue(pongs.sent() > 0, "ohne Pongs hätte die Stille gegriffen");
            RelayFixture.await(() -> !f.relay.hub().hasApp(), "Sitzung geschlossen");
            for (CompletableFuture<RelayFixture.Reply> r : replies) {
                RelayFixture.Reply got = r.get(20, TimeUnit.SECONDS);
                assertEquals(0, got.bytes().length, "ohne App darf keine Antwort kommen: " + got.text());
            }
            assertFreed(f, before);
        }
    }

    /** Mehr als maxLocal gleichzeitige Plugin-Verbindungen (LocalListener): die nächste sofort mit RST. */
    @Test
    void connectionsBeyondMaxLocalAreResetImmediately() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.maxLocal = 2;
        try (RelayFixture.LogCapture log = new RelayFixture.LogCapture();
                RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6)) {
            CompletableFuture<RelayFixture.Reply> one = async(f, RelayFixture.get(f.local(), "/health"));
            CompletableFuture<RelayFixture.Reply> two = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r1 = app.request();
            FakeApp.Request r2 = app.request();
            long start = System.nanoTime();
            End third;
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), f.local())) {
                try {
                    s.getOutputStream().write(RelayFixture.get(f.local(), "/health"));
                    s.getOutputStream().flush();
                } catch (IOException e) {
                    // RST kam schon vor dem Schreiben an – das Lesen unten sieht es ebenso.
                }
                third = endOf(s, 3_000);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertEquals(End.RESET, third, "dritte Verbindung über maxLocal");
            assertTrue(ms < 2_000, "RST kam erst nach " + ms + " ms");
            log.await("lokal_zu_viele", "grenze=2");
            assertEquals(2, f.relay.hub().exchanges().size(), "die beiden laufenden Anfragen bleiben");
            app.answer(r1.id(), 200, "text/plain", new byte[0], 65_536);
            app.answer(r2.id(), 200, "text/plain", new byte[0], 65_536);
            assertEquals(200, one.get(10, TimeUnit.SECONDS).status());
            assertEquals(200, two.get(10, TimeUnit.SECONDS).status());
            // Plätze wieder frei: Die nächste Verbindung geht durch – die App sieht sie als nächste Anfrage.
            RelayFixture.await(() -> f.relay.hub().exchanges().isEmpty(), "Plätze frei");
            CompletableFuture<RelayFixture.Reply> next = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request r3 = app.request();
            assertEquals(3, r3.id(), "die zurückgesetzte Verbindung hat die App nie erreicht");
            app.answer(r3.id(), 200, "text/plain", new byte[0], 65_536);
            assertEquals(200, next.get(10, TimeUnit.SECONDS).status());
        }
    }

    /**
     * Kopf-Frist der Plugin-Seite (Slowloris): Ein Client tröpfelt alle 100 ms ein Byte, die Lesefrist je Byte greift
     * also nie – nur die Frist für den ganzen Kopf. Geprüft wird das Schließen, keine Antwort: Das Relay antwortet hier
     * gar nicht, und Windows schickt beim Schließen mit ungelesenen Bytes RST statt FIN (Host-Gedächtnis,
     * Slowloris-Test).
     */
    @Test
    void pluginHeaderThatTricklesInIsClosedAtTheHeaderDeadline() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.localHeader = Duration.ofMillis(500);
        try (RelayFixture.LogCapture log = new RelayFixture.LogCapture();
                RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6);
                Socket s = new Socket(InetAddress.getLoopbackAddress(), f.local())) {
            Set<String> before = exchangeThreads();
            byte[] head = ("GET /health HTTP/1.1\r\nHost: 127.0.0.1:" + f.local() + "\r\nX-Langsam: " + "a".repeat(100))
                    .getBytes(StandardCharsets.US_ASCII);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            long start = System.nanoTime();
            long closedMs = -1;
            for (int i = 0; i < head.length && closedMs < 0; i++) {
                try {
                    out.write(head[i]);
                    out.flush();
                    // Kurz nachsehen: EOF, RST oder Daten heißen „zu“ bzw. „Antwort“ – beides darf hier nicht offen
                    // bleiben. Zeitüberschreitung = Verbindung steht noch.
                    s.setSoTimeout(1);
                    int c = in.read();
                    assertEquals(-1, c, "das Relay darf auf einen halben Kopf nicht antworten");
                    closedMs = (System.nanoTime() - start) / 1_000_000;
                } catch (SocketTimeoutException e) {
                    Thread.sleep(100);
                } catch (IOException e) {
                    closedMs = (System.nanoTime() - start) / 1_000_000;
                }
            }
            assertTrue(closedMs >= 0, "Verbindung nach " + head.length + " Bytes in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms noch offen – Kopf-Frist greift nicht");
            assertTrue(closedMs >= 400 && closedMs < 3_000, "Kopf-Frist nicht eingehalten: " + closedMs + " ms");
            log.await("anfrage_ohne_antwort", "grund=lesen_abgebrochen");
            assertFreed(f, before);
            // Die App hat vom halben Kopf nichts gesehen: Die nächste Nachricht ist die nächste echte Anfrage.
            CompletableFuture<RelayFixture.Reply> ok = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request real = app.request();
            assertEquals(1, real.id());
            app.answer(real.id(), 200, "text/plain", new byte[0], 65_536);
            assertEquals(200, ok.get(10, TimeUnit.SECONDS).status());
        }
    }

    /**
     * Budget auf der Anfrage-Seite voll (Hub.reserveRequest): Eine gestaute Antwort (Plugin liest nicht) hält fast das
     * ganze Budget, dann kommt ein POST. Die gestaute Antwort wird abgebrochen (größte offene Antwort, Abschnitt 4),
     * und der POST geht durch – statt dass ein Plugin, das nicht liest, alle anderen Anfragen aussperrt.
     */
    @Test
    void fullBudgetOnTheRequestSideAbortsTheLargestOpenResponse() throws Exception {
        int limit = 256 * 1024;
        Limits.Builder b = RelayFixture.fast();
        b.byteBudget = limit;
        try (RelayFixture.LogCapture log = new RelayFixture.LogCapture();
                RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6);
                Socket slow = new Socket()) {
            slow.setReceiveBufferSize(4096);
            slow.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), f.local()));
            slow.getOutputStream().write(RelayFixture.get(f.local(), "/baumeister/v1/stadt"));
            slow.getOutputStream().flush();
            FakeApp.Request stalled = app.request();
            byte[] piece = pattern(16 * 1024, 9);
            app.send(new Message.Response(stalled.id(), 200, "application/octet-stream", 16L * 1024 * 1024,
                    Message.Chunk.of(piece)));
            // Stückweise nachlegen, bis mehr als limit - 64 KiB gepuffert sind: Dann passt das erste Anfragestück
            // (64 KiB) nicht mehr. 16-KiB-Stücke und Pausen halten den Stau unter dem Budget, damit die Antwort nicht
            // schon an sich selbst scheitert.
            for (int i = 0; i < 400 && f.relay.budget().used() <= 200 * 1024; i++) {
                app.send(new Message.Data(stalled.id(), Message.Chunk.of(piece)));
                Thread.sleep(30);
            }
            long used = f.relay.budget().used();
            assertTrue(used > limit - 65_536, "Stau nicht erreicht: " + used + " Bytes gepuffert");
            assertTrue(f.relay.hub().exchanges().stream().anyMatch(LocalExchange::responseOpen),
                    "gestaute Antwort muss noch offen sein");

            CompletableFuture<RelayFixture.Reply> post = async(f, RelayFixture.post(f.local(), "/v1/chat/completions",
                    "application/json", pattern(65_536, 4)));
            boolean aborted = false;
            FakeApp.Request request = null;
            app.socket().setSoTimeout(5_000);
            while (!aborted || request == null) {
                Message m = app.message();
                if (m instanceof Message.Abort a && a.id() == stalled.id()) {
                    aborted = true;
                } else if (m instanceof Message.Request q) {
                    assertTrue(aborted, "abbruch der gestauten Antwort muss vor der neuen Anfrage kommen");
                    assertEquals(65_536, q.chunk().length());
                    request = new FakeApp.Request(q.id(), q.method(), q.path(), q.contentType(), null);
                } else {
                    throw new AssertionError("abbruch bzw. anfrage erwartet, kam " + m);
                }
            }
            app.answer(request.id(), 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), 65_536);
            assertEquals(200, post.get(10, TimeUnit.SECONDS).status());
            assertTrue(f.relay.budget().peak() <= limit, "Budget überschritten: " + f.relay.budget().peak());
            log.await("anfrage_abgebrochen", "grund=budget");
            RelayFixture.await(() -> f.relay.budget().used() == 0, "Budget wieder frei");
            assertEquals(End.RESET, endOf(slow, 5_000), "gestaute Antwort endet mit RST");
        }
    }

    /** Budget kleiner als das erste Anfragestück und keine offene Antwort: RST, die App sieht die Anfrage nie. */
    @Test
    void requestThatDoesNotFitIntoTheBudgetIsResetWithoutReachingTheApp() throws Exception {
        Limits.Builder b = RelayFixture.fast();
        b.byteBudget = 32 * 1024;
        try (RelayFixture f = RelayFixture.start(b.build()); FakeApp app = f.app("app", 6)) {
            RelayFixture.Reply r = f.plugin(RelayFixture.post(f.local(), "/v1/chat/completions", "application/json",
                    pattern(65_536, 2)));
            assertEquals(0, r.bytes().length, "keine Antwort erwartet: " + r.text());
            assertTrue(r.reset(), "RST erwartet");
            RelayFixture.await(() -> f.relay.hub().exchanges().isEmpty(), "Austausch aufgeräumt");
            assertEquals(0, f.relay.budget().used());
            // Ohne Body braucht eine Anfrage kein Budget – sie geht durch, und die App sieht sie als erste.
            CompletableFuture<RelayFixture.Reply> ok = async(f, RelayFixture.get(f.local(), "/health"));
            FakeApp.Request real = app.request();
            assertEquals("/health", real.path());
            app.answer(real.id(), 200, "text/plain", new byte[0], 65_536);
            assertEquals(200, ok.get(10, TimeUnit.SECONDS).status());
        }
    }
}
