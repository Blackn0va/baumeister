package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Aufruf des Relays wie auf dev01: --prüfen, --fingerabdruck, Exitcodes – und der echte Start als eigener Prozess
 * (nur so ist belegt, dass Main die JSSE-Eigenschaften vor dem ersten TLS-Zugriff setzt und das Log im Format steht).
 */
class MainTest {

    @TempDir
    Path dir;

    private record Run(int code, String out, String err) {
    }

    private Run main(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path config(String pins) throws Exception {
        Path c = dir.resolve("relay.properties");
        Files.writeString(c, "lokal.adresse=127.0.0.1\nlokal.port=0\nöffentlich.adresse=127.0.0.1\nöffentlich.port=0\n"
                + "schlüssel.datei=credential:relay.p12\nschlüssel.passwort=credential:relay.pass\n"
                + "status.datei=" + dir.resolve("status").toString().replace('\\', '/') + "\n" + pins,
                StandardCharsets.UTF_8);
        return c;
    }

    private static Map<String, String> env() throws Exception {
        return Map.of("CREDENTIALS_DIRECTORY", TestKeys.get().dir.toString());
    }

    @Test
    void checkModeAcceptsAValidConfigurationAndShowsOnlyShortPins() throws Exception {
        TestKeys k = TestKeys.get();
        Path c = config("erlaubt.gpu-pc=" + k.pin("app") + "\n");
        Run r = main(env(), "--konfig", c.toString(), "--prüfen");
        assertEquals(Main.EXIT_OK, r.code(), r.err());
        assertTrue(r.out().contains("Konfiguration in Ordnung"), r.out());
        assertTrue(r.out().contains("gpu-pc (sha256:" + Pins.brief(k.pin("app")) + "…)"), r.out());
        assertFalse(r.out().contains(k.pin("app")), "ganzer Pin in der Ausgabe");
        assertEquals(Main.EXIT_OK, main(env(), "--konfig", c.toString(), "--pruefen").code());  // umlaut-ok: Schalter
    }

    @Test
    void checkModeWithoutAnyAppWarnsAndExits2() throws Exception {
        Run r = main(env(), "--konfig", config("").toString(), "--prüfen");
        assertEquals(Main.EXIT_WARNINGS, r.code());
        assertTrue(r.out().contains("Keine App erlaubt"), r.out());
    }

    @Test
    void fingerprintPrintsTheFullServerPin() throws Exception {
        Run r = main(env(), "--konfig", config("").toString(), "--fingerabdruck");
        assertEquals(Main.EXIT_OK, r.code(), r.err());
        assertEquals(TestKeys.get().pin("relay"), r.out().strip());
    }

    @Test
    void brokenConfigurationExits78WithAGermanReason() throws Exception {
        Path bad = dir.resolve("kaputt.properties");
        Files.writeString(bad, "lokal.adresse=0.0.0.0\nschlüssel.datei=credential:relay.p12\n"
                + "schlüssel.passwort=credential:relay.pass\n", StandardCharsets.UTF_8);
        Run r = main(env(), "--konfig", bad.toString(), "--prüfen");
        assertEquals(Main.EXIT_CONFIG, r.code());
        assertTrue(r.err().startsWith("baumeister-relay: "), r.err());
        assertTrue(r.err().contains("Loopback"), r.err());
        Run missing = main(env(), "--konfig", dir.resolve("fehlt").toString(), "--prüfen");
        assertEquals(Main.EXIT_CONFIG, missing.code());
        assertTrue(missing.err().contains("nicht lesbar"), missing.err());
        Run noCredentials = main(Map.of(), "--konfig", config("").toString(), "--prüfen");
        assertEquals(Main.EXIT_CONFIG, noCredentials.code());
        assertTrue(noCredentials.err().contains("CREDENTIALS_DIRECTORY"), noCredentials.err());
    }

    @Test
    void unknownSwitchesExit64() {
        assertEquals(Main.EXIT_USAGE, main(Map.of(), "--irgendwas").code());
        assertEquals(Main.EXIT_USAGE, main(Map.of(), "--konfig").code());
        assertEquals(Main.EXIT_OK, main(Map.of(), "--hilfe").code());
    }

    /**
     * Eigener Prozess: Start, Ports aus dem Log, App zweimal mit demselben Client-Kontext (jedes Mal voller
     * Handshake = Main hat die Tickets abgeschaltet, bevor JSSE sie las), eine Anfrage, Logformat ohne Inhalte und
     * ohne ganze Pins.
     */
    @Test
    void realProcessStartsLogsInGermanAndNeverResumesSessions() throws Exception {
        TestKeys k = TestKeys.get();
        Path c = config("erlaubt.gpu-pc=" + k.pin("app") + "\n");
        List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx32m",
                "-cp", Path.of("target", "classes").toAbsolutePath().toString(), "com.blackn0va.baumeister.relay.Main",
                "--konfig", c.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("CREDENTIALS_DIRECTORY", k.dir.toString());
        Process p = pb.start();
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException e) {
                lines.add("LESEFEHLER " + e);
            }
        });
        try {
            RelayFixture.await(() -> lines.stream().anyMatch(l -> l.contains("relay | gestartet")), "Start im Log");
            String started = lines.stream().filter(l -> l.contains("relay | gestartet")).findFirst().orElseThrow();
            Matcher m = Pattern.compile("lokal=127\\.0\\.0\\.1:(\\d+) öffentlich=127\\.0\\.0\\.1:(\\d+)")
                    .matcher(started);
            assertTrue(m.find(), started);
            int local = Integer.parseInt(m.group(1));
            int external = Integer.parseInt(m.group(2));
            assertTrue(started.startsWith("INFO relay | gestartet | fassung=baumeister-relay/"), started);

            FakeApp.Identity id = new FakeApp.Identity(k.key("app"), new java.security.cert.X509Certificate[] {
                k.cert("app")}, k.pin("relay"));
            FakeApp.connect(id, external, 6).close();
            try (FakeApp app = FakeApp.connect(id, external, 6)) {
                assertEquals(2, id.chooseCount.get(), "zweite Verbindung ohne vollen Handshake (Wiederaufnahme)");
                CompletableFuture<RelayFixture.Reply> reply = CompletableFuture.supplyAsync(() -> {
                    try (java.net.Socket s = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), local)) {
                        s.getOutputStream().write(RelayFixture.post(local, "/v1/chat/completions?x=geheim",
                                "application/json", "{\"prompt\":\"GEHEIMER INHALT\"}".getBytes(StandardCharsets.UTF_8)));
                        return RelayFixture.readAll(s.getInputStream());
                    } catch (IOException e) {
                        return new RelayFixture.Reply(new byte[0], true);
                    }
                });
                FakeApp.Request r = app.request();
                app.answer(r.id(), 200, "application/json", "{\"antwort\":\"AUCH GEHEIM\"}".getBytes(
                        StandardCharsets.UTF_8), 65_536);
                RelayFixture.Reply got = reply.get(10, TimeUnit.SECONDS);
                assertEquals(200, got.status());
                assertEquals("gpu-pc", got.header("X-Baumeister-App"));
            }
            RelayFixture.await(() -> lines.stream().anyMatch(l -> l.contains("relay | anfrage |")), "Anfrage im Log");
            String all = String.join("\n", lines);
            assertFalse(all.contains("GEHEIM"), "Inhalt im Log:\n" + all);
            assertFalse(all.contains("x=geheim"), "Abfrage im Log:\n" + all);
            assertFalse(all.contains(k.pin("app").substring(7)), "ganzer Pin im Log:\n" + all);
            assertFalse(all.contains(k.pin("relay").substring(7)), "ganzer Server-Pin im Log:\n" + all);
            for (String line : lines) {
                assertTrue(line.matches("(INFO|WARNUNG|FEHLER|FEIN) relay \\| [a-zäöüß_]+( \\| .*)?"), "Format: " + line);
            }
        } finally {
            p.destroy();
            assertTrue(p.waitFor(20, TimeUnit.SECONDS), "Relay-Prozess endet nicht");
            reader.get(10, TimeUnit.SECONDS);
        }
    }
}
