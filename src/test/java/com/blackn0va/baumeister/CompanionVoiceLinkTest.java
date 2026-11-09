package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CompanionVoiceLinkTest {

    static final URI HEARD = URI.create("http://127.0.0.1:18080" + CompanionVoiceLink.HEARD_PATH);

    /** Zwei Sätze in einer Antwort: einer für den Admin, einer für einen Mitspieler. umlaut-ok: ASCII-Schlüssel */
    private static final String TWO = "{\"saetze\":[{\"spieler\":\"Blackkn0va\",\"text\":\"Vera, ja\"}," // umlaut-ok
            + "{\"spieler\":\"Steve\",\"text\":\"Vera, mach Tag\"}]}";

    /** gpu-pc darf für Blackkn0va sprechen, sonst niemand für niemanden. */
    private static final VoiceAppBinding GPU_PC = VoiceAppBinding.of(Map.of("gpu-pc", List.of("Blackkn0va")));

    @Test
    void heardUriNamesPlayersInVoiceChat() {
        // Nutzer 2026-11-04: Im Voice-Chat soll die App-Taste ruhen – die App erfährt es über diese Liste.
        assertEquals(HEARD + "?imspiel=Blackkn0va,Steve",
                CompanionVoiceLink.heardUri(HEARD, List.of("Blackkn0va", "Steve")).toString());
        assertEquals(HEARD, CompanionVoiceLink.heardUri(HEARD, List.of()));
        assertEquals(HEARD + "?imspiel=ok_1", CompanionVoiceLink.heardUri(HEARD, List.of("a b", "x", "ok_1"))
                .toString(), "ungültige Namen fallen weg");
    }

    /**
     * Fake-Host wie das Relay (docs/kanal-v1.md, Abschnitt 6): Die erste Abholung liefert TWO mit dem Kopf
     * X-Baumeister-App (null = ohne Kopf), jede weitere hält bis zum Testende. Fragt der Link ein zweites Mal, hat er
     * die erste Antwort fertig verarbeitet (poll ruft accept vor dem nächsten Abholen) – erst dann wird gezählt.
     */
    private static List<String> heardThroughHttp(VoiceAppBinding binding, String app) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger polls = new AtomicInteger();
        CountDownLatch second = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext(CompanionVoiceLink.HEARD_PATH, ex -> {
            if (polls.incrementAndGet() > 1) {
                second.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ex.close();
                return;
            }
            byte[] body = TWO.getBytes(StandardCharsets.UTF_8);
            if (app != null) {
                ex.getResponseHeaders().add(VoiceAppBinding.HEADER, app);
            }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        CompanionVoiceLink link = new CompanionVoiceLink(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"),
                HttpClient.newHttpClient(), System::nanoTime, binding);
        List<String> heard = new CopyOnWriteArrayList<>();
        try {
            link.listen((p, t) -> heard.add(p + ":" + t));
            assertTrue(second.await(10, TimeUnit.SECONDS), "zweite Abholung kam nicht");
            return new ArrayList<>(heard);
        } finally {
            link.stop();
            release.countDown();
            server.stop(0);
        }
    }

    /** Plan A4: Die App gpu-pc spricht für Blackkn0va – der Satz für Steve aus derselben Antwort fällt weg. */
    @Test
    void boundLinkTakesOnlySentencesOfPlayersOfTheAppInTheHeader() throws Exception {
        assertEquals(List.of("Blackkn0va:Vera, ja"), heardThroughHttp(GPU_PC, "gpu-pc"));
    }

    /** Ohne Kopf (Tunnel, fremder Prozess auf 18080) oder von einer unbekannten App: nichts, fail-closed. */
    @Test
    void boundLinkDropsEverythingWithoutOrWithUnknownApp() throws Exception {
        assertEquals(List.of(), heardThroughHttp(GPU_PC, null));
        assertEquals(List.of(), heardThroughHttp(GPU_PC, "laptop"));
        assertEquals(List.of(), heardThroughHttp(GPU_PC, "gpu-pc,laptop"), "zusammengelegte Köpfe sind keine App");
    }

    /** Ohne Einträge in fernverbindung.apps wie vor Plan A4 – sonst wäre die Sprache über den SSH-Tunnel stumm. */
    @Test
    void unboundLinkKeepsTheTunnelBehaviour() throws Exception {
        assertEquals(List.of("Blackkn0va:Vera, ja", "Steve:Vera, mach Tag"),
                heardThroughHttp(VoiceAppBinding.unbound(), null));
    }

    /** Zeilen des Plugin-Loggers mit einem Stichwort, solange offen (try-with-resources hängt ihn wieder ab). */
    private static final class LogLines extends Handler implements AutoCloseable {
        private final String marker;
        final List<String> lines = new CopyOnWriteArrayList<>();

        LogLines(String marker) {
            this.marker = marker;
            Logger.getLogger("Baumeister").addHandler(this);
        }

        @Override
        public void publish(LogRecord r) {
            if (r.getMessage() != null && r.getMessage().contains(marker)) {
                lines.add(r.getMessage());
            }
        }

        @Override
        public void flush() {
            // Nichts gepuffert – die Zeilen liegen schon in der Liste.
        }

        @Override
        public void close() {
            Logger.getLogger("Baumeister").removeHandler(this);
        }
    }

    /**
     * from() liest fernverbindung.apps wirklich aus der Config und sagt beim Start, ob die Sprache an Apps hängt. Ohne
     * diese Prüfung fiele ein from(), das die Bindung vergisst, keinem Test auf – alle anderen bauen den Link selbst.
     */
    @Test
    void fromReadsTheBindingFromTheConfigAndSaysSoOnce() throws Exception {
        URI llm = URI.create("http://127.0.0.1:18080/v1/chat/completions");
        try (LogLines log = new LogLines("| sprache_")) {
            List<String> heard = new ArrayList<>();
            CompanionVoiceLink.from(new YamlConfiguration(), llm).accept(TWO, List.of(), (p, t) -> heard.add(p));
            assertEquals(List.of("Blackkn0va", "Steve"), heard, "ohne Eintrag wie über den Tunnel");

            YamlConfiguration c = new YamlConfiguration();
            c.loadFromString("fernverbindung:\n  apps:\n    gpu-pc:\n      spieler: [Blackkn0va]\n");
            heard.clear();
            CompanionVoiceLink.from(c, llm).accept(TWO, List.of("gpu-pc"), (p, t) -> heard.add(p));
            assertEquals(List.of("Blackkn0va"), heard, "mit Eintrag nur der Spieler der App");

            assertEquals(2, log.lines.size(), log.lines.toString());
            assertTrue(log.lines.get(0).contains("sprache_ohne_app_bindung"), log.lines.get(0));
            assertTrue(log.lines.get(1).contains("sprache_an_apps_gebunden | apps=gpu-pc=1"), log.lines.get(1));
        }
    }

    /** Verworfenes sofort ins Log, danach höchstens eine Zeile je Minute samt Anzahl (Uhr von außen). */
    @Test
    void droppedSentencesAreLoggedAtMostOncePerMinuteWithTheirCount() {
        AtomicLong now = new AtomicLong();
        CompanionVoiceLink link = new CompanionVoiceLink(URI.create("http://127.0.0.1:1/x"),
                HttpClient.newHttpClient(), now::get, GPU_PC);
        try (LogLines log = new LogLines("gehört_verworfen")) {
            List<String> lines = log.lines;
            List<String> heard = new ArrayList<>();
            link.accept(TWO, List.of("gpu-pc"), (p, t) -> heard.add(p));
            assertEquals(List.of("Blackkn0va"), heard);
            assertEquals(1, lines.size(), lines.toString());
            assertTrue(lines.getFirst().contains("spieler=Steve app=gpu-pc grund=spieler_nicht_bei_app anzahl=1"),
                    lines.getFirst());

            now.set(TimeUnit.SECONDS.toNanos(30));
            link.accept(TWO, List.of(), (p, t) -> heard.add(p));
            assertEquals(1, lines.size(), "innerhalb der Minute keine weitere Zeile");

            now.set(TimeUnit.SECONDS.toNanos(61));
            // umlaut-ok: ASCII-Schlüssel der Schnittstelle
            link.accept("{\"saetze\":[{\"spieler\":\"x\\nbegleiter | gefälscht\",\"text\":\"ja\"}]}",
                    List.of("tablet"), (p, t) -> heard.add(p));
            assertEquals(2, lines.size(), lines.toString());
            assertTrue(lines.get(1).contains("spieler=ungültig app=tablet grund=app_unbekannt anzahl=3"),
                    "zwei ohne Kopf, einer von tablet – Steve stand schon in der ersten Zeile: " + lines.get(1));
            assertTrue(lines.stream().noneMatch(l -> l.contains("\n")), "Zeilenumbruch aus dem Namen im Log");
            assertEquals(List.of("Blackkn0va"), heard, "ohne Kopf und von tablet kommt nichts durch");
        }
    }
}
