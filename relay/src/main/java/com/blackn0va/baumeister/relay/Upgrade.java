package com.blackn0va.baumeister.relay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * WebSocket-Upgrade der App (RFC 6455, 4.2; docs/kanal-v1.md, Abschnitt 1): {@code GET /kanal/v1 HTTP/1.1} mit Host,
 * Upgrade: websocket, Connection: Upgrade, Sec-WebSocket-Key, Sec-WebSocket-Version: 13 und Sec-WebSocket-Protocol:
 * baumeister-kanal.v1. Kopf ≤ 8 KiB, kein Body. Antwort 101 mit Accept und demselben Protokoll; alles andere 400, 404
 * oder 426, danach ist die Verbindung zu.
 *
 * Gelesen wird erst nach dem TLS-Handshake mit gültigem Pin – ein Fremder kommt bis hierher gar nicht.
 *
 * Threads: zustandslos. Fehler: HttpFailure mit 400/404/426.
 */
final class Upgrade {

    static final String PATH = "/kanal/v1";
    static final String PROTOCOL = "baumeister-kanal.v1";
    static final int MAX_HEADER = 8 * 1024;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    /** 16 Zufallsbytes in Base64: 22 Zeichen, das letzte trägt nur 2 Bit (A, Q, g oder w), dann „==“. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9+/]{21}[AQgw]==");

    private Upgrade() {
    }

    /** Prüft den Kopf und liefert den Sec-WebSocket-Key. */
    static String check(HttpHead h) throws HttpFailure {
        if (!h.method().equals("GET") || !h.version().equals("HTTP/1.1")) {
            throw new HttpFailure(400, "upgrade_methode_oder_version");
        }
        if (!h.target().equals(PATH)) {
            throw new HttpFailure(404, "upgrade_pfad");
        }
        String host = h.header("host");
        if (host == null || host.isEmpty()) {
            throw new HttpFailure(400, "upgrade_ohne_host");
        }
        if (!"websocket".equalsIgnoreCase(h.header("upgrade"))
                || !HttpHead.listContains(h.header("connection"), "upgrade")) {
            throw new HttpFailure(400, "upgrade_köpfe");
        }
        if (!"13".equals(h.header("sec-websocket-version"))) {
            throw new HttpFailure(426, "upgrade_version");
        }
        String key = h.header("sec-websocket-key");
        if (key == null || !KEY.matcher(key).matches()) {
            throw new HttpFailure(400, "upgrade_key");
        }
        if (!HttpHead.listContains(h.header("sec-websocket-protocol"), PROTOCOL)) {
            throw new HttpFailure(400, "upgrade_protokoll");
        }
        String length = h.header("content-length");
        if ((length != null && !length.equals("0")) || h.header("transfer-encoding") != null) {
            throw new HttpFailure(400, "upgrade_mit_body");
        }
        return key;
    }

    /** Sec-WebSocket-Accept = Base64(SHA-1(Key + GUID)), RFC 6455, 1.3. */
    static String accept(String key) {
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sha1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 fehlt im JDK", e);
        }
    }

    static byte[] switching(String key) {
        String s = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept(key) + "\r\n"
                + "Sec-WebSocket-Protocol: " + PROTOCOL + "\r\n"
                + "\r\n";
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] refusal(int status) {
        return HttpStatus.empty(status, status == 426 ? "Sec-WebSocket-Version: 13" : null);
    }
}
