package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SpokenWordsTest {

    @Test
    void misheardRedstoneIsRepaired() {
        assertEquals("Baue mir ein Redstone Computer.", SpokenWords.fix("Baue mir ein Redstone Computer."));
        assertEquals("einen Redstone-Computer", SpokenWords.fix("einen Rapstone-Computer"));
        assertEquals("eine redstone lampe", SpokenWords.fix("eine rädstone lampe"));
        assertEquals("Redstone Uhr", SpokenWords.fix("Retstone Uhr"));
        assertEquals("aus Redstone", SpokenWords.fix("aus Redstein"));
    }

    @Test
    void realWordsStay() {
        for (String s : List.of("ein Haus aus Stein", "Rosenstock", "ein Rundturm", "Restaurant", "Ruhestand",
                "rost", "rasten")) {
            assertEquals(s, SpokenWords.fix(s));
        }
    }

    @Test
    void misheardComputerReachesTheWorkshop() {
        // Gegenprobe zum Vorfall 16:09: Ohne die Korrektur fand QuickOrders den Auftrag nicht.
        String heard = "einen Rapstone-Computer";
        Optional<String> plan = QuickOrders.match(SpokenWords.fix(heard), new Machines(), 192);
        assertTrue(plan.isPresent());
        assertTrue(plan.get().contains("processor workshop"));
        assertTrue(QuickOrders.match(heard, new Machines(), 192).isEmpty(), "ohne fix: LLM-Weg wie im Vorfall");
    }

    @Test
    void pullAfterAnArticleIsAPool() {
        // dev01 2026-11-04 19:10: „Bauen wir ein Pull.“
        assertEquals("bau ein Pool", SpokenWords.fix("bau ein Pull"));
        assertEquals("einen großen Pool bitte", SpokenWords.fix("einen großen Pul bitte"));
        assertEquals("Pull-Request und Pullover", SpokenWords.fix("Pull-Request und Pullover"));
        assertTrue(QuickOrders.match(SpokenWords.fix("bau ein Pull"), new Machines(), 192).isPresent());
    }

    @Test
    void fortMaxIsFortKnox() {
        // dev01 2026-11-04 20:24: „bau mir fort, Max“ – gemeint war Fort Knox, das LLM baute das Haus um.
        assertEquals("bau mir Fort Knox", SpokenWords.fix("bau mir fort, Max"));
        assertEquals("bau mir Fort Knox", SpokenWords.fix("bau mir Fortnox"));
        assertEquals("Fort Knox hier", SpokenWords.fix("Fort Nocks hier"));
        assertEquals("geh fort", SpokenWords.fix("geh fort"));
        assertEquals("Fortschritt", SpokenWords.fix("Fortschritt"));
        assertTrue(QuickOrders.match(SpokenWords.fix("bau mir fort, Max"), new Machines(), 192).isPresent());
    }
}
