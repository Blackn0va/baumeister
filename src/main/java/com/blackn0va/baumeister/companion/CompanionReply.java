package com.blackn0va.baumeister.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Antwort des Begleiters aus dem LLM: {"say": "…", "actions": [ … ]} nach begleiter-schema.json. Gegenstück im Host ist
 * host/src/plan/begleiter.gbnf – beide gleich halten (host/tests/plan/test_begleiter.cpp prüft die Abschrift).
 *
 * Die Grammatik im Host verhindert schon falsche Form; geprüft wird hier trotzdem fail-closed, denn das Plugin kann
 * auch an einem anderen OpenAI-kompatiblen Server hängen (llm.url), der keine Grammatik kennt. Unbekannte Aktionen
 * fallen weg, Zahlen werden auf die Schema-Grenzen gekappt, mehr als 3 Aktionen werden abgeschnitten.
 *
 * Threads: zustandslos. Fehler: parse liefert null, wenn say fehlt oder kein JSON-Objekt kommt.
 */
public final class CompanionReply {

    public static final int MAX_ACTIONS = 3;
    public static final int MAX_SAY = 300;

    public enum Op {
        GIVE, FIND, CALC, FOLLOW, STAY, COME, BUILD, SONG, TIME, WEATHER, COMMAND, RECIPE, REPAIR,
        // Seit 2026-11-04 (Wunsch): Orte merken und hinbringen, Spieler und Orte finden,
        // Erinnerung nach Minuten, „was ist das?“ – alles im Plugin gerechnet, nie vom Modell geraten.
        MARK, GOTO, WHERE, REMIND, LOOK,
        // Seit 2026-11-07 (Wunsch): benutzen (Tür, Hebel, Knopf, Glocke,
        // Notenblock, Kiste) und Charakter wechseln – auch, wenn der Satz zu frei für CompanionUse/CompanionCharacter ist.
        USE, CHARACTER
    }

    /** Felder nach Schema; nicht gesetzte Texte sind "", count/radius 0. */
    public record Action(Op op, String item, int count, String block, int radius, String expr, String text,
            String name) {
    }

    public record Reply(String say, List<Action> actions) {
    }

    private CompanionReply() {
    }

    public static Reply parse(String content) {
        JsonObject root;
        try {
            JsonElement e = JsonParser.parseString(content == null ? "" : content.trim());
            if (!e.isJsonObject()) {
                return null;
            }
            root = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        String say = text(root, "say", MAX_SAY);
        if (say.isEmpty()) {
            return null;
        }
        List<Action> actions = new ArrayList<>();
        JsonElement arr = root.get("actions");
        if (arr != null && arr.isJsonArray()) {
            JsonArray a = arr.getAsJsonArray();
            for (int i = 0; i < a.size() && actions.size() < MAX_ACTIONS; i++) {
                if (!a.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject o = a.get(i).getAsJsonObject();
                Op op = op(text(o, "op", 16));
                if (op == null) {
                    continue;
                }
                actions.add(new Action(op, text(o, "item", 48), number(o, "count", 1, 64), text(o, "block", 48),
                        number(o, "radius", 1, 64), text(o, "expr", 80), text(o, "text", 200), text(o, "name", 32)));
            }
        }
        return new Reply(say, List.copyOf(actions));
    }

    static Op op(String s) {
        for (Op op : Op.values()) {
            if (op.name().toLowerCase(Locale.ROOT).equals(s)) {
                return op;
            }
        }
        return null;
    }

    private static String text(JsonObject o, String key, int max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return "";
        }
        String s = e.getAsString().trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 0 = nicht gesetzt; sonst auf min … max gekappt. */
    private static int number(JsonObject o, String key, int min, int max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        double d = e.getAsDouble();
        if (Double.isNaN(d)) {
            return 0;
        }
        return (int) Math.max(min, Math.min(max, Math.round(d)));
    }
}
