package com.blackn0va.baumeister.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.PlanParser;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * AK-21: Ein Gegenstand, der kein Block ist, zählt im Lehrset-Prüfer so, wie das Plugin ihn auf dem Server baut –
 * über BlockRules.resolve (Alias, ItemNames, Ersatzblock), nicht als Block mit eigener Zelle. Vorher kannte der
 * Katalog von PlanJudge jeden Material-Namen (Beifund T-017, 2026-11-21).
 */
class PlanJudgeItemTest {

    private static final PlanJudge JUDGE = new PlanJudge();

    private static String plan(String block) {
        return "{\"design\":\"x\",\"reply\":\"x\",\"mode\":\"new\",\"steps\":["
                + "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]},"
                + "{\"op\":\"line\",\"block\":\"" + block + "\",\"from\":[0,1,2],\"to\":[4,1,2]}]}";
    }

    private static Set<String> cellIds(String json) throws Exception {
        CompiledBuild b = JUDGE.compiler().compile(PlanParser.parse(json));
        Set<String> ids = new TreeSet<>();
        for (CompiledBuild.Action a : b.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                ids.add(c.block().id());
            }
        }
        return ids;
    }

    @Test
    void redstoneItemIsBuiltAsRedstoneWireLikeOnTheServer() throws Exception {
        Set<String> ids = cellIds(plan("redstone"));
        assertFalse(ids.contains("redstone"), ids::toString);
        assertTrue(ids.contains("redstone_wire"), ids::toString);
    }

    @Test
    void itemsThatAreNoBlocksNeverGetTheirOwnCell() throws Exception {
        for (String item : new String[] {"redstone", "string", "wheat_seeds", "stick", "iron_ingot"}) {
            Set<String> ids = cellIds(plan(item));
            assertFalse(ids.contains(item), () -> item + " als Block gezählt: " + ids);
        }
    }

    /** Das Plugin baut den Gegenstand nur mit Warnung – ins Lehrset darf so ein Plan nicht. */
    @Test
    void lessonSetRejectsPlansNamingAnItem() {
        for (String item : new String[] {"redstone", "string", "wheat_seeds"}) {
            Optional<String> why = JUDGE.rejection("eine leitung", plan(item));
            assertTrue(why.isPresent() && why.get().startsWith("warnung: "), () -> item + ": " + why);
        }
    }

    /** Gegenstück: Der Blockname selbst bleibt sauber – der neue Katalog sperrt nicht zu viel. */
    @Test
    void realBlockNamesStayClean() throws Exception {
        assertEquals(Set.of("redstone_wire", "stone"), cellIds(plan("redstone_wire")));
        Optional<String> why = JUDGE.rejection("eine leitung", plan("redstone_wire"));
        assertFalse(why.orElse("").startsWith("warnung: "), why::toString);
    }

    /** Katalog = Freigabeliste der Stufen (tiers/block-ids.txt), nicht eine eigene Liste. */
    @Test
    void catalogIsTheTierAllowList() {
        assertTrue(PlanJudge.SERVER_BLOCKS.isBlock("redstone_wire"));
        assertTrue(PlanJudge.SERVER_BLOCKS.isBlock("air"));
        assertFalse(PlanJudge.SERVER_BLOCKS.isBlock("redstone"));
        assertTrue(PlanJudge.SERVER_BLOCKS.allIds().contains("tripwire"));
        assertFalse(PlanJudge.SERVER_BLOCKS.allIds().contains("wheat_seeds"));
    }
}
