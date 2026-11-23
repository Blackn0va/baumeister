package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.WorkSpots;
import com.blackn0va.baumeister.town.work.BuildSettings;
import com.blackn0va.baumeister.town.work.Field;
import com.blackn0va.baumeister.town.work.TownBuilds;
import com.blackn0va.baumeister.town.work.WorkSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

/**
 * Bauen im Baufeld über den echten Takt und den echten Befehl (Vertrag v013, AK-45). Der Baudienst ist ein Fake mit
 * dem Vertrag von BuildService.startTown: Er nimmt den Auftrag, baut später (complete) oder sagt ab (deny, wie die
 * Prüfphase von BuildJob bei verweigertem Recht: nichts gesetzt, done(false)).
 *
 * Was hier NICHT geprüft ist, weil es einen Server braucht: dass BuildJob jeden Block im Namen von actor fragt und den
 * Bau dann ganz absagt, und dass /bm rückgängig die Blöcke zurücksetzt. Beides ist der bestehende Weg jedes Baus
 * (BuildJob.checkNext, BuildService.undo) und in der Live-Probe gemessen (T-037).
 */
class TownLifeBuildTest {

    /** Vertrag von TownLife.Env.townBuild, ohne Mannequin (im Test ein Proxy, den niemand braucht). */
    interface Builder {
        String start(TownBuilds.Order order, Player actor, String name, Consumer<Boolean> done);
    }

    /** Baudienst-Fake: merkt Aufträge, baut auf Zuruf in die Test-Welt. */
    static final class FakeBuilder implements Builder {
        final TownLifeFieldTest.Acre world;
        final TownBuilds catalog;
        final List<TownBuilds.Order> orders = new ArrayList<>();
        final List<Player> actors = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        Consumer<Boolean> pending;
        String refuse;

        FakeBuilder(TownLifeFieldTest.Acre world, TownBuilds catalog) {
            this.world = world;
            this.catalog = catalog;
        }

        @Override
        public String start(TownBuilds.Order order, Player actor, String name, Consumer<Boolean> done) {
            if (refuse != null) {
                return refuse;
            }
            assertNull(pending, "zweiter Bau gestartet, während einer läuft");
            orders.add(order);
            actors.add(actor);
            names.add(name);
            pending = done;
            return null;
        }

        /** Bau fertig: alle Zellen gesetzt. */
        void complete() {
            TownBuilds.Order o = orders.getLast();
            List<String> types = catalog.types(o.kind());
            for (int i = 0; i < o.cells().size(); i++) {
                int[] c = o.cells().get(i);
                world.types.put(TownLifeFieldTest.Acre.k(c[0], c[1], c[2]), types.get(i));
            }
            finish(true);
        }

        /** Abbruch mittendrin (/bm stop, Bewohner weg): die erste Hälfte steht, der Bau gilt als nicht fertig. */
        void abortHalfway() {
            TownBuilds.Order o = orders.getLast();
            List<String> types = catalog.types(o.kind());
            for (int i = 0; i < o.cells().size() / 2 + 1; i++) {
                int[] c = o.cells().get(i);
                world.types.put(TownLifeFieldTest.Acre.k(c[0], c[1], c[2]), types.get(i));
            }
            finish(false);
        }

        /** Wie BuildJob bei verweigertem Recht: nichts gesetzt. */
        void deny() {
            finish(false);
        }

        /** Wie /bm rückgängig: der letzte Bau weg, Boden und Luft wie vorher. */
        void undoLast() {
            for (int[] c : orders.getLast().cells()) {
                world.types.remove(TownLifeFieldTest.Acre.k(c[0], c[1], c[2]));
            }
        }

        private void finish(boolean ok) {
            Consumer<Boolean> d = pending;
            pending = null;
            d.accept(ok);
        }
    }

    record Setup(TownLifeWalkTest.Town town, FakeBuilder builder, TownLifeFieldTest.Acre acre) {
    }

    private static String[] cmd(String... rest) {
        String[] out = new String[rest.length + 1];
        out[0] = "stadtleben";
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    /** Feld „dorf“ (Radius 8 um 0/64/0) mit Karl (Bauer), Schalter nach Wunsch, Feld noch kein Baufeld. */
    private static Setup village(boolean adminOnline, boolean worldChange, boolean buildOn, int perField) {
        TownLifeFieldTest.Acre acre = new TownLifeFieldTest.Acre();
        TownLifeWalkTest.Town t = new TownLifeWalkTest.Town(1000, 0.5, 12.5, 1, adminOnline);
        t.env.plot = acre;
        t.env.hands = acre;
        t.env.catalog = id -> true;
        FakeBuilder b = new FakeBuilder(acre, new TownBuilds(id -> true));
        t.env.builder = b;
        t.life.configureWork(new WorkSettings(worldChange, 60));
        t.life.configureBuilding(new BuildSettings(buildOn, perField, 0));
        BaumeisterCommand.townLife(TownLifeFieldTest.sender(true, new ArrayList<>()), new String[] {"stadtleben",
            "feld", "setzen", "dorf", "8", "testwelt", "0", "64", "0"}, t.life);
        TownLife.Resident karl = t.add("Karl", 0.5, 0.5);
        karl.work = t.life.chooseWork(karl, karl.home);
        return new Setup(t, b, acre);
    }

    private static void release(Setup s) {
        List<String> said = new ArrayList<>();
        BaumeisterCommand.townLife(TownLifeFieldTest.sender(true, said), cmd("feld", "bauen", "dorf", "an"),
                s.town().life);
        assertTrue(s.town().life.fields().get("dorf").build(), said.toString());
    }

    @Test
    void buildsOnlyInAFieldReleasedForBuilding() {
        Setup s = village(true, true, true, 4);
        s.town().run(400);
        assertTrue(s.builder().orders.isEmpty(), "gebaut ohne Baufreigabe: " + s.builder().orders);

        // Freigeben dürfen nur Admins.
        List<String> said = new ArrayList<>();
        BaumeisterCommand.townLife(TownLifeFieldTest.sender(false, said), cmd("feld", "bauen", "dorf", "an"),
                s.town().life);
        assertTrue(said.getFirst().contains("baumeister.admin"), said.toString());
        assertTrue(!s.town().life.fields().get("dorf").build(), "Freigabe ohne Admin");

        release(s);
        s.town().run(400);
        assertEquals(1, s.builder().orders.size(), "kein Bau im Baufeld");
        TownBuilds.Order o = s.builder().orders.getFirst();
        Field f = s.town().life.fields().get("dorf");
        for (int[] c : o.cells()) {
            assertTrue(f.contains("testwelt", c[0], c[1], c[2]), "Zelle außerhalb des Feldes: " + c[0] + "," + c[2]);
        }
        assertEquals("Karl", s.builder().names.getFirst(), "nicht der Bewohner baut");
        assertTrue(s.builder().actors.getFirst().hasPermission("baumeister.admin"), "nicht im Namen eines Admins");
    }

    @Test
    void withoutAuthorisedPlayerOnlineNothingIsStarted() {
        Setup s = village(false, true, true, 4);
        release(s);
        s.town().run(800);
        assertTrue(s.builder().orders.isEmpty(), "Bau ohne berechtigten Spieler: " + s.builder().orders);
    }

    @Test
    void deniedBuildLeavesNothingIsNotCountedAndNotRetriedAtOnce() {
        Setup s = village(true, true, true, 4);
        release(s);
        s.town().run(400);
        assertEquals(1, s.builder().orders.size());
        s.builder().deny();
        s.town().run(1200);
        assertEquals(1, s.builder().orders.size(), "nach Ablehnung sofort neu versucht");
        assertTrue(s.town().life.fields().built("dorf").isEmpty(), "verweigerter Bau gemerkt");
        assertTrue(s.town().life.fieldBuildSpots().isEmpty(), "verweigerter Bau als Arbeitsort");
        assertEquals(1, s.town().life.buildCounts()[2]);
    }

    /** Gegenprobe T-037: Vorher fiel „nur fertige Bauten merken“ heraus, ohne dass ein Test rot wurde. */
    @Test
    void abortedBuildIsNotCountedNorAWorkSpot() {
        Setup s = village(true, true, true, 4);
        release(s);
        s.town().run(400);
        s.builder().abortHalfway();
        assertTrue(s.town().life.fields().built("dorf").isEmpty(), "halber Bau gezählt");
        assertTrue(s.town().life.fieldBuildSpots().isEmpty(), "halber Bau als Arbeitsort");
        assertEquals(1, s.town().life.buildCounts()[2]);
    }

    @Test
    void finishedBuildIsRememberedAsWorkSpotAndUndoFreesIt() {
        Setup s = village(true, true, true, 4);
        release(s);
        s.town().run(400);
        s.builder().complete();
        List<TownBuilds.Built> built = s.town().life.fields().built("dorf");
        assertEquals(1, built.size(), "fertiger Bau nicht gemerkt");
        List<WorkSpots.Spot> spots = s.town().life.fieldBuildSpots();
        assertEquals(1, spots.size());
        assertEquals(WorkSpots.Source.BUILD, spots.getFirst().source());
        assertTrue(spots.getFirst().name().startsWith(built.getFirst().kind().german()), spots.toString());

        // Rückgängig: Der Bau ist weg → zählt nicht mehr, ist kein Arbeitsort, der Platz ist wieder frei.
        s.builder().undoLast();
        s.town().run(400);
        assertTrue(s.town().life.fields().built("dorf").isEmpty(), "zurückgebauter Bau zählt noch");
        assertTrue(s.town().life.fieldBuildSpots().isEmpty(), "zurückgebauter Bau noch Arbeitsort");
        // … und nicht gleich wieder gebaut (live T-037: neue Hütte in derselben Sekunde daneben).
        s.town().run(1200);
        assertEquals(1, s.builder().orders.size(), "nach Rückgängig sofort neu gebaut");
    }

    /**
     * /bm stadtleben feld rückgängig <name>: nur Admins, geht mit dem Feldnamen an den Bewohner-Stapel (nie /bm
     * rückgängig des Admins), danach ruht das Feld.
     */
    @Test
    void fieldUndoIsForAdminsTakesTheFieldsBuildAndTheFieldRests() {
        Setup s = village(true, true, true, 4);
        release(s);
        s.town().run(400);
        s.builder().complete();
        List<String> asked = new ArrayList<>();
        // Wie BuildService.undoTown: Das Zurücksetzen läuft danach im RestoreJob – die Blöcke stehen im Moment der
        // Antwort noch. Die Ruhe muss also vom Befehl kommen, nicht erst von der Abriss-Erkennung.
        s.town().env.townUndo = (who, field) -> {
            asked.add(field);
            return java.util.Map.entry(true, "zurückgebaut");
        };
        List<String> said = new ArrayList<>();
        BaumeisterCommand.townLife(TownLifeFieldTest.sender(false, said), cmd("feld", "rückgängig", "dorf"),
                s.town().life);
        assertTrue(asked.isEmpty(), "Feld-Rückgängig ohne Admin");
        assertTrue(said.getFirst().contains("baumeister.admin"), said.toString());

        said.clear();
        BaumeisterCommand.townLife(TownLifeFieldTest.sender(true, said), cmd("feld", "rückgängig", "DORF"),
                s.town().life);
        assertEquals(List.of("dorf"), asked);
        assertTrue(said.stream().anyMatch(l -> l.contains("zurückgebaut")), said.toString());
        BaumeisterCommand.townLife(TownLifeFieldTest.sender(true, said), cmd("feld", "rückgängig", "gibtsnicht"),
                s.town().life);
        assertEquals(1, asked.size(), "unbekanntes Feld an den Stapel gegeben");
        s.town().run(1200);
        assertEquals(1, s.builder().orders.size(), "nach Feld-Rückgängig sofort neu gebaut");
        s.builder().undoLast();
        s.town().run(400);
        assertTrue(s.town().life.fields().built("dorf").isEmpty(), "zurückgebauter Bau zählt noch");
    }

    @Test
    void oneRunningBuildPerFieldAndTheCapHolds() {
        Setup s = village(true, true, true, 2);
        release(s);
        s.town().run(1200);
        assertEquals(1, s.builder().orders.size(), "zweiter Bau, während einer läuft");
        s.builder().complete();
        s.town().run(400);
        assertEquals(2, s.builder().orders.size(), "nach dem ersten kein zweiter (Pause 0)");
        s.builder().complete();
        s.town().run(2000);
        assertEquals(2, s.builder().orders.size(), "Obergrenze 2 überschritten");
        assertEquals(2, s.town().life.fields().built("dorf").size());
    }

    @Test
    void switchesOffMeanNoBuild() {
        Setup off = village(true, true, false, 4);
        release(off);
        off.town().run(800);
        assertTrue(off.builder().orders.isEmpty(), "gebaut mit stadtleben.bauen.an aus");

        Setup noChange = village(true, false, true, 4);
        release(noChange);
        noChange.town().run(800);
        assertTrue(noChange.builder().orders.isEmpty(), "gebaut mit stadtleben.weltänderung aus");
    }

    @Test
    void refusedByTheBuilderNothingIsRunningAndItAsksAgainLater() {
        Setup s = village(true, true, true, 4);
        release(s);
        s.builder().refuse = "baumeister_beschäftigt";
        s.town().run(400);
        assertEquals(0, s.builder().orders.size());
        assertTrue(s.town().life.buildCounts()[3] >= 1, "Ablehnung nicht gezählt");
        TownLife.Resident karl = s.town().life.residentsForTest().getFirst();
        assertTrue(!karl.building, "Bewohner hängt nach Ablehnung im Bauen fest");
        assertNotNull(karl.field);
    }

    @Test
    void ownBuildsDoNotComeBackFromTheLedger() {
        BuildLedger.Entry town = new BuildLedger.Entry("a1b2c3d4e5", new java.util.UUID(0, 1), "Admin",
                TownBuilds.ORDER_PREFIX + " ein Zaun (Feld dorf)", new java.util.UUID(0, 2), 0L, 0L, 0, 64, 0, 4, 64,
                0, 5, "");
        BuildLedger.Entry player = new BuildLedger.Entry("f6e5d4c3b2", new java.util.UUID(0, 1), "Admin",
                "ein haus", new java.util.UUID(0, 2), 0L, 0L, 10, 64, 10, 16, 70, 16, 120, "");
        List<WorkSpots.Spot> spots = TownLife.builds(List.of(town, player), id -> "testwelt");
        assertEquals(1, spots.size(), spots.toString());
        assertTrue(spots.getFirst().name().startsWith("ein haus"));
    }
}
