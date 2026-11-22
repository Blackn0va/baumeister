package com.blackn0va.baumeister.town.use;

import com.blackn0va.baumeister.companion.CompanionPath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Welt zum Einstellen für die Benutzen-Tests: Boden bis y=63 (STONE), darüber Luft, einzelne Blöcke gesetzt. Schreibt
 * jeden setOpen-Aufruf mit – auch die verbotenen (kein Tür-Typ), damit „nie Behälter“ prüfbar ist.
 */
public final class FakeUseWorld implements UseWorld {

    public final Map<String, UseWorld.Block> blocks = new HashMap<>();
    /** Jeder Schreibversuch: „open x y z TYP“ / „close …“; „VERBOTEN …“, wenn kein Tür-Typ. */
    public final List<String> writes = new ArrayList<>();
    public final List<String> sounds = new ArrayList<>();
    public final List<double[]> players = new ArrayList<>();
    public final List<String> asked = new ArrayList<>();
    /** Rechteprüfung: true = erlaubt. */
    public Predicate<int[]> rights = p -> true;

    static String key(int x, int y, int z) {
        return x + " " + y + " " + z;
    }

    public FakeUseWorld put(int x, int y, int z, UseWorld.Block b) {
        blocks.put(key(x, y, z), b);
        return this;
    }

    public FakeUseWorld put(int x, int y, int z, String type) {
        return put(x, y, z, UseWorld.Block.of(type));
    }

    /** Tür aus zwei Hälften bei (x, y..y+1, z). */
    public FakeUseWorld door(int x, int y, int z, String type, boolean open) {
        put(x, y, z, new UseWorld.Block(type, "EAST", "BOTTOM", open, false));
        return put(x, y + 1, z, new UseWorld.Block(type, "EAST", "TOP", open, false));
    }

    /** Bett: Kopf bei (x, y, z), Fußteil gegen facing. */
    public FakeUseWorld bed(int x, int y, int z, String facing, boolean occupied) {
        int[] d = UseBlocks.step(facing);
        put(x, y, z, new UseWorld.Block("RED_BED", facing, "HEAD", false, occupied));
        return put(x - d[0], y, z - d[1], new UseWorld.Block("RED_BED", facing, "FOOT", false, occupied));
    }

    @Override
    public String name() {
        return "testwelt";
    }

    @Override
    public UseWorld.Block at(int x, int y, int z) {
        UseWorld.Block b = blocks.get(key(x, y, z));
        if (b != null) {
            return b;
        }
        return UseWorld.Block.of(y <= 63 ? "STONE" : "AIR");
    }

    @Override
    public boolean passable(int x, int y, int z) {
        UseWorld.Block b = at(x, y, z);
        return b.type().equals("AIR") || b.type().endsWith("_CARPET")
                || UseBlocks.door(b.type()) != null && b.open() || b.type().startsWith("IRON_") && b.open();
    }

    @Override
    public boolean solid(int x, int y, int z) {
        return !passable(x, y, z);
    }

    @Override
    public boolean setOpen(int x, int y, int z, boolean open) {
        UseWorld.Block b = at(x, y, z);
        if (UseBlocks.door(b.type()) == null) {
            writes.add("VERBOTEN " + key(x, y, z) + " " + b.type());
            return false;
        }
        writes.add((open ? "open " : "close ") + key(x, y, z) + " " + b.type());
        // Wie Minecraft: die zweite Hälfte folgt.
        for (int dy = -1; dy <= 1; dy++) {
            UseWorld.Block o = blocks.get(key(x, y + dy, z));
            if (o != null && o.type().equals(b.type())) {
                blocks.put(key(x, y + dy, z), new UseWorld.Block(o.type(), o.facing(), o.half(), open, o.occupied()));
            }
        }
        return true;
    }

    @Override
    public boolean mayUse(UUID who, int x, int y, int z) {
        asked.add(key(x, y, z));
        return rights.test(new int[] {x, y, z});
    }

    @Override
    public void sound(String key, double x, double y, double z) {
        sounds.add(key);
    }

    @Override
    public boolean playerNear(double x, double y, double z, double r) {
        for (double[] p : players) {
            double dx = p[0] - x;
            double dy = p[1] - y;
            double dz = p[2] - z;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    public boolean isOpen(int x, int y, int z) {
        return at(x, y, z).open();
    }

    /** Wegsuche-Welt wie CompanionService.pathBlocks ohne Türen: geschlossene Tür = Wand. */
    public CompanionPath.Blocks pathBlocks() {
        return new CompanionPath.Blocks() {
            @Override
            public boolean solid(int x, int y, int z) {
                return FakeUseWorld.this.solid(x, y, z);
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return FakeUseWorld.this.passable(x, y, z);
            }

            @Override
            public boolean water(int x, int y, int z) {
                return false;
            }
        };
    }
}
