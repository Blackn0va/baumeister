import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Messprobe: Wie bewegt sich ein Mannequin, wenn man ihm Schwerkraft und Bewegung lässt? Jede Spur steht in einem
 * eigenen Chunk-Paar, alle laufen gleichzeitig (ohne Spieler tickt eine Welt nur 300 Ticks lang Entities).
 */
public final class MannequinProbe extends JavaPlugin {

    private static final int TICKS = 140;

    private final List<Lane> lanes = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int tick;

    private record Lane(String name, Mannequin m, Location start, BiConsumer<Lane, Integer> control, double[] last) {
    }

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::setup, 1L);
    }

    private void setup() {
        World w = Bukkit.getWorlds().get(0);
        int[] lane = {0};
        // Spuren: Name, Aufbau, Startversatz, Schwerkraft, unbeweglich, Steuerung.
        add(w, lane, "vera_parcours", "course", 0, true, false, (l, t) -> vera(l, t, 15));
        add(w, lane, "vera_mauer", "wall", 0, true, false, (l, t) -> vera(l, t, 8));
        add(w, lane, "vera_flach", null, 0, true, false, (l, t) -> vera(l, t, 15));
        add(w, lane, "fall", null, 5, true, false, (l, t) -> { });
        add(w, lane, "fall_immovable", null, 5, true, true, (l, t) -> { });
        add(w, lane, "fall_nogravity", null, 5, false, false, (l, t) -> { });
        add(w, lane, "push_once", null, 0, true, false, (l, t) -> {
            if (t == 0) {
                l.m().setVelocity(new Vector(0.5, 0, 0));
            }
        });
        add(w, lane, "walk", null, 0, true, false, MannequinProbe::walk);
        add(w, lane, "walk_immovable", null, 0, true, true, MannequinProbe::walk);
        add(w, lane, "walk_nogravity_air", null, 3, false, false, MannequinProbe::walk);
        add(w, lane, "step_slab_walk", "slab", 0, true, false, MannequinProbe::walk);
        add(w, lane, "step_block_walk", "block", 0, true, false, MannequinProbe::walk);
        add(w, lane, "step_block_jump", "block", 0, true, false, MannequinProbe::walkJump);
        add(w, lane, "step_block_attr11", "block", 0, true, false, (l, t) -> {
            if (t == 0) {
                AttributeInstance a = l.m().getAttribute(Attribute.STEP_HEIGHT);
                notes.add("step_block_attr11 | step_height vorher=" + (a == null ? "fehlt" : a.getBaseValue()));
                if (a != null) {
                    a.setBaseValue(1.1);
                }
            }
            walk(l, t);
        });
        add(w, lane, "wall_walk_jump", "wall", 0, true, false, MannequinProbe::walkJump);
        add(w, lane, "wall_teleport_steps", "wall", 0, false, true, (l, t) -> {
            Location cur = l.m().getLocation();
            l.m().teleport(cur.add(0.45, 0, 0));
        });
        add(w, lane, "water_fall", "pool", 2, true, false, (l, t) -> { });
        add(w, lane, "water_walk", "pool", 2, true, false, MannequinProbe::walk);
        add(w, lane, "water_jump_up", "pool", 2, true, false, (l, t) -> {
            // Wie FloatGoal der Mobs: im Wasser nach oben drücken.
            if (l.m().isInWater()) {
                Vector v = l.m().getVelocity();
                l.m().setVelocity(new Vector(v.getX(), 0.04 + v.getY(), v.getZ()));
            }
        });
        add(w, lane, "ladder_walk", "ladder", 0, true, false, MannequinProbe::walk);
        add(w, lane, "passenger", null, 0, true, false, (l, t) -> {
            if (t == 0) {
                Entity carrier = l.m().getWorld().spawnEntity(l.start().clone().add(0, 0, 2), EntityType.WOLF);
                ((LivingEntity) carrier).setInvisible(true);
                boolean ok = carrier.addPassenger(l.m());
                notes.add("passenger | addPassenger=" + ok + " träger=" + carrier.getType());
                carrier.setVelocity(new Vector(0.4, 0, 0));
            }
            if (t == TICKS - 1) {
                notes.add("passenger | fahrzeug am ende=" + l.m().getVehicle());
            }
        });

        Mannequin probe = lanes.get(0).m();
        StringBuilder attrs = new StringBuilder("attribute |");
        for (Attribute a : Registry.ATTRIBUTE) {
            AttributeInstance inst = probe.getAttribute(a);
            if (inst != null) {
                attrs.append(' ').append(a.getKey().getKey()).append('=').append(inst.getValue());
            }
        }
        notes.add(attrs.toString());
        notes.add("chunk | ladestufe=" + probe.getLocation().getChunk().getLoadLevel());
        rows.add("spur,tick,x,y,z,am_boden,im_wasser");
        Bukkit.getScheduler().runTaskTimer(this, this::step, 1L, 1L);
    }


    private final java.util.Map<String, com.blackn0va.baumeister.companion.CompanionWalk.State> walks = new java.util.HashMap<>();

    /** Die echte Laufsteuerung des Plugins (CompanionWalk) auf ein Ziel dx Blöcke voraus. */
    private void vera(Lane l, int t, double dx) {
        com.blackn0va.baumeister.companion.CompanionWalk.State st = walks.computeIfAbsent(l.name(),
                k -> new com.blackn0va.baumeister.companion.CompanionWalk.State());
        Mannequin m = l.m();
        Location cur = m.getLocation();
        Location goal = l.start().clone().add(dx, 0, 0);
        double dist = cur.distance(goal);
        com.blackn0va.baumeister.companion.CompanionWalk.Step s = com.blackn0va.baumeister.companion.CompanionWalk.next(st,
                new com.blackn0va.baumeister.companion.CompanionWalk.Body(cur.getX(), cur.getY(), cur.getZ(),
                        m.getVelocity().getY(), m.isOnGround(), m.isInWater()),
                goal.getX(), goal.getY(), goal.getZ(), com.blackn0va.baumeister.companion.CompanionWalk.speedFor(dist));
        if (s.teleport()) {
            notes.add(l.name() + " | teleport tick=" + t + " dx=" + String.format(java.util.Locale.ROOT, "%.2f", cur.getX() - l.start().getX()));
            m.setVelocity(new Vector());
            m.teleport(goal);
            return;
        }
        if (s.arrived() && notes.stream().noneMatch(n -> n.startsWith(l.name() + " | angekommen"))) {
            notes.add(l.name() + " | angekommen tick=" + t);
        }
        m.setVelocity(new Vector(s.vx(), s.vy(), s.vz()));
    }

    private static void walk(Lane l, int t) {
        Vector v = l.m().getVelocity();
        l.m().setVelocity(new Vector(0.25, v.getY(), 0));
    }

    /** Laufen; steht sie am Boden und kommt nicht weiter, springt sie (Sprungkraft wie Spieler 0,42). */
    private static void walkJump(Lane l, int t) {
        Mannequin m = l.m();
        Vector v = m.getVelocity();
        double x = m.getLocation().getX();
        boolean blocked = t > 2 && x - l.last()[0] < 0.02;
        l.last()[0] = x;
        m.setVelocity(new Vector(0.25, m.isOnGround() && blocked ? 0.42 : v.getY(), 0));
    }

    private void add(World w, int[] lane, String name, String build, double up, boolean gravity, boolean immovable,
            BiConsumer<Lane, Integer> control) {
        int x0 = lane[0]++ * 32 + 2;
        int z = 8;
        w.getChunkAt(x0 >> 4, 0).addPluginChunkTicket(this);
        w.getChunkAt((x0 >> 4) + 1, 0).addPluginChunkTicket(this);
        int g = w.getHighestBlockYAt(x0, z) + 1;
        if (build != null) {
            for (int dz = -3; dz <= 3; dz++) {
                switch (build) {
                    case "course" -> {
                        w.getBlockAt(x0 + 3, g, z + dz).setType(Material.STONE);
                        w.getBlockAt(x0 + 6, g, z + dz).setType(Material.SMOOTH_STONE_SLAB);
                        for (int dx = 9; dx <= 11; dx++) {
                            for (int dy = 1; dy <= 2; dy++) {
                                w.getBlockAt(x0 + dx, g - dy, z + dz).setType(Material.WATER, false);
                            }
                        }
                    }
                    case "slab" -> w.getBlockAt(x0 + 4, g, z + dz).setType(Material.SMOOTH_STONE_SLAB);
                    case "block" -> w.getBlockAt(x0 + 4, g, z + dz).setType(Material.STONE);
                    case "wall" -> {
                        for (int dy = 0; dy < 4; dy++) {
                            w.getBlockAt(x0 + 4, g + dy, z + dz).setType(Material.STONE);
                        }
                    }
                    case "ladder" -> {
                        for (int dy = 0; dy < 4; dy++) {
                            w.getBlockAt(x0 + 5, g + dy, z + dz).setType(Material.STONE);
                            org.bukkit.block.Block b = w.getBlockAt(x0 + 4, g + dy, z + dz);
                            b.setType(Material.LADDER, false);
                            org.bukkit.block.data.type.Ladder data = (org.bukkit.block.data.type.Ladder) b.getBlockData();
                            data.setFacing(org.bukkit.block.BlockFace.WEST);
                            b.setBlockData(data, false);
                        }
                    }
                    case "pool" -> {
                        for (int dx = -2; dx <= 12; dx++) {
                            for (int dy = 1; dy <= 3; dy++) {
                                w.getBlockAt(x0 + dx, g - dy, z + dz).setType(Material.WATER, false);
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException(build);
                }
            }
        }
        Location start = new Location(w, x0 + 0.5, g + up, z + 0.5);
        Mannequin m = w.spawn(start, Mannequin.class, e -> {
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setPersistent(false);
            e.setGravity(gravity);
            e.setImmovable(immovable);
        });
        lanes.add(new Lane(name, m, start, control, new double[] {start.getX()}));
    }

    private void step() {
        if (tick >= TICKS) {
            finish();
            return;
        }
        for (Lane l : lanes) {
            try {
                l.control().accept(l, tick);
            } catch (RuntimeException e) {
                notes.add(l.name() + " | fehler | " + e);
            }
        }
        for (Lane l : lanes) {
            Location at = l.m().getLocation();
            rows.add(String.format(java.util.Locale.ROOT, "%s,%d,%.3f,%.3f,%.3f,%b,%b", l.name(), tick,
                    at.getX() - l.start().getX(), at.getY() - l.start().getY() + 0.0, at.getZ() - l.start().getZ(),
                    l.m().isOnGround(), l.m().isInWater()));
        }
        tick++;
    }

    private void finish() {
        Bukkit.getScheduler().cancelTasks(this);
        Path dir = getDataFolder().toPath();
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("spuren.csv"), rows, StandardCharsets.UTF_8);
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(dir.resolve("notizen.txt"),
                    StandardCharsets.UTF_8))) {
                notes.forEach(out::println);
                for (Lane l : lanes) {
                    Location at = l.m().getLocation();
                    out.printf(java.util.Locale.ROOT, "ende | %-20s dx=%.3f dy=%.3f boden=%b wasser=%b%n", l.name(),
                            at.getX() - l.start().getX(), at.getY() - l.start().getY(), l.m().isOnGround(),
                            l.m().isInWater());
                }
            }
        } catch (IOException e) {
            getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
        }
        getLogger().info("probe | fertig | spuren=" + lanes.size());
        Bukkit.shutdown();
    }
}
