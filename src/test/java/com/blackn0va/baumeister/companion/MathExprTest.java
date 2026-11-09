package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Rechnen für den Begleiter: Vorrang, deutsche Schreibweise und die Grenzen gegen Missbrauch. */
class MathExprTest {

    private static String calc(String s) {
        MathExpr.Result r = MathExpr.eval(s);
        return r.ok() ? MathExpr.format(r.value()) : "Fehler: " + r.error();
    }

    @Test
    void respectsPrecedenceAndParentheses() {
        assertEquals("391", calc("17*23"));
        assertEquals("14", calc("2+3*4"));
        assertEquals("20", calc("(2+3)*4"));
        assertEquals("512", calc("2^3^2"), "^ bindet rechts");
        assertEquals("-7", calc("-(3+4)"));
    }

    @Test
    void understandsGermanWordsAndDecimalComma() {
        assertEquals("391", calc("17 mal 23"));
        assertEquals("2,5", calc("5 durch 2"));
        assertEquals("1,75", calc("1,5 plus 0,25"));
        assertEquals("1500", calc("1.000 + 500"), "Tausenderpunkt");
        assertEquals("0,333333", calc("1/3"));
        assertEquals("6", calc("2 × 3"));
    }

    @Test
    void rejectsDivisionByZeroAndGarbage() {
        assertFalse(MathExpr.eval("1/0").ok());
        assertFalse(MathExpr.eval("2+").ok());
        assertFalse(MathExpr.eval("Math.exit(0)").ok());
        assertFalse(MathExpr.eval("").ok());
        assertFalse(MathExpr.eval("(1+2").ok());
    }

    @Test
    void limitsLengthDepthAndExponent() {
        assertFalse(MathExpr.eval("1+".repeat(41) + "1").ok(), "über 80 Zeichen");
        assertFalse(MathExpr.eval("(".repeat(17) + "1" + ")".repeat(17)).ok(), "Klammertiefe 17");
        assertTrue(MathExpr.eval("(".repeat(16) + "1" + ")".repeat(16)).ok());
        assertFalse(MathExpr.eval("2^1001").ok());
        assertFalse(MathExpr.eval("10^400").ok(), "Unendlich wird gemeldet, nicht gesagt");
    }
}
