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
        assertEquals("sp_wuestentempel_3", GameStructures.fileStem("Wüstentempel", 3));
        assertEquals("sp_pluenderer_aussenposten_1", GameStructures.fileStem("Plünderer-Außenposten", 1)); // umlaut-ok
        assertTrue(SchematicCatalog.NAME.matcher(GameStructures.fileStem("Gestrandetes Schiffswrack", 99)).matches());
    }

    @Test
    void indexLineFeedsTheCatalogWithFamilyAndVariant() {
        String line = GameStructures.indexLine("sp_hexenhuette_2.nbt", "sp_hexenhuette_2", "Hexenhütte", 2,
                "Hütte\tauf\nStelzen", 3);
        String[] cols = line.strip().split("\t");
        assertEquals(6, cols.length, line);
        assertEquals("3", cols[5], "Lagen unter der Oberfläche");
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
                    case "getBlockData" -> {
                        int y = (Integer) a[1];
                        String key = ((cx << 4) + (Integer) a[0]) + "," + y + "," + ((cz << 4) + (Integer) a[2]);
                        Material type = changed.getOrDefault(key, FLAT[y + 64]);
                        String state = "minecraft:" + type.name().toLowerCase(java.util.Locale.ROOT)
                                + (type == Material.SANDSTONE_STAIRS ? "[facing=north,half=bottom]" : "");
                        yield blockData(state);
                    }
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** BlockData nur mit getAsString – mehr liest writeTemplate nicht. */
    private static org.bukkit.block.data.BlockData blockData(String state) {
        return (org.bukkit.block.data.BlockData) Proxy.newProxyInstance(
                org.bukkit.block.data.BlockData.class.getClassLoader(),
                new Class<?>[] {org.bukkit.block.data.BlockData.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "getAsString" -> state;
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
    void maskKeepsChangedCellsAndDugOutAirButNoUntouchedGroundOrAir() {
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
        // Vorher behalten (85 % der Dateien), beim Bau verwarf StructureFile sie ohnehin (Review 2026-11-15).
        assertFalse(keep.get(GameStructures.cell(0, 3, 0, sx, sz)), "unberührte Luft über dem Boden");
        assertEquals(2, keep.cardinality(), "nur die gegrabene Luft und der Sandstein");
    }

    @Test
    void templateIsWrittenFromSnapshotsWithOnlyChangedCellsAndReadsBackWithDugOutAir(@TempDir Path dir)
            throws Exception {
        // Tempel im Kleinen über eine Chunkgrenze mit negativen x: Kammer (Luft, gegraben) unter der Oberfläche,
        // Sandstein im Gras, Treppe darüber, Luft über dem Boden bleibt draußen.
        Map<String, Material> changed = Map.of("-1,-63,0", Material.AIR, "-1,-61,0", Material.SANDSTONE,
                "1,-60,0", Material.SANDSTONE_STAIRS);
        List<ChunkSnapshot> snaps = List.of(chunk(-1, 0, changed), chunk(0, 0, changed));
        int[] b = GameStructures.changedBounds(snaps, FLAT, -64);
        assertArrayEquals(new int[] {-1, -63, 0, 1, -60, 0}, b);
        GameStructures.Written w = GameStructures.writeTemplate(dir, "sp_tempel_1", snaps, FLAT, -64, b, 4671);
        assertEquals(dir.resolve("sp_tempel_1.nbt.neu"), w.file(), "erst .neu – FileSchematics liest das nicht");
        assertEquals(3, w.blocks());
        Map<String, Object> root;
        try (var in = Files.newInputStream(w.file())) {
            root = Nbt.read(in);
        }
        assertEquals(List.of(3, 4, 1), root.get("size"));
        assertEquals(4671, root.get("DataVersion"));
        com.blackn0va.baumeister.structure.StructureFile.Structure s =
                com.blackn0va.baumeister.structure.StructureFile.readKeepingAir(w.file());
        assertEquals("air", s.cells().get(new com.blackn0va.baumeister.plan.Pos(0, 0, 0)).id(), "gegrabene Kammer");
        assertEquals("sandstone", s.cells().get(new com.blackn0va.baumeister.plan.Pos(0, 2, 0)).id());
        assertEquals("sandstone_stairs", s.cells().get(new com.blackn0va.baumeister.plan.Pos(2, 3, 0)).id());
        assertEquals(3, s.cells().size(), "keine unberührte Luft, kein Gestein");
        assertEquals(2, com.blackn0va.baumeister.structure.StructureFile.read(w.file()).cells().size(),
                "ohne den Schalter verwirft der Leser Luft weiter (Technik-Wiki & Co.)");
    }

    @Test
    void paletteEntriesCarryNameAndProperties() {
        assertEquals(Map.of("Name", "minecraft:stone"), GameStructures.paletteEntry("minecraft:stone"));
        assertEquals(Map.of("Name", "minecraft:oak_stairs", "Properties", Map.of("facing", "north", "half", "top")),
                GameStructures.paletteEntry("minecraft:oak_stairs[facing=north,half=top]"));
    }

    @Test
    void groundOffsetSinksSurfaceKindsButNotUndergroundOnes() {
        assertEquals(14, GameStructures.groundOffset("desert_pyramid", 64, 50, 74), "Fallgrube 14 unter dem Boden");
        assertEquals(1, GameStructures.groundOffset("village_plains", 64, 63, 75), "Wege in der Grasschicht");
        assertEquals(0, GameStructures.groundOffset("ancient_city", -20, -40, 0), "steht wie ein Monument");
        assertEquals(0, GameStructures.groundOffset("igloo", 64, 70, 75));
        // Gemessen 2026-11-15: Portalruine ganz im Fels (Versatz 82), Monument mit Oberkante an der Oberfläche.
        assertEquals(0, GameStructures.groundOffset("ruined_portal", 64, -18, 2), "ganz unter den Füßen");
        assertEquals(0, GameStructures.groundOffset("monument", 64, 39, 63));
        assertEquals(33, GameStructures.groundOffset("bastion_remnant", 64, 31, 95), "ragt wirklich in den Boden");
    }

    @Test
    void summaryCountsSavedTemplatesNotAttempts() {
        assertEquals("Keine Iglu-Vorlage gespeichert (3 versucht).", GameStructures.summary("Iglu", 0, 3));
        assertTrue(GameStructures.summary("Iglu", 2, 3).startsWith("Fertig: 2 von 3 × Iglu."));
    }

    @Test
    void untouchedFlatWorldHasNoBounds() {
        assertNull(GameStructures.changedBounds(List.of(chunk(0, 0, Map.of()), chunk(3, -2, Map.of())), FLAT, -64));
    }

    @Test
    void flatWorldHasItsSurfaceAt63WithStoneBelow() {
        JsonObject o = JsonParser.parseString(GameStructures.FLAT_SETTINGS.formatted("minecraft:plains"))
                .getAsJsonObject();
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
        String[] game = GameStructures.indexLine("sp_wiesendorf_1.nbt", "sp_wiesendorf_1", "Wiesendorf", 1,
                "ganzes Dorf", 1).strip().split("\t");
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
    void everyFeatureAndSpecialPlaceIsAKnownKind() {
        assertTrue(GameStructures.KINDS.keySet().containsAll(GameStructures.FEATURES));
        assertTrue(GameStructures.KINDS.keySet().containsAll(GameStructures.UNDERGROUND));
        assertTrue(GameStructures.KINDS.keySet().containsAll(GameStructures.SEA));
        assertTrue(GameStructures.FEATURES.containsAll(GameStructures.GROUND.keySet()));
        for (String family : GameStructures.ALIASES.keySet()) {
            assertTrue(GameStructures.KINDS.values().stream().anyMatch(k -> k[0].equals(family)), family);
        }
    }

    @Test
    void numbersContinueAfterTheHighestFileLeftoverOrIndexLine(@TempDir Path dir) throws Exception {
        assertEquals(1, GameStructures.nextNumber(dir, "Iglu"));
        Files.writeString(dir.resolve("sp_iglu_1.nbt"), "x");
        Files.writeString(dir.resolve("sp_iglu_3.nbt"), "x");
        assertEquals(4, GameStructures.nextNumber(dir, "Iglu"), "nach der höchsten, nicht in die Lücke 2");
        Files.writeString(dir.resolve("sp_iglu_4.nbt.neu"), "x");
        assertEquals(5, GameStructures.nextNumber(dir, "Iglu"), "auch ein abgebrochener Rest zählt");
        Files.writeString(dir.resolve("index.tsv"), "sp_iglu_7.nbt\tsp_iglu_7\tIglu, Variante 7\n");
        assertEquals(8, GameStructures.nextNumber(dir, "Iglu"), "auch eine index-Zeile ohne Datei zählt");
        Files.writeString(dir.resolve("sp_iglu_extra_9.nbt"), "x");
        assertEquals(8, GameStructures.nextNumber(dir, "Iglu"), "andere Familie mit gleichem Anfang zählt nicht");
    }
}
