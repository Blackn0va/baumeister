package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprache auf dem Server (2026-11-08): Zwischenspeicher je Satz, Umrechnung auf 48 kHz, gesprochener Text ohne
 * Farbcodes und Befehle, Fehler leise. Piper selbst ersetzt eine Ersatz-Engine (gemessen wurde Piper auf dev01).
 */
class LocalVoiceTest {

    @TempDir
    Path dir;

    private static LocalVoice.Settings settings(long cacheBytes) {
        return new LocalVoice.Settings(true, "piper", "stimme.onnx", true, true, 400, cacheBytes);
    }

    /** 0,1 s Ton in 22050 Hz: 2205 Samples, 4410 Bytes. */
    private static byte[] tone() {
        byte[] b = new byte[4410];
        for (int i = 0; i < 2205; i++) {
            short v = (short) (i % 100 * 100);
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }

    @Test
    void sameSentenceIsComputedOnceAndComesAt48k() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LocalVoice v = LocalVoice.forTest(settings(10_000_000), t -> {
            calls.incrementAndGet();
            return tone();
        }, 22_050, dir.resolve("sprache"));
        Optional<short[]> a = v.voice("Ich fange an!").get(5, TimeUnit.SECONDS);
        Optional<short[]> b = v.voice("Ich fange an!").get(5, TimeUnit.SECONDS);
        assertEquals(1, calls.get(), "zweites Mal aus dem Zwischenspeicher");
        assertEquals(4800, a.orElseThrow().length, "0,1 s in 48 kHz");
        assertEquals(a.get().length, b.orElseThrow().length);
        v.stop();
    }

    /** Cartoon-Ton: Die Samples laufen um die Tonhöhe schneller – kürzer und höher; Piper dehnt dafür. */
    @Test
    void higherPitchMakesTheSameSentenceShorter() throws Exception {
        LocalVoice.Settings toon = new LocalVoice.Settings(true, "piper", "stimme.onnx", true, true, 400, 0, 1.25);
        LocalVoice v = LocalVoice.forTest(toon, t -> tone(), 22_050, dir.resolve("t"));
        int n = v.voice("Hey!").get(5, TimeUnit.SECONDS).orElseThrow().length;
        assertEquals(2205L * 48_000 / 27_563, n, 2, "0,1 s um 1,25 schneller abgespielt");
        assertEquals(1.25 / 1.1, toon.lengthScale(), 1e-9);
        assertEquals(1.0, settings(0).lengthScale(), "normaler Ton: Piper ohne Dehnung");
        v.stop();
    }

    @Test
    void veraHasHerOwnVoiceAndCartoonByDefault() {
        MemoryConfiguration c = new MemoryConfiguration();
        c.set("sprache.stimme", "/s/thorsten.onnx");
        assertEquals("/s/thorsten.onnx", LocalVoice.Settings.forVera(c).model(), "ohne eigene: wie der Baumeister");
        assertEquals(LocalVoice.CARTOON, LocalVoice.Settings.forVera(c).pitch());
        assertEquals(1.0, LocalVoice.Settings.from(c).pitch(), "Baumeister natürlich");
        c.set("sprache.vera-stimme", "/s/kerstin.onnx");
        c.set("sprache.vera-tonhöhe", 9.0);
        assertEquals("/s/kerstin.onnx", LocalVoice.Settings.forVera(c).model());
        assertEquals(1.8, LocalVoice.Settings.forVera(c).pitch(), "gekappt");
        assertEquals(1.0, LocalVoice.Settings.pitchOf(Double.NaN));
        assertEquals(java.util.Optional.of(LocalVoice.CARTOON), VoiceControl.pitch("cartoon"));
        assertEquals(java.util.Optional.of(1.3), VoiceControl.pitch("1,3"));
        assertTrue(VoiceControl.pitch("9").isEmpty());
    }

    @Test
    void failuresAreQuietAndEmpty() throws Exception {
        LocalVoice v = LocalVoice.forTest(settings(0), t -> {
            throw new IOException("Piper endete mit 1");
        }, 22_050, dir);
        assertTrue(v.voice("Hallo").get(5, TimeUnit.SECONDS).isEmpty());
        assertTrue(v.voice("   ").get(5, TimeUnit.SECONDS).isEmpty(), "nichts zu sagen");
        v.stop();
    }

    @Test
    void oldestSentencesLeaveWhenTheCacheIsFull() throws Exception {
        LocalVoice v = LocalVoice.forTest(settings(10_000), t -> tone(), 22_050, dir.resolve("s"));
        for (int i = 0; i < 4; i++) {
            v.voice("Satz " + i).get(5, TimeUnit.SECONDS);
            Thread.sleep(20);
        }
        v.trim();
        long left;
        try (var s = Files.list(dir.resolve("s"))) {
            left = s.count();
        }
        assertEquals(2, left, "4 × 4410 Bytes bei 10 000 erlaubt → die zwei jüngsten bleiben");
        assertTrue(Files.exists(dir.resolve("s").resolve(LocalVoice.key("Satz 3") + ".raw")));
        v.stop();
    }

    @Test
    void spokenTextHasNoColorCodesCommandsOrLinks() {
        assertEquals("Klar! Tippe dann und los geht's.", LocalVoice.speakable(
                "§6Klar! §7Tippe dann /bm vorschau und los geht's.", 400).replace("  ", " "));
        assertEquals("Hier:", LocalVoice.speakable("Hier: https://example.org/x", 400));
        assertEquals("Ein Krieper im Tschank!", LocalVoice.speakable("§cEin Creeper im Chunk! ⚠", 400),
                "Aussprache wie im Host (SpeechText)");
        String longText = "wort ".repeat(200);
        assertTrue(LocalVoice.speakable(longText, 100).length() <= 100);
        assertFalse(LocalVoice.speakable(longText, 100).endsWith(" "));
    }

    @Test
    void resampleKeepsLengthRatioAndValues() {
        byte[] raw = {0, 0, (byte) 0xe8, 0x03}; // 0, 1000
        short[] up = LocalVoice.resample(raw, 16_000, 48_000);
        assertEquals(6, up.length);
        assertEquals(0, up[0]);
        assertEquals(333, up[1], 1);
        assertEquals(2, LocalVoice.resample(raw, 48_000, 48_000).length);
    }

    @Test
    void offByDefaultAndExplainsWhatIsMissing() {
        LocalVoice.Settings s = LocalVoice.Settings.from(new MemoryConfiguration());
        assertFalse(s.enabled());
        assertTrue(LocalVoice.open(s, dir).isEmpty());
        assertTrue(settings(1).problem().orElseThrow().contains("sprache.piper"));
    }
}
