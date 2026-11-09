package com.blackn0va.baumeister.companion;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Rechnet für den Begleiter (Aktion calc). Ein Sprachmodell rechnet „17 mal 23“ gern falsch, sagt das Ergebnis aber
 * im Brustton der Überzeugung; deshalb liefert das Modell nur den Ausdruck, gerechnet wird hier.
 *
 * Erlaubt: Zahlen (Komma oder Punkt als Dezimaltrenner), + − * / ^, die Wörter „mal“, „durch“, „plus“, „minus“,
 * die Zeichen × ÷ · und Klammern. Mehr nicht – kein Funktionsaufruf, keine Variable.
 * Grenzen gegen Missbrauch (Eingabe kommt vom LLM, also mittelbar vom Spieler): höchstens 80 Zeichen wie im Schema,
 * Klammertiefe 16, Exponent höchstens 1000 (sonst liefe pow ins Unendliche oder 10^10^10 in die Länge).
 *
 * Threads: zustandslos. Fehler: liefert Result mit error (deutscher Satz) statt zu werfen.
 */
public final class MathExpr {

    static final int MAX_LENGTH = 80;
    static final int MAX_DEPTH = 16;
    static final double MAX_EXPONENT = 1000;

    /** value ist nur gültig, wenn error null ist. */
    public record Result(double value, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    private final String s;
    private int pos;
    private int depth;

    private MathExpr(String s) {
        this.s = s;
    }

    public static Result eval(String input) {
        if (input == null || input.isBlank()) {
            return new Result(0, "Da steht keine Rechnung.");
        }
        if (input.length() > MAX_LENGTH) {
            return new Result(0, "Die Rechnung ist mir zu lang.");
        }
        String norm = input.toLowerCase(Locale.ROOT)
                .replace("mal", "*").replace("×", "*").replace("·", "*")
                .replace("geteilt durch", "/").replace("durch", "/").replace("÷", "/").replace(":", "/")
                .replace("plus", "+").replace("minus", "-").replace("−", "-").replace("hoch", "^")
                .replace(" ", "");
        MathExpr p = new MathExpr(norm);
        try {
            double v = p.sum();
            if (p.pos != norm.length()) {
                return new Result(0, "Das verstehe ich nicht als Rechnung.");
            }
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return new Result(0, "Das Ergebnis ist zu groß.");
            }
            return new Result(v, null);
        } catch (ArithmeticException e) {
            return new Result(0, e.getMessage());
        } catch (IllegalArgumentException e) {
            return new Result(0, "Das verstehe ich nicht als Rechnung.");
        }
    }

    /** Zahl auf Deutsch: Komma, höchstens 6 Nachkommastellen, ohne Nullen am Ende („391“, „0,333333“). */
    public static String format(double v) {
        BigDecimal d = BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        String plain = d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
        return plain.replace('.', ',');
    }

    private double sum() {
        double v = product();
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '+') {
                pos++;
                v += product();
            } else if (c == '-') {
                pos++;
                v -= product();
            } else {
                break;
            }
        }
        return v;
    }

    private double product() {
        double v = power();
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '*') {
                pos++;
                v *= power();
            } else if (c == '/') {
                pos++;
                double d = power();
                if (d == 0) {
                    throw new ArithmeticException("Durch null kann man nicht teilen.");
                }
                v /= d;
            } else {
                break;
            }
        }
        return v;
    }

    /** ^ bindet rechts: 2^3^2 = 2^9. */
    private double power() {
        double base = unary();
        if (pos < s.length() && s.charAt(pos) == '^') {
            pos++;
            double exp = power();
            if (Math.abs(exp) > MAX_EXPONENT) {
                throw new ArithmeticException("Der Exponent ist mir zu groß.");
            }
            return Math.pow(base, exp);
        }
        return base;
    }

    private double unary() {
        if (pos < s.length() && s.charAt(pos) == '-') {
            pos++;
            return -unary();
        }
        if (pos < s.length() && s.charAt(pos) == '+') {
            pos++;
            return unary();
        }
        return atom();
    }

    private double atom() {
        if (pos < s.length() && s.charAt(pos) == '(') {
            if (++depth > MAX_DEPTH) {
                throw new ArithmeticException("Zu viele Klammern.");
            }
            pos++;
            double v = sum();
            if (pos >= s.length() || s.charAt(pos) != ')') {
                throw new IllegalArgumentException("klammer");
            }
            pos++;
            depth--;
            return v;
        }
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == ',' || s.charAt(pos) == '.')) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("zahl");
        }
        // Tausenderpunkte („1.000“) kommen im gesprochenen Deutsch vor, Dezimalkomma ebenso: Komma ist Dezimaltrenner,
        // ein Punkt gefolgt von genau drei Ziffern am Zahlende Tausendertrenner, sonst Dezimalpunkt.
        String num = s.substring(start, pos);
        if (num.contains(",")) {
            num = num.replace(".", "").replace(',', '.');
        } else if (num.matches("\\d{1,3}(\\.\\d{3})+")) {
            num = num.replace(".", "");
        }
        return Double.parseDouble(num);
    }
}
