package com.blackn0va.baumeister.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verdrahtung des Gedächtnisses im echten ClientBuilder (AK-10): Bau anlegen (launch, die zweite Hälfte von begin),
 * Prüfdurchgang (verifyPass, aus tick), /kbau stop und /kbau rückgängig (undo) – dieselben Methoden wie im Spiel.
 * Vorher prüfte nur ClientFollowUpTest, und der rief remember/undoMemory direkt auf: Ohne den Aufruf in begin() oder
 * undo() blieben alle Tests grün (Gegenprobe des Dirigenten 2026-11-21).
 *
 * Nicht erreichbar ohne Spielstart sind nur die Placer (PlayerPlacer/CommandPlacer.tick brauchen Minecraft und Spieler).
 * {@link #place} bildet deren Schritt je Ziel nach – vorher-Zustand in originals, setzen, next/placed weiter. Ob die
 * Placer das im Spiel so tun, belegt nur runClientGameTest.
 */
class ClientBuilderMemoryTest {

    @BeforeAll
    static void bootMinecraftRegistries() {
        // Wie ClientFollowUpTest: Mit Bootstrap gehen Blocks und BlockState im Unit-Test (gemessen 2026-11-21).
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String FIRST = "{\"mode\":\"new\",\"steps\":[{\"op\":\"fill\",\"block\":\"stone\"}]}";
    private static final String SECOND = "{\"mode\":\"update\",\"steps\":[{\"op\":\"set\",\"block\":\"red_wool\"}]}";
    /** Tiefste Welthöhe wie in der Oberwelt – der Bau steht weit darüber. */
    private static final int MIN_Y = -64;

    private final Object level = new Object();
    private final Map<BlockPos, BlockState> world = new HashMap<>();
    private final List<String> said = new ArrayList<>();
    private ClientBuilder builder;

    @BeforeEach
    void newBuilder() throws Exception {
        ClientSettings s = new ClientSettings();
        s.reportResults = false;  // kein Versuch, die App über HTTP zu erreichen
        builder = new ClientBuilder(s, new ClientPlanner(s, id -> true), new ResultSender(s), said::add);
    }

    private BlockState at(BlockPos p) {
        return world.getOrDefault(p, Blocks.AIR.defaultBlockState());
    }

    private static ClientPlanner.Planned planned(String json, boolean update) {
        return new ClientPlanner.Planned(null, "", List.of(), List.of(), 1, json, update);
    }

    private ClientBuild build(String prompt, Map<BlockPos, BlockState> cells) {
        List<ClientBuild.Target> targets = new ArrayList<>();
        cells.forEach((p, st) -> targets.add(new ClientBuild.Target(p, st, false)));
        return new ClientBuild(prompt, targets, false, 0, level);
    }

    /** Haus: drei Steine, Dach aus Holz darüber. */
    private static Map<BlockPos, BlockState> house() {
        Map<BlockPos, BlockState> c = new LinkedHashMap<>();
        c.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(1, 1, 0), Blocks.OAK_PLANKS.defaultBlockState());
        return c;
    }

    /** Nachtrag zum Haus: Dach rot, der dritte Stein fällt weg. */
    private static Map<BlockPos, BlockState> redRoof() {
        Map<BlockPos, BlockState> c = new LinkedHashMap<>();
        c.put(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        c.put(new BlockPos(1, 1, 0), Blocks.RED_NETHER_BRICKS.defaultBlockState());
        return c;
    }

    /** Was begin() nach dem Plan tut – über den echten Einstieg, mit dem Gedächtnis-Eintrag wie start() ihn holt. */
    private ClientBuild launch(String prompt, Map<BlockPos, BlockState> cells, String json, boolean update) {
        var mem = builder.memory().recall(level, 0, 0, 0, prompt).orElse(null);
        ClientBuild next = build(prompt, cells);
        builder.launch(next, planned(json, update), level, MIN_Y, BlockPos.ZERO, Cardinal.NORTH, 0, 3, mem,
                this::at);
        assertEquals(next, builder.job(), "launch übernimmt den Bau");
        return next;
    }

    /** Nachbau von PlayerPlacer/CommandPlacer je Ziel: vorher-Zustand merken (nur beim Bauen), setzen, weiter. */
    private void place(ClientBuild job, int count) {
        for (int i = 0; i < count && job.current() != null; i++) {
            ClientBuild.Target t = job.current();
            if (!job.undo) {
                job.originals.putIfAbsent(t.pos(), at(t.pos()));
            }
            world.put(t.pos(), t.state());
            job.placed++;
            job.next++;
        }
    }

    /** Bau komplett setzen und den Prüfdurchgang laufen lassen (wie tick nach dem letzten Ziel). */
    private void finish(ClientBuild job) {
        place(job, job.total());
        builder.verifyPass(this::at);
        assertEquals(ClientBuild.State.DONE, job.state);
    }

    private ClientMemory.Entry<Map<BlockPos, BlockState>> recalled(String prompt) {
        return builder.memory().recall(level, 0, 0, 0, prompt).orElse(null);
    }

    @Test
    void launchedBuildLandsInMemory() {
        launch("bau ein Haus", house(), FIRST, false);
        var mem = recalled("mach das Dach rot");
        assertNotNull(mem, "Der gestartete Bau steht im Gedächtnis – ohne remember() in launch() fehlt er");
        assertEquals(FIRST, mem.planJson());
        assertEquals(house(), mem.cells());
    }

    @Test
    void undoOfTheOnlyBuildForgetsIt() {
        finish(launch("bau ein Haus", house(), FIRST, false));
        builder.undo(level);
        ClientBuild back = builder.job();
        assertTrue(back.undo, "undo startet den Rückbau");
        finish(back);
        assertTrue(world.values().stream().allMatch(BlockState::isAir));
        assertFalse(builder.memory().recall(level, 0, 0, 0, "mach das Dach rot").isPresent(),
                "Nach dem Rückgängig gibt es keinen Bau mehr – ohne undoMemory() in undo() bliebe er im Gedächtnis");
    }

    @Test
    void undoOfTheFollowUpPointsMemoryBackToTheBuildBefore() {
        finish(launch("bau ein Haus", house(), FIRST, false));
        Map<BlockPos, BlockState> afterFirst = new HashMap<>(world);
        finish(launch("mach das Dach rot", redRoof(), SECOND, true));
        assertEquals(SECOND, recalled("mach die Tür blau").planJson());
        builder.undo(level);
        finish(builder.job());
        assertEquals(afterFirst, world, "Rückgängig des Nachtrags lässt den vorigen Bau stehen");
        var mem = recalled("mach das Dach blau");
        assertNotNull(mem);
        assertEquals(FIRST, mem.planJson(), "Gedächtnis zeigt wieder auf den vorigen Bau");
    }

    @Test
    void stopBeforeAnyBlockDropsTheBuildFromMemory() {
        launch("bau ein Haus", house(), FIRST, false);
        builder.stop();
        assertFalse(builder.memory().recall(level, 0, 0, 0, "mach das Dach rot").isPresent(),
                "Abgebrochen ohne gesetzten Block: kein Bau, auf den ein Nachtrag aufsetzen dürfte");
    }

    @Test
    void stopOfAFollowUpBeforeAnyBlockKeepsTheBuildBefore() {
        finish(launch("bau ein Haus", house(), FIRST, false));
        launch("mach das Dach rot", redRoof(), SECOND, true);
        builder.stop();
        var mem = recalled("mach das Dach blau");
        assertNotNull(mem, "Der Bau davor steht noch und bleibt nachtragbar");
        assertEquals(FIRST, mem.planJson());
        assertEquals(house(), mem.cells());
    }

    @Test
    void stopAfterSomeBlocksKeepsTheBuildAndUndoStillWorks() {
        ClientBuild first = launch("bau ein Haus", house(), FIRST, false);
        place(first, 2);
        builder.stop();
        var mem = recalled("mach das Dach rot");
        assertNotNull(mem, "Ein Teil steht – der Bau bleibt im Gedächtnis");
        assertEquals(FIRST, mem.planJson());
        builder.undo(level);
        assertTrue(builder.job().undo, "Rückgängig räumt die gesetzten Blöcke weg");
        assertEquals(2, builder.job().total());
        finish(builder.job());
        assertFalse(builder.memory().recall(level, 0, 0, 0, "mach das Dach rot").isPresent());
    }
}
