package com.blackn0va.baumeister.minimap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Zwischenspeicher der Kartenkacheln je Welt, mit fester Obergrenze (am längsten nicht gebrauchte Kachel fliegt) und
 * Alter je Kachel. Warum ein eigener Speicher statt jedes Bild neu aus der Welt: Ein Bild bei 1 Block je Pixel braucht
 * 64 Chunks; sie bei jedem Auffrischen (2× je Sekunde und Spieler) neu zu lesen, kostete den Server-Thread genau die
 * Zeit, die Cartographer2 auf dev01 verbrannt hat (2026-10-31). So liest der Dienst jeden Chunk nur,
 * wenn er fehlt, veraltet oder geändert ist.
 *
 * Threads: nur Server-Thread (wie alle Weltzugriffe). Fehler: keine.
 */
public final class TileCache {

    private record Entry(MinimapImage.Tile tile, long readAt) {
    }

    private final int maxTiles;
    private final Map<String, LinkedHashMap<Long, Entry>> worlds = new HashMap<>();
    /** Geänderte Chunks je Welt – eigene Menge, damit markDirty die Verdrängungsreihenfolge nicht berührt. */
    private final Map<String, Set<Long>> dirty = new HashMap<>();
    private int size;

    public TileCache(int maxTiles) {
        if (maxTiles < 1) {
            throw new IllegalArgumentException("maxTiles " + maxTiles);
        }
        this.maxTiles = maxTiles;
    }

    /** Kachel oder null; zählt als Gebrauch (rückt in der Verdrängungsreihenfolge nach hinten). */
    public MinimapImage.Tile get(String world, int chunkX, int chunkZ) {
        LinkedHashMap<Long, Entry> m = worlds.get(world);
        Entry e = m == null ? null : m.get(MinimapImage.chunkKey(chunkX, chunkZ));
        return e == null ? null : e.tile();
    }

    /** Muss die Kachel (neu) gelesen werden? Fehlt, als geändert markiert oder älter als maxAgeMs. */
    public boolean needsRead(String world, long key, long nowMs, long maxAgeMs) {
        LinkedHashMap<Long, Entry> m = worlds.get(world);
        Entry e = m == null ? null : m.get(key);
        return e == null || nowMs - e.readAt() >= maxAgeMs || dirty.getOrDefault(world, Set.of()).contains(key);
    }

    public void put(String world, long key, MinimapImage.Tile tile, long nowMs) {
        LinkedHashMap<Long, Entry> m = worlds.computeIfAbsent(world, w -> new LinkedHashMap<>(256, 0.75f, true));
        Set<Long> d = dirty.get(world);
        if (d != null) {
            d.remove(key);
        }
        if (m.put(key, new Entry(tile, nowMs)) == null) {
            size++;
        }
        while (size > maxTiles) {
            evictOne();
        }
    }

    /** Ein Block im Chunk hat sich geändert (Bau, Abbau) – beim nächsten Bild neu lesen, die alte Kachel bleibt solange. */
    public void markDirty(String world, int chunkX, int chunkZ) {
        LinkedHashMap<Long, Entry> m = worlds.get(world);
        long key = MinimapImage.chunkKey(chunkX, chunkZ);
        // containsKey statt get: get zählte als Gebrauch und hielte geänderte, nie angesehene Kacheln im Speicher.
        if (m != null && m.containsKey(key)) {
            dirty.computeIfAbsent(world, w -> new HashSet<>()).add(key);
        }
    }

    public void forgetWorld(String world) {
        LinkedHashMap<Long, Entry> m = worlds.remove(world);
        dirty.remove(world);
        if (m != null) {
            size -= m.size();
        }
    }

    public int size() {
        return size;
    }

    private void evictOne() {
        // Älteste Kachel der größten Welt: Eine Welt, in der gerade niemand ist, soll nicht alles verdrängen.
        String biggest = null;
        for (Map.Entry<String, LinkedHashMap<Long, Entry>> w : worlds.entrySet()) {
            if (biggest == null || w.getValue().size() > worlds.get(biggest).size()) {
                biggest = w.getKey();
            }
        }
        if (biggest == null || worlds.get(biggest).isEmpty()) {
            size = 0;
            return;
        }
        Iterator<Long> it = worlds.get(biggest).keySet().iterator();
        long gone = it.next();
        it.remove();
        size--;
        Set<Long> d = dirty.get(biggest);
        if (d != null) {
            d.remove(gone);
        }
    }
}
