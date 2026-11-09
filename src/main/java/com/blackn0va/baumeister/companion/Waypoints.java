package com.blackn0va.baumeister.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gemerkte Orte je Spieler für den Begleiter („Vera, merk dir das hier als Zuhause“ → später „bring mich nach Hause“).
 *
 * Wunsch 2026-11-04. Vorher konnte Vera nur Blöcke suchen; wer seine Basis verlor, musste
 * Koordinaten notieren. Gespeichert wird als JSON neben der config.yml (CompanionService schreibt, dieser Teil ist
 * rein und ohne Server testbar). Grenzen gegen volllaufende Dateien: {@link #MAX_PER_PLAYER} Orte je Spieler, Namen
 * höchstens {@link #MAX_NAME} Zeichen aus Buchstaben, Ziffern, Leerzeichen, - und _.
 *
 * Threads: nicht thread-sicher – nur im Hauptthread benutzen; toJson liefert eine Momentaufnahme fürs asynchrone
 * Schreiben. Fehler: fromJson überspringt kaputte Einträge statt zu werfen (eine beschädigte Datei kostet einzelne
 * Orte, nicht den Plugin-Start).
 */
public final class Waypoints {

    public static final int MAX_PER_PLAYER = 20;
    public static final int MAX_NAME = 32;

    /** Ein Ort: Welt per UID (Umbenennen der Welt schadet nicht), Blickrichtung für die Ankunft. */
    public record Point(UUID world, double x, double y, double z, float yaw) {
    }

    /** Ergebnis von {@link #mark}. */
    public enum Marked {
        NEW, REPLACED, FULL, BAD_NAME
    }

    private final Map<UUID, Map<String, Point>> points = new LinkedHashMap<>();

    /** „Zuhause!“, „  Meine   Basis “ → „zuhause“, „meine basis“; null bei leerem oder unerlaubtem Namen. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} _-]", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty() || s.length() > MAX_NAME) {
            return null;
        }
        return s;
    }

    public Marked mark(UUID player, String rawName, Point p) {
        String name = normalize(rawName);
        if (name == null) {
            return Marked.BAD_NAME;
        }
        Map<String, Point> mine = points.computeIfAbsent(player, k -> new LinkedHashMap<>());
        if (mine.containsKey(name)) {
            mine.put(name, p);
            return Marked.REPLACED;
        }
        if (mine.size() >= MAX_PER_PLAYER) {
            return Marked.FULL;
        }
        mine.put(name, p);
        return Marked.NEW;
    }

    /** Genauer Name, sonst eindeutiger Anfang („haus“ findet „haus am see“, wenn es nur einen gibt). */
    public Optional<Map.Entry<String, Point>> find(UUID player, String rawName) {
        String name = normalize(rawName);
        Map<String, Point> mine = points.get(player);
        if (name == null || mine == null) {
            return Optional.empty();
        }
        if (mine.containsKey(name)) {
            return Optional.of(Map.entry(name, mine.get(name)));
        }
        List<Map.Entry<String, Point>> hits = mine.entrySet().stream()
                .filter(e -> e.getKey().startsWith(name) || e.getKey().contains(" " + name)).toList();
        return hits.size() == 1 ? Optional.of(Map.entry(hits.getFirst().getKey(), hits.getFirst().getValue()))
                : Optional.empty();
    }

    public boolean remove(UUID player, String rawName) {
        String name = normalize(rawName);
        Map<String, Point> mine = points.get(player);
        return name != null && mine != null && mine.remove(name) != null;
    }

    public List<String> names(UUID player) {
        Map<String, Point> mine = points.get(player);
        return mine == null ? List.of() : List.copyOf(mine.keySet());
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        points.forEach((player, mine) -> {
            JsonObject o = new JsonObject();
            mine.forEach((name, p) -> {
                JsonObject q = new JsonObject();
                q.addProperty("welt", p.world().toString());
                q.addProperty("x", p.x());
                q.addProperty("y", p.y());
                q.addProperty("z", p.z());
                q.addProperty("blick", p.yaw());
                o.add(name, q);
            });
            root.add(player.toString(), o);
        });
        return root.toString();
    }

    public static Waypoints fromJson(String json) {
        Waypoints w = new Waypoints();
        JsonElement e;
        try {
            e = JsonParser.parseString(json);
        } catch (RuntimeException ex) {
            return w;
        }
        if (!e.isJsonObject()) {
            return w;
        }
        for (Map.Entry<String, JsonElement> pe : e.getAsJsonObject().entrySet()) {
            UUID player;
            try {
                player = UUID.fromString(pe.getKey());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (!pe.getValue().isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> ne : pe.getValue().getAsJsonObject().entrySet()) {
                try {
                    JsonObject q = ne.getValue().getAsJsonObject();
                    Point p = new Point(UUID.fromString(q.get("welt").getAsString()), q.get("x").getAsDouble(),
                            q.get("y").getAsDouble(), q.get("z").getAsDouble(), q.get("blick").getAsFloat());
                    w.mark(player, ne.getKey(), p);
                } catch (RuntimeException ex) {
                    // Kaputter Eintrag: nur dieser Ort fehlt (siehe Klassenkommentar), der Rest wird geladen.
                    continue;
                }
            }
        }
        return w;
    }
}
