package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.work.FieldWork;
import com.blackn0va.baumeister.town.work.Plot;
import com.blackn0va.baumeister.town.work.Task;
import com.blackn0va.baumeister.town.work.WorkSettings;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

/**
 * Arbeitsfelder über den echten Takt und den echten Befehl (Vertrag v013, AK-43): Befehl nur für Admins, Arbeit nur
 * mit Schalter, nur im Feld, nur im Namen eines berechtigten Spielers – sonst kein einziges Ereignis und kein Block.
 */
class TownLifeFieldTest {

    /** Ebene mit Ackerboden: reifer Weizen an den gesetzten Stellen, Hände zählen und ändern nur bei Erlaubnis. */
    static final class Acre implements Plot, FieldWork.Hands {
        final Map<String, String> types = new HashMap<>();
        final Map<String, Integer> ages = new HashMap<>();
        final List<String> events = new ArrayList<>();
        boolean deny;

        static String k(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        Acre wheat(int x, int z) {
            types.put(k(x, 63, z), "farmland");
            types.put(k(x, 64, z), "wheat");
            ages.put(k(x, 64, z), 7);
            return this;
        }

        @Override
        public String type(int x, int y, int z) {
            return types.getOrDefault(k(x, y, z), y <= 63 ? "grass_block" : "air");
        }

        @Override
        public int age(int x, int y, int z) {
            return "wheat".equals(type(x, y, z)) ? ages.getOrDefault(k(x, y, z), 0) : -1;
        }

        @Override
        public int maxAge(int x, int y, int z) {
            return "wheat".equals(type(x, y, z)) ? 7 : -1;
        }

        @Override
        public boolean persistent(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean breakBlock(Task.Change c) {
            events.add("abbau " + c.x() + "," + c.z());
            if (deny) {
                return false;
            }
            types.put(k(c.x(), c.y(), c.z()), "air");
            return true;
        }

        @Override
        public boolean place(Task.Change c) {
            events.add("setzen " + c.x() + "," + c.z());
            if (deny) {
                return false;
            }
            types.put(k(c.x(), c.y(), c.z()), c.type());
            ages.put(k(c.x(), c.y(), c.z()), 0);
            return true;
        }
    }

    static CommandSender sender(boolean admin, List<String> said) {
        return (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "hasPermission" -> admin || "baumeister.bauen".equals(String.valueOf(a[0]));
                    case "sendMessage" -> {
                        said.add(String.valueOf(a[0]));
                        yield null;
                    }
                    case "getName" -> admin ? "Konsole" : "Gast";
                    default -> TownLifeTest.none(m.getReturnType());
                });
    }

    private static String[] set(String name, int radius) {
        return new String[] {"stadtleben", "feld", "setzen", name, String.valueOf(radius), "testwelt", "0", "64", "0"};
    }

    /** Stadt mit Feld „acker“ (Radius 6 um 0/64/0) und Karl (Bauer) an der Feldmitte. */
    private static TownLifeWalkTest.Town farm(boolean adminOnline, boolean worldChange, Acre acre) {
        TownLifeWalkTest.Town t = new TownLifeWalkTest.Town(1000, 0.5, 9.5, 1, adminOnline);
        t.env.plot = acre;
        t.env.hands = acre;
        t.life.configureWork(new WorkSettings(worldChange, 60));
        BaumeisterCommand.townLife(sender(true, new ArrayList<>()), set("acker", 6), t.life);
        assertEquals(1, t.life.fields().all().size());
        TownLife.Resident karl = t.add("Karl", 0.5, 0.5);
        karl.work = t.life.chooseWork(karl, karl.home);
        return t;
    }

    @Test
    void fieldCommandNeedsAdmin() {
        TownLifeWalkTest.Town t = new TownLifeWalkTest.Town(1000, 0.5, 9.5, 1);
        List<String> said = new ArrayList<>();
        BaumeisterCommand.townLife(sender(false, said), set("acker", 6), t.life);
        assertTrue(t.life.fields().all().isEmpty(), "ohne Admin gesetzt");
        assertTrue(said.getFirst().contains("baumeister.admin"), said.toString());
        said.clear();
        BaumeisterCommand.townLife(sender(false, said), new String[] {"stadtleben", "feld", "liste"}, t.life);
        assertTrue(said.getFirst().contains("baumeister.admin"), "liste ohne Admin: " + said);

        said.clear();
        BaumeisterCommand.townLife(sender(true, said), set("acker", 6), t.life);
        assertEquals(1, t.life.fields().all().size(), said.toString());
        assertTrue(said.getFirst().contains("weltänderung ist aus"), "Hinweis auf den Schalter fehlt: " + said);
        said.clear();
        BaumeisterCommand.townLife(sender(true, said), set("Kaputt Name", 6), t.life);
        BaumeisterCommand.townLife(sender(true, said), set("weit", 99), t.life);
        assertEquals(1, t.life.fields().all().size(), "ungültiges Feld angelegt: " + said);
        said.clear();
        BaumeisterCommand.townLife(sender(true, said), new String[] {"stadtleben", "feld", "liste"}, t.life);
        assertTrue(said.stream().anyMatch(s -> s.contains("acker: testwelt 0 64 0, Radius 6")), said.toString());
        said.clear();
        BaumeisterCommand.townLife(sender(true, said), new String[] {"stadtleben", "feld", "entfernen", "acker"},
                t.life);
        assertTrue(t.life.fields().all().isEmpty(), said.toString());
    }

    @Test
    void farmerHarvestsAndReplantsInTheFieldOnlyWithAnAdminOnline() {
        Acre acre = new Acre().wheat(3, 0);
        TownLifeWalkTest.Town t = farm(true, true, acre);
        TownLife.Resident karl = t.life.residentsForTest().getFirst();
        assertNotNull(karl.field, "Karl arbeitet nicht im Feld");
        t.run(400);
        assertEquals(List.of("abbau 3,0", "setzen 3,0"), acre.events);
        assertEquals("wheat", acre.type(3, 64, 0), "nicht nachgepflanzt");
        assertEquals(0, acre.age(3, 64, 0), "als Saat");
        assertEquals(2, t.life.fieldCounts()[0]);
    }

    /** Ernte und Saat im selben Takt: Dazwischen könnte er gehen (Mittag, Zuruf), und die Stelle bliebe leer. */
    @Test
    void harvestAndReplantHappenInTheSameTick() {
        Acre acre = new Acre().wheat(3, 0);
        TownLifeWalkTest.Town t = farm(true, true, acre);
        for (int k = 0; k < 400 && acre.events.isEmpty(); k++) {
            t.run(1);
        }
        assertEquals(List.of("abbau 3,0", "setzen 3,0"), acre.events, "Saat nicht im selben Takt wie die Ernte");
    }

    @Test
    void withoutAuthorisedPlayerOnlineNothingIsTouched() {
        Acre acre = new Acre().wheat(3, 0);
        TownLifeWalkTest.Town t = farm(false, true, acre);
        assertNotNull(t.life.residentsForTest().getFirst().field);
        t.run(400);
        assertTrue(acre.events.isEmpty(), "Ereignis ohne berechtigten Spieler: " + acre.events);
        assertEquals("wheat", acre.type(3, 64, 0));
    }

    @Test
    void withTheSwitchOffResidentsWorkWithoutChangingTheWorld() {
        Acre acre = new Acre().wheat(3, 0);
        TownLifeWalkTest.Town t = farm(true, false, acre);
        assertNull(t.life.residentsForTest().getFirst().field, "Feldarbeit trotz weltänderung aus");
        t.run(400);
        assertTrue(acre.events.isEmpty(), acre.events.toString());
    }

    @Test
    void deniedEventChangesNothingAndIsNotRetriedAtOnce() {
        Acre acre = new Acre().wheat(3, 0);
        acre.deny = true;
        TownLifeWalkTest.Town t = farm(true, true, acre);
        t.run(600);
        assertEquals(List.of("abbau 3,0"), acre.events, "verbotene Stelle erneut versucht");
        assertEquals("wheat", acre.type(3, 64, 0));
        assertEquals(7, acre.age(3, 64, 0));
        assertEquals(1, t.life.fieldCounts()[1]);
        assertFalse(t.life.fieldCounts()[0] > 0);
    }

    @Test
    void cropsOutsideTheFieldStay() {
        Acre acre = new Acre().wheat(8, 0).wheat(0, -9);
        TownLifeWalkTest.Town t = farm(true, true, acre);
        t.run(400);
        assertTrue(acre.events.isEmpty(), acre.events.toString());
    }
}
