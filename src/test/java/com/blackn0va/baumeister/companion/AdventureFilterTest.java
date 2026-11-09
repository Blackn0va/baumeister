package com.blackn0va.baumeister.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Im Abenteuer verspricht Vera nichts, was das Plugin danach ablehnt (dev01 2026-11-04 22:24, „zwei Stimmen“). */
class AdventureFilterTest {

    private static CompanionReply.Reply reply(String json) {
        CompanionReply.Reply r = CompanionReply.parse(json);
        assertTrue(r != null, json);
        return r;
    }

    @Test
    void theRepliesFromTheLogLoseTheirForbiddenActions() {
        AdventureFilter.Result day = AdventureFilter.filter(reply("{\"say\": \"Es wird Zeit für das Tageslicht.\", "
                + "\"actions\": [{\"op\": \"command\", \"text\": \"time set day\"}]}").actions());
        assertEquals(1, day.blocked());
        assertTrue(day.kept().isEmpty());
        assertNull(day.ore());
        AdventureFilter.Result diamond = AdventureFilter.filter(reply("{\"say\": \"Ich schaue mich um.\", \"actions\": "
                + "[{\"op\": \"find\", \"block\": \"deepslate_diamond_ore\", \"radius\": 48}]}").actions());
        assertEquals("diamond", diamond.ore());
        assertEquals(1, diamond.blocked());
    }

    @Test
    void helpThatDoesNotCheatStays() {
        AdventureFilter.Result r = AdventureFilter.filter(reply("{\"say\": \"Moment.\", \"actions\": ["
                + "{\"op\": \"calc\", \"expr\": \"3*8\"}, {\"op\": \"recipe\", \"item\": \"furnace\"}, "
                + "{\"op\": \"find\", \"block\": \"oak_log\", \"radius\": 32}]}").actions());
        assertEquals(0, r.blocked());
        assertEquals(3, r.kept().size());
        AdventureFilter.Result mixed = AdventureFilter.filter(reply("{\"say\": \"Hier.\", \"actions\": ["
                + "{\"op\": \"give\", \"item\": \"bread\", \"count\": 16}, {\"op\": \"build\", \"text\": \"ein Haus\"},"
                + " {\"op\": \"mark\", \"name\": \"zuhause\"}]}").actions());
        assertEquals(2, mixed.blocked());
        assertEquals(List.of(CompanionReply.Op.MARK), mixed.kept().stream().map(CompanionReply.Action::op).toList());
    }

    @Test
    void oreHintsNameTheDepthNeverThePlace() {
        assertEquals("Diamanten liegen am meisten um Höhe -58. Du bist auf Höhe 64, also noch 122 Blöcke nach unten. "
                + "Die Stelle verrate ich nicht – das wäre geschummelt.", AdventureFilter.oreHint("diamond", 64));
        assertTrue(AdventureFilter.oreHint("iron", 14).startsWith("Eisen liegt am meisten um Höhe 16. Du bist genau"));
        assertTrue(AdventureFilter.oreHint("coal", 40).contains("56 Blöcke höher"));
        assertEquals(java.util.Optional.of("iron"), AdventureFilter.ore("minecraft:raw_iron_block"));
        assertEquals(java.util.Optional.empty(), AdventureFilter.ore("stone"));
    }
}
