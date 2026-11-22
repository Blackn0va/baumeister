package com.blackn0va.baumeister.module;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * modul_json (Vertrag v012): genau die Felder, Grenzen je Feld, Escaping. Host (T-031) lehnt Überlanges mit 400 ab und
 * die Mod (T-032) kappt selbst – beide verlassen sich darauf, dass das Plugin schon sauber schickt.
 */
class ModuleJsonTest {

    private static ModuleEntry entry(String id, String name, String description, String reason, String... needs) {
        return new ModuleEntry(new ModuleDef(id, name, description, "x.an", ModuleDef.Effect.SOFORT, List.of(needs)),
                true, ModuleState.WARTET, reason);
    }

    private static JsonObject only(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray mods = root.getAsJsonArray("modules");
        assertEquals(1, mods.size(), json);
        return mods.get(0).getAsJsonObject();
    }

    @Test
    void realDirectoryEncodesExactlyTheContractFields() {
        String json = ModuleJson.encode(ModuleDirectory.evaluate(ModuleDirectoryTest.facts(Map.of(), true, true,
                true)));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(Set.of("version", "modules"), root.keySet());
        assertEquals(1, root.get("version").getAsInt());
        JsonArray mods = root.getAsJsonArray("modules");
        assertEquals(ModuleDirectory.ALL.size(), mods.size());
        for (int i = 0; i < mods.size(); i++) {
            JsonObject o = mods.get(i).getAsJsonObject();
            assertEquals(Set.of("id", "name", "description", "on", "state", "reason", "needs"), o.keySet(),
                    o.toString());
            assertTrue(Set.of("aktiv", "aus", "wartet", "fehler").contains(o.get("state").getAsString()));
            assertTrue(o.get("on").getAsJsonPrimitive().isBoolean());
        }
        // Weit unter jeder Grenze eines Plugin-Kanals (Spigot: 1 MiB) und eines POST.
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length < 16_384, "Größe " + json.length());
    }

    @Test
    void overlongTextsAreCutToTheContractLimits() {
        JsonObject o = only(ModuleJson.encode(List.of(entry("lang", "N".repeat(100), "D".repeat(500),
                "R".repeat(300)))));
        assertEquals(40, o.get("name").getAsString().length());
        assertTrue(o.get("name").getAsString().endsWith("…"));
        assertEquals(160, o.get("description").getAsString().length());
        assertEquals(120, o.get("reason").getAsString().length());
        // Genau an der Grenze bleibt alles, ohne „…“.
        JsonObject exact = only(ModuleJson.encode(List.of(entry("genau", "N".repeat(40), "d", "r"))));
        assertEquals("N".repeat(40), exact.get("name").getAsString());
    }

    @Test
    void invalidIdsAndNeedsAreDropped() {
        String json = ModuleJson.encode(List.of(entry("Groß", "a", "b", ""), entry("x".repeat(33), "a", "b", ""),
                entry("ok-1", "a", "b", "", "ki", "Böse", "a b", "worldedit")));
        JsonObject o = only(json);
        assertEquals("ok-1", o.get("id").getAsString());
        JsonArray needs = o.getAsJsonArray("needs");
        assertEquals(2, needs.size(), needs.toString());
        assertEquals("ki", needs.get(0).getAsString());
        assertEquals("worldedit", needs.get(1).getAsString());
    }

    @Test
    void quotesBackslashesControlAndBidiCharactersCannotBreakOrSpoofTheJson() {
        String evil = "a\"b\\c\nd\u0000e‮f</script>";
        JsonObject o = only(ModuleJson.encode(List.of(entry("x", evil, evil, evil))));
        // Rundreise: gültiges JSON, Zeichen erhalten, Steuer- und Richtungszeichen weg.
        assertEquals("a\"b\\c d e" + "f</script>", o.get("name").getAsString());
        assertFalse(o.get("reason").getAsString().contains("‮"));
        assertFalse(o.get("description").getAsString().contains("\n"));
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        // 38 Zeichen + Emoji (2 UTF-16-Einheiten) + Rest: Schnitt bei 39 fiele mitten ins Paar.
        String name = "N".repeat(38) + "😀" + "zzz";
        String cut = only(ModuleJson.encode(List.of(entry("emoji", name, "d", "")))).get("name").getAsString();
        assertTrue(cut.length() <= 40, cut);
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < cut.length() && Character.isLowSurrogate(cut.charAt(i + 1)), cut);
            }
            assertFalse(Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(cut.charAt(i - 1))),
                    cut);
        }
    }

    @Test
    void moreThanTheCapOfModulesIsCut() {
        List<ModuleEntry> many = new ArrayList<>();
        for (int i = 0; i < ModuleJson.MAX_MODULES + 10; i++) {
            many.add(entry("m" + i, "a", "b", ""));
        }
        JsonObject root = JsonParser.parseString(ModuleJson.encode(many)).getAsJsonObject();
        assertEquals(ModuleJson.MAX_MODULES, root.getAsJsonArray("modules").size());
    }

    @Test
    void channelPayloadIsRawUtf8WithoutLengthPrefix() {
        String json = ModuleJson.encode(List.of(entry("gruss", "Grüße", "ß", "")));
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), ModuleJson.bytes(json));
        assertEquals('{', ModuleJson.bytes(json)[0]);
    }
}
