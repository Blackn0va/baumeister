import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.companion.CompanionWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Messprobe Wegsuche: Vera (Mannequin mit CompanionWalk) läuft auf Parcours einmal geradeaus wie bis 2026-11-06 und
 * einmal mit CompanionPath (A*) – gezählt werden Ticks bis zum Ziel und Teleports. Ohne Spieler tickt eine Welt nur
 * 300 Ticks lang Entities, darum laufen alle Spuren gleichzeitig und höchstens 220 Ticks.
 */
public final class VeraPathProbe extends JavaPlugin {

    private static final int TICKS = 220;

    private World w;
    private int g;
    private int tick;
    private final List<Lane> lanes = new ArrayList<>();
    private final List<String> out = new ArrayList<>();

    private static final class Lane {
        final String name;
        final boolean usePath;
        final Mannequin m;
        final Location goal;
        final CompanionWalk.State walk = new CompanionWalk.State();
        List<CompanionPath.Node> path;
        int index;
        int planned = Integer.MIN_VALUE / 2;
        int teleports;
        int arrived = -1;
        /** Steg: tiefer als das gilt als abgestürzt. Sonst: Fall = in 5 Ticks über 1,2 Blöcke tiefer. */
        double fallBelow;
        boolean fallen;
        int falls;
        double y5 = Double.NaN;

        Lane(String name, boolean usePath, Mannequin m, Location goal) {
            this.name = name;
            this.usePath = usePath;
            this.m = m;
            this.goal = goal;
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::setup, 1L);
    }

    private void setup() {
        w = Bukkit.getWorlds().get(0);
        g = w.getHighestBlockYAt(0, 0) + 1;
        int lane = 0;
        for (String course : List.of("mauer", "treppe", "falle", "hoehle", "bergauf", "steg", "leiter")) {
            for (boolean usePath : List.of(false, true)) {
                int x0 = lane++ * 48;
                build(course, x0);
                Location start = new Location(w, x0 + 0.5, g, 0.5);
                Location goal = switch (course) {
                    case "treppe" -> new Location(w, x0 + 10.5, g + 3, 0.5);
                    case "hoehle" -> new Location(w, x0 + 11.5, g, 0.5);
                    case "bergauf" -> new Location(w, x0 + 9.5, g + 6, 0.5);
                    case "steg" -> new Location(w, x0 + 13.5, g, 0.5);
                    case "leiter" -> new Location(w, x0 + 4.5, g + 5, 0.5);
                    default -> new Location(w, x0 + 10.5, g, 0.5);
                };
                Mannequin m = w.spawn(start, Mannequin.class, e -> {
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    e.setPersistent(false);
                    e.setGravity(true);
                    e.setImmovable(false);
                    e.setCollidable(false);
                });
                Lane l = new Lane(course + (usePath ? "_mit_weg" : "_geradeaus"), usePath, m, goal);
                l.fallBelow = course.equals("steg") ? g - 0.5 : Double.NEGATIVE_INFINITY;
                lanes.add(l);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::step, 1L, 1L);
    }

    private void set(int x, int y, int z, Material m) {
        w.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(this);
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void build(String course, int x0) {
        for (int dx = -2; dx <= 14; dx++) {
            w.getChunkAt((x0 + dx) >> 4, 0).addPluginChunkTicket(this);
        }
        switch (course) {
            // 4 hohe Mauer quer, 13 breit (z -6..6), Ziel dahinter.
            case "mauer" -> {
                for (int z = -6; z <= 6; z++) {
                    for (int y = 0; y < 4; y++) {
                        set(x0 + 5, g + y, z, Material.STONE);
                    }
                }
            }
            // Treppe aus vollen Blöcken: 1, 2, 3 hoch, Ziel oben auf der 3.
            case "treppe" -> {
                for (int z = -3; z <= 3; z++) {
                    for (int step = 1; step <= 3; step++) {
                        for (int y = 0; y < step; y++) {
                            for (int x = x0 + 3 + step * 2; x <= x0 + 12; x++) {
                                set(x, g + y, z, Material.STONE);
                            }
                        }
                    }
                }
            }
            // U-Falle: Mauer 3 hoch vor dem Start nach vorn und zu beiden Seiten offen nach hinten.
            case "falle" -> {
                for (int z = -4; z <= 4; z++) {
                    for (int y = 0; y < 3; y++) {
                        set(x0 + 3, g + y, z, Material.STONE);
                    }
                }
                for (int x = x0 - 1; x <= x0 + 3; x++) {
                    for (int y = 0; y < 3; y++) {
                        set(x, g + y, -4, Material.STONE);
                        set(x, g + y, 4, Material.STONE);
                    }
                }
            }
            // Höhle: Steinblock, darin ein Tunnel 1 breit, 2 hoch, mit vier Kurven – dunkel, kein Himmel.
            case "hoehle" -> {
                for (int x = x0 - 1; x <= x0 + 13; x++) {
                    for (int z = -5; z <= 5; z++) {
                        for (int y = 0; y <= 3; y++) {
                            set(x, g + y, z, Material.STONE);
                        }
                    }
                }
                int[][] cells = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {3, 1}, {3, 2}, {4, 2}, {5, 2}, {6, 2}, {6, 1},
                    {6, 0}, {6, -1}, {6, -2}, {7, -2}, {8, -2}, {9, -2}, {9, -1}, {9, 0}, {10, 0}, {11, 0}};
                for (int[] c : cells) {
                    set(x0 + c[0], g, c[1], Material.AIR);
                    set(x0 + c[0], g + 1, c[1], Material.AIR);
                }
            }
            // Bergauf: schmale Treppe (1 breit) sechs Stufen hoch, links und rechts nichts – oben ein kurzes Plateau.
            case "bergauf" -> {
                for (int k = 0; k <= 7; k++) {
                    int height = Math.min(k + 1, 6);
                    for (int y = 0; y < height; y++) {
                        set(x0 + 2 + k, g + y, 0, Material.STONE);
                    }
                }
            }
            // Steg: Grube 4 tief, darüber ein 1 breiter Steg mit zwei Kurven.
            case "steg" -> {
                for (int x = x0 + 1; x <= x0 + 12; x++) {
                    for (int z = -3; z <= 4; z++) {
                        for (int y = 1; y <= 4; y++) {
                            set(x, g - y, z, Material.AIR);
                        }
                    }
                }
                int[][] bridge = {{1, 0}, {2, 0}, {3, 0}, {4, 0}, {4, 1}, {4, 2}, {5, 2}, {6, 2}, {7, 2}, {8, 2},
                    {8, 1}, {8, 0}, {9, 0}, {10, 0}, {11, 0}, {12, 0}};
                for (int[] c : bridge) {
                    set(x0 + c[0], g - 1, c[1], Material.STONE);
                }
            }
            // Leiter: Mauer 5 hoch und 41 breit (außen herum zu weit), Leiter davor in der Mitte.
            case "leiter" -> {
                for (int z = -20; z <= 20; z++) {
                    for (int y = 0; y < 5; y++) {
                        set(x0 + 4, g + y, z, Material.STONE);
                    }
                }
                for (int y = 0; y < 5; y++) {
                    org.bukkit.block.Block b = w.getBlockAt(x0 + 3, g + y, 0);
                    b.setType(Material.LADDER, false);
                    org.bukkit.block.data.type.Ladder data = (org.bukkit.block.data.type.Ladder) b.getBlockData();
                    data.setFacing(org.bukkit.block.BlockFace.WEST);
                    b.setBlockData(data, false);
                }
            }
            default -> throw new IllegalArgumentException(course);
        }
    }

    private CompanionPath.Blocks blocks() {
        Map<Long, Material> cache = new HashMap<>();
        return new CompanionPath.Blocks() {
            Material t(int x, int y, int z) {
                return cache.computeIfAbsent(((long) x << 40) ^ ((long) y << 20) ^ (z & 0xFFFFF),
                        k -> w.getBlockAt(x, y, z).getType());
            }

            @Override
            public boolean solid(int x, int y, int z) {
                return t(x, y, z).isSolid();
            }

            @Override
            public boolean passable(int x, int y, int z) {
                Material m = t(x, y, z);
                return m != Material.LAVA && w.getBlockAt(x, y, z).isPassable();
            }

            @Override
            public boolean water(int x, int y, int z) {
                return t(x, y, z) == Material.WATER;
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return org.bukkit.Tag.CLIMBABLE.isTagged(t(x, y, z));
            }
        };
    }

    private void step() {
        if (tick >= TICKS) {
            finish();
            return;
        }
        for (Lane l : lanes) {
            if (l.arrived >= 0) {
                continue;
            }
            Location cur = l.m.getLocation();
            Location target = l.goal;
            if (l.usePath) {
                CompanionPath.Node goalNode = new CompanionPath.Node(l.goal.getBlockX(), l.goal.getBlockY(),
                        l.goal.getBlockZ());
                if ((l.path == null || l.walk.stuck() >= 10 || tick - l.planned > 40) && tick - l.planned >= 5) {
                    long t0 = System.nanoTime();
                    l.path = CompanionPath.find(blocks(), new CompanionPath.Node(cur.getBlockX(),
                            (int) Math.floor(cur.getY() + 0.01), cur.getBlockZ()), goalNode);
                    long us = (System.nanoTime() - t0) / 1000;
                    out.add(l.name + " | plan tick=" + tick + " punkte=" + l.path.size() + " us=" + us);
                    l.index = 0;
                    l.planned = tick;
                }
                if (l.path != null && l.path.size() >= 2) {
                    l.index = CompanionPath.advance(l.path, l.index, cur.getX(), cur.getY(), cur.getZ());
                    if (l.index < l.path.size() - 1) {
                        CompanionPath.Node n = l.path.get(l.index);
                        target = new Location(w, n.x() + 0.5, n.y(), n.z() + 0.5);
                    }
                }
            }
            if (!l.fallen && cur.getY() < l.fallBelow) {
                l.fallen = true;
                l.falls++;
                out.add(l.name + " | abgestürzt tick=" + tick);
            }
            if (tick % 5 == 0) {
                if (!Double.isNaN(l.y5) && l.y5 - cur.getY() > 1.2
                        && !org.bukkit.Tag.CLIMBABLE.isTagged(cur.getBlock().getType())) {
                    l.falls++;
                    out.add(l.name + " | gefallen tick=" + tick + " von=" + String.format(Locale.ROOT, "%.1f", l.y5)
                            + " auf=" + String.format(Locale.ROOT, "%.1f", cur.getY()));
                }
                l.y5 = cur.getY();
            }
            CompanionWalk.Step s = CompanionWalk.next(l.walk, new CompanionWalk.Body(cur.getX(), cur.getY(),
                    cur.getZ(), l.m.getVelocity().getY(), l.m.isOnGround(), l.m.isInWater(),
                    org.bukkit.Tag.CLIMBABLE.isTagged(cur.getBlock().getType())), target.getX(),
                    target.getY(), target.getZ(), CompanionWalk.SPEED);
            if (s.teleport()) {
                l.teleports++;
                l.path = null;
                l.m.setVelocity(new Vector());
                l.m.teleport(l.goal);
                out.add(l.name + " | teleport tick=" + tick);
                continue;
            }
            l.m.setVelocity(new Vector(s.vx(), s.vy(), s.vz()));
            Location now = l.m.getLocation();
            if (Math.hypot(now.getX() - l.goal.getX(), now.getZ() - l.goal.getZ()) < 0.6
                    && Math.abs(now.getY() - l.goal.getY()) < 1.0) {
                l.arrived = tick;
            }
        }
        tick++;
    }

    private void finish() {
        Bukkit.getScheduler().cancelTasks(this);
        for (Lane l : lanes) {
            Location at = l.m.getLocation();
            out.add(String.format(Locale.ROOT, "ende | %-18s angekommen_tick=%d teleports=%d stürze=%d rest=%.2f", l.name,
                    l.arrived, l.teleports, l.falls, at.distance(l.goal)));
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("weg.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
        }
        getLogger().info("probe | fertig");
        Bukkit.shutdown();
    }
}
