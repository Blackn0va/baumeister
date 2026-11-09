package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Antwort des LLM für den Begleiter: Form nach begleiter-schema.json, fail-closed bei Unsinn. */
class CompanionReplyTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = CompanionReplyTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesSayAndActions() {
        CompanionReply.Reply r = CompanionReply.parse("{\"say\":\"Hier!\",\"actions\":[{\"op\":\"give\","
                + "\"item\":\"torch\",\"count\":16},{\"op\":\"calc\",\"expr\":\"17*23\"}]}");
        assertNotNull(r);
        assertEquals("Hier!", r.say());
        assertEquals(2, r.actions().size());
        assertEquals(CompanionReply.Op.GIVE, r.actions().get(0).op());
        assertEquals("torch", r.actions().get(0).item());
        assertEquals(16, r.actions().get(0).count());
        assertEquals("17*23", r.actions().get(1).expr());
    }

    @Test
    void dropsUnknownOpsClampsNumbersAndCutsAfterThree() {
        CompanionReply.Reply r = CompanionReply.parse("{\"say\":\"x\",\"actions\":[{\"op\":\"explode\"},"
                + "{\"op\":\"give\",\"item\":\"torch\",\"count\":9999},{\"op\":\"time\"},{\"op\":\"weather\"},"
                + "{\"op\":\"come\"}]}");
        assertNotNull(r);
        assertEquals(List.of(CompanionReply.Op.GIVE, CompanionReply.Op.TIME, CompanionReply.Op.WEATHER),
                r.actions().stream().map(CompanionReply.Action::op).toList());
        assertEquals(64, r.actions().get(0).count());
    }

    @Test
    void parsesUseAndCharacter() {
        CompanionReply.Reply r = CompanionReply.parse("{\"say\":\"Mach ich!\",\"actions\":[{\"op\":\"use\","
                + "\"block\":\"door\",\"text\":\"open\"},{\"op\":\"character\",\"name\":\"frech\"}]}");
        assertEquals(2, r.actions().size());
        assertEquals(CompanionReply.Op.USE, r.actions().get(0).op());
        assertEquals("door", r.actions().get(0).block());
        assertEquals("open", r.actions().get(0).text());
        assertEquals(CompanionReply.Op.CHARACTER, r.actions().get(1).op());
        assertEquals("frech", r.actions().get(1).name());
    }

    @Test
    void schemaListsEveryOpAndNothingElse() throws java.io.IOException {
        // Gegenstück host/src/plan/begleiter.gbnf (Host-Test vergleicht die Grammatik mit diesem Schema).
        String schema = new String(CompanionReplyTest.class.getResourceAsStream("/begleiter-schema.json")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        for (CompanionReply.Op op : CompanionReply.Op.values()) {
            assertTrue(schema.contains("\"" + op.name().toLowerCase(java.util.Locale.ROOT) + "\""), op.name());
        }
    }

    @Test
    void rejectsMissingSayAndNonJson() {
        assertNull(CompanionReply.parse("{\"actions\":[]}"));
        assertNull(CompanionReply.parse("{\"say\":\"\",\"actions\":[]}"));
        assertNull(CompanionReply.parse("Klar, hier sind deine Fackeln!"));
        assertNull(CompanionReply.parse("[1,2]"));
        assertNull(CompanionReply.parse(null));
        assertNotNull(CompanionReply.parse("{\"say\":\"ohne Aktionen\"}"), "actions fehlt – Satz zählt trotzdem");
    }

    /** Jedes Beispiel im Prompt muss der Parser verstehen, jede op im Schema muss es im Code geben. */
    @Test
    void promptExamplesAndSchemaMatchTheCode() throws IOException {
        String prompt = resource("begleiter-prompt.txt");
        int examples = 0;
        for (String line : prompt.split("\n")) {
            if (line.startsWith("{\"say\"")) {
                assertNotNull(CompanionReply.parse(line), line);
                examples++;
            }
        }
        assertEquals(20, examples);
        JsonObject schema = JsonParser.parseString(resource("begleiter-schema.json")).getAsJsonObject();
        var ops = schema.getAsJsonObject("properties").getAsJsonObject("actions").getAsJsonObject("items")
                .getAsJsonObject("properties").getAsJsonObject("op").getAsJsonArray("enum");
        assertEquals(CompanionReply.Op.values().length, ops.size());
        ops.forEach(o -> assertNotNull(CompanionReply.op(o.getAsString()), o.getAsString()));
        for (String song : Songs.names()) {
            assertTrue(prompt.contains("\"" + song + "\""), "Lied fehlt im Prompt: " + song);
        }
    }

    @Test
    void commandActionCarriesItsText() {
        CompanionReply.Reply r = CompanionReply.parse(
                "{\"say\":\"Los!\",\"actions\":[{\"op\":\"command\",\"text\":\"fly\"}]}");
        assertEquals(CompanionReply.Op.COMMAND, r.actions().get(0).op());
        assertEquals("fly", r.actions().get(0).text());
    }
}
