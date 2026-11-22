package com.blackn0va.baumeister.town.work;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Die Arbeitsfelder, persistiert in plugins/Baumeister/stadtleben-felder.json (AK-43).
 *
 * Unlesbare Datei = kaputt: Dann gibt es keine Felder (Bewohner ändern nichts), und die Datei wird nie überschrieben –
 * setzen und entfernen lehnen ab, bis der Admin sie repariert oder löscht. Vorher stehende Felder kämen sonst nach dem
 * nächsten „setzen“ nie zurück (wie RucksackFile.check). Geschrieben wird über eine Nachbardatei und ATOMIC_MOVE.
 *
 * Threads: Hauptthread (Befehl und Takt). Fehler: IOException beim Schreiben an den Befehl; Lesefehler → broken().
 */
public final class Fields {

    public static final String FILE = "stadtleben-felder.json";
    /** Mehr Felder braucht keine Stadt; begrenzt auch, was je Takt durchsucht wird. */
    public static final int MAX = 32;

    private final Path file;
    private final Map<String, Field> byName = new LinkedHashMap<>();
    private String broken;

    /** file = null: nur im Speicher (Tests). */
    public Fields(Path file) {
        this.file = file;
    }

    /** Lesen; fehlt die Datei, gibt es keine Felder. Einzelne kaputte Einträge fallen heraus (Grund in skipped). */
    public List<String> load() {
        byName.clear();
        broken = null;
        List<String> skipped = new ArrayList<>();
        if (file == null || !Files.exists(file)) {
            return skipped;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonArray all = root.getAsJsonObject().getAsJsonArray("felder");
            for (JsonElement e : all) {
                JsonObject o = e.getAsJsonObject();
                String name = Field.key(o.get("name").getAsString());
                int radius = o.get("radius").getAsInt();
                String problem = Field.check(name, radius);
                if (problem != null || byName.size() >= MAX || byName.containsKey(name)) {
                    skipped.add(name + ": " + (problem != null ? problem : "doppelt oder zu viele"));
                    continue;
                }
                UUID owner = o.has("besitzer") && !o.get("besitzer").isJsonNull()
                        ? UUID.fromString(o.get("besitzer").getAsString()) : null;
                byName.put(name, new Field(name, o.get("welt").getAsString(), o.get("x").getAsInt(),
                        o.get("y").getAsInt(), o.get("z").getAsInt(), radius, owner));
            }
        } catch (IOException | RuntimeException e) {
            // Gson wirft IllegalStateException/JsonParseException/NullPointerException bei falscher Form – alles
            // „kaputt“: keine Felder, kein Überschreiben.
            byName.clear();
            broken = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return skipped;
    }

    /** null = in Ordnung, sonst warum die Datei als kaputt gilt. */
    public String broken() {
        return broken;
    }

    /** Neu oder ersetzt (gleicher Name). null = gespeichert, sonst der Satz für den Admin. */
    public String put(Field f) throws IOException {
        if (broken != null) {
            return "Die Felddatei " + FILE + " ist unlesbar (" + broken + ") – erst reparieren oder löschen.";
        }
        String problem = Field.check(f.name(), f.radius());
        if (problem != null) {
            return problem;
        }
        if (!byName.containsKey(f.name()) && byName.size() >= MAX) {
            return "Höchstens " + MAX + " Felder.";
        }
        Field before = byName.put(f.name(), f);
        try {
            save();
        } catch (IOException e) {
            if (before == null) {
                byName.remove(f.name());
            } else {
                byName.put(f.name(), before);
            }
            throw e;
        }
        return null;
    }

    /** true = es gab das Feld und es ist weg. */
    public boolean remove(String name) throws IOException {
        if (broken != null) {
            throw new IOException("Felddatei unlesbar: " + broken);
        }
        Field before = byName.remove(Field.key(name));
        if (before == null) {
            return false;
        }
        try {
            save();
        } catch (IOException e) {
            byName.put(before.name(), before);
            throw e;
        }
        return true;
    }

    public List<Field> all() {
        List<Field> out = new ArrayList<>(byName.values());
        out.sort(Comparator.comparing(Field::name));
        return out;
    }

    public Field get(String name) {
        return byName.get(Field.key(name));
    }

    /** Nächstes Feld in der Welt höchstens range Blöcke (waagrecht, Mitte) von (x, z); null = keins. */
    public Field nearest(String world, double x, double z, double range) {
        Field best = null;
        for (Field f : byName.values()) {
            if (f.world().equals(world) && f.distance(x, z) <= range
                    && (best == null || f.distance(x, z) < best.distance(x, z))) {
                best = f;
            }
        }
        return best;
    }

    private void save() throws IOException {
        if (file == null) {
            return;
        }
        JsonArray all = new JsonArray();
        for (Field f : all()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", f.name());
            o.addProperty("welt", f.world());
            o.addProperty("x", f.x());
            o.addProperty("y", f.y());
            o.addProperty("z", f.z());
            o.addProperty("radius", f.radius());
            o.addProperty("besitzer", f.owner() == null ? null : f.owner().toString());
            all.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("fassung", 1);
        root.add("felder", all);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".neu");
        Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
