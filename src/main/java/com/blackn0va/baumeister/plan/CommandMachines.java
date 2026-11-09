package com.blackn0va.baumeister.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Befehlsblock-Maschinen (Plan B7; Wunsch 2026-11-08). Zwei geprüfte Bauteile:
 *
 * <ul>
 * <li><b>befehlsknopf</b>: Steinknopf auf einem Impuls-Befehlsblock – ein Druck, ein Befehl.</li>
 * <li><b>befehlskette</b>: Knopf auf einem Impuls-Befehlsblock, dahinter zwei Ketten-Befehlsblöcke (immer aktiv) –
 * ein Druck, drei Befehle nacheinander.</li>
 * </ul>
 *
 * Die Daten kommen aus Code (BlockSpec.parseTrusted) und gelten als vertrauenswürdig: kein Klick zur Bestätigung,
 * „immer aktiv“ an den Ketten-Blöcken bleibt. Bauen darf sie trotzdem nur, wer die Stufe Befehle hat (BlockTiers: ein
 * Befehlsblock mit Befehl ist COMMANDS) – für alle anderen fällt das ganze Bauteil weg. Die Befehle sind harmlose
 * Platzhalter („say …“); geändert werden sie im Spiel mit /bm befehlsblock oder gleich im Auftrag („bau einen
 * Befehlsknopf mit dem Befehl time set day“, QuickOrders). Die Probe (MachineProbe) setzt eigene Prüfbefehle per
 * data merge und prüft deren Wirkung in allen vier Richtungen.
 *
 * Threads: zustandslos. Fehler: keine (die Daten sind fest und im Test geprüft).
 */
final class CommandMachines {

    /** Wo im Bauteil der Impuls-Befehlsblock steht (für den Befehl aus dem Auftrag). */
    static final Pos IMPULSE = new Pos(0, 1, 0);
    /** Ketten-Blöcke der befehlskette, in Reihenfolge. */
    static final List<Pos> CHAIN = List.of(new Pos(0, 1, 1), new Pos(0, 1, 2));

    private CommandMachines() {
    }

    static Machines.Machine button() {
        Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        cells.put(IMPULSE, trusted("command_block[facing=up]{Command:\"say Befehlsknopf - mit /bm befehlsblock "
                + "<befehl> änderst du mich\"}"));
        cells.put(new Pos(0, 2, 0), trusted("stone_button[face=floor,facing=front]"));
        ModuleLibrary.Module module = module("befehlsknopf", "Command button (admins only): a stone button on top of an "
                + "impulse command block - one press runs the command once. Change the command in the game with "
                + "/bm befehlsblock while looking at the block.", cells, 1, 1);
        Machines.Pin knopf = new Machines.Pin("Knopf", new Pos(0, 2, 0), new Pos(0, 2, 0), null);
        Machines.Pin wirkung = new Machines.Pin("Wirkung", new Pos(0, 3, 0), new Pos(0, 3, 0),
                "entsteht:gold_block");
        // Ausgang „entsteht:“ – erst der Befehl setzt das Gold, der Ablauf nie selbst (MachinesTest prüft das).
        // Ablauf: Prüfbefehl einsetzen, Knopf drücken (Redstone-Block statt Knopf) → Gold erscheint. Weiter gehalten
        // läuft nichts nach (Impuls = nur die steigende Flanke), erst ein neuer Druck wieder.
        List<Machines.Step> steps = List.of(
                new Machines.Step(Map.of("Knopf", false), Map.of("Wirkung", false), List.of(
                        "data merge block {0,1,0} {Command:\"setblock {0,3,0} minecraft:gold_block\"}",
                        "setblock {0,3,0} minecraft:air")),
                new Machines.Step(Map.of("Knopf", true), Map.of("Wirkung", true)),
                new Machines.Step(Map.of("Knopf", true), Map.of("Wirkung", false),
                        List.of("setblock {0,3,0} minecraft:air")),
                new Machines.Step(Map.of("Knopf", false), Map.of("Wirkung", false)),
                new Machines.Step(Map.of("Knopf", true), Map.of("Wirkung", true)));
        return new Machines.Machine(module, List.of(knopf), List.of(wirkung), "state", Map.of(), steps);
    }

    static Machines.Machine chain() {
        Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        cells.put(IMPULSE, trusted("command_block[facing=back]{Command:\"say Befehlskette 1\"}"));
        cells.put(new Pos(0, 2, 0), trusted("stone_button[face=floor,facing=front]"));
        cells.put(CHAIN.get(0), trusted("chain_command_block[facing=back]{Command:\"say Befehlskette 2\",auto:1b}"));
        cells.put(CHAIN.get(1), trusted("chain_command_block[facing=back]{Command:\"say Befehlskette 3\",auto:1b}"));
        ModuleLibrary.Module module = module("befehlskette", "Command chain (admins only): button on an impulse "
                + "command block, two chain command blocks behind it - one press runs three commands in a row. Change "
                + "each with /bm befehlsblock.", cells, 1, 3);
        Machines.Pin knopf = new Machines.Pin("Knopf", new Pos(0, 2, 0), new Pos(0, 2, 0), null);
        List<Machines.Pin> outs = new ArrayList<>();
        List<String> setup = new ArrayList<>();
        for (int z = 0; z < 3; z++) {
            outs.add(new Machines.Pin("Befehl" + (z + 1), new Pos(0, 3, z), new Pos(0, 3, z), "entsteht:gold_block"));
            setup.add("data merge block {0,1," + z + "} {Command:\"setblock {0,3," + z + "} minecraft:gold_block\"}");
            setup.add("setblock {0,3," + z + "} minecraft:air");
        }
        Map<String, Boolean> none = Map.of("Befehl1", false, "Befehl2", false, "Befehl3", false);
        Map<String, Boolean> all = Map.of("Befehl1", true, "Befehl2", true, "Befehl3", true);
        List<Machines.Step> steps = List.of(
                new Machines.Step(Map.of("Knopf", false), none, setup),
                new Machines.Step(Map.of("Knopf", true), all));
        return new Machines.Machine(module, List.of(knopf), outs, "state", Map.of(), steps);
    }

    /**
     * Bauteil mit Boden aus Glattstein auf y=0 unter der ganzen Grundfläche (wie Machines.Grid) – der Knopf und die
     * Blöcke stehen so überall auf festem Grund.
     */
    private static ModuleLibrary.Module module(String name, String description, Map<Pos, BlockSpec> cells, int width,
            int depth) {
        List<CompiledBuild.Change> list = new ArrayList<>();
        int maxY = 0;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                list.add(new CompiledBuild.Change(new Pos(x, 0, z), BlockSpec.of("smooth_stone")));
            }
        }
        for (Map.Entry<Pos, BlockSpec> e : cells.entrySet()) {
            list.add(new CompiledBuild.Change(e.getKey(), e.getValue()));
            maxY = Math.max(maxY, e.getKey().y());
        }
        return new ModuleLibrary.Module(name, description, new Pos(width, maxY + 1, depth), List.copyOf(list));
    }

    private static BlockSpec trusted(String block) {
        try {
            return BlockSpec.parseTrusted(block);
        } catch (PlanException e) {
            throw new IllegalArgumentException(block, e);
        }
    }

    /**
     * Befehl(e) aus einem Auftrag („… mit dem Befehl time set day“, „… mit den Befehlen say a; say b; say c“) als
     * Schritte, die die Platzhalter im Bauteil überschreiben. Plantext: Jeder dieser Befehle geht durch die Bestätigung
     * des Admins (Plan B5), „immer aktiv“ am Impuls-Block wird zu „braucht Redstone“. null = kein brauchbarer Befehl.
     */
    static List<String> overrideBlocks(String module, List<String> commands) {
        if (commands.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (module.equals("befehlsknopf")) {
            if (commands.size() != 1) {
                return null;
            }
            out.add("command_block[facing=up]" + commandData(commands.getFirst(), false));
            return out;
        }
        if (module.equals("befehlskette") && commands.size() <= 3) {
            out.add("command_block[facing=back]" + commandData(commands.getFirst(), false));
            for (int i = 1; i < commands.size(); i++) {
                out.add("chain_command_block[facing=back]" + commandData(commands.get(i), true));
            }
            return out;
        }
        return null;
    }

    /** Blockdaten mit dem Befehl, neu geschrieben (Snbt.write maskiert " und \\). */
    private static String commandData(String command, boolean auto) {
        Map<String, Nbt> data = new LinkedHashMap<>();
        data.put("Command", new Nbt.Str(command));
        if (auto) {
            data.put("auto", Nbt.Num.ofByte(1));
        }
        return Snbt.write(new Nbt.Compound(data));
    }
}
