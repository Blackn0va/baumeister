package com.blackn0va.baumeister.town;

import java.util.Arrays;

/**
 * Was das Stadtleben je Tick kostet: Mittel, p99 und Höchstwert über die letzten WINDOW Ticks (Vertrag v008, AK-25:
 * Ziel ≤ 1 ms im Mittel, ≤ 5 ms p99 mit 12 Bewohnern auf dem PC). Gemessen mit System.nanoTime um den ganzen
 * Stadtleben-Takt, nicht geschätzt; /bm stadtleben status zeigt die Werte, die Live-Probe liest sie dort ab.
 *
 * Ein fester Ring statt einer Liste: Messen darf selbst nichts kosten (keine Objekte je Tick).
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
public final class TickCost {

    /** 60 s bei 20 TPS. */
    public static final int WINDOW = 1200;

    private final long[] nanos = new long[WINDOW];
    private int size;
    private int next;

    public void add(long tickNanos) {
        nanos[next] = tickNanos;
        next = (next + 1) % WINDOW;
        size = Math.min(size + 1, WINDOW);
    }

    public void clear() {
        size = 0;
        next = 0;
    }

    public int size() {
        return size;
    }

    /** {Mittel, p99, Höchstwert} in Millisekunden, {0, 0, 0} ohne Messwerte. */
    public double[] millis() {
        if (size == 0) {
            return new double[] {0, 0, 0};
        }
        long[] copy = Arrays.copyOf(nanos, size);
        Arrays.sort(copy);
        long sum = 0;
        for (long n : copy) {
            sum += n;
        }
        int p99 = Math.min(size - 1, (int) Math.ceil(size * 0.99) - 1);
        return new double[] {sum / (double) size / 1e6, copy[Math.max(0, p99)] / 1e6, copy[size - 1] / 1e6};
    }
}
