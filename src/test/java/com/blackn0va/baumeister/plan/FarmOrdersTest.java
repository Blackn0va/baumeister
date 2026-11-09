package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Farmen ohne LLM (Wunsch 2026-11-07 – „bau mir eine XP-Farm“ ging ans Modell, das baute 60 fill-Schritte mit
 * eingemauertem Wasser, und die
 * Korrektur sprengte den Kontext; danach: „Weizen-, Eisen- und Hühnerfarm gehen noch übers LLM“). Die Farmen selbst
 * sind auf dem Testserver geprüft (MachineProbe, je mit Gegenprobe „ohne=…“).
 */
class FarmOrdersTest {

    private static final Machines LIB = new Machines();
    private static final PlanCompiler COMPILER = new PlanCompiler(
            id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null, "stone",
            new PlanCompiler.Limits(150_000, 192), LIB);

    private static void assertBuilds(String prompt, String module) throws Exception {
        String json = QuickOrders.match(prompt, LIB, 192).orElseThrow(() -> new AssertionError(prompt));
        BuildPlan plan = PlanParser.parse(json);
        assertEquals("module", plan.steps().getFirst().op().name().toLowerCase(Locale.ROOT), prompt);
        assertEquals(module, plan.steps().getFirst().name(), prompt);
        CompiledBuild b = COMPILER.compile(plan);
        assertTrue(PlanLinter.lint(plan, b, LIB, true).stream().noneMatch(PlanReview::severe), prompt);
    }

    @Test
    void xpFarmOrdersGoStraightToTheTestedFarm() throws Exception {
        for (String prompt : List.of("bau mir eine XP-Farm", "baue eine Mobfarm", "bau mir eine Erfahrungsfarm",
                "eine xp farm bitte", "bau eine Monsterfarm für mich", "bau mir eine Mobfarm ohne Spawner",
                "Bau eine funktionierende EXP-Farm")) {
            assertBuilds(prompt, "xp_farm");
        }
    }

    @Test
    void everyNewFarmHasItsOwnWordsWithoutTheModel() throws Exception {
        Map<String, String> orders = Map.ofEntries(
                Map.entry("bau mir eine Weizenfarm", "weizenfarm"),
                Map.entry("bau eine Weizen-Farm", "weizenfarm"),
                Map.entry("bau mir eine automatische Kornfarm", "weizenfarm"),
                Map.entry("bau mir eine Eisenfarm", "eisenfarm"),
                Map.entry("bau eine iron farm", "eisenfarm"),
                Map.entry("bau mir eine Golemfarm bitte", "eisenfarm"),
                Map.entry("bau eine Hühnerfarm", "huehnerfarm"),
                Map.entry("bau mir eine Eierfarm", "huehnerfarm"),
                Map.entry("bau eine Kürbisfarm", "kuerbisfarm"),
                Map.entry("bau eine Melonen-Farm", "melonenfarm"),
                Map.entry("bau eine Bambusfarm", "bambusfarm"),
                Map.entry("bau mir eine Kaktusfarm", "kaktusfarm"),
                Map.entry("bau einen Bruchsteingenerator", "bruchsteingenerator"),
                Map.entry("bau mir einen Cobblestone-Generator", "bruchsteingenerator"));
        for (Map.Entry<String, String> e : orders.entrySet()) {
            assertBuilds(e.getKey(), e.getValue());
        }
    }

    /**
     * Antwort beim Auftrag (2026-11-08): deutscher Name und was bereitliegen soll (FarmGuide). Vorher „Ich baue dir das
     * Bauteil 'eisenfarm'.“ – und kein Wort zu Betten, Dorfbewohnern oder Zombie.
     */
    @Test
    void farmOrdersAnswerWithTheGermanNameAndWhatToBring() {
        Map<String, String> orders = Map.of("bau mir eine XP-Farm", "xp_farm", "bau mir eine Weizenfarm", "weizenfarm",
                "bau mir eine Eisenfarm", "eisenfarm", "bau eine Hühnerfarm", "huehnerfarm", // umlaut-ok
                "bau eine Kürbisfarm", "kuerbisfarm", "bau eine Melonenfarm", "melonenfarm", // umlaut-ok
                "bau eine Bambusfarm", "bambusfarm", "bau mir eine Kaktusfarm", "kaktusfarm",
                "bau einen Bruchsteingenerator", "bruchsteingenerator");
        assertEquals(new java.util.TreeSet<>(QuickOrders.FARMS.values()), new java.util.TreeSet<>(orders.values()));
        for (Map.Entry<String, String> e : orders.entrySet()) {
            String json = QuickOrders.match(e.getKey(), LIB, 192).orElseThrow(() -> new AssertionError(e.getKey()));
            String reply = com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("reply").getAsString();
            assertEquals(FarmGuide.acceptReply(e.getValue()).orElseThrow(), reply, e.getKey());
            assertTrue(!reply.contains("Bauteil"), reply);
        }
    }

    @Test
    void otherFarmsAndExtraWishesStayWithTheModel() {
        // Zwei Farmen auf einmal, fremde Zusätze und Fragen sind Sache des Modells: Ein Treffer baute sie still ohne
        // den Zusatz („mit Glasdach“, „mit Lava“) oder nur eine der beiden Farmen.
        for (String prompt : List.of("bau eine XP-Farm mit Glasdach", "wie funktioniert eine Mobfarm",
                "bau eine Weizen- und Kürbisfarm", "bau eine Mob-Weizenfarm", "bau mir eine Schweinefarm",
                "bau eine Eisenfarm mit Lava")) {
            assertTrue(QuickOrders.farm(prompt).isEmpty(), prompt);
            assertTrue(QuickOrders.match(prompt, LIB, 192).isEmpty(), prompt);
        }
    }
}
