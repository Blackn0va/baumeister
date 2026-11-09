package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Config-Migration gegen echte frühere Fassungen der Vorlage (src/test/resources/konfig, aus der Git-Historie) – so
 * sieht die Config auf dev01 aus, die seit dem ersten Deploy nur von Hand ergänzt wurde.
 */
class ConfigMigrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 7, 30, 0);

    @TempDir
    Path dir;

    /**
     * Start fail-closed (2026-11-01): Kaputtes YAML muss erkannt werden, sonst lief das Plugin mit JAR-Vorgaben weiter
     * und das nächste saveConfig überschrieb die Datei des Admins.
     */
    @Test
    void brokenYamlIsReportedSoThePluginStaysOff() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "bau:\n  max-blöcke: 6000\n   ticks-pro-block: [3\n", StandardCharsets.UTF_8);
        assertNotNull(ConfigMigration.brokenReason(file));
        assertEquals("bau:\n  max-blöcke: 6000\n   ticks-pro-block: [3\n",
                Files.readString(file, StandardCharsets.UTF_8), "Datei bleibt unberührt");
    }

    @Test
    void validOrMissingYamlIsNotBroken() throws IOException {
        Path file = dir.resolve("config.yml");
        assertNull(ConfigMigration.brokenReason(file), "fehlt: legt saveDefaultConfig an");
        Files.writeString(file, "bau:\n  max-blöcke: 6000\n", StandardCharsets.UTF_8);
        assertNull(ConfigMigration.brokenReason(file));
    }

    static YamlConfiguration bundled() throws IOException, InvalidConfigurationException {
        try (InputStream in = ConfigMigrationTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml fehlt auf dem Klassenpfad");
            return ConfigMigration.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private Path copy(String resource, String... replacements) throws IOException {
        String text;
        try (InputStream in = getClass().getResourceAsStream("/konfig/" + resource)) {
            assertNotNull(in, resource);
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (int i = 0; i < replacements.length; i += 2) {
            assertTrue(text.contains(replacements[i]), replacements[i]);
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        Path file = dir.resolve("config.yml");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    /** Wie dev01: erster Stand, Grenzen und UUID von Hand. */
    private Path dev01Like() throws IOException {
        return copy("config-c2a5ae9.yml",
                "uuid: ''", "uuid: 1b5c6c1e-0d0f-4c49-9a43-6a7e0f3b2a11",
                "blöcke-pro-tick: 2000", "blöcke-pro-tick: 500",
                "max-blöcke: 150000", "max-blöcke: 150001");
    }

    @Test
    void missingKeysArriveAndEveryExistingValueStays() throws Exception {
        Path file = dev01Like();
        Map<String, Object> before = ConfigMigration.leaves(ConfigMigration.load(file));

        ConfigMigration.Result r = ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW);

        YamlConfiguration after = ConfigMigration.load(file);
        for (Map.Entry<String, Object> e : before.entrySet()) {
            assertEquals(e.getValue(), after.get(e.getKey()), e.getKey());
        }
        // Von Hand gesetzte Werte – nie auf die Vorlage zurück.
        assertEquals(500, after.getInt("stadt.blöcke-pro-tick"));
        assertEquals(150_001, after.getInt("bau.max-blöcke"));
        assertEquals("1b5c6c1e-0d0f-4c49-9a43-6a7e0f3b2a11", after.getString("npc.uuid"));
        // Alles aus der Vorlage ist jetzt da.
        for (String key : bundled().getKeys(true)) {
            assertTrue(after.contains(key, true), "fehlt nach der Migration: " + key);
        }
        assertTrue(r.added().contains("weltbau.auto"), r.added().toString());
        assertTrue(r.added().contains("stadt.lag-ms"), r.added().toString());
        assertFalse(r.added().contains("stadt.blöcke-pro-tick"));
    }

    @Test
    void addedKeysKeepTheirExplainingComment() throws Exception {
        Path file = dev01Like();
        ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        YamlConfiguration after = ConfigMigration.load(file);
        assertTrue(String.join("\n", after.getComments("stadt.lag-ms")).contains("überlastet"),
                after.getComments("stadt.lag-ms").toString());
        assertTrue(String.join("\n", after.getComments("weltbau")).contains("Weltbau"),
                after.getComments("weltbau").toString());
        // Kommentare, die schon in der Datei standen, bleiben auch stehen.
        assertTrue(text.contains("# Tick-Bremse: Neue Chunks zu erzeugen ist das Teure"), text);
        // Umlaute in Schlüsseln bleiben lesbar (kein \xf6-Escape von SnakeYAML).
        assertTrue(text.contains("max-blöcke-je-etappe:"), text);
        // Leerer Abschnitt (fernverbindung.apps: {}, Plan A4) kommt als Abschnitt an, samt Beispiel im Kommentar –
        // der Admin sieht so, wie er eine App an Spieler bindet, ohne dass sich etwas ändert (leer = ungebunden).
        assertTrue(after.isConfigurationSection("fernverbindung.apps"), text);
        assertTrue(String.join("\n", after.getComments("fernverbindung.apps")).contains("spieler: [Blackkn0va]"),
                after.getComments("fernverbindung.apps").toString());
        assertTrue(!VoiceAppBinding.from(after).bound(), "ergänzte Vorlage bindet die Sprache nicht");
    }

    @Test
    void backupHoldsTheOldFileBeforeAnythingIsWritten() throws Exception {
        Path file = dev01Like();
        String original = Files.readString(file, StandardCharsets.UTF_8);

        ConfigMigration.Result r = ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW);

        assertNotNull(r.backup());
        assertEquals("config-2026-10-29-073000.yml", r.backup().getFileName().toString());
        assertEquals(original, Files.readString(r.backup(), StandardCharsets.UTF_8));
        assertFalse(original.equals(Files.readString(file, StandardCharsets.UTF_8)));
    }

    @Test
    void completeConfigIsNeitherWrittenNorBackedUp() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, bundled().saveToString(), StandardCharsets.UTF_8);
        long stamp = Files.getLastModifiedTime(file).toMillis();

        ConfigMigration.Result r = ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW);

        assertTrue(r.added().isEmpty());
        assertNull(r.backup());
        assertFalse(Files.exists(dir.resolve("sicherung")));
        assertEquals(stamp, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void brokenYamlIsNeverOverwritten() throws Exception {
        Path file = dir.resolve("config.yml");
        String broken = "bau:\n  max-blöcke: 5000\n llm: [kaputt\n";
        Files.writeString(file, broken, StandardCharsets.UTF_8);

        assertThrows(InvalidConfigurationException.class,
                () -> ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW));
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void ownOverpassInstanceIsNotShadowedByTheNewList() throws Exception {
        Path file = copy("config-c2a5ae9.yml", "overpass-url: https://overpass-api.de/api/interpreter",
                "overpass-url: https://overpass.example.org/api/interpreter");

        ConfigMigration.Result r = ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW);

        YamlConfiguration after = ConfigMigration.load(file);
        // Die Liste in der Datei überstimmte die eigene Instanz auch dann, wenn CityService einmal nur die Datei liest.
        // Seit CityService.overpassUrls (2026-11-01) liest es nur die Datei – eine ergänzte Liste nähme ihr den Vorrang.
        assertFalse(after.contains("stadt.overpass-urls", true), r.added().toString());
        assertEquals("https://overpass.example.org/api/interpreter", after.getString("stadt.overpass-url"));
        assertTrue(r.skipped().stream().anyMatch(s -> s.startsWith("stadt.overpass-urls")), r.skipped().toString());
    }

    @Test
    void oldDefaultOverpassGetsTheListBecauseNothingChanges() throws Exception {
        Path file = copy("config-c2a5ae9.yml");

        ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW);

        // Mit der alten Vorgabe nahm CityService ohnehin OVERPASS_DEFAULT – die Liste in der Vorlage ist dieselbe.
        assertEquals(CityService.OVERPASS_DEFAULT, ConfigMigration.load(file).getStringList("stadt.overpass-urls"));
    }

    @Test
    void valueWhereASectionBelongsBlocksItsChildren() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "npc:\n  name: Baumeister\nweltbau: aus\n", StandardCharsets.UTF_8);

        ConfigMigration.Result r = ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW);

        YamlConfiguration after = ConfigMigration.load(file);
        assertEquals("aus", after.getString("weltbau"));
        assertEquals(1, r.skipped().stream().filter(s -> s.startsWith("weltbau ")).count(), r.skipped().toString());
        assertTrue(after.contains("erde.tiefe", true));
    }

    @Test
    void onlyTheLastTenBackupsStay() throws Exception {
        Path backups = dir.resolve("sicherung");
        for (int i = 0; i < 12; i++) {
            Path file = copy("config-4460e9e.yml");
            ConfigMigration.migrate(file, bundled(), backups, NOW.plusMinutes(i));
        }
        List<String> names;
        try (Stream<Path> s = Files.list(backups)) {
            names = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertEquals(ConfigMigration.KEEP_BACKUPS, names.size());
        assertEquals("config-2026-10-29-073200.yml", names.getFirst());
    }

    @Test
    void oldestTemplateKeepsItsSmallLimits() throws Exception {
        // Erster Stand: 6 000 Blöcke, 48 je Richtung. Die Vorlage hat heute 150 000/192 – übernommen wird das nicht.
        Path file = copy("config-4460e9e.yml");
        ConfigMigration.migrate(file, bundled(), dir.resolve("sicherung"), NOW);
        YamlConfiguration after = ConfigMigration.load(file);
        assertEquals(6000, after.getInt("bau.max-blöcke"));
        assertEquals(48, after.getInt("bau.max-ausdehnung"));
        assertTrue(after.contains("bau.zieldauer-sekunden", true));
        assertTrue(after.contains("stadt.overpass-urls", true));
    }
}
