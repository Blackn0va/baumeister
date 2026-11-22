package com.blackn0va.baumeister.town.presence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.Roster;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Jeder Beruf hat seine Ausrüstung, und jedes Teil ist ein echter Gegenstand dieser Serverfassung. */
class OutfitTest {

    /**
     * Namen der Material-Konstanten, ohne die Klasse zu initialisieren: Material.matchMaterial braucht seit 1.21 die
     * Registry des Servers (ohne Server ExceptionInInitializerError, gemessen 2026-11-22).
     */
    private static Set<String> materials() throws ClassNotFoundException {
        Set<String> out = new HashSet<>();
        Class<?> m = Class.forName("org.bukkit.Material", false, OutfitTest.class.getClassLoader());
        for (Field f : m.getDeclaredFields()) {
            if (f.isEnumConstant() && !f.getName().startsWith("LEGACY_")) {
                out.add(f.getName());
            }
        }
        return out;
    }

    @Test
    void everyJobHasAnOutfitOfRealItems() throws Exception {
        Set<String> known = materials();
        for (Roster.Person p : Roster.all()) {
            assertTrue(Outfit.BY_JOB.containsKey(p.job()), "Zeile fehlt für " + p.job());
            Outfit.Pieces o = Outfit.of(p.job());
            for (String name : new String[] {o.helmet(), o.leggings(), o.boots(), o.offhand()}) {
                if (name != null) {
                    assertTrue(known.contains(name.toUpperCase(Locale.ROOT)), p.job() + ": " + name);
                }
            }
        }
        assertEquals(Roster.all().size(), Outfit.BY_JOB.size(), "keine Zeile für einen Beruf, den es nicht gibt");
    }

    @Test
    void leatherIsAShadeDarkerThanTheJacket() {
        assertEquals(0xBFBFBF, Outfit.darker(0xFFFFFF));
        assertEquals(0x000000, Outfit.darker(0x000000));
    }
}
