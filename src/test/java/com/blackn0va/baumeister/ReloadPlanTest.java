package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** /bm neuladen: was sofort gilt, was wartet, was einen Neustart braucht – und dass laufende Bauten bleiben. */
class ReloadPlanTest {

    private static YamlConfiguration bundled;

    private static YamlConfiguration with(String... keyValues) throws Exception {
        if (bundled == null) {
            bundled = ConfigMigrationTest.bundled();
        }
        YamlConfiguration c = new YamlConfiguration();
        for (int i = 0; i < keyValues.length; i += 2) {
            c.set(keyValues[i], parse(keyValues[i + 1]));
        }
        c.setDefaults(bundled);
        return c;
    }

    private static Object parse(String v) {
        if (v.matches("-?\\d+")) {
            return Integer.parseInt(v);
        }
        if (v.equals("true") || v.equals("false")) {
            return Boolean.parseBoolean(v);
        }
        return v;
    }

    /** Ablauf wie in ConfigReload: aktive Config gegen frisch gelesene. */
    private static ReloadPlan.Outcome decide(YamlConfiguration active, YamlConfiguration fresh, boolean busy,
            boolean undo, boolean force, boolean city, boolean world) {
        Map<String, Object> before = ConfigReload.flat(active, bundled);
        Map<String, Object> after = ConfigReload.flat(fresh, bundled);
        return ReloadPlan.decide(new ReloadPlan.Input(Settings.from(active), Settings.from(fresh), before, after, busy,
                undo, force, city, world));
    }

    @Test
    void unchangedBuildSettingsKeepTheBuildServiceAndItsUndoStack() throws Exception {
        // Nur die Overpass-Liste ändert sich. Vorher legte neuladen trotzdem einen neuen BuildService an – drei
        // Bauten auf dem Rückgängig-Stapel waren danach weg.
        ReloadPlan.Outcome o = decide(with(), with("stadt.nominatim-url", "https://nominatim.example.org/search"),
                false, true, false, false, false);
        assertFalse(o.replaceBuildService(), o.lines().toString());
        assertTrue(o.reloadModules());
        assertTrue(o.lines().getFirst().startsWith("Kartendienste übernommen"), o.lines().toString());
    }

    @Test
    void runningBuildKeepsItsSettingsAndTheChangeWaits() throws Exception {
        ReloadPlan.Outcome o = decide(with(), with("bau.ticks-pro-block", "1"), true, false, false, false, false);
        assertFalse(o.replaceBuildService());
        assertFalse(o.reloadModules(), "Bauteile nicht unter einem laufenden Plan austauschen");
        assertTrue(o.lines().getFirst().contains("warten"), o.lines().toString());
        assertTrue(o.lines().getFirst().contains("bau.ticks-pro-block"), o.lines().toString());
    }

    @Test
    void fullUndoStackNeedsForceForNewBuildSettings() throws Exception {
        ReloadPlan.Outcome refused = decide(with(), with("llm.max-tokens", "4000"), false, true, false, false, false);
        assertFalse(refused.replaceBuildService());
        assertTrue(refused.lines().getFirst().contains("erzwingen"), refused.lines().toString());

        ReloadPlan.Outcome forced = decide(with(), with("llm.max-tokens", "4000"), false, true, true, false, false);
        assertTrue(forced.replaceBuildService());
        assertTrue(forced.lines().getFirst().contains("geleert"), forced.lines().toString());
    }

    @Test
    void idleWithoutUndoTakesNewBuildSettingsAtOnce() throws Exception {
        ReloadPlan.Outcome o = decide(with(), with("npc.name", "Polier"), false, false, false, false, false);
        assertTrue(o.replaceBuildService());
        // Auch ohne Rückgängig-Stapel fängt der neue BuildService mit leerem Gedächtnis an (Nachträge der letzten
        // Stunde). Vorher hieß die Antwort nur „übernommen.“ – das Gedächtnis war still weg (Prüfbefund 2026-10-29).
        assertEquals(List.of("Bau- und LLM-Einstellungen übernommen (npc.name) – Gedächtnis und Nachträge fangen "
                + "neu an."), o.lines());
    }

    @Test
    void undoDepthOfTheWorldBuildAppliesFromTheNextFinishedTile() throws Exception {
        // WorldBuildService.onJobFinished liest weltbau.rückgängig-tiefe nach jeder fertigen Kachel neu
        // (undo.depth(undoDepth())). Vorher stand der Schlüssel in RESTART_ONLY: neuladen und /bm leistung mahnten
        // einen Neustart an, der nichts änderte.
        ReloadPlan.Outcome o = decide(with(), with("weltbau.rückgängig-tiefe", "5"), false, false, false, false,
                true);
        assertEquals(Set.of(), o.restartKeys(), o.lines().toString());
        assertTrue(o.lines().stream().anyMatch(l -> l.startsWith("Weltbau: weltbau.rückgängig-tiefe")
                && l.contains("ab der nächsten fertigen Kachel")), o.lines().toString());
        assertEquals(Set.of(), ReloadPlan.restartPending(ConfigReload.flat(with(), bundled),
                ConfigReload.flat(with("weltbau.rückgängig-tiefe", "5"), bundled)));
    }

    @Test
    void ownOverpassUrlWithoutOwnListIsTakenOver() throws Exception {
        // Seit CityService.overpassUrls (2026-11-01) gilt die Einzeladresse, wenn die Datei keine Liste nennt. Bis
        // dahin las CityService die Liste samt JAR-Vorgaben, und neuladen meldete hier richtig „wirkt nicht“.
        ReloadPlan.Outcome o = decide(with(), with("stadt.overpass-url", "https://overpass.example.org/api/interpreter"),
                false, false, false, false, false);
        assertTrue(o.lines().stream().anyMatch(l -> l.startsWith("Kartendienste übernommen (stadt.overpass-url)")),
                o.lines().toString());
        assertFalse(o.lines().stream().anyMatch(l -> l.contains("wirkt nicht")), o.lines().toString());
    }

    @Test
    void ownOverpassUrlBesideOwnListIsNotReportedAsTakenOver() throws Exception {
        // Nennt die Datei eine eigene Liste, hat sie Vorrang – die geänderte Einzeladresse bewirkt nichts.
        YamlConfiguration active = with();
        active.set("stadt.overpass-urls", List.of("https://a.example.org/api/interpreter"));
        YamlConfiguration fresh = with("stadt.overpass-url", "https://overpass.example.org/api/interpreter");
        fresh.set("stadt.overpass-urls", List.of("https://a.example.org/api/interpreter"));
        ReloadPlan.Outcome o = decide(active, fresh, false, false, false, false, false);
        assertFalse(o.lines().stream().anyMatch(l -> l.startsWith("Kartendienste übernommen")), o.lines().toString());
        assertFalse(o.lines().stream().anyMatch(l -> l.startsWith("Stadtbau:")), "nicht nebenbei als Stadtbau-Wert "
                + "übernommen: " + o.lines());
        assertTrue(o.lines().stream().anyMatch(l -> l.contains("stadt.overpass-url") && l.contains("wirkt nicht")
                && l.contains("stadt.overpass-urls")), o.lines().toString());
    }

    @Test
    void ownOverpassUrlCountsWhenTheListIsExplicitlyEmpty() throws Exception {
        // Einziger Fall, in dem CityService die Einzeladresse nimmt: Die Datei setzt die Liste ausdrücklich leer.
        YamlConfiguration active = with();
        active.set("stadt.overpass-urls", List.of());
        YamlConfiguration fresh = with("stadt.overpass-url", "https://overpass.example.org/api/interpreter");
        fresh.set("stadt.overpass-urls", List.of());
        ReloadPlan.Outcome o = decide(active, fresh, false, false, false, false, false);
        assertTrue(o.lines().stream().anyMatch(l -> l.startsWith("Kartendienste übernommen (stadt.overpass-url)")),
                o.lines().toString());
    }

    @Test
    void deferredBuildSettingsAreStillPendingOnTheNextReload() throws Exception {
        // Erster Lauf während des Baus: reloadConfig hat die neuen Werte schon übernommen, der BuildService nicht.
        // Beim zweiten Lauf ist die Datei gleich der aktiven Config – entscheidend ist der Vergleich der Settings.
        YamlConfiguration fileNow = with("bau.ticks-pro-block", "1");
        ReloadPlan.Outcome o = ReloadPlan.decide(new ReloadPlan.Input(Settings.from(with()), Settings.from(fileNow),
                ConfigReload.flat(fileNow, bundled), ConfigReload.flat(fileNow, bundled), false, false, false, false,
                false));
        assertTrue(o.replaceBuildService(), o.lines().toString());
    }

    @Test
    void cityValuesApplyFromTheNextCityWhileOneRuns() throws Exception {
        ReloadPlan.Outcome o = decide(with(), with("stadt.blöcke-pro-tick", "500"), false, false, false, true, false);
        assertFalse(o.replaceBuildService());
        assertTrue(o.lines().getFirst().contains("ab dem nächsten Stadtbau"), o.lines().toString());
        assertTrue(o.lines().getFirst().contains("/bm tempo"), o.lines().toString());
    }

    @Test
    void earthAndWorldBuildTimerNeedARestart() throws Exception {
        ReloadPlan.Outcome o = decide(with(), with("erde.tiefe", "32", "weltbau.auto", "true",
                "weltbau.helfer", "4"), false, false, false, false, false);
        assertEquals(Set.of("erde.tiefe", "weltbau.auto"), o.restartKeys());
        assertTrue(o.lines().stream().anyMatch(l -> l.startsWith("Weltbau: weltbau.helfer – übernommen")),
                o.lines().toString());
        assertTrue(o.lines().stream().anyMatch(l -> l.contains("/bm weltbau auto an|aus")), o.lines().toString());
    }

    @Test
    void restartReminderEndsWhenTheValueIsBackAsAtStart() throws Exception {
        Map<String, Object> atStart = ConfigReload.flat(with(), bundled);
        assertEquals(Set.of("erde.tiefe"), ReloadPlan.restartPending(atStart,
                ConfigReload.flat(with("erde.tiefe", "32", "stadt.helfer", "2"), bundled)));
        // Zurückgestellt: kein Neustart mehr nötig, auch wenn ein Neuladen dazwischen „erde.tiefe“ gemeldet hatte.
        assertEquals(Set.of(), ReloadPlan.restartPending(atStart, ConfigReload.flat(with("erde.tiefe", "64"),
                bundled)));
    }

    @Test
    void npcUuidWrittenBySpawnIsNoChange() throws Exception {
        ReloadPlan.Outcome o = decide(with(), with("npc.uuid", "1b5c6c1e-0d0f-4c49-9a43-6a7e0f3b2a11"), false, false,
                false, false, false);
        assertEquals(List.of("Keine Änderung gegenüber den aktiven Einstellungen."), o.lines());
    }

    @Test
    void missingKeyEqualsItsDefault() throws Exception {
        // Fehlt ein Schlüssel in der Datei, gilt die Vorlage – das ist keine Änderung gegenüber einer Datei, die
        // denselben Wert ausdrücklich nennt.
        YamlConfiguration explicit = with("stadt.lag-ms", "150");
        assertEquals(Set.of(), ReloadPlan.changedKeys(ConfigReload.flat(with(), bundled),
                ConfigReload.flat(explicit, bundled)));
    }
}
