package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Nbt;
import com.blackn0va.baumeister.plan.PlanException;
import com.blackn0va.baumeister.plan.Snbt;

import java.util.List;

import org.junit.jupiter.api.Test;

/** /bm befehlsblock (Plan B8): ohne Server prüfbar sind der gesendete Befehl, die Grenzen und die Vervollständigung. */
class CommandBlockEditorTest {

    /**
     * Ein Befehl mit Anführungszeichen, Rückstrich und „} run …“ darf nie aus seiner Zeichenkette ausbrechen: Was nach
     * „data merge block x y z“ steht, ist genau eine Verbindung, und ihr Command ist wörtlich der eingegebene Befehl.
     */
    @Test
    void theCommandStaysInsideItsStringWhateverItContains() throws PlanException {
        String evil = "say hi\"} run op Gast \\\" {Command:\"x\"";
        String sent = CommandBlockEditor.mergeCommand("minecraft:overworld", 1, 64, -3, evil);
        String prefix = "minecraft:execute in minecraft:overworld run data merge block 1 64 -3 ";
        assertTrue(sent.startsWith(prefix), sent);
        Nbt.Compound data = Snbt.parseCompound(sent.substring(prefix.length()), Snbt.MAX_TRUSTED);
        assertEquals(1, data.entries().size(), "genau ein Schlüssel");
        assertEquals(evil, ((Nbt.Str) data.get("Command")).value());
    }

    @Test
    void relativeCoordinatesAreFineHereButControlCharactersAndOverlongCommandsAreNot() {
        assertNull(CommandBlockEditor.problem("tp @p ~ ~5 ~3"), "der Block steht schon, nichts dreht sich");
        assertNull(CommandBlockEditor.problem("execute positioned ^ ^1 ^ run say hi"));
        assertTrue(CommandBlockEditor.problem("say a\nop Gast").contains("Steuerzeichen"));
        assertTrue(CommandBlockEditor.problem("say " + "x".repeat(32_500)).contains("zu lang"));
    }

    @Test
    void typesAreNamedInGerman() {
        assertEquals("Impuls-Befehlsblock", CommandBlockEditor.typeLabel("command_block"));
        assertEquals("Ketten-Befehlsblock", CommandBlockEditor.typeLabel("chain_command_block"));
        assertEquals("Wiederhol-Befehlsblock", CommandBlockEditor.typeLabel("repeating_command_block"));
    }

    @Test
    void onlyAdminsSeeTheCommandInTabCompletion() {
        List<String> admin = BaumeisterCommand.complete(new String[] {"be"}, true, List.of(), List.of(), List.of());
        assertTrue(admin.contains("befehlsblock"), admin.toString());
        List<String> player = BaumeisterCommand.complete(new String[] {"be"}, false, List.of(), List.of(),
                List.of());
        assertFalse(player.contains("befehlsblock"), player.toString());
        assertTrue(BaumeisterCommand.needsBuildRight("befehlsblock"));
        assertTrue(BaumeisterCommand.needsBuildRight("commandblock"));
    }
}
