package com.blackn0va.baumeister.help;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionIntent;
import com.blackn0va.baumeister.companion.CompanionReply;
import com.blackn0va.baumeister.companion.CompanionRules;
import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.QuickOrders;
import com.blackn0va.baumeister.plan.TunnelOrder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Bedienung im Buch (2026-11-08): Schnellbau, Suchen, Sammlung, Mods und Stimme. Jeder Knopf muss den Weg treffen,
 * den er verspricht – ein Schnellbau-Knopf, der beim LLM landet, oder ein Such-Knopf, den Vera nicht ohne Modell
 * versteht, fiele sonst erst im Spiel auf.
 */
class HelpBookControlsTest {

    private static final Set<String> ADMIN;

    static {
        Set<String> s = new HashSet<>(HelpBookTest.ALL);
        s.add("baumeister.begleiter.suchen");
        ADMIN = Set.copyOf(s);
    }

    private static HelpBook.Context ctx(Set<String> can, HelpBook.Extras extras) {
        return new HelpBook.Context("Vera", can::contains, List.of("Alex"), List.of("farmwelt"), List.of(),
                List.of(), List.of("turm"), "Kumpel", List.of(), List.of(), List.of(), false, extras);
    }

    private static final HelpBook.Voice VOICE = new HelpBook.Voice(true, "de_DE-thorsten-medium",
            List.of("de_DE-kerstin-low", "de_DE-thorsten-medium", "en_US-ryan-medium"), "", "de_DE-kerstin-low",
            true);

    private static List<String> chapters(List<HelpBook.Page> pages) {
        return pages.stream().map(HelpBook.Page::chapter).distinct().toList();
    }

    private static List<String> chapterCommands(String chapter) {
        return HelpBook.pages(ctx(ADMIN, HelpBook.Extras.NONE)).stream().filter(p -> p.chapter().equals(chapter))
                .flatMap(p -> p.parts().stream()).map(HelpBook.Part::command).filter(c -> c != null)
                .filter(c -> !c.startsWith(HelpBook.PAGE_COMMAND)).toList();
    }

    @Test
    void quickBuildButtonsHitTheFastPathWithoutTheLlm() {
        List<String> quick = chapterCommands("Schnellbau").stream().filter(c -> c.startsWith("/bm bau ein")).toList();
        assertEquals(9, quick.size(), quick.toString());
        for (String c : quick) {
            String prompt = c.substring("/bm bau ".length());
            // Dieselbe Überladung wie BuildService und ClientPlanner: Vorher prüfte der Test die mit einer Namensmenge,
            // die Sonderaufträge (Bunker, Pool, Lobby) gar nicht kennt – ein Lobby-Knopf wäre dort rot, im Spiel grün.
            assertTrue(QuickOrders.match(prompt, ModuleLibrary.EMPTY, 64).isPresent(), "ginge ans LLM: " + prompt);
            // BuildService fragt TunnelOrder vor den Schnellaufträgen (2026-11-16): Ein Schnellbau-Knopf, den der
            // Grabweg abfinge, baute statt der Bauform eine endlose Brücke.
            assertTrue(TunnelOrder.parse(prompt).isEmpty(), "landete beim Graben: " + prompt);
        }
    }

    @Test
    void digButtonsReachTheTunnelPath() {
        List<String> dig = chapterCommands("Graben");
        assertEquals(7, dig.size(), dig.toString());
        for (String c : dig) {
            // So setzt BaumeisterCommand die Befehle in einen Satz um.
            String rest = c.replaceFirst("^/bm \\S+ ?", "");
            String stairs = TunnelOrder.mentionsDown(rest) ? "treppe " : "treppe nach oben ";
            String head = c.startsWith("/bm treppe") ? stairs : c.startsWith("/bm brücke") ? "brücke "
                    : c.startsWith("/bm weiterbauen") ? "bau weiter " : "tunnel ";
            assertTrue(TunnelOrder.parse((head + rest).strip()).isPresent(), "kein Grabauftrag: " + c);
        }
    }

    @Test
    void searchButtonsAreUnderstoodWithoutAModel() {
        List<String> search = chapterCommands("Suchen").stream().filter(c -> c.startsWith("/vera wo ")).toList();
        assertEquals(7, search.size(), search.toString());
        for (String c : search) {
            String said = c.substring("/vera ".length());
            CompanionReply.Reply r = CompanionIntent.command(said, new CompanionIntent.Known(false)).orElse(null);
            assertNotNull(r, "ohne Modell nicht verstanden: " + said);
            CompanionReply.Action a = r.actions().getFirst();
            assertEquals(CompanionReply.Op.FIND, a.op(), said);
            assertTrue(CompanionRules.placeFor(a.block()).isPresent() || CompanionRules.oreFor(a.block()) != null,
                    "weder Ort noch Erz: " + a.block());
        }
    }

    @Test
    void searchNeedsTheRightCollectionNeedsFilesVoiceNeedsAnAdmin() {
        Set<String> player = Set.of("baumeister.begleiter", "baumeister.bauen");
        List<String> plain = chapters(HelpBook.pages(ctx(player, new HelpBook.Extras(0, null))));
        assertFalse(plain.contains("Suchen"), "ohne Recht „Erze suchen“");
        assertFalse(plain.contains("Sammlung"), "ohne Dateien keine Sammlung");
        assertFalse(plain.contains(HelpBook.VOICE_CHAPTER));
        assertTrue(plain.contains("Schnellbau"));
        assertTrue(plain.contains("Mods"), "Mods für jeden");
        List<String> full = chapters(HelpBook.pages(ctx(ADMIN, new HelpBook.Extras(812, VOICE))));
        for (String must : List.of("Suchen", "Sammlung", "Schnellbau", "Mods", HelpBook.VOICE_CHAPTER)) {
            assertTrue(full.contains(must), "fehlt: " + must);
        }
        assertFalse(chapters(HelpBook.pages(ctx(Set.of("baumeister.begleiter"), HelpBook.Extras.NONE)))
                .contains("Schnellbau"), "ohne baumeister.bauen kein Schnellbau");
    }

    @Test
    void voicePageMarksTheChosenVoiceAndSwitchesByClick() {
        List<HelpBook.Page> pages = HelpBook.pages(ctx(ADMIN, new HelpBook.Extras(3, VOICE)));
        List<HelpBook.Part> parts = pages.stream().filter(p -> p.chapter().equals(HelpBook.VOICE_CHAPTER))
                .flatMap(p -> p.parts().stream()).toList();
        // Vera und Baumeister je mit eigener Liste: Vera kerstin ■, Baumeister thorsten ■, Cartoon-Ton ■.
        List<HelpBook.Part> chosen = parts.stream().filter(p -> p.text().startsWith("■ ")).toList();
        assertEquals(List.of("■ Cartoon-Ton\n", "■ kerstin (de)\n", "■ thorsten (de)\n"),
                chosen.stream().map(HelpBook.Part::text).toList());
        assertEquals("/bm sprache ton normal", chosen.get(0).command(), "an → ausschalten");
        assertEquals("/bm sprache vera de_DE-kerstin-low", chosen.get(1).command());
        assertEquals("/bm sprache stimme de_DE-thorsten-medium", chosen.get(2).command());
        assertEquals(4, parts.stream().filter(p -> p.text().startsWith("□ ")).count());
        List<String> cmds = parts.stream().map(HelpBook.Part::command).filter(c -> c != null).toList();
        assertTrue(cmds.contains("/bm sprache aus"), "an → Ausschalten");
        assertTrue(cmds.contains("/bm sprache probe"));
        assertTrue(cmds.contains("/bm sprache probe baumeister"));
        HelpBook.Voice plain = new HelpBook.Voice(true, "a", List.of("a"), "", "a", false);
        assertTrue(HelpBookTest.commands(HelpBook.pages(ctx(ADMIN, new HelpBook.Extras(0, plain))))
                .contains("/bm sprache ton cartoon"), "aus → Cartoon einschalten");
        HelpBook.Voice off = new HelpBook.Voice(false, "", List.of(), "");
        List<String> offCmds = HelpBookTest.commands(HelpBook.pages(ctx(ADMIN, new HelpBook.Extras(0, off))));
        assertTrue(offCmds.contains("/bm sprache an"));
        assertFalse(offCmds.contains("/bm sprache aus"));
    }

    @Test
    void voiceLabelsFitOneLine() {
        assertEquals("thorsten (de)", HelpBook.voiceLabel("de_DE-thorsten-medium"));
        assertEquals("ryan (en)", HelpBook.voiceLabel("en_US-ryan-medium"));
        assertEquals("kerstin (de)", HelpBook.voiceLabel("de_DE-kerstin-low"));
        assertTrue(HelpBook.voiceLabel("x".repeat(40)).length() <= 17);
    }

    /** Mit allen Kapiteln reicht eine Inhaltsseite nicht mehr: Jede Inhaltsseite springt auf Kapitelanfänge. */
    @Test
    void contentsSpanPagesAndEveryChapterIsReachable() {
        List<HelpBook.Page> pages = HelpBook.pages(ctx(ADMIN, new HelpBook.Extras(812, VOICE)));
        List<HelpBook.Part> links = pages.stream().filter(p -> p.chapter().equals("Inhalt"))
                .flatMap(p -> p.parts().stream()).filter(x -> x.text().startsWith("» ")).toList();
        assertTrue(pages.stream().filter(p -> p.chapter().equals("Inhalt")).count() >= 2, "Inhalt über 2 Seiten");
        List<String> reached = links.stream().map(l -> pages.get(l.page() - 1).chapter()).toList();
        List<String> all = chapters(pages).stream().filter(c -> !c.equals("Inhalt") && !c.equals("Mehr")).toList();
        assertEquals(all, reached, "jedes Kapitel genau einmal, in Buchreihenfolge");
        for (HelpBook.Part l : links) {
            HelpBook.Page target = pages.get(l.page() - 1);
            assertEquals("» " + target.chapter() + "\n", l.text());
            assertFalse(pages.get(l.page() - 2).chapter().equals(target.chapter()), "erste Seite: " + l.text());
        }
        for (HelpBook.Page p : pages) {
            assertTrue(HelpBook.fitsPage(p), "zu lang: " + p.chapter() + " – " + HelpBook.lines(p));
            HelpBook.Page content = new HelpBook.Page(p.chapter(), p.parts().subList(0, p.parts().size() - 3));
            assertEquals(HelpBook.CONTENT_LINES, HelpBook.lines(content), "Fußzeile auf Zeile 14: " + p.chapter());
            StringBuilder text = new StringBuilder();
            p.parts().forEach(x -> text.append(x.text()));
            for (String line : text.toString().split("\n")) {
                assertTrue(line.length() <= HelpBook.LINE, "zu lang auf " + p.chapter() + ": „" + line + "“");
            }
        }
    }
}
