package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Nachrichten über die Testvektoren hinaus: Feldregeln je Typ und Richtung, Grenzen, unbekannte Felder, Rundlauf
 * Schreiben → Lesen, Zufallsköpfe. Die Gründe sind die aus docs/kanal-v1.md, Abschnitt 3, plus die Namen der Host-App
 * für das hallo (kennung_ungueltig, max_ungueltig, version_ungueltig – siehe Reason).  // umlaut-ok: Protokollcodes
 */
class CodecTest {

    private static final Codec.Direction APP = Codec.Direction.APP_TO_RELAY;
    private static final Codec.Direction RELAY = Codec.Direction.RELAY_TO_APP;

    static byte[] payload(String header, byte[] data) {
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[2 + h.length + data.length];
        p[0] = (byte) (h.length >> 8);
        p[1] = (byte) h.length;
        System.arraycopy(h, 0, p, 2, h.length);
        System.arraycopy(data, 0, p, 2 + h.length, data.length);
        return p;
    }

    private static String reason(String header, Codec.Direction d) {
        return reason(header, new byte[0], d);
    }

    private static String reason(String header, byte[] data, Codec.Direction d) {
        try {
            Codec.decode(payload(header, data), d);
        } catch (ProtocolException e) {
            return e.reason().code();
        }
        fail("nicht abgelehnt: " + header);
        return null;
    }

    private static Message ok(String header, Codec.Direction d) throws ProtocolException {
        return Codec.decode(payload(header, new byte[0]), d);
    }

    @Test
    void helloOfTheAppNeedsVersionIdAndMax() throws Exception {
        Message.Hello h = (Message.Hello) ok("{\"t\":\"hallo\",\"v\":1,\"app\":\"BaumeisterHost/1.2\",\"max\":6}", APP);
        assertEquals("BaumeisterHost/1.2", h.id());
        assertEquals(6, h.max());
        assertEquals("version_ungueltig", reason("{\"t\":\"hallo\",\"app\":\"a\",\"max\":1}", APP));  // umlaut-ok
        assertEquals("version_ungueltig", reason("{\"t\":\"hallo\",\"v\":2,\"app\":\"a\",\"max\":1}", APP));  // umlaut-ok
        assertEquals("version_ungueltig", reason("{\"t\":\"hallo\",\"v\":\"1\",\"app\":\"a\",\"max\":1}", APP));  // umlaut-ok
        assertEquals("version_ungueltig", reason("{\"t\":\"hallo\",\"v\":1.0,\"app\":\"a\",\"max\":1}", APP));  // umlaut-ok
        for (String bad : List.of("\"max\":0", "\"max\":7", "\"max\":\"6\"", "\"max\":6.0", "\"max\":null")) {
            assertEquals("max_ungueltig", reason("{\"t\":\"hallo\",\"v\":1,\"app\":\"a\"," + bad + "}", APP), bad);  // umlaut-ok
        }
        for (String bad : List.of("", "a".repeat(65), "a\\nb", "J\\u00fcrgen", "x" + (char) 0x7F)) {
            assertEquals("kennung_ungueltig", reason("{\"t\":\"hallo\",\"v\":1,\"app\":\"" + bad + "\",\"max\":1}", APP),  // umlaut-ok
                    bad);
        }
        assertEquals("kennung_ungueltig", reason("{\"t\":\"hallo\",\"v\":1,\"max\":1}", APP));  // umlaut-ok
        assertEquals("kennung_ungueltig", reason("{\"t\":\"hallo\",\"v\":1,\"relay\":\"r\",\"max\":1}", APP));  // umlaut-ok
        assertEquals("stueck_zu_gross", reason("{\"t\":\"hallo\",\"v\":1,\"app\":\"a\",\"max\":1}", new byte[1],  // umlaut-ok
                APP));
        assertEquals(64, ((Message.Hello) ok("{\"t\":\"hallo\",\"v\":1,\"app\":\"" + "a".repeat(64)
                + "\",\"max\":1}", APP)).id().length());
    }

    @Test
    void helloOfTheRelayCarriesItsIdAndNoMax() throws Exception {
        Message.Hello h = (Message.Hello) ok("{\"t\":\"hallo\",\"v\":1,\"relay\":\"baumeister-relay/0.1.0\"}", RELAY);
        assertEquals("baumeister-relay/0.1.0", h.id());
        assertEquals(0, h.max());
        assertEquals("kennung_ungueltig", reason("{\"t\":\"hallo\",\"v\":1,\"app\":\"a\",\"max\":1}", RELAY));  // umlaut-ok
    }

    /** anfrage geht nur Relay → App, antwort nur App → Relay; falsche Richtung ist ein unbekannter Typ. */
    @Test
    void typesAreBoundToTheirDirection() {
        assertEquals("typ_unbekannt", reason("{\"t\":\"anfrage\",\"id\":1,\"m\":\"GET\",\"p\":\"/health\",\"len\":0}",
                APP));
        assertEquals("typ_unbekannt", reason("{\"t\":\"antwort\",\"id\":1,\"s\":200,\"len\":0}", RELAY));
        assertEquals("typ_unbekannt", reason("{\"id\":1}", APP));
        assertEquals("typ_unbekannt", reason("{\"t\":1,\"id\":1}", APP));
        assertEquals("typ_unbekannt", reason("{\"t\":\"Abbruch\",\"id\":1}", APP));
    }

    @Test
    void idsAreIntegersFromOneToU32Max() throws Exception {
        assertEquals(4_294_967_295L, ((Message.Abort) ok("{\"t\":\"abbruch\",\"id\":4294967295}", APP)).id());
        assertEquals("id_fehlt", reason("{\"t\":\"daten\"}", APP));
        for (String bad : List.of("0", "-1", "4294967296", "1.0", "1e0", "\"1\"", "null", "true", "[1]")) {
            assertEquals("id_ungueltig", reason("{\"t\":\"abbruch\",\"id\":" + bad + "}", APP), bad);  // umlaut-ok
        }
    }

    /** 200…599 seit der zweiten Sicherheitsprüfung (M1): 1xx liest der HttpClient des Plugins als Teil der Antwort. */
    @Test
    void statusIsAnIntegerFrom200To599() throws Exception {
        assertEquals(200, ((Message.Response) ok("{\"t\":\"antwort\",\"id\":1,\"s\":200,\"len\":0}", APP)).status());
        assertEquals(599, ((Message.Response) ok("{\"t\":\"antwort\",\"id\":1,\"s\":599,\"len\":0}", APP)).status());
        for (String bad : List.of("99", "100", "102", "199", "600", "\"200\"", "200.0", "null")) {
            assertEquals("status_ungueltig", reason("{\"t\":\"antwort\",\"id\":1,\"s\":" + bad + ",\"len\":0}", APP),  // umlaut-ok
                    bad);
        }
        assertEquals("status_ungueltig", reason("{\"t\":\"antwort\",\"id\":1,\"len\":0}", APP));  // umlaut-ok
    }

    @Test
    void lengthsStayWithinTheirLimitsAndCoverTheFirstChunk() throws Exception {
        assertEquals(16L * 1024 * 1024, ((Message.Response) ok("{\"t\":\"antwort\",\"id\":1,\"s\":200,\"len\":"
                + 16L * 1024 * 1024 + "}", APP)).length());
        assertEquals("laenge_ungueltig", reason("{\"t\":\"antwort\",\"id\":1,\"s\":200,\"len\":"  // umlaut-ok
                + (16L * 1024 * 1024 + 1) + "}", APP));
        assertEquals("laenge_ungueltig", reason("{\"t\":\"antwort\",\"id\":1,\"s\":200}", APP));  // umlaut-ok
        assertEquals("laenge_ungueltig", reason("{\"t\":\"antwort\",\"id\":1,\"s\":200,\"len\":1}", new byte[2],  // umlaut-ok
                APP));
        assertEquals(2L * 1024 * 1024, ((Message.Request) ok("{\"t\":\"anfrage\",\"id\":1,\"m\":\"POST\",\"p\":"
                + "\"/health\",\"len\":" + 2L * 1024 * 1024 + "}", RELAY)).length());
        assertEquals("laenge_ungueltig", reason("{\"t\":\"anfrage\",\"id\":1,\"m\":\"POST\",\"p\":\"/health\","  // umlaut-ok
                + "\"len\":" + (2L * 1024 * 1024 + 1) + "}", RELAY));
        assertEquals(65_536, ((Message.Data) Codec.decode(payload("{\"t\":\"daten\",\"id\":1}", new byte[65_536]),
                APP)).chunk().length());
        assertEquals("stueck_zu_gross", reason("{\"t\":\"abbruch\",\"id\":1}", new byte[1], APP));  // umlaut-ok
    }

    @Test
    void contentTypeIsOptionalPrintableAsciiUpTo128() throws Exception {
        String base = "{\"t\":\"antwort\",\"id\":1,\"s\":200,\"len\":0";
        assertEquals(null, ((Message.Response) ok(base + "}", APP)).contentType());
        assertEquals("a".repeat(128), ((Message.Response) ok(base + ",\"ct\":\"" + "a".repeat(128) + "\"}", APP))
                .contentType());
        for (String bad : List.of("\"" + "a".repeat(129) + "\"", "\"\"", "null", "1", "\"text/pl\\u00e4in\"",
                "\"a\\tb\"", "\"a\\rb\"")) {
            assertEquals("typ_ungueltig", reason(base + ",\"ct\":" + bad + "}", APP), bad);  // umlaut-ok
        }
    }

    @Test
    void requestsNeedGetOrPostAndAnAllowedPath() {
        String tail = ",\"len\":0}";
        assertEquals("methode_ungueltig", reason("{\"t\":\"anfrage\",\"id\":1,\"m\":\"get\",\"p\":\"/health\"" + tail,  // umlaut-ok
                RELAY));
        assertEquals("methode_ungueltig", reason("{\"t\":\"anfrage\",\"id\":1,\"p\":\"/health\"" + tail, RELAY));  // umlaut-ok
        assertEquals("pfad_ungueltig", reason("{\"t\":\"anfrage\",\"id\":1,\"m\":\"GET\",\"p\":\"/v1/../x\"" + tail,  // umlaut-ok
                RELAY));
        assertEquals("pfad_ungueltig", reason("{\"t\":\"anfrage\",\"id\":1,\"m\":\"GET\",\"p\":7" + tail, RELAY));  // umlaut-ok
    }

    /** Unbekannte Felder werden übergangen (Abschnitt 3) – doppelte Schlüssel und tiefe Verschachtelung nicht. */
    @Test
    void unknownFieldsAreIgnoredButDuplicatesAndDeepNestingAreNot() throws Exception {
        assertEquals(1, ((Message.Abort) ok("{\"t\":\"abbruch\",\"id\":1,\"x\":{\"y\":[1,2,{\"z\":null}]}}", APP))
                .id());
        assertEquals("kopf_kein_objekt", reason("{\"t\":\"abbruch\",\"id\":1,\"id\":2}", APP));
        assertEquals("kopf_kein_objekt", reason("{\"t\":\"abbruch\",\"t\":\"daten\",\"id\":1}", APP));
        assertEquals("kopf_kein_objekt", reason("{\"t\":\"abbruch\",\"id\":1,\"x\":" + "[".repeat(8) + "]".repeat(8)
                + "}", APP));
    }

    @Test
    void headerFormIsChecked() {
        for (String bad : List.of("[]", "1", "\"x\"", "{", "{}x", "nul")) {
            assertEquals("kopf_kein_objekt", reason(bad, APP), bad);
        }
        assertThrows(ProtocolException.class, () -> Codec.decode(new byte[0], APP));
        assertThrows(ProtocolException.class, () -> Codec.decode(new byte[] {0}, APP));
        assertEquals("kopf_leer", reasonOf(new byte[] {0, 0}));
        assertEquals("kopf_abgeschnitten", reasonOf(new byte[] {0, 5, '{', '}'}));
        assertEquals("kopf_kein_utf8", reasonOf(new byte[] {0, 2, (byte) 0xC3, (byte) 0x28}));
        // Überlange UTF-8-Form von „{“ (0xC1 0xBB) ist kein gültiges UTF-8.
        assertEquals("kopf_kein_utf8", reasonOf(new byte[] {0, 3, (byte) 0xC1, (byte) 0xBB, '}'}));
    }

    private static String reasonOf(byte[] payload) {
        try {
            Codec.decode(payload, APP);
        } catch (ProtocolException e) {
            return e.reason().code();
        }
        return "angenommen";
    }

    @Test
    void everyMessageSurvivesEncodingAndDecoding() throws Exception {
        byte[] data = new byte[1000];
        new Random(1).nextBytes(data);
        Message.Request q = new Message.Request(4_294_967_295L, "POST", "/baumeister/v1/stadt?x=%C3%BC", "a/b; q=\"x\\y\"",
                5000, new Message.Chunk(data, 10, 900));
        Message.Request q2 = (Message.Request) Codec.decode(Codec.encode(q), RELAY);
        assertEquals(q.id(), q2.id());
        assertEquals(q.path(), q2.path());
        assertEquals(q.contentType(), q2.contentType());
        assertEquals(q.length(), q2.length());
        assertArrayEquals(q.chunk().copy(), q2.chunk().copy());

        Message.Response r = new Message.Response(9, 418, null, 0, Message.Chunk.EMPTY);
        Message.Response r2 = (Message.Response) Codec.decode(Codec.encode(r), APP);
        assertEquals(418, r2.status());
        assertEquals(null, r2.contentType());

        Message.Hello h = new Message.Hello(APP, "BaumeisterHost/x", 3);
        assertEquals(h, Codec.decode(Codec.encode(h), APP));
        Message.Hello rh = new Message.Hello(RELAY, "baumeister-relay/dev", 0);
        assertEquals(rh, Codec.decode(Codec.encode(rh), RELAY));
        assertEquals(new Message.Abort(5), Codec.decode(Codec.encode(new Message.Abort(5)), APP));
        assertThrows(IllegalArgumentException.class, () -> Codec.encode(new Message.Data(1,
                Message.Chunk.of(new byte[65_537]))));
    }

    /** Zufällige Köpfe aus gültigen und kaputten Bausteinen: nur ProtocolException, nie etwas anderes. */
    @Test
    void randomHeadersNeverThrowAnythingButProtocolException() {
        String[] keys = {"t", "id", "s", "m", "p", "ct", "len", "v", "app", "max", "x"};
        String[] values = {"\"hallo\"", "\"anfrage\"", "\"antwort\"", "\"daten\"", "\"abbruch\"", "1", "0", "-1",
            "4294967296", "200", "700", "\"GET\"", "\"/health\"", "\"/v1/../x\"", "null", "true", "1.5", "[]", "{}",
            "\"" + "a".repeat(200) + "\"", "\"text/plain\"", "6", "7", "2097153"};
        Random r = new Random(99);
        int accepted = 0;
        for (int i = 0; i < 30_000; i++) {
            StringBuilder b = new StringBuilder("{");
            int n = r.nextInt(7);
            for (int k = 0; k < n; k++) {
                b.append(k == 0 ? "" : ",").append('"').append(keys[r.nextInt(keys.length)]).append("\":")
                        .append(values[r.nextInt(values.length)]);
            }
            b.append('}');
            byte[] data = new byte[r.nextInt(4) == 0 ? r.nextInt(70_000) : r.nextInt(3)];
            try {
                Codec.decode(payload(b.toString(), data), r.nextBoolean() ? APP : RELAY);
                accepted++;
            } catch (ProtocolException e) {
                assertTrue(e.reason() != null);
            }
        }
        assertTrue(accepted > 0, "kein einziger Kopf angenommen – Bausteine prüfen");
    }
}
