package com.blackn0va.baumeister.relay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Strenger, kleiner JSON-Leser und -Schreiber (RFC 8259) für die Kanal-Köpfe. Das JDK bringt keinen mit, und eine
 * Bibliothek wäre die erste Laufzeitabhängigkeit des Relays – auf einem Port, der im Internet steht.
 *
 * Bewusst strenger als RFC 8259:
 * - Doppelte Schlüssel sind ein Fehler. RFC 8259 sagt nur „SHOULD be unique“, und Parser lösen das verschieden auf
 *   (erster oder letzter gewinnt). Läse die App {"id":1,"id":2} anders als das Relay, landete eine Antwort beim
 *   falschen Auftrag. Ablehnen ist eindeutig (Parser-Differenz, OWASP A08).
 * - Verschachtelung höchstens maxDepth: Ein Kopf von 4 KiB aus lauter „[“ wären sonst 2048 Rekursionsebenen – mit
 *   -Xss256k ein StackOverflowError im Leser-Thread statt einer sauberen Ablehnung.
 * - Zahlen bleiben Text. exactLong() liest nur ganze Zahlen ohne Rundung; 1.0, 1e3 und -0 sind keine.
 * - Einzelne Surrogate (roh oder als \\u-Escape) sind ein Fehler: Der Kopf muss gültiges Unicode sein.
 *
 * Der Schreiber erzeugt dieselben Bytes wie Pythons json.dumps(..., ensure_ascii=False, separators=(',', ':')) – so
 * entstehen die Testvektoren (tools/kanal/testvektoren.py), und der Test vergleicht Byte für Byte.
 *
 * Threads: zustandslos, beliebig. Fehler: JsonException mit deutschem Text, nie mit dem Eingabetext.
 */
final class Json {

    /** Längste Zahl, die der Leser annimmt. u32 hat 10 Stellen; mehr als 40 ist nur Last für den Leser. */
    static final int MAX_NUMBER_TEXT = 40;

    private Json() {
    }

    sealed interface Value permits Obj, Arr, Str, Num, Bool, Null {
    }

    /** Objekt in Schreibreihenfolge der Quelle; nicht veränderbar. */
    record Obj(Map<String, Value> fields) implements Value {

        Obj {
            fields = Collections.unmodifiableMap(fields);
        }

        Value get(String key) {
            return fields.get(key);
        }
    }

    record Arr(List<Value> items) implements Value {

        Arr {
            items = Collections.unmodifiableList(items);
        }
    }

    record Str(String value) implements Value {
    }

    /** Zahl als geprüfter JSON-Text. */
    record Num(String text) implements Value {

        /** Ganze Zahl ohne Bruch und Exponent, die in long passt; sonst leer. „-0“ erzeugt kein Schreiber – leer. */
        OptionalLong exactLong() {
            String t = text;
            int start = t.startsWith("-") ? 1 : 0;
            if (t.length() == start || t.length() - start > 19) {
                return OptionalLong.empty();
            }
            for (int i = start; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c < '0' || c > '9') {
                    return OptionalLong.empty();
                }
            }
            if (t.equals("-0")) {
                return OptionalLong.empty();
            }
            try {
                return OptionalLong.of(Long.parseLong(t));
            } catch (NumberFormatException e) {
                // 19 Stellen über Long.MAX_VALUE: keine darstellbare ganze Zahl – für den Aufrufer „ungültig“.
                return OptionalLong.empty();
            }
        }
    }

    record Bool(boolean value) implements Value {
    }

    record Null() implements Value {
    }

    /** Ungültiger JSON-Text. Ohne Stack-Trace: kommt aus fremden Bytes. */
    static final class JsonException extends Exception {

        private static final long serialVersionUID = 1L;

        JsonException(String message) {
            super(message, null, false, false);
        }
    }

    /** Ganzer Text ist genau ein JSON-Wert (Leerraum davor und danach erlaubt). */
    static Value parse(String text, int maxDepth) throws JsonException {
        Parser p = new Parser(text, maxDepth);
        p.skipWhitespace();
        Value v = p.value(0);
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw new JsonException("Text nach dem JSON-Wert");
        }
        return v;
    }

    private static final class Parser {

        private final String s;
        private final int maxDepth;
        private int pos;

        Parser(String s, int maxDepth) {
            this.s = s;
            this.maxDepth = maxDepth;
        }

        Value value(int depth) throws JsonException {
            if (pos >= s.length()) {
                throw new JsonException("Ende statt JSON-Wert");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> new Str(string());
                case 't' -> literal("true", new Bool(true));
                case 'f' -> literal("false", new Bool(false));
                case 'n' -> literal("null", new Null());
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new JsonException("unerwartetes Zeichen an Stelle " + pos);
                }
            };
        }

        private Obj object(int depth) throws JsonException {
            if (depth > maxDepth) {
                throw new JsonException("zu tief verschachtelt");
            }
            pos++;
            Map<String, Value> fields = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return new Obj(fields);
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw new JsonException("Schlüssel erwartet an Stelle " + pos);
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Value v = value(depth);
                if (fields.putIfAbsent(key, v) != null) {
                    throw new JsonException("doppelter Schlüssel");
                }
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                expect('}');
                return new Obj(fields);
            }
        }

        private Arr array(int depth) throws JsonException {
            if (depth > maxDepth) {
                throw new JsonException("zu tief verschachtelt");
            }
            pos++;
            List<Value> items = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return new Arr(items);
            }
            while (true) {
                skipWhitespace();
                items.add(value(depth));
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                    continue;
                }
                expect(']');
                return new Arr(items);
            }
        }

        private String string() throws JsonException {
            pos++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new JsonException("String ohne Ende");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    escape(b);
                    continue;
                }
                if (c < 0x20) {
                    throw new JsonException("Steuerzeichen im String");
                }
                if (Character.isHighSurrogate(c)) {
                    if (pos >= s.length() || !Character.isLowSurrogate(s.charAt(pos))) {
                        throw new JsonException("einzelnes Surrogat");
                    }
                    b.append(c).append(s.charAt(pos++));
                    continue;
                }
                if (Character.isLowSurrogate(c)) {
                    throw new JsonException("einzelnes Surrogat");
                }
                b.append(c);
            }
        }

        private void escape(StringBuilder b) throws JsonException {
            if (pos >= s.length()) {
                throw new JsonException("Escape ohne Zeichen");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"' -> b.append('"');
                case '\\' -> b.append('\\');
                case '/' -> b.append('/');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    char c = hex4();
                    if (Character.isHighSurrogate(c)) {
                        if (pos + 1 >= s.length() || s.charAt(pos) != '\\' || s.charAt(pos + 1) != 'u') {
                            throw new JsonException("einzelnes Surrogat");
                        }
                        pos += 2;
                        char low = hex4();
                        if (!Character.isLowSurrogate(low)) {
                            throw new JsonException("einzelnes Surrogat");
                        }
                        b.append(c).append(low);
                    } else if (Character.isLowSurrogate(c)) {
                        throw new JsonException("einzelnes Surrogat");
                    } else {
                        b.append(c);
                    }
                }
                default -> throw new JsonException("unbekanntes Escape");
            }
        }

        private char hex4() throws JsonException {
            if (pos + 4 > s.length()) {
                throw new JsonException("\\u ohne vier Hex-Ziffern");
            }
            int v = 0;
            for (int i = 0; i < 4; i++) {
                // Von Hand statt Character.digit: Das nimmt auch Ziffern anderer Schriften (z. B. U+0660), JSON nur ASCII.
                char h = s.charAt(pos + i);
                int d;
                if (h >= '0' && h <= '9') {
                    d = h - '0';
                } else if (h >= 'a' && h <= 'f') {
                    d = h - 'a' + 10;
                } else if (h >= 'A' && h <= 'F') {
                    d = h - 'A' + 10;
                } else {
                    throw new JsonException("\\u ohne vier Hex-Ziffern");
                }
                v = v * 16 + d;
            }
            pos += 4;
            return (char) v;
        }

        private Num number() throws JsonException {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            if (peek('0')) {
                pos++;
            } else if (digitAt(pos) && s.charAt(pos) != '0') {
                while (digitAt(pos)) {
                    pos++;
                }
            } else {
                throw new JsonException("Zahl ohne Ziffer");
            }
            if (peek('.')) {
                pos++;
                if (!digitAt(pos)) {
                    throw new JsonException("Bruch ohne Ziffer");
                }
                while (digitAt(pos)) {
                    pos++;
                }
            }
            if (peek('e') || peek('E')) {
                pos++;
                if (peek('+') || peek('-')) {
                    pos++;
                }
                if (!digitAt(pos)) {
                    throw new JsonException("Exponent ohne Ziffer");
                }
                while (digitAt(pos)) {
                    pos++;
                }
            }
            if (pos - start > MAX_NUMBER_TEXT) {
                throw new JsonException("Zahl zu lang");
            }
            return new Num(s.substring(start, pos));
        }

        private boolean digitAt(int i) {
            return i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9';
        }

        private Value literal(String word, Value v) throws JsonException {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("unbekanntes Wort an Stelle " + pos);
            }
            pos += word.length();
            return v;
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) throws JsonException {
            if (!peek(c)) {
                throw new JsonException("„" + c + "“ erwartet an Stelle " + pos);
            }
            pos++;
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }
    }

    /**
     * Kompaktes Objekt aus Schlüssel/Wert-Paaren in genau dieser Reihenfolge. Werte: String, Long oder Integer – mehr
     * braucht kein Kanal-Kopf. Ein anderer Typ ist ein Programmierfehler.
     */
    static String object(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Schlüssel ohne Wert");
        }
        StringBuilder b = new StringBuilder(96).append('{');
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                b.append(',');
            }
            quote(b, (String) keyValues[i]);
            b.append(':');
            switch (keyValues[i + 1]) {
                case String str -> quote(b, str);
                case Long l -> b.append(l.longValue());
                case Integer n -> b.append(n.intValue());
                default -> throw new IllegalArgumentException("JSON-Wert nicht unterstützt: "
                        + keyValues[i + 1].getClass().getSimpleName());
            }
        }
        return b.append('}').toString();
    }

    /**
     * String mit Anführungszeichen. Escapes wie Pythons json-Modul mit ensure_ascii=False: ", \\ und die
     * Steuerzeichen; \b \f \n \r \t kurz, die übrigen unter 0x20 als \\u00xx (klein). Alles andere bleibt roh (UTF-8).
     */
    static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
