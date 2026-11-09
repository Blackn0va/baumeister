package com.blackn0va.baumeister;

import java.util.Map;
import java.util.TreeMap;

/**
 * Messprobe (2026-10-28): zählt per RCON in einem Quader, wo welche der genannten Blöcke stehen – für Bauten, deren
 * Lage nach Drehung und Abstand nicht genau bekannt ist (Haus mit Einrichtung). Gibt je Block Anzahl und Fundorte aus.
 *
 *   BlockScanProbe &lt;port&gt; &lt;passwort&gt; &lt;x0&gt; &lt;y0&gt; &lt;z0&gt; &lt;x1&gt; &lt;y1&gt; &lt;z1&gt; &lt;block,block,…&gt;
 */
public final class BlockScanProbe {

    private BlockScanProbe() {
    }

    public static void main(String[] args) throws Exception {
        int x0 = Integer.parseInt(args[2]);
        int y0 = Integer.parseInt(args[3]);
        int z0 = Integer.parseInt(args[4]);
        int x1 = Integer.parseInt(args[5]);
        int y1 = Integer.parseInt(args[6]);
        int z1 = Integer.parseInt(args[7]);
        String[] blocks = args[8].split(",");
        Map<String, StringBuilder> found = new TreeMap<>();
        Map<String, Integer> count = new TreeMap<>();
        try (Rcon r = new Rcon(Integer.parseInt(args[0]), args[1])) {
            r.command("forceload add " + x0 + " " + z0 + " " + x1 + " " + z1);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (String b : blocks) {
                            String res = r.command("execute if block " + x + " " + y + " " + z + " minecraft:" + b);
                            if (res.contains("not loaded")) {
                                throw new IllegalStateException("Chunk nicht geladen bei " + x + " " + z);
                            }
                            if (res.contains("passed")) {
                                count.merge(b, 1, Integer::sum);
                                StringBuilder sb = found.computeIfAbsent(b, k -> new StringBuilder());
                                if (sb.length() < 600) {
                                    sb.append(x).append('/').append(y).append('/').append(z).append(' ');
                                }
                                break;
                            }
                        }
                    }
                }
            }
            r.command("forceload remove " + x0 + " " + z0 + " " + x1 + " " + z1);
        }
        for (String b : blocks) {
            System.out.println(b + ": " + count.getOrDefault(b, 0) + "  " + found.getOrDefault(b, new StringBuilder()));
        }
    }
}
