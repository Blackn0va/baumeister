package com.blackn0va.baumeister.earth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Material;

/**
 * Merkliste der Erdwelt-Chunks, die mit geschätzten Höhen entstanden sind (Netz weg, Kachel gesperrt): je Chunk der
 * Fingerabdruck, den der Populator hinterlassen hat – je Säule tiefster und höchster gesetzter Block und das Material
 * ganz oben. EarthRepaint malt diese Chunks neu, sobald die Daten da sind, und erkennt am Fingerabdruck, welche Säulen
 * ein Spieler inzwischen verändert hat (die bleiben).
 *
 * Warum auf der Platte (erde/geschätzt.txt): Ein Netzausfall endet oft erst nach einem Neustart; ohne Datei blieben
 * die groben Chunks für immer grob (Gedächtnis „Neumalen fehlt“). Eine Zeile je Chunk:
 * „cx cz min,max,MATERIAL min,max,MATERIAL …“ (256 Säulen, z-Zeilen zu je 16 x). Unlesbare Zeilen werden übersprungen
 * und gemeldet – lieber einen Chunk nicht neu malen als einen falschen Fingerabdruck anwenden.
 *
 * Threads: alle Methoden synchronisiert (Populator auf den Generator-Threads, EarthRepaint auf dem Hauptthread). save()
 * baut den Text unter dem Schloss, schreibt die Datei aber außerhalb – sonst warteten die Generator-Threads (add) auf
 * die Platte. Fehler: save() wirft IOException; der Aufrufer meldet sie, die Liste im Speicher bleibt.
 */
public final class EstimatedChunks {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /**
     * Mehr merkt sich die Liste nicht (je Chunk ~4 KB im Speicher, ~5 KB Text) – höchstens ~16 MB, auch wenn das Netz
     * einen ganzen Abend weg ist; ältere fallen heraus und bleiben grob. Gemessen: ein Ausfall mit 9 festgehaltenen
     * Chunks hinterließ 81 geschätzte (Vorstufen-Ring).
     */
    static final int LIMIT = 4_000;

    /** Fingerabdruck eines Chunks; Säule i = z · 16 + x. */
    public record Print(int[] minY, int[] maxY, Material[] top) {
    }

    private final Path file;
    private final Map<Long, Print> chunks = new LinkedHashMap<>();
    private boolean dirty;
    /** Zählt jede Änderung: Ein Speichern setzt dirty nur zurück, wenn seitdem nichts dazukam. */
    private long version;
    private long lastSave = System.currentTimeMillis();
    /** Nur ein Schreiben zur Zeit (gemeinsame .tmp-Datei). */
    private final Object writeLock = new Object();

    public EstimatedChunks(Path file) {
        this.file = file;
    }

    public static long key(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xffffffffL);
    }

    public static int cx(long key) {
        return (int) (key >> 32);
    }

    public static int cz(long key) {
        return (int) key;
    }

    /** Merkt einen Chunk (der erste Fingerabdruck gilt: er beschreibt, was wirklich in der Welt steht). */
    public synchronized void add(int cx, int cz, Print p) {
        long k = key(cx, cz);
        if (chunks.containsKey(k)) {
            return;
        }
        if (chunks.size() >= LIMIT) {
            Long oldest = chunks.keySet().iterator().next();
            chunks.remove(oldest);
        }
        chunks.put(k, p);
        changed();
    }

    private void changed() {
        dirty = true;
        version++;
    }

    public synchronized Print get(long key) {
        return chunks.get(key);
    }

    /** Säule i ist neu gemalt: Fingerabdruck auf den neuen Stand (falls der Chunk mittendrin entladen wird). */
    public synchronized void updateColumn(long key, int i, int minY, int maxY, Material top) {
        Print p = chunks.get(key);
        if (p == null) {
            return;
        }
        p.minY()[i] = minY;
        p.maxY()[i] = maxY;
        p.top()[i] = top;
        changed();
    }

    public synchronized void remove(long key) {
        if (chunks.remove(key) != null) {
            changed();
        }
    }

    public synchronized List<Long> keys() {
        return new ArrayList<>(chunks.keySet());
    }

    public synchronized int size() {
        return chunks.size();
    }

    public synchronized void load() throws IOException {
        chunks.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        int skipped = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            try {
                parse(line);
            } catch (IllegalArgumentException e) {
                skipped++;
            }
        }
        int bad = skipped;
        if (bad > 0) {
            LOG.warning(() -> "erde | geschätzt_unlesbar | zeilen=" + bad + " datei=" + file.getFileName());
        }
        dirty = false;
    }

    private void parse(String line) {
        String[] parts = line.trim().split(" ");
        if (parts.length != 2 + 256) {
            throw new IllegalArgumentException("Spaltenzahl " + parts.length);
        }
        int cx = Integer.parseInt(parts[0]);
        int cz = Integer.parseInt(parts[1]);
        int[] min = new int[256];
        int[] max = new int[256];
        Material[] top = new Material[256];
        for (int i = 0; i < 256; i++) {
            String[] c = parts[2 + i].split(",");
            if (c.length != 3) {
                throw new IllegalArgumentException("Säule " + i);
            }
            min[i] = Integer.parseInt(c[0]);
            max[i] = Integer.parseInt(c[1]);
            top[i] = Material.getMaterial(c[2]);
            if (top[i] == null || min[i] > max[i]) {
                throw new IllegalArgumentException("Säule " + i + ": " + parts[2 + i]);
            }
        }
        chunks.put(key(cx, cz), new Print(min, max, top));
    }

    /** Wann zuletzt gespeichert (oder angelegt) wurde, ms seit 1970. */
    public synchronized long lastSaveMillis() {
        return lastSave;
    }

    /**
     * Speichert, wenn sich etwas geändert hat und das letzte Speichern mindestens interval her ist (Takt von
     * EarthRepaint, damit ein Absturz nach einem Netzausfall die Liste nicht kostet).
     */
    public void saveIfDue(long nowMillis, long interval, Consumer<IOException> onError) {
        synchronized (this) {
            if (nowMillis - lastSave < interval) {
                return;
            }
            lastSave = nowMillis;
        }
        try {
            save();
        } catch (IOException e) {
            onError.accept(e);
        }
    }

    /** Schreibt nur, wenn sich etwas geändert hat; erst .tmp, dann umbenennen (kein halber Stand nach Absturz). */
    public void save() throws IOException {
        synchronized (writeLock) {
            String text;
            long saved;
            synchronized (this) {
                if (!dirty) {
                    return;
                }
                StringBuilder sb = new StringBuilder(
                        "# Geschätzte Erdwelt-Chunks (Netz weg) – EarthRepaint malt sie neu\n");
                for (Map.Entry<Long, Print> e : chunks.entrySet()) {
                    sb.append(cx(e.getKey())).append(' ').append(cz(e.getKey()));
                    Print p = e.getValue();
                    for (int i = 0; i < 256; i++) {
                        sb.append(String.format(Locale.ROOT, " %d,%d,%s", p.minY()[i], p.maxY()[i],
                                p.top()[i].name()));
                    }
                    sb.append('\n');
                }
                text = sb.toString();
                saved = version;
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            synchronized (this) {
                if (version == saved) {
                    dirty = false;
                }
            }
        }
    }
}
