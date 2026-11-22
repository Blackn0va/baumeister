package com.blackn0va.baumeister.town;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Fester Satz der Bewohner (Vertrag v008, AK-23): jeder mit Name, Beruf, Charakter; Namen eindeutig. */
class RosterTest {

    @Test
    void enoughDistinctPeopleForTheMaximum() {
        Set<String> names = new HashSet<>();
        for (Roster.Person p : Roster.all()) {
            assertTrue(names.add(p.name().toLowerCase(Locale.ROOT)), "doppelt: " + p.name());
            assertFalse(p.job().isBlank());
            assertFalse(p.character().isBlank());
            assertTrue(p.name().matches("\\p{L}{2,16}"), p.name());
        }
        assertTrue(names.size() >= TownSettings.MAX_COUNT);
    }

    @Test
    void reservedAndPresentNamesAreSkipped() {
        List<Roster.Person> pick = Roster.pick(12, Set.of("Lotte"), Set.of("EMIL", "vera"), 0);
        assertEquals(10, pick.size());
        assertTrue(pick.stream().noneMatch(p -> p.name().equals("Lotte") || p.name().equals("Emil")));
    }

    @Test
    void pickStartsWhereTheCursorIs() {
        assertEquals(Roster.all().get(3), Roster.pick(1, Set.of(), Set.of(), 3).getFirst());
        assertEquals(Roster.all().getFirst(), Roster.pick(1, Set.of(), Set.of(), Roster.all().size()).getFirst());
    }
}
