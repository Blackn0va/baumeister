import com.blackn0va.baumeister.companion.CompanionFollow;
import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.companion.CompanionWalk;
import com.blackn0va.baumeister.companion.CompanionYield;

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
 * Messprobe „im Weg“ (Wunsch 2026-11-15: „manchmal steht der vera im weg rum und checkt das nicht, er soll sich mehr
 * wie ein mensch verhalten“). Ein Geist-Spieler (nur Koordinaten, 0,216 Block je Tick wie Gehen im Überleben) läuft
 * auf Vera zu oder an ihr vorbei; Vera folgt mit der Steuerung aus CompanionService (shouldWalk → goal → Planner →
 * CompanionWalk). Ist sie nah genug, bleibt sie dort stehen – wie im Dienst (CompanionService, Zweig !b.following).
 *
 * Gezählt je Lage:
 * - berührt: Ticks, in denen Geist und Vera sich überlappen (waagrecht unter 0,7, senkrecht unter 1,5) – im Spiel
 *   stößt der Spieler an sie oder muss um sie herum.
 * - im_weg: Ticks, in denen der Geist geht und Vera vor ihm in seiner Bahn steht (seitlich unter 0,8, 0 bis 2,5
 *   Blöcke voraus).
 * - abstand_mittel/abstand_max, teleports, im_stein: dürfen sich beim Folgen nicht verschlechtern.
 *
 * Lagen: entgegen_offen (Feld, frontal auf sie zu), entgegen_gang (3 breiter Gang), tür (sie steht in der einzigen
 * Tür einer Mauer, durch die er muss), umkehr_gang (1 breiter Gang, er kehrt um und läuft auf sie zu), folgen_offen
 * (normales Folgen mit Abbiegen, Vergleichsgröße). Ohne echten Spieler ticken Entities nur 300 Ticks – alle Lagen
 * laufen gleichzeitig, höchstens 290 Ticks.
 *
 * Je Lage zwei Veras nebeneinander: „jetzt“ (Steuerung bis 2026-11-15) und „neu“ (vorher CompanionYield.decide wie
 * CompanionService.yieldStep). Gesteuert wird wie im Dienst alle 2 Ticks; der Geist geht dann 2 Schritte je Aufruf.
 */
public final class WegProbe extends JavaPlugin {

    private static final int TICKS = 290;
    private static final int CALL = 2;
    private static final int START = 10;
    private static final double WALK = 0.216;
    private static final double DISTANCE = 2.5;

    private World w;
    private int g;
    private int tick;
    private final List<Lane> lanes = new ArrayList<>();
    private final List<String> out = new ArrayList<>();

    private static final class Lane {
        final String name;
        final Mannequin m;
        final List<double[]> route;
        final CompanionWalk.State walk = new CompanionWalk.State();
        final CompanionFollow.Trail trail = new CompanionFollow.Trail();
        final CompanionFollow.Planner planner = new CompanionFollow.Planner();
        final CompanionYield.State manners = new CompanionYield.State();
        final CompanionYield.Motion selfMotion = new CompanionYield.Motion();
        final CompanionYield.Motion ghostMotion = new CompanionYield.Motion();
        boolean neu;
        boolean walking;
        double gx;
        double gy;
        double gz;
        double dirX = 1;
        double dirZ;
        double along;
        boolean moving;
        int touched;
        int inWay;
        int moves;
        int teleports;
        int inStone;
        double lagSum;
        int lagN;
        double lagMax;

        Lane(String name, Mannequin m, List<double[]> route) {
            this.name = name;
            this.m = m;
            this.route = route;
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::setup, 1L);
    }

    private static final List<String> COURSES = List.of("entgegen_offen", "entgegen_gang", "tür", "umkehr_gang",
            "folgen_offen");

    private void setup() {
        w = Bukkit.getWorlds().get(0);
        g = w.getHighestBlockYAt(0, 0) + 12;
        int lane = 0;
        for (String course : COURSES) {
            for (boolean neu : new boolean[] {false, true}) {
                int x0 = lane++ * 64;
                build(course, x0);
                List<double[]> route = new ArrayList<>();
                for (int[] c : route(course)) {
                    route.add(new double[] {x0 + c[0] + 0.5, g + c[1], c[2] + 0.5});
                }
                int[] v = veraStart(course);
                Mannequin m = w.spawn(new Location(w, x0 + v[0] + 0.5, g + v[1], v[2] + 0.5), Mannequin.class, e -> {
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    e.setPersistent(false);
                    e.setGravity(true);
                    e.setImmovable(false);
                    e.setCollidable(false);
                });
                Lane l = new Lane(course + (neu ? "_neu" : "_jetzt"), m, route);
                l.neu = neu;
                double[] s = route.get(0);
                l.gx = s[0];
                l.gy = s[1];
                l.gz = s[2];
                // Spur wie nach ein paar Schritten des Spielers: sonst kennt Vera am Anfang keinen Weg hinter ihm.
                for (int i = 0; i < 6; i++) {
                    l.trail.record(s[0] - (6 - i) * 0.8 * l.dirX, s[1], s[2]);
                }
                lanes.add(l);
            }
        }
        // Wie CompanionService: Steuerung alle 2 Ticks.
        Bukkit.getScheduler().runTaskTimer(this, this::step, 1L, CALL);
    }

    /** Wo Vera am Anfang steht (relativ zur Lage). */
    private static int[] veraStart(String course) {
        return switch (course) {
            case "entgegen_offen", "entgegen_gang" -> new int[] {3, 0, 0};
            case "tür" -> new int[] {6, 0, 0};
            case "umkehr_gang", "folgen_offen" -> new int[] {-3, 0, 0};
            default -> throw new IllegalArgumentException(course);
        };
    }

    /** Weg des Geists als Zellen (x, y, z) relativ zur Lage. */
    private static List<int[]> route(String course) {
        List<int[]> c = new ArrayList<>();
        switch (course) {
            case "entgegen_offen", "entgegen_gang" -> line(c, 0, 0, 0, 16, 0, 0);
            case "tür" -> line(c, 0, 0, 0, 14, 0, 0);
            case "umkehr_gang" -> {
                line(c, 0, 0, 0, 10, 0, 0);
                line(c, 9, 0, 0, -12, 0, 0);
            }
            case "folgen_offen" -> {
                line(c, 0, 0, 0, 14, 0, 0);
                line(c, 14, 0, -1, 14, 0, -12);
            }
            default -> throw new IllegalArgumentException(course);
        }
        return c;
    }

    private static void line(List<int[]> c, int x1, int y, int z1, int x2, int y2, int z2) {
        int n = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= n; i++) {
            c.add(new int[] {x1 + Integer.signum(x2 - x1) * i, y, z1 + Integer.signum(z2 - z1) * i});
        }
    }

    private void set(int x, int y, int z, Material m) {
        w.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(this);
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void build(String course, int x0) {
        for (int x = x0 - 20; x <= x0 + 30; x++) {
            for (int z = -18; z <= 18; z++) {
                set(x, g - 1, z, Material.STONE);
                for (int y = 0; y <= 3; y++) {
                    set(x, g + y, z, Material.AIR);
                }
            }
        }
        switch (course) {
            case "entgegen_gang" -> {
                for (int x = x0 - 6; x <= x0 + 20; x++) {
                    for (int y = 0; y <= 2; y++) {
                        set(x, g + y, -2, Material.STONE);
                        set(x, g + y, 2, Material.STONE);
                    }
                }
            }
            case "umkehr_gang" -> {
                for (int x = x0 - 16; x <= x0 + 14; x++) {
                    for (int y = 0; y <= 2; y++) {
                        set(x, g + y, -1, Material.STONE);
                        set(x, g + y, 1, Material.STONE);
                    }
                }
            }
            case "tür" -> {
                for (int z = -8; z <= 8; z++) {
                    for (int y = 0; y <= 2; y++) {
                        if (z != 0 || y == 2) {
                            set(x0 + 6, g + y, z, Material.STONE);
                        }
                    }
                }
            }
            default -> {
            }
        }
    }

    private CompanionPath.Blocks blocks() {
        return new CompanionPath.Blocks() {
            @Override
            public boolean solid(int x, int y, int z) {
                return w.getBlockAt(x, y, z).getType().isSolid();
            }

            @Override
            public boolean passable(int x, int y, int z) {
                return w.getBlockAt(x, y, z).getType() != Material.LAVA && w.getBlockAt(x, y, z).isPassable();
            }

            @Override
            public boolean water(int x, int y, int z) {
                return w.getBlockAt(x, y, z).getType() == Material.WATER;
            }

            @Override
            public boolean climbable(int x, int y, int z) {
                return org.bukkit.Tag.CLIMBABLE.isTagged(w.getBlockAt(x, y, z).getType());
            }

            @Override
            public boolean doorway(int x, int y, int z) {
                Material t = w.getBlockAt(x, y, z).getType();
                return org.bukkit.Tag.DOORS.isTagged(t) || org.bukkit.Tag.TRAPDOORS.isTagged(t)
                        || org.bukkit.Tag.FENCE_GATES.isTagged(t);
            }

            @Override
            public boolean stairs(int x, int y, int z) {
                return org.bukkit.Tag.STAIRS.isTagged(w.getBlockAt(x, y, z).getType());
            }
        };
    }

    private void ghost(Lane l) {
        l.moving = false;
        if (tick < START || l.along >= l.route.size() - 1) {
            return;
        }
        l.along = Math.min(l.route.size() - 1, l.along + WALK * CALL);
        int i = (int) Math.floor(l.along);
        double t = l.along - i;
        double[] a = l.route.get(i);
        double[] b = l.route.get(Math.min(i + 1, l.route.size() - 1));
        double nx = a[0] + (b[0] - a[0]) * t;
        double nz = a[2] + (b[2] - a[2]) * t;
        double ny = t >= 0.5 ? b[1] : a[1];
        double step = Math.hypot(nx - l.gx, nz - l.gz);
        if (step > 1.0e-6) {
            l.dirX = (nx - l.gx) / step;
            l.dirZ = (nz - l.gz) / step;
            l.moving = true;
        }
        l.gx = nx;
        l.gy = ny;
        l.gz = nz;
    }

    private void measure(Lane l, Location cur) {
        double dx = cur.getX() - l.gx;
        double dz = cur.getZ() - l.gz;
        double dy = Math.abs(cur.getY() - l.gy);
        if (Math.hypot(dx, dz) < 0.7 && dy < 1.5) {
            l.touched++;
        }
        if (l.moving && dy < 1.5) {
            double forward = dx * l.dirX + dz * l.dirZ;
            double lateral = Math.abs(dx * l.dirZ - dz * l.dirX);
            if (forward > 0 && forward < 2.5 && lateral < 0.8) {
                l.inWay++;
            }
        }
        if (l.m.getEyeLocation().getBlock().getType().isOccluding() || cur.getBlock().getType().isOccluding()) {
            l.inStone++;
        }
        double lag = cur.distance(new Location(w, l.gx, l.gy, l.gz));
        if (tick >= START) {
            l.lagSum += lag;
            l.lagN++;
            l.lagMax = Math.max(l.lagMax, lag);
        }
    }

    private void step() {
        if (tick >= TICKS) {
            finish();
            return;
        }
        CompanionPath.Blocks bl = blocks();
        for (Lane l : lanes) {
            ghost(l);
            l.trail.record(l.gx, l.gy, l.gz);
            Mannequin m = l.m;
            Location cur = m.getLocation();
            measure(l, cur);
            double lag = cur.distance(new Location(w, l.gx, l.gy, l.gz));
            boolean hold = false;
            if (l.neu) {
                l.selfMotion.update(cur.getX(), cur.getZ());
                l.ghostMotion.update(l.gx, l.gz);
                CompanionYield.Person ghost = new CompanionYield.Person(l.gx, l.gy, l.gz, l.ghostMotion.vx(),
                        l.ghostMotion.vz(), l.gy + 1.62, l.dirX, 0, l.dirZ, 4.5, false, true, false);
                CompanionYield.Decision d = CompanionYield.decide(l.manners, bl, CompanionYield.Zones.NONE,
                        cur.getX(), cur.getY(), cur.getZ(), l.selfMotion.vx(), l.selfMotion.vz(), List.of(ghost));
                double near = Math.hypot(cur.getX() - l.gx, cur.getZ() - l.gz);
                if (d.reason() == CompanionYield.Reason.SWAP && near > 1.5) {
                    hold = true;
                } else if (d.reason() == CompanionYield.Reason.SWAP) {
                    CompanionFollow.Trail fresh = l.trail.ifFresh(l.gx, l.gy, l.gz);
                    double[] t = fresh == null ? null : fresh.behind(1.5);
                    if (t != null) {
                        l.teleports++;
                        m.setVelocity(new Vector());
                        m.teleport(new Location(w, t[0], t[1], t[2]));
                        l.manners.reset();
                        continue;
                    }
                    hold = true;
                } else if (d.moving()) {
                    l.moves++;
                    Vector v = m.getVelocity();
                    CompanionWalk.Step s = CompanionWalk.next(l.walk, new CompanionWalk.Body(cur.getX(), cur.getY(),
                            cur.getZ(), v.getY(), m.isOnGround(), m.isInWater(),
                            org.bukkit.Tag.CLIMBABLE.isTagged(cur.getBlock().getType())), d.x(), d.y(), d.z(),
                            d.speed());
                    if (!s.teleport()) {
                        m.setVelocity(new Vector(s.vx(), s.vy(), s.vz()));
                        continue;
                    }
                    l.walk.reset();
                    m.setVelocity(new Vector());
                    l.manners.reset();
                    hold = true;
                } else {
                    hold = d.holdFollow();
                }
            }
            boolean go = !hold && CompanionFollow.shouldWalk(l.walking, lag, DISTANCE);
            l.walking = go;
            if (!go) {
                // Wie CompanionService: nah genug → stehen bleiben (nur die Physik läuft weiter).
                l.walk.reset();
                continue;
            }
            double[] goal = CompanionFollow.goal(bl, l.gx, l.gy, l.gz, cur.getX(), cur.getZ(), DISTANCE, l.trail);
            walkTo(l, bl, cur, goal, lag);
        }
        tick += CALL;
    }

    private void walkTo(Lane l, CompanionPath.Blocks bl, Location cur, double[] goal, double lag) {
        Mannequin m = l.m;
        Location gl = new Location(w, goal[0], goal[1], goal[2]);
        double far = CompanionWalk.TELEPORT_FROM + DISTANCE;
        if (cur.distanceSquared(gl) > CompanionWalk.TELEPORT_FROM * CompanionWalk.TELEPORT_FROM || lag > far) {
            l.teleports++;
            l.planner.reset();
            m.setVelocity(new Vector());
            double[] t = l.trail.behind(DISTANCE);
            m.teleport(t != null ? new Location(w, t[0], t[1], t[2]) : gl);
            return;
        }
        double[] t = l.planner.next(bl, cur.getX(), cur.getY(), cur.getZ(), goal[0], goal[1], goal[2],
                l.trail.ifFresh(l.gx, l.gy, l.gz), tick, l.walk.stuck());
        Vector v = m.getVelocity();
        CompanionWalk.Step s = CompanionWalk.next(l.walk, new CompanionWalk.Body(cur.getX(), cur.getY(), cur.getZ(),
                v.getY(), m.isOnGround(), m.isInWater(), org.bukkit.Tag.CLIMBABLE.isTagged(cur.getBlock().getType())),
                t[0], t[1], t[2], CompanionWalk.speedFor(cur.distance(gl)));
        if (s.teleport()) {
            l.teleports++;
            l.planner.reset();
            m.setVelocity(new Vector());
            m.teleport(gl);
            return;
        }
        m.setVelocity(new Vector(s.vx(), s.vy(), s.vz()));
    }

    private void finish() {
        Bukkit.getScheduler().cancelTasks(this);
        for (Lane l : lanes) {
            out.add(String.format(Locale.ROOT,
                    "ende | %-20s berührt=%d im_weg=%d teleports=%d im_stein=%d abstand_mittel=%.1f abstand_max=%.1f "
                            + "ausweichschritte=%d",
                    l.name, l.touched, l.inWay, l.teleports, l.inStone, l.lagSum / Math.max(1, l.lagN), l.lagMax,
                    l.moves));
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
