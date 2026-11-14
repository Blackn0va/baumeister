package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sicherheitsprüfung 1, Befund 6 (2026-11-14): Rückgängig setzt nur zurück, wo noch der Bau steht. */
class UndoCheckTest {

    @Test
    void keepsWhatSomeoneElseBuiltAfterwards() {
        // Bau setzte Stein, ein Spieler hat danach Gold hingesetzt: bleibt.
        assertFalse(UndoCheck.restorable("minecraft:gold_block", "minecraft:stone"));
        // Bau entfernte einen Block (Luft), danach baute jemand dort: bleibt.
        assertFalse(UndoCheck.restorable("minecraft:oak_planks", "minecraft:air"));
        // Bau setzte Erde, jemand stellte eine Truhe drauf … an dieselbe Stelle: bleibt.
        assertFalse(UndoCheck.restorable("chest", "minecraft:dirt"));
    }

    @Test
    void restoresWhereTheBuildStillStandsOrNothingWasPutThere() {
        assertTrue(UndoCheck.restorable("minecraft:stone", "minecraft:stone"));
        assertTrue(UndoCheck.restorable("stone", "minecraft:stone"));
        // Abgebaut, ausgelaufen, abgebrannt: niemand verliert etwas, wenn der Ursprung zurückkommt.
        assertTrue(UndoCheck.restorable("air", "minecraft:oak_planks"));
        assertTrue(UndoCheck.restorable("water", "minecraft:air"));
        assertTrue(UndoCheck.restorable("fire", "minecraft:oak_planks"));
        assertTrue(UndoCheck.restorable("snow", "minecraft:air"));
    }

    @Test
    void changesMinecraftMakesByItselfCountAsTheBuild() {
        assertTrue(UndoCheck.restorable("dirt", "minecraft:grass_block"));
        assertTrue(UndoCheck.restorable("grass_block", "minecraft:dirt_path"));
        assertTrue(UndoCheck.restorable("bubble_column", "minecraft:water"));
        assertTrue(UndoCheck.restorable("oxidized_cut_copper", "minecraft:cut_copper"));
        assertTrue(UndoCheck.restorable("red_concrete", "minecraft:red_concrete_powder"));
        assertFalse(UndoCheck.restorable("blue_concrete", "minecraft:red_concrete_powder"));
    }

    @Test
    void withoutInformationItRestoresAsBefore() {
        // Einträge von vor Format 2: kein Wissen, was gesetzt wurde – altes Verhalten.
        assertTrue(UndoCheck.restorable("gold_block", null));
    }

    @Test
    void spoolKeepsThePlacedTypeAndStillReadsOldParts(@TempDir Path tmp) throws Exception {
        UndoSpool s = new UndoSpool(tmp.resolve("bau"));
        s.record(1, 64, 1, "minecraft:grass_block[snowy=false]", "minecraft:stone");
        s.record(2, 64, 1, "minecraft:air", "minecraft:oak_planks");
        s.record(3, 64, 1, "minecraft:air", null);
        s.record(4, 64, 1, "minecraft:stone", "minecraft:stone");
        s.finish();
        UndoSpool.Part p = UndoSpool.read(s.partsNewestFirst().getFirst());
        assertEquals(4, p.size());
        assertArrayEquals(new String[] {"minecraft:grass_block[snowy=false]", "minecraft:air", "minecraft:air",
            "minecraft:stone"}, java.util.Arrays.copyOf(p.state(), 4));
        assertEquals("minecraft:stone", p.placedAt(0));
        assertEquals("minecraft:oak_planks", p.placedAt(1));
        assertNull(p.placedAt(2));
        assertEquals("minecraft:stone", p.placedAt(3));

        // Format 1 (bis 2026-11-14 geschrieben, liegt nach dem Update noch auf der Platte): ohne Kopf, 4 Wörter je
        // Eintrag – muss weiter lesbar sein, ohne gesetzten Typ.
        Path old = tmp.resolve("teil-00001.bin.gz");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(old)))) {
            out.writeInt(Integer.MIN_VALUE);
            out.writeUTF("minecraft:stone");
            for (int i = 0; i < 3; i++) {
                out.writeInt(i);
                out.writeInt(70);
                out.writeInt(-i);
                out.writeInt(0);
            }
        }
        UndoSpool.Part v1 = UndoSpool.read(old);
        assertEquals(3, v1.size());
        assertEquals("minecraft:stone", v1.state()[2]);
        assertEquals(-2, v1.z()[2]);
        assertNull(v1.placed());
        assertNull(v1.placedAt(1));
    }

    @Test
    void placedTypeOutsideTheDictionaryIsAnIoError(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("teil-00001.bin.gz");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(f)))) {
            out.writeInt(Integer.MIN_VALUE + 2);  // Kopf Format 2
            out.writeInt(Integer.MIN_VALUE);
            out.writeUTF("minecraft:stone");
            out.writeInt(1);
            out.writeInt(2);
            out.writeInt(3);
            out.writeInt(0);
            out.writeInt(5);  // Typ 5 – nie angelegt
        }
        IOException e = assertThrows(IOException.class, () -> UndoSpool.read(f));
        assertTrue(e.getMessage().contains("kaputt"), e.getMessage());
    }
}
