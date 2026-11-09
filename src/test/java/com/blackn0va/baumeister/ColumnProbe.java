package com.blackn0va.baumeister;

/**
 * Messprobe (2026-10-28): Blocksäule per RCON – je Höhe der erste passende Kandidat. Zum Prüfen hoher Bauten ohne
 * Spielclient (Geschosse, Fensterbänder, Leiterschacht).
 *
 *   ColumnProbe &lt;port&gt; &lt;passwort&gt; &lt;x&gt; &lt;z&gt; &lt;y von&gt; &lt;y bis&gt; &lt;kandidat,kandidat,…&gt;
 */
public final class ColumnProbe {

    private ColumnProbe() {
    }

    public static void main(String[] args) throws Exception {
        int x = Integer.parseInt(args[2]);
        int z = Integer.parseInt(args[3]);
        int y0 = Integer.parseInt(args[4]);
        int y1 = Integer.parseInt(args[5]);
        String[] candidates = args[6].split(",");
        try (Rcon r = new Rcon(Integer.parseInt(args[0]), args[1])) {
            r.command("forceload add " + x + " " + z);
            StringBuilder runs = new StringBuilder();
            String last = null;
            int from = y0;
            for (int y = y0; y <= y1 + 1; y++) {
                String id = y > y1 ? null : "?";
                for (int i = 0; y <= y1 && i < candidates.length; i++) {
                    String res = r.command("execute if block " + x + " " + y + " " + z + " minecraft:" + candidates[i]);
                    if (res.contains("not loaded")) {
                        throw new IllegalStateException("Chunk nicht geladen bei " + x + " " + z);
                    }
                    if (res.contains("passed")) {
                        id = candidates[i];
                        break;
                    }
                }
                if (last != null && !last.equals(id)) {
                    runs.append(from).append("..").append(y - 1).append(' ').append(last).append('\n');
                    from = y;
                } else if (last == null) {
                    from = y;
                }
                last = id;
            }
            r.command("forceload remove " + x + " " + z);
            System.out.print(runs);
        }
    }
}
