package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Bauwerk-Dateien im Chat (2026-11-08): eng wie QuickOrders – Familie plus höchstens eine Nummer, sonst LLM. Namen wie
 * in der index.tsv des Technik-Wiki-Imports.
 */
class SchematicCatalogTest {

    private static SchematicCatalog catalog() {
        List<SchematicCatalog.Entry> e = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            e.add(SchematicCatalog.entry("taktgeber_" + i, "Taktgeber, Variante " + i, "Taktgeber aus der Redstone-Welt",
                    "Technik Wiki"));
        }
        e.add(SchematicCatalog.entry("tuer_und_toranlage_1", "Tür- und Toranlage, Variante 1", "", "")); // umlaut-ok
        e.add(SchematicCatalog.entry("drop_aufzug_1", "Drop-Aufzug, Variante 1", "", ""));
        e.add(SchematicCatalog.entry("meine_burg", null, null, null));
        return new SchematicCatalog(e);
    }

    @Test
    void familyAloneIsVariantOneAndTellsHowManyThereAre() {
        SchematicCatalog.Match m = catalog().match("Baumeister, bau mir einen Taktgeber").orElseThrow();
        assertEquals("taktgeber_1", m.entry().name());
        assertEquals(21, m.variants());
    }

    @Test
    void variantNumberPicksThatOne() {
        assertEquals("taktgeber_3", catalog().match("bau mir einen Taktgeber Variante 3").orElseThrow().entry().name());
        assertEquals("taktgeber_12", catalog().match("Taktgeber Nr. 12").orElseThrow().entry().name());
        assertTrue(catalog().match("Taktgeber Variante 99").isEmpty(), "gibt es nicht → LLM");
        assertTrue(catalog().match("Taktgeber 2 3").isEmpty(), "zwei Nummern sind unklar");
    }

    @Test
    void umlautsHyphensAndJoinedWordsMatch() {
        assertEquals("tuer_und_toranlage_1", catalog().match("bau eine Tür und Toranlage").orElseThrow() // umlaut-ok
                .entry().name());
        assertEquals("drop_aufzug_1", catalog().match("bau mir einen Dropaufzug").orElseThrow().entry().name());
        assertEquals("drop_aufzug_1", catalog().match("bau mir einen Drop-Aufzug").orElseThrow().entry().name());
        assertEquals("meine_burg", catalog().match("meine burg").orElseThrow().entry().name());
    }

    @Test
    void anythingElseGoesToTheLlmWithAHint() {
        SchematicCatalog c = catalog();
        assertTrue(c.match("bau mir ein Haus mit Taktgeber im Keller").isEmpty());
        assertTrue(c.match("bau mir ein Haus").isEmpty());
        String hint = c.hint("bau mir ein Haus mit Taktgeber im Keller", 8);
        assertTrue(hint.contains("taktgeber_1: Taktgeber, Variante 1"), hint);
        assertTrue(hint.contains("taktgeber_3") && !hint.contains("taktgeber_4"), "höchstens drei je Familie");
        assertEquals("", c.hint("bau mir ein Haus", 8));
    }

    @Test
    void familiesAreCountedAndSearchable() {
        SchematicCatalog c = catalog();
        assertEquals(24, c.size());
        assertEquals(List.of(java.util.Map.entry("Taktgeber", 21)), c.families("takt"));
        assertEquals(4, c.families("").size());
        assertFalse(c.families("tuer").isEmpty(), "Suche ohne Umlaut findet Tür"); // umlaut-ok
    }
}
