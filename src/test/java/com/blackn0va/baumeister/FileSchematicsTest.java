package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.structure.NbtAccess;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bauwerk-Dateien als Bauteile (2026-11-08): Namen aus index.tsv oder Dateiname, Lesen erst beim Bau, neue Dateien
 * ohne Neustart, kaputte Dateien und schlechte Namen fallen weg statt den Rest mitzureißen.
 */
class FileSchematicsTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000);

    private static Map<Pos, BlockSpec> clock() throws Exception {
        Map<Pos, BlockSpec> c = new LinkedHashMap<>();
        c.put(new Pos(0, 0, 0), BlockSpec.parse("stone"));
        c.put(new Pos(1, 0, 0), BlockSpec.parse("stone"));
        c.put(new Pos(0, 1, 0), BlockSpec.parse("redstone_wire"));
        c.put(new Pos(1, 1, 0), BlockSpec.parse("repeater[facing=west,delay=2]"));
        return c;
    }

    @Test
    void indexNamesTheFilesAndModulesLoadOnFirstUse() throws Exception {
        Path w = Files.createDirectories(dir.resolve("redstone"));
        Files.write(w.resolve("taktgeber_1.nbt"), NbtAccess.structureNbt(clock()));
        Files.writeString(w.resolve("index.tsv"), "# datei\tname\tanzeige\tbeschreibung\tquelle\n"
                + "taktgeber_1.nbt\ttaktgeber_1\tTaktgeber, Variante 1\tTaktgeber aus der Redstone-Welt\tTechnik Wiki\n",
                StandardCharsets.UTF_8);
        FileSchematics f = new FileSchematics(dir, now::get);
        assertEquals(1, f.catalog().size());
        ModuleLibrary.Module m = f.find("taktgeber_1").orElseThrow();
        assertEquals(4, m.cells().size());
        assertEquals(new Pos(2, 2, 1), m.size());
        assertEquals("Taktgeber aus der Redstone-Welt", m.description());
        assertTrue(f.all().isEmpty(), "nicht in jeden LLM-Prompt");
        assertEquals("taktgeber_1", f.catalog().match("bau mir einen Taktgeber").orElseThrow().entry().name());
    }

    @Test
    void withoutIndexTheFileNameIsTheName() throws Exception {
        Files.write(dir.resolve("Meine Burg (v2).nbt"), NbtAccess.structureNbt(clock()));
        FileSchematics f = new FileSchematics(dir, now::get);
        assertTrue(f.find("meine_burg_v2").isPresent(), f.catalog().families("").toString());
    }

    @Test
    void newFilesCountAfterThirtySecondsWithoutRestart() throws Exception {
        FileSchematics f = new FileSchematics(dir, now::get);
        assertEquals(0, f.catalog().size());
        Files.write(dir.resolve("neu.nbt"), NbtAccess.structureNbt(clock()));
        assertEquals(0, f.catalog().size(), "erst nach 30 s wieder in den Ordner sehen");
        now.addAndGet(30_000);
        assertEquals(1, f.catalog().size());
    }

    @Test
    void brokenFilesAndBadNamesDropOutAlone() throws Exception {
        Files.write(dir.resolve("gut.nbt"), NbtAccess.structureNbt(clock()));
        Files.write(dir.resolve("kaputt.nbt"), new byte[] {1, 2, 3});
        Files.writeString(dir.resolve("index.tsv"), "gut.nbt\tGUT-NAME\tX\n", StandardCharsets.UTF_8);
        Files.write(dir.resolve("anders.schem"), NbtAccess.structureNbt(clock()));
        FileSchematics f = new FileSchematics(dir, now::get);
        assertTrue(f.find("gut").isEmpty() && f.find("gut-name").isEmpty(), "ungültiger Name aus dem Index");
        assertTrue(f.find("kaputt").isEmpty(), "unlesbar → kein Bauteil, keine Ausnahme");
        assertEquals(List.of("anders", "kaputt"), f.catalog().families("").stream().map(Map.Entry::getKey)
                .map(String::toLowerCase).sorted().toList());
    }
}
