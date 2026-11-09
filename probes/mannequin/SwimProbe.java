import com.blackn0va.baumeister.companion.CompanionFollow;
import com.blackn0va.baumeister.companion.CompanionWalk;
import com.blackn0va.baumeister.companion.WalkDeployed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Messprobe Schwimmen (Wunsch 2026-11-07). Ein Geist-Spieler schwimmt drei
 * Strecken in gemauerten Becken (6 tief); Vera folgt mit derselben Folgeregel wie CompanionService (shouldWalk, Ziel
 * 1,5 hinter ihm auf seiner Höhe) – einmal mit der Laufsteuerung auf dev01 („jetzt“, WalkDeployed), einmal mit
 * CompanionWalk („neu“):
 * - see: 28 Blöcke an der Oberfläche.
 * - tauchen: Oberfläche, schräg 5 hinab, am Grund 12, wieder hoch.
 * - ufer: 14 schwimmen, dann über eine 1 Block hohe Kante an Land und 16 gehen.
 * Gezählt: Teleports, Abstand (3D), Tiefenfehler (Vera – Geist, solange er taucht), Ticks festgehangen im Wasser, Tick
 * an Land (ufer). Ohne echten Spieler ticken Entities nur 300 Ticks – alles läuft gleichzeitig, höchstens 290 Ticks.
 */
public final class SwimProbe extends JavaPlugin {

    private static final int TICKS = 290;
    private static final int START = 10;
    private static final double SWIM = 0.12;
    private static final double WALK = 0.216;
    private static final double DISTANCE = 2.5;
    private static final List<String> COURSES = List.of("see", "tauchen", "ufer");

    private World w;
    private int g;
    private int tick;
    private final List<Lane> lanes = new ArrayList<>();
    private final List<String> out = new ArrayList<>();

    private static final class Lane {
        final String name;
        final boolean neu;
        final int x0;
        final Mannequin m;
        final List<double[]> route;        // {x, y, z, Tempo}
        final CompanionWalk.State walk = new CompanionWalk.State();
        final WalkDeployed.State oldWalk = new WalkDeployed.State();
        int seg;
        double segT;
        double gx;
        double gy;
        double gz;
        boolean walking;
        boolean done;
        Location last;
        int teleports;
        int stuck;
        double lagSum;
        int lagN;
        double lagMax;
        double depthSum;
        int depthN;
        int landTick = -1;
        int ghostLandTick = -1;

        Lane(String name, boolean neu, int x0, Mannequin m, List<double[]> route) {
            this.name = name;
            this.neu = neu;
            this.x0 = x0;
            this.m = m;
            this.route = route;
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::setup, 1L);
    }

    private void setup() {
        w = Bukkit.getWorlds().get(0);
        g = w.getHighestBlockYAt(0, 0) + 12;
        int lane = 0;
        for (String course : COURSES) {
            for (int variant = 0; variant < 2; variant++) {
                int x0 = lane++ * 64;
                build(course, x0);
                List<double[]> route = route(course, x0);
                double[] s = route.get(0);
                Mannequin m = w.spawn(new Location(w, s[0] - 2, s[1], s[2]), Mannequin.class, e -> {
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    e.setPersistent(false);
                    e.setGravity(true);
                    e.setImmovable(false);
                    e.setCollidable(false);
                });
                Lane l = new Lane(course + (variant == 1 ? "_neu" : "_jetzt"), variant == 1, x0, m, route);
                l.gx = s[0];
                l.gy = s[1];
                l.gz = s[2];
                l.last = m.getLocation();
                lanes.add(l);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::step, 1L, 1L);
    }

    /** Wasser von g-6 bis g-1 (Oberfläche: Füße auf g-1), Boden g-7. ufer: ab x0+17 Land bis g (Füße auf g+1). */
    private void build(String course, int x0) {
        int poolEnd = course.equals("ufer") ? x0 + 16 : x0 + 34;
        for (int x = x0 - 4; x <= x0 + 38; x++) {
            for (int z = -6; z <= 6; z++) {
                set(x, g - 7, z, Material.STONE);
                boolean edge = z == -5 || z == 5 || x == x0 - 3 || x == x0 + 37;
                boolean pool = !edge && z > -5 && z < 5 && x > x0 - 3 && x <= poolEnd;
                boolean land = !edge && z > -5 && z < 5 && x > poolEnd && x < x0 + 37;
                for (int y = g - 6; y <= g + 3; y++) {
                    Material mat;
                    if (edge && y <= g + 1) {
                        mat = Material.STONE;
                    } else if (pool && y <= g - 1) {
                        mat = Material.WATER;
                    } else if (land && y <= g) {
                        mat = Material.STONE;
                    } else {
                        mat = Material.AIR;
                    }
                    set(x, y, z, mat);
                }
            }
        }
    }

    private List<double[]> route(String course, int x0) {
        double z = 0.5;
        double top = g - 1;
        double bottom = g - 6;
        List<double[]> r = new ArrayList<>();
        switch (course) {
            case "see" -> {
                r.add(new double[] {x0 + 2.5, top, z, SWIM});
                r.add(new double[] {x0 + 30.5, top, z, SWIM});
            }
            case "tauchen" -> {
                r.add(new double[] {x0 + 2.5, top, z, SWIM});
                r.add(new double[] {x0 + 7.5, top, z, SWIM});
                r.add(new double[] {x0 + 12.5, bottom, z, SWIM});
                r.add(new double[] {x0 + 24.5, bottom, z, SWIM});
                r.add(new double[] {x0 + 29.5, top, z, SWIM});
            }
            case "ufer" -> {
                r.add(new double[] {x0 + 2.5, top, z, SWIM});
                r.add(new double[] {x0 + 16.5, top, z, SWIM});
                r.add(new double[] {x0 + 17.5, g + 1, z, WALK});
                r.add(new double[] {x0 + 33.5, g + 1, z, WALK});
            }
            default -> throw new IllegalArgumentException(course);
        }
        return r;
    }

    private void set(int x, int y, int z, Material m) {
        w.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(this);
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void ghost(Lane l) {
        if (tick < START || l.done) {
            return;
        }
        double[] a = l.route.get(l.seg);
        double[] b = l.route.get(l.seg + 1);
        double len = Math.max(1.0e-6, Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2)
                + Math.pow(b[2] - a[2], 2)));
        l.segT = Math.min(1.0, l.segT + b[3] / len);
        l.gx = a[0] + (b[0] - a[0]) * l.segT;
        l.gy = a[1] + (b[1] - a[1]) * l.segT;
        l.gz = a[2] + (b[2] - a[2]) * l.segT;
        if (l.segT >= 1.0) {
            l.seg++;
            l.segT = 0;
            if (l.seg >= l.route.size() - 1) {
                l.done = true;
            }
        }
        if (l.ghostLandTick < 0 && l.gy >= g + 1 - 1.0e-6) {
            l.ghostLandTick = tick;
        }
    }

    private void step() {
        if (tick >= TICKS) {
            finish();
            return;
        }
        for (Lane l : lanes) {
            ghost(l);
            Mannequin m = l.m;
            Location cur = m.getLocation();
            Location player = new Location(w, l.gx, l.gy, l.gz);
            double lag = cur.distance(player);
            double moved = Math.hypot(cur.getX() - l.last.getX(), cur.getZ() - l.last.getZ());
            l.last = cur.clone();
            if (tick >= START) {
                l.lagSum += lag;
                l.lagN++;
                l.lagMax = Math.max(l.lagMax, lag);
                if (l.gy < g - 2.5) {
                    l.depthSum += Math.abs(cur.getY() - l.gy);
                    l.depthN++;
                }
                if (m.isInWater() && l.walking && moved < 0.03 && lag > 3) {
                    l.stuck++;
                }
                if (l.landTick < 0 && cur.getX() >= l.x0 + 17 && cur.getY() >= g + 1 - 0.1 && !m.isInWater()) {
                    l.landTick = tick;
                }
            }
            l.walking = CompanionFollow.shouldWalk(l.walking, lag, DISTANCE);
            double gx = l.gx - 1.5;
            double gy = l.gy;
            double gz = l.gz;
            Vector v = m.getVelocity();
            boolean climbing = org.bukkit.Tag.CLIMBABLE.isTagged(cur.getBlock().getType());
            if (!l.walking) {
                l.walk.reset();
                l.oldWalk.reset();
                if (m.isInWater()) {
                    // Im Stand wie CompanionService: jetzt +0,04 nach oben, neu auf die Höhe des Spielers.
                    double vy = l.neu ? CompanionWalk.swim(l.gy - cur.getY()) : Math.min(0.3, v.getY() + 0.04);
                    m.setVelocity(new Vector(v.getX(), vy, v.getZ()));
                }
                continue;
            }
            double speed = CompanionWalk.speedFor(Math.sqrt(Math.pow(gx - cur.getX(), 2) + Math.pow(gz - cur.getZ(), 2)));
            boolean teleport;
            Vector next;
            if (l.neu) {
                CompanionWalk.Step s = CompanionWalk.next(l.walk, new CompanionWalk.Body(cur.getX(), cur.getY(),
                        cur.getZ(), v.getY(), m.isOnGround(), m.isInWater(), climbing), gx, gy, gz, speed);
                teleport = s.teleport();
                next = new Vector(s.vx(), s.vy(), s.vz());
            } else {
                WalkDeployed.Step s = WalkDeployed.next(l.oldWalk, new WalkDeployed.Body(cur.getX(), cur.getY(),
                        cur.getZ(), v.getY(), m.isOnGround(), m.isInWater(), climbing), gx, gy, gz, speed);
                teleport = s.teleport();
                next = new Vector(s.vx(), s.vy(), s.vz());
            }
            if (teleport) {
                l.teleports++;
                m.setVelocity(new Vector());
                m.teleport(new Location(w, gx, gy, gz));
                l.last = m.getLocation();
                out.add(l.name + " | teleport tick=" + tick);
                continue;
            }
            m.setVelocity(next);
        }
        tick++;
    }

    private void finish() {
        Bukkit.getScheduler().cancelTasks(this);
        for (Lane l : lanes) {
            Location end = l.m.getLocation();
            out.add(String.format(Locale.ROOT, "ende | %-14s teleports=%d festgehangen=%d abstand_mittel=%.1f "
                            + "abstand_max=%.1f tiefenfehler=%s an_land=%s geist_an_land=%s ende=%.1f,%.1f,%.1f",
                    l.name, l.teleports, l.stuck, l.lagSum / Math.max(1, l.lagN), l.lagMax,
                    l.depthN == 0 ? "-" : String.format(Locale.ROOT, "%.1f", l.depthSum / l.depthN),
                    l.landTick < 0 ? "nie" : String.valueOf(l.landTick), l.ghostLandTick < 0 ? "-"
                            : String.valueOf(l.ghostLandTick), end.getX() - l.x0, end.getY() - g, end.getZ()));
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("schwimmen.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
        }
        getLogger().info("probe | fertig");
        Bukkit.shutdown();
    }
}
