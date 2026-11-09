package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** /mods liest die Liste, die tools/modpaket/Modpaket-Erstellen.ps1 schreibt (2026-11-07, Probelauf des Skripts). */
class ModsInfoTest {

    @Test
    void readsTheListTheScriptWrites() {
        // Wortlaut aus dem Probelauf des Skripts (Tabulator getrennt).
        ModsInfo.Listing l = ModsInfo.parse(List.of(
                "Baumeisterland 26.2 – Fabric-Loader 0.20.1 – erzeugt 2026-11-07 21:53",
                "baumeister-client-0.2.0.jar\tim Paket\t-\t-",
                "voicechat-fabric-2.6.27+26.2.jar\tModrinth\tfabric-2.6.27+26.2\t9eGKb6K1",
                "kaputte zeile"));
        assertTrue(l.header().contains("Fabric-Loader 0.20.1"));
        assertEquals(2, l.entries().size());
        assertEquals("fabric-2.6.27+26.2", l.entries().get(1).version());
        assertEquals("im Paket", l.entries().getFirst().source());
    }

    /** Wortlaut der Server: Spigot 1.21.11 und Paper 26.2-135 (frischer Server, 2026-11-08). */
    @Test
    void minecraftVersionIsTheLauncherVersionOnSpigotAndPaper() {
        assertEquals("1.21.11", ModsInfo.minecraftVersion("1.21.11-R0.1-SNAPSHOT"));
        assertEquals("26.2", ModsInfo.minecraftVersion("26.2.build.135-alpha"));
        assertEquals("26.2", ModsInfo.minecraftVersion("26.2-R0.1-SNAPSHOT"));
        assertEquals("26.3.1", ModsInfo.minecraftVersion("26.3.1.build.7"));
        assertEquals("unbekannt", ModsInfo.minecraftVersion("unbekannt"));
    }

    @Test
    void picksTheNewestListOrNothing(@TempDir Path dir) throws Exception {
        assertTrue(ModsInfo.newestList(dir.resolve("fehlt")).isEmpty());
        Files.writeString(dir.resolve("Baumeisterland-26.1-mods.txt"), "alt");
        Files.writeString(dir.resolve("Baumeisterland-26.2-mods.txt"), "neu");
        Files.writeString(dir.resolve("Baumeisterland-26.2.mrpack"), "zip");
        assertEquals("Baumeisterland-26.2-mods.txt", ModsInfo.newestList(dir).orElseThrow().getFileName().toString());
    }
}
