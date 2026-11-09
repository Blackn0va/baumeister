package com.blackn0va.baumeister.earth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Letzte Stelle je Spieler in der Erdwelt ({x, y, z, yaw, pitch}) für /bm erde, gespeichert in erde/besuche.json.
 *
 * Warum eine eigene Klasse mit eigenem Schreib-Thread: Vorher lief das Speichern im Pool der Reisen, und der Stopp rief
 * pool.shutdownNow() – das warf ein eingereihtes Speichern weg und unterbrach ein laufendes (Files.writeString bricht
 * dann mit ClosedByInterruptException ab). Wer kurz vor dem Stopp die Erdwelt verließ, verlor seine Stelle
 * (Prüferbefund 2026-10-28). Jetzt schreibt close() den letzten Stand selbst, synchron und ohne Unterbrechung. Wer
 * close() wieder auf shutdownNow() allein zurückdreht, verliert diese Stelle wieder.
 *
 * Reihenfolge: Jedes Speichern trägt eine laufende Nummer; ein älterer Stand überschreibt nie einen neueren (sonst
 * könnte ein eingereihter Auftrag nach close() die Datei auf einen alten Stand zurücksetzen).
 *
 * Threads: remember/get von beliebigen Threads (Bukkit-Ereignisse auf dem Hauptthread), Schreiben auf dem eigenen
 * Daemon-Thread bzw. in close(). Fehler: Speichern/Laden nur geloggt – reine Bequemlichkeit, ohne Datei startet
 * /bm erde in Hepberg.
 */
final class VisitStore {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    private final Path file;
    private final ExecutorService saver;
    private final Map<UUID, double[]> visits = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Object saveLock = new Object();
    /** Nummer des zuletzt geschriebenen Stands (nur unter saveLock). */
    private long written;

    VisitStore(Path file) {
        this(file, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Baumeister-Erde-Besuche");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Mit eigenem Executor (Tests: einer, der gerade blockiert ist). */
    VisitStore(Path file, ExecutorService saver) {
        this.file = file;
        this.saver = saver;
    }

    double[] get(UUID id) {
        return visits.get(id);
    }

    /** Stelle merken und im Hintergrund speichern. */
    void remember(UUID id, double[] where) {
        visits.put(id, where.clone());
        long seq = sequence.incrementAndGet();
        String json = toJson(visits).toString();
        saver.execute(() -> save(json, seq));
    }

    /** Beim Plugin-Stopp: keine neuen Aufträge mehr, letzten Stand jetzt im aufrufenden Thread schreiben. */
    void close() {
        saver.shutdown();
        if (sequence.get() == 0) {
            // Nichts Neues gemerkt: nicht schreiben. Sonst überschriebe ein Stopp nach gescheitertem Start (load() lief
            // nie) die gespeicherten Stellen mit {}.
            return;
        }
        save(toJson(visits).toString(), sequence.incrementAndGet());
    }

    static JsonObject toJson(Map<UUID, double[]> visits) {
        JsonObject o = new JsonObject();
        visits.forEach((id, v) -> {
            JsonArray a = new JsonArray();
            for (double d : v) {
                a.add(d);
            }
            o.add(id.toString(), a);
        });
        return o;
    }

    private void save(String json, long seq) {
        synchronized (saveLock) {
            if (seq <= written) {
                return;
            }
            try {
                Path dir = file.getParent();
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, "besuche", ".tmp");
                try {
                    Files.writeString(tmp, json + "\n", StandardCharsets.UTF_8);
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(tmp);
                }
                written = seq;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "erde | besuche_speichern_fehlgeschlagen | datei=" + file, e);
            }
        }
    }

    /** Gespeicherte Stellen einlesen (beim Start). */
    void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> m : o.entrySet()) {
                JsonArray a = m.getValue().getAsJsonArray();
                if (a.size() != 5) {
                    continue;
                }
                double[] v = new double[5];
                for (int i = 0; i < 5; i++) {
                    v[i] = a.get(i).getAsDouble();
                }
                visits.put(UUID.fromString(m.getKey()), v);
            }
        } catch (IOException | JsonParseException | IllegalStateException | IllegalArgumentException e) {
            // Nur Bequemlichkeit: ohne die Datei startet /bm erde eben in Hepberg.
            LOG.log(Level.WARNING, "erde | besuche_laden_fehlgeschlagen | datei=" + file, e);
        }
    }
}
