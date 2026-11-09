package com.blackn0va.baumeister.help;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** Rechte und Charakter im Buch: nur für Admins, alles passt auf die Seiten, Knopf und Beschriftung bleiben beisammen. */
class HelpBookRightsTest {

    private static final String[] KEYS = {"geben", "suchen", "reisen", "befehle", "reparieren", "kisten", "benutzen",
        "ausprobieren", "frech"};

    private static HelpBook.Context ctx(Set<String> can, int players) {
        List<HelpBook.Right> rights = new ArrayList<>();
        for (String k : KEYS) {
            rights.add(new HelpBook.Right(k, "Fremde Kisten", "Ops"));
        }
        return new HelpBook.Context("Vera", can::contains, List.of(), List.of(), List.of(), List.of(), List.of(),
                "Anpassen", rights, List.of("Steve: Geben ✘"),
                IntStream.range(0, players).mapToObj(i -> "Spieler" + i).toList());
    }

    @Test
    void adminSeesRightsWithThreeButtonsEach() {
        List<HelpBook.Page> pages = HelpBook.pages(ctx(Set.of("baumeister.begleiter", "baumeister.admin",
                "baumeister.begleiter.frech"), 30));
        List<String> cmds = HelpBookTest.commands(pages);
        for (String k : KEYS) {
            for (String l : List.of("alle", "ops", "aus")) {
                assertTrue(cmds.contains(HelpBook.RIGHT_COMMAND + k + " " + l), k + " " + l);
            }
        }
        assertTrue(cmds.contains(HelpBook.PLAYER_RIGHTS_COMMAND + "Spieler0"));
        assertTrue(cmds.contains(HelpBook.CHARACTER_COMMAND + "frech"));
        pages.forEach(p -> assertTrue(HelpBook.fitsPage(p), p.chapter() + ": " + HelpBook.lines(p)));
    }

    @Test
    void labelAndButtonsStayOnOnePage() {
        for (HelpBook.Page p : HelpBook.pages(ctx(Set.of("baumeister.admin"), 30))) {
            List<HelpBook.Part> parts = p.parts();
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).text().endsWith(": Ops")) {
                    assertTrue(i + 3 < parts.size() && parts.get(i + 3).text().endsWith("\n"),
                            "Knöpfe auf derselben Seite wie " + parts.get(i).text());
                }
            }
        }
    }

    @Test
    void playersDoNotSeeRightsAndWithoutFrechNoCheekyButton() {
        List<String> cmds = HelpBookTest.commands(HelpBook.pages(ctx(Set.of("baumeister.begleiter"), 3)));
        assertFalse(cmds.stream().anyMatch(c -> c.startsWith(HelpBook.RIGHT_COMMAND)));
        assertFalse(cmds.contains(HelpBook.CHARACTER_COMMAND + "frech"));
        assertTrue(cmds.contains(HelpBook.CHARACTER_COMMAND + "kumpel"));
    }

    @Test
    void playerPageListsEveryRightAndFits() {
        List<HelpBook.PlayerRight> list = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            list.add(new HelpBook.PlayerRight("r" + i, "Warps o. Limit", i % 3 == 0 ? null : i % 3 == 1, i % 2 == 0));
        }
        List<HelpBook.Page> pages = HelpBook.playerRights("Steve", list);
        List<String> cmds = HelpBookTest.commands(pages);
        for (int i = 0; i < 19; i++) {
            assertTrue(cmds.contains(HelpBook.RIGHT_COMMAND + "r" + i + " verbieten Steve"));
        }
        pages.forEach(p -> assertTrue(HelpBook.fitsPage(p), p.chapter() + ": " + HelpBook.lines(p)));
        assertFalse(HelpBookTest.commands(HelpBook.playerRights("x op Steve", list)).stream()
                .anyMatch(c -> c.contains("x op")), "Name mit Leerzeichen ergibt keinen Befehl (OWASP A03)");
    }
}
