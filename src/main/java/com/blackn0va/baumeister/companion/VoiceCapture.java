package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sammelt die Sprachpakete eines Spielers aus Simple Voice Chat zu Sätzen (Wunsch 2026-11-04). Simple Voice Chat
 * schickt nur, solange jemand spricht (Sprechtaste oder Sprachaktivierung) – eine
 * Lücke von GAP_MS ohne Paket beendet den Satz. Danach geht er mit 16 kHz an die Erkennung im Baumeister-Host.
 *
 * Rein, ohne Simple-Voice-Chat-Typen: Die Pakete kommen schon dekodiert (48 kHz mono, 960 Samples je 20 ms).
 *
 * Threads: feed aus dem Netzwerk-Thread von Simple Voice Chat, poll aus einem Bukkit-Takt – beide über this
 * synchronisiert. Fehler: keine.
 */
public final class VoiceCapture {

    public static final int RATE_IN = 48_000;
    public static final int RATE_OUT = 16_000;
    /** So lange ohne Paket = Satz zu Ende. Simple Voice Chat hängt beim Loslassen der Taste noch ~100 ms nach. */
    public static final long GAP_MS = 500;
    /** Längere Sätze werden hier abgeschnitten (16 kHz × 16 bit × 15 s = 480 KB, unter der Host-Grenze von 1 MiB). */
    public static final int MAX_SAMPLES_IN = RATE_IN * 15;
    /** Kürzer = ein Räuspern oder ein versehentlicher Tastendruck (0,4 s). */
    public static final int MIN_SAMPLES_IN = RATE_IN * 2 / 5;

    /** Fertiger Satz eines Spielers: PCM 16 kHz, 16 bit, mono, little-endian – so erwartet ihn /begleiter/hoeren. */
    public record Done(UUID player, byte[] pcm16kLe) {
    }

    private static final class Pending {
        short[] samples = new short[RATE_IN];
        int size;
        long last;
    }

    private final Map<UUID, Pending> pending = new HashMap<>();

    public synchronized void feed(UUID player, short[] frame, long nowMillis) {
        Pending p = pending.computeIfAbsent(player, k -> new Pending());
        int room = MAX_SAMPLES_IN - p.size;
        int n = Math.min(room, frame.length);
        if (n > 0) {
            if (p.size + n > p.samples.length) {
                p.samples = java.util.Arrays.copyOf(p.samples, Math.min(MAX_SAMPLES_IN, Math.max(p.size + n,
                        p.samples.length * 2)));
            }
            System.arraycopy(frame, 0, p.samples, p.size, n);
            p.size += n;
        }
        p.last = nowMillis;
    }

    /** Sätze, nach denen GAP_MS nichts mehr kam; zu kurze fallen weg. */
    public synchronized List<Done> poll(long nowMillis) {
        List<Done> out = new ArrayList<>();
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> e = it.next();
            Pending p = e.getValue();
            if (nowMillis - p.last < GAP_MS) {
                continue;
            }
            it.remove();
            if (p.size >= MIN_SAMPLES_IN) {
                out.add(new Done(e.getKey(), toLittleEndian(downsample(p.samples, p.size))));
            }
        }
        return out;
    }

    /** Spieler weg (Server verlassen, Begleiter weggeschickt): Angefangenes verwerfen. */
    public synchronized void drop(UUID player) {
        pending.remove(player);
    }

    /** 48 kHz → 16 kHz: Mittel aus je drei Samples (einfacher Tiefpass gegen Aliasing, reicht für Sprache). */
    static short[] downsample(short[] in, int size) {
        short[] out = new short[size / 3];
        for (int i = 0; i < out.length; i++) {
            int sum = in[3 * i] + in[3 * i + 1] + in[3 * i + 2];
            out[i] = (short) (sum / 3);
        }
        return out;
    }

    static byte[] toLittleEndian(short[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            b[2 * i] = (byte) (s[i] & 0xFF);
            b[2 * i + 1] = (byte) ((s[i] >> 8) & 0xFF);
        }
        return b;
    }

    /** Gegenstück für die Stimme aus dem Host (48 kHz, little-endian) → Samples für Simple Voice Chat. */
    public static short[] fromLittleEndian(byte[] b) {
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
        }
        return s;
    }
}
