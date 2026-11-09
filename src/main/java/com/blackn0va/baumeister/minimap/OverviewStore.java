package com.blackn0va.baumeister.minimap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Grobe Übersicht der Welt für weites Herauszoomen (bis 64 Blöcke je Kartenpunkt, Nutzerwunsch 2026-10-31): je Chunk
 * 4 × 4 Stichproben (alle 4 Blöcke eine Spalte: Grundfarbe und Höhe), gesammelt in Regionen zu 32 × 32 Chunks.
 *
 * Warum: Die Minikarte lädt nie einen Chunk (Last). Ohne Übersicht wäre alles jenseits der Sichtweite
 * des Servers (~330 Blöcke) leer – Herauszoomen über 4 Blöcke je Punkt zeigte nur Hintergrund. Die Übersicht merkt sich
 * jeden Chunk, den der Server je geladen hat (beim Laden, beim Entladen und bei jedem Kartenlesen), und liegt je Region
 * auf der Platte – so sieht die weite Karte auch Städte, die vor dem letzten Neustart gebaut wurden.
 *
 * Speicher: eine Region 1 024 Chunks × 16 Stichproben × 4 Byte = 64 KB, höchstens maxRegions im Speicher (am längsten
 * nicht gebrauchte fliegt; ist sie geändert, bekommt sie der Aufrufer zum Speichern).
 *
 * Threads: Die Datenhaltung nur auf dem Server-Thread. encode/decode sind rein und laufen auf einem Arbeitsthread
 * (Plattenzugriff). Fehler: decode wirft IOException bei fremden oder kaputten Dateien.
 */
public final class OverviewStore {

    /** Abstand der Stichproben im Chunk; die Stichprobe liegt in der Mitte ihres 4 × 4-Felds (Block 2, 6, 10, 14). */
    public static final int SPACING = 4;
    public static final int PER_CHUNK = 16;
    public static final int REGION = 32;
    private static final int MAGIC = 0x424D4B31;  // "BMK1"
    private static final int PRESENT = 1 << 24;
    /**
     * Seit dem Start live gelesen (Laden, Entladen, Kartenlesen) – nur im Speicher, nie auf der Platte. Ein Scan der
     * Regionsdateien überschreibt alles außer diesen: Die .mca kann älter sein als der Chunk, der gerade im Spiel steht.
     */
    private static final int LIVE = 1 << 25;

    /** Eine Region im Speicher; data[(chunk in Region) * 16 + Stichprobe], 0 = unbekannt. */
    public static final class Region {
        final int[] data;
        boolean dirty;

        Region(int[] data) {
            this.data = data;
        }
    }

    /** Eine geänderte Region zum Speichern – data ist eine Kopie und darf auf einen anderen Thread. */
    public record Saved(String world, long regionKey, int[] data) {
    }

    private final int maxRegions;
    private final Map<String, LinkedHashMap<Long, Region>> worlds = new HashMap<>();
    private final Consumer<Saved> onEvictDirty;
    private int size;

    public OverviewStore(int maxRegions, Consumer<Saved> onEvictDirty) {
        if (maxRegions < 1) {
            throw new IllegalArgumentException("maxRegions " + maxRegions);
        }
        this.maxRegions = maxRegions;
        this.onEvictDirty = onEvictDirty;
    }

    public static int pack(int base, int height) {
        return PRESENT | (Math.clamp(height + 32768, 0, 0xFFFF) << 8) | (base & 0xFF);
    }

    public static boolean present(int v) {
        return (v & PRESENT) != 0;
    }

    public static int base(int v) {
        return v & 0xFF;
    }

    public static int height(int v) {
        return ((v >> 8) & 0xFFFF) - 32768;
    }

    public static long regionKey(int chunkX, int chunkZ) {
        return MinimapImage.chunkKey(chunkX >> 5, chunkZ >> 5);
    }

    /** Stichproben eines Chunks setzen: samples[sz * 4 + sx] (gepackt), Stichprobe sx/sz liegt bei Block sx*4+2. */
    public void putChunk(String world, int chunkX, int chunkZ, int[] samples) {
        Region r = region(world, regionKey(chunkX, chunkZ), true);
        int off = chunkIndex(chunkX, chunkZ) * PER_CHUNK;
        boolean changed = false;
        for (int i = 0; i < PER_CHUNK; i++) {
            if (present(samples[i]) && (r.data[off + i] & ~LIVE) != samples[i]) {
                changed = true;
            }
            if (present(samples[i])) {
                r.data[off + i] = samples[i] | LIVE;
            }
        }
        r.dirty |= changed;
    }

    /**
     * Ergebnis eines Regionsscans (RegionScanner) einsetzen: ersetzt Plattenstand und Lücken, aber keine live gelesene
     * Stichprobe. Geändertes wird gespeichert.
     */
    public void putScan(String world, long regionKey, int[] scanned) {
        Region r = region(world, regionKey, true);
        boolean changed = false;
        for (int i = 0; i < r.data.length && i < scanned.length; i++) {
            if (present(scanned[i]) && (r.data[i] & LIVE) == 0 && r.data[i] != scanned[i]) {
                r.data[i] = scanned[i];
                changed = true;
            }
        }
        r.dirty |= changed;
    }

    /** Aus einer vollen Kachel (MinimapImage.Tile) die 16 Stichproben übernehmen – fällt beim Kartenlesen ab. */
    public void putFromTile(String world, int chunkX, int chunkZ, MinimapImage.Tile t) {
        int[] s = new int[PER_CHUNK];
        for (int sz = 0; sz < 4; sz++) {
            for (int sx = 0; sx < 4; sx++) {
                int c = (sz * SPACING + SPACING / 2) * 16 + sx * SPACING + SPACING / 2;
                s[sz * 4 + sx] = pack(t.base()[c] & 0xFF, t.height()[c]);
            }
        }
        putChunk(world, chunkX, chunkZ, s);
    }

    /** Gepackte Stichprobe für Block (x, z) oder 0, wenn unbekannt oder die Region nicht im Speicher ist. */
    public int sample(String world, int x, int z) {
        LinkedHashMap<Long, Region> m = worlds.get(world);
        if (m == null) {
            return 0;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        Region r = m.get(regionKey(cx, cz));
        if (r == null) {
            return 0;
        }
        return r.data[chunkIndex(cx, cz) * PER_CHUNK + ((z & 15) / SPACING) * 4 + (x & 15) / SPACING];
    }

    public boolean hasRegion(String world, long regionKey) {
        LinkedHashMap<Long, Region> m = worlds.get(world);
        return m != null && m.containsKey(regionKey);
    }

    /**
     * Von der Platte gelesene Region einsetzen. Was im Speicher schon bekannt ist, ist neuer (seit dem Start gelesen) und
     * bleibt; die Platte füllt nur Lücken.
     */
    public void putRegion(String world, long regionKey, int[] fromDisk) {
        Region r = region(world, regionKey, true);
        for (int i = 0; i < r.data.length && i < fromDisk.length; i++) {
            if (!present(r.data[i]) && present(fromDisk[i])) {
                r.data[i] = fromDisk[i];
            }
        }
    }

    /** Alle geänderten Regionen als Kopie zum Speichern; sie gelten danach als gespeichert. */
    public List<Saved> takeDirty() {
        List<Saved> out = new ArrayList<>();
        worlds.forEach((w, m) -> m.forEach((k, r) -> {
            if (r.dirty) {
                out.add(new Saved(w, k, r.data.clone()));
                r.dirty = false;
            }
        }));
        return out;
    }

    public int regions() {
        return size;
    }

    public void forgetWorld(String world) {
        LinkedHashMap<Long, Region> m = worlds.remove(world);
        if (m != null) {
            size -= m.size();
            m.forEach((k, r) -> {
                if (r.dirty) {
                    onEvictDirty.accept(new Saved(world, k, r.data));
                }
            });
        }
    }

    private Region region(String world, long key, boolean create) {
        LinkedHashMap<Long, Region> m = worlds.computeIfAbsent(world, w -> new LinkedHashMap<>(64, 0.75f, true));
        Region r = m.get(key);
        if (r == null && create) {
            r = new Region(new int[REGION * REGION * PER_CHUNK]);
            m.put(key, r);
            size++;
            while (size > maxRegions) {
                evictOne(world, key);
            }
        }
        return r;
    }

    /** Älteste Region der größten Welt – nie die, die gerade angelegt wurde. */
    private void evictOne(String keepWorld, long keepKey) {
        String biggest = null;
        for (Map.Entry<String, LinkedHashMap<Long, Region>> w : worlds.entrySet()) {
            if (biggest == null || w.getValue().size() > worlds.get(biggest).size()) {
                biggest = w.getKey();
            }
        }
        if (biggest == null) {
            return;
        }
        Iterator<Map.Entry<Long, Region>> it = worlds.get(biggest).entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Region> e = it.next();
            if (biggest.equals(keepWorld) && e.getKey() == keepKey) {
                continue;
            }
            it.remove();
            size--;
            if (e.getValue().dirty) {
                onEvictDirty.accept(new Saved(biggest, e.getKey(), e.getValue().data));
            }
            return;
        }
    }

    private static int chunkIndex(int chunkX, int chunkZ) {
        return (chunkZ & (REGION - 1)) * REGION + (chunkX & (REGION - 1));
    }

    // ------------------------------------------------------------------ Datei (rein, Arbeitsthread)

    /** Region als Datei: Kennung, Länge, Daten – gzip, weil unbekannte Chunks (0) den Großteil ausmachen. */
    public static byte[] encode(int[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(MAGIC);
            out.writeInt(data.length);
            for (int v : data) {
                out.writeInt(v & ~LIVE);  // „live“ gilt nur bis zum nächsten Start
            }
        }
        return bytes.toByteArray();
    }

    public static int[] decode(byte[] file) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(file)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("keine Übersichtsdatei (Kennung fehlt)");
            }
            int n = in.readInt();
            if (n != REGION * REGION * PER_CHUNK) {
                throw new IOException("falsche Länge " + n);
            }
            int[] data = new int[n];
            for (int i = 0; i < n; i++) {
                data[i] = in.readInt();
            }
            return data;
        }
    }
}
