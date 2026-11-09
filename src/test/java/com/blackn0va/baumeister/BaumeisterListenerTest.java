package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BaumeisterListenerTest {

    @Test
    void recognisesNameWithCommaColonOrAt() {
        assertEquals("bau ein Haus", BaumeisterListener.extractPrompt("Baumeister", "Baumeister, bau ein Haus"));
        assertEquals("bau ein Haus", BaumeisterListener.extractPrompt("Baumeister", "baumeister: bau ein Haus"));
        assertEquals("bau ein Haus", BaumeisterListener.extractPrompt("Baumeister", "@Baumeister bau ein Haus"));
    }

    @Test
    void ignoresNameInsideNormalChat() {
        assertNull(BaumeisterListener.extractPrompt("Baumeister", "der Baumeister ist cool"));
        assertNull(BaumeisterListener.extractPrompt("Baumeister", "Baumeisterin, hallo"));
        assertNull(BaumeisterListener.extractPrompt("Baumeister", "Baumeister,"));
    }

    @Test
    void nameIsTakenLiterallyNotAsRegex() {
        assertNull(BaumeisterListener.extractPrompt("B.b", "Bob, bau was"));
        assertEquals("bau was", BaumeisterListener.extractPrompt("B.b", "B.b, bau was"));
    }

    @Test
    void cleanRemovesColourCodesAndControlCharacters() {
        assertEquals("Hallo Welt", Texts.clean("§cHallo\u0007 Welt", 50));
        assertEquals("abc…", Texts.clean("abcdefgh", 4));
    }
}
