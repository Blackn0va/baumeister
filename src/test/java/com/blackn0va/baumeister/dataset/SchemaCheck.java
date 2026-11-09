package com.blackn0va.baumeister.dataset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kann die Grammatik des Hosts diesen Plan überhaupt erzeugen? Geprüft gegen plan-schema.json: Kopffelder in fester
 * Folge, Felder je Schritt in Schema-Reihenfolge, Aufzählungen, Zahlengrenzen, Längen, höchstens 60 Schritte.
 *
 * Warum: Der Host lässt das Modell nur schreiben, was host/src/plan/bauplan.gbnf erlaubt – und die Grammatik legt die
 * Feldreihenfolge fest (op, block, name, expr, from, to, pos, radius, height, hollow, floors, window_block, …).
 * PlanParser ist dagegen großzügig; PlanReview merkt eine falsche Reihenfolge also nie. Gemessen 2026-11-01: 214 von
 * 4000 Lehrplänen (alle Hochhaus-Schnellaufträge, QuickOrders.skyscraperJson schreibt window_block vor from) hätte
 * die Grammatik nie erzeugen können – das Modell hätte ein Format gelernt, das es beim Planen nicht schreiben darf.
 *
 * Quelle ist das Schema, nicht eine dritte Kopie der Regeln: GrammarDriftTest hält Schema und GBNF gleich (Felder,
 * Reihenfolge, Grenzen). Nicht geprüft: Leerraum (die Grammatik erlaubt höchstens einen Zeilenumbruch plus 8 Zeichen
 * zwischen Token; PlanText schreibt kompakt) und Steuerzeichen in Texten.
 *
 * Threads: zustandslos. Fehler: keine – ein Befund kommt als Text zurück.
 */
final class SchemaCheck {

    private SchemaCheck() {
    }

    static Optional<String> problem(String json, JsonObject schema) {
        JsonObject plan;
        try {
            plan = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return Optional.of("kein JSON-Objekt");
        }
        JsonObject props = schema.getAsJsonObject("properties");
        List<String> head = new ArrayList<>(props.keySet());
        if (!new ArrayList<>(plan.keySet()).equals(head)) {
            return Optional.of("kopf: " + plan.keySet() + " statt " + head);
        }
        Optional<String> p = value("design", plan.get("design"), props.getAsJsonObject("design"), schema);
        if (p.isEmpty()) {
            p = value("reply", plan.get("reply"), props.getAsJsonObject("reply"), schema);
        }
        if (p.isEmpty()) {
            p = value("mode", plan.get("mode"), props.getAsJsonObject("mode"), schema);
        }
        if (p.isPresent()) {
            return p;
        }
        JsonObject stepsSchema = props.getAsJsonObject("steps");
        JsonArray steps = plan.getAsJsonArray("steps");
        if (steps == null || steps.size() < stepsSchema.get("minItems").getAsInt()
                || steps.size() > stepsSchema.get("maxItems").getAsInt()) {
            return Optional.of("steps: " + (steps == null ? 0 : steps.size()) + " Schritte");
        }
        JsonObject stepProps = stepsSchema.getAsJsonObject("items").getAsJsonObject("properties");
        List<String> order = new ArrayList<>(stepProps.keySet());
        for (int i = 0; i < steps.size(); i++) {
            JsonObject st = steps.get(i).getAsJsonObject();
            int last = -1;
            for (Map.Entry<String, JsonElement> e : st.entrySet()) {
                int at = order.indexOf(e.getKey());
                if (at < 0) {
                    return Optional.of("schritt " + (i + 1) + ": unbekanntes feld " + e.getKey());
                }
                if (at < last) {
                    return Optional.of("schritt " + (i + 1) + ": reihenfolge " + st.keySet());
                }
                last = at;
                Optional<String> v = value(e.getKey(), e.getValue(), stepProps.getAsJsonObject(e.getKey()), schema);
                if (v.isPresent()) {
                    return Optional.of("schritt " + (i + 1) + ": " + v.get());
                }
            }
            if (!st.has("op")) {
                return Optional.of("schritt " + (i + 1) + ": op fehlt");
            }
        }
        return Optional.empty();
    }

    private static Optional<String> value(String key, JsonElement v, JsonObject s, JsonObject schema) {
        if (s.has("$ref")) {
            String ref = s.get("$ref").getAsString();
            s = schema.getAsJsonObject("$defs").getAsJsonObject(ref.substring(ref.lastIndexOf('/') + 1));
        }
        if (s.has("enum")) {
            for (JsonElement allowed : s.getAsJsonArray("enum")) {
                if (allowed.equals(v)) {
                    return Optional.empty();
                }
            }
            return Optional.of(key + " = " + v + " nicht erlaubt");
        }
        String type = s.has("type") ? s.get("type").getAsString() : "";
        switch (type) {
            case "string" -> {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                    return Optional.of(key + " ist kein Text");
                }
                if (s.has("maxLength") && v.getAsString().length() > s.get("maxLength").getAsInt()) {
                    return Optional.of(key + " länger als " + s.get("maxLength").getAsInt() + " Zeichen");
                }
            }
            case "integer" -> {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()
                        || v.getAsDouble() != Math.rint(v.getAsDouble())) {
                    return Optional.of(key + " ist keine Ganzzahl");
                }
                long n = v.getAsLong();
                if (s.has("minimum") && n < s.get("minimum").getAsLong()
                        || s.has("maximum") && n > s.get("maximum").getAsLong()) {
                    return Optional.of(key + " = " + n + " außerhalb der Grenzen");
                }
            }
            case "boolean" -> {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
                    return Optional.of(key + " ist kein Wahrheitswert");
                }
            }
            case "array" -> {
                if (!v.isJsonArray() || v.getAsJsonArray().size() < s.get("minItems").getAsInt()
                        || v.getAsJsonArray().size() > s.get("maxItems").getAsInt()) {
                    return Optional.of(key + " hat nicht genau " + s.get("minItems").getAsInt() + " Werte");
                }
                for (JsonElement item : v.getAsJsonArray()) {
                    Optional<String> p = value(key, item, s.getAsJsonObject("items"), schema);
                    if (p.isPresent()) {
                        return p;
                    }
                }
            }
            default -> {
                // Unbekannter Typ im Schema: nichts zu prüfen (das Schema kennt heute nur die vier oben).
            }
        }
        return Optional.empty();
    }

    /** Felder eines Schritts in Schema-Reihenfolge – so, wie die Grammatik sie verlangt. */
    static JsonObject canonical(JsonObject step, JsonObject schema) {
        JsonObject order = schema.getAsJsonObject("properties").getAsJsonObject("steps").getAsJsonObject("items")
                .getAsJsonObject("properties");
        JsonObject out = new JsonObject();
        for (String k : order.keySet()) {
            if (step.has(k)) {
                out.add(k, step.get(k));
            }
        }
        for (Map.Entry<String, JsonElement> e : step.entrySet()) {
            if (!out.has(e.getKey())) {
                out.add(e.getKey(), e.getValue());  // unbekanntes Feld bleibt stehen – problem() meldet es
            }
        }
        return out;
    }
}
