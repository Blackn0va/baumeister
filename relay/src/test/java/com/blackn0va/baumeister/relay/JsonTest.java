package com.blackn0va.baumeister.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Der eigene JSON-Leser: gültige Texte, jede Art Fehler, die Tiefengrenze, doppelte Schlüssel und Zufallseingaben.
 * Der Schreiber muss Byte für Byte wie Pythons json.dumps(..., ensure_ascii=False) schreiben (Testvektoren).
 */
class JsonTest {

    private static Json.Value parse(String s) throws Json.JsonException {
        return Json.parse(s, 8);
    }

    @Test
    void parsesObjectsArraysStringsNumbersAndLiterals() throws Exception {
        Json.Obj o = (Json.Obj) parse(" {\"a\" : [1, -2, 3.5e-1, true, false, null], \"b\":{\"c\":\"d\"}} ");
        Json.Arr a = (Json.Arr) o.get("a");
        assertEquals(new Json.Num("1"), a.items().get(0));
        assertEquals(new Json.Num("-2"), a.items().get(1));
        assertEquals(new Json.Num("3.5e-1"), a.items().get(2));
        assertEquals(new Json.Bool(true), a.items().get(3));
        assertEquals(new Json.Bool(false), a.items().get(4));
        assertEquals(new Json.Null(), a.items().get(5));
        assertEquals(new Json.Str("d"), ((Json.Obj) o.get("b")).get("c"));
        assertEquals(List.of("a", "b"), List.copyOf(o.fields().keySet()));
    }

    @Test
    void stringEscapesAreDecoded() throws Exception {
        Json.Str s = (Json.Str) parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u00fc\\ud83d\\ude00\"");
        assertEquals("a\"b\\c/d\b\f\n\r\t" + (char) 0xFC + new String(Character.toChars(0x1F600)), s.value());
    }

    @Test
    void objectEqualityIgnoresKeyOrder() throws Exception {
        assertEquals(parse("{\"a\":1,\"b\":2}"), parse("{\"b\":2,\"a\":1}"));
    }

    /** {"id":1,"id":2} hieße für zwei Leser zwei verschiedene Aufträge – deshalb Fehler statt „letzter gewinnt“. */
    @Test
    void duplicateKeysAreRejectedAlsoWhenWrittenWithAnEscape() {
        assertThrows(Json.JsonException.class, () -> parse("{\"id\":1,\"id\":2}"));
        assertThrows(Json.JsonException.class, () -> parse("{\"id\":1,\"\\u0069d\":2}"));
        assertThrows(Json.JsonException.class, () -> parse("{\"x\":{\"a\":1,\"a\":1}}"));
    }

    @Test
    void nestingDeeperThanTheLimitIsRejectedWithoutStackOverflow() throws Exception {
        assertEquals(new Json.Arr(List.of()), Json.parse("[]", 8));
        Json.parse("[".repeat(8) + "]".repeat(8), 8);
        assertThrows(Json.JsonException.class, () -> Json.parse("[".repeat(9) + "]".repeat(9), 8));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":".repeat(9) + "1" + "}".repeat(9), 8));
        // 4 KiB „[“: so tief wie ein Kopf werden kann – Ablehnung, kein StackOverflowError.
        assertThrows(Json.JsonException.class, () -> Json.parse("[".repeat(4096), 8));
        assertThrows(Json.JsonException.class, () -> Json.parse("[".repeat(100_000) + "]".repeat(100_000), 8));
    }

    @Test
    void malformedTextIsRejected() {
        for (String bad : List.of("", " ", "{", "}", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[,1]", "{,}",
                "{a:1}", "{'a':1}", "[01]", "[+1]", "[.5]", "[1.]", "[1e]", "[1e+]", "[-]", "[NaN]", "[Infinity]",
                "[tru]", "[nul]", "[True]", "\"abc", "\"a\\x\"", "\"a\\u12\"", "\"a\\u12G4\"", "[1] [2]", "[1]x",
                "[\"a\tb\"]", "[\"a\nb\"]", "/* */[1]", "[1]//", "\"" + (char) 0xFEFF + "\"x", (char) 0xFEFF + "[1]",
                "{\"a\":1 \"b\":2}", "[1 2]", "\"\\u" + (char) 0x0661 + "000\"")) {
            assertThrows(Json.JsonException.class, () -> parse(bad), bad);
        }
    }

    @Test
    void surrogatesMustBePaired() throws Exception {
        assertThrows(Json.JsonException.class, () -> parse("\"\\ud83d\""));
        assertThrows(Json.JsonException.class, () -> parse("\"\\ude00\""));
        assertThrows(Json.JsonException.class, () -> parse("\"\\ud83d\\u0041\""));
        assertThrows(Json.JsonException.class, () -> parse("\"" + (char) 0xD83D + "\""));
        assertThrows(Json.JsonException.class, () -> parse("\"" + (char) 0xDE00 + "\""));
        assertEquals(new Json.Str(new String(Character.toChars(0x1F600))),
                parse("\"" + new String(Character.toChars(0x1F600)) + "\""));
    }

    @Test
    void exactLongReadsOnlyPlainIntegers() {
        assertEquals(OptionalLong.of(4_294_967_295L), new Json.Num("4294967295").exactLong());
        assertEquals(OptionalLong.of(-1), new Json.Num("-1").exactLong());
        assertEquals(OptionalLong.of(0), new Json.Num("0").exactLong());
        assertEquals(OptionalLong.of(Long.MAX_VALUE), new Json.Num("9223372036854775807").exactLong());
        for (String no : List.of("1.0", "1e3", "1E0", "-0", "9223372036854775808", "99999999999999999999", "-")) {
            assertTrue(new Json.Num(no).exactLong().isEmpty(), no);
        }
    }

    @Test
    void numbersLongerThanTheLimitAreRejected() {
        assertThrows(Json.JsonException.class, () -> parse("[" + "1".repeat(Json.MAX_NUMBER_TEXT + 1) + "]"));
    }

    /** Wie Pythons json.dumps(..., ensure_ascii=False): Steuerzeichen als \\u00xx klein, Unicode roh, „/“ roh. */
    @Test
    void writerEscapesLikePythonJsonDumps() {
        String input = "a\"b\\c\n\r\t\b\f" + (char) 1 + (char) 0x1F + "ü" + (char) 0x2028 + (char) 0x7F + "/";
        StringBuilder b = new StringBuilder();
        Json.quote(b, input);
        assertEquals("\"a\\\"b\\\\c\\n\\r\\t\\b\\f\\u0001\\u001fü" + (char) 0x2028 + (char) 0x7F + "/\"",
                b.toString());
        assertEquals("{\"t\":\"daten\",\"id\":3}", Json.object("t", "daten", "id", 3L));
        assertThrows(IllegalArgumentException.class, () -> Json.object("t", 1.5));
        assertThrows(IllegalArgumentException.class, () -> Json.object("t"));
    }

    @Test
    void writtenObjectsParseBackToTheSameValues() throws Exception {
        String text = Json.object("s", "x\"y\\z" + (char) 2 + "ä", "n", 4_294_967_295L, "i", -7);
        Json.Obj o = (Json.Obj) parse(text);
        assertEquals(new Json.Obj(Map.of("s", new Json.Str("x\"y\\z" + (char) 2 + "ä"), "n",
                new Json.Num("4294967295"), "i", new Json.Num("-7"))), o);
    }

    /** Zufall aus JSON-Bausteinen: Der Leser liefert einen Wert oder JsonException – nie etwas anderes. */
    @Test
    void randomInputNeverThrowsAnythingButJsonException() {
        String[] parts = {"{", "}", "[", "]", ",", ":", "\"", "\\", "\\u", "d83d", "0", "1", "-", ".", "e", "+",
            "true", "false", "null", " ", "\n", "a", "\"t\"", "\"id\"", "ü", String.valueOf((char) 0xD800),
            String.valueOf((char) 1), "9999999999999999999999"};
        Random r = new Random(20_261_108);
        int values = 0;
        for (int i = 0; i < 50_000; i++) {
            StringBuilder b = new StringBuilder();
            int n = r.nextInt(30);
            for (int k = 0; k < n; k++) {
                b.append(parts[r.nextInt(parts.length)]);
            }
            try {
                Json.parse(b.toString(), 8);
                values++;
            } catch (Json.JsonException e) {
                // erwartet für die meisten Eingaben
                assertTrue(e.getMessage() != null);
            }
        }
        assertTrue(values > 100, "kaum gültige Eingaben erzeugt (" + values + ") – Bausteine prüfen");
    }

    @Test
    void mutatedHeadersNeverThrowAnythingButJsonException() {
        String[] seeds = {"{\"t\":\"antwort\",\"id\":1,\"s\":200,\"ct\":\"application/json\",\"len\":2}",
            "{\"t\":\"hallo\",\"v\":1,\"app\":\"BaumeisterHost/test\",\"max\":6}",
            "{\"len\":0,\"p\":\"/baumeister/v1/begleiter/gehoert?imspiel=J%C3%BCrgen\",\"m\":\"GET\",\"id\":5}"};  // umlaut-ok
        Random r = new Random(7);
        for (int i = 0; i < 50_000; i++) {
            StringBuilder b = new StringBuilder(seeds[r.nextInt(seeds.length)]);
            for (int k = r.nextInt(4); k >= 0; k--) {
                int at = r.nextInt(b.length() + 1);
                switch (r.nextInt(3)) {
                    case 0 -> b.insert(at, (char) r.nextInt(0x80));
                    case 1 -> {
                        if (at < b.length()) {
                            b.deleteCharAt(at);
                        }
                    }
                    default -> {
                        if (at < b.length()) {
                            String alphabet = "{}[],:\"\\0-e.";
                            b.setCharAt(at, alphabet.charAt(r.nextInt(alphabet.length())));
                        }
                    }
                }
            }
            try {
                Json.parse(b.toString(), 8);
            } catch (Json.JsonException e) {
                assertTrue(e.getMessage() != null);
            }
        }
    }
}
