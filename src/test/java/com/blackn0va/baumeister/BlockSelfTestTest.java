package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.BlockTiers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Selbsttest „jeder Block“ (Plan B9) ohne Server: die Listen lesen sich vollständig, jedes Datenbeispiel geht durch den
 * strengen Leser, und was nicht ins Stabil-Raster darf, gibt es in der Liste wirklich.
 */
class BlockSelfTestTest {

    private static final Path RES = Path.of("src/test/resources/blockstates");

    @Test
    void stateListsReadCompletely() throws Exception {
        assertEquals(32_366, BlockSelfTest.readStates(RES.resolve("26.2.txt.gz")).size());
        assertEquals(29_671, BlockSelfTest.readStates(RES.resolve("1.21.11.txt.gz")).size());
    }

    @Test
    void everyDataSampleParsesStrictlyAndIsRewrittenUnchanged() throws Exception {
        for (Map.Entry<String, String> e : BlockSelfTest.DATA_SAMPLES.entrySet()) {
            String block = e.getKey();
            assertNotNull(BlockSelfTest.sampleData(block, e.getValue()), block);
            String once = BlockSelfTest.sampleText(block, e.getValue());
            assertEquals(once, BlockSelfTest.sampleText(block, once), "neu geschrieben ist stabil: " + block);
            BlockTiers.Verdict v = BlockTiers.place(BlockSpec.parseTrusted(block + e.getValue()),
                    BlockTiers.WorldFacts.OVERWORLD);
            assertFalse(v.reason().startsWith("ungeprüfter Schlüssel"), block + ": " + v.reason());
        }
    }

    @Test
    void gridExclusionsExistInTheList() throws Exception {
        List<String> states = BlockSelfTest.readStates(RES.resolve("26.2.txt.gz"));
        for (String id : BlockSelfTest.NOT_IN_GRID) {
            assertTrue(states.stream().anyMatch(s -> s.equals(id) || s.startsWith(id + "[")), id);
        }
    }
}
