package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.Test;

/**
 * Schutz vor der PlotSquared-Rekursion (dev01 2026-10-30): PlotSquared darf im ChunkLoadEvent nur laufen, wenn der Chunk
 * schon als geladen gilt – sonst lädt Spigots getEntities ihn verschachtelt nach.
 */
class ChunkLoadGuardTest {

    private static Plugin plotSquared() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getName" -> "PlotSquared";
                    case "isEnabled" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "PlotSquared";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getName" -> "stadtwelt";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "stadtwelt";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static ChunkLoadEvent event(World w, int cx, int cz) {
        Chunk c = (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[] {Chunk.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "getX" -> cx;
                    case "getZ" -> cz;
                    case "getWorld" -> w;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Chunk " + cx + "/" + cz;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return new ChunkLoadEvent(c, true);
    }

    /** PlotSquareds Handler als Zähler. */
    private static RegisteredListener original(int[] calls) {
        EventExecutor counting = (l, e) -> calls[0]++;
        return new RegisteredListener(new Listener() { }, counting, EventPriority.NORMAL, plotSquared(), false);
    }

    @Test
    void loadedChunkReachesPlotSquaredAtOnce() throws Exception {
        int[] calls = {0};
        List<Runnable> later = new ArrayList<>();
        EventExecutor guarded = ChunkLoadGuard.executor(original(calls), (w, x, z) -> true, later::add);
        guarded.execute(new Listener() { }, event(world(), 3, 4));
        assertEquals(1, calls[0]);
        assertTrue(later.isEmpty());
    }

    @Test
    void chunkNotYetLoadedIsHandedOverOneTickLaterInsteadOfLoadingItNested() throws Exception {
        int[] calls = {0};
        boolean[] loaded = {false};
        List<Runnable> later = new ArrayList<>();
        EventExecutor guarded = ChunkLoadGuard.executor(original(calls), (w, x, z) -> loaded[0], later::add);
        guarded.execute(new Listener() { }, event(world(), 3, 4));
        assertEquals(0, calls[0], "nicht mitten im Laden: PlotSquareds getEntities lüde den Chunk verschachtelt nach");
        assertEquals(1, later.size());
        loaded[0] = true;
        later.get(0).run();
        assertEquals(1, calls[0], "einen Tick später, wenn geladen, wie gewohnt");
    }

    @Test
    void chunkUnloadedAgainBeforeTheNextTickIsSkipped() throws Exception {
        int[] calls = {0};
        List<Runnable> later = new ArrayList<>();
        EventExecutor guarded = ChunkLoadGuard.executor(original(calls), (w, x, z) -> false, later::add);
        guarded.execute(new Listener() { }, event(world(), 3, 4));
        later.get(0).run();
        assertEquals(0, calls[0]);
    }

    @Test
    void onlyPlotSquaredsEntitySpawnListenerIsWrapped() {
        assertTrue(ChunkLoadGuard.matches("PlotSquared", "com.plotsquared.bukkit.listener.EntitySpawnListener"));
        assertFalse(ChunkLoadGuard.matches("Baumeister", "com.plotsquared.bukkit.listener.EntitySpawnListener"));
        assertFalse(ChunkLoadGuard.matches("PlotSquared", "com.plotsquared.bukkit.listener.ChunkListener"));
    }
}
