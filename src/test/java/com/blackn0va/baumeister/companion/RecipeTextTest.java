package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Rezepte aus den Serverdaten als Satz – Wunsch 2026-11-04. */
class RecipeTextTest {

    @Test
    void shapedRecipeNamesCountsAndLayout() {
        // Kolben: drei Bretter oben, Bruchstein–Eisen–Bruchstein, Bruchstein–Redstone–Bruchstein
        String s = RecipeText.shaped("piston", 1, List.of("PPP", "CIC", "CRC"), Map.of('P', "beliebige planks", 'C',
                "cobblestone", 'I', "iron_ingot", 'R', "redstone"));
        assertEquals("Für 1 piston brauchst du 3 beliebige planks, 4 cobblestone, 1 iron ingot und 1 redstone. Leg sie "
                + "so in die Werkbank – oben: beliebige planks, beliebige planks, beliebige planks; Mitte: "
                + "cobblestone, iron ingot, cobblestone; unten: cobblestone, redstone, cobblestone.", s);
    }

    @Test
    void emptySlotsAndTwoRows() {
        String s = RecipeText.shaped("torch", 4, List.of("C", "S"), Map.of('C', "coal", 'S', "stick"));
        assertEquals("Für 4 torch brauchst du 1 coal und 1 stick. Leg sie so in die Werkbank – oben: coal; unten: "
                + "stick.", s);
        String bowl = RecipeText.shaped("bowl", 4, List.of("P P", " P "), Map.of('P', "beliebige planks"));
        assertEquals("Für 4 bowl brauchst du 3 beliebige planks. Leg sie so in die Werkbank – oben: beliebige planks, "
                + "frei, beliebige planks; unten: frei, beliebige planks, frei.", bowl);
    }

    @Test
    void shapelessProcessedAndUncraftable() {
        assertEquals("Für 1 fire charge brauchst du 1 gunpowder, 1 blaze powder und 1 coal oder Ähnliches, egal in "
                + "welcher Anordnung.", RecipeText.shapeless("fire_charge", 1, List.of("gunpowder", "blaze_powder",
                        RecipeText.choiceName(List.of("coal", "charcoal")))));
        assertEquals("Für 1 iron ingot legst du raw iron in einen Ofen.",
                RecipeText.processed("minecraft:iron_ingot", 1, "raw_iron", "einen Ofen"));
        assertEquals("diamond kann man nicht herstellen – das findet, erbeutet oder ertauscht man.",
                RecipeText.uncraftable("diamond"));
        assertEquals("beliebige planks", RecipeText.choiceName(List.of("oak_planks", "birch_planks", "cherry_planks")));
        assertEquals("redstone", RecipeText.choiceName(List.of("redstone")));
    }
}
