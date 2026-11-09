package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Modpaket vom Server (2026-11-08, Wunsch): nur mit gültigem Link, nur
 * die Dateien des Skripts, keine Pfade aus der Anfrage, Grenzen je Adresse. Gegen einen echten Socket auf 127.0.0.1.
 */
class ModDownloadsTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    Path dir;

    private ModDownloads d;
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @AfterEach
    void stop() {
        if (d != null) {
            d.stop();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    private ModDownloads started() throws IOException {
        Files.write(dir.resolve("Baumeisterland-26.2.mrpack"), "pack".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("Baumeisterland-26.2-mods.zip"), new byte[200_000]);
        Files.writeString(dir.resolve("Baumeisterland-26.2-mods.txt"), "liste");
        Files.writeString(dir.resolve("config.yml"), "geheim");
        d = new ModDownloads(new ModDownloads.Settings(true, "mein-server.ddns.net", freePort(), "127.0.0.1"), dir,
                now::get);
        assertTrue(d.start());
        return d;
    }

    /** Rohe Anfrage, Antwort komplett (der Server schließt nach jeder Antwort). */
    private String raw(String request) throws IOException {
        return new String(rawBytes(request), StandardCharsets.ISO_8859_1);
    }

    private byte[] rawBytes(String request) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), d.boundPort())) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            return s.getInputStream().readAllBytes();
        }
    }

    private static String token(String link) {
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private static String get(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: x\r\n\r\n";
    }

    @Test
    void offByDefaultAndChecksItsSettings() {
        assertFalse(ModDownloads.Settings.from(new MemoryConfiguration()).enabled());
        assertFalse(new ModDownloads(ModDownloads.Settings.from(null), dir).start());
        assertTrue(new ModDownloads.Settings(true, "", 25581, "0.0.0.0").problem().isPresent());
        assertTrue(new ModDownloads.Settings(true, "a b", 25581, "0.0.0.0").problem().isPresent());
        assertTrue(new ModDownloads.Settings(true, "x.de", 80, "0.0.0.0").problem().isPresent());
        assertTrue(new ModDownloads.Settings(true, "x.de", 25581, "0.0.0.0").problem().isEmpty());
        assertFalse(new ModDownloads(new ModDownloads.Settings(true, "", 25581, "127.0.0.1"), dir).start(),
                "ohne Adresse kein Server");
    }

    @Test
    void aValidLinkShowsThePageAndServesBothFilesExactly() throws Exception {
        started();
        String link = d.link(ALEX, "Alex").orElseThrow();
        assertTrue(link.startsWith("http://mein-server.ddns.net:" + d.boundPort() + "/m/"), link);
        String t = token(link);
        assertEquals(22, t.length());
        String page = raw(get("/m/" + t));
        assertTrue(page.startsWith("HTTP/1.1 200 "), page);
        assertTrue(page.contains("/m/" + t + "/Baumeisterland-26.2.mrpack"), page);
        assertTrue(page.contains("/m/" + t + "/Baumeisterland-26.2-mods.zip"), page);
        assertFalse(page.contains("mods.txt") || page.contains("config.yml"), "nur .mrpack und ZIP");
        assertTrue(page.contains("Content-Security-Policy: default-src 'none'"));
        byte[] zip = rawBytes(get("/m/" + t + "/Baumeisterland-26.2-mods.zip"));
        String head = new String(zip, 0, 400, StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith("HTTP/1.1 200 ") && head.contains("Content-Length: 200000"), head);
        int body = head.indexOf("\r\n\r\n") + 4;
        assertEquals(200_000, zip.length - body);
        String headOnly = raw("HEAD /m/" + t + "/Baumeisterland-26.2.mrpack HTTP/1.1\r\n\r\n");
        assertTrue(headOnly.contains("Content-Length: 4") && headOnly.endsWith("\r\n\r\n"), "HEAD ohne Körper");
    }

    @Test
    void unknownExpiredAndReplacedLinksAreAllTheSame404() throws Exception {
        started();
        String first = token(d.link(ALEX, "Alex").orElseThrow());
        String second = token(d.link(ALEX, "Alex").orElseThrow());
        assertNotEquals(first, second);
        assertTrue(raw(get("/m/" + first)).startsWith("HTTP/1.1 404 "), "alter Link desselben Spielers gilt nicht mehr");
        assertTrue(raw(get("/m/" + second)).startsWith("HTTP/1.1 200 "));
        assertTrue(raw(get("/m/AAAAAAAAAAAAAAAAAAAAAA")).startsWith("HTTP/1.1 404 "));
        now.addAndGet(ModDownloads.TTL_MILLIS);
        assertTrue(raw(get("/m/" + second)).startsWith("HTTP/1.1 404 "), "nach 24 h abgelaufen");
    }

    @Test
    void noPathsFromTheRequestAndOnlyGetOrHead() throws Exception {
        started();
        String t = token(d.link(ALEX, "Alex").orElseThrow());
        for (String path : new String[] {"/m/" + t + "/config.yml", "/m/" + t + "/Baumeisterland-26.2-mods.txt",
                "/m/" + t + "/../config.yml", "/m/" + t + "/%2e%2e%2fconfig.yml", "/m/" + t + "/a/b", "/",
                "/m/" + t + "/Baumeisterland-26.1.mrpack"}) {
            String r = raw(get(path));
            assertTrue(r.startsWith("HTTP/1.1 404 "), path + " → " + r.lines().findFirst().orElse(""));
            assertFalse(r.contains("geheim"), path);
        }
        assertTrue(raw("POST /m/" + t + " HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 405 "));
        assertTrue(raw("GET /m/" + t + " HTTP/1.1\r\nX: " + "a".repeat(9000) + "\r\n\r\n").startsWith("HTTP/1.1 400 "),
                "Kopf über 8 KiB");
    }

    @Test
    void atMostTwoConnectionsPerAddress() throws Exception {
        started();
        try (Socket a = new Socket(InetAddress.getLoopbackAddress(), d.boundPort());
                Socket b = new Socket(InetAddress.getLoopbackAddress(), d.boundPort())) {
            Thread.sleep(200);
            try (Socket c = new Socket(InetAddress.getLoopbackAddress(), d.boundPort())) {
                c.setSoTimeout(3000);
                InputStream in = c.getInputStream();
                assertEquals(-1, in.read(), "dritte Verbindung derselben Adresse wird sofort geschlossen");
            }
            assertTrue(a.isConnected() && b.isConnected());
        }
        Thread.sleep(300);
        String t = token(d.link(ALEX, "Alex").orElseThrow());
        assertTrue(raw(get("/m/" + t)).startsWith("HTTP/1.1 200 "), "danach wieder frei");
    }

    /**
     * HTTPS (2026-11-08): ohne Zertifikat http, dann PEM-Dateien wie von certbot ablegen → nach der Prüfung der Uhr
     * läuft derselbe Port mit TLS, die Links beginnen mit https und die schöne Seite kommt über TLS.
     */
    @Test
    void certificateAppearsAndThePortSwitchesToHttps() throws Exception {
        Files.write(dir.resolve("Baumeisterland-26.2.mrpack"), "pack".getBytes(StandardCharsets.UTF_8));
        Path pem = Files.createDirectories(dir.resolve("pem"));
        Path chain = pem.resolve("fullchain.pem");
        Path key = pem.resolve("privkey.pem");
        d = new ModDownloads(new ModDownloads.Settings(true, "localhost", freePort(), "127.0.0.1", chain.toString(),
                key.toString()), dir, System::currentTimeMillis);
        assertTrue(d.start());
        assertTrue(d.link(ALEX, "Alex").orElseThrow().startsWith("http://"), "noch kein Zertifikat");

        java.security.cert.X509Certificate cert = writePem(pem, chain, key);
        d.watchCertificate();
        String link = d.link(ALEX, "Alex").orElseThrow();
        assertTrue(link.startsWith("https://localhost:" + d.boundPort() + "/m/"), link);

        java.security.KeyStore trust = java.security.KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", cert);
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) ctx.getSocketFactory()
                .createSocket(InetAddress.getLoopbackAddress(), d.boundPort())) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(get("/m/" + token(link)).getBytes(StandardCharsets.ISO_8859_1));
            String page = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(page.startsWith("HTTP/1.1 200 "), page);
            assertTrue(page.contains("Baumeister<span>land</span>") && page.contains("Prism Launcher"), page);
            assertTrue(page.contains("<code>localhost</code>"), "Serveradresse zum Verbinden");
            assertTrue(s.getSession().getProtocol().startsWith("TLSv1."), s.getSession().getProtocol());
        }
    }

    /** Schlüssel und selbst signiertes Zertifikat mit keytool, als PEM wie certbot (PKCS#8, Kette). */
    private static java.security.cert.X509Certificate writePem(Path pem, Path chain, Path key) throws Exception {
        Path p12 = pem.resolve("k.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        Process pr = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "k", "-keyalg", "EC", "-groupname",
                "secp256r1", "-dname", "CN=localhost", "-validity", "2", "-keystore", p12.toString(), "-storetype",
                "PKCS12", "-storepass", "testtest", "-keypass", "testtest").redirectErrorStream(true).start();
        pr.getInputStream().readAllBytes();
        assertEquals(0, pr.waitFor(), "keytool");
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            ks.load(in, "testtest".toCharArray());
        }
        java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) ks.getCertificate("k");
        java.util.Base64.Encoder b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        Files.writeString(chain, "-----BEGIN CERTIFICATE-----\n" + b64.encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n");
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" + b64.encodeToString(
                ks.getKey("k", "testtest".toCharArray()).getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        return cert;
    }

    @Test
    void pemKeysInTheOldFormatAreRefusedWithAReason() {
        String old = "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n";
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> PemTls.privateKey(old, "EC"));
        assertTrue(e.getMessage().contains("PKCS#8"), e.getMessage());
    }

    @Test
    void pageEscapesNamesAndListsTheMods() {
        String page = ModDownloads.page("AAAAAAAAAAAAAAAAAAAAAA", new ModDownloads.Grant(ALEX, "Alex<script>", 0),
                java.util.List.of(new ModDownloads.Offer("Baumeisterland-26.2.mrpack", dir, 1024)),
                java.util.List.of("sodium-fabric-0.9.2+mc26.2", "<b>x</b>"), "mein.server.de", "26.2");
        assertFalse(page.contains("<script>") || page.contains("<b>x</b>"), "escaped");
        assertTrue(page.contains("&lt;b&gt;x&lt;/b&gt;") && page.contains("sodium-fabric"));
        assertTrue(page.contains("Minecraft 26.2") && page.contains("mein.server.de"));
    }

    @Test
    void newestVersionOfEachKindIsOffered() throws Exception {
        for (String n : new String[] {"Baumeisterland-26.9.mrpack", "Baumeisterland-26.10.mrpack",
                "Baumeisterland-26.2-mods.zip", "Baumeisterland-1.21.11-mods.zip", "fremd.mrpack"}) {
            Files.writeString(dir.resolve(n), n);
        }
        ModDownloads m = new ModDownloads(ModDownloads.Settings.from(null), dir);
        assertEquals(java.util.List.of("Baumeisterland-26.10.mrpack", "Baumeisterland-26.2-mods.zip"),
                m.offers().stream().map(ModDownloads.Offer::name).toList());
        assertTrue(m.link(ALEX, "Alex").isEmpty(), "ohne laufenden Server kein Link");
    }
}
