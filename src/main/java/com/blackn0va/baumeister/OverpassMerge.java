package com.blackn0va.baumeister;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Führt Overpass-Antworten zusammen – für Weltbau-Kacheln über der Datumsgrenze, die in zwei Abfragen (je eine Seite)
 * geladen werden (WorldTiles.GeoBox.parts). Ergebnis ist eine Antwort im selben Format („elements“), die OsmImport
 * wie jede andere liest und die als EINE Datei im Zwischenspeicher landet.
 *
 * Doppelte Elemente: Ein Weg über die Datumsgrenze (Straße auf Taveuni, Fidschi) kommt in beiden Antworten mit voller
 * Geometrie. Er zählt einmal – Schlüssel Typ + id, das erste Vorkommen gilt. Doppelt gebaut wäre er harmlos (gleiche
 * Blöcke), aber Gebäude zählten doppelt und Straßen würden zweimal geplant.
 *
 * Threads: rein. Fehler: IllegalArgumentException, wenn eine Antwort kein JSON-Objekt ist (der Weltbau gibt die Kachel
 * dann auf, wie bei einer kaputten Einzelantwort).
 */
final class OverpassMerge {

    private OverpassMerge() {
    }

    static String merge(List<String> answers) {
        JsonArray out = new JsonArray();
        Set<String> seen = new HashSet<>();
        JsonObject first = null;
        for (String a : answers) {
            JsonElement root;
            try {
                root = JsonParser.parseString(a);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Kartendienst: Antwort ist kein JSON (" + e.getMessage() + ")", e);
            }
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Kartendienst: Antwort ist kein JSON-Objekt");
            }
            JsonObject o = root.getAsJsonObject();
            if (first == null) {
                first = o;
            }
            if (!o.has("elements") || !o.get("elements").isJsonArray()) {
                continue;  // leere Seite (nur Meer) – Overpass schickt dann trotzdem „elements“, aber sicher ist sicher
            }
            for (JsonElement el : o.getAsJsonArray("elements")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject e = el.getAsJsonObject();
                String type = e.has("type") ? e.get("type").getAsString() : "?";
                String id = e.has("id") ? e.get("id").getAsString() : null;
                if (id == null || seen.add(type + "/" + id)) {
                    out.add(e);
                }
            }
        }
        JsonObject merged = new JsonObject();
        if (first != null && first.has("version")) {
            merged.add("version", first.get("version"));
        }
        merged.addProperty("generator", "Baumeister: " + answers.size() + " Overpass-Antworten zusammengeführt");
        if (first != null && first.has("osm3s")) {
            merged.add("osm3s", first.get("osm3s"));
        }
        merged.add("elements", out);
        return merged.toString();
    }
}
