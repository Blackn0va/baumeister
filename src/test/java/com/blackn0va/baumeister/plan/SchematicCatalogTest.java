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

    /** Stichwort (2026-11-15): trifft genau eine Herkunft, auch wenn das Wort in fremden Herkunftsspalten steckt. */
    @Test
    void tagListsExactlyOneSourceEvenIfTheWordAppearsElsewhere() {
        List<SchematicCatalog.Entry> entries = List.of(
                SchematicCatalog.entry("iglu_1", "Iglu, Variante 1", "", "Minecraft, auf diesem Server erzeugt"),
                SchematicCatalog.entry("taktgeber_1", "Taktgeber, Variante 1", "",
                        "Technik Wiki (de.minecraft.wiki), Redstone-Welt 1.21a, CC BY-NC-SA 3.0"),
                SchematicCatalog.entry("glockenspiel_1", "Glockenspiel, Variante 1", "", "Eigenbau"));
        SchematicCatalog c = new SchematicCatalog(entries, java.util.Map.of(),
                java.util.Map.of("spiel", "Minecraft, auf diesem Server erzeugt"));
        assertEquals(List.of(java.util.Map.entry("Iglu", 1)), c.families("spiel"),
                "Stichwort: nur die genaue Herkunft, nicht Glockenspiel");
        assertEquals(List.of(java.util.Map.entry("Iglu", 1)), c.families("Spiel"));
        assertEquals(2, c.families("minecraft").size(), "freie Suche trifft auch die Technik-Wiki-Herkunft");
        assertEquals(List.of(java.util.Map.entry("Glockenspiel", 1)), new SchematicCatalog(entries).families("spiel"),
                "ohne Stichwort bleibt es eine Namenssuche");
    }

    /** Zweitnamen (2026-11-15): offizielle Namen und Einzahl treffen die Familie, der eigene Name geht vor. */
    @Test
    void aliasesFindTheFamilyButNeverShadowARealOne() {
        List<SchematicCatalog.Entry> entries = List.of(
                SchematicCatalog.entry("hexenhuette_1", "Hexenhütte, Variante 1", "", "Spiel"),
                SchematicCatalog.entry("hexenhuette_2", "Hexenhütte, Variante 2", "", "Spiel"),
                SchematicCatalog.entry("taktgeber_1", "Taktgeber, Variante 1", "", "Wiki"));
        SchematicCatalog c = new SchematicCatalog(entries, java.util.Map.of(
                "Hexenhütte", List.of("Sumpfhütte"),
                "Unbekannt", List.of("Geist"),
                "Taktgeber", List.of("Hexenhütte")));
        assertEquals("hexenhuette_1", c.match("bau mir eine Sumpfhütte").orElseThrow().entry().name());
        assertEquals("hexenhuette_2", c.match("bau mir eine Sumpfhütte Variante 2").orElseThrow().entry().name());
        assertEquals(2, c.match("bau mir eine Sumpfhütte").orElseThrow().variants());
        assertEquals("hexenhuette_1", c.match("bau mir eine Hexenhütte").orElseThrow().entry().name(),
                "ein Zweitname, der selbst eine Familie ist, zählt nicht");
        assertTrue(c.match("bau mir einen Geist").isEmpty(), "Zweitname ohne Familie");
        assertEquals(List.of(java.util.Map.entry("Hexenhütte", 2)), c.families("sumpf"));
        assertEquals(2, c.variants("Sumpfhütte").size());
        assertTrue(c.hint("ein Garten mit einer Sumpfhütte", 8).contains("hexenhuette_1"));
        assertEquals(2, c.families("").size(), "Zweitnamen zählen nicht als eigene Familie");
        assertEquals(List.of(java.util.Map.entry("Hexenhütte", 2)), c.families("spiel"), "Suche nach Herkunft");
        assertEquals(List.of(java.util.Map.entry("Taktgeber", 1)), c.families("wiki"));
    }
}
