package com.blackn0va.baumeister.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Modulverzeichnis (Vertrag v012, AK-37): Liste, Grenzen, Schlüssel aus config.yml, Zustand und Grund. */
class ModuleDirectoryTest {

    /** Alle Schalter laut Vorlage, alles da, KI erreichbar. */
    static ModuleDirectory.Facts facts(Map<String, Boolean> override, Boolean llmUp, boolean townAnswered,
            boolean townUp) {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        Map<String, Boolean> config = new HashMap<>();
        for (ModuleDef d : ModuleDirectory.ALL) {
            if (d.key() != null) {
                config.put(d.key(), c.getBoolean(d.key()));
            }
        }
        Map<String, Boolean> start = new HashMap<>(config);
        config.putAll(override);
        return new ModuleDirectory.Facts(config, start, llmUp, townAnswered, townUp, true,
                Set.of("worldedit", "voicechat", "coreprotect"), true, null, true, true, false, false, 0, false);
    }

    static ModuleEntry find(List<ModuleEntry> all, String id) {
        return all.stream().filter(m -> m.def().id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyModuleHasValidUniqueIdAndFitsTheContractLimits() {
        Set<String> ids = new HashSet<>();
        for (ModuleDef d : ModuleDirectory.ALL) {
            assertTrue(ModuleJson.ID.matcher(d.id()).matches(), d.id());
            assertTrue(ids.add(d.id()), "doppelt: " + d.id());
            assertTrue(d.name().length() <= ModuleJson.MAX_NAME, d.id() + " name " + d.name().length());
            assertTrue(d.description().length() <= ModuleJson.MAX_DESCRIPTION,
                    d.id() + " description " + d.description().length());
            for (String n : d.needs()) {
                assertTrue(ModuleJson.ID.matcher(n).matches(), d.id() + " needs " + n);
            }
        }
        // Die im Vertrag genannten Funktionen sind alle da.
        assertTrue(ids.containsAll(List.of("bau-ki", "begleiter", "stadtleben", "stadt", "erde", "weltbau", "karte",
                "rucksack", "sprache", "voicechat", "mods-download", "fernverbindung", "abenteuer")), ids.toString());
    }

    /** Vertrag v012 non_goals: bestehende Schlüssel bleiben die Quelle – jeder Modulschalter steht in config.yml. */
    @Test
    void everySwitchKeyIsAnExistingBooleanInConfigYml() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (ModuleDef d : ModuleDirectory.ALL) {
            if (d.key() != null) {
                assertTrue(c.isBoolean(d.key()), d.key() + " fehlt oder ist kein Wahrheitswert");
            }
        }
    }

    @Test
    void reasonsFitTheContractLimit() {
        ModuleDirectory.Facts down = new ModuleDirectory.Facts(Map.of("stadtleben.an", true, "sprache.an", true,
                "mods.download.an", true, "gui.stadt-anzeige", true, "karte.an", false), Map.of("karte.an", true),
                false, true, false, false, Set.of(), false, "sprache.piper zeigt auf kein ausführbares Piper",
                false, false, true, true, 3, true);
        for (ModuleEntry m : ModuleDirectory.evaluate(down)) {
            assertTrue(m.reason().length() <= ModuleJson.MAX_REASON, m.def().id() + ": " + m.reason());
        }
    }

    @Test
    void townLifeWaitsForKiAndRunsWithIt() {
        Map<String, Boolean> on = Map.of("stadtleben.an", true);
        assertEquals(ModuleState.AUS, find(ModuleDirectory.evaluate(facts(Map.of(), true, true, true)),
                "stadtleben").state(), "Vorlage: stadtleben.an false");
        ModuleEntry unchecked = find(ModuleDirectory.evaluate(facts(on, true, false, false)), "stadtleben");
        assertEquals(ModuleState.WARTET, unchecked.state());
        ModuleEntry waiting = find(ModuleDirectory.evaluate(facts(on, true, true, false)), "stadtleben");
        assertEquals(ModuleState.WARTET, waiting.state());
        assertTrue(waiting.reason().contains("KI"), waiting.reason());
        assertEquals(ModuleState.AKTIV, find(ModuleDirectory.evaluate(facts(on, true, true, true)), "stadtleben")
                .state());
    }

    @Test
    void buildKiShowsErrorWithoutKiAndWaitsBeforeTheFirstCheck() {
        assertEquals(ModuleState.FEHLER, find(ModuleDirectory.evaluate(facts(Map.of(), false, true, true)), "bau-ki")
                .state());
        assertEquals(ModuleState.WARTET, find(ModuleDirectory.evaluate(facts(Map.of(), null, true, true)), "bau-ki")
                .state());
        ModuleEntry vera = find(ModuleDirectory.evaluate(facts(Map.of(), false, true, true)), "begleiter");
        assertEquals(ModuleState.WARTET, vera.state());
        assertEquals("wartet auf KI", vera.reason());
    }

    /** Karte geändert, aber final in Minimap: Zustand „wartet“ mit dem Neustart als Grund – nicht „aus“. */
    @Test
    void switchThatNeedsARestartIsShownAsWaiting() {
        List<ModuleEntry> all = ModuleDirectory.evaluate(facts(Map.of("karte.an", false), true, true, true));
        ModuleEntry map = find(all, "karte");
        assertFalse(map.on());
        assertEquals(ModuleState.WARTET, map.state());
        assertTrue(map.reason().contains("Neustart"), map.reason());
        // Stadtleben wirkt sofort: dort gibt es keinen Neustart-Zustand.
        ModuleEntry town = find(ModuleDirectory.evaluate(facts(Map.of("stadtleben.an", true), true, true, true)),
                "stadtleben");
        assertEquals(ModuleState.AKTIV, town.state());
    }

    @Test
    void missingWorldOrPluginIsNamedAsReason() {
        ModuleDirectory.Facts f = new ModuleDirectory.Facts(Map.of("begleiter.an", false), Map.of("begleiter.an",
                false), true, false, false, false, Set.of(), false, null, false, true, false, false, 0, false);
        List<ModuleEntry> all = ModuleDirectory.evaluate(f);
        assertEquals("Erdwelt fehlt – /bm gelaende einrichten, dann Neustart", find(all, "erde").reason());
        assertEquals("braucht die Erdwelt", find(all, "weltbau").reason());
        assertEquals("Plugin WorldEdit fehlt", find(all, "bauteile").reason());
        assertEquals("Plugin voicechat fehlt", find(all, "voicechat").reason());
        assertEquals(ModuleState.WARTET, find(all, "abenteuer").state(), "Vera aus → Abenteuer wartet");
        assertEquals(ModuleState.AUS, find(all, "begleiter").state());
    }

    /** stadtleben.ohne-ki: ohne KI „aktiv“ mit Grund (kein neuer state-Wert, Host/Mod prüfen streng). */
    @Test
    void townLifeWithoutKiIsActiveWithReason() {
        YamlConfiguration c = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        Map<String, Boolean> config = new HashMap<>(Map.of("stadtleben.an", true));
        for (boolean answered : new boolean[] {false, true}) {
            ModuleDirectory.Facts f = new ModuleDirectory.Facts(config, Map.of(), false, answered, false, true,
                    Set.of(), false, null, false, true, false, false, 0, true);
            ModuleEntry town = find(ModuleDirectory.evaluate(f), "stadtleben");
            assertEquals(ModuleState.AKTIV, town.state(), "beantwortet=" + answered);
            assertEquals("ohne KI, feste Sätze", town.reason());
        }
        ModuleDirectory.Facts up = new ModuleDirectory.Facts(config, Map.of(), true, true, true, true, Set.of(), false,
                null, false, true, false, false, 0, true);
        assertEquals("Bewohner kommen tagsüber", find(ModuleDirectory.evaluate(up), "stadtleben").reason());
        assertFalse(c.getBoolean("stadtleben.ohne-ki"), "Vorgabe bleibt false");
    }

    @Test
    void findIgnoresCaseAndUnknownIsEmpty() {
        assertEquals("stadtleben", ModuleDirectory.find(" StadtLeben ").orElseThrow().id());
        assertTrue(ModuleDirectory.find("gibtsnicht").isEmpty());
        assertTrue(ModuleDirectory.find(null).isEmpty());
    }
}
