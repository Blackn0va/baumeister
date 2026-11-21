package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Kette Bau → Nachtrag (mode update) → Rückgängig mit echten Blockzuständen, ohne Spielstart (AK-5.3). Geprüft wird
 * ClientBuilder.remember/undoMemory und ClientBuild.undoBuild. Die Welt ist eine Map; {@link #place} ist ein NACHBAU
 * dessen, was PlayerPlacer und CommandPlacer je Ziel tun (vorher-Zustand per putIfAbsent in originals, dann setzen) –
 * wer dort remember() ändert, muss diesen Nachbau mitziehen. Ob die Placer das im Spiel so tun, belegt nur
 * runClientGameTest.
 */
class ClientFollowUpTest {

    /** Erst nach bootMinecraftRegistries – als Konstante lief Blocks vor dem Bootstrap an (IllegalArgumentException). */
    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    @BeforeAll
    static void bootMinecraftRegistries() {
        // Gemessen 2026-11-21: Mit Bootstrap gehen Blocks und BlockState im Unit-Test (Probe, ohne Client-Start).
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final Object level = new Object();
    private final Map<BlockPos, BlockState> world = new HashMap<>();
    private final ClientMemory<Map<BlockPos, BlockState>> memory = new ClientMemory<>();

    private BlockState at(BlockPos p) {
        return world.getOrDefault(p, air());
    }

    private ClientBuild build(String prompt, Map<BlockPos, BlockState> cells) {
        List<ClientBuild.Target> targets = new ArrayList<>();
        cells.forEach((p, s) -> targets.add(new ClientBuild.Target(p, s, false)));
        return new ClientBuild(prompt, targets, false, 0, level);
    }

    /** Wie PlayerPlacer/CommandPlacer: alten Zustand merken (nur beim Bauen, nicht beim Rückgängig), dann setzen. */
    private void place(ClientBuild job) {
        for (ClientBuild.Target t : job.targets) {
            if (!job.undo) {
                job.originals.putIfAbsent(t.pos(), at(t.pos()));
            }
            world.put(t.pos(), t.state());
        }
    }

    private static ClientPlanner.Planned planned(String json, boolean update) {
        return new ClientPlanner.Planned(null, "", List.of(), List.of(), 1, json, update);
    }

    private void remember(ClientBuild b, ClientPlanner.Planned p, ClientMemory.Entry<Map<BlockPos, BlockState>> mem) {
        ClientBuilder.remember(memory, b, level, BlockPos.ZERO, Cardinal.NORTH, 0, 3, p, mem, this::at);
    }

    private static final String FIRST = "{\"mode\":\"new\",\"steps\":[{\"op\":\"fill\",\"block\":\"stone\"}]}";
    private static final String SECOND = "{\"mode\":\"update\",\"steps\":[{\"op\":\"set\",\"block\":\"red_wool\"}]}";

    /** Haus: drei Steine, Dach aus Holz darüber. */
    private Map<BlockPos, BlockState> house() {
        Map<BlockPos, BlockState> c = new LinkedHashMap<>();
        c.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(1, 1, 0), Blocks.OAK_PLANKS.defaultBlockState());
        return c;
    }

    private ClientBuild firstBuild() {
        ClientBuild first = build("bau ein Haus", house());
        remember(first, planned(FIRST, false), null);
        place(first);
        return first;
    }

    /** Nachtrag: Dach rot, der dritte Stein fällt weg (vollständiger neuer Plan ohne ihn). */
    private ClientBuild followUp() {
        var mem = memory.recall(level, 0, 0, 0, "mach das Dach rot").orElseThrow();
        Map<BlockPos, BlockState> c = new LinkedHashMap<>();
        c.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(1, 1, 0), Blocks.RED_NETHER_BRICKS.defaultBlockState());
        ClientBuild next = build("mach das Dach rot", c);
        remember(next, planned(SECOND, true), mem);
        place(next);
        return next;
    }

    @Test
    void followUpRemovesWhatTheNewPlanDropsAndRemembersItself() {
        firstBuild();
        ClientBuild next = followUp();
        assertEquals(new BlockPos(2, 0, 0), next.targets.get(0).pos(), "Wegräumen kommt zuerst");
        assertTrue(next.targets.get(0).state().isAir());
        assertEquals(air(), at(new BlockPos(2, 0, 0)));
        assertEquals(Blocks.RED_NETHER_BRICKS.defaultBlockState(), at(new BlockPos(1, 1, 0)));
        var now = memory.recall(level, 0, 0, 0, "mach die Tür blau").orElseThrow();
        assertEquals(SECOND, now.planJson());
        assertFalse(now.cells().containsKey(new BlockPos(2, 0, 0)), "Weggeräumtes gehört nicht mehr zum Bau");
    }

    @Test
    void undoOfTheFollowUpRestoresThePreviousBuildAndItsMemory() {
        firstBuild();
        Map<BlockPos, BlockState> afterFirst = new HashMap<>(world);
        ClientBuild next = followUp();
        assertTrue(next.originals.keySet().containsAll(house().keySet()),
                "Jede Zelle des vorigen Baus, die der Nachtrag ändert, steht in originals");
        place(next.undoBuild());
        ClientBuilder.undoMemory(memory, next);
        assertEquals(afterFirst, world, "Rückgängig des Nachtrags lässt den vorigen Bau stehen");
        var mem = memory.recall(level, 0, 0, 0, "mach das Dach blau").orElseThrow();
        assertEquals(FIRST, mem.planJson(), "Gedächtnis zeigt wieder auf den vorigen Bau, nicht auf den zurückgebauten");
        assertEquals(house(), mem.cells());
    }

    @Test
    void undoOfTheOnlyBuildForgetsIt() {
        ClientBuild first = firstBuild();
        place(first.undoBuild());
        ClientBuilder.undoMemory(memory, first);
        assertTrue(world.values().stream().allMatch(BlockState::isAir));
        assertFalse(memory.recall(level, 0, 0, 0, "mach das Dach rot").isPresent(),
                "Nach dem Rückgängig gibt es keinen Bau mehr, auf den ein Nachtrag aufbauen könnte");
    }

    @Test
    void additionThatDropsTheWholeOldBuildKeepsItAndMergesBothPlans() {
        firstBuild();
        var mem = memory.recall(level, 0, 0, 0, "bau noch einen Turm dran").orElseThrow();
        Map<BlockPos, BlockState> tower = new LinkedHashMap<>();
        tower.put(new BlockPos(5, 0, 0), Blocks.COBBLESTONE.defaultBlockState());
        ClientBuild next = build("bau noch einen Turm dran", tower);
        remember(next, planned(SECOND, true), mem);
        assertEquals(1, next.targets.size(), "UpdateGuard: nichts vom alten Bau wird weggeräumt");
        var now = memory.recall(level, 0, 0, 0, "mach ihn höher").orElseThrow();
        assertTrue(now.planJson().contains("stone") && now.planJson().contains("red_wool"), now.planJson());
        assertTrue(now.cells().keySet().containsAll(house().keySet()), "Gedächtnis kennt beide Teile");
    }
}
