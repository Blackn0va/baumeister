package com.blackn0va.baumeister;

import java.util.Set;

import org.bukkit.block.Beehive;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Jukebox;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.BlockInventoryHolder;

/**
 * Blöcke, die der Baumeister nie überbaut: Behälter mit Inhalt – und seit 2026-11-08 (Plan B1) auch Lagerfeuer mit
 * Essen, Bienenstöcke mit Bienen, Spawner, Prüfungsspawner, Tresore und Befehlsblöcke mit Befehl. Bei allen steckt der
 * Wert in den Blockdaten, die Rückgängig nicht sichert: Ein überbauter Befehlsblock käme leer zurück, ein Spawner als
 * Schweine-Spawner, Bienen wären weg.
 *
 * Warum: Rückgängig (BuildJob.originals, UndoSpool) sichert nur BlockData, nie den Inhalt eines Blockobjekts. Wer eine
 * volle Kiste überbaut und danach /bm rückgängig ruft, bekommt eine leere Kiste zurück – die Gegenstände sind weg.
 * Ein Bau darf deshalb an so einer Stelle gar nicht erst ansetzen.
 *
 * Erkannt wird zuerst nur über den Namen (billig, ohne Server testbar); getState() kostet ein Blockobjekt-Abbild und
 * läuft deshalb nur bei einem Namenstreffer. Endertruhe fehlt bewusst: ihr Inhalt gehört dem Spieler, nicht dem
 * Block, und bleibt beim Überbauen erhalten.
 *
 * Threads: nur Hauptthread (getState). Fehler: keine eigenen; unbekannte Blockobjekte gelten als voll (fail-closed).
 */
final class ProtectedBlocks {

    private static final Set<String> NAMES = Set.of("chest", "trapped_chest", "barrel", "furnace", "blast_furnace",
            "smoker", "hopper", "dispenser", "dropper", "brewing_stand", "decorated_pot", "crafter", "lectern",
            "jukebox", "chiseled_bookshelf", "campfire", "soul_campfire", "beehive", "bee_nest", "spawner",
            "trial_spawner", "vault", "command_block", "chain_command_block", "repeating_command_block");

    /** Immer geschützt: Ihr Zustand (Wellen, Beute, Abklingzeit) steckt ganz in den Blockdaten. */
    private static final Set<String> ALWAYS = Set.of("trial_spawner", "vault");

    private ProtectedBlocks() {
    }

    /** key ohne oder mit „minecraft:“, z. B. „red_shulker_box“. Rein über Strings, damit es ohne Server testbar ist. */
    static boolean isContainerName(String key) {
        if (key == null) {
            return false;
        }
        String k = key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
        // Alle 16 Farben plus die ungefärbte; copper_chest samt exposed_/weathered_/oxidized_ und waxed_-Varianten;
        // *_shelf sind die Regale aus 1.21.9 (oak_shelf …), die drei Gegenstände halten. „bookshelf“ endet nicht auf
        // „_shelf“ und bleibt frei – es hat keinen Inhalt.
        return NAMES.contains(k) || k.endsWith("shulker_box") || k.endsWith("copper_chest") || k.endsWith("_shelf");
    }

    /**
     * true, wenn an b ein Behälter steht, der etwas enthält. before ist b.getBlockData() des Aufrufers (liegt dort
     * schon vor, spart den zweiten Zugriff).
     *
     * Doppelkiste: getSnapshotInventory() liefert nur die Hälfte an b, nicht beide. Das ist gewollt – wird nur die leere
     * Hälfte ersetzt, wird die andere zur Einzelkiste und behält ihren Inhalt; die volle Hälfte schützt ihr eigener
     * Aufruf. Wer hier getInventory() nimmt, sperrt auch die leere Hälfte, sobald die andere etwas enthält.
     */
    static boolean hasContents(Block b, BlockData before) {
        String key = before.getMaterial().getKey().getKey();
        if (!isContainerName(key)) {
            return false;
        }
        if (ALWAYS.contains(key)) {
            return true;
        }
        BlockState state = b.getState();
        if (state instanceof CommandBlock cb) {
            return cb.getCommand() != null && !cb.getCommand().isBlank();
        }
        if (state instanceof CreatureSpawner s) {
            return s.getSpawnedType() != null;
        }
        if (state instanceof Beehive h) {
            return h.getEntityCount() > 0;
        }
        if (state instanceof Campfire f) {
            for (int i = 0; i < f.getSize(); i++) {
                if (f.getItem(i) != null && !f.getItem(i).getType().isAir()) {
                    return true;
                }
            }
            return false;
        }
        if (state instanceof Container c) {
            return !c.getSnapshotInventory().isEmpty();
        }
        if (state instanceof Jukebox j) {
            return j.hasRecord();
        }
        if (state instanceof BlockInventoryHolder h) {
            return !h.getInventory().isEmpty();
        }
        // Name passt, Blockobjekt nicht (andere Serverfassung): als voll behandeln. fail-closed – lieber eine Stelle
        // auslassen als Gegenstände vernichten.
        return true;
    }
}
