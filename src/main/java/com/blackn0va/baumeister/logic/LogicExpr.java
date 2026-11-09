package com.blackn0va.baumeister.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Logische Formel → Wahrheitstabelle. Grundlage des Logik-Compilers (LogicCircuit): Spieler und LLM beschreiben eine
 * Schaltung als Formel, gebaut wird sie deterministisch – ein LLM verdrahtet Redstone aus dem Kopf praktisch nie
 * richtig (gemessen: Llama 3.1 8B stapelte Gatter in eine Fassade).
 *
 * Schreibweisen (Groß-/Kleinschreibung egal): NICHT {@code ! ~ ¬ nicht not}, UND {@code & && * ∧ und and},
 * EXKLUSIV-ODER {@code ^ ⊕ xor}, ODER {@code | || + ∨ oder or}, Klammern, Konstanten {@code 0 1 wahr falsch}.
 * Vorrang NICHT &gt; UND &gt; XOR &gt; ODER. Ein vorangestelltes „Q =“ (Name des Ausgangs) wird angenommen.
 * Variablen: Buchstabe, dann Buchstaben/Ziffern/_; Reihenfolge des ersten Auftretens = Hebel von vorn nach hinten.
 *
 * Mehrere Ausgänge ({@link #parseAll}): Formeln mit „;“ getrennt in EINEM Text, z. B. Volladdierer
 * „S = A xor B xor C; U = A&amp;B | C&amp;(A^B)“. Warum ein Text statt einer Liste: op logic hat genau ein Feld expr
 * (String) – die feste Host-Grammatik (bauplan.gbnf) und das Schema brauchen so kein neues Feld, nur die Länge
 * (seit 2026-11-07 480 statt 160 Zeichen, {@link #MAX_LENGTH}).
 * Alle Formeln teilen sich die Eingänge (Reihenfolge des ersten Auftretens über alle Formeln hinweg).
 *
 * Threads: zustandslos. Fehler: IllegalArgumentException mit deutschem Satz (geht an Spieler und LLM).
 */
public final class LogicExpr {

    /** Mehr Eingänge sprengen das Raster (Tiefe 8 Blöcke je Eingang) und die Wahrheitstabelle der Probe. */
    public static final int MAX_INPUTS = 5;
    /**
     * Vorher 160 – ein 3-zu-8-Dekoder ist allein ~170 Zeichen. plan-schema.json und die Host-Grammatik (bauplan.gbnf)
     * erlauben dem Modell seit 2026-11-07 ebenfalls 480; GrammarDriftTest hält beide gleich.
     */
    public static final int MAX_LENGTH = 480;
    /**
     * Ausgänge je Schaltung. Vorher 4 (eine Ebene des Rasters). Seit 2026-11-07 stapelt LogicCircuit Stockwerke
     * übereinander (je bis 4 Ausgänge und 16 Terme, Wunsch) – 4 Stockwerke
     * tragen 16 Ausgänge, etwa einen 3-zu-8-Dekoder oder zwei 5-Bit-Paritäten.
     */
    public static final int MAX_OUTPUTS = 16;

    private final String output;
    private final List<String> inputs;
    private final Node root;

    private LogicExpr(String output, List<String> inputs, Node root) {
        this.output = output;
        this.inputs = inputs;
        this.root = root;
    }

    public String output() {
        return output;
    }

    /** Eingänge in Reihenfolge des ersten Auftretens. */
    public List<String> inputs() {
        return inputs;
    }

    /** Wert für eine Belegung: Bit i = Eingang i. */
    public boolean eval(int bits) {
        return root.eval(bits);
    }

    /** Alle Belegungen, für die die Formel wahr ist (Bit i = Eingang i). */
    public List<Integer> minterms() {
        List<Integer> out = new ArrayList<>();
        for (int b = 0; b < 1 << inputs.size(); b++) {
            if (eval(b)) {
                out.add(b);
            }
        }
        return out;
    }

    public static LogicExpr parse(String text) {
        checkLength(text);
        Map<String, Integer> vars = new LinkedHashMap<>();
        Part part = parsePart(text.trim(), vars);
        checkInputs(vars);
        String out = part.named() ? part.output() : defaultName("", new HashSet<>(vars.keySet()));
        return new LogicExpr(out, List.copyOf(vars.keySet()), part.root());
    }

    /**
     * Eine oder mehrere Formeln, getrennt durch „;“ (Zeilenumbruch geht auch). Alle Ergebnisse haben dieselbe
     * Eingangsliste – Bit i heißt in jeder Formel derselbe Hebel. Ohne Namen heißt eine einzelne Formel „Q“ (wie
     * {@link #parse}), mehrere „Q1“, „Q2“ … nach ihrer Stelle – ist der Name schon Eingang oder ausdrücklich
     * vergeben, weicht er aus ({@link #defaultName}).
     *
     * Abgelehnt mit deutschem Grund: mehr als {@link #MAX_OUTPUTS} Formeln, ein Name doppelt, und ein ausdrücklich
     * benannter Ausgang, der in einer Formel als Eingang auftaucht – das Raster speist keinen Ausgang zurück; wer
     * „C = A&amp;B; S = C^D“ meint, schreibt „S = (A&amp;B)^D“.
     */
    public static List<LogicExpr> parseAll(String text) {
        checkLength(text);
        List<String> parts = new ArrayList<>();
        for (String raw : text.split("[;\n]")) {
            if (!raw.isBlank()) {
                parts.add(raw.trim());
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("Leere Formel.");
        }
        if (parts.size() > MAX_OUTPUTS) {
            throw new IllegalArgumentException("Höchstens " + MAX_OUTPUTS + " Ausgänge, die Formel hat " + parts.size()
                    + ".");
        }
        Map<String, Integer> vars = new LinkedHashMap<>();
        List<Part> parsed = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (String part : parts) {
            Part p = parsePart(part, vars);
            if (p.named() && names.putIfAbsent(p.output().toUpperCase(Locale.ROOT), p.output()) != null) {
                throw new IllegalArgumentException("Ausgang „" + p.output() + "“ kommt zweimal vor – jede Formel "
                        + "braucht einen eigenen Namen.");
            }
            parsed.add(p);
        }
        checkInputs(vars);
        // Nur ausdrücklich genannte Namen: Vorher galt die Prüfung auch für den Vorgabenamen, und „P und Q“ (baute auf
        // b8ec700) scheiterte mit „„Q“ ist Ausgang und zugleich Eingang“, obwohl niemand einen Ausgang Q genannt hatte.
        for (String key : names.keySet()) {
            if (vars.containsKey(key)) {
                throw new IllegalArgumentException("„" + names.get(key) + "“ ist Ausgang und zugleich Eingang einer "
                        + "Formel – das Raster speist keinen Ausgang zurück. Setze seine Formel dort in Klammern ein.");
            }
        }
        Set<String> taken = new HashSet<>(vars.keySet());
        taken.addAll(names.keySet());
        List<String> inputs = List.copyOf(vars.keySet());
        List<LogicExpr> out = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            Part p = parsed.get(i);
            String name = p.named() ? p.output() : defaultName(parsed.size() == 1 ? "" : String.valueOf(i + 1), taken);
            taken.add(name.toUpperCase(Locale.ROOT));
            out.add(new LogicExpr(name, inputs, p.root()));
        }
        return List.copyOf(out);
    }

    /** output ist null, wenn die Formel keinen Namen trägt – der Vorgabename hängt von allen Formeln ab. */
    private record Part(String output, boolean named, Node root) {
    }

    /**
     * Vorgabename „Q“ bzw. „Q“ + Stelle; ist er schon Eingang oder vergeben, „Y“, „Z“, dann „Q…_1“, „Q…_2“ … – taken
     * enthält höchstens 5 Eingänge und 4 Namen, die Schleife endet also spätestens beim zehnten Versuch. Warum
     * ausweichen statt ablehnen: Der Spieler hat keinen Ausgang benannt, also gibt es auch keine Rückführung.
     */
    private static String defaultName(String suffix, Set<String> taken) {
        for (String base : List.of("Q", "Y", "Z")) {
            String name = base + suffix;
            if (!taken.contains(name.toUpperCase(Locale.ROOT))) {
                return name;
            }
        }
        for (int k = 1; ; k++) {
            String name = "Q" + suffix + "_" + k;
            if (!taken.contains(name.toUpperCase(Locale.ROOT))) {
                return name;
            }
        }
    }

    private static void checkLength(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Leere Formel.");
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Formel zu lang (höchstens " + MAX_LENGTH + " Zeichen).");
        }
    }

    private static void checkInputs(Map<String, Integer> vars) {
        if (vars.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("Höchstens " + MAX_INPUTS + " Eingänge, die Formel hat " + vars.size()
                    + " (" + String.join(", ", vars.keySet()) + ").");
        }
    }

    /** Eine Formel ohne „;“; vars sammelt die Eingänge über alle Formeln einer Schaltung. */
    private static Part parsePart(String s, Map<String, Integer> vars) {
        String out = null;
        int eq = s.indexOf('=');
        // ß gehört dazu: Vorher scheiterte „Größer = A & !B“ (Vergleicher) mit „Zeichen „=“ verstehe ich nicht“, weil
        // der Name nicht als Name erkannt wurde und das „=“ in die Formel rutschte.
        if (eq > 0 && s.substring(0, eq).trim().matches("[A-Za-zÄÖÜäöüß][A-Za-z0-9_ÄÖÜäöüß]*")) {
            out = s.substring(0, eq).trim();
            s = s.substring(eq + 1);
        }
        Parser p = new Parser(tokenize(s), vars);
        Node root = p.or();
        if (p.pos < p.tokens.size()) {
            throw new IllegalArgumentException("Unerwartet: „" + p.tokens.get(p.pos) + "“.");
        }
        return new Part(out, out != null, root);
    }

    // ------------------------------------------------------------------ Zerlegen

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isLetter(c)) {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else if (c == '&' || c == '|') {
                int j = i + 1 < s.length() && s.charAt(i + 1) == c ? i + 2 : i + 1;
                out.add(String.valueOf(c));
                i = j;
            } else if ("!~¬*∧^⊕+∨()01".indexOf(c) >= 0) {
                out.add(String.valueOf(c));
                i++;
            } else {
                throw new IllegalArgumentException("Zeichen „" + c + "“ verstehe ich nicht.");
            }
        }
        return out;
    }

    private static final class Parser {
        final List<String> tokens;
        int pos;
        final Map<String, Integer> vars;

        Parser(List<String> tokens, Map<String, Integer> vars) {
            this.tokens = tokens;
            this.vars = vars;
        }

        String peek() {
            return pos < tokens.size() ? tokens.get(pos).toLowerCase(Locale.ROOT) : "";
        }

        Node or() {
            Node n = xor();
            while (List.of("|", "+", "∨", "oder", "or").contains(peek())) {
                pos++;
                Node l = n;
                Node r = xor();
                n = b -> l.eval(b) || r.eval(b);
            }
            return n;
        }

        Node xor() {
            Node n = and();
            while (List.of("^", "⊕", "xor").contains(peek())) {
                pos++;
                Node l = n;
                Node r = and();
                n = b -> l.eval(b) ^ r.eval(b);
            }
            return n;
        }

        Node and() {
            Node n = not();
            while (List.of("&", "*", "∧", "und", "and").contains(peek())) {
                pos++;
                Node l = n;
                Node r = not();
                n = b -> l.eval(b) && r.eval(b);
            }
            return n;
        }

        Node not() {
            if (List.of("!", "~", "¬", "nicht", "not").contains(peek())) {
                pos++;
                Node inner = not();
                return b -> !inner.eval(b);
            }
            return atom();
        }

        Node atom() {
            String t = peek();
            if (t.isEmpty()) {
                throw new IllegalArgumentException("Formel endet zu früh.");
            }
            pos++;
            switch (t) {
                case "(" -> {
                    Node n = or();
                    if (!peek().equals(")")) {
                        throw new IllegalArgumentException("Schließende Klammer fehlt.");
                    }
                    pos++;
                    return n;
                }
                case "1", "wahr", "true", "an" -> {
                    return b -> true;
                }
                case "0", "falsch", "false", "aus" -> {
                    return b -> false;
                }
                default -> {
                    String name = tokens.get(pos - 1);
                    if (!Character.isLetter(name.charAt(0)) || KEYWORDS.contains(t)) {
                        throw new IllegalArgumentException("Erwartet einen Eingang oder „(“, nicht „" + name + "“.");
                    }
                    String key = name.toUpperCase(Locale.ROOT);
                    int bit = vars.computeIfAbsent(key, k -> vars.size());
                    return b -> (b >> bit & 1) == 1;
                }
            }
        }
    }

    private static final List<String> KEYWORDS = List.of("und", "and", "oder", "or", "nicht", "not", "xor");

    @FunctionalInterface
    private interface Node {
        boolean eval(int bits);
    }
}
