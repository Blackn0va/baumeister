package com.blackn0va.baumeister.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.structure.MiniVorbilder;
import com.blackn0va.baumeister.structure.VorbildFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AK-16 „Vorbilder: Treppenreihen werden ein Dach, keine Linien“: Der Weg von DatasetGenerator --vorbilder
 * (VorbildImport.collect mit vorbilder.json) macht aus einem eigenen Häuschen mit Satteldach aus Treppen ein Lehrpaar
 * mit genau einem roof-Schritt. Die Datei ist nach Westen gedreht gespeichert, so kommt auch „vorne“ mit an.
 */
class DatasetVorbildDachTest {

    private static final PlanJudge JUDGE = new PlanJudge();

    @Test
    void stairRowsOfAModelBecomeARoofStep(@TempDir Path dir) throws Exception {
        VorbildFiles.writeLocal(dir.resolve("steinhaus.schem"), MiniVorbilder.cottage(), Cardinal.WEST);
        Files.writeString(dir.resolve("vorbilder.json"), "[{\"datei\":\"steinhaus.schem\",\"auftrag\":\"bau mir ein "
                + "kleines Steinhaus mit Satteldach\",\"vorne\":\"w\"}]", StandardCharsets.UTF_8);
        VorbildImport.Result r = VorbildImport.collect(dir, JUDGE, Set.of());
        assertEquals(1, r.samples().size(), () -> "verworfen: " + r.stats().rejected());
        String json = r.samples().getFirst().json();
        assertEquals(1, json.split("\"op\":\"roof\"", -1).length - 1, json);
        assertEquals(0, MiniVorbilder.stairsSteps(json), json);
        assertTrue(json.contains("\"gable_block\":\"spruce_planks\""), json);
        assertEquals(Optional.empty(), JUDGE.rejection(r.samples().getFirst().order(), json));
    }
}
