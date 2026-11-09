package com.blackn0va.baumeister.relay;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strenger Leser für einen HTTP/1.1-Anfragekopf (RFC 9112) – für das WebSocket-Upgrade der App und für die Anfragen
 * des Plugins. Eigener Leser statt com.sun.net.httpserver: Der ist großzügig (nimmt Faltung, doppelte Köpfe, nacktes
 * LF), und jede Großzügigkeit ist eine Stelle, an der zwei Leser dieselben Bytes verschieden verstehen
 * (Request-Smuggling, OWASP A04).
 *
 * Abgelehnt mit 400: nacktes CR oder LF, Leerzeile vor der Anfragezeile, Anfragezeile nicht genau „METHODE SP ZIEL SP
 * HTTP/x.y“, Zeichen außerhalb von sichtbarem ASCII, gefaltete Köpfe (obs-fold), Leerzeichen vor dem Doppelpunkt,
 * Namen außerhalb von tchar, Werte mit Steuerzeichen oder Bytes ≥ 0x80, jeder Kopf doppelt (auch erlaubte
 * Listenköpfe – kein Client des Relays schickt so etwas, und so gibt es keine Frage, welcher gilt).
 *
 * Threads: beliebig (zustandslos). Fehler: HttpFailure (4xx), EOFException wenn die Gegenseite vorher aufhört.
 */
record HttpHead(String method, String target, String version, Map<String, String> headers) {

    /** Mehr Köpfe schickt weder das Plugin (Java-HttpClient: 4–5) noch WinHTTP beim Upgrade (≈ 8). */
    static final int MAX_HEADERS = 64;

    /** Wert eines Kopfes, Name klein geschrieben; null wenn er fehlt. */
    String header(String lowerCaseName) {
        return headers.get(lowerCaseName);
    }

    /**
     * Liest bis zur Leerzeile, höchstens maxBytes samt Leerzeile. tooLarge: Status bei Überlauf (Upgrade 400 laut
     * Abschnitt 1 der Spezifikation, Loopback 431).
     */
    static HttpHead read(InputStream in, int maxBytes, int tooLarge) throws IOException, HttpFailure {
        byte[] buf = new byte[maxBytes];
        int n = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException(n == 0 ? "keine Anfrage" : "Kopf abgeschnitten");
            }
            if (n == maxBytes) {
                throw new HttpFailure(tooLarge, "kopf_zu_groß");
            }
            buf[n++] = (byte) b;
            if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n' && buf[n - 2] == '\r' && buf[n - 1] == '\n') {
                return parse(buf, n, tooLarge);
            }
        }
    }

    static HttpHead parse(byte[] buf, int n, int tooLarge) throws HttpFailure {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                throw new HttpFailure(400, "lf_ohne_cr");
            }
            if (buf[i] == '\r') {
                if (i + 1 >= n || buf[i + 1] != '\n') {
                    throw new HttpFailure(400, "cr_ohne_lf");
                }
                lines.add(new String(buf, start, i - start, StandardCharsets.ISO_8859_1));
                i++;
                start = i + 1;
            }
        }
        if (start != n || lines.size() < 2 || !lines.get(lines.size() - 1).isEmpty()) {
            throw new HttpFailure(400, "kopf_ohne_ende");
        }
        String requestLine = lines.get(0);
        int a = requestLine.indexOf(' ');
        int b = a < 0 ? -1 : requestLine.indexOf(' ', a + 1);
        if (a <= 0 || b <= a + 1 || b == requestLine.length() - 1 || requestLine.indexOf(' ', b + 1) >= 0) {
            throw new HttpFailure(400, "anfragezeile");
        }
        String method = requestLine.substring(0, a);
        String target = requestLine.substring(a + 1, b);
        String version = requestLine.substring(b + 1);
        if (method.length() > 16 || !method.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            throw new HttpFailure(400, "methode");
        }
        if (!target.chars().allMatch(c -> c >= 0x21 && c <= 0x7E)) {
            throw new HttpFailure(400, "ziel_zeichen");
        }
        if (!version.matches("HTTP/[0-9]\\.[0-9]")) {
            throw new HttpFailure(400, "version");
        }
        if (lines.size() - 2 > MAX_HEADERS) {
            throw new HttpFailure(tooLarge, "zu_viele_köpfe");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.size() - 1; i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new HttpFailure(400, "kopf_gefaltet");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpFailure(400, "kopf_ohne_doppelpunkt");
            }
            String name = line.substring(0, colon);
            if (!name.chars().allMatch(HttpHead::tchar)) {
                throw new HttpFailure(400, "kopf_name");
            }
            String value = trimOws(line.substring(colon + 1));
            if (!value.chars().allMatch(c -> c == '\t' || (c >= 0x20 && c <= 0x7E))) {
                throw new HttpFailure(400, "kopf_wert");
            }
            if (headers.putIfAbsent(name.toLowerCase(Locale.ROOT), value) != null) {
                throw new HttpFailure(400, "kopf_doppelt");
            }
        }
        return new HttpHead(method, target, version, Collections.unmodifiableMap(headers));
    }

    /** tchar nach RFC 9110, 5.6.2. */
    private static boolean tchar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    private static String trimOws(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && (s.charAt(from) == ' ' || s.charAt(from) == '\t')) {
            from++;
        }
        while (to > from && (s.charAt(to - 1) == ' ' || s.charAt(to - 1) == '\t')) {
            to--;
        }
        return s.substring(from, to);
    }

    /** Liste eines Kopfes (durch Komma getrennt, ohne Leerraum, klein geschrieben) enthält das Wort. */
    static boolean listContains(String value, String token) {
        if (value == null) {
            return false;
        }
        for (String part : value.split(",", -1)) {
            if (trimOws(part).equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }
}
