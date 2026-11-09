package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Neumalen geschätzter Chunks: Merkliste auf der Platte, Plan je Säule (Spielerspuren bleiben) und der Fingerabdruck,
 * den der Populator beim Malen hinterlässt – alles ohne Server.
 */
class EarthRepaintTest {

    /** Projektion ohne Verzerrung um (48°, 11°): 1 Block = 1 m. */
    private static final EarthProjection FLAT = new EarthProjection() {
        @Override
        public double[] toWorld(double lat, double lon) {
            return new double[] {(lon - 11) * 111_320 * Math.cos(Math.toRadians(48)), (48 - lat) * 110_540};
        }

        @Override
        public double[] toGeo(double x, double z) {
            return new double[] {48 - z / 110_540, 11 + x / (111_320 * Math.cos(Math.toRadians(48)))};
        }
    };

    private static EarthTerrain terrain(Elevation fine) {
        Elevation coarse = (lat, lon) -> 0;  // grober Ersatz bei Netzausfall: 0 m
        return new EarthTerrain(FLAT, fine, coarse, new HeightModel((lat, lon) -> new double[] {0, 500}, 200), 64,
                -2032, 2032, new WaterTable((lat, lon) -> true));
    }

    /** Eine Welt als Tabelle; was nicht drinsteht, ist Luft. */
    private static final class World implements RepaintJob.Live {
        final Map<String, Material> blocks = new HashMap<>();

        @Override
        public void set(int x, int y, int z, Material m) {
            put(x, y, z, m);
        }

        World copy() {
            World c = new World();
            c.blocks.putAll(blocks);
            return c;
        }

        @Override
        public Material at(int x, int y, int z) {
            return blocks.getOrDefault(x + "/" + y + "/" + z, Material.AIR);
        }

        void put(int x, int y, int z, Material m) {
            if (m == Material.AIR) {
                blocks.remove(x + "/" + y + "/" + z);
            } else {
                blocks.put(x + "/" + y + "/" + z, m);
            }
        }

        void apply(RepaintPlan.Result r) {
            for (RepaintPlan.ColumnChanges col : r.columns()) {
                for (RepaintPlan.Change c : col.changes()) {
                    put(c.x(), c.y(), c.z(), c.material());
                }
            }
        }
    }

    private static final AtomicInteger FACTORY_CALLS = new AtomicInteger();

    private static BlockData fakeData(Material m) {
        FACTORY_CALLS.incrementAndGet();
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] {BlockData.class},
                (proxy, method, args) -> method.getName().equals("getMaterial") ? m : null);
    }

    /** Malt Chunk (0,0) wie der Populator in eine Tabellenwelt; liefert den Fingerabdruck. */
    private static EstimatedChunks.Print populate(EarthTerrain t, World w, boolean[] estimated) {
        EarthPopulator.RegionSink sink = new EarthPopulator.RegionSink(0, 0,
                (x, y, z, d) -> w.put(x, y, z, d.getMaterial()), EarthRepaintTest::fakeData);
        estimated[0] = t.paintChecked(0, 0, sink);
        sink.flush();
        return new EstimatedChunks.Print(sink.minY, sink.maxY, sink.top);
    }

    @Test
    void sinkWritesTheSameBlocksAsDirectPaintingTopDownAndOnce() {
        // Wasserfläche und Deich überschreiben schon gesetzte Blöcke – auch das muss gleich ausgehen.
        Elevation e = (lat, lon) -> FLAT.toWorld(lat, lon)[0] < 6 ? -1 : FLAT.toWorld(lat, lon)[0] < 11 ? -4.3 : 0;
        EarthTerrain t = terrain(e);
        World direct = new World();
        t.paintChecked(0, 0, new com.blackn0va.baumeister.terrain.DemGenerator.Sink() {
            @Override
            public void set(int x, int y, int z, Material m) {
                direct.put(x, y, z, m);
            }

            @Override
            public void fill(int x, int y0, int z, int y1, Material m) {
                for (int y = y0; y < y1; y++) {
                    direct.put(x, y, z, m);
                }
            }
        });
        World buffered = new World();
        Map<String, Integer> writes = new HashMap<>();
        int[] lastY = {Integer.MAX_VALUE, -1};
        boolean[] upward = {false};
        EarthPopulator.RegionSink sink = new EarthPopulator.RegionSink(0, 0, (x, y, z, d) -> {
            buffered.put(x, y, z, d.getMaterial());
            writes.merge(x + "/" + y + "/" + z, 1, Integer::sum);
            int col = z * 16 + x;
            if (col == lastY[1] && y > lastY[0]) {
                upward[0] = true;
            }
            lastY[0] = y;
            lastY[1] = col;
        }, EarthRepaintTest::fakeData);
        t.paintChecked(0, 0, sink);
        sink.flush();
        assertEquals(direct.blocks, buffered.blocks);
        assertTrue(writes.values().stream().allMatch(n -> n == 1), "Block doppelt geschrieben");
        assertEquals(false, upward[0], "eine Säule wurde von unten nach oben geschrieben");
    }

    @Test
    void estimatedChunkIsRepaintedOnceTheDataIsThere() {
        World w = new World();
        boolean[] est = new boolean[1];
        EstimatedChunks.Print old = populate(terrain((lat, lon) -> {
            throw new IOException("offline");
        }), w, est);
        assertTrue(est[0], "Chunk muss als geschätzt gemeldet werden");
        assertEquals(Material.SAND, w.at(4, 0, 4), "geschätzt: 0 m");
        EarthTerrain online = terrain((lat, lon) -> 420.4);
        RepaintPlan.Recording rec = new RepaintPlan.Recording();
        assertEquals(false, online.paintChecked(0, 0, rec));
        RepaintPlan.Result r = RepaintPlan.plan(old, rec, w, 2032);
        assertEquals(256, r.repainted());
        w.apply(r);
        assertEquals(Material.GRASS_BLOCK, w.at(4, 420, 4));
        assertEquals(Material.AIR, w.at(4, 421, 4));
        assertEquals(Material.BEDROCK, w.at(4, 420 - 64, 4));
        assertEquals(Material.AIR, w.at(4, -64, 4), "altes Grundgestein weg");
        // Neu gemalt = genau das, was ein frischer Chunk mit den echten Daten hätte.
        World fresh = new World();
        populate(online, fresh, est);
        assertEquals(fresh.blocks, w.blocks);
    }

    @Test
    void columnsAPlayerChangedAreKept() {
        World w = new World();
        boolean[] est = new boolean[1];
        EstimatedChunks.Print old = populate(terrain((lat, lon) -> {
            throw new IOException("offline");
        }), w, est);
        w.put(1, 1, 1, Material.OAK_PLANKS);  // auf die Oberfläche gebaut
        w.put(2, 0, 2, Material.AIR);  // Oberfläche abgegraben
        RepaintPlan.Recording rec = new RepaintPlan.Recording();
        terrain((lat, lon) -> 30.7).paintChecked(0, 0, rec);
        RepaintPlan.Result r = RepaintPlan.plan(old, rec, w, 2032);
        assertEquals(2, r.kept());
        assertEquals(254, r.repainted());
        w.apply(r);
        assertEquals(Material.OAK_PLANKS, w.at(1, 1, 1));
        assertEquals(Material.SAND, w.at(1, 0, 1));
        assertEquals(Material.AIR, w.at(1, 31, 1));
        assertEquals(Material.GRASS_BLOCK, w.at(3, 31, 3));
    }

    @Test
    void storeSurvivesARestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("erde").resolve("geschätzt.txt");
        EstimatedChunks s = new EstimatedChunks(file);
        int[] min = new int[256];
        int[] max = new int[256];
        Material[] top = new Material[256];
        for (int i = 0; i < 256; i++) {
            min[i] = -64 - i;
            max[i] = i;
            top[i] = i % 2 == 0 ? Material.SAND : Material.WATER;
        }
        s.add(-3, 7, new EstimatedChunks.Print(min, max, top));
        s.save();
        EstimatedChunks back = new EstimatedChunks(file);
        back.load();
        EstimatedChunks.Print p = back.get(EstimatedChunks.key(-3, 7));
        assertArrayEquals(min, p.minY());
        assertArrayEquals(max, p.maxY());
        assertArrayEquals(top, p.top());
        back.remove(EstimatedChunks.key(-3, 7));
        back.save();
        EstimatedChunks empty = new EstimatedChunks(file);
        empty.load();
        assertEquals(0, empty.size());
    }

    @Test
    void brokenLinesAreSkippedNotGuessed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("geschätzt.txt");
        Files.writeString(file, "# Kopf\n1 2 3,4,SAND\nkaputt\n");
        EstimatedChunks s = new EstimatedChunks(file);
        s.load();
        assertEquals(0, s.size());
        assertNull(s.get(EstimatedChunks.key(1, 2)));
    }

    /** Geschätzter Chunk (0 m) in w samt Merkliste; liefert den Plan auf 30,7 m gegen einen Schnappschuss. */
    private static RepaintPlan.Result planAgainstSnapshot(World w, EstimatedChunks store, EstimatedChunks.Print[] old) {
        boolean[] est = new boolean[1];
        EstimatedChunks.Print p = populate(terrain((lat, lon) -> {
            throw new IOException("offline");
        }), w, est);
        store.add(0, 0, p);
        old[0] = new EstimatedChunks.Print(p.minY().clone(), p.maxY().clone(), p.top().clone());
        RepaintPlan.Recording rec = new RepaintPlan.Recording();
        terrain((lat, lon) -> 30.7).paintChecked(0, 0, rec);
        return RepaintPlan.plan(old[0], rec, w.copy(), 2032);
    }

    @Test
    void columnsChangedAfterTheSnapshotAreKept(@TempDir Path dir) {
        // Geplant wird im Pool gegen einen Schnappschuss; gesetzt wird Ticks später. Was ein Spieler dazwischen auf die
        // Oberfläche stellt oder abgräbt, muss bleiben (vorher plante der Hauptthread direkt gegen die Welt).
        World w = new World();
        EstimatedChunks store = new EstimatedChunks(dir.resolve("g.txt"));
        EstimatedChunks.Print[] old = new EstimatedChunks.Print[1];
        RepaintPlan.Result r = planAgainstSnapshot(w, store, old);
        assertEquals(256, r.repainted());
        w.put(1, 1, 1, Material.OAK_PLANKS);
        w.put(2, 0, 2, Material.AIR);
        RepaintJob job = new RepaintJob(EstimatedChunks.key(0, 0), old[0], r.columns(), 2032);
        while (!job.done()) {
            job.step(w, store, 500);
        }
        assertEquals(2, job.skipped());
        assertEquals(Material.OAK_PLANKS, w.at(1, 1, 1), "Spielerblock bleibt");
        assertEquals(Material.AIR, w.at(1, 31, 1));
        assertEquals(Material.AIR, w.at(2, 0, 2), "abgegrabene Säule bleibt abgegraben");
        assertEquals(Material.GRASS_BLOCK, w.at(3, 31, 3), "der Rest ist neu gemalt");
    }

    @Test
    void unloadMidwayLeavesFinishedColumnsWithTheirNewPrint(@TempDir Path dir) {
        World w = new World();
        EstimatedChunks store = new EstimatedChunks(dir.resolve("g.txt"));
        EstimatedChunks.Print[] old = new EstimatedChunks.Print[1];
        RepaintPlan.Result r = planAgainstSnapshot(w, store, old);
        RepaintJob job = new RepaintJob(EstimatedChunks.key(0, 0), old[0], r.columns(), 2032);
        int set = job.step(w, store, 1);  // eine Säule, dann wird der Chunk „entladen“
        assertTrue(set > 0 && set <= 400, "eine Säule: " + set);
        EstimatedChunks.Print now = store.get(EstimatedChunks.key(0, 0));
        assertEquals(31, now.maxY()[0], "fertige Säule trägt ihren neuen Fingerabdruck");
        assertEquals(Material.GRASS_BLOCK, now.top()[0]);
        assertEquals(old[0].maxY()[255], now.maxY()[255], "der Rest bleibt beim alten Fingerabdruck");
        // Später neu geplant gegen die echte Welt: Keine Säule gilt als vom Spieler verändert, Ergebnis wie frisch.
        RepaintPlan.Recording rec = new RepaintPlan.Recording();
        terrain((lat, lon) -> 30.7).paintChecked(0, 0, rec);
        RepaintPlan.Result again = RepaintPlan.plan(now, rec, w, 2032);
        assertEquals(0, again.kept());
        w.apply(again);
        World fresh = new World();
        populate(terrain((lat, lon) -> 30.7), fresh, new boolean[1]);
        assertEquals(fresh.blocks, w.blocks);
    }

    @Test
    void listIsSavedOnTheClockWithoutAStop(@TempDir Path dir) throws IOException {
        // Vorher nur bei stop() und nach fertigem Neumalen gespeichert: Ein Absturz nach dem Netzausfall kostete die
        // Liste, die geschätzten Chunks blieben für immer grob.
        Path file = dir.resolve("geschätzt.txt");
        EstimatedChunks s = new EstimatedChunks(file);
        long t0 = s.lastSaveMillis();
        s.add(5, -6, populate(terrain((lat, lon) -> {
            throw new IOException("offline");
        }), new World(), new boolean[1]));
        List<IOException> errors = new ArrayList<>();
        s.saveIfDue(t0 + 1_000, EarthRepaint.SAVE_MILLIS, errors::add);
        EstimatedChunks early = new EstimatedChunks(file);
        early.load();
        assertEquals(0, early.size(), "vor dem Takt wird nicht geschrieben");
        s.saveIfDue(t0 + EarthRepaint.SAVE_MILLIS, EarthRepaint.SAVE_MILLIS, errors::add);
        EstimatedChunks back = new EstimatedChunks(file);
        back.load();
        assertEquals(1, back.size(), "nach dem Takt steht der Chunk auf der Platte");
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void populatorBuildsEachBlockDataOncePerChunk() {
        // Vorher region.setType(Material): Spigot baute für JEDEN der ~16 700 Blöcke ein neues BlockData.
        FACTORY_CALLS.set(0);
        World w = new World();
        populate(terrain((lat, lon) -> 420.4), w, new boolean[1]);
        assertTrue(w.blocks.size() > 16_000, "Blöcke: " + w.blocks.size());
        assertTrue(FACTORY_CALLS.get() <= 6, "BlockData gebaut: " + FACTORY_CALLS.get());
    }
}
