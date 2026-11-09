package com.blackn0va.baumeister.plan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Repariert die Syntax einer LLM-Antwort, bevor sie als Bauplan verworfen wird.
 *
 * Anlass (GPU-PC des Nutzers, 2026-10-29): Die Grammatik des Hosts erzwingt gültiges JSON, aber nicht dessen Ende –
 * erreicht die Antwort max_tokens, bricht sie mitten in einem Schritt ab („JSON fehlerhaft: java.io.EOFException: End of
 * input at line 1 column 1999 path $.steps[26]“). Vorher ging dann die ganze kaputte Antwort samt Fehlermeldung in die
 * Reparaturrunde – der Prompt wuchs auf 8246 Token und sprengte den Kontext („Prompt zu lang“), der Spieler bekam nichts.
 * Jetzt: Was vollständig war, wird gebaut; der halbe letzte Schritt fällt weg (ein halber Schritt hieße raten, welche
 * Koordinate noch gefehlt hat). Ohne Grammatik (anderer Endpunkt) liefern Modelle außerdem gern Markdown-Zäune, Vortext,
 * Kommentare, Python-Literale, einfache Anführungszeichen oder vergessene/überzählige Kommas – auch das wird geglättet.
 *
 * Arbeitsweise: ein Durchlauf Zeichen für Zeichen mit einem Stapel offener Klammern. Für jede offene Klammer merkt sich
 * der Stapel die Ausgabelänge nach ihrem letzten VOLLSTÄNDIGEN Eintrag („sichere Stelle“). Endet der Text vorzeitig, wird
 * an der sicheren Stelle der tiefsten Liste geschnitten, deren angefangener Eintrag ein Objekt ist (bei einem Bauplan:
 * die Schrittliste), und alles Offene sauber geschlossen.
 *
 * Grenzen: Die Reparatur ändert nur die Syntax, nie Inhalte; ob der Plan Sinn ergibt, prüfen PlanParser, PlanCompiler und
 * PlanReview wie bei jeder Antwort. Eine Antwort ohne „{“ bleibt unlesbar.
 *
 * Threads: zustandslos, reentrant. Fehler: wirft nicht; null heißt „nichts Brauchbares gefunden“.
 */
public final class JsonRepair {

    /** Ergebnis: reparierter Text und deutsche Stichworte, was geändert wurde (leer = nichts nötig). */
    public record Result(String json, List<String> fixes, boolean truncated) {
    }

    private static final int MAX_DEPTH = 64;

    private JsonRepair() {
    }

    /** null, wenn kein „{“ im Text steht. */
    public static Result repair(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        return new Scanner(raw, start).run();
    }

    private static final class Frame {
        final char open;
        /** Ausgabelänge direkt nach dem letzten vollständigen Eintrag (bzw. nach der öffnenden Klammer). */
        int safe;
        /** Ein Eintrag ist fertig, das Komma davor steht noch aus. */
        boolean needComma;
        /** Nur in Objekten: als Nächstes kommt ein Schlüssel (sonst ein Wert nach dem Doppelpunkt). */
        boolean expectKey;
        /** Nur in Objekten: Schlüssel gelesen, Doppelpunkt fehlt noch. */
        boolean needColon;

        Frame(char open, int safe) {
            this.open = open;
            this.safe = safe;
            this.expectKey = open == '{';
        }
    }

    private static final class Scanner {
        private final String in;
        private int pos;
        private final StringBuilder out = new StringBuilder();
        private final List<Frame> stack = new ArrayList<>();
        private final Set<String> fixes = new LinkedHashSet<>();
        private boolean done;

        Scanner(String in, int start) {
            this.in = in;
            this.pos = start;
            if (!in.substring(0, start).isBlank()) {
                fixes.add("Text vor dem JSON entfernt");
            }
        }

        Result run() {
            while (pos < in.length() && !done) {
                char c = in.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '/' && pos + 1 < in.length() && (in.charAt(pos + 1) == '/' || in.charAt(pos + 1) == '*')) {
                    skipComment();
                } else if (c == '{' || c == '[') {
                    if (!beginValue()) {
                        continue;
                    }
                    if (stack.size() >= MAX_DEPTH) {
                        break;  // als Abbruch behandeln – so tief schachtelt kein Bauplan
                    }
                    out.append(c);
                    stack.add(new Frame(c, out.length()));
                    pos++;
                } else if (c == '}' || c == ']') {
                    close(c);
                    pos++;
                } else if (c == ',') {
                    comma();
                    pos++;
                } else if (c == ':') {
                    colon();
                    pos++;
                } else if (c == '"' || c == '\'') {
                    if (!string(c)) {
                        break;
                    }
                } else if (c == '-' || c == '+' || c == '.' || Character.isDigit(c)) {
                    if (!number()) {
                        break;
                    }
                } else if (Character.isLetter(c) || c == '_' || c == '$') {
                    word();
                } else {
                    // Fremdzeichen außerhalb von Texten (z. B. ` aus einem Markdown-Zaun mitten drin) – weg damit.
                    fixes.add("Fremdzeichen entfernt");
                    pos++;
                }
            }
            boolean truncated = false;
            if (!stack.isEmpty()) {
                truncated = true;
                cutAndClose();
            }
            if (done && pos < in.length() && !in.substring(pos).isBlank()) {
                fixes.add("Text nach dem JSON entfernt");
            }
            return new Result(out.toString(), List.copyOf(fixes), truncated);
        }

        private Frame top() {
            return stack.getLast();
        }

        /**
         * Vor jedem Wert oder Schlüssel: fehlendes Komma bzw. fehlenden Doppelpunkt ergänzen. false, wenn der Wert nicht
         * hierher gehört und übersprungen wurde (Stapel leer, Wurzel schon fertig).
         */
        private boolean beginValue() {
            if (stack.isEmpty()) {
                if (out.length() > 0) {
                    done = true;  // zweites Objekt nach der Wurzel: Rest ist Nachtext
                    return false;
                }
                return true;
            }
            Frame f = top();
            if (f.needComma) {
                out.append(',');
                f.needComma = false;
                if (f.open == '{') {
                    f.expectKey = true;
                }
                fixes.add("fehlendes Komma ergänzt");
            }
            if (f.open == '{' && f.needColon) {
                out.append(':');
                f.needColon = false;
                fixes.add("fehlenden Doppelpunkt ergänzt");
            }
            return true;
        }

        /** Ein Wert ist vollständig – für den umgebenden Rahmen die sichere Stelle merken. */
        private void valueDone() {
            if (stack.isEmpty()) {
                done = true;
                return;
            }
            Frame f = top();
            if (f.open == '{' && f.expectKey) {
                // gerade wurde ein Schlüssel gelesen, kein Wert
                f.expectKey = false;
                f.needColon = true;
                return;
            }
            f.safe = out.length();
            f.needComma = true;
        }

        private void comma() {
            if (stack.isEmpty()) {
                return;
            }
            Frame f = top();
            if (!f.needComma) {
                fixes.add("überzähliges Komma entfernt");
                return;
            }
            out.append(',');
            f.needComma = false;
            if (f.open == '{') {
                f.expectKey = true;
            }
        }

        private void colon() {
            if (stack.isEmpty()) {
                return;
            }
            Frame f = top();
            if (f.open == '{' && f.needColon) {
                out.append(':');
                f.needColon = false;
            } else {
                fixes.add("überzähligen Doppelpunkt entfernt");
            }
        }

        private void close(char c) {
            char want = c == '}' ? '{' : '[';
            int match = -1;
            for (int i = stack.size() - 1; i >= 0; i--) {
                if (stack.get(i).open == want) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                fixes.add("überzählige Klammer entfernt");
                return;
            }
            if (match != stack.size() - 1) {
                fixes.add("fehlende Klammer ergänzt");
            }
            while (stack.size() > match) {
                Frame f = stack.removeLast();
                // Halbes Paar ({"a":} oder {"a"}) – zurück zur sicheren Stelle.
                if (f.open == '{' && (f.needColon || !f.needComma && !f.expectKey)) {
                    out.setLength(f.safe);
                    fixes.add("unvollständigen Eintrag entfernt");
                }
                if (!f.needComma && out.length() > f.safe) {
                    out.setLength(f.safe);  // trailing comma
                    fixes.add("überzähliges Komma entfernt");
                }
                out.append(f.open == '{' ? '}' : ']');
                valueDone();
            }
        }

        /** false bei Abbruch mitten im Text. */
        private boolean string(char quote) {
            if (!beginValue()) {
                return true;
            }
            if (quote == '\'') {
                fixes.add("einfache Anführungszeichen ersetzt");
            }
            StringBuilder s = new StringBuilder("\"");
            int i = pos + 1;
            while (i < in.length()) {
                char c = in.charAt(i);
                if (c == quote) {
                    s.append('"');
                    out.append(s);
                    pos = i + 1;
                    valueDone();
                    return true;
                }
                if (c == '\\' && i + 1 < in.length()) {
                    char n = in.charAt(i + 1);
                    if (n == '\'' && quote == '\'') {
                        s.append('\'');
                    } else if ("\"\\/bfnrtu".indexOf(n) >= 0) {
                        s.append(c).append(n);
                    } else {
                        s.append("\\\\").append(n);
                        fixes.add("ungültige Escape-Folge maskiert");
                    }
                    i += 2;
                    continue;
                }
                if (c == '"' && quote == '\'') {
                    s.append("\\\"");
                } else if (c < 0x20) {
                    s.append(switch (c) {
                        case '\n' -> "\\n";
                        case '\r' -> "\\r";
                        case '\t' -> "\\t";
                        default -> String.format("\\u%04x", (int) c);
                    });
                    fixes.add("Steuerzeichen im Text maskiert");
                } else {
                    s.append(c);
                }
                i++;
            }
            pos = in.length();
            return false;  // Text endet vor dem schließenden Anführungszeichen
        }

        /** false bei Abbruch mitten in der Zahl (am Textende). */
        private boolean number() {
            if (!beginValue()) {
                return true;
            }
            int i = pos;
            StringBuilder num = new StringBuilder();
            if (in.charAt(i) == '+') {
                fixes.add("Pluszeichen entfernt");
                i++;
            }
            while (i < in.length() && "-+.eE0123456789".indexOf(in.charAt(i)) >= 0) {
                num.append(in.charAt(i));
                i++;
            }
            if (i >= in.length()) {
                pos = i;
                return false;
            }
            String n = num.toString();
            if (n.startsWith(".")) {
                n = "0" + n;
                fixes.add("Zahl ergänzt");
            } else if (n.startsWith("-.")) {
                n = "-0" + n.substring(1);
                fixes.add("Zahl ergänzt");
            }
            if (n.endsWith(".") || n.endsWith("e") || n.endsWith("E") || n.endsWith("-") || n.endsWith("+")) {
                n = n + "0";
                fixes.add("Zahl ergänzt");
            }
            if (n.isEmpty() || n.equals("-")) {
                pos = i;
                fixes.add("Fremdzeichen entfernt");
                return true;
            }
            out.append(n);
            pos = i;
            valueDone();
            return true;
        }

        private void word() {
            int i = pos;
            while (i < in.length()
                    && (Character.isLetterOrDigit(in.charAt(i)) || in.charAt(i) == '_' || in.charAt(i) == '$')) {
                i++;
            }
            String w = in.substring(pos, i);
            if (i >= in.length() && !stack.isEmpty()) {
                // Wort am Textende: vielleicht abgeschnitten („tru“) – als Abbruch behandeln.
                pos = i;
                return;
            }
            pos = i;
            if (!beginValue()) {
                return;
            }
            Frame f = stack.isEmpty() ? null : top();
            if (f != null && f.open == '{' && f.expectKey) {
                out.append('"').append(w).append('"');
                fixes.add("Schlüssel ohne Anführungszeichen ergänzt");
                valueDone();
                return;
            }
            String lit = switch (w) {
                case "true", "false", "null" -> w;
                case "True" -> "true";
                case "False" -> "false";
                case "None", "undefined", "NaN", "Infinity" -> "null";
                default -> null;
            };
            if (lit == null) {
                out.append('"').append(w).append('"');
                fixes.add("Text ohne Anführungszeichen ergänzt");
            } else {
                if (!lit.equals(w)) {
                    fixes.add(w + " ersetzt");
                }
                out.append(lit);
            }
            valueDone();
        }

        private void skipComment() {
            fixes.add("Kommentar entfernt");
            if (in.charAt(pos + 1) == '/') {
                int nl = in.indexOf('\n', pos);
                pos = nl < 0 ? in.length() : nl + 1;
            } else {
                int endC = in.indexOf("*/", pos + 2);
                pos = endC < 0 ? in.length() : endC + 2;
            }
        }

        /**
         * Text endete vorzeitig. Geschnitten wird an der sicheren Stelle der tiefsten Liste, deren angefangener
         * Eintrag ein Objekt ist – im Bauplan die Schrittliste: Ein halber Schritt fällt weg, die fertigen bleiben.
         * Gibt es keine solche Liste (Abbruch in „design“), dann an der sicheren Stelle des innersten Rahmens.
         */
        private void cutAndClose() {
            int cutAt = stack.size() - 1;
            for (int i = stack.size() - 2; i >= 0; i--) {
                if (stack.get(i).open == '[' && stack.get(i + 1).open == '{') {
                    cutAt = i;
                    break;
                }
            }
            Frame f = stack.get(cutAt);
            boolean dropped = out.length() > f.safe;
            out.setLength(f.safe);
            while (stack.size() > cutAt + 1) {
                stack.removeLast();
            }
            fixes.add(dropped ? "abgeschnittene Antwort beendet, unvollständigen letzten Eintrag verworfen"
                    : "abgeschnittene Antwort beendet");
            // Die Rahmen darunter enthalten den gerade geschnittenen als offenen Eintrag – sie werden nur geschlossen,
            // nicht gekürzt (sonst fiele z. B. die ganze Schrittliste samt "steps" weg).
            while (!stack.isEmpty()) {
                out.append(stack.removeLast().open == '{' ? '}' : ']');
            }
        }
    }
}
