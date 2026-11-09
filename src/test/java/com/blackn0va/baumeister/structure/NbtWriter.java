package com.blackn0va.baumeister.structure;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Pos;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Testhilfe: schreibt NBT und daraus die vier Bauwerk-Formate, die StructureFile liest. Damit prüfen die Tests das
 * Lesen ohne fremde Dateien im Repo (Bauten von Dritten haben ungeklärte Rechte).
 *
 * Damit Schreiber und Leser nicht denselben Denkfehler teilen, packt {@link #litematicBits} die Bits über BigInteger –
 * ein anderer Rechenweg als StructureFile.packedAt mit Verschieben und Maskieren.
 */
final class NbtWriter {

    private NbtWriter() {
    }

    static byte[] gzip(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            out.writeUTF("");
            compound(out, root);
        }
        return bytes.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void payload(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case Byte b -> out.writeByte(b);
            case Short s -> out.writeShort(s);
            case Integer i -> out.writeInt(i);
            case Long l -> out.writeLong(l);
            case String s -> out.writeUTF(s);
            case byte[] b -> {
                out.writeInt(b.length);
                out.write(b);
            }
            case int[] a -> {
                out.writeInt(a.length);
                for (int x : a) {
                    out.writeInt(x);
                }
            }
            case long[] a -> {
                out.writeInt(a.length);
                for (long x : a) {
                    out.writeLong(x);
                }
            }
            case List<?> list -> {
                out.writeByte(list.isEmpty() ? 0 : type(list.getFirst()));
                out.writeInt(list.size());
                for (Object o : list) {
                    payload(out, o);
                }
            }
            case Map<?, ?> m -> compound(out, (Map<String, Object>) m);
            default -> throw new IllegalArgumentException("Typ nicht unterstützt: " + v.getClass());
        }
    }

    private static void compound(DataOutputStream out, Map<String, Object> m) throws IOException {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.writeByte(type(e.getValue()));
            out.writeUTF(e.getKey());
            payload(out, e.getValue());
        }
        out.writeByte(0);
    }

    private static int type(Object v) {
        return switch (v) {
            case Byte b -> 1;
            case Short s -> 2;
            case Integer i -> 3;
            case Long l -> 4;
            case byte[] b -> 7;
            case String s -> 8;
            case List<?> l -> 9;
            case Map<?, ?> m -> 10;
            case int[] a -> 11;
            case long[] a -> 12;
            default -> throw new IllegalArgumentException("Typ nicht unterstützt: " + v.getClass());
        };
    }

    // ---------------------------------------------------------------- Formate

    /** Palette in fester Reihenfolge, Luft zuerst (wie WorldEdit). */
    private static List<String> palette(Map<Pos, BlockSpec> cells) {
        List<String> p = new ArrayList<>(List.of("minecraft:air"));
        for (BlockSpec b : cells.values()) {
            if (!p.contains(b.asString())) {
                p.add(b.asString());
            }
        }
        return p;
    }

    private static int[] size(Map<Pos, BlockSpec> cells) {
        int[] s = {1, 1, 1};
        for (Pos p : cells.keySet()) {
            s[0] = Math.max(s[0], p.x() + 1);
            s[1] = Math.max(s[1], p.y() + 1);
            s[2] = Math.max(s[2], p.z() + 1);
        }
        return s;
    }

    /** Sponge v2 (version 2) oder v3 (version 3, unter Schematic → Blocks). */
    static byte[] sponge(Map<Pos, BlockSpec> cells, int version) throws IOException {
        int[] s = size(cells);
        List<String> pal = palette(cells);
        Map<String, Object> palette = new LinkedHashMap<>();
        for (int i = 0; i < pal.size(); i++) {
            palette.put(pal.get(i), i);
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int y = 0; y < s[1]; y++) {
            for (int z = 0; z < s[2]; z++) {
                for (int x = 0; x < s[0]; x++) {
                    BlockSpec b = cells.get(new Pos(x, y, z));
                    int v = b == null ? 0 : pal.indexOf(b.asString());
                    while ((v & ~0x7f) != 0) {
                        data.write((v & 0x7f) | 0x80);
                        v >>>= 7;
                    }
                    data.write(v);
                }
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", version);
        root.put("DataVersion", 4671);
        root.put("Width", (short) s[0]);
        root.put("Height", (short) s[1]);
        root.put("Length", (short) s[2]);
        if (version >= 3) {
            Map<String, Object> blocks = new LinkedHashMap<>();
            blocks.put("Palette", palette);
            blocks.put("Data", data.toByteArray());
            root.put("Blocks", blocks);
            return gzip(Map.of("Schematic", root));
        }
        root.put("PaletteMax", pal.size());
        root.put("Palette", palette);
        root.put("BlockData", data.toByteArray());
        return gzip(root);
    }

    /** Litematica mit einer Region; negative = Größe negativ ab der Gegenecke angegeben. */
    static byte[] litematic(Map<Pos, BlockSpec> cells, boolean negative) throws IOException {
        int[] s = size(cells);
        List<String> pal = palette(cells);
        int width = Math.max(2, 32 - Integer.numberOfLeadingZeros(pal.size() - 1));
        int[] values = new int[s[0] * s[1] * s[2]];
        for (Map.Entry<Pos, BlockSpec> e : cells.entrySet()) {
            Pos p = e.getKey();
            values[(p.y() * s[2] + p.z()) * s[0] + p.x()] = pal.indexOf(e.getValue().asString());
        }
        List<Object> palette = new ArrayList<>();
        for (String state : pal) {
            Map<String, Object> entry = new LinkedHashMap<>();
            int open = state.indexOf('[');
            entry.put("Name", open < 0 ? state : state.substring(0, open));
            if (open >= 0) {
                Map<String, Object> props = new LinkedHashMap<>();
                for (String kv : state.substring(open + 1, state.length() - 1).split(",")) {
                    props.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
                }
                entry.put("Properties", props);
            }
            palette.add(entry);
        }
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("Position", negative ? xyz(10 + s[0] - 1, 5 + s[1] - 1, -3 + s[2] - 1) : xyz(10, 5, -3));
        region.put("Size", negative ? xyz(-s[0], -s[1], -s[2]) : xyz(s[0], s[1], s[2]));
        region.put("BlockStatePalette", palette);
        region.put("BlockStates", litematicBits(values, width));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("MinecraftDataVersion", 4671);
        root.put("Version", 6);
        root.put("Regions", Map.of("haus", region));
        return gzip(root);
    }

    /** Bits fortlaufend ab Bit 0, über long-Grenzen hinweg – gerechnet mit BigInteger, nicht mit Shifts. */
    static long[] litematicBits(int[] values, int width) {
        BigInteger all = BigInteger.ZERO;
        for (int i = 0; i < values.length; i++) {
            all = all.or(BigInteger.valueOf(values[i]).shiftLeft(i * width));
        }
        int longs = (values.length * width + 63) / 64;
        long[] out = new long[longs];
        BigInteger mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        for (int k = 0; k < longs; k++) {
            out[k] = all.shiftRight(64 * k).and(mask).longValue();
        }
        return out;
    }

    /** Strukturdatei des Spiels (Strukturblock). */
    static byte[] structureNbt(Map<Pos, BlockSpec> cells) throws IOException {
        int[] s = size(cells);
        List<String> pal = palette(cells);
        List<Object> palette = new ArrayList<>();
        for (String state : pal) {
            int open = state.indexOf('[');
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Name", open < 0 ? state : state.substring(0, open));
            if (open >= 0) {
                Map<String, Object> props = new LinkedHashMap<>();
                for (String kv : state.substring(open + 1, state.length() - 1).split(",")) {
                    props.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
                }
                entry.put("Properties", props);
            }
            palette.add(entry);
        }
        List<Object> blocks = new ArrayList<>();
        for (Map.Entry<Pos, BlockSpec> e : cells.entrySet()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("pos", List.of(e.getKey().x(), e.getKey().y(), e.getKey().z()));
            b.put("state", pal.indexOf(e.getValue().asString()));
            blocks.add(b);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("size", List.of(s[0], s[1], s[2]));
        root.put("palette", palette);
        root.put("blocks", blocks);
        return gzip(root);
    }

    private static Map<String, Object> xyz(int x, int y, int z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }
}
