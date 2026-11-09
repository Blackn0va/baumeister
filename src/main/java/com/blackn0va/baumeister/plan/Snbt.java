package com.blackn0va.baumeister.plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strenger Leser und Schreiber für eine Teilmenge von SNBT (Plan B2, Wunsch 2026-11-08, Befehlsblöcke inklusive).
 *
 * Warum eine Teilmenge und kein vollständiger Leser: Was das Plugin an Minecraft schickt, muss es vorher vollständig
 * verstanden und eingeordnet haben (BlockTiers). Minecraft selbst kennt seit 1.21.5 Escapes wie \x41, A, \N{…},
 * Funktionen wie bool(…) und uuid(…), Hex- und Binärzahlen, Unterstriche, vorzeichenlose Zahlen. Jede dieser Formen
 * könnte einen Schlüssel oder Wert so verkleiden, dass die Einordnung ihn anders liest als Minecraft – also lehnt der
 * Leser alles außer der Grundform ab (fail-closed, ASVS V5.1.3 Eingaben über Freigabeliste prüfen, OWASP A03).
 *
 * Erlaubt: Verbindungen {k:v}, Listen [..], typisierte Felder [B;..] [I;..] [L;..], Zeichenketten in "…" oder '…' mit
 * genau den Escapes \" \\ \n \t, Zahlen mit den Endungen b s l f d (ohne Endung: int bzw. double), true/false.
 *
 * An Minecraft geht nur die Fassung von {@link #write}: aus dem Baum neu geschrieben, Zeichenketten immer in "…",
 * Schlüssel nur ohne Anführungszeichen, wenn sie dafür taugen. Wer stattdessen den Rohtext weiterreicht, umgeht die
 * Prüfung.
 *
 * Threads: zustandslos. Fehler: PlanException mit deutschem Satz und Stelle.
 */
public final class Snbt {

    /** Aus dem LLM (Plan B2): höchstens 1000 Zeichen für den ganzen Block samt Daten. */
    public static final int MAX_FROM_MODEL = 1000;
    /** Konsole und Bauteile: 32 KiB – reicht für volle Truhen und lange Befehle (Befehlsblock-Grenze 32 500). */
    public static final int MAX_TRUSTED = 32 * 1024;
    /** Tiefe aus Verbindungen und Listen; Minecraft selbst erlaubt 512, echte Blockdaten brauchen unter 10. */
    static final int MAX_DEPTH = 32;

    private static final Pattern INTEGER = Pattern.compile("[-+]?[0-9]+[bBsSlL]?");
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(?:[0-9]+\\.[0-9]*|\\.[0-9]+)[fFdD]?|[-+]?[0-9]+[fFdD]");
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.+-]*");

    private final String text;
    private int pos;

    private Snbt(String text) {
        this.text = text;
    }

    /** Liest genau eine Verbindung {…}; danach darf nur Leerraum folgen. */
    public static Nbt.Compound parseCompound(String text, int maxChars) throws PlanException {
        if (text == null) {
            throw new PlanException("Blockdaten fehlen.");
        }
        if (text.length() > maxChars) {
            throw new PlanException("Blockdaten zu lang: " + text.length() + " Zeichen, erlaubt sind " + maxChars + ".");
        }
        Snbt p = new Snbt(text);
        p.skipWhitespace();
        if (p.peek() != '{') {
            throw p.error("Blockdaten müssen mit { beginnen");
        }
        Nbt.Compound out = p.compound(1);
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw p.error("nach den Blockdaten steht noch Text");
        }
        return out;
    }

    // ---------------------------------------------------------------- Lesen

    private Nbt value(int depth) throws PlanException {
        if (depth > MAX_DEPTH) {
            throw error("zu tief verschachtelt (höchstens " + MAX_DEPTH + " Ebenen)");
        }
        skipWhitespace();
        char c = peek();
        if (c == '{') {
            return compound(depth);
        }
        if (c == '[') {
            if (pos + 2 < text.length() && text.charAt(pos + 2) == ';' && "BIL".indexOf(text.charAt(pos + 1)) >= 0) {
                return array(depth);
            }
            return list(depth);
        }
        if (c == '"' || c == '\'') {
            return new Nbt.Str(quoted());
        }
        return bare();
    }

    private Nbt.Compound compound(int depth) throws PlanException {
        if (depth > MAX_DEPTH) {
            throw error("zu tief verschachtelt (höchstens " + MAX_DEPTH + " Ebenen)");
        }
        expect('{');
        Map<String, Nbt> entries = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return new Nbt.Compound(entries);
        }
        while (true) {
            skipWhitespace();
            String key = key();
            skipWhitespace();
            expect(':');
            Nbt v = value(depth + 1);
            // Doppelte Schlüssel: Minecraft nähme still den letzten – die Einordnung könnte den ersten gesehen haben.
            if (entries.put(key, v) != null) {
                throw error("Schlüssel doppelt: " + key);
            }
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return new Nbt.Compound(entries);
            }
            if (c != ',') {
                throw error("erwartet , oder }");
            }
        }
    }

    private Nbt.ListTag list(int depth) throws PlanException {
        expect('[');
        List<Nbt> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return new Nbt.ListTag(items);
        }
        while (true) {
            items.add(value(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return new Nbt.ListTag(items);
            }
            if (c != ',') {
                throw error("erwartet , oder ]");
            }
        }
    }

    private Nbt.Arr array(int depth) throws PlanException {
        expect('[');
        char type = next();
        expect(';');
        List<Long> values = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return new Nbt.Arr(type, values);
        }
        while (true) {
            skipWhitespace();
            int start = pos;
            String token = token();
            if (!INTEGER.matcher(token).matches()) {
                pos = start;
                throw error("im Feld [" + type + ";…] nur ganze Zahlen");
            }
            char suffix = Character.toLowerCase(token.charAt(token.length() - 1));
            boolean hasSuffix = Character.isLetter(suffix);
            char want = switch (type) {
                case 'B' -> 'b';
                case 'L' -> 'l';
                default -> 'i';
            };
            if (hasSuffix && suffix != want) {
                pos = start;
                throw error("Zahl passt nicht zum Feld [" + type + ";…]");
            }
            values.add(range(hasSuffix ? token.substring(0, token.length() - 1) : token, want, start).asLong());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return new Nbt.Arr(type, values);
            }
            if (c != ',') {
                throw error("erwartet , oder ]");
            }
        }
    }

    private String key() throws PlanException {
        char c = peek();
        if (c == '"' || c == '\'') {
            return quoted();
        }
        int start = pos;
        String token = token();
        if (token.isEmpty()) {
            pos = start;
            throw error("Schlüssel fehlt");
        }
        return token;
    }

    /** Zahl, true/false oder Zeichenkette ohne Anführungszeichen. */
    private Nbt bare() throws PlanException {
        int start = pos;
        String token = token();
        if (token.isEmpty()) {
            throw error("Wert fehlt");
        }
        skipWhitespace();
        // bool(…), uuid(…) und künftige Funktionen: Minecraft rechnet sie aus, die Einordnung sähe nur den Namen.
        if (pos < text.length() && text.charAt(pos) == '(') {
            pos = start;
            throw error("Funktionen wie " + token + "(…) sind nicht erlaubt");
        }
        if (token.equals("true")) {
            return Nbt.Num.ofByte(1);
        }
        if (token.equals("false")) {
            return Nbt.Num.ofByte(0);
        }
        if (INTEGER.matcher(token).matches()) {
            char last = Character.toLowerCase(token.charAt(token.length() - 1));
            if (Character.isLetter(last)) {
                return range(token.substring(0, token.length() - 1), last, start);
            }
            return range(token, 'i', start);
        }
        if (DECIMAL.matcher(token).matches()) {
            char last = Character.toLowerCase(token.charAt(token.length() - 1));
            boolean typed = last == 'f' || last == 'd';
            String digits = typed ? token.substring(0, token.length() - 1) : token;
            char type = typed ? last : 'd';
            BigDecimal v = new BigDecimal(digits.startsWith("+") ? digits.substring(1) : digits);
            double asDouble = type == 'f' ? v.floatValue() : v.doubleValue();
            if (Double.isInfinite(asDouble) || Double.isNaN(asDouble)) {
                pos = start;
                throw error("Zahl zu groß: " + token);
            }
            return new Nbt.Num(type, v);
        }
        // Ohne Anführungszeichen nur Wörter, die mit Buchstabe oder _ beginnen: 1e5, 0x1F, 1_000 und 1ub sind in
        // Minecraft Zahlen, die dieser Leser nicht nachrechnet.
        if (!Character.isLetter(token.charAt(0)) && token.charAt(0) != '_') {
            pos = start;
            throw error("Zahl in dieser Form nicht erlaubt: " + token);
        }
        return new Nbt.Str(token);
    }

    private Nbt.Num range(String digits, char type, int start) throws PlanException {
        BigDecimal v = new BigDecimal(digits.startsWith("+") ? digits.substring(1) : digits);
        BigDecimal min;
        BigDecimal max;
        switch (type) {
            case 'b' -> {
                min = BigDecimal.valueOf(Byte.MIN_VALUE);
                max = BigDecimal.valueOf(Byte.MAX_VALUE);
            }
            case 's' -> {
                min = BigDecimal.valueOf(Short.MIN_VALUE);
                max = BigDecimal.valueOf(Short.MAX_VALUE);
            }
            case 'l' -> {
                min = BigDecimal.valueOf(Long.MIN_VALUE);
                max = BigDecimal.valueOf(Long.MAX_VALUE);
            }
            default -> {
                min = BigDecimal.valueOf(Integer.MIN_VALUE);
                max = BigDecimal.valueOf(Integer.MAX_VALUE);
            }
        }
        if (v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            pos = start;
            throw error("Zahl außerhalb des Bereichs: " + digits + type);
        }
        return new Nbt.Num(type, v);
    }

    /** Zeichenkette in "…" oder '…'; nur \" \\ \n \t. */
    private String quoted() throws PlanException {
        char quote = next();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error("Zeichenkette nicht geschlossen");
            }
            char c = text.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= text.length()) {
                    throw error("Zeichenkette nicht geschlossen");
                }
                char e = text.charAt(pos++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> {
                        pos -= 2;
                        throw error("Escape \\" + e + " ist nicht erlaubt (nur \\\" \\\\ \\n \\t)");
                    }
                }
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                pos--;
                throw error("Steuerzeichen in einer Zeichenkette");
            }
            if (Character.isHighSurrogate(c)) {
                if (pos >= text.length() || !Character.isLowSurrogate(text.charAt(pos))) {
                    pos--;
                    throw error("ungültiges Unicode-Zeichen");
                }
                sb.append(c).append(text.charAt(pos++));
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                pos--;
                throw error("ungültiges Unicode-Zeichen");
            }
            sb.append(c);
        }
    }

    private String token() {
        int start = pos;
        while (pos < text.length() && isBare(text.charAt(pos))) {
            pos++;
        }
        return text.substring(start, pos);
    }

    private static boolean isBare(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-'
                || c == '.' || c == '+';
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next() throws PlanException {
        if (pos >= text.length()) {
            throw error("Blockdaten enden zu früh");
        }
        return text.charAt(pos++);
    }

    private void expect(char c) throws PlanException {
        if (peek() != c) {
            throw error("erwartet " + c);
        }
        pos++;
    }

    private PlanException error(String what) {
        return new PlanException("Blockdaten ungültig an Stelle " + (pos + 1) + ": " + what + ".");
    }

    // ---------------------------------------------------------------- Schreiben

    /** Neu geschriebene Fassung – das Einzige, was an Minecraft geht. */
    public static String write(Nbt value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    private static void write(Nbt value, StringBuilder sb) {
        switch (value) {
            case Nbt.Compound c -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Nbt> e : c.entries().entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    if (BARE_KEY.matcher(e.getKey()).matches()) {
                        sb.append(e.getKey());
                    } else {
                        quote(e.getKey(), sb);
                    }
                    sb.append(':');
                    write(e.getValue(), sb);
                }
                sb.append('}');
            }
            case Nbt.ListTag l -> {
                sb.append('[');
                for (int i = 0; i < l.items().size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(l.items().get(i), sb);
                }
                sb.append(']');
            }
            case Nbt.Str s -> quote(s.value(), sb);
            case Nbt.Num n -> {
                String digits = n.value().toPlainString();
                switch (n.type()) {
                    case 'b' -> sb.append(digits).append('b');
                    case 's' -> sb.append(digits).append('s');
                    case 'l' -> sb.append(digits).append('L');
                    case 'f' -> sb.append(digits).append('f');
                    case 'd' -> sb.append(digits).append('d');
                    default -> sb.append(digits);
                }
            }
            case Nbt.Arr a -> {
                sb.append('[').append(a.type()).append(';');
                for (int i = 0; i < a.values().size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(a.values().get(i));
                    if (a.type() == 'B') {
                        sb.append('b');
                    } else if (a.type() == 'L') {
                        sb.append('L');
                    }
                }
                sb.append(']');
            }
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> {
                    // Andere Steuerzeichen liest der Leser nie ein; kommen sie trotzdem (Bauteil-Datei), nicht roh in
                    // den Konsolenbefehl (Befund 12) – Leser und Schreiber bleiben gleich streng.
                    if (c < 0x20 || c == 0x7F) {
                        throw new IllegalArgumentException("Steuerzeichen U+" + String.format("%04X", (int) c)
                                + " in Blockdaten");
                    }
                    sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
