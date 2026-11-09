package com.blackn0va.baumeister.structure;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.nio.file.Files;
import java.nio.file.Path;

/** Testhilfe für andere Pakete: das Testhaus aus PlanDecompilerTest als .schem-Datei (Vorderseite front). */
public final class VorbildFiles {

    private VorbildFiles() {
    }

    public static void writeHouse(Path file, Cardinal front) throws Exception {
        var local = PlanDecompilerTest.build(PlanDecompilerTest.HOUSE);
        var o = new com.blackn0va.baumeister.plan.Orientation(front.opposite());
        java.util.Map<com.blackn0va.baumeister.plan.Pos, com.blackn0va.baumeister.plan.BlockSpec> world =
                new java.util.HashMap<>();
        int mx = Integer.MAX_VALUE;
        int my = Integer.MAX_VALUE;
        int mz = Integer.MAX_VALUE;
        for (var p : local.keySet()) {
            var w = o.toWorldOffset(p);
            mx = Math.min(mx, w.x());
            my = Math.min(my, w.y());
            mz = Math.min(mz, w.z());
        }
        for (var e : local.entrySet()) {
            world.put(o.toWorldOffset(e.getKey()).add(-mx, -my, -mz), o.toWorld(e.getValue()));
        }
        Files.write(file, NbtWriter.sponge(world, 2));
    }
}
