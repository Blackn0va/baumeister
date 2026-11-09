package com.blackn0va.baumeister.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.structure.VorbildFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vorbilder: eine Bauwerk-Datei samt Auftrag aus vorbilder.json wird zum Lehrpaar, wenn der vereinfachte Bau das
 * Original trifft und die strenge Prüfung besteht. Eingang ist das eigene Testhaus (Haus + Turm), nach Osten gedreht
 * gespeichert – so prüft der Test auch, dass „vorne“ ankommt.
 */
class VorbildImportTest {

    private static final PlanJudge JUDGE = new PlanJudge();

    @Test
    void ownBuildBecomesASample(@TempDir Path dir) throws Exception {
        VorbildFiles.writeHouse(dir.resolve("haus.schem"), Cardinal.EAST);
        Files.writeString(dir.resolve("vorbilder.json"), "[{\"datei\":\"haus.schem\",\"auftrag\":\"bau mir ein "
                + "Holzhaus mit einem Turm daneben\",\"vorne\":\"o\"}]", StandardCharsets.UTF_8);
        VorbildImport.Result r = VorbildImport.collect(dir, JUDGE, Set.of());
        assertEquals(1, r.samples().size(), () -> "verworfen: " + r.stats().rejected());
        String json = r.samples().getFirst().json();
        assertTrue(json.contains("\"op\":\"door\"") && json.contains("\"facing\":\"front\""), json);
        assertEquals(java.util.Optional.empty(), JUDGE.rejection(r.samples().getFirst().order(), json));
    }

    /** Gegenprobe zu „vorne“: Mit falscher Seite zeigt die Tür nicht nach vorne. */
    @Test
    void wrongFrontTurnsTheDoorAway(@TempDir Path dir) throws Exception {
        VorbildFiles.writeHouse(dir.resolve("haus.schem"), Cardinal.EAST);
        var local = com.blackn0va.baumeister.structure.StructureFile.toLocal(
                com.blackn0va.baumeister.structure.StructureFile.read(dir.resolve("haus.schem")), Cardinal.SOUTH);
        String json = VorbildImport.plan(local, "ein Holzhaus", "haus.schem").json();
        assertTrue(json.contains("\"op\":\"door\""), json);
        assertTrue(!json.contains("\"facing\":\"front\""), json);
    }

    @Test
    void entriesOutsideTheFolderOrWithoutOrderAreRejected(@TempDir Path dir) throws Exception {
        Path sub = Files.createDirectory(dir.resolve("vorbilder"));
        VorbildFiles.writeHouse(dir.resolve("draussen.schem"), Cardinal.SOUTH);
        VorbildFiles.writeHouse(sub.resolve("haus.schem"), Cardinal.SOUTH);
        Files.writeString(sub.resolve("vorbilder.json"), "[{\"datei\":\"../draussen.schem\",\"auftrag\":\"ein Haus\"},"
                + "{\"datei\":\"haus.schem\",\"auftrag\":\"\"}]", StandardCharsets.UTF_8);
        VorbildImport.Result r = VorbildImport.collect(sub, JUDGE, Set.of());
        assertEquals(0, r.samples().size());
        assertEquals(2, r.stats().rejected().values().stream().mapToInt(Integer::intValue).sum(),
                () -> r.stats().rejected().toString());
    }
}
