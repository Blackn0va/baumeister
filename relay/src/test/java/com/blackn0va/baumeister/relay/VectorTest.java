package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Alle Testvektoren aus docs/kanal-v1-testvektoren.json (Erzeuger tools/kanal/testvektoren.py) gegen den Code des
 * Relays. Die App-Seite (host/) prüft dieselbe Datei – so lesen und schreiben beide Seiten dieselben Bytes.
 *
 * Große Rahmen stehen nur als SHA-256 mit Erzeugungsregel in der Datei; der Test erzeugt sie nach der Regel neu und
 * vergleicht Länge und Hash, bevor er sie liest.
 */
class VectorTest {

    private static final HexFormat HEX = HexFormat.of();
    private static Json.Obj vectors;

    @BeforeAll
    static void load() throws Exception {
        Path file = Path.of("..", "docs", "kanal-v1-testvektoren.json");
        assertTrue(Files.isRegularFile(file), "Testvektoren fehlen: " + file.toAbsolutePath());
        vectors = (Json.Obj) Json.parse(Files.readString(file, StandardCharsets.UTF_8), 16);
        assertEquals("1", ((Json.Num) vectors.get("version")).text());
    }

    private static String str(Json.Obj o, String key) {
        return ((Json.Str) o.get(key)).value();
    }

    private static long num(Json.Obj o, String key) {
        return ((Json.Num) o.get(key)).exactLong().orElseThrow();
    }

    private static List<Json.Obj> list(String key) {
        List<Json.Obj> out = new ArrayList<>();
        for (Json.Value v : ((Json.Arr) vectors.get(key)).items()) {
            out.add((Json.Obj) v);
        }
        return out;
    }

    static String sha256(byte[] b) throws Exception {
        return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /** bytes(i % 251 for i in range(n)) – Regel der großen Vektoren. */
    private static byte[] rule(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 251);
        }
        return b;
    }

    private static boolean fromApp(Json.Obj v) {
        return str(v, "richtung").equals("app->relay");
    }

    /** Rahmen eines gültigen Vektors: aus rahmen_hex oder nach Regel neu gebaut (und gegen Hash und Länge geprüft). */
    private static byte[] validFrame(Json.Obj v) throws Exception {
        if (v.get("rahmen_hex") != null) {
            return HEX.parseHex(str(v, "rahmen_hex"));
        }
        byte[] data = rule((int) num(v, "daten_laenge"));
        String header = Json.object("t", str((Json.Obj) v.get("kopf"), "t"), "id", num((Json.Obj) v.get("kopf"), "id"));
        byte[] payload = payload(header, data);
        byte[] frame = fromApp(v) ? Frames.client(Frames.BINARY, payload, HEX.parseHex(str(v, "maske")))
                : Frames.server(Frames.BINARY, payload);
        assertEquals(num(v, "rahmen_laenge"), frame.length, str(v, "name"));
        assertEquals(str(v, "rahmen_sha256"), sha256(frame), str(v, "name"));
        return frame;
    }

    private static byte[] payload(String header, byte[] data) {
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[2 + h.length + data.length];
        p[0] = (byte) (h.length >> 8);
        p[1] = (byte) h.length;
        System.arraycopy(h, 0, p, 2, h.length);
        System.arraycopy(data, 0, p, 2 + h.length, data.length);
        return p;
    }

    private static Frames.Frame read(byte[] frame, boolean fromApp) throws Exception {
        return new Frames.Reader(new ByteArrayInputStream(frame), fromApp, Codec.MAX_PAYLOAD).read();
    }

    private static Codec.Direction direction(Json.Obj v) {
        return fromApp(v) ? Codec.Direction.APP_TO_RELAY : Codec.Direction.RELAY_TO_APP;
    }

    @Test
    void websocketAcceptMatchesTheRfc6455Example() {
        Json.Obj ws = (Json.Obj) vectors.get("websocket");
        assertEquals(str(ws, "accept"), Upgrade.accept(str(ws, "key")));
        assertEquals(str(ws, "protokoll"), Upgrade.PROTOCOL);
    }

    @Test
    void pinOfTheTestCertificateIsTheSha256OfItsDerBytes() {
        Json.Obj pin = (Json.Obj) vectors.get("pin");
        String pem = str(pin, "pem");
        StringBuilder b64 = new StringBuilder();
        for (String line : pem.split("\n")) {
            if (!line.startsWith("-----")) {
                b64.append(line.strip());
            }
        }
        byte[] der = Base64.getDecoder().decode(b64.toString());
        assertEquals(str(pin, "pin"), Pins.of(der));
        assertTrue(Pins.PIN.matcher(Pins.of(der)).matches());
        assertEquals("1ae3a080", Pins.brief(Pins.of(der)));
    }

    @Test
    void everyValidFrameDecodesToTheExpectedHeaderAndData() throws Exception {
        List<Json.Obj> valid = list("gueltig");
        assertEquals(13, valid.size(), "Anzahl gültiger Vektoren geändert – Test und Erzeuger abgleichen");
        for (Json.Obj v : valid) {
            String name = str(v, "name");
            Frames.Frame f = read(validFrame(v), fromApp(v));
            assertEquals(Frames.BINARY, f.opcode(), name);
            byte[] p = f.payload();
            int hl = ((p[0] & 0xFF) << 8) | (p[1] & 0xFF);
            Json.Value header = Json.parse(new String(p, 2, hl, StandardCharsets.UTF_8), 8);
            // Objekt-Gleichheit über Map.equals: Schlüsselreihenfolge zählt nicht (Abschnitt 3).
            assertEquals(v.get("kopf"), header, name);
            Message m = Codec.decode(p, direction(v));
            Message.Chunk chunk = switch (m) {
                case Message.Request r -> r.chunk();
                case Message.Response r -> r.chunk();
                case Message.Data d -> d.chunk();
                default -> Message.Chunk.EMPTY;
            };
            assertEquals(num(v, "daten_laenge"), chunk.length(), name);
            assertEquals(str(v, "daten_sha256"), sha256(chunk.copy()), name);
            if (v.get("daten_hex") != null) {
                assertEquals(str(v, "daten_hex"), HEX.formatHex(chunk.copy()), name);
            }
            assertEquals(str((Json.Obj) v.get("kopf"), "t"), type(m), name);
        }
    }

    private static String type(Message m) {
        return switch (m) {
            case Message.Hello h -> "hallo";
            case Message.Request r -> "anfrage";
            case Message.Response r -> "antwort";
            case Message.Data d -> "daten";
            case Message.Abort a -> "abbruch";
        };
    }

    /**
     * Gelesen und neu geschrieben ergibt jeder Vektor wieder genau seine Bytes – Relay-Rahmen unmaskiert, App-Rahmen mit
     * der Maske des Vektors. Einzige Ausnahme: der Vektor mit anderer Schlüsselreihenfolge (er prüft nur Leser; der
     * Schreiber hat eine feste Reihenfolge).
     */
    @Test
    void everyFrameIsWrittenBackByteForByte() throws Exception {
        List<String> differ = new ArrayList<>();
        for (Json.Obj v : list("gueltig")) {
            byte[] frame = validFrame(v);
            Message m = Codec.decode(read(frame, fromApp(v)).payload(), direction(v));
            byte[] payload = Codec.encode(m);
            byte[] again = fromApp(v) ? Frames.client(Frames.BINARY, payload, HEX.parseHex(str(v, "maske")))
                    : Frames.server(Frames.BINARY, payload);
            if (!java.util.Arrays.equals(frame, again)) {
                differ.add(str(v, "name"));
            }
        }
        assertEquals(List.of("Kopf mit Umlaut und anderer Schlüsselreihenfolge"), differ);
    }

    @Test
    void everyInvalidFrameIsRejectedWithTheNamedReason() throws Exception {
        List<Json.Obj> invalid = list("ungueltig");  // umlaut-ok: JSON-Schlüssel der Testvektoren
        // 23 seit der zweiten Sicherheitsprüfung (M1): „Status 1xx (Zwischenantwort)“.
        assertEquals(23, invalid.size(), "Anzahl ungültiger Vektoren geändert – Test und Erzeuger abgleichen");
        for (Json.Obj v : invalid) {
            String name = str(v, "name");
            byte[] frame;
            if (v.get("rahmen_hex") != null) {
                frame = HEX.parseHex(str(v, "rahmen_hex"));
            } else {
                // „Kopf {"t":"daten","id":1} kompakt + 65537 Nullbytes, Binärrahmen FIN, maskiert mit 37fa213d“
                assertTrue(str(v, "regel").contains("65537 Nullbytes"), name);
                frame = Frames.client(Frames.BINARY, payload("{\"t\":\"daten\",\"id\":1}", new byte[65_537]),
                        HEX.parseHex("37fa213d"));
                assertEquals(num(v, "rahmen_laenge"), frame.length, name);
                assertEquals(str(v, "rahmen_sha256"), sha256(frame), name);
            }
            String reason;
            try {
                Frames.Frame f = read(frame, fromApp(v));
                Codec.decode(f.payload(), direction(v));
                fail("nicht abgelehnt: " + name);
                return;
            } catch (ProtocolException e) {
                reason = e.reason().code();
            }
            assertEquals(str(v, "grund"), reason, name);
        }
    }

    @Test
    void pathListIsAllowedAndForbiddenExactlyAsListed() {
        Json.Obj paths = (Json.Obj) vectors.get("pfade");
        for (Json.Value p : ((Json.Arr) paths.get("erlaubt")).items()) {
            assertTrue(PathRules.allowed(((Json.Str) p).value()), ((Json.Str) p).value());
        }
        int forbidden = 0;
        for (Json.Value p : ((Json.Arr) paths.get("verboten")).items()) {
            assertFalse(PathRules.allowed(((Json.Str) p).value()), ((Json.Str) p).value());
            forbidden++;
        }
        // 20 seit 9393af9 (2026-11-08): fünf Abfragen mit %2e und kaputtem % dazu – so prüft die fertige App.
        // 23 seit der zweiten Sicherheitsprüfung (N6): kodierte Steuerzeichen %00, %1F, %7f.
        assertEquals(23, forbidden);
    }

    @Test
    void validVectorsKeepTheirMaskAndDirection() throws Exception {
        for (Json.Obj v : list("gueltig")) {
            byte[] frame = validFrame(v);
            boolean masked = (frame[1] & 0x80) != 0;
            assertEquals(fromApp(v), masked, str(v, "name"));
            if (masked) {
                assertArrayEquals(HEX.parseHex(str(v, "maske")),
                        java.util.Arrays.copyOfRange(frame, maskOffset(frame), maskOffset(frame) + 4), str(v, "name"));
            }
        }
    }

    private static int maskOffset(byte[] frame) {
        int len7 = frame[1] & 0x7F;
        return len7 < 126 ? 2 : len7 == 126 ? 4 : 10;
    }
}
