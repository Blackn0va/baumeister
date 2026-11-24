package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.blackn0va.baumeister.town.work.BuildSettings;

import org.junit.jupiter.api.Test;

/**
 * Bewohner-Bau im BuildService (Vertrag v013, AK-45), soweit ohne Server prüfbar: Wann ein Bewohner gar nicht erst
 * anfängt (fail-closed, Spieler gehen vor), und dass ConfigCheck dieselben Grenzen kennt wie BuildSettings.
 */
class TownBuildServiceTest {

    @Test
    void residentsOnlyBuildWhenNobodyElseNeedsTheBuilderAndSomeoneIsAccountable() {
        assertNull(BuildService.townRefusal(null, false, false, false, true, true, true));
        assertEquals("niemand_berechtigt", BuildService.townRefusal(null, false, false, false, true, false, true));
        assertEquals("bewohner_weg", BuildService.townRefusal(null, false, false, false, true, true, false));
        assertEquals("stadt_oder_weltbau", BuildService.townRefusal("Stadtbau", false, false, false, true, true,
                true));
        assertEquals("baumeister_beschäftigt", BuildService.townRefusal(null, true, false, false, true, true, true));
        assertEquals("baumeister_beschäftigt", BuildService.townRefusal(null, false, true, false, true, true, true));
        assertEquals("baumeister_beschäftigt", BuildService.townRefusal(null, false, false, true, true, true, true));
        assertEquals("warteschlange", BuildService.townRefusal(null, false, false, false, false, true, true));
    }

    private static BuildJob.UndoRecord record(java.util.UUID owner, String name, long when) {
        return new BuildJob.UndoRecord(owner, name, null, java.util.List.of(), java.time.Instant.ofEpochMilli(when),
                null);
    }

    /**
     * Bewohner-Bauten gehören der Stadt und landen auf deren Stapel – der Stapel des Admins, in dessen Namen geprüft
     * wurde, bleibt, wie er war (Dirigent T-037: live verdrängten Bewohner-Bauten vier Bauten des Admins).
     */
    @Test
    void residentBuildsNeverTouchTheAdminsStack() {
        java.util.UUID admin = java.util.UUID.randomUUID();
        BuildService.TownJob tj = BuildService.townJob(admin, "dorf");
        assertEquals(admin, tj.checkAs(), "Rechte nicht im Namen des Admins");
        assertEquals(admin, tj.rightsOf(), "Stufen nicht die des Admins");
        assertEquals(com.blackn0va.baumeister.town.work.TownBuilds.TOWN_OWNER, tj.owner());
        java.util.Deque<BuildJob.UndoRecord> players = new java.util.ArrayDeque<>();
        java.util.Deque<BuildJob.UndoRecord> town = new java.util.ArrayDeque<>();
        BuildJob.UndoRecord own = record(admin, "Admin", 1);
        players.push(own);
        BuildJob.UndoRecord built = record(tj.owner(), tj.ownerName(), 2);
        BuildService.stackFor(built, players, town).push(built);
        assertEquals(java.util.List.of(own), java.util.List.copyOf(players), "Stapel des Admins verändert");
        assertEquals(java.util.List.of(built), java.util.List.copyOf(town));
        // Nach einem Neustart (adoptUndo) gilt dieselbe Weiche.
        BuildJob.UndoRecord loaded = record(tj.owner(), tj.ownerName(), 0);
        BuildService.stackFor(loaded, players, town).addLast(loaded);
        assertEquals(1, players.size());
    }

    @Test
    void fieldUndoTakesTheNewestBuildOfThatFieldOnly() {
        java.util.UUID t = com.blackn0va.baumeister.town.work.TownBuilds.TOWN_OWNER;
        java.util.Deque<BuildJob.UndoRecord> town = new java.util.ArrayDeque<>();
        BuildJob.UndoRecord oldDorf = record(t, "Stadt-dorf", 1);
        BuildJob.UndoRecord acker = record(t, "Stadt-acker", 2);
        BuildJob.UndoRecord newDorf = record(t, "Stadt-dorf", 3);
        town.push(oldDorf);
        town.push(acker);
        town.push(newDorf);
        assertEquals(newDorf, BuildService.newestOfField(town, "dorf"));
        assertEquals(acker, BuildService.newestOfField(town, "acker"));
        assertNull(BuildService.newestOfField(town, "wiese"));
        // Der Name übersteht das Journal unverändert – sonst fände das Feld seinen Bau nach einem Neustart nicht.
        for (String f : java.util.List.of("dorf", "dorf_2", "alt-feld", "a1")) {
            String n = com.blackn0va.baumeister.town.work.TownBuilds.ownerName(f);
            assertEquals(n, UndoJournal.cleanName(n));
        }
    }

    @Test
    void residentBuildsAreNotReportedToTheApp() {
        com.blackn0va.baumeister.town.work.TownBuilds.Order o = new com.blackn0va.baumeister.town.work.TownBuilds.Order(
                "dorf", com.blackn0va.baumeister.town.work.TownBuilds.Kind.ZAUN, "w", 0, 64, 0,
                com.blackn0va.baumeister.plan.Orientation.Cardinal.NORTH, java.util.List.of());
        assertEquals(false, BuildService.reportsToApp(o.orderText()));
        assertEquals(true, BuildService.reportsToApp("ein kleines haus"));
        assertEquals(true, BuildService.reportsToApp(null));
    }

    @Test
    void playerOrdersEndAResidentBuildButNoOtherBuild() {
        assertEquals(true, BuildService.playerPreempts(true, true));
        assertEquals(false, BuildService.playerPreempts(true, false), "Spielerbau abgebrochen");
        assertEquals(false, BuildService.playerPreempts(false, true));
    }

    @Test
    void configCheckKnowsTheBuildLimits() {
        ConfigCheck.Range perField = ConfigCheck.RANGES.get(BuildSettings.KEY_PER_FIELD);
        ConfigCheck.Range pause = ConfigCheck.RANGES.get(BuildSettings.KEY_PAUSE);
        assertEquals(BuildSettings.MIN_PER_FIELD, (int) perField.min());
        assertEquals(BuildSettings.MAX_PER_FIELD, (int) perField.max());
        assertEquals(BuildSettings.MIN_PAUSE, (int) pause.min());
        assertEquals(BuildSettings.MAX_PAUSE, (int) pause.max());
    }

    /**
     * Abbruch- und Schlusssatz eines Bewohner-Baus zeigen auf den Stapel der Stadt (T-038): „/bm rückgängig“ des Admins
     * nähme seinen eigenen letzten Bau zurück, nicht den des Bewohners.
     */
    @Test
    void residentBuildsPointToTheFieldUndo() {
        assertEquals("/bm stadtleben feld rückgängig dorf",
                BuildJob.undoCommand(true, com.blackn0va.baumeister.town.work.TownBuilds.ownerName("dorf")));
        assertEquals("/bm stadtleben feld rückgängig <feld>", BuildJob.undoCommand(true, "Karl"));
        assertEquals("/bm rückgängig", BuildJob.undoCommand(false, "Karl"));
    }
}
