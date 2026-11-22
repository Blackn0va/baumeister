package com.blackn0va.baumeister.town.use;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.town.DayPlan;
import com.blackn0va.baumeister.town.Roster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

/**
 * Benutzen (Vertrag v013, AK-44) ohne Server: Türen, Wegsuche durch Türen, Bett, Bank, Arbeitsblock je Beruf, nie
 * Behälter. Welt und Körper sind Fakes (FakeUseWorld, FakeActor), die jede Änderung mitschreiben.
 */
class TownUseTest {

    /** Zufall, der immer „ja, sofort, kürzeste Dauer“ sagt (nextDouble 0, nextInt 0). */
    private static final RandomGenerator YES = () -> 0L;
    private static final Location HOME = new Location(null, 0.5, 64, 0.5);

    private static Map<UUID, Actor> all(Actor... actors) {
        Map<UUID, Actor> m = new HashMap<>();
        for (Actor a : actors) {
            m.put(a.id(), a);
        }
        return m;
    }

    // ---------------------------------------------------------------- Türen

    @Test
    void doorOpensWhenPassingAndClosesBehindHim() {
        FakeUseWorld w = new FakeUseWorld().door(5, 64, 0, "OAK_DOOR", false);
        TownUse use = new TownUse(YES);
        FakeActor a = new FakeActor(4.3, 64, 0.5);
        use.pass(a, w, 5.5, 0.5, 100);
        assertTrue(w.isOpen(5, 64, 0) && w.isOpen(5, 65, 0), "Tür vor ihm geht auf");
        assertEquals(1, use.openDoors());
        use.tick(105, all(a));
        assertTrue(w.isOpen(5, 64, 0), "nicht vor DOOR_MIN_TICKS zu");
        a.move(5.5, 64, 0.5);
        use.tick(115, all(a));
        assertTrue(w.isOpen(5, 64, 0), "nicht zu, solange er im Durchgang steht");
        a.move(7.5, 64, 0.5);
        use.tick(120, all(a));
        assertFalse(w.isOpen(5, 64, 0), "hinter ihm wieder zu");
        assertEquals(List.of("open 5 64 0 OAK_DOOR", "close 5 64 0 OAK_DOOR"), w.writes);
        assertEquals(List.of("block.wooden_door.open", "block.wooden_door.close"), w.sounds);
        assertEquals(0, use.openDoors());
    }

    @Test
    void doorStaysOpenWhileAPlayerStandsInItButNotForever() {
        FakeUseWorld w = new FakeUseWorld().door(5, 64, 0, "SPRUCE_FENCE_GATE", false);
        TownUse use = new TownUse(YES);
        FakeActor a = new FakeActor(4.3, 64, 0.5);
        use.pass(a, w, 5.5, 0.5, 0);
        a.move(9, 64, 0.5);
        w.players.add(new double[] {5.5, 64, 0.5});
        use.tick(50, all(a));
        assertTrue(w.isOpen(5, 64, 0), "Spieler im Tor: offen lassen");
        use.tick(TownUse.DOOR_HARD_MAX_TICKS, all(a));
        assertFalse(w.isOpen(5, 64, 0), "spätestens nach DOOR_HARD_MAX_TICKS zu");
    }

    @Test
    void alreadyOpenDoorIsLeftAsItWas() {
        FakeUseWorld w = new FakeUseWorld().door(5, 64, 0, "OAK_DOOR", true);
        TownUse use = new TownUse(YES);
        FakeActor a = new FakeActor(4.3, 64, 0.5);
        use.pass(a, w, 5.5, 0.5, 0);
        a.move(9, 64, 0.5);
        use.tick(400, all(a));
        assertTrue(w.writes.isEmpty(), "offene Tür weder an- noch zugefasst: " + w.writes);
        assertTrue(w.isOpen(5, 64, 0));
    }

    /** Mauer bei x=5 (z −30…30, y 64–65), Tür bei z=0; weiter als CompanionPath.RANGE, also kein Weg außen herum. */
    private static FakeUseWorld wallWithDoor(String door) {
        FakeUseWorld w = new FakeUseWorld();
        for (int z = -30; z <= 30; z++) {
            w.put(5, 64, z, "STONE").put(5, 65, z, "STONE");
        }
        return w.door(5, 64, 0, door, false);
    }

    private static List<CompanionPath.Node> path(CompanionPath.Blocks b) {
        return CompanionPath.find(b, new CompanionPath.Node(2, 64, 0), new CompanionPath.Node(8, 64, 0));
    }

    @Test
    void allowedDoorIsAPassageForThePathfinder() {
        FakeUseWorld w = wallWithDoor("OAK_DOOR");
        TownUse use = new TownUse(YES);
        assertTrue(path(w.pathBlocks()).isEmpty(), "ohne Benutzen: geschlossene Tür ist Wand");
        List<CompanionPath.Node> p = path(use.blocks(w.pathBlocks(), w, UUID.randomUUID(), 0));
        assertFalse(p.isEmpty(), "mit Benutzen: Weg durch die Tür");
        assertTrue(p.contains(new CompanionPath.Node(5, 64, 0)), "der Weg führt durch die Tür: " + p);
    }

    @Test
    void forbiddenDoorStaysClosedAndIsNotCrossed() {
        FakeUseWorld w = wallWithDoor("OAK_DOOR");
        w.rights = p -> false;
        TownUse use = new TownUse(YES);
        UUID who = UUID.randomUUID();
        assertTrue(path(use.blocks(w.pathBlocks(), w, who, 0)).isEmpty(), "verboten: kein Weg hindurch");
        FakeActor a = new FakeActor(4.3, 64, 0.5);
        use.pass(a, w, 5.5, 0.5, 0);
        assertTrue(w.writes.isEmpty(), "verboten: nie geöffnet " + w.writes);
        assertFalse(w.isOpen(5, 64, 0));
        assertTrue(use.doorCounts()[2] >= 1, "Verweigerung gezählt");
    }

    @Test
    void ironDoorsAndIronTrapdoorsAreNeverOpened() {
        for (String iron : List.of("IRON_DOOR", "IRON_TRAPDOOR")) {
            FakeUseWorld w = wallWithDoor(iron);
            TownUse use = new TownUse(YES);
            assertTrue(path(use.blocks(w.pathBlocks(), w, UUID.randomUUID(), 0)).isEmpty(), iron);
            use.pass(new FakeActor(4.3, 64, 0.5), w, 5.5, 0.5, 0);
            assertTrue(w.writes.isEmpty() && w.asked.isEmpty(), iron + ": weder gefragt noch geöffnet");
        }
        assertEquals(UseBlocks.DoorKind.TRAPDOOR, UseBlocks.door("OAK_TRAPDOOR"));
        assertEquals(UseBlocks.DoorKind.DOOR, UseBlocks.door("COPPER_DOOR"));
        assertEquals(UseBlocks.DoorKind.GATE, UseBlocks.door("BIRCH_FENCE_GATE"));
    }

    @Test
    void rightsAnswerIsRememberedNotAskedPerSearchStep() {
        FakeUseWorld w = wallWithDoor("OAK_DOOR");
        TownUse use = new TownUse(YES);
        UUID who = UUID.randomUUID();
        path(use.blocks(w.pathBlocks(), w, who, 0));
        path(use.blocks(w.pathBlocks(), w, who, 10));
        assertEquals(2, w.asked.size(), "je Türhälfte einmal gefragt, nicht je Suche: " + w.asked);
        path(use.blocks(w.pathBlocks(), w, who, TownUse.RIGHTS_TTL_TICKS + 1));
        assertEquals(4, w.asked.size(), "nach Ablauf neu gefragt");
    }

    @Test
    void doorsSwitchedOffMeansOldPathAndNoOpening() {
        FakeUseWorld w = wallWithDoor("OAK_DOOR");
        TownUse use = new TownUse(YES);
        use.configure(new UseSettings(true, false, true, true, true));
        CompanionPath.Blocks base = w.pathBlocks();
        assertSame(base, use.blocks(base, w, UUID.randomUUID(), 0));
        use.pass(new FakeActor(4.3, 64, 0.5), w, 5.5, 0.5, 0);
        assertTrue(w.writes.isEmpty());
        use.configure(new UseSettings(false, true, true, true, true));
        assertSame(base, use.blocks(base, w, UUID.randomUUID(), 0), "nutzen.an=false schaltet alles ab");
    }

    // ---------------------------------------------------------------- Bett

    @Test
    void atNightHeLiesDownInAFreeBedNearHome() {
        FakeUseWorld w = new FakeUseWorld().bed(3, 64, 0, "EAST", false);
        TownUse use = new TownUse(YES);
        FakeActor a = new FakeActor(0.5, 64, 0.5);
        Location spot = use.target(a, w, DayPlan.Goal.SLEEP, HOME, "Bäckerin", 0);
        assertNotSame(HOME, spot, "zum Bett, nicht nach Hause");
        assertTrue(use.hasBed(a.id) && !use.sleeping(a.id));
        assertSame(spot, use.target(a, w, DayPlan.Goal.SLEEP, HOME, "Bäckerin", 1), "Ziel bleibt dasselbe Objekt");
        a.move(spot.getX(), spot.getY(), spot.getZ());
        Location lying = use.target(a, w, DayPlan.Goal.SLEEP, HOME, "Bäckerin", 2);
        assertEquals("lie 3.4000 64.6875 0.5000 180", a.calls.getFirst(), "Kopfteil, Liegehöhe, Kopf nach Osten");
        assertTrue(use.sleeping(a.id) && use.resting(a.id));
        assertEquals(64.6875, lying.getY(), 1e-9);
        assertTrue(w.writes.isEmpty(), "Bett bleibt unverändert (kein occupied)");
    }

    @Test
    void bedOnlyAtNight() {
        FakeUseWorld w = new FakeUseWorld().bed(1, 64, 1, "EAST", false);
        TownUse use = new TownUse(YES);
        FakeActor a = new FakeActor(1.5, 64, 0.5);
        for (DayPlan.Goal g : List.of(DayPlan.Goal.WORK, DayPlan.Goal.PLAZA, DayPlan.Goal.HOME)) {
            for (int t = 0; t < 2000; t += 100) {
                use.target(a, w, g, HOME, "Bäckerin", t);
            }
        }
        assertEquals(0, a.count("lie"), "am Tag nie ins Bett: " + a.calls);
        assertFalse(use.hasBed(a.id));
    }

    @Test
    void occupiedClaimedOrWantedBedIsNotTaken() {
        TownUse use = new TownUse(YES);
        FakeUseWorld occupied = new FakeUseWorld().bed(3, 64, 0, "EAST", true);
        assertSame(HOME, use.target(new FakeActor(0.5, 64, 0.5), occupied, DayPlan.Goal.SLEEP, HOME, "Bauer", 0),
                "belegt (Spieler/Dorfbewohner)");
        FakeUseWorld one = new FakeUseWorld().bed(3, 64, 0, "EAST", false);
        FakeActor first = new FakeActor(0.5, 64, 0.5);
        FakeActor second = new FakeActor(0.5, 64, 0.5);
        assertNotSame(HOME, use.target(first, one, DayPlan.Goal.SLEEP, HOME, "Bauer", 0));
        assertSame(HOME, use.target(second, one, DayPlan.Goal.SLEEP, HOME, "Bauer", 0), "schon vergeben");
        FakeUseWorld wanted = new FakeUseWorld().bed(3, 64, 0, "EAST", false);
        wanted.players.add(new double[] {2.5, 64, 1.5});
        assertSame(HOME, new TownUse(YES).target(new FakeActor(0.5, 64, 0.5), wanted, DayPlan.Goal.SLEEP, HOME,
                "Bauer", 0), "Spieler steht am Bett");
    }

    private static FakeActor lying(TownUse use, FakeUseWorld w) {
        FakeActor a = new FakeActor(0.5, 64, 0.5);
        Location spot = use.target(a, w, DayPlan.Goal.SLEEP, HOME, "Bauer", 0);
        a.move(spot.getX(), spot.getY(), spot.getZ());
        use.target(a, w, DayPlan.Goal.SLEEP, HOME, "Bauer", 1);
        assertTrue(use.sleeping(a.id));
        return a;
    }

    @Test
    void playerWantsTheBedResidentGetsUp() {
        FakeUseWorld w = new FakeUseWorld().bed(3, 64, 0, "EAST", false);
        TownUse use = new TownUse(YES);
        FakeActor a = lying(use, w);
        use.tick(5, all(a));
        assertTrue(use.sleeping(a.id), "ohne Spieler bleibt er liegen");
        w.players.add(new double[] {3.5, 64, 1.8});
        use.tick(6, all(a));
        assertFalse(use.sleeping(a.id), "Spieler am Bett: aufstehen");
        assertEquals(1, a.count("rise"));
    }

    @Test
    void inTheMorningHeGetsUp() {
        FakeUseWorld w = new FakeUseWorld().bed(3, 64, 0, "EAST", false);
        TownUse use = new TownUse(YES);
        FakeActor a = lying(use, w);
        use.target(a, w, DayPlan.Goal.WORK, HOME, "Bauer", 100);
        assertEquals(1, a.count("rise"));
        assertFalse(use.hasBed(a.id));
        // Bett wieder frei für den nächsten.
        FakeActor b = new FakeActor(0.5, 64, 0.5);
        assertNotSame(HOME, use.target(b, w, DayPlan.Goal.SLEEP, HOME, "Bauer", 101));
    }

    @Test
    void bedsSwitchedOffMeansNoBed() {
        FakeUseWorld w = new FakeUseWorld().bed(3, 64, 0, "EAST", false);
        TownUse use = new TownUse(YES);
        use.configure(new UseSettings(true, true, false, true, true));
        assertSame(HOME, use.target(new FakeActor(0.5, 64, 0.5), w, DayPlan.Goal.SLEEP, HOME, "Bauer", 0));
    }

    // ---------------------------------------------------------------- Sitzen

    @Test
    void atNoonHeSitsOnABenchAndGetsUpAgain() {
        FakeUseWorld w = new FakeUseWorld().put(4, 64, 0, new UseWorld.Block("OAK_STAIRS", "EAST", "BOTTOM", false,
                false));
        TownUse use = new TownUse(YES);
        FakeActor a = new FakeActor(0.5, 64, 0.5);
        Location seat = use.target(a, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 0);
        assertEquals(4.5, seat.getX(), 1e-9);
        assertEquals(65.0, seat.getY(), 1e-9);
        a.move(seat.getX(), seat.getY(), seat.getZ());
        use.target(a, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 1);
        assertEquals("sit 4.50 64.50 0.50 90", a.calls.getFirst(), "Sitzfläche, Blick weg von der Lehne (West)");
        assertTrue(use.resting(a.id));
        use.target(a, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 1 + TownUse.SIT_MIN_TICKS - 1);
        assertTrue(use.resting(a.id), "sitzt mindestens SIT_MIN_TICKS");
        assertSame(HOME, use.target(a, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 1 + TownUse.SIT_MIN_TICKS));
        assertEquals(1, a.count("unseat"));
        assertFalse(use.resting(a.id));
        assertTrue(w.writes.isEmpty(), "Sitzen ändert keinen Block");
    }

    @Test
    void slabBenchWorksStaircaseIsNoBench() {
        FakeUseWorld slab = new FakeUseWorld().put(3, 64, 0, new UseWorld.Block("OAK_SLAB", "", "BOTTOM", false,
                false));
        assertTrue(TownUse.bench(slab, slab.at(3, 64, 0), 3, 64, 0));
        FakeUseWorld top = new FakeUseWorld().put(3, 64, 0, new UseWorld.Block("OAK_SLAB", "", "TOP", false, false));
        assertFalse(TownUse.bench(top, top.at(3, 64, 0), 3, 64, 0), "obere Platte ist keine Bank");
        FakeUseWorld stairs = new FakeUseWorld()
                .put(4, 64, 0, new UseWorld.Block("OAK_STAIRS", "EAST", "BOTTOM", false, false))
                .put(5, 65, 0, new UseWorld.Block("OAK_STAIRS", "EAST", "BOTTOM", false, false));
        assertFalse(TownUse.bench(stairs, stairs.at(4, 64, 0), 4, 64, 0), "Hausaufgang ist keine Bank");
        FakeUseWorld covered = new FakeUseWorld()
                .put(4, 64, 0, new UseWorld.Block("OAK_STAIRS", "EAST", "BOTTOM", false, false))
                .put(4, 65, 0, "STONE");
        assertFalse(TownUse.bench(covered, covered.at(4, 64, 0), 4, 64, 0), "kein Kopfraum");
    }

    @Test
    void seatRefusedMeansHeKeepsStanding() {
        FakeUseWorld w = new FakeUseWorld().put(4, 64, 0, new UseWorld.Block("OAK_STAIRS", "EAST", "BOTTOM", false,
                false));
        TownUse use = new TownUse(YES);
        FakeActor a = new FakeActor(0.5, 64, 0.5);
        a.seatRefused = true;
        Location seat = use.target(a, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 0);
        a.move(seat.getX(), seat.getY(), seat.getZ());
        assertSame(HOME, use.target(a, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 1));
        assertFalse(use.resting(a.id));
    }

    // ---------------------------------------------------------------- Arbeitsblöcke

    @Test
    void everyJobWorksAtItsOwnBlock() {
        List<String> jobs = new java.util.ArrayList<>(Roster.all().stream().map(Roster.Person::job).toList());
        jobs.add("Unbekannt");
        for (String job : jobs) {
            String block = UseBlocks.workBlocks(job).getFirst();
            FakeUseWorld w = new FakeUseWorld().put(4, 64, 0, block);
            TownUse use = new TownUse(YES);
            FakeActor a = new FakeActor(0.5, 64, 0.5);
            Location spot = use.target(a, w, DayPlan.Goal.WORK, HOME, job, 0);
            assertNotSame(HOME, spot, job + " geht zu " + block);
            assertTrue(Math.abs(spot.getX() - 4.5) + Math.abs(spot.getZ() - 0.5) <= 1.0 + 1e-9,
                    job + ": Stehplatz direkt am Block");
            a.move(spot.getX(), spot.getY(), spot.getZ());
            use.target(a, w, DayPlan.Goal.WORK, HOME, job, 1);
            UseBlocks.Effect e = UseBlocks.effect(block);
            assertTrue(e != null, block + " hat eine Wirkung");
            assertEquals(List.of("face", "swing", "effect " + e.sound() + " " + e.particle()), a.calls, job);
            assertEquals(TownUse.Mode.WORKING, use.mode(a.id));
            assertTrue(w.writes.isEmpty(), job + ": keine Weltänderung");
        }
        assertEquals("CRAFTING_TABLE", UseBlocks.workBlocks("Unbekannt").getFirst());
    }

    @Test
    void bakerPrefersSmokerOverCraftingTable() {
        FakeUseWorld w = new FakeUseWorld().put(2, 64, 0, "CRAFTING_TABLE").put(6, 64, 0, "SMOKER");
        TownUse use = new TownUse(YES);
        Location spot = use.target(new FakeActor(0.5, 64, 0.5), w, DayPlan.Goal.WORK, HOME, "Bäckerin", 0);
        assertTrue(Math.abs(spot.getX() - 6.5) <= 1.0 + 1e-9, "Räucherofen vor Werkbank: " + spot);
    }

    @Test
    void otherJobsBlockIsIgnored() {
        FakeUseWorld w = new FakeUseWorld().put(4, 64, 0, "ANVIL");
        TownUse use = new TownUse(YES);
        assertSame(HOME, use.target(new FakeActor(0.5, 64, 0.5), w, DayPlan.Goal.WORK, HOME, "Gärtnerin", 0));
    }

    @Test
    void neverAContainerOrAnyOtherBlock() {
        List<String> props = List.of("BARREL", "FURNACE", "SMOKER", "BLAST_FURNACE", "BREWING_STAND", "CHEST",
                "COMPOSTER", "LECTERN", "JUKEBOX", "BELL", "CAULDRON", "LEVER", "STONE_BUTTON");
        FakeUseWorld w = new FakeUseWorld().door(-3, 64, 0, "OAK_DOOR", false);
        for (int i = 0; i < props.size(); i++) {
            w.put(2 + i % 4, 64, 2 + i / 4, props.get(i));
        }
        Map<String, UseWorld.Block> before = new HashMap<>(w.blocks);
        TownUse use = new TownUse(YES);
        for (Roster.Person p : Roster.all()) {
            FakeActor a = new FakeActor(0.5, 64, 0.5);
            for (int t = 0; t < 400; t += 37) {
                for (DayPlan.Goal g : DayPlan.Goal.values()) {
                    Location spot = use.target(a, w, g, HOME, p.job(), t);
                    a.move(spot.getX(), spot.getY(), spot.getZ());
                    use.pass(a, w, spot.getX() + 1, spot.getZ(), t);
                    use.target(a, w, g, HOME, p.job(), t + 1);
                }
                // Durch die Tür und weiter, damit auch Öffnen und Schließen in diesem Lauf vorkommen.
                a.move(-4.3, 64, 0.5);
                use.pass(a, w, -2.5, 0.5, t);
                a.move(-1.5, 64, 0.5);
                use.tick(t + 20, all(a));
            }
            use.forget(a.id);
        }
        for (String write : w.writes) {
            assertTrue(write.startsWith("open ") || write.startsWith("close "), write);
            assertTrue(write.endsWith("OAK_DOOR"), "nur die Tür: " + write);
        }
        for (Map.Entry<String, UseWorld.Block> e : before.entrySet()) {
            if (!e.getValue().type().equals("OAK_DOOR")) {
                assertEquals(e.getValue(), w.blocks.get(e.getKey()), "unverändert: " + e.getKey());
            }
        }
    }

    // ---------------------------------------------------------------- Aufräumen

    @Test
    void forgetClosesHisDoorsClearsTheSeatAndFreesTheBed() {
        FakeUseWorld w = new FakeUseWorld().door(5, 64, 0, "OAK_DOOR", false)
                .put(4, 64, 3, new UseWorld.Block("OAK_STAIRS", "EAST", "BOTTOM", false, false));
        TownUse use = new TownUse(YES);
        FakeActor a = new FakeActor(4.3, 64, 0.5);
        use.pass(a, w, 5.5, 0.5, 0);
        a.move(0.5, 64, 0.5);
        Location seat = use.target(a, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 1);
        a.move(seat.getX(), seat.getY(), seat.getZ());
        use.target(a, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 2);
        assertTrue(use.resting(a.id));
        use.forget(a.id);
        assertFalse(w.isOpen(5, 64, 0), "seine Tür zu");
        assertEquals(1, a.count("unseat"), "Sitz geräumt");
        assertEquals(TownUse.Mode.NONE, use.mode(a.id));
        FakeActor b = new FakeActor(0.5, 64, 0.5);
        assertNotSame(HOME, use.target(b, w, DayPlan.Goal.PLAZA, HOME, "Malerin", 3), "Bank wieder frei");
    }

    @Test
    void settingsFromConfig() {
        org.bukkit.configuration.file.YamlConfiguration c = new org.bukkit.configuration.file.YamlConfiguration();
        assertEquals(UseSettings.defaults(), UseSettings.from(c), "ohne Einträge: alles an");
        c.set(UseSettings.KEY_DOORS, false);
        assertFalse(UseSettings.from(c).doorsOn());
        assertTrue(UseSettings.from(c).bedsOn());
        c.set(UseSettings.KEY_ENABLED, false);
        assertFalse(UseSettings.from(c).bedsOn() || UseSettings.from(c).seatsOn() || UseSettings.from(c).workOn());
    }
}
