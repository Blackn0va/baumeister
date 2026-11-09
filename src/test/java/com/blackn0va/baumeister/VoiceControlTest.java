package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stimmwahl per Klick (2026-11-08): Zur Wahl stehen nur Modelle mit .onnx.json daneben, aus stimmen/ und dem Ordner
 * der aktuellen Stimme. Ein Name, der nicht in dieser Liste steht, wird nie zu einem Pfad.
 */
class VoiceControlTest {

    @TempDir
    Path dir;

    private void model(Path folder, String name, boolean json) throws Exception {
        Files.createDirectories(folder);
        Files.write(folder.resolve(name + ".onnx"), new byte[] {1});
        if (json) {
            Files.writeString(folder.resolve(name + ".onnx.json"), "{\"audio\":{\"sample_rate\":22050}}");
        }
    }

    @Test
    void listsOnlyCompleteModelsFromBothFolders() throws Exception {
        Path data = dir.resolve("Baumeister");
        model(data.resolve("stimmen"), "de_DE-thorsten-medium", true);
        model(data.resolve("stimmen"), "halb", false);
        Path opt = dir.resolve("opt");
        model(opt, "en_US-ryan-medium", true);
        model(opt, "de_DE-thorsten-medium", true);
        Map<String, Path> v = VoiceControl.voices(data, opt.resolve("en_US-ryan-medium.onnx").toString());
        assertEquals(List.of("de_DE-thorsten-medium", "en_US-ryan-medium"), List.copyOf(v.keySet()));
        assertTrue(v.get("de_DE-thorsten-medium").startsWith(data.resolve("stimmen").toAbsolutePath()),
                "stimmen/ zuerst");
    }

    @Test
    void unknownNamesAndTraversalAreNotFound() throws Exception {
        Path data = dir.resolve("B");
        model(data.resolve("stimmen"), "a", true);
        Map<String, Path> v = VoiceControl.voices(data, "");
        assertEquals(null, v.get("../stimmen/a"));
        assertEquals(null, v.get("/etc/passwd"));
        assertTrue(VoiceControl.voices(dir.resolve("leer"), "").isEmpty(), "ohne Ordner: keine Stimmen, kein Fehler");
    }

    @Test
    void nameOfStripsFolderAndEnding() {
        assertEquals("de_DE-thorsten-medium", VoiceControl.nameOf("/opt/piper/de_DE-thorsten-medium.onnx"));
        assertEquals("", VoiceControl.nameOf(""));
    }

    @Test
    void menuPagesAndSlices() {
        assertEquals(1, SchematicMenu.pages(0));
        assertEquals(1, SchematicMenu.pages(45));
        assertEquals(2, SchematicMenu.pages(46));
        List<Integer> all = java.util.stream.IntStream.range(0, 100).boxed().toList();
        assertEquals(45, SchematicMenu.slice(all, 0).size());
        assertEquals(10, SchematicMenu.slice(all, 2).size());
        assertEquals(SchematicMenu.slice(all, 2), SchematicMenu.slice(all, 99), "zu große Seite = letzte");
        assertEquals("clock", SchematicMenu.icon("Taktgeber"));
        assertEquals("iron_door", SchematicMenu.icon("Tür- und Toranlage"));
        assertEquals("bricks", SchematicMenu.icon("Irgendwas"));
        assertTrue(SchematicMenu.wrap("ein langer Text ".repeat(20), 36, 3).size() <= 3);
    }
}
