package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lesen und Prüfen vor dem Neuladen: eine kaputte Datei darf nichts ändern. */
class ConfigReloadTest {

    @TempDir
    Path dir;

    @Test
    void brokenFileChangesNothing() throws Exception {
        Path file = dir.resolve("config.yml");
        // Tab statt Leerzeichen – der häufigste Fehler beim Bearbeiten über nano auf dev01.
        Files.writeString(file, "bau:\n\tmax-blöcke: 20000\n", StandardCharsets.UTF_8);

        ConfigReload.Prepared p = ConfigReload.prepare(file, ConfigMigrationTest.bundled());

        assertNotNull(p.error(), "kaputtes YAML muss abgelehnt werden");
        assertTrue(p.error().startsWith("config.yml ist kein gültiges YAML"), p.error());
        assertNull(p.settings());
    }

    @Test
    void invalidSettingChangesNothing() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "llm:\n  url: ftp://127.0.0.1/\n", StandardCharsets.UTF_8);

        ConfigReload.Prepared p = ConfigReload.prepare(file, ConfigMigrationTest.bundled());

        assertNotNull(p.error());
        assertTrue(p.error().contains("llm.url"), p.error());
    }

    @Test
    void goodFileComesWithItsWarnings() throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "bau:\n  max-blöcke: 20000\n  rechte-prüfen: nein\n", StandardCharsets.UTF_8);

        ConfigReload.Prepared p = ConfigReload.prepare(file, ConfigMigrationTest.bundled());

        assertNull(p.error());
        assertEquals(20_000, p.settings().maxBlocks());
        assertTrue(p.settings().checkPermissions(), "„nein“ ist kein false – fail-closed bleibt");
        assertEquals(1, p.warnings().size(), p.warnings().toString());
    }
}
