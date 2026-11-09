package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Messprobe (nur auf Wunsch: mvn -o -f relay/pom.xml test -Drelay.messen=ja -Dtest=RelayMeasurementTest).
 *
 * Startet das Relay als eigenen Prozess mit GENAU den JVM-Optionen aus relay/deploy/baumeister-relay.service und
 * misst unter Last: Durchsatz (Antworten und Anfragen), Handshakes je Sekunde, Heap, Metaspace, Code-Cache und den
 * Höchststand des Arbeitsspeichers (Windows: PeakWorkingSet64, Linux: VmHWM). Ergebnis auf stdout und in
 * target/messung.txt. Unter Windows gemessen ist das ein Anhaltspunkt für dev01 – dort mit systemctl/ps nachmessen.
 */
@EnabledIfSystemProperty(named = "relay.messen", matches = "ja")
class RelayMeasurementTest {

    private static final long MIB = 1024L * 1024;

    /**
     * JVM-Optionen zwischen …/bin/java und -jar aus der Unit (Fortsetzungszeilen zusammengefügt). Vorher fest
     * „/usr/bin/java“ – seit die Unit Temurin 25 unter /opt/temurin-25-jre startet, fand die Probe ExecStart nicht mehr
     * (gefunden 2026-11-09 beim Umzug nach relay/deploy).
     */
    static List<String> unitJvmFlags() throws IOException {
        String unit = Files.readString(Path.of("deploy", "baumeister-relay.service"),
                StandardCharsets.UTF_8).replace("\\\n", " ");
        Matcher m = Pattern.compile("(?m)^ExecStart=\\S+/bin/java (.*?) -jar ").matcher(unit);
        assertTrue(m.find(), "ExecStart nicht gefunden");
        List<String> flags = new ArrayList<>();
        for (String f : m.group(1).trim().split("\\s+")) {
            flags.add(f);
        }
        return flags;
    }

    @Test
    void measureTheRelayUnderLoadWithTheFlagsOfTheUnit() throws Exception {
        TestKeys k = TestKeys.get();
        Path dir = Files.createTempDirectory("relay-messung-");
        Path cfg = dir.resolve("relay.properties");
        Files.writeString(cfg, "lokal.adresse=127.0.0.1\nlokal.port=0\nöffentlich.adresse=127.0.0.1\nöffentlich.port=0\n"
                + "schlüssel.datei=credential:relay.p12\nschlüssel.passwort=credential:relay.pass\n"
                + "status.datei=" + dir.resolve("status").toString().replace('\\', '/') + "\n"
                + "erlaubt.gpu-pc=" + k.pin("app") + "\n", StandardCharsets.UTF_8);
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> flags = unitJvmFlags();
        cmd.addAll(flags);
        cmd.addAll(List.of("-cp", Path.of("target", "classes").toAbsolutePath().toString(),
                "com.blackn0va.baumeister.relay.Main", "--konfig", cfg.toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("CREDENTIALS_DIRECTORY", k.dir.toString());
        pb.environment().put("MALLOC_ARENA_MAX", "2");
        Process p = pb.start();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.add(line);
                }
            } catch (IOException e) {
                log.add("LESEFEHLER " + e);
            }
        });
        StringBuilder report = new StringBuilder("Relay-Messung ").append(java.time.LocalDate.now())
                .append(", JDK ").append(System.getProperty("java.version")).append(", ")
                .append(System.getProperty("os.name")).append('\n').append("JVM: ").append(String.join(" ", flags))
                .append('\n');
        try {
            RelayFixture.await(() -> log.stream().anyMatch(l -> l.contains("relay | gestartet")), "Start");
            String started = log.stream().filter(l -> l.contains("relay | gestartet")).findFirst().orElseThrow();
            Matcher m = Pattern.compile("lokal=127\\.0\\.0\\.1:(\\d+) öffentlich=127\\.0\\.0\\.1:(\\d+)").matcher(started);
            assertTrue(m.find(), started);
            int local = Integer.parseInt(m.group(1));
            int external = Integer.parseInt(m.group(2));
            report.append(started.substring(started.indexOf("budget_kb"))).append('\n');
            report.append("nach Start:         ").append(memory(p.pid())).append('\n');

            FakeApp.Identity id = new FakeApp.Identity(k.key("app"), new X509Certificate[] {k.cert("app")},
                    k.pin("relay"));
            long t0 = System.nanoTime();
            int handshakes = 100;
            for (int i = 0; i < handshakes; i++) {
                FakeApp.connect(id, external, 6).close();
            }
            double hs = handshakes / ((System.nanoTime() - t0) / 1e9);
            report.append(String.format(Locale.ROOT, "Anmeldungen (TLS 1.3 + Upgrade + hallo): %.0f/s%n", hs));

            try (FakeApp app = FakeApp.connect(id, external, 6)) {
                CompletableFuture<Void> server = CompletableFuture.runAsync(() -> serve(app));
                HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
                URI down = URI.create("http://127.0.0.1:" + local + "/v1/chat/completions");
                for (int i = 0; i < 20; i++) {
                    http.send(post(down, 100, 64 * 1024), HttpResponse.BodyHandlers.discarding());
                }
                long t1 = System.nanoTime();
                int rounds = 30;
                for (int i = 0; i < rounds; i++) {
                    HttpResponse<Void> r = http.send(post(down, 100, 4 * MIB), HttpResponse.BodyHandlers.discarding());
                    assertEquals(200, r.statusCode());
                }
                double downMiB = rounds * 4.0 / ((System.nanoTime() - t1) / 1e9);
                long t2 = System.nanoTime();
                for (int i = 0; i < rounds; i++) {
                    http.send(post(down, (int) (2 * MIB), 100), HttpResponse.BodyHandlers.discarding());
                }
                double upMiB = rounds * 2.0 / ((System.nanoTime() - t2) / 1e9);
                long t3 = System.nanoTime();
                for (int round = 0; round < 10; round++) {
                    List<CompletableFuture<HttpResponse<Void>>> par = new ArrayList<>();
                    for (int i = 0; i < 6; i++) {
                        par.add(http.sendAsync(post(down, 100, 3 * MIB + 512 * 1024),
                                HttpResponse.BodyHandlers.discarding()));
                    }
                    for (CompletableFuture<HttpResponse<Void>> f : par) {
                        assertEquals(200, f.get(60, TimeUnit.SECONDS).statusCode());
                    }
                }
                double parMiB = 10 * 6 * 3.5 / ((System.nanoTime() - t3) / 1e9);
                report.append(String.format(Locale.ROOT, "Durchsatz App→Plugin: %.0f MiB/s, Plugin→App: %.0f MiB/s,"
                        + " 6 parallel: %.0f MiB/s%n", downMiB, upMiB, parMiB));
                // Plugin liest nicht: Das Relay puffert bis zum Budget und bricht dann ab – Spitze des Budgets.
                try (Socket slow = new Socket(InetAddress.getLoopbackAddress(), local)) {
                    slow.setReceiveBufferSize(4096);
                    slow.getOutputStream().write(RelayFixture.post(local, "/baumeister/v1/stadt", "application/json",
                            "{\"mib\":16}".getBytes(StandardCharsets.UTF_8)));
                    slow.getOutputStream().flush();
                    Thread.sleep(3_000);
                }
                report.append("nach Last:          ").append(memory(p.pid())).append('\n');
                app.socket().close();
                server.get(30, TimeUnit.SECONDS);
            }
            report.append(jcmd(p.pid(), "GC.heap_info"));
            report.append(jcmd(p.pid(), "Compiler.codecache"));
            assertTrue(p.isAlive(), "Relay unter Last beendet:\n" + String.join("\n", log));
        } finally {
            p.destroy();
            p.waitFor(20, TimeUnit.SECONDS);
            Files.writeString(Path.of("target", "messung.txt"), report + "\nLog:\n" + String.join("\n", log),
                    StandardCharsets.UTF_8);
            System.out.println(report);
        }
    }

    /** Antwortet auf jede Anfrage: Die Größe der Antwort steht im Body – {"mib":n} in MiB oder {"antwort":n} in Bytes. */
    private static void serve(FakeApp app) {
        try {
            while (true) {
                FakeApp.Request r = app.request();
                String body = new String(r.body(), StandardCharsets.UTF_8);
                int size;
                if (body.startsWith("{\"mib\":")) {
                    size = (int) (Integer.parseInt(body.replaceAll("\\D", "")) * MIB);
                } else if (body.startsWith("{\"antwort\":")) {
                    size = Integer.parseInt(body.replaceAll("\\D", ""));
                } else {
                    size = 100;
                }
                app.answer(r.id(), 200, "application/octet-stream", new byte[size], 65_536);
            }
        } catch (IOException e) {
            // Ende der Messung: App-Verbindung geschlossen.
            System.out.println("Messung: App beendet (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static HttpRequest post(URI uri, int requestBytes, long responseBytes) {
        String json = "{\"antwort\":" + responseBytes + "}";
        byte[] b = new byte[Math.max(requestBytes, json.length())];
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(j, 0, b, 0, j.length);
        for (int i = j.length; i < b.length; i++) {
            b[i] = ' ';
        }
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(b)).build();
    }

    /** Arbeitsspeicher des Prozesses: aktuell, Höchststand, privat (Windows) bzw. VmRSS/VmHWM (Linux). */
    private static String memory(long pid) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Process ps = new ProcessBuilder("powershell", "-NoProfile", "-Command", "$p = Get-Process -Id " + pid
                    + "; '{0} {1} {2}' -f $p.WorkingSet64, $p.PeakWorkingSet64, $p.PrivateMemorySize64")
                    .redirectErrorStream(true).start();
            String[] v = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim().split("\\s+");
            ps.waitFor(30, TimeUnit.SECONDS);
            return String.format(Locale.ROOT, "Arbeitsspeicher %.1f MiB, Höchststand %.1f MiB, privat %.1f MiB",
                    Long.parseLong(v[0]) / (double) MIB, Long.parseLong(v[1]) / (double) MIB,
                    Long.parseLong(v[2]) / (double) MIB);
        }
        String status = Files.readString(Path.of("/proc", String.valueOf(pid), "status"));
        Matcher rss = Pattern.compile("VmRSS:\\s+(\\d+)").matcher(status);
        Matcher hwm = Pattern.compile("VmHWM:\\s+(\\d+)").matcher(status);
        return (rss.find() ? "VmRSS " + Long.parseLong(rss.group(1)) / 1024 + " MiB" : "")
                + (hwm.find() ? ", VmHWM " + Long.parseLong(hwm.group(1)) / 1024 + " MiB" : "");
    }

    private static String jcmd(long pid, String... command) throws Exception {
        List<String> c = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(),
                String.valueOf(pid)));
        c.addAll(List.of(command));
        Process j = new ProcessBuilder(c).redirectErrorStream(true).start();
        String out = new String(j.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        j.waitFor(60, TimeUnit.SECONDS);
        return "--- jcmd " + String.join(" ", command) + "\n" + out.strip() + "\n";
    }
}
