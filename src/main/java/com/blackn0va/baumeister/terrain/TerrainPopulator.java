package com.blackn0va.baumeister.terrain;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

/**
 * Füllt die hohe Datapack-Dimension (y −2032..2031, leerer Flachgenerator) mit echtem Gelände 1:1.
 *
 * Warum ein Populator: Spigot gibt einer Datapack-Dimension einen Plugin-Generator nur über bukkit.yml, und das nur,
 * wenn das Plugin mit load: STARTUP vor den Welten startet – dann gingen Stadtwelt und NPC-Suche in onEnable nicht
 * mehr. WorldCreator wiederum kennt nur Oberwelt/Nether/End mit 384 Blöcken (gemessen 2026-10-28: Bytecode von
 * CraftServer.createWorld, und in der Dimension ging setblock bei y 2031, bei 2032 „out of this world“). Ein
 * Populator hängt sich zur Laufzeit an jede Welt.
 *
 * Mehrere Orte in einer Welt: Ort n liegt mit seiner Mitte bei x = n · SLOT_BLOCKS, z = 0. Ein Ort hat höchstens
 * 25 km Radius, die Plätze liegen 64 km auseinander. Chunks außerhalb eines Platzes bleiben leer (Luft).
 *
 * Threads: populate läuft auf den Weltgenerator-Threads; die Platztabelle ist eine ConcurrentHashMap.
 */
public final class TerrainPopulator extends BlockPopulator {

    /** Abstand der Ortsmitten in Blöcken (Vielfaches von 16, > 2 · 25 000). */
    public static final int SLOT_BLOCKS = 65_536;

    /** Ein Ort: Generator mit Ort-Koordinaten, Grundgestein und Kantenlänge. */
    public record Slot(DemGenerator gen, int floorY, int radius) {
    }

    private final Map<Integer, Slot> slots = new ConcurrentHashMap<>();

    public void put(int slot, Slot s) {
        slots.put(slot, s);
    }

    public boolean has(int slot) {
        return slots.containsKey(slot);
    }

    /** Platz, in dessen Bereich die Block-x-Koordinate fällt. */
    public static int slotOf(int blockX) {
        return Math.floorDiv(blockX + SLOT_BLOCKS / 2, SLOT_BLOCKS);
    }

    @Override
    public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        int slot = slotOf(chunkX * 16 + 8);
        Slot s = slots.get(slot);
        if (s == null) {
            return;
        }
        int localChunkX = chunkX - slot * (SLOT_BLOCKS / 16);
        // Nur Chunks, die das Gebiet berühren; dahinter bleibt Luft (Kacheln gibt es dort nicht).
        int x0 = localChunkX * 16;
        int z0 = chunkZ * 16;
        if (x0 > s.radius() || x0 + 15 < -s.radius() || z0 > s.radius() || z0 + 15 < -s.radius()) {
            return;
        }
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        s.gen().paint(localChunkX, chunkZ, s.floorY(), info.getMaxHeight(), new DemGenerator.Sink() {
            @Override
            public void set(int x, int y, int z, Material m) {
                region.setType(baseX + x, y, baseZ + z, m);
            }

            @Override
            public void fill(int x, int y0, int z, int y1, Material m) {
                for (int y = y0; y < y1; y++) {
                    region.setType(baseX + x, y, baseZ + z, m);
                }
            }
        });
    }
}
