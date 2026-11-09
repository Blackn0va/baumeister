package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** Block wie bei /setblock: ID, Zustände, Daten (Plan B2) – Daten nie in asString, Herkunft der Zustände gemerkt. */
class BlockSpecTest {

    @Test
    void parsesStatesAndDataLikeSetblock() throws Exception {
        BlockSpec s = BlockSpec.parse("Minecraft:OAK_SIGN[rotation=4]{front_text:{messages:[\"Hallo Welt\",\"\",\"\","
                + "\"\"]}}");
        assertEquals("oak_sign", s.id());
        assertEquals("4", s.props().get("rotation"));
        assertEquals("minecraft:oak_sign[rotation=4]", s.asString(), "Client-Mod und createBlockData ohne Daten");
        assertEquals("{front_text:{messages:[\"Hallo Welt\",\"\",\"\",\"\"]}}", s.nbtText());
        assertEquals("oak_sign[rotation=4]{front_text:{messages:[\"Hallo Welt\",\"\",\"\",\"\"]}}", s.toPlanText());
        assertFalse(s.trustedNbt(), "aus Plantext");
        assertEquals(Set.of("rotation"), s.explicit());
    }

    @Test
    void dataKeepsItsCaseAndPositionKeysAreDropped() throws Exception {
        BlockSpec cmd = BlockSpec.parseTrusted("command_block[facing=up]{Command:\"say Hallo Heiko\",auto:0b}");
        assertEquals("say Hallo Heiko", ((Nbt.Str) cmd.nbt().get("Command")).value());
        assertTrue(cmd.trustedNbt());
        BlockSpec chest = BlockSpec.parse("chest{id:\"minecraft:chest\",x:1,y:2,z:3,Items:[]}");
        assertEquals(Set.of("Items"), chest.nbt().entries().keySet());
        assertNull(BlockSpec.parse("chest{id:\"minecraft:chest\"}").nbt(), "nur Lage → keine Daten");
    }

    @Test
    void modelTextIsLimitedTo1000CharactersTrustedTextTo32KiB() throws Exception {
        String sign = "oak_sign{front_text:{messages:[\"" + "x".repeat(1100) + "\",\"\",\"\",\"\"]}}";
        assertTrue(assertThrows(PlanException.class, () -> BlockSpec.parse(sign)).getMessage().contains("1000"));
        assertEquals("oak_sign", BlockSpec.parseTrusted(sign).id());
        String huge = "oak_sign{front_text:{messages:[\"" + "x".repeat(40_000) + "\"]}}";
        assertThrows(PlanException.class, () -> BlockSpec.parseTrusted(huge));
    }

    @Test
    void commandsWithCaretsOrSidewaysOffsetsAreRejectedAtParse() {
        assertThrows(PlanException.class, () -> BlockSpec.parse("command_block{Command:\"tp @s ^ ^ ^1\"}"));
        assertThrows(PlanException.class, () -> BlockSpec.parse("command_block{Command:\"setblock ~2 ~ ~ stone\"}"));
        assertThrows(PlanException.class, () -> BlockSpec.parse("command_block{Command:5}"));
        assertThrows(PlanException.class, () -> BlockSpec.parse("chest{Items:[\\u0041]}"));
    }

    @Test
    void addedStatesAreNotExplicitAndExplicitOnesStay() throws Exception {
        BlockSpec log = BlockSpec.parse("oak_log[axis=x]").with("facing", "left");
        assertEquals(Set.of("axis"), log.explicit(), "facing ergänzt (decorate), axis ausdrücklich");
        assertEquals(Set.of("axis", "half"), log.withExplicit("half", "top").explicit());
        assertFalse(log.with("axis", "y").explicit().contains("axis"), "überschrieben = ergänzt");
    }

    @Test
    void equalityComparesStateAndDataButNotOrigin() throws Exception {
        assertEquals(BlockSpec.parse("oak_log[axis=x]"), BlockSpec.of("oak_log").with("axis", "x"));
        assertFalse(BlockSpec.parse("chest{Items:[]}").equals(BlockSpec.parse("chest")), "Daten gehören dazu");
        assertEquals(BlockSpec.parse("chest"), BlockSpec.parse("chest{Items:[{Slot:0b,id:\"minecraft:stone\","
                + "count:1}]}").withoutNbt());
    }

    @Test
    void placeholdersMoveOnlyInsideTheCommand() throws Exception {
        BlockSpec cmd = BlockSpec.parse("command_block{Command:\"setblock {1,0,2} stone\",CustomName:\"{1,0,2}\"}");
        BlockSpec moved = cmd.mapPlaceholders(p -> p.add(10, 0, 0));
        assertEquals("setblock {11,0,2} stone", ((Nbt.Str) moved.nbt().get("Command")).value());
        assertEquals("{1,0,2}", ((Nbt.Str) moved.nbt().get("CustomName")).value(), "nur der Befehl");
    }
}
