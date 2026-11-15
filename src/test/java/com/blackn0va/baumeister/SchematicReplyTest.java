package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.SchematicCatalog;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Was der Baumeister bei einem Katalogbau laut sagt und was nur im Chat steht (2026-11-15: „nervig und unnötig“). */
class SchematicReplyTest {

    private static final String WIKI = "Technik Wiki (de.minecraft.wiki), Redstone-Welt 1.21a, CC BY-NC-SA 3.0";

    private static SchematicCatalog.Match match(String prompt) {
        SchematicCatalog c = new SchematicCatalog(List.of(
                SchematicCatalog.entry("taktgeber_1", "Taktgeber, Variante 1", "", WIKI),
                SchematicCatalog.entry("taktgeber_2", "Taktgeber, Variante 2", "", WIKI),
                SchematicCatalog.entry("taktgeber_3", "Taktgeber, Variante 3", "", WIKI),
                SchematicCatalog.entry("iglu_1", "Iglu, Variante 1", "", "")));
        return c.match(prompt).orElseThrow();
    }

    @Test
    void spokenConfirmationIsShortWithoutSourceOrInstructions() {
        String said = BuildService.schematicReply(match("bau mir einen Taktgeber"));
        assertEquals("Wird gemacht: Taktgeber, Variante 1.", said);
        assertFalse(said.contains("Quelle") || said.contains("Wiki") || said.contains("Schematic"), said);
        assertEquals("Wird gemacht: Iglu.", BuildService.schematicReply(match("bau mir ein Iglu")),
                "ohne Varianten keine Nummer");
    }

    @Test
    void detailsForTheChatNameSourceAndTheNextVariant() {
        String chat = BuildService.schematicDetails(match("bau mir einen Taktgeber 3"));
        assertTrue(chat.contains("3 Varianten") && chat.contains("„Taktgeber Variante 1“"), chat);
        assertTrue(chat.contains("Quelle: " + WIKI), chat);
        assertEquals("", BuildService.schematicDetails(match("bau mir ein Iglu")), "nichts zu sagen");
    }
}
