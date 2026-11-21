package com.blackn0va.baumeister.relay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Ein echtes Relay für einen Test: Konfigurationsdatei im Temp-Ordner (wird mit RelayConfig gelesen, wie auf dev01),
 * Schlüssel aus TestKeys über credential:, beide Ports auf 127.0.0.1 und vom System gewählt – nie 8080/18080/25565/
 * 25580, auf diesem PC kann eine echte Host-App laufen. Die öffentliche Seite auf 127.0.0.1 statt 0.0.0.0, damit die
 * Windows-Firewall nicht nachfragt.
 */
final class RelayFixture implements AutoCloseable {

    final TestKeys keys;
    final Path dir;
    final Path config;
    final Path status;
    final Relay relay;
    final List<String> fatals = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> env;

    private RelayFixture(Limits limits, Map<String, String> pinsByName) throws Exception {
        keys = TestKeys.get();
        dir = Files.createTempDirectory("relay-test-");
        config = dir.resolve("relay.properties");
        status = dir.resolve("status");
        writeConfig(pinsByName);
        env = Map.of("CREDENTIALS_DIRECTORY", keys.dir.toString());
        RelayConfig c = RelayConfig.load(config, env);
        KeyMaterial k = KeyMaterial.load(c.keystore(), c.password());
        relay = new Relay(c, k, limits, new PinStore(c, () -> env), fatals::add);
        relay.start();
    }

    /** Relay mit den Apps „gpu-pc“ (Schlüssel app) und „laptop“ (Schlüssel app2). */
    static RelayFixture start(Limits limits) throws Exception {
        TestKeys k = TestKeys.get();
        Map<String, String> pins = new LinkedHashMap<>();
        pins.put("gpu-pc", k.pin("app"));
        pins.put("laptop", k.pin("app2"));
        return new RelayFixture(limits, pins);
    }

    static RelayFixture start(Limits limits, Map<String, String> pins) throws Exception {
        return new RelayFixture(limits, pins);
    }

    static Limits.Builder fast() {
        Limits.Builder b = new Limits.Builder();
        b.tick = Duration.ofMillis(50);
        b.pinReload = Duration.ofMillis(50);
        b.byteBudget = 48L * 1024 * 1024;
        return b;
    }

    /** Konfiguration (neu) schreiben – das Relay lädt die Pins beim nächsten Handshake bzw. Takt. */
    void writeConfig(Map<String, String> pinsByName) throws IOException {
        StringBuilder b = new StringBuilder("# Test-Relay\n")
                .append("lokal.adresse=127.0.0.1\nlokal.port=0\n")
                .append("öffentlich.adresse=127.0.0.1\nöffentlich.port=0\n")
                .append("schlüssel.datei=credential:relay.p12\nschlüssel.passwort=credential:relay.pass\n")
                .append("status.datei=").append(status.toString().replace('\\', '/')).append('\n');
        pinsByName.forEach((name, pin) -> b.append("erlaubt.").append(name).append('=').append(pin).append('\n'));
        Files.writeString(config, b, StandardCharsets.UTF_8);
    }

    int local() {
        return relay.localPort();
    }

    int external() {
        return relay.externalPort();
    }

    FakeApp.Identity identity(String alias) throws Exception {
        return new FakeApp.Identity(keys.key(alias), new X509Certificate[] {keys.cert(alias)}, keys.pin("relay"));
    }

    /**
     * App mit Schlüssel alias anmelden und warten, bis der Hub die NEUE Sitzung kennt (das hallo des Relays geht kurz
     * vor dem Eintrag beim Hub raus). Pin-Namen: app = gpu-pc, app2 = laptop.
     */
    FakeApp app(String alias, int max) throws Exception {
        String name = alias.equals("app") ? "gpu-pc" : alias.equals("app2") ? "laptop" : alias;
        java.util.Set<Session> before = new java.util.HashSet<>(relay.hub().sessions());
        FakeApp a = FakeApp.connect(identity(alias), external(), max);
        await(() -> relay.hub().sessions().stream().anyMatch(s -> s.app.equals(name) && !before.contains(s)),
                "App " + name + " angemeldet");
        return a;
    }

    String statusText() throws IOException {
        return Files.readString(status, StandardCharsets.UTF_8);
    }

    /** Für await: Statusdatei hat genau diesen Inhalt (fehlt sie gerade, nein). */
    boolean statusIs(String expected) {
        try {
            return statusText().equals(expected);
        } catch (IOException e) {
            return false;
        }
    }

    static void await(BooleanSupplier condition, String what) {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("nicht eingetreten: " + what);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("unterbrochen");
            }
        }
    }

    /** Ergebnis einer rohen Plugin-Anfrage: Antwortbytes bis EOF, oder reset = RST ohne jede Antwort. */
    record Reply(byte[] bytes, boolean reset) {

        String text() {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }

        int status() {
            String t = text();
            return t.startsWith("HTTP/1.1 ") ? Integer.parseInt(t.substring(9, 12)) : -1;
        }

        String header(String name) {
            for (String line : text().split("\r\n")) {
                if (line.isEmpty()) {
                    break;
                }
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).equalsIgnoreCase(name)) {
                    return line.substring(c + 1).strip();
                }
            }
            return null;
        }

        byte[] body() {
            String t = text();
            int i = t.indexOf("\r\n\r\n");
            return i < 0 ? new byte[0] : java.util.Arrays.copyOfRange(bytes, i + 4, bytes.length);
        }
    }

    /** Rohe Anfrage ans Plugin-Ende; liest bis EOF. */
    Reply plugin(byte[] request) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), local())) {
            s.setSoTimeout(15_000);
            s.getOutputStream().write(request);
            s.getOutputStream().flush();
            return readAll(s.getInputStream());
        }
    }

    static Reply readAll(InputStream in) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65_536];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                b.write(buf, 0, n);
            }
            return new Reply(b.toByteArray(), false);
        } catch (IOException e) {
            // RST (oder Abbruch) – zählt nur als „ohne Antwort“, wenn bis dahin nichts kam.
            return new Reply(b.toByteArray(), true);
        }
    }

    static byte[] get(int port, String path) {
        return ("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUser-Agent: Java-http-client/21\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] post(int port, String path, String contentType, byte[] body) {
        byte[] head = ("POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nContent-Type: " + contentType
                + "\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    @Override
    public void close() {
        relay.close();
    }

    /**
     * Schreibt die Logzeilen des Relays mit, solange sie offen ist. Die Gründe (frist, plugin_liest_nicht …) stehen
     * nur im Log – im abbruch von Kanal v1 gibt es kein Feld dafür. Vor der Fixture öffnen, sonst fehlen die ersten
     * Zeilen.
     */
    static final class LogCapture extends java.util.logging.Handler implements AutoCloseable {

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        LogCapture() {
            RelayLog.LOG.addHandler(this);
        }

        @Override
        public void publish(java.util.logging.LogRecord r) {
            lines.add(String.valueOf(r.getMessage()));
        }

        @Override
        public void flush() {
            // nichts zu leeren
        }

        @Override
        public void close() {
            RelayLog.LOG.removeHandler(this);
        }

        boolean has(String... parts) {
            synchronized (lines) {
                for (String l : lines) {
                    boolean all = true;
                    for (String p : parts) {
                        all &= l.contains(p);
                    }
                    if (all) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Wartet, bis eine Zeile alle Teile enthält. */
        void await(String... parts) {
            RelayFixture.await(() -> has(parts), "Logzeile mit " + String.join(" + ", parts) + " in " + lines);
        }
    }
}
