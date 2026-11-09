package com.blackn0va.baumeister;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Sammelt die festen Sätze von Vera und dem Baumeister aus dem Quelltext nach src/main/resources/sprache-vorrat.txt
 * (2026-11-08). Der Server spricht sie vorab (LocalVoice.prewarm): Dann kommen sie sofort aus dem Zwischenspeicher,
 * auch wenn kein LLM läuft – Vera antwortet ohne Modell ohnehin nur mit diesen Sätzen.
 *
 * Genommen werden nur ganze Sätze: Literale ohne „+“ davor oder danach (Bruchstücke wie „In “ + r + „ Blöcken“ hört
 * nie jemand allein), mit Großbuchstaben vorn, mindestens zwei Wörtern und Satzzeichen; ohne Log-Zeilen („|“),
 * Formate, Pfade und Regex. V = Vera (Begleiter-Quellen), B = Baumeister (Argumente von Texts.say).
 *
 * Neu erzeugen: java -cp target/classes;target/test-classes;lib/* com.blackn0va.baumeister.SpracheVorrat .
 * SpracheVorratTest schlägt fehl, solange die Datei nicht zum Quelltext passt.
 *
 * Threads: einer. Fehler: IOException bricht ab.
 */
public final class SpracheVorrat {

    static final String RESOURCE = "src/main/resources/sprache-vorrat.txt";

    private SpracheVorrat() {
    }

    public static void main(String[] args) throws IOException {
        Path repo = Path.of(args.length > 0 ? args[0] : ".");
        List<String> lines = collect(repo.resolve("src/main/java"));
        Files.writeString(repo.resolve(RESOURCE), render(lines), StandardCharsets.UTF_8);
        System.out.println("sprache-vorrat.txt: " + lines.size() + " Sätze");
    }

    static String render(List<String> lines) {
        return "# Erzeugt von SpracheVorrat (src/test) – nicht von Hand ändern. V = Vera, B = Baumeister.\n"
                + String.join("\n", lines) + "\n";
    }

    /** „V\tSatz“ / „B\tSatz“, sortiert und ohne Doppel. */
    static List<String> collect(Path srcMain) throws IOException {
        Set<String> out = new java.util.TreeSet<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(srcMain)) {
            files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path f : files) {
            String name = f.getFileName().toString();
            String rel = srcMain.relativize(f).toString().replace('\\', '/');
            boolean vera = rel.contains("/companion/") || name.startsWith("Companion");
            String src = Files.readString(f, StandardCharsets.UTF_8);
            for (Literal l : literals(src)) {
                if (!sentence(l.text())) {
                    continue;
                }
                if (vera) {
                    out.add("V\t" + l.text());
                }
                if (l.inTextsSay()) {
                    out.add("B\t" + l.text());
                }
            }
        }
        return List.copyOf(new LinkedHashSet<>(out));
    }

    record Literal(String text, boolean inTextsSay) {
    }

    /** Alleinstehende String-Literale (kein „+“ daneben) außerhalb von Kommentaren. */
    static List<Literal> literals(String src) {
        List<Literal> out = new ArrayList<>();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i = src.indexOf('\n', i);
                i = i < 0 ? n : i;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                continue;
            }
            if (c == '\'') {
                i = src.charAt(i + 1) == '\\' ? src.indexOf('\'', i + 3) + 1 : i + 3;
                continue;
            }
            if (c == '"' && src.startsWith("\"\"\"", i)) {
                int e = src.indexOf("\"\"\"", i + 3);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (c == '"') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != '"') {
                    char d = src.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        char e = src.charAt(j + 1);
                        switch (e) {
                            case 'n' -> b.append('\n');
                            case 't' -> b.append('\t');
                            case 'u' -> {
                                b.append((char) Integer.parseInt(src.substring(j + 2, j + 6), 16));
                                j += 4;
                            }
                            default -> b.append(e);
                        }
                        j += 2;
                        continue;
                    }
                    b.append(d);
                    j++;
                }
                char before = prevSolid(src, i);
                char after = nextSolid(src, j + 1);
                if (before != '+' && after != '+' && after != '.') {
                    out.add(new Literal(b.toString(), insideTextsSay(src, i)));
                }
                i = j + 1;
                continue;
            }
            i++;
        }
        return out;
    }

    private static char prevSolid(String s, int i) {
        for (int k = i - 1; k >= 0; k--) {
            if (!Character.isWhitespace(s.charAt(k))) {
                return s.charAt(k);
            }
        }
        return 0;
    }

    private static char nextSolid(String s, int i) {
        for (int k = i; k < s.length(); k++) {
            if (!Character.isWhitespace(s.charAt(k))) {
                return s.charAt(k);
            }
        }
        return 0;
    }

    /** Steht das Literal in den Klammern eines Texts.say( … )-Aufrufs? (höchstens 400 Zeichen zurück) */
    private static boolean insideTextsSay(String src, int at) {
        int start = src.lastIndexOf("Texts.say(", at);
        if (start < 0 || at - start > 400) {
            return false;
        }
        int depth = 0;
        for (int k = start + "Texts.say".length(); k < at; k++) {
            char c = src.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return false;
                }
            }
        }
        return depth > 0;
    }

    /** Sieht aus wie ein gesprochener Satz. */
    static boolean sentence(String t) {
        if (t.length() < 8 || t.length() > 400 || t.chars().noneMatch(Character::isWhitespace)) {
            return false;
        }
        char first = t.charAt(0);
        if (!(Character.isUpperCase(first) || first == '„')) {
            return false;
        }
        for (String bad : List.of("|", "%", "{}", "{0", "(?", "\\", "/", "_", "§", "http", ".java", "minecraft:",
                "\n", "\t", "[", "<", ">", "=")) {
            if (t.contains(bad)) {
                return false;
            }
        }
        return t.matches("(?s).*[.!?…“]\\s*$") || t.matches("(?s).*[.!?].*");
    }
}
