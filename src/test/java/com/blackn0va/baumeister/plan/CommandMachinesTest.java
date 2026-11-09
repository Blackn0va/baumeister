package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Befehlsblock-Maschinen (Plan B7): Daten aus Code sind vertrauenswürdig und brauchen die Stufe Befehle; Befehle aus
 * dem Auftrag überschreiben die Platzhalter als Plantext und gehen durch die Bestätigung.
 */
class CommandMachinesTest {

    private static final ModuleLibrary LIB = new ModuleLibrary() {
        @Override
        public Optional<Module> find(String name) {
            return all().stream().filter(m -> m.name().equals(name)).findFirst();
        }

        @Override
        public List<Module> all() {
            return List.of(CommandMachines.button().module(), CommandMachines.chain().module());
        }
    };
    private static final PlanCompiler COMPILER = new PlanCompiler(id -> true, "stone",
            new PlanCompiler.Limits(6000, 48), LIB);
    private static final Set<String> NAMES = Set.of("befehlsknopf", "befehlskette");

    private static BlockSpec cellAt(CompiledBuild b, Pos p) {
        BlockSpec found = null;
        for (CompiledBuild.Action a : b.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                if (c.pos().equals(p)) {
                    found = c.block();
                }
            }
        }
        return found;
    }

    @Test
    void moduleCommandBlocksAreTrustedButNeedTheCommandTier() {
        for (Machines.Machine m : List.of(CommandMachines.button(), CommandMachines.chain())) {
            for (CompiledBuild.Change c : m.module().cells()) {
                BlockSpec s = c.block();
                if (s.id().endsWith("command_block")) {
                    assertTrue(s.trustedNbt(), m.module().name());
                    assertFalse(PlanCommands.needsConfirmation(s), "aus Code: kein Klick nötig");
                    assertEquals(Tier.COMMANDS, BlockTiers.place(s, BlockTiers.WorldFacts.OVERWORLD).tier(),
                            "bauen nur mit der Stufe Befehle");
                }
            }
        }
        BlockSpec chain = CommandMachines.chain().module().cells().stream().map(CompiledBuild.Change::block)
                .filter(s -> s.id().equals("chain_command_block")).findFirst().orElseThrow();
        assertEquals(Nbt.Num.ofByte(1), chain.nbt().get("auto"), "Kette bleibt immer aktiv");
    }

    @Test
    void orderedCommandOverridesThePlaceholderAndNeedsConfirmation() throws Exception {
        String json = QuickOrders.commandMachine("bau mir einen Befehlsknopf mit dem Befehl time set day", NAMES)
                .orElseThrow();
        CompiledBuild b = COMPILER.compile(PlanParser.parse(json));
        BlockSpec impulse = cellAt(b, CommandMachines.IMPULSE);
        assertEquals("command_block", impulse.id());
        assertEquals("time set day", ((Nbt.Str) impulse.nbt().get("Command")).value());
        assertTrue(PlanCommands.needsConfirmation(impulse), "Befehl aus dem Auftrag: Bestätigung per Klick");
        assertEquals(1, PlanCommands.find(b).size(), "nur der eigene Befehl ist offen, der Knopf nicht");
        assertEquals("stone_button", cellAt(b, new Pos(0, 2, 0)).id(), "Knopf bleibt");
    }

    @Test
    void chainTakesUpToThreeCommandsInOrder() throws Exception {
        String json = QuickOrders.commandMachine("eine Befehlskette mit den Befehlen: /say a; say \"b\"; „say c“",
                NAMES).orElseThrow();
        CompiledBuild b = COMPILER.compile(PlanParser.parse(json));
        assertEquals("say a", ((Nbt.Str) cellAt(b, CommandMachines.IMPULSE).nbt().get("Command")).value());
        BlockSpec second = cellAt(b, CommandMachines.CHAIN.get(0));
        assertEquals("say \"b\"", ((Nbt.Str) second.nbt().get("Command")).value(), "Anführungszeichen im Befehl");
        assertEquals(Nbt.Num.ofByte(1), second.nbt().get("auto"));
        assertEquals("say c", ((Nbt.Str) cellAt(b, CommandMachines.CHAIN.get(1)).nbt().get("Command")).value());
        assertEquals(3, PlanCommands.find(b).size());
        assertTrue(QuickOrders.commandMachine("eine Befehlskette mit den Befehlen a; b; c; d", NAMES).isEmpty(),
                "vier Befehle: ans LLM");
    }

    @Test
    void refusedCommandBuildsOnlyTheModuleWithAHint() throws Exception {
        String json = QuickOrders.commandMachine("befehlsknopf mit dem befehl setblock ~1 ~ ~ stone", NAMES)
                .orElseThrow();
        assertTrue(json.contains("/bm befehlsblock"), json);
        CompiledBuild b = COMPILER.compile(PlanParser.parse(json));
        assertTrue(PlanCommands.find(b).isEmpty(), "der abgelehnte Befehl kommt nicht hinein");
        assertTrue(QuickOrders.commandMachine("bau einen befehlsknopf", NAMES).isEmpty(), "ohne Befehl: Name genügt");
        assertTrue(QuickOrders.commandMachine("befehlsknopf mit dem befehl x", Set.of()).isEmpty(), "ohne Bauteil");
    }

    @Test
    void probeStepsCoverPressHoldAndRepress() {
        Machines.Machine button = CommandMachines.button();
        assertEquals(5, button.sequence().size());
        assertEquals(Map.of("Wirkung", false), button.sequence().get(2).expect(), "gehalten: läuft nicht nach");
        assertEquals("state", button.outputKind());
        assertEquals(3, CommandMachines.chain().outputs().size());
    }
}
