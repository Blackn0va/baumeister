package com.blackn0va.baumeister.help;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** Hilfebuch als Bedienpult: passt ins Buch, Knöpfe führen das Richtige aus, zeigt nur, was der Spieler darf. */
class HelpBookTest {

    static final Set<String> ALL = Set.of("baumeister.begleiter", "baumeister.rucksack", "baumeister.warp",
            "baumeister.warp.setzen", "baumeister.aufgaben", "baumeister.bauen", "baumeister.reparieren",
            "baumeister.admin");

    static HelpBook.Context ctx(String name, Set<String> can, int players, int warps) {
        return new HelpBook.Context(name, can::contains,
                IntStream.range(0, players).mapToObj(i -> "Spieler" + i).toList(),
                IntStream.range(0, warps).mapToObj(i -> "warp" + i).toList(),
                List.of("bauteam"), List.of("· Baue 16 Kupfererz ab 3/16", "✔ Besiege 8 Skelette",
                        "· Ernte 32 Zuckerrohr 0/32"),
                List.of("turm", "haus"));
    }

    static List<HelpBook.Part> parts(List<HelpBook.Page> pages) {
        return pages.stream().flatMap(p -> p.parts().stream()).toList();
    }

    static List<String> commands(List<HelpBook.Page> pages) {
        return parts(pages).stream().map(HelpBook.Part::command).filter(c -> c != null).toList();
    }

    @Test
    void everyPageFitsEvenWithLongListsAndALongName() {
        for (HelpBook.Context c : List.of(ctx("Vera", ALL, 3, 2), ctx("Wunderbare Fee X", ALL, 40, 60),
                ctx("Vera", Set.of(), 0, 0))) {
            for (HelpBook.Page p : HelpBook.pages(c)) {
                assertTrue(HelpBook.fitsPage(p), "zu lang: " + p.chapter() + " – " + HelpBook.lines(p) + " Zeilen");
            }
        }
    }

    @Test
    void tableOfContentsJumpsToTheFirstPageOfEachChapter() {
        List<HelpBook.Page> pages = HelpBook.pages(ctx("Vera", ALL, 30, 30));
        List<HelpBook.Part> links = pages.get(0).parts().stream().filter(x -> x.text().startsWith("» ")).toList();
        assertTrue(links.size() >= 9);
        for (HelpBook.Part link : links) {
            HelpBook.Page target = pages.get(link.page() - 1);
            assertEquals("» " + target.chapter() + "\n", link.text());
            HelpBook.Page before = pages.get(link.page() - 2);
            assertFalse(before.chapter().equals(target.chapter()), "Sprung auf die ERSTE Seite: " + target.chapter());
            assertEquals(HelpBook.PAGE_COMMAND + link.page(), link.command(), "Sprung per Befehl, nicht change_page");
        }
    }

    @Test
    void everyPageHasBackContentsAndForward() {
        List<HelpBook.Page> pages = HelpBook.pages(ctx("Vera", ALL, 3, 3));
        int n = pages.size();
        for (int i = 1; i <= n; i++) {
            List<String> cmds = pages.get(i - 1).parts().stream().map(HelpBook.Part::command).filter(c -> c != null)
                    .toList();
            assertTrue(cmds.contains(HelpBook.PAGE_COMMAND + 1), "Inhalt auf Seite " + i);
            assertEquals(i > 1, cmds.contains(HelpBook.PAGE_COMMAND + (i - 1)), "zurück auf Seite " + i);
            assertEquals(i < n, cmds.contains(HelpBook.PAGE_COMMAND + (i + 1)), "weiter auf Seite " + i);
        }
    }

    @Test
    void openingAtAPagePutsItFirstAndKeepsTheOrder() {
        List<HelpBook.Page> pages = HelpBook.pages(ctx("Vera", ALL, 0, 0));
        List<HelpBook.Page> r = HelpBook.rotate(pages, 4);
        assertEquals(pages.size(), r.size());
        assertEquals(pages.get(3), r.get(0));
        assertEquals(pages.get(4), r.get(1));
        assertEquals(pages.get(0), r.get(r.size() - 3));
        assertEquals(pages, HelpBook.rotate(pages, 1));
        assertEquals(pages.getLast(), HelpBook.rotate(pages, 999).get(0), "zu große Seite = letzte");
    }

    @Test
    void theBookCanDoEverythingByClick() {
        List<String> cmds = commands(HelpBook.pages(ctx("Vera", ALL, 2, 2)));
        for (String must : List.of("/vera", "/vera erzähl mir einen Witz",
                "/bm begleiter abenteuer normal", "/bm begleiter abenteuer team Spieler1", "/rucksack",
                "/rucksack team bauteam", "/warp warp1", "/aufgaben rangliste", "/vera bau turm hier gespiegelt",
                "/vera reparier mein Haus", "/hilfe tippen /setwarp ", "/hilfe tippen /rucksack suche ")) {
            assertTrue(cmds.contains(must), "fehlt: " + must);
        }
        String text = parts(HelpBook.pages(ctx("Vera", ALL, 0, 0))).stream().map(HelpBook.Part::text)
                .reduce("", String::concat);
        assertTrue(text.contains("Baue 16 Kupfererz ab 3/16"), "Aufgaben mit Stand im Buch");
    }

    /**
     * Erste Seite (2026-11-08): Welt- und Grundstücks-Knöpfe nur, wo es Warp bzw. PlotSquared gibt. Auf einem fremden
     * Server ohne beides führten sie ins Leere; auf dev01 (Warps farmwelt und plotwelt, PlotSquared) bleiben sie.
     */
    @Test
    void startPageLinksOnlyToWhatExistsOnThisServer() {
        List<String> plain = commands(HelpBook.pages(ctx("Vera", ALL, 0, 0)));
        for (String none : List.of("/warp farmwelt", "/warp plotwelt", "/plot auto", "/plot home")) {
            assertFalse(plain.contains(none), "fremder Server: " + none);
        }
        HelpBook.Context dev01 = new HelpBook.Context("Vera", ALL::contains, List.of(), List.of("farmwelt",
                "plotwelt"), List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(), true);
        List<HelpBook.Page> pages = HelpBook.pages(dev01);
        List<String> cmds = commands(pages);
        for (String must : List.of("/warp farmwelt", "/warp plotwelt", "/plot auto", "/plot home")) {
            assertTrue(cmds.contains(must), "dev01: " + must);
        }
        for (HelpBook.Page p : pages) {
            assertTrue(HelpBook.fitsPage(p), "zu lang: " + p.chapter());
        }
    }

    @Test
    void playersSeeOnlyWhatTheyMayDo() {
        List<HelpBook.Page> pages = HelpBook.pages(ctx("Vera", Set.of("baumeister.warp", "baumeister.rucksack"),
                1, 1));
        List<String> chapters = pages.stream().map(HelpBook.Page::chapter).toList();
        assertFalse(chapters.contains("Bauen"));
        assertFalse(chapters.contains("Aufgaben"));
        assertFalse(chapters.contains("Abenteuer"), "ohne baumeister.begleiter keine Vera-Kapitel");
        assertTrue(chapters.contains("Warps"));
        List<String> cmds = commands(pages);
        assertFalse(cmds.contains("/hilfe tippen /setwarp "), "ohne Recht kein Setzen");
        assertFalse(cmds.contains("/vera reparier mein Haus"));
        assertFalse(chapters.contains("Admin"), "Admin-Knöpfe nur für Admins");
    }

    @Test
    void namesCannotSmuggleInOtherCommands() {
        HelpBook.Context evil = new HelpBook.Context("Vera", ALL::contains, List.of("ok_1", "x op Steve"),
                List.of("a;b", "gut"), List.of("../x"), List.of(), List.of("turm /op me"));
        List<String> cmds = commands(HelpBook.pages(evil));
        assertTrue(cmds.contains("/bm begleiter abenteuer team ok_1"));
        assertTrue(cmds.contains("/warp gut"));
        assertTrue(cmds.stream().noneMatch(c -> c.contains("op Steve") || c.contains("a;b") || c.contains("../")
                || c.contains("/op me")), cmds.toString());
    }

    @Test
    void theCompanionNameIsUsed() {
        String all = parts(HelpBook.pages(ctx("Mira", ALL, 0, 0))).stream().map(HelpBook.Part::text)
                .reduce("", String::concat);
        assertTrue(all.contains("Mira"));
        assertFalse(all.contains("Vera"));
    }

    @Test
    void footerIsAlwaysOnTheSameLine() {
        for (HelpBook.Context c : List.of(ctx("Vera", ALL, 3, 2), ctx("Wunderbare Fee X", ALL, 40, 60),
                ctx("Vera", Set.of(), 0, 0))) {
            for (HelpBook.Page p : HelpBook.pages(c)) {
                List<HelpBook.Part> parts = p.parts();
                assertEquals(HelpBook.FOOTER_NEXT, parts.getLast().text(), "Fußzeile am Ende: " + p.chapter());
                HelpBook.Page content = new HelpBook.Page(p.chapter(), parts.subList(0, parts.size() - 3));
                assertEquals(HelpBook.CONTENT_LINES, HelpBook.lines(content), "Fußzeile auf Zeile 14: " + p.chapter());
            }
        }
    }

    @Test
    void fixedTextsAreShortSingleLinesSoNothingWraps() {
        // Kurze Namen in den Listen: Dann darf keine Zeile umbrechen – sonst stimmt die Höhe der Fußzeile nicht.
        for (HelpBook.Page p : HelpBook.pages(ctx("Vera", ALL, 3, 3))) {
            StringBuilder all = new StringBuilder();
            p.parts().forEach(x -> all.append(x.text()));
            for (String line : all.toString().split("\n")) {
                if (line.startsWith("·") || line.startsWith("✔")) {
                    continue;  // Aufgabentitel kommen aus DailyQuests und dürfen umbrechen
                }
                assertTrue(line.length() <= HelpBook.LINE, "zu lang auf " + p.chapter() + ": „" + line + "“");
            }
        }
    }
}
