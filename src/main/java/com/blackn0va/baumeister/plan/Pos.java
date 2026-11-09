package com.blackn0va.baumeister.plan;

/**
 * Ganzzahlige Blockposition. Im Bauplan lokal (x rechts, y hoch, z vom Spieler weg),
 * nach {@link Orientation#toWorldOffset} ein Versatz in Weltachsen.
 */
public record Pos(int x, int y, int z) {

    public Pos add(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public Pos up() {
        return add(0, 1, 0);
    }

    public Pos down() {
        return add(0, -1, 0);
    }

    public long distanceSquared(Pos other) {
        long dx = x - other.x;
        long dy = y - other.y;
        long dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
