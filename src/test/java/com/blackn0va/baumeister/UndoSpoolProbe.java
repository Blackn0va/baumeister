package com.blackn0va.baumeister;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Messprobe Rückgängig (2026-10-29): schreibt n Einträge wie ein Stadtbau (Zeilen über eine Fläche, ~40 Zustände)
 * und misst Schreiben, Plattengröße und das Lesen je Teil. Das Lesen eines Teils läuft beim Zurücksetzen IN einem
 * Tick (BuildService.restoreSpool) – die Zeit je Teil ist also eine Tick-Spitze. Aufruf: UndoSpoolProbe [n] [runden]
 */
public final class UndoSpoolProbe {

    private UndoSpoolProbe() {
    }

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        String[] states = new String[40];
        for (int i = 0; i < states.length; i++) {
            states[i] = "minecraft:block_" + i + "[facing=north,half=bottom,waterlogged=false]";
        }
        for (int r = 0; r < rounds; r++) {
            Path dir = Files.createTempDirectory("undo-probe");
            UndoSpool s = new UndoSpool(dir);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                s.record(4096 + i % 300, -61 + (i / 90_000) % 20, 4096 + (i / 300) % 300, states[(i * 7 / 13) % 40]);
            }
            s.finish();
            long write = System.nanoTime() - t0;
            long bytes = 0;
            List<Path> parts = s.partsNewestFirst();
            long maxRead = 0;
            long sumRead = 0;
            for (Path p : parts) {
                bytes += Files.size(p);
                long t1 = System.nanoTime();
                UndoSpool.Part part = UndoSpool.read(p);
                long took = System.nanoTime() - t1;
                maxRead = Math.max(maxRead, took);
                sumRead += took;
                if (part.size() == 0) {
                    throw new IllegalStateException("leerer Teil");
                }
            }
            System.out.printf("runde=%d einträge=%d teile=%d schreiben_ms=%.1f platte_kb=%d lesen_ms=%.1f "
                    + "längster_teil_ms=%.1f%n", r, n, parts.size(), write / 1e6, bytes / 1024, sumRead / 1e6,
                    maxRead / 1e6);
            s.delete();
        }
    }
}
