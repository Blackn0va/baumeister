package com.blackn0va.baumeister.structure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Liest die Blöcke einer Minecraft-Welt (Anvil, .mca, Java Edition ab 1.18) für den Import der Technik-Wiki-Welten
 * (Wunsch 2026-11-08). Werkzeug, nicht
 * Plugin: Es läuft auf dem PC, das Plugin bekommt nur die ausgeschnittenen Dateien.
 *
 * Aufbau nach der öffentlichen Beschreibung des Formats (kein fremder Code): Kopf mit 1024 Einträgen (Versatz in
 * 4-KiB-Sektoren, 3 Byte, plus Sektorzahl), je Chunk Länge, Kompression (1 gzip, 2 zlib, 3 roh) und NBT. Je Abschnitt
 * (16³) eine Palette und long[] mit max(4, ⌈log2 n⌉) Bit je Eintrag, Einträge laufen seit 1.16 NICHT über long-Grenzen,
 * Index (y·16 + z)·16 + x. Fehlt data, ist der ganze Abschnitt der eine Palettenblock.
 *
 * Threads: zustandslos. Fehler: IOException mit Datei und Grund; ein kaputter Chunk bricht den Import ab.
 */
public final class RegionWorld {

    /** Ein Block mit Weltkoordinaten; state wie in der Palette („minecraft:repeater[delay=2,facing=north]“). */
    public record Block(int x, int y, int z, String state) {
    }

    /** Ein Blockobjekt (Schild, Truhe …) samt rohen Daten. */
    public record Entity(int x, int y, int z, String id, Map<String, Object> data) {
    }

    private RegionWorld() {
    }

    /** Jeder Nicht-Luft-Block der Oberwelt an consumer, je Chunk danach die Blockobjekte an entities. */
    public static void scan(Path world, Consumer<Block> blocks, Consumer<Entity> entities) throws IOException {
        Path region = world.resolve("region");
        List<Path> files;
        try (Stream<Path> s = Files.list(region)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".mca")).sorted().toList();
        }
        for (Path f : files) {
            scanRegion(f, blocks, entities);
        }
    }

    static void scanRegion(Path file, Consumer<Block> blocks, Consumer<Entity> entities) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 8192) {
                return;
            }
            byte[] head = new byte[4096];
            raf.readFully(head);
            for (int i = 0; i < 1024; i++) {
                int off = ((head[i * 4] & 0xff) << 16) | ((head[i * 4 + 1] & 0xff) << 8) | (head[i * 4 + 2] & 0xff);
                int sectors = head[i * 4 + 3] & 0xff;
                if (off == 0 || sectors == 0) {
                    continue;
                }
                raf.seek((long) off * 4096);
                int len = raf.readInt();
                int kind = raf.readUnsignedByte();
                if (len <= 1 || len > sectors * 4096) {
                    throw new IOException(file.getFileName() + ": Chunk " + i + " mit unplausibler Länge " + len);
                }
                byte[] data = new byte[len - 1];
                raf.readFully(data);
                InputStream in = switch (kind) {
                    case 1 -> new GZIPInputStream(new ByteArrayInputStream(data));
                    case 2 -> new InflaterInputStream(new ByteArrayInputStream(data));
                    case 3 -> new ByteArrayInputStream(data);
                    default -> throw new IOException(file.getFileName() + ": Kompression " + kind + " nicht gelesen");
                };
                chunk(Nbt.read(in), blocks, entities);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void chunk(Map<String, Object> root, Consumer<Block> blocks, Consumer<Entity> entities)
            throws IOException {
        int cx = ((Number) root.get("xPos")).intValue();
        int cz = ((Number) root.get("zPos")).intValue();
        Object secs = root.get("sections");
        if (secs instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> sec = (Map<String, Object>) o;
                Object bs = sec.get("block_states");
                if (!(bs instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> states = (Map<String, Object>) bs;
                List<Object> palette = (List<Object>) states.get("palette");
                if (palette == null || palette.isEmpty()) {
                    continue;
                }
                String[] names = new String[palette.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = state((Map<String, Object>) palette.get(i));
                }
                int sy = ((Number) sec.get("Y")).intValue();
                long[] data = (long[]) states.get("data");
                if (data == null) {
                    if (!air(names[0])) {
                        for (int idx = 0; idx < 4096; idx++) {
                            emit(blocks, cx, sy, cz, idx, names[0]);
                        }
                    }
                    continue;
                }
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(names.length - 1));
                int perLong = 64 / bits;
                long mask = (1L << bits) - 1;
                if (data.length < (4096 + perLong - 1) / perLong) {
                    throw new IOException("Abschnitt " + cx + "/" + sy + "/" + cz + ": zu wenig Daten");
                }
                for (int idx = 0; idx < 4096; idx++) {
                    int p = (int) ((data[idx / perLong] >>> ((idx % perLong) * bits)) & mask);
                    if (p >= names.length) {
                        throw new IOException("Abschnitt " + cx + "/" + sy + "/" + cz + ": Palettenindex " + p);
                    }
                    if (!air(names[p])) {
                        emit(blocks, cx, sy, cz, idx, names[p]);
                    }
                }
            }
        }
        Object bes = root.get("block_entities");
        if (bes instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> be = (Map<String, Object>) o;
                entities.accept(new Entity(((Number) be.get("x")).intValue(), ((Number) be.get("y")).intValue(),
                        ((Number) be.get("z")).intValue(), String.valueOf(be.get("id")), be));
            }
        }
    }

    private static void emit(Consumer<Block> blocks, int cx, int sy, int cz, int idx, String state) {
        int x = idx & 15;
        int z = (idx >> 4) & 15;
        int y = idx >> 8;
        blocks.accept(new Block(cx * 16 + x, sy * 16 + y, cz * 16 + z, state));
    }

    static boolean air(String s) {
        return s.equals("minecraft:air") || s.equals("minecraft:cave_air") || s.equals("minecraft:void_air");
    }

    @SuppressWarnings("unchecked")
    private static String state(Map<String, Object> p) {
        String name = String.valueOf(p.get("Name"));
        Object props = p.get("Properties");
        if (!(props instanceof Map<?, ?> m) || m.isEmpty()) {
            return name;
        }
        StringBuilder b = new StringBuilder(name).append('[');
        new java.util.TreeMap<>((Map<String, Object>) m).forEach((k, v) -> b.append(k).append('=').append(v).append(','));
        b.setCharAt(b.length() - 1, ']');
        return b.toString();
    }

    /** Text eines Schilds (Vorder- und Rückseite, JSON-Komponenten auf Klartext gekürzt). */
    @SuppressWarnings("unchecked")
    public static String signText(Map<String, Object> data) {
        List<String> lines = new ArrayList<>();
        for (String side : new String[] {"front_text", "back_text"}) {
            if (data.get(side) instanceof Map<?, ?> t && t.get("messages") instanceof List<?> msgs) {
                for (Object m : msgs) {
                    String s = plain(m);
                    if (!s.isBlank()) {
                        lines.add(s.strip());
                    }
                }
            }
        }
        return String.join(" ", lines);
    }

    /** "\"Text\"" oder {"text":…,"extra":[…]} (als String oder schon als Compound) → Klartext. */
    @SuppressWarnings("unchecked")
    static String plain(Object m) {
        if (m instanceof Map<?, ?> c) {
            StringBuilder b = new StringBuilder();
            Object t = ((Map<String, Object>) c).get("text");
            if (t != null) {
                b.append(t);
            }
            if (((Map<String, Object>) c).get("extra") instanceof List<?> extra) {
                extra.forEach(e -> b.append(plain(e)));
            }
            return b.toString();
        }
        if (m instanceof List<?> l) {
            StringBuilder b = new StringBuilder();
            l.forEach(e -> b.append(plain(e)));
            return b.toString();
        }
        String s = String.valueOf(m).strip();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"");
        }
        if (s.startsWith("{") || s.startsWith("[")) {
            try {
                return plain(com.google.gson.JsonParser.parseString(s) instanceof com.google.gson.JsonElement j
                        ? new com.google.gson.Gson().fromJson(j, Object.class) : s);
            } catch (RuntimeException e) {
                return s;
            }
        }
        return s;
    }

    /** Zählt Blöcke je Name (ohne Zustand) – für die Messprobe. */
    public static Map<String, Integer> histogram(List<Block> blocks) {
        Map<String, Integer> h = new HashMap<>();
        for (Block b : blocks) {
            int i = b.state().indexOf('[');
            h.merge(i < 0 ? b.state() : b.state().substring(0, i), 1, Integer::sum);
        }
        return h;
    }
}
