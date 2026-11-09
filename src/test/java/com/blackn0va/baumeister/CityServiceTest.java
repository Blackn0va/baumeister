package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Chat-Bitten „&lt;Ort&gt; nachbauen“ gehen ohne LLM an den Stadtbau, alles andere bleibt beim LLM. */
class CityServiceTest {

    @Test
    void recognisesTownRequestsWithAndWithoutRadius() {
        assertEquals(new CityService.CityRequest("Bad Kreuznach", null),
                CityService.parseCityRequest("bau Bad Kreuznach nach"));
        assertEquals(new CityService.CityRequest("Hargesheim", 300),
                CityService.parseCityRequest("bau mir das Dorf Hargesheim mit 300 m nach!"));
        assertEquals(new CityService.CityRequest("Mainz", null), CityService.parseCityRequest("Mainz 1:1 nachbauen"));
        assertEquals(new CityService.CityRequest("49.8451, 7.8676", 120),
                CityService.parseCityRequest("baue 49.8451, 7.8676 120 meter nach"));
    }

    @Test
    void ordinaryBuildOrdersStayWithTheLlm() {
        assertNull(CityService.parseCityRequest("bau mir ein kleines Haus aus Fichtenholz"));
        assertNull(CityService.parseCityRequest("bau mir Bad Kreuznach"));
        assertNull(CityService.parseCityRequest("bau eine Brücke nach Norden"));
    }

    /** Wie im Betrieb: getConfig() trägt die mitgelieferte config.yml als Vorgaben. */
    private static List<String> overpass(String file) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(file);
        c.setDefaults(ConfigMigrationTest.bundled());
        return CityService.overpassUrls(c);
    }

    @Test
    void ownSingleOverpassInstanceWinsOverTheBundledList() throws Exception {
        // Vorher lieferten die JAR-Vorgaben die Liste, und die eigene Einzeladresse wirkte nie (Prüfbefund 2026-10-29).
        String own = "https://overpass.example.org/api/interpreter";
        assertEquals(List.of(own), overpass("stadt:\n  overpass-url: " + own + "\n"));
    }

    @Test
    void ownListOldDefaultAndEmptyFileFollowTheUsualOrder() throws Exception {
        List<String> bundled = ConfigMigrationTest.bundled().getStringList("stadt.overpass-urls");
        assertEquals(List.of("https://a.example.org/api/interpreter", "https://b.example.org/api/interpreter"),
                overpass("stadt:\n  overpass-urls:\n    - https://a.example.org/api/interpreter\n"
                        + "    - https://b.example.org/api/interpreter\n"
                        + "  overpass-url: https://c.example.org/api/interpreter\n"));
        assertEquals(bundled, overpass("stadt:\n  overpass-url: " + CityService.OVERPASS_DEFAULT.getFirst() + "\n"));
        assertEquals(bundled, overpass(""));
        // Ausdrücklich leere Liste: wie bisher die Einzeladresse, ohne sie die Vorgabe.
        assertEquals(List.of("https://c.example.org/api/interpreter"),
                overpass("stadt:\n  overpass-urls: []\n  overpass-url: https://c.example.org/api/interpreter\n"));
        assertEquals(bundled, overpass("stadt:\n  overpass-urls: []\n"));
    }
}
