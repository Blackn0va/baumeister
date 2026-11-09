package com.blackn0va.baumeister.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.Pos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

class StructureFileTest {

    /** 20 verschiedene Zustände: Litematica braucht dafür 5 Bit je Eintrag – Einträge laufen über long-Grenzen. */
    static Map<Pos, BlockSpec> sample() throws Exception {
        String[] states = {"stone", "oak_planks", "spruce_planks", "birch_planks", "bricks", "glass", "cobblestone",
            "oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
            "oak_stairs[facing=east,half=top,shape=outer_left,waterlogged=false]", "oak_log[axis=x]",
            "oak_log[axis=z]", "lantern[hanging=false,waterlogged=false]", "white_wool", "red_wool", "blue_wool",
            "sandstone", "quartz_block", "dirt", "gravel", "andesite"};
        Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        Random r = new Random(7);
        for (int x = 0; x < 6; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 5; z++) {
                    if (r.nextInt(4) > 0) {
                        cells.put(new Pos(x, y, z), BlockSpec.parse(states[r.nextInt(states.length)]));
                    }
                }
            }
        }
        cells.put(new Pos(5, 3, 4), BlockSpec.of("stone"));  // äußerste Ecke belegt, Größe eindeutig
        return cells;
    }

    private static StructureFile.Structure read(byte[] b) throws IOException {
        return StructureFile.read(new ByteArrayInputStream(b));
    }

    @Test
    void readsAllFourFormatsToTheSameBlocks() throws Exception {
        Map<Pos, BlockSpec> cells = sample();
        assertEquals("schem-v2", read(NbtWriter.sponge(cells, 2)).format());
        assertEquals(cells, read(NbtWriter.sponge(cells, 2)).cells());
        assertEquals("schem-v3", read(NbtWriter.sponge(cells, 3)).format());
        assertEquals(cells, read(NbtWriter.sponge(cells, 3)).cells());
        assertEquals("litematic", read(NbtWriter.litematic(cells, false)).format());
        assertEquals(cells, read(NbtWriter.litematic(cells, false)).cells());
        assertEquals(cells, read(NbtWriter.litematic(cells, true)).cells(), "negative Region-Größe");
        assertEquals("nbt", read(NbtWriter.structureNbt(cells)).format());
        assertEquals(cells, read(NbtWriter.structureNbt(cells)).cells());
    }

    /** Bitpackung gegen einen zweiten Rechenweg (BigInteger in NbtWriter), für alle Breiten von 2 bis 12. */
    @Test
    void packedBitsCrossLongBoundaries() {
        Random r = new Random(3);
        for (int width = 2; width <= 12; width++) {
            int[] values = new int[301];
            for (int i = 0; i < values.length; i++) {
                values[i] = r.nextInt(1 << width);
            }
            long[] bits = NbtWriter.litematicBits(values, width);
            long mask = (1L << width) - 1;
            for (int i = 0; i < values.length; i++) {
                assertEquals(values[i], StructureFile.packedAt(bits, i, width, mask), "Breite " + width + " Index " + i);
            }
        }
    }

    @Test
    void oldNamesAreUpgraded() throws Exception {
        Map<Pos, BlockSpec> cells = Map.of(new Pos(0, 0, 0), BlockSpec.of("grass_path"), new Pos(1, 0, 0),
                BlockSpec.of("grass"), new Pos(2, 0, 0), BlockSpec.parse("chain[axis=y]"));
        Map<Pos, BlockSpec> got = read(NbtWriter.sponge(cells, 2)).cells();
        assertEquals("dirt_path", got.get(new Pos(0, 0, 0)).id());
        assertEquals("short_grass", got.get(new Pos(1, 0, 0)).id());
        assertEquals("iron_chain", got.get(new Pos(2, 0, 0)).id());
    }

    /**
     * Altes MCEdit-Format (2026-11-08): 9 von 24 echten Server-Schematics. Die Umrechnung ID:Daten → Zustand kommt von
     * außen (im Plugin der Server); hier eine kleine Tabelle. AddBlocks: gerade Stelle unteres, ungerade oberes
     * Halbbyte. Unbekannte Datenwerte fallen auf den Grundzustand, unbekannte IDs werden gezählt.
     */
    @Test
    void legacyMceditIsReadThroughTheServersConversion() throws Exception {
        StructureFile.useLegacy((id, data) -> switch (id << 4 | data) {
            case 1 << 4 -> "minecraft:stone";
            case 35 << 4 | 14 -> "minecraft:red_wool";
            case 35 << 4 -> "minecraft:white_wool";
            case 53 << 4 | 1 -> "minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]";
            case 300 << 4 -> "minecraft:glowstone";
            default -> null;
        });
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Width", (short) 3);
            m.put("Height", (short) 2);
            m.put("Length", (short) 1);
            m.put("Materials", "Alpha");
            // Index (y·Länge + z)·Breite + x: y0 = Stein, Wolle rot, Treppe; y1 = Luft, Wolle mit unbekannten Daten 7,
            // ID 300 über AddBlocks (Stelle 5, ungerade → oberes Halbbyte von AddBlocks[2]).
            m.put("Blocks", new byte[] {1, 35, 53, 0, 35, (byte) 44});
            m.put("Data", new byte[] {0, 14, 1, 0, 7, 0});
            m.put("AddBlocks", new byte[] {0, 0, 0x10});
            StructureFile.Structure s = read(NbtWriter.gzip(m));
            assertEquals("schematic-mcedit", s.format());
            assertEquals("stone", s.cells().get(new Pos(0, 0, 0)).id());
            assertEquals("red_wool", s.cells().get(new Pos(1, 0, 0)).id());
            assertEquals("west", s.cells().get(new Pos(2, 0, 0)).props().get("facing"));
            assertEquals("white_wool", s.cells().get(new Pos(1, 1, 0)).id(), "Daten 7 unbekannt → Grundzustand");
            assertEquals("glowstone", s.cells().get(new Pos(2, 1, 0)).id(), "ID 300 = 44 + (1 << 8)");
            assertEquals(5, s.cells().size(), "Luft fällt weg");
        } finally {
            StructureFile.useLegacy(null);
        }
    }

    @Test
    void legacyAndBrokenFilesFailWithAReason() throws Exception {
        Map<String, Object> mcedit = new LinkedHashMap<>();
        mcedit.put("Width", (short) 1);
        mcedit.put("Blocks", new byte[] {1});
        mcedit.put("Materials", "Alpha");
        StructureFile.useLegacy(null);
        IOException e = assertThrows(IOException.class, () -> read(NbtWriter.gzip(mcedit)));
        assertTrue(e.getMessage().contains("MCEdit"), e.getMessage());

        // 2026-11-08: Grenze 512 statt 256 (bedwars.schem 327 × 140 × 327 vom eigenen Server war unlesbar).
        Map<String, Object> huge = new LinkedHashMap<>();
        huge.put("Version", 2);
        huge.put("Width", (short) 600);
        huge.put("Height", (short) 1);
        huge.put("Length", (short) 1);
        huge.put("Palette", Map.of("minecraft:air", 0));
        huge.put("BlockData", new byte[600]);
        assertThrows(IOException.class, () -> read(NbtWriter.gzip(huge)));

        Map<String, Object> shortData = new LinkedHashMap<>();
        shortData.put("Version", 2);
        shortData.put("Width", (short) 2);
        shortData.put("Height", (short) 2);
        shortData.put("Length", (short) 2);
        shortData.put("Palette", Map.of("minecraft:air", 0));
        shortData.put("BlockData", new byte[5]);
        IOException e2 = assertThrows(IOException.class, () -> read(NbtWriter.gzip(shortData)));
        assertTrue(e2.getMessage().contains("5 statt 8"), e2.getMessage());
    }

    @Test
    void nbtRejectsNegativeLengthsAndDeepNesting() throws Exception {
        // Byte-Array mit Länge −1: Compound(10) "" { ByteArray(7) "a" len=-1 }
        byte[] neg = {10, 0, 0, 7, 0, 1, 'a', -1, -1, -1, -1, 0};
        assertThrows(IOException.class, () -> Nbt.read(new ByteArrayInputStream(neg)));
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cur = deep;
        for (int i = 0; i < Nbt.MAX_DEPTH + 5; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cur.put("x", next);
            cur = next;
        }
        assertThrows(IOException.class, () -> Nbt.read(new ByteArrayInputStream(NbtWriter.gzip(deep))));
    }

    /**
     * Drehung: dasselbe Bauwerk, einmal mit Vorderseite Süden, einmal Osten gespeichert, ergibt lokal dieselben Blöcke
     * (Treppe zeigt in beiden Fällen nach vorne).
     */
    @Test
    void frontSideTurnsTheBuildIntoLocalCoordinates() throws Exception {
        Map<Pos, BlockSpec> local = new HashMap<>();
        local.put(new Pos(0, 0, 0), BlockSpec.parse("oak_stairs[facing=front]"));
        local.put(new Pos(2, 0, 0), BlockSpec.of("stone"));
        local.put(new Pos(0, 1, 3), BlockSpec.parse("oak_log[axis=x]"));
        for (Cardinal front : List.of(Cardinal.SOUTH, Cardinal.EAST, Cardinal.NORTH, Cardinal.WEST)) {
            Orientation o = new Orientation(front.opposite());
            Map<Pos, BlockSpec> world = new HashMap<>();
            for (Map.Entry<Pos, BlockSpec> e : local.entrySet()) {
                world.put(o.toWorldOffset(e.getKey()), o.toWorld(e.getValue()));
            }
            StructureFile.Structure s = new StructureFile.Structure("test", world, 3, 0, Map.of());
            assertEquals(local, StructureFile.toLocal(s, front), "Vorderseite " + front);
        }
    }
}
