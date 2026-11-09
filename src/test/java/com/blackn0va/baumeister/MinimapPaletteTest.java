package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Die Minikarte rechnet Blockfarben (BlockData#getMapColor, Vanilla-Grundfarbe) über 220/255 in die Palette um und
 * nimmt den Eintrag als Grundfarbe × 4 + 1. Das stimmt nur, wenn die Umrechnung den Vanilla-Eintrag GENAU trifft –
 * sonst landen Farben bei einer Nachbarfarbe oder falschen Helligkeit, und die Schattierung (× 4 + 0/2) kippt.
 */
class MinimapPaletteTest {

    @Test
    void blockColorsHitTheirVanillaPaletteEntryAtNormalBrightness() {
        // Vanilla-MapColor: Id und Grundfarbe (net.minecraft.world.level.material.MapColor).
        int[][] colors = {
            {1, 0x7FB238},   // GRASS
            {8, 0xFFFFFF},   // SNOW
            {11, 0x707070},  // STONE
            {12, 0x4040FF},  // WATER
            {13, 0x8F7748},  // WOOD
            {28, 0x993333},  // COLOR_RED
            {29, 0x191919},  // COLOR_BLACK
        };
        for (int[] c : colors) {
            int rgb = c[1];
            int b = Minimap.palette(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF) & 0xFF;
            assertEquals(c[0] * 4 + 1, b, "Grundfarbe " + c[0] + " (" + Integer.toHexString(rgb) + ")");
        }
    }
}
