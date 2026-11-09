package com.blackn0va.baumeister.client;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Befehls-Bauweise: /fill für Reihen gleicher Blöcke (entlang x), sonst /setblock. Sehr schnell, braucht aber OP bzw.
 * Cheats und geht an der Plot-/Regionsprüfung des Servers vorbei wie jeder OP-Befehl – deshalb nur auf Wunsch
 * (/kbau modus befehl).
 *
 * Zweiteilige Blöcke (Türen, Betten, hohe Pflanzen) setzt der Befehl ohne Nachbar-Updates („strict“, ab 1.21.5) – mit
 * Updates zerfiele die erste Hälfte, bevor die zweite steht. Alles andere mit Updates, damit Redstone verbindet.
 *
 * Tempo: commandsPerSecond, als Bruchteil je Tick angespart. Vanilla wirft Nicht-OPs bei Spam hinaus, OPs nicht; der
 * Befehl wirkt ohnehin nur für OPs.
 *
 * Threads: Client-Thread. Fehler: Was der Server ablehnt, findet der Prüfdurchgang (ClientBuilder) als „stimmt nicht“.
 */
final class CommandPlacer {

    private final ClientSettings settings;
    private double budget;

    CommandPlacer(ClientSettings settings) {
        this.settings = settings;
    }

    /** Ein Tick: sendet so viele Befehle, wie das Tempo erlaubt. */
    void tick(Minecraft mc, ClientBuild job) {
        budget = Math.min(budget + settings.commandsPerSecond / 20.0, 50);
        while (budget >= 1 && job.current() != null) {
            ClientBuild.Target first = job.current();
            BlockState now = mc.level.getBlockState(first.pos());
            if (ClientBuild.sameIntent(now, first.state())) {
                job.next++;
                continue;
            }
            if (twoPart(first.state())) {
                send(mc, "setblock " + xyz(first.pos()) + " " + BlockStateParser.serialize(first.state()) + " strict");
                remember(job, mc, first.pos());
                job.next++;
                budget--;
                job.placed++;
                continue;
            }
            // Reihe: gleiche Zielzustände nebeneinander (x+1), keine zweiten Hälften.
            int end = job.next;
            while (end + 1 < job.targets.size()) {
                ClientBuild.Target n = job.targets.get(end + 1);
                ClientBuild.Target p = job.targets.get(end);
                if (n.companion() || !n.state().equals(first.state()) || n.pos().getY() != p.pos().getY()
                        || n.pos().getZ() != p.pos().getZ() || n.pos().getX() != p.pos().getX() + 1) {
                    break;
                }
                end++;
            }
            for (int i = job.next; i <= end; i++) {
                remember(job, mc, job.targets.get(i).pos());
            }
            String state = BlockStateParser.serialize(first.state());
            if (end == job.next) {
                send(mc, "setblock " + xyz(first.pos()) + " " + state);
            } else {
                send(mc, "fill " + xyz(first.pos()) + " " + xyz(job.targets.get(end).pos()) + " " + state);
            }
            job.placed += end - job.next + 1;
            job.next = end + 1;
            budget--;
        }
    }

    private static void remember(ClientBuild job, Minecraft mc, BlockPos pos) {
        if (!job.undo) {
            job.originals.putIfAbsent(pos.immutable(), mc.level.getBlockState(pos));
        }
    }

    static boolean twoPart(BlockState s) {
        return s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.hasProperty(BlockStateProperties.BED_PART);
    }

    private static String xyz(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static void send(Minecraft mc, String command) {
        mc.player.connection.sendCommand(command);
    }
}
