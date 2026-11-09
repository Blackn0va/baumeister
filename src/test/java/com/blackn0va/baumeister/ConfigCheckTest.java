package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Warnungen zu Werten, die Bukkit und Settings sonst stillschweigend anders nehmen, als sie in der Datei stehen. */
class ConfigCheckTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    private static List<String> check(String text) throws Exception {
        return ConfigCheck.check(yaml(text), ConfigMigrationTest.bundled());
    }

    @Test
    void shippedConfigHasNoFindings() throws Exception {
        YamlConfiguration bundled = ConfigMigrationTest.bundled();
        assertEquals(List.of(), ConfigCheck.check(bundled, bundled));
    }

    @Test
    void keyTypedWithoutUmlautsIsReportedWithTheRightName() throws Exception {
        List<String> w = check("bau:\n  max-bloecke: 5000\n"); // umlaut-ok: genau dieser Tippfehler ist der Fall
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.getFirst().contains("Unbekannter Schlüssel 'bau.max-bloecke'"), w.toString()); // umlaut-ok
        assertTrue(w.getFirst().contains("gemeint: 'bau.max-blöcke'"), w.toString());
    }

    @Test
    void unknownSectionIsReportedOnceNotPerChild() throws Exception {
        List<String> w = check("tempo:\n  a: 1\n  b: 2\n");
        assertEquals(1, w.size(), w.toString());
    }

    @Test
    void wordInsteadOfBooleanSaysWhatReallyApplies() throws Exception {
        // „nein“ ist für Bukkit kein false – getBoolean liefert die Vorgabe true. Gut, dass es fail-closed ist, aber
        // der Admin muss es erfahren.
        List<String> w = check("bau:\n  rechte-prüfen: nein\n");
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.getFirst().contains("es gilt true"), w.toString());
    }

    @Test
    void numberOutsideTheClampSaysTheClampedValue() throws Exception {
        List<String> w = check("stadt:\n  ms-pro-tick: 100\n");
        assertEquals(List.of("'stadt.ms-pro-tick' = 100 liegt außerhalb 5–45 – es gilt 45."), w);
    }

    @Test
    void textWhereANumberBelongs() throws Exception {
        List<String> w = check("bau:\n  ticks-pro-block: schnell\n");
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.getFirst().contains("es gilt 3"), w.toString());
    }

    @Test
    void fractionWhereAWholeNumberBelongsIsCutOff() throws Exception {
        List<String> w = check("weltbau:\n  helfer: 2.7\n");
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.getFirst().contains("es gilt 2"), w.toString());
    }

    @Test
    void unknownProjectionIsNamed() throws Exception {
        List<String> w = check("erde:\n  projektion: mercator\n");
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.getFirst().contains("airocean, sinus6"), w.toString());
    }

    @Test
    void valueWhereASectionBelongs() throws Exception {
        List<String> w = check("weltbau: aus\n");
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.getFirst().contains("Abschnitt"), w.toString());
    }

    /**
     * Die Grenzen in RANGES spiegeln Settings.from. Wer dort eine Grenze verschiebt und hier nicht, bekommt diesen
     * Test rot statt falscher Warnungen beim Admin.
     */
    @Test
    void rangesMatchTheClampsInSettings() throws Exception {
        Map<String, Function<Settings, Number>> fields = Map.ofEntries(
                Map.entry("llm.timeout-sekunden", s -> s.llmTimeout().toSeconds()),
                Map.entry("llm.max-tokens", Settings::maxTokens),
                Map.entry("llm.temperatur", Settings::temperature),
                Map.entry("llm.reparatur-versuche", Settings::repairAttempts),
                Map.entry("bau.ticks-pro-block", Settings::ticksPerBlock),
                Map.entry("bau.max-blöcke", Settings::maxBlocks),
                Map.entry("bau.max-ausdehnung", Settings::maxExtent),
                Map.entry("bau.abstand-vor-spieler", Settings::distanceAhead),
                Map.entry("bau.undo-tiefe", Settings::undoDepth),
                Map.entry("bau.max-überbauen", Settings::maxOverbuild),
                Map.entry("bau.zieldauer-sekunden", Settings::targetSeconds));
        for (Map.Entry<String, Function<Settings, Number>> e : fields.entrySet()) {
            ConfigCheck.Range r = ConfigCheck.RANGES.get(e.getKey());
            boolean whole = !e.getKey().equals("llm.temperatur");
            double below = whole ? r.min() - 1 : r.min() - 0.5;
            double above = whole ? r.max() + 1 : r.max() + 0.5;
            YamlConfiguration low = new YamlConfiguration();
            low.set(e.getKey(), whole ? (Object) (long) below : below);
            YamlConfiguration high = new YamlConfiguration();
            high.set(e.getKey(), whole ? (Object) (long) above : above);
            assertEquals(r.min(), e.getValue().apply(Settings.from(low)).doubleValue(), 1e-9, e.getKey() + " unten");
            assertEquals(r.max(), e.getValue().apply(Settings.from(high)).doubleValue(), 1e-9, e.getKey() + " oben");
        }
    }

    /**
     * Die Migration trägt fehlende Schlüssel mit dem Wert der Vorlage ein. Das ändert nur dann nichts am Verhalten,
     * wenn die Vorlage dieselben Werte hat wie die Vorgaben im Code – sonst bekäme dev01 nach dem Ergänzen andere
     * Bau-Einstellungen als vorher.
     */
    @Test
    void templateValuesEqualTheCodeDefaults() throws Exception {
        assertEquals(Settings.from(new YamlConfiguration()), Settings.from(ConfigMigrationTest.bundled()));
    }

    @Test
    void ownOverpassUrlWithoutListIsReportedAsOutdated() throws Exception {
        // Wie im Betrieb: getConfig() trägt die Vorlage als Defaults. getStringList("stadt.overpass-urls") liefert dann
        // die Vorgabe-Liste – darum las CityService die Einzeladresse bis 2026-11-01 nie. Jetzt nimmt
        // CityService.overpassUrls sie, die Prüfung meldet sie nur noch als veraltet, nicht mehr als ignoriert.
        String own = "https://overpass.example.org/api/interpreter";
        YamlConfiguration file = yaml("stadt:\n  overpass-url: " + own + "\n");
        file.setDefaults(ConfigMigrationTest.bundled());
        assertEquals(List.of(own), CityService.overpassUrls(file), "die eigene Instanz gilt");

        List<String> w = check("stadt:\n  overpass-url: " + own + "\n");
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.getFirst().contains("'stadt.overpass-url'") && w.getFirst().contains("veraltet")
                && !w.getFirst().contains("ignoriert") && w.getFirst().contains("stadt.overpass-urls"), w.toString());
    }

    /**
     * fernverbindung.apps (Plan A4): Die App-Namen vergibt der Admin, sie stehen nie in der Vorlage. Vorher wäre jeder
     * Eintrag ein „unbekannter Schlüssel“ gewesen; jetzt zählt die Form, und jede Meldung sagt, dass verworfen wird.
     */
    @Test
    void appEntriesAreCheckedByShapeNotByName() throws Exception {
        assertEquals(List.of(), check("fernverbindung:\n  apps:\n    gpu-pc:\n      spieler: [Blackkn0va]\n"
                + "    laptop:\n      spieler:\n        - Alex\n        - 12345\n"));
        List<String> w = check("fernverbindung:\n  apps:\n    GPU_PC:\n      spieler: [Blackkn0va]\n"
                + "    gpu-pc:\n      spieler: Blackkn0va\n      farbe: rot\n"
                + "    laptop:\n      spieler: ['a b', Alex]\n"
                + "    tablet: Alex\n");
        assertEquals(5, w.size(), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("'fernverbindung.apps.GPU_PC': App-Namen nur")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("'fernverbindung.apps.gpu-pc.spieler' erwartet eine Liste")),
                w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("Unbekannter Schlüssel 'fernverbindung.apps.gpu-pc.farbe'")),
                w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("ungültige Spielernamen [a b]")), w.toString());
        assertTrue(w.stream().anyMatch(s -> s.contains("'fernverbindung.apps.tablet' muss ein Abschnitt")),
                w.toString());
        List<String> scalar = check("fernverbindung:\n  apps: gpu-pc\n");
        assertEquals(1, scalar.size(), scalar.toString());
        assertTrue(scalar.getFirst().contains("jeder Satz aus einer App wird verworfen"), scalar.toString());
    }

    @Test
    void oldDefaultOverpassUrlNeedsNoWarning() throws Exception {
        // Die alte Vorgabe der Vorlage ist kein Wunsch des Admins – CityService nimmt dafür ohnehin die Liste.
        assertEquals(List.of(), check("stadt:\n  overpass-url: " + CityService.OVERPASS_DEFAULT.getFirst() + "\n"));
    }
}
