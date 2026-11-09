package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Satz für Satz sprechen: erster Ton früher, kurze Sätze zusammen, Abkürzungen nicht zerreißen. */
class SpeechChunksTest {

    @Test
    void splitsIntoSentencesAndMergesShortOnes() {
        List<String> c = SpeechChunks.split("Klar! Ich gebe dir sechzehn Fackeln, damit die Höhle hell wird. "
                + "Pass unten auf die Creeper auf, die lauern gern an dunklen Ecken.");
        assertEquals(List.of("Klar! Ich gebe dir sechzehn Fackeln, damit die Höhle hell wird.",
                "Pass unten auf die Creeper auf, die lauern gern an dunklen Ecken."), c);
    }

    @Test
    void shortAnswerStaysOneChunk() {
        assertEquals(List.of("Klar!"), SpeechChunks.split("Klar!"));
        assertEquals(List.of(), SpeechChunks.split("  "));
        assertEquals(List.of(), SpeechChunks.split(null));
    }

    @Test
    void abbreviationsAndNumbersDoNotEndASentence() {
        // Lang genug vor der Abkürzung, damit das Zusammenlegen kurzer Stücke den Fehler nicht verdeckt.
        List<String> c = SpeechChunks.split("Ich habe dir viele Sachen in die große Truhe gelegt, z.B. Eisen und Gold "
                + "und noch mehr gute Dinge für dich.");
        assertEquals(1, c.size(), c.toString());
        List<String> d = SpeechChunks.split("Der Händler kommt wieder, und zwar ganz sicher am 17. Tag nach dem "
                + "letzten Vollmond zu uns.");
        assertEquals(1, d.size(), d.toString());
    }

    @Test
    void nothingIsLostAndChunksAreCapped() {
        String text = "Erster Satz ist lang genug für ein Stück. Zweiter Satz ist auch lang genug dafür. Dritter "
                + "Satz ist ebenfalls lang genug, ja. Vierter Satz ist lang genug für ein Stück. Fünfter Satz ist lang "
                + "genug für ein Stück. Sechster Satz.";
        List<String> c = SpeechChunks.split(text);
        assertTrue(c.size() <= SpeechChunks.MAX_CHUNKS, c.toString());
        assertEquals(text.replace(" ", ""), String.join("", c).replace(" ", ""));
    }
}
