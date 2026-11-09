package com.blackn0va.baumeister;

/**
 * Messprobe (2026-10-28): Oberkante der Erdwelt an einer Stelle per RCON – lädt den Chunk in der Dimension, sucht von
 * oben den ersten festen oder Wasserblock (erst in 32er-Schritten, dann halbierend) und nennt den obersten Block.
 * Damit lässt sich ohne Spielclient prüfen, ob y die erwartete Meereshöhe trifft.
 *
 *   EarthSurfaceProbe &lt;port&gt; &lt;passwort&gt; &lt;dimension&gt; &lt;name x z&gt;…
 *   z. B. EarthSurfaceProbe 25612 geheim baumeister:erde Marienplatz 382503 6821329
 */
public final class EarthSurfaceProbe {

    private static final String[] TOPS = {"grass_block", "water", "sand", "stone", "snow_block", "coarse_dirt", "dirt",
        "terracotta", "red_terracotta", "orange_terracotta", "white_terracotta", "brown_terracotta",
        "yellow_terracotta", "light_gray_terracotta", "bedrock", "stone_bricks"};

    private EarthSurfaceProbe() {
    }

    public static void main(String[] args) throws Exception {
        String in = "execute in " + args[2] + " ";
        try (Rcon r = new Rcon(Integer.parseInt(args[0]), args[1])) {
            for (int i = 3; i + 2 < args.length; i += 3) {
                String name = args[i];
                int x = Integer.parseInt(args[i + 1]);
                int z = Integer.parseInt(args[i + 2]);
                r.command(in + "run forceload add " + x + " " + z);
                long t0 = System.nanoTime();
                while (r.command(in + "if block " + x + " 2031 " + z + " air").contains("not loaded")) {
                    if (System.nanoTime() - t0 > 120_000_000_000L) {
                        throw new IllegalStateException("Chunk nach 120 s nicht geladen: " + name);
                    }
                    Thread.sleep(200);
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int hit = Integer.MIN_VALUE;
                for (int y = 2031; y >= -2032; y -= 32) {
                    if (!r.command(in + "if block " + x + " " + y + " " + z + " air").contains("passed")) {
                        hit = y;
                        break;
                    }
                }
                if (hit == Integer.MIN_VALUE) {
                    System.out.printf("%s x=%d z=%d leer (nur Luft), geladen in %d ms%n", name, x, z, ms);
                    continue;
                }
                int lo = hit;
                int hi = Math.min(2031, hit + 32);
                while (hi - lo > 1) {
                    int m = (lo + hi) / 2;
                    if (r.command(in + "if block " + x + " " + m + " " + z + " air").contains("passed")) {
                        hi = m;
                    } else {
                        lo = m;
                    }
                }
                String top = "?";
                for (String c : TOPS) {
                    if (r.command(in + "if block " + x + " " + lo + " " + z + " minecraft:" + c).contains("passed")) {
                        top = c;
                        break;
                    }
                }
                String ground = top;
                int groundY = lo;
                if (top.equals("water")) {
                    groundY = lo;
                    while (groundY > -2032 && r.command(in + "if block " + x + " " + groundY + " " + z + " water")
                            .contains("passed")) {
                        groundY--;
                    }
                    ground = "Boden y=" + groundY;
                }
                System.out.printf("%s x=%d z=%d oberkante y=%d %s%s, geladen in %d ms%n", name, x, z, lo, top,
                        top.equals("water") ? " (" + ground + ")" : "", ms);
            }
        }
    }
}
