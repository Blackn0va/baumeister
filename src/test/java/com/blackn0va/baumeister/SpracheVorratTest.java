package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Vorrat fester Sätze (2026-11-08): aktuell zum Quelltext, nur ganze Sätze, keine Bruchstücke. */
class SpracheVorratTest {

    @Test
    void resourceMatchesTheSource() throws Exception {
        String want = SpracheVorrat.render(SpracheVorrat.collect(Path.of("src/main/java")));
        String have = Files.readString(Path.of(SpracheVorrat.RESOURCE), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(want, have, "sprache-vorrat.txt veraltet – mit SpracheVorrat neu erzeugen (Aufruf im Kopf)");
    }

    @Test
    void onlyStandaloneSentencesOutsideComments() {
        String src = """
                // say(b, p, "Kommentar mit Satz.");
                say(b, p, "Hallo, ich bin da!");
                say(b, p, "Fertig! Es waren " + n + " Blöcke.");
                LOG.info("bau | fertig | blöcke=3");
                Texts.say(npc.name(), loc, null, "Ich fange sofort an!");
                String id = "oak_planks";
                """;
        List<SpracheVorrat.Literal> l = SpracheVorrat.literals(src).stream()
                .filter(x -> SpracheVorrat.sentence(x.text())).toList();
        assertEquals(List.of("Hallo, ich bin da!", "Ich fange sofort an!"), l.stream()
                .map(SpracheVorrat.Literal::text).toList());
        assertFalse(l.get(0).inTextsSay());
        assertTrue(l.get(1).inTextsSay());
    }

    @Test
    void speechTextFixesWhatPiperReadsWrong() {
        assertEquals("Pass auf, ein Krieper und zwei Spohner im Tschank!",
                SpeechText.forSpeech("Pass auf, ein Creeper und zwei Spawner im Chunk!"));
        assertEquals("circa 20 Blöcke, zum Beispiel Rädstohn.", SpeechText.forSpeech("ca. 20 Blöcke, z. B. Redstone."));
        assertEquals("Um 6 Uhr, 16 Fackeln, 3 mal 4.", SpeechText.forSpeech("Um 6:00 Uhr, 16x Fackeln, 3×4."));
        assertEquals("Fertig, 2665 Blöcke.", SpeechText.forSpeech("✔ Fertig → 2.665 Blöcke."));
        assertEquals("Hallo Blackknova!", SpeechText.forSpeech("Hallo Blackkn0va!"));
        assertEquals("Näther und Nethereit.", SpeechText.forSpeech("Nether und Netherite."));
        assertEquals("10 bis 15, 5 minus 3", SpeechText.forSpeech("10-15, 5 - 3"));
    }
}
