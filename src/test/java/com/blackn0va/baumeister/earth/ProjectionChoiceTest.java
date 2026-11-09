package com.blackn0va.baumeister.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Projektionswahl: einmal festgeschrieben, danach gilt fail-closed die gespeicherte. */
class ProjectionChoiceTest {

    @Test
    void firstStartWithTheWorldWritesTheChoice(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("erde/welt.json");
        ProjectionChoice.Result r = ProjectionChoice.resolve(file, null, true);
        assertEquals("airocean", r.name());
        assertInstanceOf(AiroceanProjection.class, r.projection());
        assertTrue(r.written());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"projektion\":\"airocean\""));
    }

    @Test
    void withoutTheWorldNothingIsWritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("welt.json");
        ProjectionChoice.Result r = ProjectionChoice.resolve(file, "Sinus6", false);
        assertEquals("sinus6", r.name());
        assertInstanceOf(Sinus6Projection.class, r.projection());
        assertFalse(r.written());
        assertFalse(Files.exists(file));
    }

    @Test
    void storedChoiceWinsOverALaterConfigChange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("welt.json");
        ProjectionChoice.resolve(file, "sinus6", true);
        ProjectionChoice.Result r = ProjectionChoice.resolve(file, "airocean", true);
        assertEquals("sinus6", r.name());
        assertInstanceOf(Sinus6Projection.class, r.projection());
        assertNotNull(r.warning());
        assertTrue(r.warning().contains("sinus6"), r.warning());
        assertFalse(r.written());
        assertNull(ProjectionChoice.resolve(file, "sinus6", true).warning());
    }

    @Test
    void brokenOrUnknownStoredChoiceKeepsTheEarthOff(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("welt.json");
        Files.writeString(file, "{\"projektion\":\"mercator\"}", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ProjectionChoice.resolve(file, "airocean", true));
        Files.writeString(file, "{kaputt", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> ProjectionChoice.resolve(file, "airocean", true));
        // Die kaputte Datei wird nicht überschrieben – sonst entstünde stillschweigend eine neue Wahl.
        assertEquals("{kaputt", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void unknownConfiguredProjectionIsRejected(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> ProjectionChoice.resolve(dir.resolve("w.json"), "mercator",
                true));
        assertFalse(Files.exists(dir.resolve("w.json")));
    }
}
