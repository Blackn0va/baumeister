package com.blackn0va.baumeister;

import static com.blackn0va.baumeister.VoiceAppBinding.Verdict.ACCEPT;
import static com.blackn0va.baumeister.VoiceAppBinding.Verdict.NOT_LISTED;
import static com.blackn0va.baumeister.VoiceAppBinding.Verdict.NO_APP;
import static com.blackn0va.baumeister.VoiceAppBinding.Verdict.UNKNOWN_APP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Für wen eine App über „gehört“ sprechen darf (Plan A4): fernverbindung.apps und Kopf X-Baumeister-App. */
class VoiceAppBindingTest {

    private static VoiceAppBinding from(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return VoiceAppBinding.from(c);
    }

    /** Wie getConfig() im Betrieb: Datei des Admins mit der Vorlage als Vorgaben. */
    private static VoiceAppBinding fromFileWithDefaults(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        c.setDefaults(ConfigMigrationTest.bundled());
        return VoiceAppBinding.from(c);
    }

    /** Ohne Eintrag wie über den SSH-Tunnel: Es gibt keinen Kopf, und jeder Satz gilt wie bisher. */
    @Test
    void withoutEntriesEverySentenceCountsAsBefore() throws Exception {
        for (VoiceAppBinding b : List.of(VoiceAppBinding.from(ConfigMigrationTest.bundled()),
                fromFileWithDefaults("llm:\n  max-tokens: 3000\n"), from("fernverbindung:\n  apps: []\n"),
                from("fernverbindung:\n  apps: ''\n"), from(""))) {
            assertFalse(b.bound());
            assertEquals(ACCEPT, b.check(List.of(), "Steve"));
            assertEquals(ACCEPT, b.check(List.of("fremd"), "Steve"));
        }
    }

    @Test
    void boundAppSpeaksOnlyForItsOwnPlayers() throws Exception {
        VoiceAppBinding b = fromFileWithDefaults("fernverbindung:\n  apps:\n    gpu-pc:\n      spieler: [Blackkn0va, "
                + "Steve]\n    laptop:\n      spieler:\n        - Alex\n");
        assertTrue(b.bound());
        assertEquals("gpu-pc=2,laptop=1", b.describe());
        assertEquals(ACCEPT, b.check(List.of("gpu-pc"), "Blackkn0va"));
        assertEquals(ACCEPT, b.check(List.of("gpu-pc"), "blackkn0va"), "Groß/klein wie Bukkit.getPlayerExact");
        assertEquals(ACCEPT, b.check(List.of("laptop"), "Alex"));
        assertEquals(NOT_LISTED, b.check(List.of("laptop"), "Blackkn0va"), "der Laptop spricht nie für den Admin");
        assertEquals(NOT_LISTED, b.check(List.of("gpu-pc"), null));
        assertEquals(NO_APP, b.check(List.of(), "Blackkn0va"), "ohne Kopf (Tunnel, fremder Prozess)");
        assertEquals(NO_APP, b.check(null, "Blackkn0va"));
        assertEquals(UNKNOWN_APP, b.check(List.of("tablet"), "Blackkn0va"));
        assertEquals(UNKNOWN_APP, b.check(List.of("gpu-pc", "laptop"), "Blackkn0va"), "zwei Köpfe: nicht eindeutig");
        assertEquals(UNKNOWN_APP, b.check(List.of("GPU-PC"), "Blackkn0va"), "Pin-Namen sind klein");
    }

    /** Fail-closed: Was der Admin falsch schreibt, macht die Bindung strenger, nie lockerer. */
    @Test
    void brokenEntriesBindMoreNotLess() throws Exception {
        VoiceAppBinding scalar = from("fernverbindung:\n  apps: gpu-pc\n");
        assertTrue(scalar.bound(), "ein Wert statt eines Abschnitts ist ein Versuch zu binden");
        assertEquals(UNKNOWN_APP, scalar.check(List.of("gpu-pc"), "Blackkn0va"));
        assertEquals("-", scalar.describe());

        VoiceAppBinding text = from("fernverbindung:\n  apps:\n    gpu-pc:\n      spieler: Blackkn0va\n");
        assertEquals(NOT_LISTED, text.check(List.of("gpu-pc"), "Blackkn0va"), "Text statt Liste: für niemanden");

        VoiceAppBinding name = from("fernverbindung:\n  apps:\n    GPU_PC:\n      spieler: [Blackkn0va]\n");
        assertTrue(name.bound());
        assertEquals(UNKNOWN_APP, name.check(List.of("GPU_PC"), "Blackkn0va"), "ungültiger Name gilt nicht");

        VoiceAppBinding mixed = from("fernverbindung:\n  apps:\n    gpu-pc:\n      spieler: ['a b', Blackkn0va, "
                + "12345]\n");
        assertEquals(ACCEPT, mixed.check(List.of("gpu-pc"), "Blackkn0va"));
        assertEquals(ACCEPT, mixed.check(List.of("gpu-pc"), "12345"), "reine Zahl liest YAML als Zahl – ist ein Name");
        assertEquals(NOT_LISTED, mixed.check(List.of("gpu-pc"), "a b"));
    }

    /** Beide Namen kommen von außen: Ins Log nur, was wie ein Name aussieht (keine zweite, gefälschte Zeile). */
    @Test
    void logShowsOnlyAppNamesThatLookLikeNames() {
        assertEquals("gpu-pc", VoiceAppBinding.logName(List.of("gpu-pc")));
        assertEquals("ungültig", VoiceAppBinding.logName(List.of("x\nbegleiter | gefälscht")));
        assertEquals("ungültig", VoiceAppBinding.logName(List.of("gpu-pc", "laptop")));
        assertEquals("-", VoiceAppBinding.logName(List.of()));
    }
}
