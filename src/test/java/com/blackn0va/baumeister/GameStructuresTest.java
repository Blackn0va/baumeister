package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.SchematicCatalog;
import com.blackn0va.baumeister.structure.Nbt;
import com.blackn0va.baumeister.structure.NbtOut;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bauwerke des Spiels als Vorlagen (2026-11-15): Namen, index.tsv, Nummern. */
class GameStructuresTest {

    @Test
    void everyFileNameIsAValidCatalogName() {
        for (String stem : GameStructures.stems()) {
            assertTrue(SchematicCatalog.NAME.matcher(stem).matches(), stem);
        }
        assertEquals("wuestentempel_3", GameStructures.fileStem("Wüstentempel", 3));
        assertEquals("pluenderer_aussenposten_1", GameStructures.fileStem("Plünderer-Außenposten", 1)); // umlaut-ok
    }

    @Test
    void indexLineFeedsTheCatalogWithFamilyAndVariant() {
        String line = GameStructures.indexLine("hexenhuette_2.nbt", "hexenhuette_2", "Hexenhütte", 2,
                "Hütte\tauf\nStelzen");
        String[] cols = line.strip().split("\t");
        assertEquals(5, cols.length, line);
        assertEquals("Hexenhütte, Variante 2", cols[2]);
        SchematicCatalog.Entry e = SchematicCatalog.entry(cols[1], cols[2], cols[3], cols[4]);
        assertEquals("Hexenhütte", e.display().replaceAll(",? *Variante.*", ""));
        // Ohne Nummer nimmt der Katalog Variante 1 – hier ist es die 2.
        assertTrue(new SchematicCatalog(List.of(e)).match("bau mir eine Hexenhütte 2").isPresent());
    }

    /** Flachwelt -64..-61 (Grundgestein, 2× Erde, Gras), darüber Luft; abweichende Zellen nach Weltkoordinate. */
    private static ChunkSnapshot chunk(int cx, int cz, Map<String, Material> changed) {
        return (ChunkSnapshot) Proxy.newProxyInstance(ChunkSnapshot.class.getClassLoader(),
                new Class<?>[] {ChunkSnapshot.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "getX" -> cx;
                    case "getZ" -> cz;
                    case "isSectionEmpty" -> {
                        int s = (Integer) a[0];
                        boolean any = changed.keySet().stream().anyMatch(k -> {
                            String[] p = k.split(",");
                            int x = Integer.parseInt(p[0]);
                            int y = Integer.parseInt(p[1]);
                            int z = Integer.parseInt(p[2]);
                            return x >> 4 == cx && z >> 4 == cz && (y + 64) / 16 == s;
                        });
                        yield s != 0 && !any;
                    }
                    case "getBlockType" -> {
                        int y = (Integer) a[1];
                        String key = ((cx << 4) + (Integer) a[0]) + "," + y + "," + ((cz << 4) + (Integer) a[2]);
                        yield changed.getOrDefault(key, FLAT[y + 64]);
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static final Material[] FLAT = new Material[384];

    static {
        Arrays.fill(FLAT, Material.AIR);
        FLAT[0] = Material.BEDROCK;
        FLAT[1] = Material.DIRT;
        FLAT[2] = Material.DIRT;
        FLAT[3] = Material.GRASS_BLOCK;
    }

    @Test
    void boundsCoverEveryChangedBlockAcrossChunksAndBelowTheSurface() {
        // Wüstentempel-artig: Dach oben, Fallgrube bis ins Grundgestein, über zwei Chunks verteilt.
        Map<String, Material> a = Map.of("5,10,7", Material.SANDSTONE);
        Map<String, Material> b = Map.of("20,-64,3", Material.AIR, "17,-61,-2", Material.TNT);
        List<ChunkSnapshot> snaps = List.of(chunk(0, 0, a), chunk(1, 0, b), chunk(1, -1, Map.of("17,-61,-2",
                Material.TNT)), chunk(5, 5, Map.of()));
        int[] box = GameStructures.changedBounds(snaps, FLAT, -64);
        assertArrayEquals(new int[] {5, -64, -2, 20, 10, 7}, box);
    }

    @Test
    void maskKeepsChangedCellsAndAirButNotUntouchedGround() {
        // Quader x 0..1, y -63..-60, z 0..0: Erde -63 unberührt, -62 zu Luft gegraben, Gras -61 zu Sandstein, Luft -60.
        Map<String, Material> changed = Map.of("1,-62,0", Material.AIR, "0,-61,0", Material.SANDSTONE);
        int[] b = {0, -63, 0, 1, -60, 0};
        BitSet keep = GameStructures.keepMask(List.of(chunk(0, 0, changed)), FLAT, -64, b);
        int sx = 2;
        int sz = 1;
        assertFalse(keep.get(GameStructures.cell(0, 0, 0, sx, sz)), "unberührte Erde");
        assertFalse(keep.get(GameStructures.cell(0, 1, 0, sx, sz)), "unberührte Erde");
        assertTrue(keep.get(GameStructures.cell(1, 1, 0, sx, sz)), "ausgegrabene Luft");
        assertTrue(keep.get(GameStructures.cell(0, 2, 0, sx, sz)), "Sandstein statt Gras");
        assertFalse(keep.get(GameStructures.cell(1, 2, 0, sx, sz)), "unberührtes Gras");
        assertTrue(keep.get(GameStructures.cell(0, 3, 0, sx, sz)), "Luft bleibt, damit der Innenraum frei wird");
        assertEquals(4, keep.cardinality());
    }

    @Test
    void pruneDropsOnlyMaskedOutBlocksAndKeepsTheRest(@TempDir Path dir) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("size", List.of(2, 1, 1));
        root.put("palette", List.of(Map.of("Name", "minecraft:stone"), Map.of("Name", "minecraft:chest")));
        Map<String, Object> chest = new LinkedHashMap<>();
        chest.put("pos", List.of(1, 0, 0));
        chest.put("state", 1);
        chest.put("nbt", Map.of("LootTable", "minecraft:chests/desert_pyramid", "Seed", 7L, "f", 0.5f));
        root.put("blocks", List.of(Map.of("pos", List.of(0, 0, 0), "state", 0), chest));
        root.put("entities", List.of());
        Path file = dir.resolve("t.nbt");
        try (var out = Files.newOutputStream(file)) {
            NbtOut.writeGzip(root, out);
        }
        BitSet keep = new BitSet();
        keep.set(GameStructures.cell(1, 0, 0, 2, 1));
        assertEquals(1, GameStructures.prune(file, keep, 2, 1, 1));
        Map<String, Object> back;
        try (var in = Files.newInputStream(file)) {
            back = Nbt.read(in);
        }
        assertEquals(List.of(chest), back.get("blocks"));
        assertEquals(root.get("palette"), back.get("palette"));
        assertEquals(List.of(), back.get("entities"));
        assertFalse(Files.exists(dir.resolve("t.nbt.neu")));
    }

    @Test
    void untouchedFlatWorldHasNoBounds() {
        assertNull(GameStructures.changedBounds(List.of(chunk(0, 0, Map.of()), chunk(3, -2, Map.of())), FLAT, -64));
    }

    @Test
    void flatWorldHasItsSurfaceAt63WithStoneBelow() {
        JsonObject o = JsonParser.parseString(GameStructures.FLAT_SETTINGS).getAsJsonObject();
        int height = 0;
        for (var layer : o.getAsJsonArray("layers")) {
            height += layer.getAsJsonObject().get("height").getAsInt();
        }
        assertEquals(63, -64 + height - 1);
        assertEquals("minecraft:stone", o.getAsJsonArray("layers").get(1).getAsJsonObject().get("block").getAsString());
        assertEquals(0, o.getAsJsonArray("structure_overrides").size());
    }

    @Test
    void onlyGameStructuresGoBeforeTheTemplateCatalog() {
        String[] game = GameStructures.indexLine("wiesendorf_1.nbt", "wiesendorf_1", "Wiesendorf", 1, "ganzes Dorf")
                .strip().split("\t");
        SchematicCatalog.Entry village = SchematicCatalog.entry(game[1], game[2], game[3], game[4]);
        SchematicCatalog.Entry clock = SchematicCatalog.entry("taktgeber_1", "Taktgeber, Variante 1", "Uhr",
                "Technik-Wiki");
        SchematicCatalog catalog = new SchematicCatalog(List.of(village, clock));
        assertTrue(GameStructures.preferred(catalog.match("bau mir ein Wiesendorf").orElseThrow()));
        assertFalse(GameStructures.preferred(catalog.match("bau mir einen Taktgeber").orElseThrow()));
        // Zusätze treffen keine Familie – „mit 8 Häusern“ bleibt Sache des TemplateCatalog.
        assertTrue(catalog.match("bau mir ein Wiesendorf mit 8 Häusern").isEmpty());
    }

    @Test
    void numbersContinueAfterExistingFiles(@TempDir Path dir) throws Exception {
        assertEquals(1, GameStructures.nextNumber(dir, "Iglu"));
        Files.writeString(dir.resolve("iglu_1.nbt"), "x");
        Files.writeString(dir.resolve("iglu_2.nbt"), "x");
        assertEquals(3, GameStructures.nextNumber(dir, "Iglu"));
    }
}
