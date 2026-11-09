package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Befehle in Bauplänen: Platzhalter statt relativer Koordinaten, die nicht mit dem Bau drehen (Plan B2). */
class CommandTextTest {

    @Test
    void placeholdersAreFoundAndRenderedAsWorldCoordinates() {
        String cmd = "setblock {1,2,3} minecraft:stone replace && tp @p {@-4,0,7}";
        assertEquals(List.of(new CommandText.Placeholder(false, new Pos(1, 2, 3)),
                new CommandText.Placeholder(true, new Pos(-4, 0, 7))), CommandText.placeholders(cmd));
        String world = CommandText.render(cmd, p -> new int[] {p.x() + 100, p.y() + 64, p.z() - 10});
        assertEquals("setblock 101 66 -7 minecraft:stone replace && tp @p 96.5 64.5 -3.5", world);
    }

    @Test
    void verticalOffsetsAreAllowedButSidewaysOffsetsAndCaretsAreNot() throws Exception {
        CommandText.check("tp @s ~ ~5 ~");
        CommandText.check("setblock ~ ~-1 ~0 stone");
        CommandText.check("say Hallo ~ Welt");
        CommandText.check("fill {0,0,0} {3,1,3} air");
        assertTrue(assertThrows(PlanException.class, () -> CommandText.check("setblock ~1 ~ ~ stone")).getMessage()
                .contains("~1"));
        assertThrows(PlanException.class, () -> CommandText.check("setblock ~ ~ ~-2 stone"));
        assertThrows(PlanException.class, () -> CommandText.check("summon zombie ^ ^ ^1"));
        assertThrows(PlanException.class, () -> CommandText.check("execute positioned ^ ^1 ^ run say x"));
        // Drehwinkel drehen ebenso wenig mit dem Bau.
        assertThrows(PlanException.class, () -> CommandText.check("tp @s ~ ~ ~ ~90 ~"));
        assertThrows(PlanException.class, () -> CommandText.check("say a\u0007b"));
        assertThrows(PlanException.class, () -> CommandText.check("say " + "x".repeat(CommandText.MAX_LENGTH)));
    }

    @Test
    void placeholdersFollowTransformsAndKeepTheirForm() {
        String moved = CommandText.mapPositions("clone {0,0,0} {2,1,0} {@5,0,0}", p -> new Pos(4 - p.x(), p.y(),
                p.z()));
        assertEquals("clone {4,0,0} {2,1,0} {@-1,0,0}", moved);
        assertEquals("tellraw @a {\"text\":\"{x,y,z}\"}", CommandText.mapPositions(
                "tellraw @a {\"text\":\"{x,y,z}\"}", p -> new Pos(0, 0, 0)), "nur Zahlen sind Platzhalter");
    }
}
