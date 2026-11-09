package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.List;

/**
 * Das Tagebuch des Abenteuers als Buchseiten: Titelseite (Spieler, Modus, Spielzeit, Tode, Stand), dann alle Etappen –
 * geschafft mit der Zeit, die sie gedauert hat, die aktuelle hervorgehoben, die übrigen grau.
 *
 * Wunsch 2026-11-04. Ein Buch, das man aufschlägt, macht den Fortschritt greifbar und zeigt
 * am Ende die ganze Geschichte. Etappen, die beim Start schon erfüllt waren (Spieler kam mit Werkzeug), stehen als
 * „schon dabei“ – keine erfundene Dauer.
 *
 * Seiten höchstens ~14 Zeilen: 5 Etappen je Seite (je zwei Zeilen) passen sicher.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class AdventureDiary {

    static final int STAGES_PER_PAGE = 5;

    private AdventureDiary() {
    }

    public static List<String> pages(AdventureBook.Run r, String player, long nowMillis) {
        List<String> out = new ArrayList<>();
        out.add("§l§5Abenteuer-Tagebuch§r\n\n§0von " + player + "\n\nModus: "
                + (r.mode() == Adventure.Mode.HARDCORE ? "§4Hardcore§0 (ein Leben)" : "normal")
                + "\nUnterwegs: " + duration(nowMillis - r.startedMillis())
                + (r.mode() == Adventure.Mode.NORMAL ? "\nTode: " + r.deaths() : "")
                + "\n\n" + Adventure.progress(r.stage()) + "\n\n§7Aufgeschrieben von Vera");
        StringBuilder page = new StringBuilder();
        int onPage = 0;
        long previous = r.startedMillis();
        for (int i = 0; i < Adventure.STAGES.size(); i++) {
            Adventure.Stage s = Adventure.STAGES.get(i);
            Long at = r.doneAt().get(i);
            String line;
            if (i < r.stage()) {
                String took = at == null ? "geschafft" : at <= r.startedMillis() ? "schon dabei"
                        : "nach " + duration(at - previous);
                if (at != null && at > previous) {
                    previous = at;
                }
                line = "§2✔ " + s.title() + "\n§8   " + took;
            } else if (i == r.stage()) {
                line = "§6➜ §l" + s.title() + "§r\n§6   jetzt dran";
            } else {
                line = "§7· " + s.title() + "\n";
            }
            page.append(page.isEmpty() ? "" : "\n").append(line);
            if (++onPage == STAGES_PER_PAGE) {
                out.add(page.toString());
                page.setLength(0);
                onPage = 0;
            }
        }
        if (!page.isEmpty()) {
            out.add(page.toString());
        }
        return out;
    }

    /** „5 Min.“, „1 Std. 20 Min.“, „unter einer Minute“. */
    static String duration(long ms) {
        long minutes = Math.max(0, ms) / 60_000;
        if (minutes < 1) {
            return "unter einer Minute";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        return h == 0 ? m + " Min." : h + " Std." + (m == 0 ? "" : " " + m + " Min.");
    }
}
