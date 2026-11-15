package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.ModuleLibrary;
import com.blackn0va.baumeister.plan.QuickOrders;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** /bm einrichten: Jeder Knopf tut, was er verspricht – sonst fiele es erst auf einem frischen Server auf. */
class SetupGuideTest {

    private static final Pattern FRAMES = Pattern.compile("(\\d+) Portalrahmen");

    @Test
    void everyBuildButtonIsBuiltWithoutTheLlm() {
        List<String> orders = SetupGuide.buildOrders("bm");
        assertEquals(8, orders.size(), orders.toString());
        for (String order : orders) {
            // Wie BuildService: Bibliothek und bau.max-ausdehnung der mitgelieferten Config (192).
            assertTrue(QuickOrders.match(order, ModuleLibrary.EMPTY, 192).isPresent(), "ginge ans LLM: " + order);
        }
    }

    @Test
    void thePortalCountInTheHoverIsWhatGetsBuilt() {
        int checked = 0;
        for (SetupGuide.Step s : SetupGuide.steps("bm")) {
            for (Texts.Choice c : s.buttons()) {
                Matcher m = FRAMES.matcher(c.hover());
                if (!m.find()) {
                    continue;
                }
                Optional<String> json = QuickOrders.match(c.command().substring("/bm bau ".length()),
                        ModuleLibrary.EMPTY, 192);
                assertTrue(json.orElseThrow().contains(m.group(1) + " unlit obsidian portal frame"),
                        c.label() + ": " + c.hover() + " – Plan: " + json.get().substring(0, 160));
                checked++;
            }
        }
        assertEquals(5, checked, "vier Lobbys und die Portalhalle");
    }

    @Test
    void theOtherButtonsAreCommandsThatExist() {
        List<String> known = BaumeisterCommand.complete(new String[] {""}, true, List.of(), List.of(), List.of());
        for (SetupGuide.Step s : SetupGuide.steps("bm")) {
            for (Texts.Choice c : s.buttons()) {
                String sub = c.command().substring("/bm ".length()).split(" ")[0];
                assertTrue(known.contains(sub), c.command());
            }
        }
    }

    @Test
    void theSpawnLooksAlongTheNearestCompassDirection() {
        assertEquals("Süden", SetupGuide.direction(0));
        assertEquals("Westen", SetupGuide.direction(90));
        assertEquals("Norden", SetupGuide.direction(180));
        assertEquals("Osten", SetupGuide.direction(270));
        assertEquals("Osten", SetupGuide.direction(-90));
    }
}
