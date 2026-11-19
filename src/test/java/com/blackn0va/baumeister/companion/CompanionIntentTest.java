package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionReply.Op;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Vera ohne Modell, an echten Sätzen aus dem Log von dev01 (bis 2026-11-07). Jeder dieser Sätze ging vorher ans Modell –
 * ohne Baumeister-App hieß die Antwort nur „Ich kann gerade nicht nachdenken“.
 */
class CompanionIntentTest {

    private static final CompanionIntent.Known SURVIVAL = new CompanionIntent.Known(false);

    private static List<String> commands(String text) {
        return CompanionIntent.command(text, SURVIVAL).map(r -> r.actions().stream()
                .filter(a -> a.op() == Op.COMMAND).map(CompanionReply.Action::text).toList()).orElse(List.of());
    }

    private static Optional<Op> op(String text) {
        return CompanionIntent.command(text, SURVIVAL).flatMap(r -> r.actions().stream().findFirst())
                .map(CompanionReply.Action::op);
    }

    @Test
    void dayAndNightFromTheLog() {
        for (String s : List.of("Tageslicht.", "Tageslicht?", "Wäre Tageslicht.", "macht Tag.", "macht Tageslicht.",
                "Kannst du Tag machen?", "Okay, mach wieder Tag.")) {
            assertEquals(List.of("time set day"), commands(s), s);
        }
        assertEquals(List.of("time set night"), commands("mach Nacht"));
    }

    @Test
    void weatherFromTheLog() {
        assertEquals(List.of("weather rain"), commands("Ich will Regen."));
        assertEquals(List.of("weather rain"), commands("Kannst du es regnen lassen?"));
        assertEquals(List.of("weather rain"), commands("kannst du schneien lassen?"));
        assertEquals(List.of("weather clear", "time set day"), commands("kannst du die Sonne scheinen lassen?"));
        assertEquals(Optional.of(Op.WEATHER), op("Wie ist das Wetter?"));
    }

    @Test
    void wordsInsideOtherSentencesAreNoCommand() {
        for (String s : List.of("Ich will morgen spielen", "kannst du mir jeden Tag Brot geben", "Ich mag keinen Regen",
                "Guten Tag Vera", "zum Beispiel ein Haus", "Wie spät ist es morgen in Berlin")) {
            assertTrue(commands(s).isEmpty() && op(s).filter(o -> o == Op.GOTO).isEmpty(), s);
        }
    }

    @Test
    void travelFindFlySummon() {
        for (String s : List.of("bring mich zur Farmwelt.", "Bring mich in die Plottwelt.", "In die Farmwelt.",
                "bring mich nach Hause.")) {
            assertEquals(Optional.of(Op.GOTO), op(s), s);
        }
        assertEquals("Farmwelt", CompanionIntent.command("In die Farmwelt.", SURVIVAL).orElseThrow().actions()
                .getFirst().name());
        assertEquals(Optional.of(Op.FIND), op("Wo finde ich Diamant?"));
        assertEquals(Optional.of(Op.GIVE), op("Kannst du machen, dass ich fliegen kann?"));
        assertTrue(CompanionIntent.command("Ich will fliegen.", new CompanionIntent.Known(true)).orElseThrow()
                .actions().isEmpty(), "im Kreativmodus nichts geben, nur erklären");
        assertEquals(List.of("summon zombie ~2 ~ ~"), commands("Kannst du ein Zombie spawnen?"));
    }

    @Test
    void chatWhenTheModelIsAway() {
        assertTrue(CompanionIntent.chat("Gut, danke. Und dir?").orElseThrow().contains("prima"));
        assertTrue(CompanionIntent.chat("Was könntest du mir bauen?").orElseThrow().contains("Baumeister"));
        assertTrue(CompanionIntent.chat("Kannst du mir helfen?").isPresent());
        assertTrue(CompanionIntent.chat("Kannst mich hören.").orElseThrow().contains("höre"));
        assertEquals("Alles klar!", CompanionIntent.chat("Ja.").orElseThrow());
        assertTrue(CompanionIntent.chat("kannst du meine Eier lutschen?").orElseThrow().startsWith("Darauf antworte"));
        assertTrue(CompanionIntent.chat("Erzähl mir die Geschichte von Rom").isEmpty(), "Unbekanntes bleibt beim Modell");
    }

    @Test
    void repairAndHowToAreUnderstoodWithoutTheModel() {
        for (String s : List.of("kannst du das hier reparieren?", "Herr Vera, kannst du das reparieren, die Anlage?",
                "Du sollst das aber reparieren hier.", "kannst du die Maschine hier reparieren?")) {
            assertTrue(CompanionChat.askFix(s), s);
        }
        assertTrue(CompanionChat.askRedstoneRepair("kannst du die Maschine hier reparieren?"));
        for (String s : List.of("wie funktioniert der Computer?", "Wie benutze ich den Computer?",
                "Ich versteh nicht, wie der Computer funktioniert.")) {
            assertTrue(CompanionChat.askHowTo(s), s);
        }
        assertEquals("Alles klar!", CompanionIntent.chat("Ja, bitte.").orElseThrow());
        assertEquals(Optional.of(Op.GOTO), op("Bring dich in die Farbenwelt"), "verhört: dich statt mich");
    }

    @Test
    void nearestWarpForMisheardNames() {
        List<String> warps = List.of("farmwelt", "plotwelt", "spawn", "netherhub");
        assertEquals("farmwelt", CompanionIntent.nearest("Farbenwelt", warps));
        assertEquals("plotwelt", CompanionIntent.nearest("Plottwelt", warps));
        assertEquals(null, CompanionIntent.nearest("Wüste", warps));
    }

    @Test
    void oreWordsMeanTheOre() {
        assertEquals("diamond_ore", CompanionRules.oreFor("Diamant"));
        assertEquals("iron_ore", CompanionRules.oreFor("eisen"));
        assertEquals(null, CompanionRules.oreFor("Dorf"));
    }

    /** Auf „was kann der Baumeister bauen?“ nennt Vera auch Tunnel und Treppen – sie fehlten seit 2026-11-16. */
    @Test
    void buildAnswerNamesTunnelsAndStairs() {
        String a = CompanionIntent.chat("Was kann der Baumeister alles bauen?").orElseThrow();
        assertTrue(a.contains("Tunnel"), a);
        assertTrue(a.contains("Treppen"), a);
        assertTrue(a.contains("Brücken"), a);
    }
}
