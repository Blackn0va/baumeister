package com.blackn0va.baumeister.town.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tätigkeiten mit Arbeitsfeldern (Vertrag v013, AK-43) ohne Server: Feldgrenzen, Persistenz, Rate, Erlaubnisliste und
 * Sperre (nie Behälter, nie Redstone), natürliche Bäume, Nachpflanzen und – über FieldWork – dass ein verbotenes
 * Ereignis keinen Block ändert.
 */
class FieldWorkTest {

    /** Gitter-Welt: Boden (grass_block) bis y=63, darüber Luft, einzelne Blöcke gesetzt. */
    static final class Grid implements Plot {
        final Map<String, String> types = new HashMap<>();
        final Map<String, Integer> ages = new HashMap<>();
        final Set<String> persistent = new HashSet<>();

        static String k(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        Grid set(int x, int y, int z, String type) {
            types.put(k(x, y, z), type);
            return this;
        }

        Grid crop(int x, int y, int z, String type, int age) {
            set(x, y - 1, z, "farmland");
            set(x, y, z, type);
            ages.put(k(x, y, z), age);
            return this;
        }

        @Override
        public String type(int x, int y, int z) {
            String t = types.get(k(x, y, z));
            if (t != null) {
                return t;
            }
            return y <= 63 ? (y == 63 ? "grass_block" : "dirt") : "air";
        }

        @Override
        public int age(int x, int y, int z) {
            return Safety.CROPS.contains(type(x, y, z)) ? ages.getOrDefault(k(x, y, z), 0) : -1;
        }

        @Override
        public int maxAge(int x, int y, int z) {
            return switch (type(x, y, z)) {
                case "wheat", "carrots", "potatoes" -> 7;
                case "beetroots" -> 3;
                default -> -1;
            };
        }

        @Override
        public boolean persistent(int x, int y, int z) {
            return persistent.contains(k(x, y, z));
        }
    }

    /**
     * Hände wie TownWork: erst das „Ereignis“ (deny = abgesagt), nur bei Erlaubnis die Änderung im Gitter. events zählt
     * die Ereignisse – das Schutz-Plugin sieht jede versuchte Änderung.
     */
    static final class FakeHands implements FieldWork.Hands {
        final Grid g;
        boolean deny;
        int events;
        final List<String> done = new ArrayList<>();

        FakeHands(Grid g) {
            this.g = g;
        }

        @Override
        public boolean breakBlock(Task.Change c) {
            events++;
            if (deny) {
                return false;
            }
            g.set(c.x(), c.y(), c.z(), "air");
            done.add("abbau " + c.expect());
            return true;
        }

        @Override
        public boolean place(Task.Change c) {
            String before = g.type(c.x(), c.y(), c.z());
            g.set(c.x(), c.y(), c.z(), c.type());
            g.ages.put(Grid.k(c.x(), c.y(), c.z()), 0);
            events++;
            if (deny) {
                // Wie TownWork.place: gesetzt, Ereignis abgesagt → zurück.
                g.set(c.x(), c.y(), c.z(), before);
                return false;
            }
            done.add("setzen " + c.type());
            return true;
        }
    }

    static final Field FIELD = new Field("acker", "welt", 0, 64, 0, 6, UUID.randomUUID());

    private static Task first(Grid g, FieldJob job) {
        return first(g, job, k -> false);
    }

    /** Erste Arbeit über eine ganze Runde der Suche (Planner sucht je Aufruf nur COLUMNS Spalten). */
    private static Task first(Grid g, FieldJob job, java.util.function.LongPredicate claimed) {
        int cursor = 0;
        for (int n = 0; n <= FIELD.columns() / Planner.COLUMNS + 1; n++) {
            Planner.Found f = Planner.find(g, FIELD, job, cursor, claimed);
            if (f.task() != null) {
                return f.task();
            }
            cursor = f.cursor();
        }
        return null;
    }

    /** Arbeit ganz ausführen (Rate großzügig). */
    private static FieldWork.Result runAll(Task t, Grid g, FakeHands h) {
        Rate rate = new Rate(1000);
        int[] i = {0};
        FieldWork.Result r;
        do {
            r = FieldWork.step(t, i, g, h, rate, 0);
        } while (r == FieldWork.Result.APPLIED);
        return r;
    }

    // ---------------------------------------------------------------- Feld

    @Test
    void fieldBoundsAreTheSquareAndHeightBand() {
        assertTrue(FIELD.contains("welt", 6, 64, -6));
        assertFalse(FIELD.contains("welt", 7, 64, 0), "außerhalb des Quadrats");
        assertFalse(FIELD.contains("welt", 0, 64 - Field.DOWN - 1, 0), "zu tief");
        assertFalse(FIELD.contains("welt", 0, 64 + Field.UP + 1, 0), "zu hoch");
        assertFalse(FIELD.contains("andere", 0, 64, 0), "andere Welt");
        assertEquals(169, FIELD.columns());
        assertNull(Field.check("acker_1", 8));
        assertNotNull(Field.check("Acker mit Leerzeichen", 8));
        assertNotNull(Field.check("a", 8));
        assertNotNull(Field.check("acker", Field.MAX_RADIUS + 1));
        assertNotNull(Field.check("acker", Field.MIN_RADIUS - 1));
    }

    @Test
    void cropOutsideTheFieldIsNeverHarvested() {
        Grid g = new Grid().crop(7, 64, 0, "wheat", 7).crop(0, 64, 7, "wheat", 7);
        for (int c = 0; c < FIELD.columns(); c += Planner.COLUMNS) {
            assertNull(Planner.find(g, FIELD, FieldJob.FARMER, c, k -> false).task(), "Ernte außerhalb");
        }
        g.crop(6, 64, 6, "wheat", 7);
        assertNotNull(first(g, FieldJob.FARMER), "am Rand innen");
    }

    @Test
    void fieldsSurviveARestartAndABrokenFileIsNeverOverwritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(Fields.FILE);
        Fields a = new Fields(file);
        a.load();
        assertNull(a.put(FIELD));
        assertNotNull(a.put(new Field("x y", "welt", 0, 0, 0, 5, null)), "ungültiger Name");
        Fields b = new Fields(file);
        assertTrue(b.load().isEmpty());
        assertEquals(List.of(FIELD), b.all());
        assertEquals(FIELD, b.nearest("welt", 30, 0, 64));
        assertNull(b.nearest("welt", 300, 0, 64));
        assertTrue(b.remove("ACKER"), "Name ohne Groß-/Kleinschreibung");
        Fields d = new Fields(file);
        d.load();
        assertTrue(d.all().isEmpty(), "entfernt bleibt entfernt");

        Files.writeString(file, "{kaputt", StandardCharsets.UTF_8);
        Fields c = new Fields(file);
        c.load();
        assertNotNull(c.broken());
        assertTrue(c.all().isEmpty(), "kaputt = keine Felder");
        assertNotNull(c.put(FIELD), "kaputte Datei überschrieben");
        assertEquals("{kaputt", Files.readString(file, StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- Rate

    @Test
    void rateAllowsAtMostNPerSlidingMinute() {
        Rate r = new Rate(3);
        assertTrue(r.tryTake(0));
        assertTrue(r.tryTake(10_000));
        assertTrue(r.tryTake(20_000));
        assertFalse(r.tryTake(30_000));
        assertFalse(r.tryTake(59_999));
        assertTrue(r.tryTake(60_000), "erster Platz frei nach 60 s");
        assertFalse(r.tryTake(60_001));
        assertEquals(3, r.used(60_001));
    }

    @Test
    void workWaitsWhenTheMinuteIsUsedUp() {
        Grid g = new Grid();
        for (int x = -2; x <= 2; x++) {
            g.crop(x, 64, 0, "wheat", 7);
        }
        FakeHands h = new FakeHands(g);
        Rate rate = new Rate(3);
        int changes = 0;
        for (int n = 0; n < 5; n++) {
            Task t = first(g, FieldJob.FARMER);
            int[] i = {0};
            FieldWork.Result r;
            do {
                r = FieldWork.step(t, i, g, h, rate, 1000);
                if (r == FieldWork.Result.APPLIED || r == FieldWork.Result.DONE) {
                    changes++;
                }
            } while (r == FieldWork.Result.APPLIED);
        }
        // Ernte und Saat gehen paarweise (harvestWaitsUntilTheReplantFitsInTheMinute): Der dritte Platz bliebe ein
        // Abbau ohne Saat, also wartet die zweite Ernte – 2 Änderungen, nie mehr als 3.
        assertEquals(2, changes, "Änderungen in der Minute");
        assertEquals(2, h.events, "kein Ereignis über der Rate");
    }

    /** Ernte nur, wenn auch die Saat in die Minute passt – sonst bliebe die Stelle leer, falls er dazwischen geht. */
    @Test
    void harvestWaitsUntilTheReplantFitsInTheMinute() {
        Grid g = new Grid().crop(1, 64, 1, "wheat", 7);
        FakeHands h = new FakeHands(g);
        Rate rate = new Rate(3);
        assertTrue(rate.tryTake(0) && rate.tryTake(0));
        Task t = first(g, FieldJob.FARMER);
        assertEquals(FieldWork.Result.WAIT, FieldWork.step(t, new int[] {0}, g, h, rate, 1000));
        assertEquals(0, h.events);
        assertEquals(7, g.age(1, 64, 1), "abgeerntet ohne Platz für die Saat");
        int[] i = {0};
        assertEquals(FieldWork.Result.APPLIED, FieldWork.step(t, i, g, h, rate, 60_000));
        assertTrue(FieldWork.pairedWithNext(t, 0));
        assertEquals(FieldWork.Result.DONE, FieldWork.step(t, i, g, h, rate, 60_000));
    }

    @Test
    void workSettingsMatchConfigCheckAndTheTemplate() {
        YamlConfiguration bundled = YamlConfiguration.loadConfiguration(new java.io.File(
                "src/main/resources/config.yml"));
        assertTrue(bundled.isBoolean(WorkSettings.KEY_WORLD_CHANGE));
        assertFalse(WorkSettings.from(bundled).worldChange(), "Vorgabe aus");
        assertEquals(WorkSettings.DEFAULT_RATE, WorkSettings.from(bundled).blocksPerMinute());
        YamlConfiguration c = new YamlConfiguration();
        c.set(WorkSettings.KEY_RATE, 100_000);
        assertEquals(WorkSettings.MAX_RATE, WorkSettings.from(c).blocksPerMinute());
        c.set(WorkSettings.KEY_RATE, 0);
        assertEquals(WorkSettings.MIN_RATE, WorkSettings.from(c).blocksPerMinute());
        assertTrue(WorkSettings.from(null).equals(WorkSettings.defaults()));
    }

    // ---------------------------------------------------------------- Bauer

    @Test
    void farmerHarvestsRipeCropAndReplantsIt() {
        Grid g = new Grid().crop(2, 64, 1, "wheat", 5).crop(3, 64, 1, "carrots", 7);
        Task t = first(g, FieldJob.FARMER);
        assertNotNull(t);
        assertEquals(3, t.changes().getFirst().x(), "nur die reife Möhre, nicht der halbe Weizen");
        FakeHands h = new FakeHands(g);
        assertEquals(FieldWork.Result.DONE, runAll(t, g, h));
        assertEquals(List.of("abbau carrots", "setzen carrots"), h.done);
        assertEquals("carrots", g.type(3, 64, 1), "nachgepflanzt");
        assertEquals(0, g.age(3, 64, 1), "als Saat");
        assertEquals("wheat", g.type(2, 64, 1), "unreifer Weizen bleibt");
        assertNull(first(g, FieldJob.FARMER), "nichts mehr reif");
    }

    // ---------------------------------------------------------------- Holzfäller

    /** Natürliche Eiche: Stamm 4 hoch bei (x, 64, z), Laubkrone (persistent=false) darum. */
    static void oak(Grid g, int x, int z) {
        for (int y = 64; y < 68; y++) {
            g.set(x, y, z, "oak_log");
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 66; y <= 68; y++) {
                    if (!(dx == 0 && dz == 0 && y < 68)) {
                        g.set(x + dx, y, z + dz, "oak_leaves");
                    }
                }
            }
        }
    }

    @Test
    void lumberjackFellsNaturalTreeAndPlantsASapling() {
        Grid g = new Grid();
        oak(g, 2, 2);
        Task t = first(g, FieldJob.LUMBERJACK);
        assertNotNull(t);
        assertEquals(5, t.changes().size(), "4 Stämme + 1 Setzling");
        assertEquals(64, t.changes().getFirst().y(), "von unten");
        FakeHands h = new FakeHands(g);
        assertEquals(FieldWork.Result.DONE, runAll(t, g, h));
        assertEquals("oak_sapling", g.type(2, 64, 2));
        for (int y = 65; y < 68; y++) {
            assertEquals("air", g.type(2, y, 2));
        }
        assertEquals("oak_leaves", g.type(1, 67, 2), "Laub fällt von selbst (Vanilla), nicht über die Rate");
    }

    /** Laub bis zur Kopfhöhe rund um den Stamm: Stehplatz zwei Blöcke weg statt im Stamm selbst. */
    @Test
    void lumberjackStandsBesideLowLeaves() {
        Grid g = new Grid();
        oak(g, 2, 2);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    g.set(2 + dx, 65, 2 + dz, "oak_leaves");
                }
            }
        }
        Task t = first(g, FieldJob.LUMBERJACK);
        assertNotNull(t);
        int d = Math.max(Math.abs(t.standX() - 2), Math.abs(t.standZ() - 2));
        assertEquals(2, d, "Stehplatz " + t.standX() + "," + t.standZ());
        assertEquals("air", g.type(t.standX(), t.standY(), t.standZ()));
    }

    @Test
    void playerBuiltWoodIsNotATree() {
        Grid persistentLeaves = new Grid();
        oak(persistentLeaves, 2, 2);
        persistentLeaves.persistent.add(Grid.k(1, 67, 2));
        assertNull(first(persistentLeaves, FieldJob.LUMBERJACK), "gesetztes Laub = Spielerbau");

        Grid house = new Grid();
        oak(house, 2, 2);
        house.set(3, 65, 2, "oak_planks");
        assertNull(first(house, FieldJob.LUMBERJACK), "Stamm an Brettern = Hauswand");

        Grid pile = new Grid();
        for (int y = 64; y < 67; y++) {
            pile.set(2, y, 2, "oak_log");
        }
        assertNull(first(pile, FieldJob.LUMBERJACK), "Stamm ohne Laub = Holzstapel");

        Grid tall = new Grid();
        for (int y = 64; y <= 64 + Field.UP + 2; y++) {
            tall.set(2, y, 2, "spruce_log");
        }
        for (int dx = -1; dx <= 1; dx++) {
            tall.set(2 + dx, 64 + Field.UP + 3, 2, "spruce_leaves");
            tall.set(2 + dx, 64 + Field.UP + 2, 3, "spruce_leaves");
        }
        assertNull(first(tall, FieldJob.LUMBERJACK), "ragt aus dem Feld – lieber stehen lassen");
    }

    // ---------------------------------------------------------------- Gärtner

    @Test
    void gardenerLaysThePathCrossAndFlowersOnTheRing() {
        Grid g = new Grid();
        Set<String> paths = new HashSet<>();
        Set<String> flowers = new HashSet<>();
        for (int round = 0; round < 400; round++) {
            Planner.Found f = Planner.find(g, FIELD, FieldJob.GARDENER, round * Planner.COLUMNS, k -> false);
            if (f.task() == null) {
                continue;
            }
            FakeHands h = new FakeHands(g);
            assertEquals(FieldWork.Result.DONE, runAll(f.task(), g, h));
            Task.Change c = f.task().changes().getFirst();
            (c.type().equals("dirt_path") ? paths : flowers).add(c.x() + "," + c.z());
        }
        assertEquals(4 * (FIELD.radius() - 1) + 1, paths.size(), "Kreuz durch die Mitte");
        for (String p : paths) {
            String[] xz = p.split(",");
            assertTrue(xz[0].equals("0") || xz[1].equals("0"), p);
        }
        assertFalse(flowers.isEmpty());
        assertNull(first(g, FieldJob.GARDENER), "einmal angelegt: keine Arbeit mehr");
    }

    // ---------------------------------------------------------------- Nie Behälter, nie Redstone

    @Test
    void containersAndRedstoneAreNeverTouched() {
        assertTrue(Safety.container("chest") && Safety.container("barrel") && Safety.container("red_shulker_box"));
        assertTrue(Safety.container("hopper") && Safety.container("decorated_pot"));
        assertTrue(Safety.redstone("lever") && Safety.redstone("oak_button") && Safety.redstone("repeater"));
        assertTrue(Safety.redstone("stone_pressure_plate") && Safety.redstone("redstone_wire"));
        assertFalse(Safety.forbidden("wheat") || Safety.forbidden("oak_log"));

        // Kiste neben der reifen Frucht, Hebel am Stamm, Draht neben dem Pfad: keine Arbeit.
        Grid crop = new Grid().crop(1, 64, 1, "wheat", 7).set(2, 64, 1, "chest");
        assertNull(first(crop, FieldJob.FARMER), "Kiste daneben");
        Grid tree = new Grid();
        oak(tree, 2, 2);
        tree.set(3, 64, 2, "lever");
        assertNull(first(tree, FieldJob.LUMBERJACK), "Hebel am Stamm");
        Grid redstone = new Grid().crop(1, 64, 1, "wheat", 7).set(1, 64, 2, "redstone_wire");
        assertNull(first(redstone, FieldJob.FARMER), "Redstone daneben");

        // Selbst eine Arbeit, die eine Kiste abbauen oder einen Hebel setzen will, kommt nie bis zu den Händen.
        Grid g = new Grid().set(0, 64, 0, "chest").set(4, 64, 4, "air");
        FakeHands h = new FakeHands(g);
        Task bad = new Task(FieldJob.FARMER, 0, 64, 1, List.of(new Task.Change(Task.Kind.BREAK, 0, 64, 0, "chest",
                "air")));
        assertEquals(FieldWork.Result.STALE, runAll(bad, g, h));
        Task lever = new Task(FieldJob.GARDENER, 4, 64, 5, List.of(new Task.Change(Task.Kind.PLACE, 4, 64, 4, "air",
                "lever")));
        assertEquals(FieldWork.Result.STALE, runAll(lever, g, h));
        Task stone = new Task(FieldJob.GARDENER, 4, 64, 5, List.of(new Task.Change(Task.Kind.BREAK, 4, 63, 4,
                "grass_block", "air")));
        assertEquals(FieldWork.Result.STALE, runAll(stone, g, h), "Boden abbauen steht nicht auf der Liste");
        assertEquals(0, h.events, "Hände angefasst");
        assertEquals("chest", g.type(0, 64, 0));
    }

    // ---------------------------------------------------------------- Rechte fail-closed

    @Test
    void forbiddenEventChangesNoBlock() {
        Grid g = new Grid().crop(1, 64, 1, "wheat", 7);
        oak(g, -3, -3);
        FakeHands h = new FakeHands(g);
        h.deny = true;
        Task harvest = first(g, FieldJob.FARMER);
        assertEquals(FieldWork.Result.DENIED, runAll(harvest, g, h));
        assertEquals("wheat", g.type(1, 64, 1));
        assertEquals(7, g.age(1, 64, 1));
        Task fell = first(g, FieldJob.LUMBERJACK);
        assertEquals(FieldWork.Result.DENIED, runAll(fell, g, h));
        assertEquals("oak_log", g.type(-3, 64, -3));
        Grid garden = new Grid();
        FakeHands gh = new FakeHands(garden);
        gh.deny = true;
        Task path = first(garden, FieldJob.GARDENER);
        assertEquals(FieldWork.Result.DENIED, runAll(path, garden, gh));
        Task.Change c = path.changes().getFirst();
        assertEquals(c.expect(), garden.type(c.x(), c.y(), c.z()), "Setzen zurückgenommen");
        assertTrue(h.done.isEmpty() && gh.done.isEmpty());
    }

    @Test
    void changedWorldDiscardsTheWork() {
        Grid g = new Grid().crop(1, 64, 1, "wheat", 7);
        Task t = first(g, FieldJob.FARMER);
        g.set(1, 64, 1, "air");
        FakeHands h = new FakeHands(g);
        assertEquals(FieldWork.Result.STALE, runAll(t, g, h));
        assertEquals(0, h.events);
    }

    @Test
    void claimedBlocksAreSkipped() {
        Grid g = new Grid().crop(1, 64, 1, "wheat", 7).crop(2, 64, 1, "wheat", 7);
        Task a = first(g, FieldJob.FARMER);
        Task b = first(g, FieldJob.FARMER, k -> k == a.key());
        assertNotNull(b);
        assertFalse(a.key() == b.key(), "zwei Bewohner nie am selben Block");
    }

    @Test
    void jobsFromTheRoster() {
        assertEquals(FieldJob.FARMER, FieldJob.of("Bauer"));
        assertEquals(FieldJob.LUMBERJACK, FieldJob.of("Holzfäller"));
        assertEquals(FieldJob.GARDENER, FieldJob.of("Gärtnerin"));
        assertNull(FieldJob.of("Bäckerin"));
        for (com.blackn0va.baumeister.town.Roster.Person p : com.blackn0va.baumeister.town.Roster.all()) {
            if (List.of("Karl", "Anton", "Greta").contains(p.name())) {
                assertNotNull(FieldJob.of(p.job()), p.name() + " hat keinen Feldberuf mehr: " + p.job());
            }
        }
    }
}
