package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.logic.LogicCircuit;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.BuildPlan.Step;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Liest die JSON-Antwort des LLM. Die Grammatik in llama-server erzwingt das Schema
 * (plan-schema.json) bereits; dieser Parser prüft trotzdem alles selbst, weil der
 * Endpunkt austauschbar ist und ein Modell ohne Grammatik freien Text liefern kann.
 */
public final class PlanParser {

    public static final int MAX_STEPS = 60;
    /** 4 Blöcke je Geschoss: 45 Geschosse + Dach passen in bau.max-ausdehnung (192). */
    public static final int MAX_FLOORS = 45;
    /** Wohnhaus: mehr als 4 Geschosse ist ein Hochhaus (skyscraper), so sagt es auch der Prompt. */
    public static final int MAX_HOUSE_FLOORS = 4;
    /** Grundriss des Wohnhauses je Seite: 5 lässt innen 3×3 für Werkbank, Truhe und Leiter, 24 wie im Prompt. */
    public static final int MIN_HOUSE_SIDE = 5;
    public static final int MAX_HOUSE_SIDE = 24;
    private static final int COORD_LIMIT = 200;

    private PlanParser() {
    }

    public static BuildPlan parse(String raw) throws PlanException {
        return parse(raw, MAX_STEPS);
    }

    /**
     * maxSteps über MAX_STEPS nur für Pläne, die das Plugin selbst zusammensetzt (Plot-Gestaltung + Hauptbau, siehe
     * PlotLayout.MAX_STEPS) – die Grenze des LLM bleibt die Grammatik und MAX_STEPS.
     */
    public static BuildPlan parse(String raw, int maxSteps) throws PlanException {
        JsonObject root = parseObject(raw);
        String design = optString(root, "design", "");
        String reply = optString(root, "reply", "");
        String mode = optString(root, "mode", "new").toLowerCase(Locale.ROOT);
        if (!mode.equals("new") && !mode.equals("update")) {
            throw new PlanException("mode muss new oder update sein, nicht \"" + mode + "\".");
        }
        JsonElement stepsEl = root.get("steps");
        if (stepsEl == null || !stepsEl.isJsonArray()) {
            throw new PlanException("Feld \"steps\" fehlt oder ist keine Liste.");
        }
        JsonArray arr = stepsEl.getAsJsonArray();
        if (arr.isEmpty()) {
            throw new PlanException("Der Plan enthält keine Schritte.");
        }
        if (arr.size() > maxSteps) {
            throw new PlanException("Zu viele Schritte (" + arr.size() + ", erlaubt " + maxSteps + ").");
        }
        List<Step> steps = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            if (!arr.get(i).isJsonObject()) {
                throw new PlanException("Schritt " + (i + 1) + " ist kein Objekt.");
            }
            steps.add(parseStep(arr.get(i).getAsJsonObject(), i + 1));
        }
        return new BuildPlan(design, reply, mode, List.copyOf(steps));
    }

    /** Lesbarer JSON-Text der Antwort und was JsonRepair daran ändern musste (leer = nichts). */
    public record Normalized(String json, List<String> fixes, boolean truncated) {
    }

    /**
     * Erst streng: vom ersten „{“ bis zum letzten „}“ (schneidet Markdown-Zäune und Vor-/Nachtext ab). Scheitert das,
     * repariert JsonRepair die Syntax – vorher war hier Schluss, und eine bei max_tokens abgeschnittene Antwort ging
     * vollständig in die Reparaturrunde (Kontext übergelaufen, 2026-10-29). Wer die Reparatur entfernt, bringt das zurück.
     */
    public static Normalized normalize(String raw) throws PlanException {
        if (raw == null || raw.isBlank()) {
            throw new PlanException("Leere Antwort.");
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            throw new PlanException("Die Antwort enthält kein JSON-Objekt.");
        }
        int end = raw.lastIndexOf('}');
        String strictError = "Text endet vor dem Ende des JSON";
        if (end > start) {
            String candidate = raw.substring(start, end + 1);
            try {
                if (JsonParser.parseString(candidate).isJsonObject()) {
                    return withoutDuplicates(new Normalized(candidate, List.of(), false));
                }
            } catch (JsonParseException e) {
                strictError = e.getMessage();
            }
        }
        JsonRepair.Result r = JsonRepair.repair(raw);
        try {
            if (r != null && JsonParser.parseString(r.json()).isJsonObject()) {
                return withoutDuplicates(new Normalized(r.json(), r.fixes(), r.truncated()));
            }
        } catch (JsonParseException e) {
            // fällt unten auf die Meldung des strengen Versuchs zurück – die beschreibt den Originaltext
        }
        throw new PlanException("JSON fehlerhaft: " + strictError);
    }

    /**
     * Wortgleiche Schritte nur einmal, und zwar an der Stelle des LETZTEN Vorkommens. Kleine Modelle geraten in
     * Wiederholungsschleifen: dev01 2026-10-30, Qwen2.5 3B schrieb dieselben 27 line-Schritte zweimal hintereinander,
     * Llama 3.1 8B denselben Schritt 40-mal – die Pläne liefen in die 60-Schritte-Grenze, den Überlappungs-Befund und
     * die Reparaturrunde. Warum das letzte Vorkommen: Ein Schritt schreibt jede seiner Zellen neu, unabhängig vom
     * Zustand davor. Steht er später noch einmal, überschreibt er dort alles, was ein früheres Vorkommen gesetzt hat –
     * das frühere ändert am Endzustand also nichts. Behielte man das ERSTE, ginge bei A, B, A verloren, dass A über B
     * liegt. Gleich heißt: dasselbe JSON-Objekt (Reihenfolge der Felder egal).
     */
    static Normalized withoutDuplicates(Normalized n) {
        JsonObject root = JsonParser.parseString(n.json()).getAsJsonObject();
        JsonElement stepsEl = root.get("steps");
        if (stepsEl == null || !stepsEl.isJsonArray()) {
            return n;
        }
        JsonArray steps = stepsEl.getAsJsonArray();
        List<JsonElement> kept = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            JsonElement s = steps.get(i);
            boolean later = false;
            for (int j = i + 1; j < steps.size() && !later; j++) {
                later = steps.get(j).equals(s);
            }
            if (!later) {
                kept.add(s);
            }
        }
        int removed = steps.size() - kept.size();
        if (removed == 0) {
            return n;
        }
        JsonArray out = new JsonArray(kept.size());
        kept.forEach(out::add);
        root.add("steps", out);
        List<String> fixes = new ArrayList<>(n.fixes());
        fixes.add(removed + " doppelte Schritte entfernt");
        return new Normalized(root.toString(), List.copyOf(fixes), n.truncated());
    }

    private static JsonObject parseObject(String raw) throws PlanException {
        return JsonParser.parseString(normalize(raw).json()).getAsJsonObject();
    }

    private static Step parseStep(JsonObject o, int n) throws PlanException {
        String opName = optString(o, "op", "");
        Op op;
        try {
            op = Op.valueOf(opName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PlanException("Schritt " + n + ": unbekannte op \"" + opName + "\".");
        }
        String block = optString(o, "block", "");
        String name = optString(o, "name", "").trim().toLowerCase(Locale.ROOT);
        String expr = optString(o, "expr", "").trim();
        boolean missing = switch (op) {
            case MODULE -> name.isEmpty();
            case LOGIC -> expr.isEmpty();
            default -> block.isBlank();
        };
        if (missing) {
            throw new PlanException("Schritt " + n + ": " + (op == Op.MODULE ? "name" : op == Op.LOGIC ? "expr" : "block")
                    + " fehlt.");
        }
        if (op == Op.LOGIC) {
            try {
                LogicCircuit.compile(expr);
            } catch (IllegalArgumentException e) {
                // Geht als Reparaturhinweis ans LLM – deshalb der genaue Grund, nicht nur „ungültig“.
                throw new PlanException("Schritt " + n + " (logic): " + e.getMessage());
            }
        }
        Pos from = optVec(o, "from", n);
        Pos to = optVec(o, "to", n);
        Pos pos = optVec(o, "pos", n);
        int radius = optInt(o, "radius", 0, n);
        // Bauformen haben eigene Vorgaben (Burgmauer 6, Mauer 5; Brücke und Baum 0 = rechnet die Form selbst).
        int height = optInt(o, "height", Structures.defaultHeight(op), n);
        boolean hollow = optBool(o, "hollow");
        boolean dome = optBool(o, "dome");
        String shape = optString(o, "shape", "gable").toLowerCase(Locale.ROOT);
        int overhang = optInt(o, "overhang", 1, n);
        String gable = optString(o, "gable_block", "");
        String facing = optString(o, "facing", "").toLowerCase(Locale.ROOT);
        // Ein Haus ohne Angabe ist einstöckig; beim Hochhaus bleibt floors Pflicht (0 → Fehler unten).
        int floors = optInt(o, "floors", op == Op.HOUSE ? 1 : 0, n);
        String windowBlock = optString(o, "window_block", "");
        String roofBlock = optString(o, "roof_block", "");
        int width = optInt(o, "width", Structures.defaultWidth(op), n);
        String style = optString(o, "style", "").trim().toLowerCase(Locale.ROOT);

        // Modelle verwechseln pos und from bei Einzelblöcken – beides annehmen statt abzulehnen.
        if (pos == null && from != null && (op == Op.SET || op == Op.DOOR || op == Op.CYLINDER
                || op == Op.SPHERE || op == Op.MODULE || op == Op.LOGIC || op == Op.TREE)) {
            pos = from;
        }
        if (to == null && from != null && op != Op.LINE) {
            to = from;
        }
        if (from == null && pos != null && (op == Op.FILL || op == Op.WALLS || op == Op.HOLLOW)) {
            from = pos;
            to = to == null ? pos : to;
        }

        switch (op) {
            case FILL, WALLS, HOLLOW, LINE, ROOF, SKYSCRAPER, HOUSE, CASTLE, WALL, BRIDGE, GARDEN -> {
                if (from == null || to == null) {
                    throw new PlanException("Schritt " + n + " (" + opName + "): from und to fehlen.");
                }
            }
            case SET, DOOR, MODULE, LOGIC, TREE -> {
                if (pos == null) {
                    throw new PlanException("Schritt " + n + " (" + opName + "): pos fehlt.");
                }
            }
            case CYLINDER, SPHERE -> {
                if (pos == null) {
                    throw new PlanException("Schritt " + n + " (" + opName + "): pos fehlt.");
                }
                if (radius < 1 || radius > 24) {
                    throw new PlanException("Schritt " + n + ": radius muss 1 bis 24 sein.");
                }
            }
        }
        if (height < (Structures.allowsAutoHeight(op) ? 0 : 1) || height > 48) {
            throw new PlanException("Schritt " + n + ": height muss 1 bis 48 sein.");
        }
        if (!shape.equals("gable") && !shape.equals("hip") && !shape.equals("flat")) {
            throw new PlanException("Schritt " + n + ": shape muss gable, hip oder flat sein.");
        }
        if (!facing.isEmpty() && !Orientation.isPlanDirection(facing)) {
            throw new PlanException("Schritt " + n + ": facing muss front, back, left, right, up oder down sein.");
        }
        if (op == Op.SKYSCRAPER && (floors < 1 || floors > MAX_FLOORS)) {
            throw new PlanException("Schritt " + n + ": floors muss 1 bis " + MAX_FLOORS + " sein.");
        }
        if (op == Op.HOUSE) {
            if (floors < 1 || floors > MAX_HOUSE_FLOORS) {
                throw new PlanException("Schritt " + n + ": floors muss bei house 1 bis " + MAX_HOUSE_FLOORS
                        + " sein; mehr Geschosse baut skyscraper.");
            }
            int w = Math.abs(to.x() - from.x()) + 1;
            int d = Math.abs(to.z() - from.z()) + 1;
            if (w < MIN_HOUSE_SIDE || d < MIN_HOUSE_SIDE || w > MAX_HOUSE_SIDE || d > MAX_HOUSE_SIDE) {
                throw new PlanException("Schritt " + n + ": house braucht einen Grundriss von " + MIN_HOUSE_SIDE + "×"
                        + MIN_HOUSE_SIDE + " bis " + MAX_HOUSE_SIDE + "×" + MAX_HOUSE_SIDE + ", nicht " + w + "×" + d
                        + ".");
            }
        }
        overhang = Math.clamp(overhang, 0, 2);
        Step step = new Step(op, block, from, to, pos, radius, height, hollow, dome, shape, overhang, gable, facing,
                name, floors, windowBlock, roofBlock, expr, width, style);
        if (Structures.handles(op)) {
            // Maße je Bauform (Burg ab 17×17, Brücke ab 4 lang …) – als Reparaturhinweis, nicht erst im Compiler.
            Structures.validate(step, n);
        }
        return step;
    }

    private static String optString(JsonObject o, String key, String def) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return def;
        }
        return el.isJsonPrimitive() ? el.getAsString() : def;
    }

    private static boolean optBool(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
    }

    private static int optInt(JsonObject o, String key, int def, int n) throws PlanException {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return def;
        }
        try {
            return el.getAsInt();
        } catch (RuntimeException e) {
            throw new PlanException("Schritt " + n + ": " + key + " ist keine Zahl.");
        }
    }

    private static Pos optVec(JsonObject o, String key, int n) throws PlanException {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (!el.isJsonArray() || el.getAsJsonArray().size() != 3) {
            throw new PlanException("Schritt " + n + ": " + key + " muss [x,y,z] sein.");
        }
        JsonArray a = el.getAsJsonArray();
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                v[i] = a.get(i).getAsInt();
            } catch (RuntimeException e) {
                throw new PlanException("Schritt " + n + ": " + key + " enthält keine Ganzzahl.");
            }
            if (Math.abs(v[i]) > COORD_LIMIT) {
                throw new PlanException("Schritt " + n + ": Koordinate " + v[i] + " liegt zu weit draußen.");
            }
        }
        return new Pos(v[0], v[1], v[2]);
    }
}
