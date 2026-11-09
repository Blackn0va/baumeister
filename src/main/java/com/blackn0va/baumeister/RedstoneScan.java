package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.Pos;
import com.blackn0va.baumeister.plan.SiteSurvey;
import com.blackn0va.baumeister.redstone.RedstoneReader;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Liest Redstone aus der Welt für RedstoneReader: um den Spieler (Vera) oder im Scan-Kasten des Bauplatzes
 * (Baumeister). Den vollen BlockData-Text gibt es nur für Bauteile; ihre Nachbarn kommen als reine ID mit – das reicht
 * für „die Fackel hängt an Stein“ und spart unter Tage Tausende getAsString-Aufrufe für Stein.
 *
 * Threads: Hauptthread (Blöcke lesen). Fehler: keine; ungeladene Chunks im Umkreis werden übersprungen.
 */
final class RedstoneScan {

    /** Um den Spieler: 10 Blöcke zur Seite, 6 hoch und tief – eine übliche Schaltung, ohne die halbe Basis. */
    static final int RADIUS = 10;
    static final int HEIGHT = 6;
    /** So viel Text bekommt das Modell höchstens über die Schaltungen (14B mit 8192 Token Kontext). */
    static final int MAX_CHARS = 1500;

    private static final Map<String, String> COMPASS = Map.of("north", "Norden", "south", "Süden", "east", "Osten",
            "west", "Westen", "up", "oben", "down", "unten");

    private RedstoneScan() {
    }

    static String compass(String facing) {
        return COMPASS.getOrDefault(facing, facing);
    }

    /** Um den Block, auf den der Spieler schaut, wenn das ein Bauteil ist; sonst um seine Füße. Weltkoordinaten. */
    static RedstoneReader.Report aroundPlayer(Player p) {
        Block look = p.getTargetBlockExact(8);
        Location c = look != null && RedstoneReader.isPart(look.getType().getKey().getKey()) ? look.getLocation()
                : p.getLocation();
        World w = c.getWorld();
        int cx = c.getBlockX();
        int cy = c.getBlockY();
        int cz = c.getBlockZ();
        Map<Pos, String> blocks = new HashMap<>();
        for (int x = cx - RADIUS; x <= cx + RADIUS; x++) {
            for (int z = cz - RADIUS; z <= cz + RADIUS; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = Math.max(w.getMinHeight(), cy - HEIGHT); y <= Math.min(w.getMaxHeight() - 1, cy + HEIGHT);
                        y++) {
                    Block b = w.getBlockAt(x, y, z);
                    String id = b.getType().getKey().getKey();
                    if (RedstoneReader.isPart(id)) {
                        blocks.put(new Pos(x, y, z), b.getBlockData().getAsString());
                        neighbours(blocks, x, y, z, pos -> {
                            Block n = w.getBlockAt(pos.x(), pos.y(), pos.z());
                            return n.getType().isAir() ? null : n.getType().getKey().getKey();
                        });
                    }
                }
            }
        }
        return RedstoneReader.read(blocks, RedstoneScan::compass);
    }

    /**
     * Im Scan-Kasten des Bauplatzes, in Plan-Koordinaten und Planrichtungen (front = zum Spieler hin, wie die Module).
     * Leer, wenn dort kein Redstone steht.
     */
    static String atSite(SiteFrame frame, SiteSurvey survey) {
        Map<Pos, String> blocks = new HashMap<>();
        for (int x = SiteFrame.SCAN_MIN.x(); x <= SiteFrame.SCAN_MAX.x(); x++) {
            for (int y = SiteFrame.SCAN_MIN.y(); y <= SiteFrame.SCAN_MAX.y(); y++) {
                for (int z = SiteFrame.SCAN_MIN.z(); z <= SiteFrame.SCAN_MAX.z(); z++) {
                    Pos p = new Pos(x, y, z);
                    if (RedstoneReader.isPart(survey.idAt(p))) {
                        blocks.put(p, frame.blockAt(p).getBlockData().getAsString());
                        neighbours(blocks, x, y, z, n -> {
                            String id = survey.idAt(n);
                            return id.equals("air") ? null : id;
                        });
                    }
                }
            }
        }
        Function<String, String> dir = facing -> switch (facing) {
            case "up", "down" -> facing;
            case "north", "south", "east", "west" -> frame.orientation().toLocal(Cardinal.parse(facing));
            default -> facing;
        };
        RedstoneReader.Report r = RedstoneReader.read(blocks, dir);
        if (r.empty()) {
            return "";
        }
        return "\nEXISTING REDSTONE AT THE SITE (read from the world; same local coordinates as the scan; directions "
                + "as in the plan, front = toward the player). Keep it working when you build next to it:\n"
                + RedstoneReader.describe(r, dir, MAX_CHARS) + "\n";
    }

    private static void neighbours(Map<Pos, String> blocks, int x, int y, int z, Function<Pos, String> id) {
        int[][] six = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : six) {
            Pos n = new Pos(x + d[0], y + d[1], z + d[2]);
            if (!blocks.containsKey(n)) {
                String s = id.apply(n);
                if (s != null) {
                    blocks.put(n, s);
                }
            }
        }
    }
}
