package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Sprachpakete aus Simple Voice Chat zu Sätzen sammeln und für den Host umrechnen. */
class VoiceCaptureTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static short[] frame(short value) {
        short[] f = new short[960];  // 20 ms bei 48 kHz
        Arrays.fill(f, value);
        return f;
    }

    @Test
    void sentenceEndsAfterTheGapAndComesOutAt16kHz() {
        VoiceCapture c = new VoiceCapture();
        long t = 1000;
        for (int i = 0; i < 50; i++) {  // 1 s
            c.feed(A, frame((short) 1000), t);
            t += 20;
        }
        long last = t - 20;  // Zeit des letzten Pakets
        assertTrue(c.poll(last + VoiceCapture.GAP_MS - 1).isEmpty(), "Lücke noch nicht lang genug");
        List<VoiceCapture.Done> done = c.poll(last + VoiceCapture.GAP_MS);
        assertEquals(1, done.size());
        assertEquals(A, done.get(0).player());
        assertEquals(16000 * 2, done.get(0).pcm16kLe().length, "1 s → 16 000 Samples × 2 Byte");
        assertEquals(1000, VoiceCapture.fromLittleEndian(done.get(0).pcm16kLe())[100]);
        assertTrue(c.poll(t + 5000).isEmpty(), "einmal abgeholt ist weg");
    }

    @Test
    void shortBlipsAreDroppedAndPlayersAreSeparate() {
        VoiceCapture c = new VoiceCapture();
        c.feed(A, frame((short) 1), 0);  // 20 ms – Räuspern
        for (int i = 0; i < 30; i++) {
            c.feed(B, frame((short) 2), i * 20L);
        }
        List<VoiceCapture.Done> done = c.poll(10_000);
        assertEquals(1, done.size());
        assertEquals(B, done.get(0).player());
    }

    @Test
    void longSentencesAreCappedAtFifteenSeconds() {
        VoiceCapture c = new VoiceCapture();
        for (int i = 0; i < 1000; i++) {  // 20 s
            c.feed(A, frame((short) 3), i * 20L);
        }
        VoiceCapture.Done d = c.poll(100_000).get(0);
        assertEquals(VoiceCapture.MAX_SAMPLES_IN / 3 * 2, d.pcm16kLe().length);
    }

    @Test
    void dropForgetsAnUnfinishedSentence() {
        VoiceCapture c = new VoiceCapture();
        for (int i = 0; i < 50; i++) {
            c.feed(A, frame((short) 4), i * 20L);
        }
        c.drop(A);
        assertTrue(c.poll(100_000).isEmpty());
    }

    @Test
    void downsampleAveragesAndLittleEndianRoundTrips() {
        assertArrayEquals(new short[] {2, 5}, VoiceCapture.downsample(new short[] {1, 2, 3, 4, 5, 6, 7}, 7));
        short[] s = {0, 1, -1, 0x1234, Short.MIN_VALUE, Short.MAX_VALUE};
        assertArrayEquals(s, VoiceCapture.fromLittleEndian(VoiceCapture.toLittleEndian(s)));
        assertEquals(0x34, VoiceCapture.toLittleEndian(new short[] {0x1234})[0]);
    }

    @Test
    void inGameSpeechNeedsTheNameOrARunningConversation() {
        assertEquals("gib mir Fackeln", CompanionChat.inGame("Vera", "Hey Vera, gib mir Fackeln.", false)
                .replace(".", ""));
        assertNull(CompanionChat.inGame("Vera", "Kommst du mit in die Mine?", false), "Gespräch mit anderen");
        assertEquals("Kommst du mit in die Mine?", CompanionChat.inGame("Vera", " Kommst du mit in die Mine? ", true));
        assertNull(CompanionChat.inGame("Vera", "  ", true));
        assertEquals("komm", CompanionChat.inGame("Vera", "Hey, Vera, komm", false));
    }
}
