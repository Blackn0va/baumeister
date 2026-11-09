package com.blackn0va.baumeister;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Baubuch: Was der Baumeister gebaut hat, Block für Block mit Zustand – damit Vera es reparieren kann (Wunsch
 * 2026-11-04 → „mach das so“: nur Bauten des Baumeisters, deren Soll-Zustand das Plugin kennt).
 *
 * Vorher gab es nur „rückgängig“ (der Zustand VOR dem Bau) und das Gedächtnis (Materialien des letzten Baus, eine
 * Stunde, im Speicher). Beides reicht für Maschinen nicht: Ein Verstärker braucht Richtung und Verzögerung, ein Kolben
 * seine Ausrichtung.
 *
 * Ablage: plugins/Baumeister/bauten/index.json (Rahmen, Besitzer, Auftrag) und je Bau eine gzip-Datei mit Palette und
 * Zellen. Luft wird nicht gespeichert – repariert wird, was fehlt oder ersetzt wurde, nicht was jemand dazugebaut hat.
 *
 * Threads: alle öffentlichen Methoden synchronisiert; add/forget laufen außerhalb des Hauptthreads (Datei-E/A), at/
 * cells/damaged im Hauptthread. Fehler: E/A-Fehler beim Schreiben werden geloggt und verworfen (bewusst fail-open – ein
 * fehlender Eintrag heißt nur „nicht reparierbar“), beim Lesen gehen sie als IOException an den Aufrufer.
 */
final class BuildLedger {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Größer (Stadt, Weltbau) nicht: 100 000 Zellen sind gepackt ~0,5 MB und in einem Tick vergleichbar. */
    static final int MAX_CELLS = 100_000;
    /** Älteste fallen heraus. */
    static final int MAX_ENTRIES = 500;

    /** Ein Block im Soll-Zustand; data wie BlockData.getAsString() („minecraft:repeater[delay=2,…]“). */
    record Cell(int x, int y, int z, String data) {
        String material() {
            int b = data.indexOf('[');
            return b < 0 ? data : data.substring(0, b);
        }
    }

    record Entry(String id, UUID owner, String ownerName, String order, UUID world, long createdMillis,
            long undoMillis, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int cells, String info) {

        boolean contains(UUID w, int x, int y, int z, int margin) {
            return world.equals(w) && x >= minX - margin && x <= maxX + margin && y >= minY - margin
                    && y <= maxY + margin && z >= minZ - margin && z <= maxZ + margin;
        }

        boolean intersects(Entry o) {
            return world.equals(o.world) && minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
                    && minZ <= o.maxZ && maxZ >= o.minZ;
        }

        long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    private final Path dir;
    private final List<Entry> index = new ArrayList<>();  // neueste zuerst

    BuildLedger(Path dir) {
        this.dir = dir;
        Path idx = dir.resolve("index.json");
        if (Files.isRegularFile(idx)) {
            try {
                Entry[] all = GSON.fromJson(Files.readString(idx, StandardCharsets.UTF_8), Entry[].class);
                if (all != null) {
                    index.addAll(List.of(all));
                }
            } catch (IOException | RuntimeException e) {
                // Kaputter Index: mit leerem Baubuch weiter – reparieren geht dann erst für neue Bauten (fail-open).
                LOG.log(Level.WARNING, "baubuch | index_unlesbar | datei=" + idx, e);
            }
        }
    }

    /**
     * Neuen Bau eintragen. replace: Änderung („update“) eines Baus desselben Besitzers – ältere Einträge, die den neuen
     * Rahmen schneiden, fallen weg, sonst setzte die Reparatur Weggeräumtes wieder hin.
     */
    synchronized Optional<Entry> add(UUID owner, String ownerName, String order, UUID world, long undoMillis,
            List<Cell> cells, boolean replace) {
        return add(owner, ownerName, order, world, undoMillis, cells, replace, "");
    }

    /**
     * info: was der Baumeister zum Bau gesagt hat plus die Bedienhinweise seiner Bauteile – damit Vera erklären kann,
     * wie ein Computer oder eine Farm benutzt wird (Wunsch 2026-11-04 → Vera schlug das Rezept eines Komparators nach).
     * Alte Einträge haben kein info (null).
     */
    synchronized Optional<Entry> add(UUID owner, String ownerName, String order, UUID world, long undoMillis,
            List<Cell> cells, boolean replace, String info) {
        if (cells.isEmpty() || cells.size() > MAX_CELLS) {
            return Optional.empty();
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Cell c : cells) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
            minZ = Math.min(minZ, c.z());
            maxX = Math.max(maxX, c.x());
            maxY = Math.max(maxY, c.y());
            maxZ = Math.max(maxZ, c.z());
        }
        Entry e = new Entry(UUID.randomUUID().toString(), owner, ownerName, order == null ? "" : order, world,
                System.currentTimeMillis(), undoMillis, minX, minY, minZ, maxX, maxY, maxZ, cells.size(),
                info == null ? "" : info);
        try {
            Files.createDirectories(dir);
            writeCells(dir.resolve(e.id() + ".json.gz"), cells);
            if (replace) {
                index.removeIf(old -> {
                    boolean gone = old.owner() != null && old.owner().equals(owner) && old.intersects(e);
                    if (gone) {
                        deleteQuietly(old);
                    }
                    return gone;
                });
            }
            index.addFirst(e);
            while (index.size() > MAX_ENTRIES) {
                deleteQuietly(index.removeLast());
            }
            writeIndex();
            return Optional.of(e);
        } catch (IOException | RuntimeException ex) {
            LOG.log(Level.WARNING, "baubuch | schreiben_fehlgeschlagen | auftrag=" + Texts.clean(order, 60), ex);
            return Optional.empty();
        }
    }

    /** Nach „rückgängig“: der Bau steht nicht mehr – nicht wieder aufbauen. */
    synchronized void forget(UUID owner, long undoMillis) {
        boolean changed = index.removeIf(e -> {
            boolean gone = e.undoMillis() == undoMillis && (owner == null || owner.equals(e.owner()));
            if (gone) {
                deleteQuietly(e);
            }
            return gone;
        });
        if (changed) {
            try {
                writeIndex();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "baubuch | index_schreiben | grund=" + e.getMessage(), e);
            }
        }
    }

    /** Kleinster Bau, dessen Rahmen (± margin) die Stelle enthält – innen in einer Farm neben dem Haus zählt die Farm. */
    synchronized Optional<Entry> at(UUID world, int x, int y, int z, int margin) {
        return index.stream().filter(e -> e.contains(world, x, y, z, margin)).min(Comparator.comparingLong(Entry::volume));
    }

    synchronized List<Entry> entries() {
        return List.copyOf(index);
    }

    List<Cell> cells(Entry e) throws IOException {
        Path file = dir.resolve(e.id() + ".json.gz");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            JsonObject root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray palette = root.getAsJsonArray("palette");
            JsonArray xyz = root.getAsJsonArray("zellen");  // umlaut-ok: ASCII-Schlüssel der Datei
            List<Cell> out = new ArrayList<>(xyz.size() / 4);
            for (int i = 0; i + 3 < xyz.size(); i += 4) {
                out.add(new Cell(xyz.get(i).getAsInt(), xyz.get(i + 1).getAsInt(), xyz.get(i + 2).getAsInt(),
                        palette.get(xyz.get(i + 3).getAsInt()).getAsString()));
            }
            return out;
        } catch (RuntimeException ex) {
            throw new IOException("Baubuch-Datei unlesbar: " + file.getFileName(), ex);
        }
    }

    /** Aktuelles Material an (x, y, z) als „minecraft:stone“. */
    @FunctionalInterface
    interface MaterialAt {
        String at(int x, int y, int z);
    }

    /**
     * Was fehlt oder durch ein anderes Material ersetzt wurde. Zustände desselben Materials (Hebel umgelegt, Tür offen,
     * Lampe an) gelten nicht als Schaden: Das ist Benutzung – zurücksetzen hieße, die Maschine zu verstellen.
     * Von unten nach oben, damit Getragenes Halt hat.
     */
    static List<Cell> damaged(List<Cell> cells, MaterialAt current) {
        List<Cell> out = new ArrayList<>();
        for (Cell c : cells) {
            if (!c.material().equals(current.at(c.x(), c.y(), c.z()))) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparingInt(Cell::y));
        return out;
    }

    private void writeCells(Path file, List<Cell> cells) throws IOException {
        Map<String, Integer> pal = new HashMap<>();
        JsonArray palette = new JsonArray();
        JsonArray xyz = new JsonArray();
        for (Cell c : cells) {
            Integer i = pal.get(c.data());
            if (i == null) {
                i = palette.size();
                pal.put(c.data(), i);
                palette.add(c.data());
            }
            xyz.add(c.x());
            xyz.add(c.y());
            xyz.add(c.z());
            xyz.add(i);
        }
        JsonObject root = new JsonObject();
        root.add("palette", palette);
        root.add("zellen", xyz);  // umlaut-ok: ASCII-Schlüssel der Datei
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            out.write(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeIndex() throws IOException {
        Path idx = dir.resolve("index.json");
        Path tmp = dir.resolve("index.json.tmp");
        Files.writeString(tmp, GSON.toJson(index), StandardCharsets.UTF_8);
        Files.move(tmp, idx, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void deleteQuietly(Entry e) {
        try {
            Files.deleteIfExists(dir.resolve(e.id() + ".json.gz"));
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "baubuch | löschen_fehlgeschlagen | id=" + e.id(), ex);
        }
    }
}
