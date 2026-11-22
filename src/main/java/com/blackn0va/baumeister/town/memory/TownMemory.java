package com.blackn0va.baumeister.town.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gedächtnis der Bewohner (Vertrag v013, AK-41): je Bewohner höchstens MAX_ENTRIES kurze Fakten (Fact), jeder läuft
 * nach TTL_MS ab. Gespeichert in plugins/Baumeister/stadtleben/FILE, damit Lotte sich nach einem Neustart noch
 * erinnert, dass Spieler KM nach Brot fragte.
 *
 * Grenzen mit Absicht (Nicht-Ziel „kein dauerhafter Chat-Mitschnitt“):
 * - Nur Fact-Objekte, deren Text aus festen Bausteinen besteht; beim Laden fällt alles heraus, was nicht so aussieht
 *   (Fact.accepts) – auch von Hand eingetragene Zeilen.
 * - 20 Einträge, 7 Tage: Ein Bewohner soll sich an die letzte Woche erinnern, nicht ein Protokoll führen. Ein Fakt,
 *   der wiederkommt, rückt nach hinten und beginnt neu zu laufen, statt den Platz doppelt zu belegen.
 * - forget/forgetAll für /bm stadtleben vergessen; danach wird sofort gespeichert, nicht erst beim nächsten Takt.
 *
 * Threads: nur Hauptthread (TownLife). save() schreibt synchron – die Datei ist klein (12 Bewohner × 20 Zeilen,
 * gemessen ≈ 20 KB) und wird nur bei Änderung, höchstens einmal je Minute und beim Abschalten geschrieben.
 * Fehler: load/save werfen IOException; der Aufrufer loggt und läuft ohne Platte weiter (fail-open mit Absicht: ein
 * Gedächtnis, das nicht speichert, schadet niemandem, ein Stadtleben, das deshalb ausfällt, schon).
 */
public final class TownMemory {

    public static final int MAX_ENTRIES = 20;
    public static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    public static final String FILE = "gedaechtnis.json";  // umlaut-ok: Dateiname
    static final int VERSION = 1;

    /** Ein Eintrag: Fakt und wann er zuletzt geschah. */
    public record Entry(Fact fact, long at) {
    }

    private final Map<String, Deque<Entry>> byResident = new LinkedHashMap<>();
    private final Path file;
    private boolean dirty;

    /** file = null: nur im Speicher. */
    public TownMemory(Path file) {
        this.file = file;
    }

    public static TownMemory inMemory() {
        return new TownMemory(null);
    }

    /** Schlüssel eines Bewohners: Name klein (wie TownTalk.key). */
    static String key(String resident) {
        return resident.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /** Fakt merken; derselbe Fakt rückt nach hinten mit neuer Zeit. Älteste fallen über MAX_ENTRIES heraus. */
    public void remember(String resident, Fact fact, long now) {
        if (resident == null || fact == null) {
            return;
        }
        Deque<Entry> list = byResident.computeIfAbsent(key(resident), k -> new ArrayDeque<>());
        list.removeIf(e -> e.fact().equals(fact));
        list.addLast(new Entry(fact, now));
        while (list.size() > MAX_ENTRIES) {
            list.removeFirst();
        }
        dirty = true;
    }

    /** Gültige Fakten, älteste zuerst; abgelaufene werden dabei entfernt. */
    public List<Entry> entries(String resident, long now) {
        Deque<Entry> list = byResident.get(key(resident));
        if (list == null) {
            return List.of();
        }
        if (expire(list, now)) {
            dirty = true;
        }
        return List.copyOf(list);
    }

    /** Nur die Texte, für den Prompt. */
    public List<String> facts(String resident, long now) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries(resident, now)) {
            out.add(e.fact().text());
        }
        return out;
    }

    /** Alles über einen Bewohner vergessen; Zahl der gelöschten Einträge. */
    public int forget(String resident) {
        Deque<Entry> gone = byResident.remove(key(resident));
        if (gone == null) {
            return 0;
        }
        dirty = true;
        return gone.size();
    }

    /** Alle Bewohner vergessen; Zahl der gelöschten Einträge. */
    public int forgetAll() {
        int n = 0;
        for (Deque<Entry> d : byResident.values()) {
            n += d.size();
        }
        byResident.clear();
        dirty = true;
        return n;
    }

    public int size() {
        int n = 0;
        for (Deque<Entry> d : byResident.values()) {
            n += d.size();
        }
        return n;
    }

    public boolean dirty() {
        return dirty;
    }

    private static boolean expire(Deque<Entry> list, long now) {
        boolean changed = false;
        for (Iterator<Entry> it = list.iterator(); it.hasNext();) {
            if (now - it.next().at() >= TTL_MS) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    /** Aus der Datei lesen (fehlt sie: leer). Abgelaufenes und Fremdes fällt heraus, Überzähliges von vorn. */
    public void load(long now) throws IOException {
        byResident.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // Gson wirft JsonParseException (unchecked) – als IOException weiter, damit der Aufrufer einen Weg hat.
            throw new IOException("gedächtnis_unlesbar", e);
        }
        if (!root.isJsonObject() || !root.getAsJsonObject().has("bewohner")
                || !root.getAsJsonObject().get("bewohner").isJsonObject()) {
            throw new IOException("gedächtnis_format");
        }
        for (Map.Entry<String, JsonElement> r : root.getAsJsonObject().getAsJsonObject("bewohner").entrySet()) {
            if (!r.getValue().isJsonArray()) {
                continue;
            }
            for (JsonElement el : r.getValue().getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                if (!o.has("t") || !o.has("at") || !o.get("t").isJsonPrimitive() || !o.get("at").isJsonPrimitive()) {
                    continue;
                }
                long at;
                try {
                    at = o.get("at").getAsLong();
                } catch (NumberFormatException e) {
                    continue;
                }
                long when = at;
                Fact.restore(o.get("t").getAsString()).filter(f -> now - when < TTL_MS)
                        .ifPresent(f -> remember(r.getKey(), f, when));
            }
        }
        dirty = false;
    }

    /** Schreiben, wenn sich etwas geändert hat; atomar über eine Zwischendatei. true = geschrieben. */
    public boolean save(long now) throws IOException {
        if (file == null || !dirty) {
            return false;
        }
        JsonObject residents = new JsonObject();
        for (Map.Entry<String, Deque<Entry>> r : byResident.entrySet()) {
            expire(r.getValue(), now);
            if (r.getValue().isEmpty()) {
                continue;
            }
            JsonArray a = new JsonArray();
            for (Entry e : r.getValue()) {
                JsonObject o = new JsonObject();
                o.addProperty("t", e.fact().text());
                o.addProperty("at", e.at());
                a.add(o);
            }
            residents.add(r.getKey(), a);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("bewohner", residents);
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "gedaechtnis", ".tmp");  // umlaut-ok: Dateiname
        try {
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        dirty = false;
        return true;
    }
}
