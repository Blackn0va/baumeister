package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Hält die Host-Grammatik (host/src/plan/bauplan.gbnf) auf dem Stand von plan-schema.json.
 *
 * Vorher prüfte das nur host/tests/plan/test_plan.cpp – und das läuft nur, wenn jemand den Host baut. Das Plugin
 * bekam am 2026-10-28 op skyscraper, floors, window_block und Koordinaten bis 200; die GBNF blieb bei -48..96 ohne
 * skyscraper. Über den Host hätte das Modell also nie ein Hochhaus planen dürfen, und das Prompt-Beispiel selbst
 * (y 121..129) war ungültig. Dieser Test wird schon bei mvn test rot. Wer ihn entfernt, merkt die nächste
 * Schemaänderung erst am Host, der jede Antwort verwirft.
 */
class GrammarDriftTest {

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("pom.xml"))) {
            p = p.getParent();
        }
        assertTrue(p != null, "pom.xml nicht gefunden – Test läuft außerhalb des Repos");
        return p;
    }

    /** Fehlt host/ (Plugin allein ausgecheckt), gibt es nichts abzugleichen. */
    private static Path hostDir() {
        Path host = repoRoot().resolve("host");
        assumeTrue(Files.isDirectory(host), "host/ fehlt – Grammatikabgleich übersprungen");
        return host;
    }

    private static String grammar() throws IOException {
        return Files.readString(hostDir().resolve(Path.of("src", "plan", "bauplan.gbnf")), StandardCharsets.UTF_8);
    }

    private static JsonObject schema() throws IOException {
        Path p = repoRoot().resolve(Path.of("src", "main", "resources", "plan-schema.json"));
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject stepProperties(JsonObject schema) {
        return schema.getAsJsonObject("properties").getAsJsonObject("steps").getAsJsonObject("items")
                .getAsJsonObject("properties");
    }

    /** Einzeilige Regeln „name ::= rumpf“; die mehrzeilige step-Regel liefert stepRule(). */
    private static Map<String, String> rules(String g) {
        Map<String, String> out = new HashMap<>();
        for (String line : g.split("\n")) {
            Matcher m = Pattern.compile("^([a-z0-9_]+) ::= (.*)$").matcher(line);
            if (m.matches()) {
                out.put(m.group(1), m.group(2).trim());
            }
        }
        return out;
    }

    private static String stepRule(String g) {
        int start = g.indexOf("\nstep ::=");
        int end = g.indexOf("ws \"}\" )", start);
        assertTrue(start >= 0 && end > start, "step-Regel nicht gefunden");
        return g.substring(start, end);
    }

    /** Wie das Feld in der GBNF geschrieben steht: "\"name\"". */
    private static String quoted(String name) {
        return "\"\\\"" + name + "\\\"\"";
    }

    /** Regel, auf die ein Feld der step-Regel verweist ( ws "," ws "\"floors\"" ws ":" ws floors ). */
    private static String fieldRule(String step, String name) {
        String field = Pattern.quote(quoted(name)) + "\\s+ws\\s+\":\"\\s+ws\\s+([a-z0-9_]+)";
        Matcher m = Pattern.compile(field).matcher(step);
        assertTrue(m.find(), "Feld fehlt in bauplan.gbnf: " + name);
        return m.group(1);
    }

    /**
     * Übersetzt eine Zahlenregel (nur Literale, Zeichenklassen, Gruppen, |) in einen Java-Regex. So werden die
     * Grenzen am Verhalten der Regel geprüft, nicht am Kommentar daneben – der kann stimmen, während die Regel
     * eine Zahl zu viel oder zu wenig zulässt.
     */
    private static Pattern toRegex(String body) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            char ch = body.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (ch == '"') {
                StringBuilder lit = new StringBuilder();
                i++;
                while (body.charAt(i) != '"') {
                    if (body.charAt(i) == '\\') {
                        i++;
                    }
                    lit.append(body.charAt(i));
                    i++;
                }
                i++;
                re.append(Pattern.quote(lit.toString()));
            } else if (ch == '[') {
                int close = body.indexOf(']', i);
                re.append(body, i, close + 1);
                i = close + 1;
            } else if (ch == '(') {
                re.append("(?:");
                i++;
            } else if (ch == ')' || ch == '|' || ch == '?' || ch == '*' || ch == '+') {
                re.append(ch);
                i++;
            } else if (ch == '{') {
                int close = body.indexOf('}', i);
                re.append(body, i, close + 1);
                i = close + 1;
            } else {
                fail("Zahlenregel mit Verweis auf eine andere Regel kann der Test nicht prüfen: " + body);
            }
        }
        return Pattern.compile(re.toString());
    }

    private static void assertIntegerRange(String ruleName, String body, int min, int max) {
        Pattern p = toRegex(body);
        for (int n = Math.min(min, 0) - 300; n <= max + 300; n++) {
            boolean expected = n >= min && n <= max;
            assertEquals(expected, p.matcher(Integer.toString(n)).matches(),
                    "Regel " + ruleName + " (" + body + ") bei " + n + ", Schema verlangt " + min + " … " + max);
        }
        // Führende Nullen sind kein JSON
        assertFalse(p.matcher("0" + Math.max(1, min)).matches(), "Regel " + ruleName + " erlaubt führende Null");
    }

    private static Set<String> enumValues(String body) {
        Set<String> out = new LinkedHashSet<>();
        for (String alt : body.split("\\|")) {
            String t = alt.trim();
            assertTrue(t.startsWith("\"\\\"") && t.endsWith("\\\"\""), "Aufzählungswert ungewöhnlich: " + t);
            out.add(t.substring(3, t.length() - 3));
        }
        return out;
    }

    private static Set<String> enumValues(JsonObject prop) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement v : prop.getAsJsonArray("enum")) {
            out.add(v.getAsString());
        }
        return out;
    }

    /**
     * Wurzelfelder (design, reply, mode, steps) in derselben Reihenfolge und mit denselben mode-Werten. mode kam
     * 2026-10-28 dazu (Folgeaufträge); fehlte es in der Grammatik, schriebe das Modell es nie und jeder
     * Folgeauftrag würde ein Neubau.
     */
    @Test
    void rootFieldsAndModeValuesMatchTheGrammar() throws Exception {
        String g = grammar();
        String root = rules(g).get("root");
        int last = -1;
        for (String f : schema().getAsJsonObject("properties").keySet()) {
            int at = root.indexOf(quoted(f));
            assertTrue(at > last, "Wurzelfeld " + f + " fehlt in der GBNF-Regel root oder steht falsch: " + root);
            last = at;
        }
        assertEquals(enumValues(schema().getAsJsonObject("properties").getAsJsonObject("mode")),
                enumValues(rules(g).get("mode")));
    }

    @Test
    void everyOpOfTheSchemaIsInTheGrammarAndNoOther() throws Exception {
        String g = grammar();
        Set<String> schemaOps = enumValues(stepProperties(schema()).getAsJsonObject("op"));
        assertTrue(schemaOps.contains("house") && schemaOps.contains("skyscraper"));
        assertEquals(schemaOps, enumValues(rules(g).get(fieldRule(stepRule(g), "op"))));
    }

    @Test
    void everyFieldOfTheSchemaIsInTheGrammarInSchemaOrderWithItsLimits() throws Exception {
        String g = grammar();
        JsonObject schema = schema();
        Map<String, String> rules = rules(g);
        String step = stepRule(g);
        int last = -1;
        for (Map.Entry<String, JsonElement> e : stepProperties(schema).entrySet()) {
            String name = e.getKey();
            JsonObject prop = e.getValue().getAsJsonObject();
            int at = step.indexOf(quoted(name));
            assertTrue(at >= 0, "Feld fehlt in bauplan.gbnf: " + name);
            assertTrue(at > last, "Feld außer der Reihe (llama-server ordnet wie das Schema): " + name);
            last = at;
            String ruleName = fieldRule(step, name);
            String body = rules.get(ruleName);
            assertTrue(body != null, "Regel " + ruleName + " fehlt");
            if (prop.has("$ref")) {
                assertEquals("vec", ruleName, name);
            } else if (prop.has("enum")) {
                assertEquals(enumValues(prop), enumValues(body), "Werte von " + name);
            } else {
                switch (prop.get("type").getAsString()) {
                    case "integer" -> assertIntegerRange(ruleName, body, prop.get("minimum").getAsInt(),
                            prop.get("maximum").getAsInt());
                    case "string" -> assertEquals("\"\\\"\" char{0," + prop.get("maxLength").getAsInt() + "} \"\\\"\"",
                            body, "Länge von " + name);
                    case "boolean" -> assertEquals("\"true\" | \"false\"", body, name);
                    default -> fail("Typ ohne Prüfung: " + name);
                }
            }
        }
        // Schritte: Kommas zwischen maxItems Objekten
        int maxSteps = schema.getAsJsonObject("properties").getAsJsonObject("steps").get("maxItems").getAsInt();
        assertTrue(g.contains("){0," + (maxSteps - 1) + "}"), "maxItems der Schritte");
        assertEquals(PlanParser.MAX_STEPS, maxSteps);
    }

    @Test
    void vecAndFloorsLimitsStandAsNumbersInTheGrammar() throws Exception {
        String g = grammar();
        JsonObject schema = schema();
        Map<String, String> rules = rules(g);
        JsonObject vec = schema.getAsJsonObject("$defs").getAsJsonObject("vec");
        JsonObject item = vec.getAsJsonObject("items");
        assertIntegerRange("coord", rules.get("coord"), item.get("minimum").getAsInt(), item.get("maximum").getAsInt());
        assertEquals(3, vec.get("minItems").getAsInt());
        assertEquals(3, vec.get("maxItems").getAsInt());
        assertEquals(3, rules.get("vec").split("coord", -1).length - 1, "vec hat genau drei Koordinaten");
        JsonObject floors = stepProperties(schema).getAsJsonObject("floors");
        assertEquals(PlanParser.MAX_FLOORS, floors.get("maximum").getAsInt());
        assertIntegerRange("floors", rules.get("floors"), 1, PlanParser.MAX_FLOORS);
    }

    @Test
    void hostCopiesAreByteEqualToThePluginResources() throws Exception {
        Path host = hostDir();
        for (String name : List.of("plan-schema.json", "system-prompt.txt")) {
            byte[] live = Files.readAllBytes(repoRoot().resolve(Path.of("src", "main", "resources", name)));
            byte[] copy = Files.readAllBytes(host.resolve(Path.of("tests", "plan", "data", name)));
            assertArrayEquals(live, copy, "Abschrift veraltet: src/main/resources/" + name
                    + " nach host/tests/plan/data/ kopieren (der Host-Bau auf dem GPU-PC prüft nur gegen sie)");
        }
    }

    /** Die Grammatik erzwingt die Feldreihenfolge – ein Beispiel außer der Reihe lehrt das Modell Ungültiges. */
    @Test
    void promptExamplesUseOnlySchemaFieldsInSchemaOrder() throws Exception {
        hostDir();
        JsonObject props = stepProperties(schema());
        List<String> order = new ArrayList<>(props.keySet());
        Set<String> ops = enumValues(props.getAsJsonObject("op"));
        List<String> examples = PlanParserTest.promptExamples();
        assertEquals(4, examples.size(), "Beispiele im Prompt");
        for (String json : examples) {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            // Wurzelfelder in Schemareihenfolge (design, reply, mode, steps) – aus dem Schema, nicht fest verdrahtet.
            assertEquals(new ArrayList<>(schema().getAsJsonObject("properties").keySet()), new ArrayList<>(root.keySet()));
            for (JsonElement el : root.getAsJsonArray("steps")) {
                JsonObject s = el.getAsJsonObject();
                assertTrue(ops.contains(s.get("op").getAsString()), "op nicht im Schema: " + s);
                int last = -1;
                for (String key : s.keySet()) {
                    int at = order.indexOf(key);
                    assertTrue(at >= 0, "Feld nicht im Schema: " + key + " in " + s);
                    assertTrue(at > last, "Feld außer der Reihe: " + key + " in " + s);
                    last = at;
                }
            }
        }
    }

    /**
     * Die Einzelschritte im Abschnitt BLOCK DATA (Schild, Behälter, Befehlsblock; 2026-11-08) stehen in keinem ganzen
     * Plan – promptExamples findet nur „{"design"“ und sieht sie nie. Sie lehren das Modell aber genauso Feldfolge,
     * Escaping (\" im SNBT) und auto:0b. Ein Beispiel außer der Reihe oder über der Länge des Schemas schriebe das
     * Modell nach, und die Grammatik des Hosts verwürfe dann jeden Plan mit Daten.
     */
    @Test
    void blockDataStepsInThePromptAreValidInSchemaOrder() throws Exception {
        String prompt;
        try (java.io.InputStream in = GrammarDriftTest.class.getResourceAsStream("/system-prompt.txt")) {
            prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject props = stepProperties(schema());
        List<String> order = new ArrayList<>(props.keySet());
        int maxBlock = props.getAsJsonObject("block").get("maxLength").getAsInt();
        List<JsonObject> steps = new ArrayList<>();
        for (String line : prompt.split("\n")) {
            int at = line.indexOf("{\"op\":");
            if (line.startsWith("- ") && at >= 0) {
                steps.add(JsonParser.parseString(line.substring(at)).getAsJsonObject());
            }
        }
        assertEquals(3, steps.size(), "Schild, Behälter, Befehlsblock");
        for (JsonObject s : steps) {
            int last = -1;
            for (String key : s.keySet()) {
                int at = order.indexOf(key);
                assertTrue(at > last, "Feld nicht im Schema oder außer der Reihe: " + key + " in " + s);
                last = at;
            }
            String block = s.get("block").getAsString();
            assertTrue(block.length() <= maxBlock, block);
            assertTrue(block.matches("[a-z_]+(\\[[a-z_=,]+])?\\{.+}"), "id[zustände]{daten}: " + block);
            assertFalse(block.contains("auto:1b"), block);
        }
        assertTrue(steps.get(2).get("block").getAsString().contains("auto:0b"));
        // umlaut-ok: „gefaehrlich“ ist der ASCII-Schlüssel der Kontextzeile (Plan B5), kein Text für Menschen
        assertTrue(prompt.contains("Rechte: inhalte=ja|nein, gefaehrlich=ja|nein, befehle=ja|nein"), // umlaut-ok
                "Form der Kontextzeile (Plan B5) – wer sie hier ändert, ändert die Zeile in der Nutzernachricht mit");
    }
}
