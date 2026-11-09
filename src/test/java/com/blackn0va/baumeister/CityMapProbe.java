package com.blackn0va.baumeister;

/**
 * Messprobe Stadtbau (2026-10-28): fragt per RCON auf einem Raster ab, ob in Höhe base+h ein Block steht, und druckt
 * daraus eine Karte – so ist ohne Spielclient zu sehen, ob Gebäude, Straßen und Höhen dort stehen, wo die Karte sie
 * hat. Aufruf: CityMapProbe &lt;port&gt; &lt;passwort&gt; &lt;welt&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; &lt;halbe Breite&gt; &lt;schritt&gt;.
 * Zeichen: '#' Decke/Wand in 3 m Höhe · '^' zusätzlich in 9 m Höhe · '.' nur Boden · ' ' leer.
 */
public final class CityMapProbe {

    private CityMapProbe() {
    }

    public static void main(String[] args) throws Exception {
        String world = args[2];
        int x0 = Integer.parseInt(args[3]);
        int y0 = Integer.parseInt(args[4]);
        int z0 = Integer.parseInt(args[5]);
        int half = Integer.parseInt(args[6]);
        int step = Integer.parseInt(args[7]);
        int walls = 0;
        int high = 0;
        int ground = 0;
        try (Rcon r = new Rcon(Integer.parseInt(args[0]), args[1])) {
            for (int z = z0 - half; z <= z0 + half; z += step) {
                // Ohne Spieler sind die Chunks nicht geladen; je Zeile laden, danach wieder freigeben.
                String band = (x0 - half) + " " + z + " " + (x0 + half) + " " + z;
                r.command("forceload add " + band);
                StringBuilder line = new StringBuilder();
                for (int x = x0 - half; x <= x0 + half; x += 2 * step) {
                    boolean low = solid(r, world, x, y0 + 3, z);
                    boolean top = low && solid(r, world, x, y0 + 9, z);
                    boolean floor = solid(r, world, x, y0, z);
                    char c = top ? '^' : low ? '#' : floor ? '.' : ' ';
                    walls += low ? 1 : 0;
                    high += top ? 1 : 0;
                    ground += floor ? 1 : 0;
                    line.append(c);
                }
                r.command("forceload remove " + band);
                System.out.println(line);
            }
        }
        System.out.printf("boden=%d gebäude=%d hoch=%d%n", ground, walls, high);
    }

    private static boolean solid(Rcon r, String world, int x, int y, int z) throws Exception {
        String res = r.command("execute in minecraft:overworld if block " + x + " " + y + " " + z + " minecraft:air");
        if (!res.contains("passed") && !res.contains("failed")) {
            // Vorher galt jede andere Antwort („That position is not loaded“) als Block – die Karte war voller '^'.
            throw new IllegalStateException("Unerwartete Antwort bei " + x + " " + y + " " + z + ": " + res);
        }
        return res.contains("failed");
    }
}
