package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.VeraRights.Ability;
import com.blackn0va.baumeister.companion.VeraRights.Level;
import com.blackn0va.baumeister.companion.VeraRights.Who;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Rechte im Admin-Buch: Eintrag vor Recht vor Stufe; kaputte Werte fallen auf die Vorgabe, nie auf „alle“. */
class VeraRightsTest {

    private static final UUID STEVE = UUID.randomUUID();
    private static final Who PLAYER = new Who(false, false);
    private static final Who OP = new Who(true, false);
    private static final Who GRANTED = new Who(false, true);

    @Test
    void defaultsKeepTheOldRulesGivingOnlyForOpsUsingForAll() {
        VeraRights r = new VeraRights();
        assertFalse(r.allowed(Ability.GEBEN, STEVE, PLAYER));
        assertTrue(r.allowed(Ability.GEBEN, STEVE, OP));
        assertTrue(r.allowed(Ability.GEBEN, STEVE, GRANTED), "LuckPerms-Recht erlaubt");
        assertTrue(r.allowed(Ability.BENUTZEN, STEVE, PLAYER));
        assertTrue(r.allowed(Ability.FRECH, STEVE, PLAYER));
    }

    @Test
    void levelAusBlocksEvenOpsButNotAGrantedRight() {
        VeraRights r = new VeraRights();
        r.level(Ability.FRECH, Level.AUS);
        assertFalse(r.allowed(Ability.FRECH, STEVE, PLAYER));
        assertFalse(r.allowed(Ability.FRECH, STEVE, OP));
        assertTrue(r.allowed(Ability.FRECH, STEVE, GRANTED));
        r.level(Ability.GEBEN, Level.ALLE);
        assertTrue(r.allowed(Ability.GEBEN, STEVE, PLAYER));
    }

    @Test
    void playerEntryBeatsRightAndLevel() {
        VeraRights r = new VeraRights();
        r.player(STEVE, "Steve", Ability.GEBEN, false);
        assertFalse(r.allowed(Ability.GEBEN, STEVE, GRANTED), "Verbot im Buch schlägt LuckPerms");
        r.player(STEVE, "Steve", Ability.BEFEHLE, true);
        assertTrue(r.allowed(Ability.BEFEHLE, STEVE, PLAYER));
        r.player(STEVE, "Steve", Ability.GEBEN, null);
        assertNull(r.player(STEVE, Ability.GEBEN));
        assertEquals(Map.of("Steve", Map.of(Ability.BEFEHLE, true)), r.entries());
    }

    @Test
    void onlyVerasAbilitiesHaveALevel() {
        VeraRights r = new VeraRights();
        r.level(Ability.BAUEN, Level.ALLE);
        assertFalse(r.save().toString().contains("bauen=alle"), "Bauen hat keine Stufe, nur Einträge");
    }

    @Test
    void saveAndLoadRoundTrip() {
        VeraRights a = new VeraRights();
        a.level(Ability.SUCHEN, Level.ALLE);
        a.player(STEVE, "Steve", Ability.RUCKSACK, false);
        Map<String, Object> saved = a.save();
        VeraRights b = new VeraRights();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> players = (Map<String, Map<String, Object>>) saved.get("spieler");
        @SuppressWarnings("unchecked")
        Map<String, Object> levels = (Map<String, Object>) saved.get("stufen");
        assertEquals(0, b.load(levels, players));
        assertEquals(Level.ALLE, b.level(Ability.SUCHEN));
        assertEquals(Boolean.FALSE, b.player(STEVE, Ability.RUCKSACK));
    }

    @Test
    void brokenValuesFallBackToTheDefaultNeverToAll() {
        VeraRights r = new VeraRights();
        int bad = r.load(Map.of("geben", "jeder2", "unbekannt", "alle", "bauen", "alle"),
                Map.of("kein-uuid", Map.of("geben", true), STEVE.toString(), Map.of("befehle", "ja")));
        assertEquals(5, bad);
        assertEquals(Level.OPS, r.level(Ability.GEBEN));
        assertFalse(r.allowed(Ability.GEBEN, STEVE, PLAYER));
        assertFalse(r.allowed(Ability.BEFEHLE, STEVE, PLAYER), "„ja“ ist kein true – kein Eintrag");
    }

    @Test
    void parsesGermanWords() {
        assertEquals(Level.AUS, Level.parse("aus"));
        assertEquals(Level.OPS, Level.parse("Ops"));
        assertNull(Level.parse("vielleicht"));
        assertEquals(Ability.WARP_SETZEN, Ability.parse("warpsetzen"));
        assertNull(Ability.parse("op"));
        for (Ability a : Ability.values()) {
            assertTrue(a.label().length() + ": Alle".length() <= 19, "Buchzeile zu lang: " + a.label());
        }
    }
}
