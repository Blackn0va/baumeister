package com.blackn0va.baumeister.structure;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Pos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bauwerk-Dateien in eine gemeinsame Form: Blöcke je Stelle in den Achsen der Datei, Luft weggelassen.
 *
 * Gelesen werden, jeweils nach der öffentlichen Beschreibung des Formats (kein fremder Code):
 * - Sponge Schematic v1/v2 (.schem, WorldEdit bis 7.2): Palette Name → Nummer, BlockData als VarInt-Folge,
 *   Index = x + z·Breite + y·Breite·Länge
 * - Sponge Schematic v3 (.schem, WorldEdit ab 7.3): dasselbe unter Schematic → Blocks
 * - Litematica (.litematic): je Region Palette und eng gepackte long[]-Bits, die über long-Grenzen laufen;
 *   Bits je Eintrag = max(2, ⌈log2 Palettengröße⌉), Index = (y·sx + … ) wie unten; negative Region-Größen zählen
 *   von Position aus rückwärts
 * - Strukturdateien des Spiels (.nbt, Strukturblock): size, palette (bzw. die erste von palettes), blocks mit pos/state
 * - MCEdit/Schematica (.schematic, vor 1.13, Zahlen-IDs): Blocks und Data je ein Byte, AddBlocks mit dem oberen
 *   Halbbyte für IDs über 255 (zwei je Byte: gerade Stelle unteres, ungerade oberes Halbbyte), Index wie Sponge. Die
 *   Umrechnung ID:Daten → Zustand liefert der Server (useLegacy, CraftLegacy in Spigot und Paper) – keine kopierte
 *   Tabelle. Seit 2026-11-08 (Wunsch): 9 von 24 echten
 *   Server-Schematics waren in diesem Format.
 * Früher nicht gelesen: das alte MCEdit-Format (.schematic mit Zahlen-IDs, vor 1.13) und Bedrock (.mcstructure) – dafür
 * bräuchte es Umsetzungstabellen; beide enden mit einer klaren IOException statt mit falschen Blöcken.
 *
 * Anlass: Echte, von Spielern gebaute Bauwerke sollen als Vorbilder ins Lehrset (PlanDecompiler, VorbildImport).
 * Stand 2026-11-01 ist das der einzige Nutzer – das Plugin selbst und die Client-Mod (nur plan/ und logic/) rufen das
 * Paket nicht auf; es liegt in src/main, damit SchematicLibrary später .litematic/.nbt ohne WorldEdit lesen kann. Die Idee einer gemeinsamen Zwischenform stammt aus crd2333/minecraft-processor
 * (Node.js, ohne Lizenzdatei – daraus ist nur die Idee übernommen, kein Code).
 *
 * Threads: zustandslos. Fehler: IOException mit deutschem Grund (unbekanntes Format, kaputte Daten, zu groß).
 */
public final class StructureFile {

    /** Größer als bau.max-ausdehnung (192) braucht der Baumeister nicht; die Grenze schützt den Speicher. */
    /**
     * Vorher 256 und 2 Mio.: bedwars.schem vom eigenen Server (327 × 140 × 327) war unlesbar. Größer baut der
     * Baumeister ohnehin nicht (schematics.max-ausdehnung höchstens 512); 4 Mio. Blöcke sind gemessen lesbar
     * (olymp.schem, 952 911 Blöcke, 3 GB Heap auf dem PC).
     */
    public static final int MAX_EDGE = 512;
    public static final int MAX_CELLS = 4_000_000;

    /** Umrechnung alter Zahlen-IDs (ID 0–4095, Daten 0–15) in einen Zustand wie „minecraft:oak_stairs[facing=east]“. */
    @FunctionalInterface
    public interface Legacy {
        /** @return Zustand oder null, wenn der Server die Nummer nicht kennt */
        String state(int id, int data);
    }

    private static volatile Legacy legacy;

    /** Vom Plugin beim Start gesetzt (Server-Umrechnung); in Tests eine kleine Tabelle; null = alte Dateien ablehnen. */
    public static void useLegacy(Legacy l) {
        legacy = l;
    }

    /**
     * @param cells       nicht-Luft-Blöcke in Dateiachsen (x Osten, y oben, z Süden), ab der Ecke der Datei
     * @param unreadable  Blockzustände, die BlockSpec nicht lesen konnte (fremde Namensräume, Mods) → Anzahl Blöcke
     */
    public record Structure(String format, Map<Pos, BlockSpec> cells, int paletteSize, int dataVersion,
            Map<String, Integer> unreadable) {
    }

    private StructureFile() {
    }

    public static Structure read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    public static Structure read(InputStream in) throws IOException {
        Map<String, Object> root = Nbt.read(in);
        // Manche Schreiber legen die Daten unter einem einzigen benannten Wurzeleintrag ab ("Schematic", "").
        if (root.size() == 1 && root.values().iterator().next() instanceof Map<?, ?> only
                && !root.containsKey("Regions")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inner = (Map<String, Object>) only;
            if (inner.containsKey("Blocks") || inner.containsKey("BlockData") || inner.containsKey("Palette")) {
                root = inner;
            }
        }
        if (root.containsKey("Regions")) {
            return litematic(root);
        }
        if (root.containsKey("Materials") || root.get("Blocks") instanceof byte[]) {
            Legacy l = legacy;
            if (l == null) {
                throw new IOException("Altes MCEdit-Format (Zahlen-IDs vor 1.13) nur auf dem Server lesbar – dort "
                        + "rechnet Minecraft die alten Nummern um");
            }
            return mcedit(root, l);
        }
        if (root.get("Blocks") instanceof Map<?, ?> blocks) {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) blocks;
            return sponge(root, b, "schem-v3");
        }
        if (root.containsKey("BlockData")) {
            return sponge(root, root, "schem-v" + intOf(root.getOrDefault("Version", 2)));
        }
        if (root.containsKey("palette") || root.containsKey("palettes")) {
            return structureNbt(root);
        }
        throw new IOException("Unbekanntes Bauwerk-Format (Schlüssel: " + root.keySet() + ")");
    }

    // ---------------------------------------------------------------- Sponge

    private static Structure sponge(Map<String, Object> root, Map<String, Object> blocks, String format)
            throws IOException {
        int w = unsigned(root.get("Width"));
        int h = unsigned(root.get("Height"));
        int l = unsigned(root.get("Length"));
        checkSize(w, h, l);
        Map<String, Object> palette = map(blocks.get("Palette"), "Palette");
        byte[] data = (byte[]) require(blocks.containsKey("Data") ? blocks.get("Data") : blocks.get("BlockData"),
                "BlockData");
        Map<Integer, String> byId = new HashMap<>();
        palette.forEach((name, id) -> byId.put(intOf(id), name));
        Decoder dec = new Decoder();
        Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        int i = 0;
        int index = 0;
        long volume = (long) w * h * l;
        while (i < data.length && index < volume) {
            int value = 0;
            int shift = 0;
            int b;
            do {
                if (i >= data.length) {
                    throw new IOException("BlockData endet mitten in einer Zahl");
                }
                b = data[i++] & 0xff;
                value |= (b & 0x7f) << shift;
                shift += 7;
                if (shift > 35) {
                    throw new IOException("BlockData: Zahl zu lang");
                }
            } while ((b & 0x80) != 0);
            String state = byId.get(value);
            if (state == null) {
                throw new IOException("BlockData verweist auf Palette-Nummer " + value + ", die es nicht gibt");
            }
            int x = index % w;
            int z = (index / w) % l;
            int y = index / (w * l);
            dec.put(cells, new Pos(x, y, z), state);
            index++;
        }
        if (index != volume) {
            throw new IOException("BlockData hat " + index + " statt " + volume + " Einträge");
        }
        return new Structure(format, cells, palette.size(), intOf(root.getOrDefault("DataVersion", 0)),
                dec.unreadable);
    }

    // ---------------------------------------------------------------- MCEdit (vor 1.13)

    private static Structure mcedit(Map<String, Object> root, Legacy l) throws IOException {
        Object materials = root.get("Materials");
        if (materials != null && !"Alpha".equals(materials.toString())) {
            throw new IOException("MCEdit-Datei für „" + materials + "“ – gelesen wird nur Java (Alpha)");
        }
        int w = unsigned(root.get("Width"));
        int h = unsigned(root.get("Height"));
        int len = unsigned(root.get("Length"));
        checkSize(w, h, len);
        byte[] ids = (byte[]) require(root.get("Blocks"), "Blocks");
        byte[] data = (byte[]) require(root.get("Data"), "Data");
        byte[] add = root.get("AddBlocks") instanceof byte[] a ? a : root.get("Add") instanceof byte[] a2 ? a2 : null;
        long volume = (long) w * h * len;
        if (ids.length < volume || data.length < volume) {
            throw new IOException("Blocks/Data haben " + ids.length + "/" + data.length + " statt " + volume
                    + " Einträge");
        }
        Decoder dec = new Decoder();
        Map<Integer, String> states = new HashMap<>();
        Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        for (int index = 0; index < volume; index++) {
            int id = ids[index] & 0xff;
            if (add != null && (index >> 1) < add.length) {
                int nibble = (index & 1) == 0 ? add[index >> 1] & 0x0f : (add[index >> 1] >> 4) & 0x0f;
                id |= nibble << 8;
            }
            if (id == 0) {
                continue;
            }
            int d = data[index] & 0x0f;
            int fullId = id;
            int key = (fullId << 4) | d;
            String state = states.computeIfAbsent(key, k -> {
                String s = l.state(fullId, d);
                // Unbekannte Datenwerte (z. B. veraltete Ausrichtungen) wenigstens als Grundzustand des Blocks.
                return s != null ? s : d != 0 ? l.state(fullId, 0) : null;
            });
            if (state == null) {
                dec.unreadable.merge("legacy:" + id + ":" + d, 1, Integer::sum);
                continue;
            }
            int x = index % w;
            int z = (index / w) % len;
            int y = index / (w * len);
            dec.put(cells, new Pos(x, y, z), state);
        }
        return new Structure("schematic-mcedit", cells, states.size(), 0, dec.unreadable);
    }

    // ---------------------------------------------------------------- Litematica

    private static Structure litematic(Map<String, Object> root) throws IOException {
        Map<String, Object> regions = map(root.get("Regions"), "Regions");
        Decoder dec = new Decoder();
        Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        int paletteSize = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        List<Object[]> parts = new ArrayList<>();
        for (Object r : regions.values()) {
            Map<String, Object> region = map(r, "Region");
            Map<String, Object> pos = map(region.get("Position"), "Position");
            Map<String, Object> size = map(region.get("Size"), "Size");
            int sx = intOf(size.get("x"));
            int sy = intOf(size.get("y"));
            int sz = intOf(size.get("z"));
            // Negative Größe: Die Region reicht von Position rückwärts (Litematica speichert die Ecke, an der die
            // Auswahl begann).
            int ox = intOf(pos.get("x")) + (sx < 0 ? sx + 1 : 0);
            int oy = intOf(pos.get("y")) + (sy < 0 ? sy + 1 : 0);
            int oz = intOf(pos.get("z")) + (sz < 0 ? sz + 1 : 0);
            parts.add(new Object[] {region, ox, oy, oz, Math.abs(sx), Math.abs(sy), Math.abs(sz)});
            minX = Math.min(minX, ox);
            minY = Math.min(minY, oy);
            minZ = Math.min(minZ, oz);
        }
        for (Object[] p : parts) {
            @SuppressWarnings("unchecked")
            Map<String, Object> region = (Map<String, Object>) p[0];
            int ox = (int) p[1] - minX;
            int oy = (int) p[2] - minY;
            int oz = (int) p[3] - minZ;
            int sx = (int) p[4];
            int sy = (int) p[5];
            int sz = (int) p[6];
            checkSize(sx, sy, sz);
            List<String> palette = new ArrayList<>();
            for (Object e : list(region.get("BlockStatePalette"), "BlockStatePalette")) {
                palette.add(stateString(map(e, "Paletteneintrag")));
            }
            paletteSize += palette.size();
            long[] bits = (long[]) require(region.get("BlockStates"), "BlockStates");
            int width = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            long volume = (long) sx * sy * sz;
            if (volume * width > (long) bits.length * 64) {
                throw new IOException("BlockStates zu kurz für " + volume + " Blöcke à " + width + " Bit");
            }
            long mask = (1L << width) - 1;
            for (long i = 0; i < volume; i++) {
                int v = packedAt(bits, i, width, mask);
                if (v >= palette.size()) {
                    throw new IOException("BlockStates verweist auf Palette-Nummer " + v + ", die es nicht gibt");
                }
                int x = (int) (i % sx);
                int z = (int) ((i / sx) % sz);
                int y = (int) (i / ((long) sx * sz));
                dec.put(cells, new Pos(ox + x, oy + y, oz + z), palette.get(v));
            }
        }
        return new Structure("litematic", cells, paletteSize, intOf(root.getOrDefault("MinecraftDataVersion", 0)),
                dec.unreadable);
    }

    /**
     * Eintrag index eines eng gepackten Bitfelds wie in Litematica: fortlaufende Bits ab Bit 0 des ersten long, ein
     * Eintrag darf über die Grenze zweier longs laufen (anders als die Chunk-Palette des Spiels seit 1.16).
     */
    static int packedAt(long[] bits, long index, int width, long mask) {
        long start = index * width;
        int startLong = (int) (start >>> 6);
        int endLong = (int) ((start + width - 1) >>> 6);
        int offset = (int) (start & 63);
        if (startLong == endLong) {
            return (int) ((bits[startLong] >>> offset) & mask);
        }
        return (int) (((bits[startLong] >>> offset) | (bits[endLong] << (64 - offset))) & mask);
    }

    // ---------------------------------------------------------------- Strukturdatei des Spiels

    private static Structure structureNbt(Map<String, Object> root) throws IOException {
        List<Object> size = list(root.get("size"), "size");
        if (size.size() != 3) {
            throw new IOException("size braucht drei Werte");
        }
        checkSize(intOf(size.get(0)), intOf(size.get(1)), intOf(size.get(2)));
        List<Object> palette = root.containsKey("palette") ? list(root.get("palette"), "palette")
                : list(list(root.get("palettes"), "palettes").getFirst(), "palettes[0]");
        List<String> states = new ArrayList<>();
        for (Object e : palette) {
            states.add(stateString(map(e, "Paletteneintrag")));
        }
        Decoder dec = new Decoder();
        Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        for (Object o : list(root.get("blocks"), "blocks")) {
            Map<String, Object> b = map(o, "Block");
            List<Object> p = list(b.get("pos"), "pos");
            int state = intOf(b.get("state"));
            if (state < 0 || state >= states.size()) {
                throw new IOException("Block verweist auf Palette-Nummer " + state + ", die es nicht gibt");
            }
            dec.put(cells, new Pos(intOf(p.get(0)), intOf(p.get(1)), intOf(p.get(2))), states.get(state));
        }
        return new Structure("nbt", cells, states.size(), intOf(root.getOrDefault("DataVersion", 0)),
                dec.unreadable);
    }

    // ---------------------------------------------------------------- lokale Form

    /**
     * In Plan-Koordinaten: Die Vorderseite des Bauwerks (front, wie „vorne“ in bauteile.yml) zeigt zum Spieler (lokal
     * −z), Richtungen werden lokal (front/back/left/right), Ecke links vorne unten auf (0,0,0), Zustände über
     * {@link BlockStates#normalize} entschlackt.
     */
    public static Map<Pos, BlockSpec> toLocal(Structure s, Cardinal front) {
        Orientation o = new Orientation(front.opposite());
        Map<Pos, BlockSpec> rotated = new HashMap<>();
        int mx = Integer.MAX_VALUE;
        int my = Integer.MAX_VALUE;
        int mz = Integer.MAX_VALUE;
        for (Map.Entry<Pos, BlockSpec> e : s.cells().entrySet()) {
            Pos p = o.toLocalOffset(e.getKey());
            rotated.put(p, BlockStates.normalize(o.toLocal(e.getValue())));
            mx = Math.min(mx, p.x());
            my = Math.min(my, p.y());
            mz = Math.min(mz, p.z());
        }
        Map<Pos, BlockSpec> out = new TreeMap<>(StructureFile::order);
        for (Map.Entry<Pos, BlockSpec> e : rotated.entrySet()) {
            out.put(e.getKey().add(-mx, -my, -mz), e.getValue());
        }
        return out;
    }

    /** Feste Reihenfolge y, z, x – gleiche Datei, gleicher Plan. */
    static int order(Pos a, Pos b) {
        int c = Integer.compare(a.y(), b.y());
        if (c == 0) {
            c = Integer.compare(a.z(), b.z());
        }
        return c != 0 ? c : Integer.compare(a.x(), b.x());
    }

    // ---------------------------------------------------------------- Hilfen

    /** Zustandstext → BlockSpec mit Zwischenspeicher; Luft fällt weg, Unlesbares wird gezählt. */
    private static final class Decoder {
        final Map<String, BlockSpec> cache = new HashMap<>();
        final Map<String, Integer> unreadable = new TreeMap<>();

        void put(Map<Pos, BlockSpec> cells, Pos p, String state) throws IOException {
            BlockSpec spec = cache.computeIfAbsent(state, s -> {
                try {
                    return BlockStates.upgrade(BlockSpec.parse(s));
                } catch (PlanException e) {
                    return null;
                }
            });
            if (spec == null) {
                unreadable.merge(state, 1, Integer::sum);
                return;
            }
            if (spec.isAir()) {
                return;
            }
            if (cells.size() >= MAX_CELLS) {
                throw new IOException("Bauwerk hat mehr als " + MAX_CELLS + " Blöcke");
            }
            cells.put(p, spec);
        }
    }

    private static String stateString(Map<String, Object> entry) throws IOException {
        Object name = require(entry.get("Name"), "Name");
        StringBuilder sb = new StringBuilder(name.toString());
        if (entry.get("Properties") instanceof Map<?, ?> props && !props.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<?, ?> e : props.entrySet()) {
                sb.append(first ? "" : ",").append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static void checkSize(int w, int h, int l) throws IOException {
        if (w <= 0 || h <= 0 || l <= 0 || w > MAX_EDGE || h > MAX_EDGE || l > MAX_EDGE) {
            throw new IOException("Größe " + w + "×" + h + "×" + l + " außerhalb 1.." + MAX_EDGE);
        }
    }

    private static Object require(Object o, String what) throws IOException {
        if (o == null) {
            throw new IOException(what + " fehlt");
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o, String what) throws IOException {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IOException(what + " fehlt oder ist kein Compound");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String what) throws IOException {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new IOException(what + " fehlt oder ist keine Liste");
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** Sponge speichert Breite/Höhe/Länge als short ohne Vorzeichen. */
    private static int unsigned(Object o) throws IOException {
        if (o instanceof Short s) {
            return Short.toUnsignedInt(s);
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        throw new IOException("Maß fehlt (Width/Height/Length)");
    }

    /** Für Tests und Berichte: Set der vorkommenden Block-IDs. */
    static Set<String> ids(Map<Pos, BlockSpec> cells) {
        Set<String> out = new java.util.TreeSet<>();
        cells.values().forEach(b -> out.add(b.id()));
        return out;
    }
}
