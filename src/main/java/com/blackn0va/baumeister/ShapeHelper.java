package com.blackn0va.baumeister;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.GlassPane;
import org.bukkit.block.data.type.Observer;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;

/**
 * Was Minecraft beim Setzen durch einen Spieler selbst ausrechnet, setBlockData aber nicht:
 * Treppenecken und Verbindungen von Zäunen, Scheiben und Mauern.
 *
 * Ohne das stünde laut Vanilla-Logik jede zuletzt gesetzte Glasscheibe einer Reihe als dünner
 * Pfosten da, und Walmdach-Ecken blieben gerade. Berechnet wird nur der neue Block gegen die Nachbarn, die
 * schon stehen; die Nachbarn selbst passt Minecraft über das Nachbar-Update (applyPhysics)
 * an. Die Regeln folgen StairBlock/FenceBlock/IronBarsBlock/WallBlock aus Vanilla, vereinfacht
 * um Sonderfälle (Mauerhöhe "tall", Laub, Barrieren), die bei Bauten kaum vorkommen.
 *
 * Seit 2026-11-08 (Plan B3) nur, was der Plan NICHT ausdrücklich nennt (explicit, Welt-Namen): Eine Treppe mit
 * shape=outer_left aus dem Plan bleibt so, ein Zaun mit north=false bleibt offen. Vorher überschrieb adjust jede Form.
 */
final class ShapeHelper {

    private static final List<BlockFace> HORIZONTAL =
            List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    private ShapeHelper() {
    }

    static BlockData adjust(Block target, BlockData data, Set<String> explicit) {
        if (data instanceof Stairs stairs) {
            return explicit.contains("shape") ? data : stairsShape(target, (Stairs) stairs.clone());
        }
        if (data instanceof Wall wall) {
            return wallShape(target, (Wall) wall.clone(), explicit);
        }
        if (data instanceof Fence || data instanceof GlassPane) {
            return connect(target, (MultipleFacing) data.clone(), explicit);
        }
        if (data instanceof RedstoneWire wire) {
            return wireShape(target, (RedstoneWire) wire.clone(), explicit);
        }
        return data;
    }

    private static String key(BlockFace face) {
        return face.name().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- Redstone (RedStoneWireBlock)

    /**
     * Wie RedStoneWireBlock.getStateForPlacement: je Seite NONE/SIDE/UP aus den Nachbarn, danach
     * die Ergänzung zur Linie (eine Verbindung → Gegenseite auch) bzw. zum Kreuz (keine Verbindung).
     * Leiter-Prüfung über isOccluding statt isRedstoneConductor – für Bauwerke gleichwertig,
     * für Sonderblöcke (Glas, Slabs) nicht exakt.
     */
    private static BlockData wireShape(Block pos, RedstoneWire wire, Set<String> explicit) {
        boolean canClimb = !pos.getRelative(BlockFace.UP).getBlockData().isOccluding();
        boolean[] link = new boolean[4];
        for (int i = 0; i < 4; i++) {
            BlockFace face = HORIZONTAL.get(i);
            RedstoneWire.Connection c = explicit.contains(key(face)) ? wire.getFace(face)
                    : wireSide(pos, face, canClimb);
            wire.setFace(face, c);
            link[i] = c != RedstoneWire.Connection.NONE;
        }
        boolean nsNone = !link[0] && !link[2];
        boolean ewNone = !link[1] && !link[3];
        if (!link[3] && nsNone && !explicit.contains("west")) {
            wire.setFace(BlockFace.WEST, RedstoneWire.Connection.SIDE);
        }
        if (!link[1] && nsNone && !explicit.contains("east")) {
            wire.setFace(BlockFace.EAST, RedstoneWire.Connection.SIDE);
        }
        if (!link[0] && ewNone && !explicit.contains("north")) {
            wire.setFace(BlockFace.NORTH, RedstoneWire.Connection.SIDE);
        }
        if (!link[2] && ewNone && !explicit.contains("south")) {
            wire.setFace(BlockFace.SOUTH, RedstoneWire.Connection.SIDE);
        }
        return wire;
    }

    private static RedstoneWire.Connection wireSide(Block pos, BlockFace dir, boolean canClimb) {
        Block n = pos.getRelative(dir);
        BlockData ns = n.getBlockData();
        if (canClimb && ns.isFaceSturdy(BlockFace.UP, BlockSupport.FULL)
                && n.getRelative(BlockFace.UP).getBlockData() instanceof RedstoneWire) {
            return ns.isFaceSturdy(dir.getOppositeFace(), BlockSupport.FULL)
                    ? RedstoneWire.Connection.UP : RedstoneWire.Connection.SIDE;
        }
        if (wireConnects(ns, dir)
                || !ns.isOccluding() && n.getRelative(BlockFace.DOWN).getBlockData() instanceof RedstoneWire) {
            return RedstoneWire.Connection.SIDE;
        }
        return RedstoneWire.Connection.NONE;
    }

    /** RedStoneWireBlock.shouldConnectTo, Signalquellen über eine feste Liste statt isSignalSource. */
    private static boolean wireConnects(BlockData n, BlockFace dir) {
        if (n instanceof RedstoneWire) {
            return true;
        }
        if (n instanceof Repeater r) {
            return r.getFacing() == dir || r.getFacing() == dir.getOppositeFace();
        }
        if (n instanceof Observer o) {
            return o.getFacing() == dir;
        }
        String id = n.getMaterial().getKey().getKey();
        return id.contains("redstone_torch") || id.contains("redstone_wall_torch") || id.equals("lever")
                || id.endsWith("_button") || id.endsWith("_pressure_plate") || id.equals("redstone_block")
                || id.equals("comparator") || id.equals("daylight_detector") || id.equals("target")
                || id.equals("trapped_chest") || id.equals("tripwire_hook") || id.equals("detector_rail")
                || id.equals("lectern") || id.endsWith("sculk_sensor");
    }

    // ---------------------------------------------------------------- Treppen (StairBlock.getStairsShape)

    private static BlockData stairsShape(Block pos, Stairs s) {
        BlockFace facing = s.getFacing();
        BlockData behind = pos.getRelative(facing).getBlockData();
        if (behind instanceof Stairs b && b.getHalf() == s.getHalf()) {
            BlockFace bf = b.getFacing();
            if (axisOf(bf) != axisOf(facing) && canTakeShape(s, pos, bf.getOppositeFace())) {
                s.setShape(bf == counterClockwise(facing) ? Stairs.Shape.OUTER_LEFT : Stairs.Shape.OUTER_RIGHT);
                return s;
            }
        }
        BlockData front = pos.getRelative(facing.getOppositeFace()).getBlockData();
        if (front instanceof Stairs f && f.getHalf() == s.getHalf()) {
            BlockFace ff = f.getFacing();
            if (axisOf(ff) != axisOf(facing) && canTakeShape(s, pos, ff)) {
                s.setShape(ff == counterClockwise(facing) ? Stairs.Shape.INNER_LEFT : Stairs.Shape.INNER_RIGHT);
                return s;
            }
        }
        s.setShape(Stairs.Shape.STRAIGHT);
        return s;
    }

    private static boolean canTakeShape(Stairs s, Block pos, BlockFace dir) {
        BlockData other = pos.getRelative(dir).getBlockData();
        return !(other instanceof Stairs o) || o.getFacing() != s.getFacing() || o.getHalf() != s.getHalf();
    }

    private static char axisOf(BlockFace f) {
        return f.getModX() != 0 ? 'x' : 'z';
    }

    private static BlockFace counterClockwise(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.WEST;
            case WEST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.EAST;
            default -> BlockFace.NORTH;
        };
    }

    // ---------------------------------------------------------------- Zäune und Scheiben

    private static BlockData connect(Block pos, MultipleFacing data, Set<String> explicit) {
        for (BlockFace face : HORIZONTAL) {
            if (!data.getAllowedFaces().contains(face) || explicit.contains(key(face))) {
                continue;
            }
            Block n = pos.getRelative(face);
            data.setFace(face, connectsTo(data, n, face.getOppositeFace()));
        }
        return data;
    }

    private static boolean connectsTo(BlockData self, Block neighbour, BlockFace towardSelf) {
        BlockData n = neighbour.getBlockData();
        Material m = n.getMaterial();
        if (self instanceof Fence) {
            if (n instanceof Fence) {
                // Holzzäune verbinden sich untereinander, der Netherziegelzaun nur mit sich selbst.
                return Tag.WOODEN_FENCES.isTagged(m) == Tag.WOODEN_FENCES.isTagged(self.getMaterial());
            }
            if (n instanceof Gate gate) {
                return axisOf(gate.getFacing()) != axisOf(towardSelf);
            }
        } else {
            if (n instanceof GlassPane || n instanceof Wall) {
                return true;
            }
        }
        return isSturdy(n, towardSelf, m);
    }

    private static boolean isSturdy(BlockData n, BlockFace face, Material m) {
        return !Tag.LEAVES.isTagged(m) && m != Material.BARRIER && n.isFaceSturdy(face, BlockSupport.FULL);
    }

    // ---------------------------------------------------------------- Mauern

    private static BlockData wallShape(Block pos, Wall wall, Set<String> explicit) {
        int connected = 0;
        boolean northSouth = true;
        boolean eastWest = true;
        for (BlockFace face : HORIZONTAL) {
            boolean link;
            if (explicit.contains(key(face))) {
                link = wall.getHeight(face) != Wall.Height.NONE;
            } else {
                BlockData n = pos.getRelative(face).getBlockData();
                link = n instanceof Wall || n instanceof GlassPane || n instanceof Fence
                        || (n instanceof Gate g && axisOf(g.getFacing()) != axisOf(face))
                        || isSturdy(n, face.getOppositeFace(), n.getMaterial());
                wall.setHeight(face, link ? Wall.Height.LOW : Wall.Height.NONE);
            }
            if (link) {
                connected++;
                if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
                    eastWest = false;
                } else {
                    northSouth = false;
                }
            }
        }
        // Mittelpfosten nur an Enden, Ecken und Kreuzungen – eine gerade Mauer ist glatt.
        boolean straight = connected == 2 && (northSouth || eastWest);
        if (!explicit.contains("up")) {
            wall.setUp(!straight || !pos.getRelative(BlockFace.UP).getType().isAir());
        }
        return wall;
    }
}
