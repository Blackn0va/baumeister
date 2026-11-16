package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.TunnelNature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.bukkit.World;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.generator.structure.StructurePiece;
import org.bukkit.util.BoundingBox;

/**
 * Teile der Bauwerke, die die Welt erzeugt hat und durch die ein Tunnel graben darf (TunnelNature.diggableStructure,
 * Wunsch 2026-11-16: „er wird irgendwann durch ca. die komplette Karte gehen“) – je Chunk einmal gelesen.
 *
 * Gemessen statt angenommen (javap auf spigot-1.21.11 und paper-26.2, 2026-11-16): StructurePiece.getBoundingBox
 * reicht minX…maxZ der Vanilla-BoundingBox unverändert durch, das Maximum zählt also mit (einschließlich). Darum hier
 * ein eigener Vergleich mit ≤ statt BoundingBox.contains, das das Maximum ausschließt – sonst fiele die letzte Reihe
 * eines Schachtteils heraus. Nur Teile (Gang, Kreuzung, Raum), nicht der Umriss des ganzen Bauwerks: Der umschließt
 * bei einem Mineschacht Hunderte Blöcke Fels und womöglich die Basis eines Spielers.
 *
 * Threads: Hauptthread (World.getStructures lädt den Chunk). Fehler: keine.
 */
final class WorldStructures {

    /** Teil eines Bauwerks, Grenzen einschließlich. */
    record Box(String structure, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        boolean touchesChunk(int cx, int cz) {
            int x0 = cx << 4;
            int z0 = cz << 4;
            return maxX >= x0 && minX <= x0 + 15 && maxZ >= z0 && minZ <= z0 + 15;
        }
    }

    /** Teile im Chunk (cx, cz), durch die gegraben werden darf. */
    private final BiFunction<Integer, Integer, List<Box>> source;
    private final Map<Long, List<Box>> chunks = new HashMap<>();

    WorldStructures(World world) {
        this((cx, cz) -> read(world, cx, cz));
    }

    /** Für Tests: Teile ohne Welt. */
    WorldStructures(BiFunction<Integer, Integer, List<Box>> source) {
        this.source = source;
    }

    /** Liegt die Stelle in einem Teil eines Bauwerks, durch das gegraben werden darf? Dann dessen Schlüssel. */
    String diggableAt(int x, int y, int z) {
        for (Box box : chunks.computeIfAbsent(ChunkPrefetch.key(x >> 4, z >> 4), k -> source.apply(x >> 4, z >> 4))) {
            if (box.contains(x, y, z)) {
                return box.structure();
            }
        }
        return null;
    }

    private static List<Box> read(World world, int cx, int cz) {
        List<Box> out = new ArrayList<>();
        for (GeneratedStructure s : world.getStructures(cx, cz)) {
            String key = s.getStructure().getKey().getKey();
            if (!TunnelNature.diggableStructure(key)) {
                continue;
            }
            for (StructurePiece piece : s.getPieces()) {
                BoundingBox bb = piece.getBoundingBox();
                Box box = new Box(key, (int) bb.getMinX(), (int) bb.getMinY(), (int) bb.getMinZ(),
                        (int) bb.getMaxX(), (int) bb.getMaxY(), (int) bb.getMaxZ());
                // Nur Teile, die diesen Chunk berühren – ein Mineschacht hat Hunderte.
                if (box.touchesChunk(cx, cz)) {
                    out.add(box);
                }
            }
        }
        return out;
    }
}
