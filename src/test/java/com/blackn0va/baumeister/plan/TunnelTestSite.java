package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.blackn0va.baumeister.plan.TunnelShape.Ground;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Testboden für TunnelShape und TunnelScan: Stein bis zur Oberfläche top(z), darüber Himmel (Luft); Ausnahmen je
 * Stelle. built() legt einen Bau Aktion für Aktion darüber – samt Zuständen, damit TunnelScan Treppen erkennt.
 */
final class TunnelTestSite implements TunnelShape.Site {

    final Map<Pos, Ground> ground = new HashMap<>();
    final Map<Pos, String> ids = new HashMap<>();
    IntUnaryOperator top = z -> 50;

    @Override
    public Ground at(Pos p) {
        Ground g = ground.get(p);
        return g != null ? g : sky(p) ? Ground.OPEN : Ground.SOLID;
    }

    @Override
    public boolean sky(Pos p) {
        return p.y() > top.applyAsInt(p.z());
    }

    @Override
    public String id(Pos p) {
        String id = ids.get(p);
        if (id != null) {
            return id;
        }
        return switch (at(p)) {
            case SOLID -> "stone";
            case OPEN -> "air";
            case FLUID -> "water";
            case BUILT -> "oak_planks";
            case FORBIDDEN -> "bedrock";
        };
    }

    TunnelTestSite set(Pos p, Ground g, String id) {
        ground.put(p, g);
        ids.put(p, id);
        return this;
    }

    TunnelTestSite box(int x0, int x1, int y0, int y1, int z0, int z1, Ground g, String id) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    set(new Pos(x, y, z), g, id);
                }
            }
        }
        return this;
    }

    /** Meer: Wasser von floor + 1 bis surface, darunter Stein, darüber Luft – für x und z in den Grenzen. */
    TunnelTestSite sea(int x0, int x1, int z0, int z1, int floor, int surface) {
        top = z -> floor;
        box(x0, x1, floor + 1, surface, z0, z1, Ground.FLUID, "water");
        return this;
    }

    /** Welt nach dem Bau: was der Bau setzt, mit Zuständen; alles andere wie der Boden. */
    Map<Pos, BlockSpec> built(TunnelShape.Result r) {
        Map<Pos, BlockSpec> world = new HashMap<>();
        for (Action a : r.build().actions()) {
            for (Change c : a.changes()) {
                world.put(c.pos(), c.block());
            }
        }
        return world;
    }

    BlockSpec block(Map<Pos, BlockSpec> world, Pos p) {
        BlockSpec b = world.get(p);
        return b != null ? b : BlockSpec.of(id(p));
    }
}
