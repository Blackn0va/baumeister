package com.blackn0va.baumeister.dataset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Plan-JSON für das Lehrset in der Schreibweise der Beispiele im System-Prompt: Kopf (design, reply, mode) in einer
 * Zeile, danach jeder Schritt in einer eigenen Zeile, Felder in der Reihenfolge op, block, from, to, pos, Rest.
 *
 * Warum genau diese Form: Das Modell sieht dieselben Beispiele in jedem System-Prompt. Weicht das Lehrset davon ab
 * (eingerückt, andere Feldfolge), lernt es zwei Schreibweisen und verbraucht für die Leerzeichen der eingerückten
 * Token, die auf der CPU jeweils ~0,1 s kosten. Gson ohne HTML-Escaping, sonst stünde „'“ statt „'“ im Ziel.
 *
 * Threads: zustandslos. Fehler: keine.
 */
final class PlanText {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Felder jedes Schritts in Schema-Reihenfolge (= Grammatik des Hosts). Vorher übernahm render die Folge des
     * Erzeugers; QuickOrders.skyscraperJson schreibt window_block vor from, was die Grammatik nie erzeugt – 214 von 4000
     * Lehrplänen (2026-11-01, SchemaCheck).
     */
    private static final JsonObject SCHEMA = com.google.gson.JsonParser.parseString(resource()).getAsJsonObject();

    private static String resource() {
        try (java.io.InputStream in = PlanText.class.getResourceAsStream("/plan-schema.json")) {
            if (in == null) {
                throw new IllegalStateException("plan-schema.json fehlt");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private PlanText() {
    }

    static String render(String design, String reply, List<JsonObject> steps) {
        JsonObject head = new JsonObject();
        head.addProperty("design", design);
        head.addProperty("reply", reply);
        head.addProperty("mode", "new");
        String h = GSON.toJson(head);
        StringBuilder sb = new StringBuilder(h.substring(0, h.length() - 1)).append(",\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            sb.append('\n').append(GSON.toJson(SchemaCheck.canonical(steps.get(i), SCHEMA)));
            if (i + 1 < steps.size()) {
                sb.append(',');
            }
        }
        return sb.append("]}").toString();
    }

    /** Ein Schritt; Felder in Aufrufreihenfolge (JsonObject behält sie). */
    static final class S {
        private final JsonObject o = new JsonObject();

        private S(String op) {
            o.addProperty("op", op);
        }

        static S op(String op) {
            return new S(op);
        }

        S block(String id) {
            o.addProperty("block", id);
            return this;
        }

        S from(int x, int y, int z) {
            o.add("from", vec(x, y, z));
            return this;
        }

        S to(int x, int y, int z) {
            o.add("to", vec(x, y, z));
            return this;
        }

        S pos(int x, int y, int z) {
            o.add("pos", vec(x, y, z));
            return this;
        }

        S num(String key, int value) {
            o.addProperty(key, value);
            return this;
        }

        S str(String key, String value) {
            o.addProperty(key, value);
            return this;
        }

        S flag(String key) {
            o.addProperty(key, true);
            return this;
        }

        JsonObject json() {
            return o;
        }
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }
}
