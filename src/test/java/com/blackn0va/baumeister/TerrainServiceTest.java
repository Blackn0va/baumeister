package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.earth.EarthService;
import com.blackn0va.baumeister.terrain.TerrainPopulator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Senkrechte Abbildung, Datapack und Plätze der hohen Geländewelt – ohne Server und Netz. */
class TerrainServiceTest {

    private static final TerrainService.Range HIGH = new TerrainService.Range(-2032, 2032);

    @Test
    void lowlandKeepsRealSeaLevelInTheHighWorld() {
        TerrainService.Vertical v = TerrainService.vertical(480, 620, HIGH);  // München
        assertEquals(0, v.ref());
        assertEquals(1.0, v.scale());
        assertEquals(480 - TerrainService.FLOOR_DEPTH, v.floorY());
    }

    @Test
    void grandCanyonFitsOneToOneShiftedByWholeHundreds() {
        // Rand bis 2 800 m, Fluss 700 m: passt in 4 064 Blöcke, aber nicht ab Meereshöhe 0.
        TerrainService.Vertical v = TerrainService.vertical(700, 2800, HIGH);
        assertEquals(1.0, v.scale());
        assertEquals(1800, v.ref());
        assertTrue(2800 - v.ref() <= 2031 - 20, "Rand unter der Decke");
        assertTrue(v.floorY() >= -2032, "Grundgestein in der Welt");
        assertEquals(700 - 1800 - TerrainService.FLOOR_DEPTH, v.floorY());
    }

    @Test
    void onlyHimalayaScaleReliefIsCompressed() {
        TerrainService.Vertical v = TerrainService.vertical(1500, 8848, HIGH);
        assertTrue(v.scale() < 1.0);
        assertTrue((8848 - v.ref()) * v.scale() <= 2031 - 20 + 1e-6);
        assertTrue(v.floorY() >= -2032);
    }

    @Test
    void normalWorldCompressesTheCanyon() {
        TerrainService.Vertical v = TerrainService.vertical(700, 2800, null);
        assertEquals(344.0 / 2100, v.scale(), 1e-9);
        assertEquals(-44, v.baseY());
    }

    @Test
    void datapackDescribesAWorld4064BlocksHigh(@TempDir Path dir) throws Exception {
        TerrainService.writePack(dir);
        JsonObject type = JsonParser.parseString(Files.readString(
                dir.resolve("data/baumeister/dimension_type/gelaende.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        int minY = type.get("min_y").getAsInt();
        int height = type.get("height").getAsInt();
        assertEquals(-2032, minY);
        assertEquals(4064, height);
        // Grenzen von Minecraft: Vielfache von 16, min_y + height ≤ 2032 (sonst lädt die Welt nicht).
        assertEquals(0, minY % 16);
        assertEquals(0, height % 16);
        assertTrue(minY + height <= 2032);
        JsonObject dim = JsonParser.parseString(Files.readString(dir.resolve("data/baumeister/dimension/gelaende.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(TerrainService.DIMENSION, dim.get("type").getAsString());
        assertEquals(0, dim.getAsJsonObject("generator").getAsJsonObject("settings").getAsJsonArray("layers").size());
        assertTrue(Files.readString(dir.resolve("pack.mcmeta"), StandardCharsets.UTF_8).contains("\"min_format\""));
    }

    @Test
    void datapackHoldsTerrainAndEarthWithTheSameType(@TempDir Path dir) throws Exception {
        List<String> added = TerrainService.writePack(dir);
        assertEquals(List.of("pack.mcmeta", "data/baumeister/dimension_type/gelaende.json",
                "data/baumeister/dimension/gelaende.json", "data/baumeister/dimension/erde.json"), added);
        JsonObject earth = JsonParser.parseString(Files.readString(dir.resolve("data/baumeister/dimension/erde.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(TerrainService.DIMENSION, earth.get("type").getAsString());
        assertEquals("minecraft:flat", earth.getAsJsonObject("generator").get("type").getAsString());
        assertEquals(0, earth.getAsJsonObject("generator").getAsJsonObject("settings").getAsJsonArray("layers")
                .size());
        assertEquals(EarthService.WORLD_SUFFIX, "_" + EarthService.DIMENSION.replace(':', '_'));
    }

    @Test
    void installOnAnOlderPackOnlyAddsTheEarth(@TempDir Path dir) throws Exception {
        // Stand vor der Erdwelt: Pack ohne erde.json, und ein Admin hat die Beschreibung angepasst.
        TerrainService.writePack(dir);
        Files.delete(dir.resolve("data/baumeister/dimension/erde.json"));
        Files.writeString(dir.resolve("pack.mcmeta"), "eigene Fassung", StandardCharsets.UTF_8);
        String dimension = Files.readString(dir.resolve("data/baumeister/dimension/gelaende.json"),
                StandardCharsets.UTF_8);
        assertEquals(List.of("data/baumeister/dimension/erde.json"), TerrainService.writePack(dir));
        assertEquals("eigene Fassung", Files.readString(dir.resolve("pack.mcmeta"), StandardCharsets.UTF_8));
        assertEquals(dimension, Files.readString(dir.resolve("data/baumeister/dimension/gelaende.json"),
                StandardCharsets.UTF_8));
        assertEquals(List.of(), TerrainService.writePack(dir));
    }

    /** So schrieb writePack das Pack bis 2026-11-07 – Stand auf dev01 vor dem Sprung auf 26.2. */
    private static void writeOldPack(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("data/baumeister/dimension_type"));
        Files.writeString(dir.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Baumeister: hohe Welt für "
                + "Gelände 1:1\",\"min_format\":[94,0],\"max_format\":[94,1]}}\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("data/baumeister/dimension_type/gelaende.json"), "{\"has_fixed_time\":false,"
                + "\"has_skylight\":true,\"has_ceiling\":false,"
                + "\"coordinate_scale\":1.0,\"min_y\":-2032,\"height\":4064,\"logical_height\":4064,"
                + "\"infiniburn\":\"#minecraft:infiniburn_overworld\",\"ambient_light\":0.0,"
                + "\"monster_spawn_light_level\":0,\"monster_spawn_block_light_limit\":0,\"skybox\":\"overworld\","
                + "\"cardinal_light\":\"default\",\"timelines\":\"#minecraft:in_overworld\"}\n", StandardCharsets.UTF_8);
    }

    private static JsonObject read(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void upgradeAddsWhatMinecraft26RequiresAndKeepsEveryOldValue(@TempDir Path dir) throws Exception {
        writeOldPack(dir);
        JsonObject before = read(dir.resolve("data/baumeister/dimension_type/gelaende.json"));
        assertEquals(List.of("data/baumeister/dimension_type/gelaende.json", "pack.mcmeta"),
                TerrainService.upgradePack(dir));
        JsonObject after = read(dir.resolve("data/baumeister/dimension_type/gelaende.json"));
        // Ohne diesen Schlüssel bricht 26.2 beim Laden der Datapacks ab (Probelauf 2026-11-07).
        assertEquals(false, after.get("has_ender_dragon_fight").getAsBoolean());
        assertEquals("minecraft:overworld", after.get("default_clock").getAsString(), "Tag-Nacht wie bisher");
        for (String key : before.keySet()) {
            assertEquals(before.get(key), after.get(key), "unverändert: " + key);
        }
        JsonObject pack = read(dir.resolve("pack.mcmeta")).getAsJsonObject("pack");
        assertEquals("[94,0]", pack.get("min_format").toString(), "1.21.11 bleibt abgedeckt");
        assertEquals("[107,1]", pack.get("max_format").toString());
        assertEquals(List.of(), TerrainService.upgradePack(dir), "zweiter Start: nichts mehr zu tun");
    }

    @Test
    void newPackAlreadyCarriesEveryRequiredKeyOfBothVersions(@TempDir Path dir) throws Exception {
        TerrainService.writePack(dir);
        assertEquals(List.of(), TerrainService.upgradePack(dir));
        JsonObject type = read(dir.resolve("data/baumeister/dimension_type/gelaende.json"));
        // Pflichtfelder (fieldOf ohne Vorgabe) aus javap net.minecraft.world.level.dimension.DimensionType, 26.2;
        // 1.21.11 verlangt dieselben ohne has_ender_dragon_fight und überliest den Rest.
        for (String key : List.of("has_skylight", "has_ceiling", "has_ender_dragon_fight", "coordinate_scale", "min_y",
                "height", "logical_height", "infiniburn", "ambient_light", "monster_spawn_light_level",
                "monster_spawn_block_light_limit")) {
            assertTrue(type.has(key), key);
        }
    }

    @Test
    void upgradeLeavesAnAdminsOwnPackMcmetaAlone(@TempDir Path dir) throws Exception {
        writeOldPack(dir);
        Files.writeString(dir.resolve("pack.mcmeta"), "eigene Fassung", StandardCharsets.UTF_8);
        assertEquals(List.of("data/baumeister/dimension_type/gelaende.json"), TerrainService.upgradePack(dir));
        assertEquals("eigene Fassung", Files.readString(dir.resolve("pack.mcmeta"), StandardCharsets.UTF_8));
    }

    @Test
    void datapackLivesInTheSaveFolderNotInTheDimensionFolder() {
        // 26.x: getWorldFolder() der Oberwelt kann unter dimensions/minecraft/overworld liegen – datapacks nicht.
        assertEquals(Path.of("server", "world", "datapacks", TerrainService.PACK),
                TerrainService.datapackDir(Path.of("server"), "world"));
    }

    @Test
    void slotsAreSixtyFourKilometresApart() {
        assertEquals(0, TerrainPopulator.slotOf(0));
        assertEquals(0, TerrainPopulator.slotOf(32_767));
        assertEquals(1, TerrainPopulator.slotOf(32_768));
        assertEquals(1, TerrainPopulator.slotOf(65_536 + 25_000));
        assertEquals(1, TerrainPopulator.slotOf(65_536 - 25_000));
        assertEquals(-1, TerrainPopulator.slotOf(-32_769));
        assertTrue(TerrainPopulator.SLOT_BLOCKS > 2 * TerrainService.MAX_RADIUS + 32);
    }

    @Test
    void sameOrtAndRadiusReusesItsSlotOtherRadiusDoesNot(@TempDir Path tmp) throws Exception {
        // dev01 hatte den Grand Canyon dreimal (je /bm gelaende ein neuer Platz) – der zweite Aufruf soll zurückführen.
        Path dir = Files.createDirectories(tmp.resolve("gelaende"));
        Files.writeString(dir.resolve("3_grand_canyon.json"), "{\"welt\":\"w\",\"ort\":\"Grand Canyon\","
                + "\"lat\":36.0997631,\"lon\":-112.1124846,\"radius\":8000,\"platz\":3}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("tief.json"), "{\"welt\":\"gelaende_tief\",\"lat\":36.0997631,"
                + "\"lon\":-112.1124846,\"radius\":8000}", StandardCharsets.UTF_8);  // flache Welt: kein Platz
        Files.writeString(dir.resolve("0_kaputt.json"), "{", StandardCharsets.UTF_8);  // vor dem Treffer: darf ihn nicht verdecken
        assertEquals(java.util.OptionalInt.of(3), TerrainService.existingSlot(dir, 36.0997631, -112.1124846, 8000));
        assertTrue(TerrainService.existingSlot(dir, 36.0997631, -112.1124846, 4000).isEmpty(), "anderer Radius");
        assertTrue(TerrainService.existingSlot(dir, 36.1, -112.1124846, 8000).isEmpty(), "anderer Ort");
        assertTrue(TerrainService.existingSlot(tmp.resolve("fehlt"), 0, 0, 8000).isEmpty());
    }
}
