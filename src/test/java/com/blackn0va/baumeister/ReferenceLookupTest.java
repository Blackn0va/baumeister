package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recherche vor dem Planen (Wunsch 2026-10-30). Wikipedia und Wikidata als Attrappe – Antworten im Format der echten
 * APIs, gekürzt.
 */
class ReferenceLookupTest {

    private static final String DOM_SEARCH = "{\"query\":{\"search\":[{\"title\":\"Kölner Dom\"}]}}";
    private static final String DOM_PAGE = "{\"query\":{\"pages\":{\"1\":{\"title\":\"Kölner Dom\",\"pageprops\":"
            + "{\"wikibase_item\":\"Q4176\"},\"extract\":\"Der Kölner Dom ist eine gotische Kathedrale in Köln.\"}}}}";
    private static final String M = "http://www.wikidata.org/entity/Q11573";
    private static final String DOM_CLAIMS = "{\"entities\":{\"Q4176\":{\"claims\":{"
            + "\"P2048\":[{\"rank\":\"normal\",\"mainsnak\":{\"datavalue\":{\"value\":{\"amount\":\"+157\",\"unit\":\"" + M + "\"}}}}],"
            + "\"P2043\":[{\"rank\":\"normal\",\"mainsnak\":{\"datavalue\":{\"value\":{\"amount\":\"+144.5\",\"unit\":\"" + M + "\"}}}}],"
            + "\"P149\":[{\"rank\":\"normal\",\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q176483\"}}}}],"
            + "\"P31\":[{\"rank\":\"normal\",\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q2977\"}}}}]}}}}";
    private static final String LABELS = "{\"entities\":{\"Q176483\":{\"labels\":{\"en\":{\"value\":\"Gothic "
            + "architecture\"}}},\"Q2977\":{\"labels\":{\"en\":{\"value\":\"cathedral\"}}}}}";

    /** Kleiner Wikimedia-Nachbau: answer bekommt die dekodierte Abfrage und liefert den Körper (null = 429). */
    private record Fake(HttpServer server, AtomicInteger calls) implements AutoCloseable {
        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/w/api.php";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Fake fake(Function<String, String> answer) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/w/api.php", ex -> {
            calls.incrementAndGet();
            String q = URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            String body = answer.apply(q);
            byte[] b = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 429 : 200, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        s.start();
        return new Fake(s, calls);
    }

    private static String dom(String q) {
        if (q.contains("list=search")) {
            return DOM_SEARCH;
        }
        if (q.contains("prop=pageprops")) {
            return DOM_PAGE;
        }
        return q.contains("props=labels") ? LABELS : DOM_CLAIMS;
    }

    private static Optional<ReferenceLookup.Reference> look(ReferenceLookup r, String prompt) throws Exception {
        return r.lookup(prompt).get(20, TimeUnit.SECONDS);
    }

    @Test
    void realBuildingComesWithItsSizeStyleAndDescription(@TempDir Path dir) throws Exception {
        try (Fake f = fake(ReferenceLookupTest::dom)) {
            ReferenceLookup r = new ReferenceLookup(f.url(), f.url(), dir, "test");
            ReferenceLookup.Reference ref = look(r, "bau mir den Kölner Dom").orElseThrow();
            assertEquals(157.0, ref.height());
            assertEquals(144.5, ref.length());
            assertEquals("Gothic architecture", ref.style());
            String p = ref.prompt(192);
            assertTrue(p.contains("height 157 blocks") && p.contains("length 145 blocks")
                    && p.contains("at real size") && p.contains("cathedral") && p.contains("gotische"), p);
            assertEquals(157, ref.targetHeight(192));
            assertEquals(ref, r.cached("bau mir den Kölner Dom").orElseThrow(), "Prüfer liest dasselbe Ergebnis");
        }
    }

    @Test
    void preferredValueWinsAndBigBuildingsAreScaledToTheLimit(@TempDir Path dir) throws Exception {
        String claims = "{\"entities\":{\"Q243\":{\"claims\":{\"P2048\":["
                + "{\"rank\":\"normal\",\"mainsnak\":{\"datavalue\":{\"value\":{\"amount\":\"+324\",\"unit\":\"" + M + "\"}}}},"
                + "{\"rank\":\"preferred\",\"mainsnak\":{\"datavalue\":{\"value\":{\"amount\":\"+330\",\"unit\":\"" + M
                + "\"}}}}]}}}}";
        try (Fake f = fake(q -> q.contains("list=search") ? "{\"query\":{\"search\":[{\"title\":\"Eiffelturm\"}]}}"
                : q.contains("prop=pageprops") ? "{\"query\":{\"pages\":{\"9\":{\"pageprops\":{\"wikibase_item\":"
                        + "\"Q243\"},\"extract\":\"Turm in Paris.\"}}}}" : claims)) {
            ReferenceLookup.Reference ref = look(new ReferenceLookup(f.url(), f.url(), dir, "test"), "bau den Eiffelturm")
                    .orElseThrow();
            assertEquals(330.0, ref.height(), "bevorzugter Wert, nicht der erste (324)");
            assertEquals(184, ref.targetHeight(192), "auf bau.max-ausdehnung 192 verkleinert (8 Blöcke Luft)");
            assertTrue(ref.prompt(192).contains("scaled down to 56 %"), ref.prompt(192));
        }
    }

    @Test
    void anArticleWhoseTitleIsNotInTheOrderIsIgnored(@TempDir Path dir) throws Exception {
        try (Fake f = fake(q -> q.contains("list=search") ? "{\"query\":{\"search\":[{\"title\":\"Haus der Kunst\"}]}}"
                : dom(q))) {
            assertTrue(look(new ReferenceLookup(f.url(), f.url(), dir, "test"), "bau ein kleines Haus").isEmpty());
            assertEquals(1, f.calls().get(), "nur die Suche, kein Wikidata-Aufruf");
        }
    }

    @Test
    void throttlingIsNotRememberedSoTheNextOrderAsksAgain(@TempDir Path dir) throws Exception {
        boolean[] busy = {true};
        try (Fake f = fake(q -> busy[0] ? null : dom(q))) {
            ReferenceLookup r = new ReferenceLookup(f.url(), f.url(), dir, "test");
            assertTrue(look(r, "bau den Kölner Dom").isEmpty(), "429: ohne Referenz weiter");
            busy[0] = false;
            assertTrue(look(r, "bau den Kölner Dom").isPresent(), "danach wieder gefragt, nicht „nichts“ gemerkt");
        }
    }

    /** Baumeister-App als Attrappe: GET liefert erlaubtJson (null = 404), POST merkt sich den Körper. */
    private static HttpServer app(String erlaubtJson, StringBuilder posted) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/baumeister/v1/recherche", ex -> {
            byte[] b;
            int code;
            if (ex.getRequestMethod().equals("POST")) {
                synchronized (posted) {
                    posted.append(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    posted.notifyAll();
                }
                b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                code = 200;
            } else {
                b = (erlaubtJson == null ? "{}" : erlaubtJson).getBytes(StandardCharsets.UTF_8);
                code = erlaubtJson == null ? 404 : 200;
            }
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        });
        s.start();
        return s;
    }

    private static java.net.URI appUri(HttpServer s) {
        return java.net.URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/baumeister/v1/recherche");
    }

    /** Wunsch 2026-10-30 – in der App abgewählt heißt: keine einzige Abfrage nach außen. */
    @Test
    void switchedOffInTheAppMeansNotASingleRequestOutside(@TempDir Path dir) throws Exception {
        HttpServer app = app("{\"erlaubt\":false}", new StringBuilder());
        try (Fake f = fake(ReferenceLookupTest::dom)) {
            ReferenceLookup r = new ReferenceLookup(f.url(), f.url(), dir, "test", appUri(app));
            assertTrue(look(r, "bau den Kölner Dom").isEmpty());
            assertEquals(0, f.calls().get(), "Wikipedia/Wikidata nie gefragt");
        } finally {
            app.stop(0);
        }
    }

    @Test
    void olderAppWithoutTheSwitchLeavesItToThePluginAndGetsTheResult(@TempDir Path dir) throws Exception {
        StringBuilder posted = new StringBuilder();
        HttpServer app = app(null, posted);
        try (Fake f = fake(ReferenceLookupTest::dom)) {
            ReferenceLookup r = new ReferenceLookup(f.url(), f.url(), dir, "test", appUri(app));
            ReferenceLookup.Reference ref = look(r, "bau den Kölner Dom").orElseThrow();
            r.report("bau den Kölner Dom", ref, 192);
            synchronized (posted) {
                long until = System.currentTimeMillis() + 5000;
                while (posted.isEmpty() && System.currentTimeMillis() < until) {
                    posted.wait(200);
                }
            }
            assertTrue(posted.toString().contains("\"titel\":\"Kölner Dom\"") && posted.toString().contains(
                    "\"zielhoehe\":157"), posted.toString());
        } finally {
            app.stop(0);
        }
    }

    @Test
    void secondServerStartReadsTheDiskInsteadOfAskingAgain(@TempDir Path dir) throws Exception {
        try (Fake f = fake(ReferenceLookupTest::dom)) {
            look(new ReferenceLookup(f.url(), f.url(), dir, "test"), "bau den Kölner Dom").orElseThrow();
            int asked = f.calls().get();
            assertEquals(157.0, look(new ReferenceLookup(f.url(), f.url(), dir, "test"), "bau den Kölner Dom")
                    .orElseThrow().height());
            assertEquals(asked, f.calls().get(), "Platte statt Netz");
        }
    }
}
