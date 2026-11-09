package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.List;

/**
 * Messprobe (2026-10-29): Wie lange braucht die Erdwelt für frische Chunks? Hält per RCON ein Quadrat von
 * (2r+1)² Chunks um eine Stelle fest (forceload, wie ein Spieler mit Sichtweite r) und fragt reihum „execute if
 * loaded“, bis alle fertig sind. Gemessen wird von Wand zu Wand: Vanilla-Stufen, Licht, Populator.
 *
 *   EarthChunkTimingProbe &lt;port&gt; &lt;passwort&gt; &lt;dimension&gt; &lt;x&gt; &lt;z&gt; &lt;r&gt;
 *   z. B. EarthChunkTimingProbe 25612 geheim baumeister:erde 357711 6764429 10
 *
 * Danach selbst „execute in &lt;dimension&gt; run forceload remove all“ (die Probe lässt die Chunks für weitere Messungen
 * geladen). Nur gegen frisches Gelände messen – schon erzeugte Chunks laden in Millisekunden von der Platte.
 */
public final class EarthChunkTimingProbe {

    private EarthChunkTimingProbe() {
    }

    public static void main(String[] args) throws Exception {
        String in = "execute in " + args[2] + " ";
        int cx0 = Math.floorDiv(Integer.parseInt(args[3]), 16);
        int cz0 = Math.floorDiv(Integer.parseInt(args[4]), 16);
        int r = Integer.parseInt(args[5]);
        try (Rcon rc = new Rcon(Integer.parseInt(args[0]), args[1])) {
            long t0 = System.nanoTime();
            // forceload nimmt höchstens 256 Chunks je Befehl – in Streifen zu 16 Chunks.
            for (int x = cx0 - r; x <= cx0 + r; x += 16) {
                for (int z = cz0 - r; z <= cz0 + r; z += 16) {
                    int x1 = Math.min(cx0 + r, x + 15);
                    int z1 = Math.min(cz0 + r, z + 15);
                    String answer = rc.command(in + "run forceload add " + x * 16 + " " + z * 16 + " " + x1 * 16 + " "
                            + z1 * 16);
                    if (!answer.contains("Marked") && !answer.contains("marked")) {
                        System.out.println("forceload: " + answer);
                    }
                }
            }
            List<int[]> open = new ArrayList<>();
            for (int x = cx0 - r; x <= cx0 + r; x++) {
                for (int z = cz0 - r; z <= cz0 + r; z++) {
                    open.add(new int[] {x, z});
                }
            }
            int total = open.size();
            long lastProgress = System.nanoTime();
            while (!open.isEmpty()) {
                List<int[]> still = new ArrayList<>();
                for (int[] c : open) {
                    String a = rc.command(in + "if loaded " + (c[0] * 16 + 8) + " 0 " + (c[1] * 16 + 8));
                    if (!a.contains("passed")) {
                        still.add(c);
                    }
                }
                if (still.size() < open.size()) {
                    lastProgress = System.nanoTime();
                }
                open = still;
                if (System.nanoTime() - lastProgress > 300_000_000_000L) {
                    throw new IllegalStateException("5 min ohne Fortschritt, offen: " + open.size());
                }
                if (!open.isEmpty()) {
                    Thread.sleep(100);
                }
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf("chunks=%d dauer_ms=%.0f ms_je_chunk=%.1f%n", total, ms, ms / total);
        }
    }
}
