package com.blackn0va.baumeister.town.use;

import java.util.List;
import java.util.Map;

/**
 * Welche Blöcke Bewohner wie benutzen (Vertrag v013, AK-44) – nur nach Material-Name, ohne Bukkit testbar.
 *
 * - Türen, Tore, Falltüren: alles, was ein Spieler mit der Hand öffnet (Holz und Kupfer). Eisentür und Eisenfalltür
 *   NICHT: Die gehen nur mit Redstone auf, und Redstone schalten Bewohner nie (Nicht-Ziel).
 * - Arbeitsblöcke je Beruf (Roster): dort stehen sie davor, schwingen den Arm, und es klingt wie beim Dorfbewohner
 *   derselben Arbeit (entity.villager.work_*, im Client-Jar 26.2 und in Spigot-Sound 1.21.11 geprüft 2026-11-22).
 *   Behälter (Fass, Ofen, Räucherofen, Schmelzofen, Braustand) sind nur Kulisse: kein Öffnen, kein Anzünden.
 *
 * Threads: zustandslos. Fehler: keine; unbekannte Namen sind „nichts Benutzbares“.
 */
public final class UseBlocks {

    /** Art einer Tür, für das passende Geräusch. */
    public enum DoorKind {
        DOOR("block.wooden_door.open", "block.wooden_door.close"),
        TRAPDOOR("block.wooden_trapdoor.open", "block.wooden_trapdoor.close"),
        GATE("block.fence_gate.open", "block.fence_gate.close");

        private final String openSound;
        private final String closeSound;

        DoorKind(String openSound, String closeSound) {
            this.openSound = openSound;
            this.closeSound = closeSound;
        }

        public String sound(boolean open) {
            return open ? openSound : closeSound;
        }
    }

    /** Wie es an einem Arbeitsblock klingt und aussieht. */
    public record Effect(String sound, String particle) {
    }

    /** Was ein Beruf benutzt, in dieser Vorliebe; Berufe ohne Eintrag nehmen die Werkbank. */
    static final Map<String, List<String>> BY_JOB = Map.ofEntries(
            Map.entry("Bäckerin", List.of("SMOKER", "FURNACE", "CRAFTING_TABLE")),
            Map.entry("Schmied", List.of("ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "BLAST_FURNACE", "SMITHING_TABLE",
                    "GRINDSTONE")),
            Map.entry("Gärtnerin", List.of("COMPOSTER")),
            Map.entry("Fischer", List.of("BARREL")),
            Map.entry("Händlerin", List.of("BELL", "BARREL")),
            Map.entry("Holzfäller", List.of("CRAFTING_TABLE", "GRINDSTONE", "STONECUTTER")),
            Map.entry("Bibliothekarin", List.of("LECTERN")),
            Map.entry("Bauer", List.of("COMPOSTER")),
            Map.entry("Malerin", List.of("LOOM", "CARTOGRAPHY_TABLE")),
            Map.entry("Postbote", List.of("CARTOGRAPHY_TABLE")),
            Map.entry("Ärztin", List.of("BREWING_STAND", "CAULDRON", "WATER_CAULDRON")),
            Map.entry("Musiker", List.of("NOTE_BLOCK", "JUKEBOX")));

    static final List<String> FALLBACK = List.of("CRAFTING_TABLE");

    private static final Map<String, Effect> EFFECTS = Map.ofEntries(
            Map.entry("CRAFTING_TABLE", new Effect("block.wood.hit", "CRIT")),
            Map.entry("SMOKER", new Effect("entity.villager.work_butcher", "SMOKE")),
            Map.entry("FURNACE", new Effect("block.furnace.fire_crackle", "SMOKE")),
            Map.entry("BLAST_FURNACE", new Effect("entity.villager.work_armorer", "SMOKE")),
            Map.entry("ANVIL", new Effect("block.anvil.use", "CRIT")),
            Map.entry("CHIPPED_ANVIL", new Effect("block.anvil.use", "CRIT")),
            Map.entry("DAMAGED_ANVIL", new Effect("block.anvil.use", "CRIT")),
            Map.entry("SMITHING_TABLE", new Effect("entity.villager.work_toolsmith", "CRIT")),
            Map.entry("GRINDSTONE", new Effect("entity.villager.work_weaponsmith", "ELECTRIC_SPARK")),
            Map.entry("STONECUTTER", new Effect("entity.villager.work_mason", "CRIT")),
            Map.entry("COMPOSTER", new Effect("entity.villager.work_farmer", "COMPOSTER")),
            Map.entry("BARREL", new Effect("entity.villager.work_fisherman", "HAPPY_VILLAGER")),
            Map.entry("BELL", new Effect("block.bell.use", "NOTE")),
            Map.entry("LECTERN", new Effect("entity.villager.work_librarian", "ENCHANT")),
            Map.entry("LOOM", new Effect("entity.villager.work_shepherd", "HAPPY_VILLAGER")),
            Map.entry("CARTOGRAPHY_TABLE", new Effect("entity.villager.work_cartographer", "HAPPY_VILLAGER")),
            Map.entry("BREWING_STAND", new Effect("entity.villager.work_cleric", "WITCH")),
            Map.entry("CAULDRON", new Effect("entity.villager.work_leatherworker", "SPLASH")),
            Map.entry("WATER_CAULDRON", new Effect("entity.villager.work_leatherworker", "SPLASH")),
            Map.entry("NOTE_BLOCK", new Effect("block.note_block.harp", "NOTE")),
            Map.entry("JUKEBOX", new Effect("block.note_block.harp", "NOTE")));

    private UseBlocks() {
    }

    /** Tür, Tor oder Falltür, die ein Spieler mit der Hand öffnet; null = keine (auch Eisen). */
    public static DoorKind door(String type) {
        if (type == null || type.startsWith("IRON_")) {
            return null;
        }
        if (type.endsWith("_TRAPDOOR")) {
            return DoorKind.TRAPDOOR;
        }
        if (type.endsWith("_DOOR")) {
            return DoorKind.DOOR;
        }
        if (type.endsWith("_FENCE_GATE")) {
            return DoorKind.GATE;
        }
        return null;
    }

    public static boolean bed(String type) {
        return type != null && type.endsWith("_BED");
    }

    /** Sitz: untere Treppenstufe oder untere Platte (Bank). */
    public static boolean seat(UseWorld.Block b) {
        if (b == null) {
            return false;
        }
        return (b.type().endsWith("_STAIRS") || b.type().endsWith("_SLAB")) && "BOTTOM".equals(b.half());
    }

    /** Arbeitsblöcke eines Berufs, in Vorliebe. */
    public static List<String> workBlocks(String job) {
        return BY_JOB.getOrDefault(job, FALLBACK);
    }

    /** Wirkung eines Arbeitsblocks; null = kein Arbeitsblock. */
    public static Effect effect(String type) {
        return EFFECTS.get(type);
    }

    /**
     * Körperdrehung für das Liegen im Bett mit Kopfende in Richtung facing. Gemessen am Client 26.2 (javap
     * LivingEntityRenderer.setupRotations, 2026-11-22): Ohne Bett-Ausrichtung (Mannequins haben keine) dreht der Renderer
     * den liegenden Körper um die Körperdrehung selbst; mit Bett nimmt er SOUTH 90, WEST 0, NORTH 270, EAST 180. Gleiche
     * Werte → liegt wie ein Spieler im selben Bett.
     */
    public static float sleepYaw(String facing) {
        return switch (facing) {
            case "SOUTH" -> 90f;
            case "NORTH" -> 270f;
            case "EAST" -> 180f;
            default -> 0f;
        };
    }

    /** Blickrichtung (Minecraft-Yaw) beim Sitzen auf einer Treppe: von der hohen Seite (facing) weg. */
    public static float seatYaw(String facing) {
        // Yaw: Süden 0, Westen 90, Norden 180, Osten 270. Die hohe Stufe liegt in facing, man schaut entgegengesetzt.
        return switch (facing) {
            case "NORTH" -> 0f;
            case "SOUTH" -> 180f;
            case "EAST" -> 90f;
            case "WEST" -> 270f;
            default -> 0f;
        };
    }

    /** Schritt in Richtung facing (x, z). */
    static int[] step(String facing) {
        return switch (facing) {
            case "NORTH" -> new int[] {0, -1};
            case "SOUTH" -> new int[] {0, 1};
            case "EAST" -> new int[] {1, 0};
            case "WEST" -> new int[] {-1, 0};
            default -> new int[] {0, 0};
        };
    }
}
