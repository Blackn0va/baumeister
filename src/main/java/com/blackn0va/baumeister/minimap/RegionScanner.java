package com.blackn0va.baumeister.minimap;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Liest eine Regionsdatei (.mca, 32 × 32 Chunks) direkt von der Platte und liefert je fertigem Chunk die 16 Stichproben
 * der Übersicht (OverviewStore) – ohne dass der Server einen Chunk lädt (Wunsch 2026-11-01). Der Server-Thread rechnet
 * dabei nichts; das läuft auf einem eigenen Thread.
 *
 * Format (Anvil, Minecraft 1.21): 4-KB-Kopf mit Lage und Länge je Chunk, dann je Chunk 4 Byte Länge, 1 Byte Kompression
 * (1 gzip, 2 zlib, 3 roh, 4 LZ4; +128 = in eigener Datei c.x.z.mcc), dann NBT. Gebraucht werden Status, die Höhenkarte
 * WORLD_SURFACE und die Blockpaletten der Sektionen.
 *
 * Threads: rein, beliebig (kein Bukkit). Fehler: Ein kaputter oder abgeschnittener Chunk (der Server schreibt die Datei
 * vielleicht gerade) zählt als nicht vorhanden; nur eine unlesbare Datei wirft IOException.
 */
public final class RegionScanner {

    /** Schlüssel, die ein Chunk-NBT mitbringt, die Karte aber nie braucht – überspringen statt anlegen. */
    static final Set<String> SKIP = Set.of("biomes", "BlockLight", "SkyLight", "block_entities", "entities",
            "structures", "PostProcessing", "blending_data", "fluid_ticks", "block_ticks", "CarvingMasks", "Lights",
            "UpgradeData", "Properties", "isLightOn", "InhabitedTime", "LastUpdate", "Paper.ChunkSlices",
            "ChunkBukkitValues");
    private static final int SEARCH_DOWN = 8;

    /** Was ein Scan einer Datei ergeben hat: Daten wie OverviewStore.Region, Zahl der fertigen Chunks. */
    public record Result(int[] data, int chunks, int skipped) {
    }

    /** Farbe eines Blocks: Grundfarbe (Palettenbyte / 4) oder 0 = keine Kartenfarbe (Luft, Glas, unbekannt). */
    @FunctionalInterface
    public interface Colors {
        int base(String blockName);
    }

    private RegionScanner() {
    }

    /**
     * Eine Regionsdatei lesen. minY/maxY: Bauhöhe der Welt (World#getMinHeight/getMaxHeight) – die Höhenkarte zählt
     * relativ zu minY, und ihre Bitbreite hängt an der Bauhöhe.
     */
    public static Result scan(Path mca, int minY, int maxY, Colors colors, Set<String> roadBlocks) throws IOException {
        byte[] file = Files.readAllBytes(mca);
        if (file.length < 8192) {
            return new Result(new int[OverviewStore.REGION * OverviewStore.REGION * OverviewStore.PER_CHUNK], 0, 0);
        }
        int[] data = new int[OverviewStore.REGION * OverviewStore.REGION * OverviewStore.PER_CHUNK];
        int chunks = 0;
        int skipped = 0;
        for (int i = 0; i < 1024; i++) {
            int loc = ((file[i * 4] & 0xFF) << 16) | ((file[i * 4 + 1] & 0xFF) << 8) | (file[i * 4 + 2] & 0xFF);
            if (loc == 0) {
                continue;
            }
            try {
                Map<String, Object> nbt = chunkNbt(file, loc * 4096L, mca, i);
                if (nbt == null || !full(nbt)) {
                    skipped += nbt == null ? 1 : 0;
                    continue;
                }
                int[] s = samples(nbt, minY, maxY, colors, roadBlocks);
                if (s != null) {
                    System.arraycopy(s, 0, data, i * OverviewStore.PER_CHUNK, OverviewStore.PER_CHUNK);
                    chunks++;
                }
            } catch (IOException | RuntimeException e) {
                skipped++;  // Chunk kaputt oder gerade im Schreiben – bleibt unbekannt, der nächste Scan holt ihn
            }
        }
        return new Result(data, chunks, skipped);
    }

    private static Map<String, Object> chunkNbt(byte[] file, long offset, Path mca, int index) throws IOException {
        if (offset + 5 > file.length) {
            return null;
        }
        int o = (int) offset;
        int len = ((file[o] & 0xFF) << 24) | ((file[o + 1] & 0xFF) << 16) | ((file[o + 2] & 0xFF) << 8)
                | (file[o + 3] & 0xFF);
        int type = file[o + 4] & 0xFF;
        InputStream raw;
        if ((type & 128) != 0) {
            // Chunk größer als 1 MB: liegt in c.<x>.<z>.mcc neben der Regionsdatei.
            int[] rc = regionCoords(mca);
            if (rc == null) {
                return null;
            }
            Path ext = mca.resolveSibling("c." + (rc[0] * 32 + (index & 31)) + "." + (rc[1] * 32 + (index >> 5))
                    + ".mcc");
            if (!Files.exists(ext)) {
                return null;
            }
            raw = new ByteArrayInputStream(Files.readAllBytes(ext));
            type &= 127;
        } else {
            if (len < 1 || o + 4L + len > file.length) {
                return null;
            }
            raw = new ByteArrayInputStream(file, o + 5, len - 1);
        }
        InputStream in = switch (type) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> null;  // 4 = LZ4 (region-file-compression=lz4) – auf dev01 deflate, hier nicht unterstützt
        };
        if (in == null) {
            return null;
        }
        try (DataInputStream d = new DataInputStream(new java.io.BufferedInputStream(in, 16384))) {
            return Nbt.readRoot(d, SKIP);
        }
    }

    private static boolean full(Map<String, Object> nbt) {
        return nbt.get("Status") instanceof String s && s.endsWith("full");
    }

    /** 16 Stichproben (Mitte jedes 4 × 4-Felds) eines fertigen Chunks oder null ohne Höhenkarte. */
    @SuppressWarnings("unchecked")
    static int[] samples(Map<String, Object> nbt, int minY, int maxY, Colors colors, Set<String> roadBlocks) {
        if (!(nbt.get("Heightmaps") instanceof Map<?, ?> hm) || !(hm.get("WORLD_SURFACE") instanceof long[] surface)) {
            return null;
        }
        Map<Integer, Section> sections = new HashMap<>();
        if (nbt.get("sections") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> sec && sec.get("Y") instanceof Number y
                        && sec.get("block_states") instanceof Map<?, ?> bs) {
                    Section s = Section.of((Map<String, Object>) bs);
                    if (s != null) {
                        sections.put(y.intValue(), s);
                    }
                }
            }
        }
        int bits = 32 - Integer.numberOfLeadingZeros(maxY - minY);  // ceil(log2(Höhe + 1))
        int[] out = new int[OverviewStore.PER_CHUNK];
        for (int sz = 0; sz < 4; sz++) {
            for (int sx = 0; sx < 4; sx++) {
                int x = sx * OverviewStore.SPACING + OverviewStore.SPACING / 2;
                int z = sz * OverviewStore.SPACING + OverviewStore.SPACING / 2;
                int top = (int) packed(surface, bits, z * 16 + x) + minY - 1;
                int y = top;
                int base = 0;
                boolean road = false;
                for (int k = 0; k < SEARCH_DOWN && y >= minY; k++, y--) {
                    String name = blockAt(sections, x, y, z);
                    int b = colors.base(name);
                    if (b != 0) {
                        base = b;
                        road = roadBlocks.contains(name) && y - 1 >= minY && !isAir(blockAt(sections, x, y - 1, z));
                        break;
                    }
                }
                if (base == 0) {
                    y = top;
                }
                out[sz * 4 + sx] = OverviewStore.pack(base | (road ? MinimapImage.ROAD : 0), y);
            }
        }
        return out;
    }

    static boolean isAir(String name) {
        return name.endsWith(":air") || name.endsWith("cave_air") || name.endsWith("void_air");
    }

    /** Eintrag i aus einem gepackten long-Feld (seit 1.16: Einträge springen nicht über long-Grenzen). */
    static long packed(long[] data, int bits, int i) {
        int perLong = 64 / bits;
        int word = i / perLong;
        if (word >= data.length) {
            return 0;
        }
        return (data[word] >>> ((i % perLong) * bits)) & ((1L << bits) - 1);
    }

    private static String blockAt(Map<Integer, Section> sections, int x, int y, int z) {
        Section s = sections.get(Math.floorDiv(y, 16));
        return s == null ? "minecraft:air" : s.at(x, Math.floorMod(y, 16), z);
    }

    /** Blockpalette einer Sektion (16 × 16 × 16). */
    private record Section(String[] palette, long[] data, int bits) {

        @SuppressWarnings("unchecked")
        static Section of(Map<String, Object> bs) {
            if (!(bs.get("palette") instanceof List<?> pal) || pal.isEmpty()) {
                return null;
            }
            String[] names = new String[pal.size()];
            for (int i = 0; i < names.length; i++) {
                Object e = pal.get(i);
                names[i] = e instanceof Map<?, ?> m && m.get("Name") instanceof String n ? n : "minecraft:air";
            }
            long[] data = bs.get("data") instanceof long[] d ? d : null;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(names.length - 1));
            return new Section(names, data, bits);
        }

        String at(int x, int y, int z) {
            if (palette.length == 1 || data == null) {
                return palette[0];
            }
            int v = (int) packed(data, bits, (y * 16 + z) * 16 + x);
            return v < palette.length ? palette[v] : "minecraft:air";
        }
    }

    /** Regionskoordinaten aus dem Dateinamen r.<x>.<z>.mca oder null. */
    public static int[] regionCoords(Path mca) {
        String n = mca.getFileName().toString();
        String[] p = n.split("\\.");
        if (p.length != 4 || !p[0].equals("r") || !p[3].equals("mca")) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
