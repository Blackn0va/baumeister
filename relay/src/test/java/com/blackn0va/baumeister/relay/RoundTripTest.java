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
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
}
