package com.blackn0va.baumeister.minimap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vorab-Scan: Eine echte kleine Regionsdatei (Kopf, zlib-Chunk mit Höhenkarte und Blockpalette, ein unfertiger und ein
 * kaputter Chunk) wird gelesen wie eine vom Server – ohne Server.
 */
class RegionScannerTest {

    private static final Map<String, Integer> COLORS = Map.of("minecraft:grass_block", 1, "minecraft:dirt", 10,
            "minecraft:gray_concrete", 21);
    private static final Set<String> ROADS = Set.of("minecraft:gray_concrete");

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- kleiner NBT-Schreiber nur für den Test

    private static void tag(DataOutputStream out, String name, Object v) throws IOException {
        out.writeByte(type(v));
        out.writeUTF(name);
        payload(out, v);
    }

    private static int type(Object v) {
        if (v instanceof Byte) {
            return Nbt.BYTE;
        } else if (v instanceof Integer) {
            return Nbt.INT;
        } else if (v instanceof String) {
            return Nbt.STRING;
        } else if (v instanceof long[]) {
            return Nbt.LONG_ARRAY;
        } else if (v instanceof int[]) {
            return Nbt.INT_ARRAY;
        } else if (v instanceof List<?>) {
            return Nbt.LIST;
        }
        return Nbt.COMPOUND;
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case Byte b -> out.writeByte(b);
            case Integer i -> out.writeInt(i);
            case String s -> out.writeUTF(s);
            case long[] a -> {
                out.writeInt(a.length);
                for (long l : a) {
                    out.writeLong(l);
                }
            }
            case int[] a -> {
                out.writeInt(a.length);
                for (int i : a) {
                    out.writeInt(i);
                }
            }
            case List<?> l -> {
                out.writeByte(l.isEmpty() ? Nbt.END : type(l.getFirst()));
                out.writeInt(l.size());
                for (Object o : l) {
                    payload(out, o);
                }
            }
            default -> {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    tag(out, e.getKey(), e.getValue());
                }
                out.writeByte(Nbt.END);
            }
        }
    }

    private static byte[] nbt(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(b)) {
            tag(out, "", root);
        }
        return b.toByteArray();
    }

    /** Gepackt wie Minecraft seit 1.16: perLong Einträge je long, keiner über eine long-Grenze. */
    private static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) {
            out[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        }
        return out;
    }

    /**
     * Chunk mit Boden auf y 63: Gras, darunter Erde; bei Block (2, 2) grauer Beton (Straße), bei (6, 2) Glas auf y 64
     * über dem Gras. Welt 0..256, Höhenkarte 9 Bit.
     */
    private static Map<String, Object> chunk(String status) {
        int[] sec3 = new int[4096];  // y 48..63: 0 dirt, 1 grass, 2 gray_concrete
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    sec3[(y * 16 + z) * 16 + x] = y < 15 ? 0 : x == 2 && z == 2 ? 2 : 1;
                }
            }
        }
        int[] sec4 = new int[4096];  // y 64..79: 0 air, 1 glass
        sec4[(0 * 16 + 2) * 16 + 6] = 1;
        int[] height = new int[256];
        for (int i = 0; i < 256; i++) {
            height[i] = 64;          // oberster Block 63 → 63 + 1 − minY
        }
        height[2 * 16 + 6] = 65;     // Glas auf 64
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("Y", (byte) 3);
        s3.put("block_states", Map.of("palette", List.of(Map.of("Name", "minecraft:dirt"),
                Map.of("Name", "minecraft:grass_block"), Map.of("Name", "minecraft:gray_concrete")),
                "data", pack(sec3, 4)));
        s3.put("biomes", Map.of("palette", List.of(Map.of("Name", "minecraft:plains"))));
        Map<String, Object> s4 = new LinkedHashMap<>();
        s4.put("Y", (byte) 4);
        s4.put("block_states", Map.of("palette", List.of(Map.of("Name", "minecraft:air"),
                Map.of("Name", "minecraft:glass")), "data", pack(sec4, 4)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4440);
        root.put("Status", status);
        root.put("Heightmaps", Map.of("WORLD_SURFACE", pack(height, 9), "OCEAN_FLOOR", pack(height, 9)));
        root.put("sections", List.of(s3, s4));
        root.put("block_entities", List.of(Map.of("id", "minecraft:chest")));
        return root;
    }

    private static byte[] zlib(byte[] raw) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(b)) {
            d.write(raw);
        }
        return b.toByteArray();
    }

    /** Regionsdatei: Chunk 0 fertig, Chunk 1 unfertig (Status features), Chunk 2 kaputte zlib-Daten. */
    private Path region() throws IOException {
        byte[][] payloads = {zlib(nbt(chunk("minecraft:full"))), zlib(nbt(chunk("minecraft:features"))),
            {1, 2, 3, 4, 5, 6}};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] header = new byte[8192];
        int sector = 2;
        for (int i = 0; i < payloads.length; i++) {
            byte[] p = payloads[i];
            int sectors = (p.length + 5 + 4095) / 4096;
            header[i * 4] = (byte) (sector >> 16);
            header[i * 4 + 1] = (byte) (sector >> 8);
            header[i * 4 + 2] = (byte) sector;
            header[i * 4 + 3] = (byte) sectors;
            ByteArrayOutputStream c = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(c);
            d.writeInt(p.length + 1);
            d.writeByte(2);
            d.write(p);
            byte[] padded = new byte[sectors * 4096];
            System.arraycopy(c.toByteArray(), 0, padded, 0, c.size());
            body.write(padded);
            sector += sectors;
        }
        Path f = dir.resolve("r.-1.2.mca");
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(header);
        all.write(body.toByteArray());
        Files.write(f, all.toByteArray());
        return f;
    }

    @Test
    void readsSurfaceColourHeightAndRoadsLikeTheServerWould() throws IOException {
        RegionScanner.Result r = RegionScanner.scan(region(), 0, 256, b -> COLORS.getOrDefault(b, 0), ROADS);
        assertEquals(1, r.chunks(), "nur der fertige Chunk");
        assertEquals(1, r.skipped(), "der kaputte Chunk zählt als übersprungen, ohne Ausnahme");
        int[] d = r.data();
        // Stichprobe 0 liegt bei Block (2, 2): grauer Beton auf Erde → Straße.
        assertEquals(21 | MinimapImage.ROAD, OverviewStore.base(d[0]));
        assertEquals(63, OverviewStore.height(d[0]));
        // Stichprobe 1 bei (6, 2): Glas auf 64 hat keine Kartenfarbe → darunter Gras auf 63.
        assertEquals(1, OverviewStore.base(d[1]));
        assertEquals(63, OverviewStore.height(d[1]));
        // Stichprobe 15 bei (14, 14): Gras.
        assertEquals(1, OverviewStore.base(d[15]));
        // Chunk 1 (unfertig) bleibt unbekannt.
        assertFalse(OverviewStore.present(d[OverviewStore.PER_CHUNK]));
        assertEquals(-1, RegionScanner.regionCoords(dir.resolve("r.-1.2.mca"))[0]);
    }

    @Test
    void scanFillsTheOverviewButLeavesLiveSamplesAlone() throws IOException {
        RegionScanner.Result r = RegionScanner.scan(region(), 0, 256, b -> COLORS.getOrDefault(b, 0), ROADS);
        OverviewStore s = new OverviewStore(10, x -> { });
        long rk = MinimapImage.chunkKey(-1, 2);
        // Chunk −32/64 (erster Chunk der Region −1/2) wurde schon live gelesen: Wasser auf 70.
        int[] live = new int[OverviewStore.PER_CHUNK];
        live[0] = OverviewStore.pack(12, 70);
        s.putChunk("w", -32, 64, live);
        s.putScan("w", rk, r.data());
        assertEquals(12, OverviewStore.base(s.sample("w", -32 * 16 + 2, 64 * 16 + 2)), "live bleibt");
        assertEquals(1, OverviewStore.base(s.sample("w", -32 * 16 + 6, 64 * 16 + 2)), "Lücke aus dem Scan");
        assertTrue(s.takeDirty().size() == 1);
    }

    @Test
    void truncatedOrForeignFilesDoNotThrowAndYieldNothing() throws IOException {
        Path tiny = dir.resolve("r.0.0.mca");
        Files.write(tiny, new byte[100]);
        assertEquals(0, RegionScanner.scan(tiny, 0, 256, b -> 1, ROADS).chunks());
        Path empty = dir.resolve("r.1.0.mca");
        Files.write(empty, new byte[8192]);
        assertEquals(0, RegionScanner.scan(empty, 0, 256, b -> 1, ROADS).chunks());
    }
}
