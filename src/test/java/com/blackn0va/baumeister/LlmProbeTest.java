package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.dataset.PlanJudge;
import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Machines;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanParser;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * LlmProbe: Gegenstand ist kein Block (AK-28). Die Probe soll denselben Compiler messen wie der Server – vorher
 * zählte ihr Katalog (Material.getMaterial != null) jeden Gegenstand als Block, wie PlanJudge vor T-019.
 */
class LlmProbeTest {

    private static final PlanCompiler COMPILER = LlmProbe.compiler(new Machines());

    private static Set<String> cellIds(String block) throws Exception {
        String json = "{\"design\":\"x\",\"reply\":\"x\",\"mode\":\"new\",\"steps\":["
                + "{\"op\":\"fill\",\"block\":\"stone\",\"from\":[0,0,0],\"to\":[4,0,4]},"
                + "{\"op\":\"line\",\"block\":\"" + block + "\",\"from\":[0,1,2],\"to\":[4,1,2]}]}";
        CompiledBuild b = COMPILER.compile(PlanParser.parse(json));
        Set<String> ids = new TreeSet<>();
        for (CompiledBuild.Action a : b.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                ids.add(c.block().id());
            }
        }
        return ids;
    }

    @Test
    void probeUsesTheServerCatalogOfPlanJudge() {
        assertSame(PlanJudge.SERVER_BLOCKS, LlmProbe.CATALOG);
        assertFalse(LlmProbe.CATALOG.isBlock("redstone"), "redstone ist ein Gegenstand");
        assertTrue(LlmProbe.CATALOG.isBlock("redstone_wire"));
    }

    @Test
    void itemIsNoBlockInTheProbe() throws Exception {
        for (String item : new String[] {"redstone", "string", "wheat_seeds"}) {
            Set<String> ids = cellIds(item);
            assertFalse(ids.contains(item), () -> item + " als Block gezählt: " + ids);
        }
        assertTrue(cellIds("redstone").contains("redstone_wire"), "Alias wie am Server");
    }
}
