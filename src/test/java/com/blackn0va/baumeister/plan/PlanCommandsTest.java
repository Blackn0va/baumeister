package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Befehle aus Plantext (Plan B5): Prüfsumme für die Bestätigung, „immer aktiv“ verboten, Anzeige ohne Versteckspiel.
 */
class PlanCommandsTest {

    /** Ein Backslash, ohne dass im Quelltext je die Folge Backslash-u steht (Werkzeuge machen daraus das Zeichen). */
    private static final String BS = "\\";

    private static CompiledBuild build(String block) throws Exception {
        PlanCompiler c = new PlanCompiler(id -> true, "stone", new PlanCompiler.Limits(6000, 48));
        return c.compile(PlanParser.parse("{\"steps\":[{\"op\":\"set\",\"block\":\"" + block.replace("\"", "\\\"")
                + "\",\"pos\":[0,0,0]},{\"op\":\"set\",\"block\":\"stone\",\"pos\":[1,0,0]}]}"));
    }

    @Test
    void fingerprintCoversExactlyTheCommandsOfTheBuild() throws Exception {
        String a = PlanCommands.fingerprint(build("command_block{Command:\"say a\"}"));
        assertEquals(64, a.length());
        assertEquals(a, PlanCommands.fingerprint(build("command_block{Command:\"say a\"}")), "gleicher Plan");
        assertNotEquals(a, PlanCommands.fingerprint(build("command_block{Command:\"say b\"}")), "anderer Befehl");
        assertNotEquals(a, PlanCommands.fingerprint(build("command_block[facing=up]{Command:\"say a\"}")),
                "anderer Zustand");
        assertEquals("", PlanCommands.fingerprint(build("oak_planks")), "ohne Befehle leer");
        assertEquals("", PlanCommands.fingerprint(build("oak_sign{front_text:{messages:[\"Hallo\",\"\",\"\",\"\"]}}")),
                "Schild mit reinem Text braucht keine Bestätigung");
    }

    private static CompiledBuild buildOf(String... steps) throws Exception {
        PlanCompiler c = new PlanCompiler(id -> true, "stone", new PlanCompiler.Limits(6000, 48));
        return c.compile(PlanParser.parse("{\"steps\":[" + String.join(",", steps) + "]}"));
    }

    private static String set(String block, int x, int y, int z) {
        return "{\"op\":\"set\",\"block\":\"" + block.replace("\"", "\\\"") + "\",\"pos\":[" + x + "," + y + "," + z
                + "]}";
    }

    /**
     * Sicherheitsprüfung 2026-11-08, H1: Bestätigbar ist nur, was die Liste vollständig zeigt – ein Impuls- oder
     * Ketten-Befehlsblock mit Befehl (und auto). Spawner-Daten, Klick-Texte, weitere Schlüssel, Wiederhol-Blöcke nie.
     */
    @Test
    void onlyPlainCommandBlocksCanBeConfirmed() throws Exception {
        String[] never = {
            "spawner{SpawnData:{entity:{id:\"minecraft:falling_block\",BlockState:{Name:\"minecraft:command_block\"},"
                    + "TileEntityData:{Command:\"op Gast\",auto:1b}}}}",
            "oak_sign{front_text:{messages:[{text:\"x\",click_event:{action:\"run_command\",command:\"/op Gast\"}},"
                    + "\"\",\"\",\"\"]}}",
            "command_block{Command:\"say a\",CustomName:\"x\"}",
            "repeating_command_block{Command:\"say a\"}",
        };
        for (String b : never) {
            List<PlanCommands.Found> f = PlanCommands.find(build(b));
            assertEquals(1, f.size(), b);
            assertFalse(f.getFirst().confirmable(), b);
            assertEquals("", PlanCommands.fingerprint(build(b)), "nichts Bestätigbares: " + b);
        }
        assertTrue(PlanCommands.find(build("command_block{Command:\"say a\",auto:1b}")).getFirst().confirmable());
        assertTrue(PlanCommands.find(build("chain_command_block{Command:\"say a\",auto:1b}")).getFirst().confirmable());
    }

    /** M3: Eine Stromquelle neben dem Befehlsblock im Plan ließe ihn von selbst laufen; ein Knopf ist ein Auslöser. */
    @Test
    void powerSourcesNextToACommandBlockForbidConfirmation() throws Exception {
        String cmd = set("command_block{Command:\"say a\"}", 0, 1, 0);
        for (String src : new String[] {"redstone_block", "redstone_torch", "observer[facing=up]",
            "lever[face=floor,facing=front,powered=true]", "repeater[facing=front]", "daylight_detector"}) {
            List<PlanCommands.Found> f = PlanCommands.find(buildOf(cmd, set(src, 1, 1, 0)));
            assertFalse(f.getFirst().confirmable(), src);
            assertTrue(f.getFirst().refusal().contains("Stromquelle"), f.getFirst().refusal());
        }
        for (String trigger : new String[] {"stone_button[face=floor,facing=front]",
            "lever[face=floor,facing=front,powered=false]", "stone_pressure_plate", "stone"}) {
            assertTrue(PlanCommands.find(buildOf(cmd, set(trigger, 0, 2, 0))).getFirst().confirmable(), trigger);
        }
        assertTrue(PlanCommands.find(buildOf(cmd, set("redstone_block", 2, 1, 0))).getFirst().confirmable(),
                "zwei Blöcke weiter berührt nicht");
    }

    @Test
    void refusedCellsDoNotChangeTheFingerprint() throws Exception {
        String cmd = set("command_block{Command:\"say a\"}", 0, 1, 0);
        String spawner = set("spawner{SpawnData:{entity:{id:\"minecraft:pig\"}}}", 5, 1, 0);
        assertEquals(PlanCommands.fingerprint(buildOf(cmd)), PlanCommands.fingerprint(buildOf(cmd, spawner)),
                "bestätigt wird nur, was gebaut wird");
    }

    @Test
    void foundListsPositionCommandAndAutoStart() throws Exception {
        List<PlanCommands.Found> found = PlanCommands.find(build("command_block{Command:\"say a\",auto:1b}"));
        assertEquals(1, found.size());
        assertEquals("say a", found.getFirst().command());
        assertTrue(found.getFirst().autoStart());
        assertFalse(PlanCommands.find(build("command_block{Command:\"say a\",auto:0b}")).getFirst().autoStart());
    }

    @Test
    void autoStartFromPlanTextBecomesRedstoneOnly() throws Exception {
        BlockSpec planned = BlockSpec.parse("repeating_command_block{Command:\"say a\",auto:1b}");
        assertTrue(PlanCommands.autoStart(planned));
        BlockSpec safe = PlanCommands.withoutAutoStart(planned);
        assertEquals(Nbt.Num.ofByte(0), safe.nbt().get("auto"));
        assertEquals("say a", ((Nbt.Str) safe.nbt().get("Command")).value(), "Befehl bleibt");
        assertTrue(PlanCommands.needsConfirmation(safe), "bleibt bestätigungspflichtig");
        assertFalse(PlanCommands.autoStart(safe));
        BlockSpec trusted = BlockSpec.parseTrusted("repeating_command_block{Command:\"say a\",auto:1b}");
        assertSame(trusted, PlanCommands.withoutAutoStart(trusted), "Bauteile und Code dürfen auto:1b");
        BlockSpec plain = BlockSpec.parse("command_block{Command:\"say a\"}");
        assertSame(plain, PlanCommands.withoutAutoStart(plain), "ohne auto unverändert");
        BlockSpec impulse = BlockSpec.parse("command_block{Command:\"say a\",auto:1b}");
        assertEquals(Nbt.Num.ofByte(0), PlanCommands.withoutAutoStart(impulse).nbt().get("auto"),
                "Impuls liefe beim Setzen einmal los");
        BlockSpec chain = BlockSpec.parse("chain_command_block{Command:\"say b\",auto:1b}");
        assertSame(chain, PlanCommands.withoutAutoStart(chain), "Kette läuft nur nach dem Block davor");
        assertTrue(PlanCommands.needsConfirmation(chain), "bestätigt werden muss sie trotzdem");
    }

    @Test
    void visibleShowsWhatRunsWithoutHidingCharacters() {
        assertEquals("say Grüße! ~ ~1 ~ @p[x=1,tag=\"a b\"] {1,2,3}",
                PlanCommands.visible("say Grüße! ~ ~1 ~ @p[x=1,tag=\"a b\"] {1,2,3}"), "normale Befehle unverändert");
        String section = "say " + (char) 0xA7 + "chi";
        assertEquals("say " + BS + "u00A7chi", PlanCommands.visible(section), "Farbcode sichtbar statt wirksam");
        String rtl = "say ok" + (char) 0x202E + "po";
        assertEquals("say ok" + BS + "u202Epo", PlanCommands.visible(rtl), "Richtungsumkehr sichtbar");
        String zeroWidth = "op" + (char) 0x200B + " Gast";
        assertEquals("op" + BS + "u200B Gast", PlanCommands.visible(zeroWidth), "Nullbreite sichtbar");
        String nbsp = "say a" + (char) 0xA0 + "b";
        assertEquals("say a" + BS + "u00A0b", PlanCommands.visible(nbsp), "nur das echte Leerzeichen bleibt");
        String tag = "say " + new String(Character.toChars(0xE0041));
        assertEquals("say " + BS + "u{E0041}", PlanCommands.visible(tag), "unsichtbare Tag-Zeichen");
        String longCommand = "say " + "x".repeat(900);
        assertEquals(longCommand, PlanCommands.visible(longCommand), "ungekürzt");
    }
}
