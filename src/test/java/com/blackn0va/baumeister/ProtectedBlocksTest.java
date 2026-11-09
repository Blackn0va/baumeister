package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Namensliste der geschützten Behälter. Nur isContainerName – hasContents braucht einen laufenden Server und wird auf
 * dem Testserver geprüft (volle Kiste, Bau darüber, data get block).
 */
class ProtectedBlocksTest {

    @Test
    void containersWithInventoryAreProtected() {
        List<String> protectedNames = List.of("chest", "trapped_chest", "barrel", "red_shulker_box", "shulker_box",
                "copper_chest", "waxed_oxidized_copper_chest", "oak_shelf", "furnace", "blast_furnace", "smoker",
                "hopper", "dispenser", "dropper", "brewing_stand", "decorated_pot", "crafter", "lectern", "jukebox",
                "chiseled_bookshelf");
        for (String name : protectedNames) {
            assertTrue(ProtectedBlocks.isContainerName(name), name);
        }
    }

    /**
     * Plan B1: Auch Blöcke, deren Wert in den Blockdaten steckt, überbaut der Baumeister nie – Rückgängig brächte einen
     * leeren Befehlsblock, einen Schweine-Spawner, einen Bienenstock ohne Bienen zurück.
     */
    @Test
    void blocksWhoseValueLivesInTheirDataAreProtectedToo() {
        for (String name : List.of("campfire", "soul_campfire", "beehive", "bee_nest", "spawner", "trial_spawner",
                "vault", "command_block", "chain_command_block", "repeating_command_block")) {
            assertTrue(ProtectedBlocks.isContainerName(name), name);
        }
    }

    @Test
    void namespacedKeyIsRecognisedToo() {
        assertTrue(ProtectedBlocks.isContainerName("minecraft:chest"));
    }

    @Test
    void ordinaryBlocksAndEnderChestAreNotProtected() {
        // Endertruhe: Inhalt gehört dem Spieler, nicht dem Block – Überbauen vernichtet nichts.
        for (String name : List.of("stone", "oak_planks", "glass", "ender_chest", "bookshelf")) {
            assertFalse(ProtectedBlocks.isContainerName(name), name);
        }
        assertFalse(ProtectedBlocks.isContainerName(null));
    }
}
