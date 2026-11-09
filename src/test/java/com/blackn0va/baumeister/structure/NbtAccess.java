package com.blackn0va.baumeister.structure;

import com.blackn0va.baumeister.plan.BlockSpec;
import com.blackn0va.baumeister.plan.Pos;

import java.io.IOException;
import java.util.Map;

/** Öffentlicher Zugang zu NbtWriter für Tests außerhalb dieses Pakets (FileSchematicsTest). */
public final class NbtAccess {

    private NbtAccess() {
    }

    public static byte[] structureNbt(Map<Pos, BlockSpec> cells) throws IOException {
        return NbtWriter.structureNbt(cells);
    }
}
