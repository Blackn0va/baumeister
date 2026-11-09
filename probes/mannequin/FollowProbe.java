import com.blackn0va.baumeister.companion.CompanionFollow;
import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.companion.CompanionWalk;
import com.blackn0va.baumeister.companion.FollowDeployed;

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
 * Messprobe Folgen. Ein Geist-Spieler (nur Koordinaten, 0,216 Block je Tick wie Gehen im Überleben) läuft eine Strecke
 * ab; Vera folgt mit derselben Steuerung wie CompanionService:
 * - „jetzt“: Stand e9c9278 auf dev01 (FollowDeployed: Ziel schräg hinter dem Spieler nach seiner Blickrichtung,
 *   ohne Weg der NÄCHSTE Spur-Punkt und von dort die Spur weiter, eine Schwelle fürs Anlaufen).
 * - „neu“: CompanionFollow (Ziel auf Veras Seite, Planner mit dem NEUESTEN erreichbaren Spur-Punkt, Hysterese).
 * Wunsch 2026-11-07 – dafür die Strecken mit Umkehren und Abbiegen.
 *
 * Gezählt: Teleports, Ticks mit Kopf im Stein, Schwebe-Ticks, Abstand zum Spieler, Umweg (Veras Weg / Weg des
 * Spielers) und „weg_vom_spieler“: Ticks, in denen sie sich mehr als 4 Blöcke entfernt vom Spieler WEG bewegt.
 * Ohne echten Spieler ticken Entities nur 300 Ticks – alle Spuren laufen gleichzeitig, höchstens 290 Ticks.
 */
public final class FollowProbe extends JavaPlugin {

    private static final int TICKS = 290;
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
        final boolean neu;
        /** Dritte Fassung: Ziel = der Spieler selbst (CompanionFollow.goalPlayer). */
        boolean player;
        final Mannequin m;
        final List<double[]> route;
        final CompanionWalk.State walk = new CompanionWalk.State();
        final FollowDeployed.Trail oldTrail = new FollowDeployed.Trail();
        final CompanionFollow.Trail trail = new CompanionFollow.Trail();
        final CompanionFollow.Planner planner = new CompanionFollow.Planner();
        // Wegpunkt-Zustand des alten Standes (CompanionService.waypoint in e9c9278).
        List<CompanionPath.Node> path;
        int index;
        int planned = Integer.MIN_VALUE / 2;
        CompanionPath.Node pathGoal;
        boolean walking;
        double gx;
        double gy;
        double gz;
        double dirX = 1;
        double dirZ;
        /** Blickrichtung: wie dirX/dirZ, auf „blick_“-Strecken alle 3 s für 1 s zu Vera zurück. */
        double lookX = 1;
        double lookZ;
        double along;
        double ghostWalked;
        double veraWalked;
        Location lastVera;
        int teleports;
        int inStone;
        int floating;
        int away;
        double lagSum;
        int lagN;
        double lagMax;
        final java.util.Map<String, Integer> modes = new java.util.TreeMap<>();

        Lane(String name, boolean neu, Mannequin m, List<double[]> route) {
            this.name = name;
            this.neu = neu;
            this.m = m;
            this.route = route;
        }
    }

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::setup, 1L);
    }

    private static final List<String> COURSES = List.of("blick_offen", "blick_abbiegen", "kehrt_offen",
            "abbiegen_offen", "kehrt_gang", "schleife",
            "gang", "parallel", "wendel", "schlucht", "offen");

    private void setup() {
        w = Bukkit.getWorlds().get(0);
        if (Boolean.getBoolean("folge.wild")) {
            setupWild();
            Bukkit.getScheduler().runTaskTimer(this, this::step, 1L, 1L);
            return;
        }
        g = w.getHighestBlockYAt(0, 0) + 12;
        int lane = 0;
        for (String course : COURSES) {
            for (int variant = 0; variant < 3; variant++) {
                boolean neu = variant > 0;
                int x0 = lane++ * 64;
                List<int[]> cells = cells(course);
                build(course, x0, cells);
                List<double[]> route = new ArrayList<>();
                for (int[] c : cells) {
                    route.add(new double[] {x0 + c[0] + 0.5, g + c[1], c[2] + 0.5});
                }
                double[] first = route.get(0);
                Mannequin m = w.spawn(new Location(w, first[0], first[1], first[2]), Mannequin.class, e -> {
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    e.setPersistent(false);
                    e.setGravity(true);
                    e.setImmovable(false);
                    e.setCollidable(false);
                });
                Lane l = new Lane(course + (variant == 2 ? "_spieler" : neu ? "_neu" : "_jetzt"), neu, m,
                        route.subList(2, route.size()));
                l.player = variant == 2;
                double[] s = route.get(2);
                l.gx = s[0];
                l.gy = s[1];
                l.gz = s[2];
                l.lastVera = m.getLocation();
                lanes.add(l);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::step, 1L, 1L);
    }

    /**
     * Natur-Welt (-Dfolge.wild=true, normale Welt mit festem Seed): sechs Orte, je eine Strecke mit Abbiegen und
     * Umkehren, wie ein Spieler sie geht – um Bäume und Hügel herum (Wegsuche zwischen den Eckpunkten). Beide Veras
     * (jetzt/neu) folgen demselben Geist auf demselben Gelände; Mannequins stoßen sich nicht (setCollidable false).
     */
    private void setupWild() {
        int[][] sites = {{0, 0}, {160, 0}, {320, 0}, {0, 160}, {160, 160}, {320, 160}};
        for (int s = 0; s < sites.length; s++) {
            int cx = sites[s][0];
            int cz = sites[s][1];
            for (int x = cx - 48; x <= cx + 48; x += 16) {
                for (int z = cz - 48; z <= cz + 48; z += 16) {
                    w.getChunkAt(x >> 4, z >> 4).addPluginChunkTicket(this);
                }
            }
            List<double[]> route = wildRoute(cx, cz);
            out.add("wild" + s + " | strecke punkte=" + route.size());
            if (route.size() < 12) {
                continue;
            }
            for (int variant = 0; variant < 3; variant++) {
                boolean neu = variant > 0;
                double[] first = route.get(0);
                Mannequin m = w.spawn(new Location(w, first[0], first[1], first[2]), Mannequin.class, e -> {
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    e.setPersistent(false);
                    e.setGravity(true);
                    e.setImmovable(false);
                    e.setCollidable(false);
                });
                Lane l = new Lane((s >= 3 ? "blick_" : "") + "wild" + s + (variant == 2 ? "_spieler" : neu ? "_neu"
                        : "_jetzt"), neu, m, route.subList(2, route.size()));
                l.player = variant == 2;
                double[] st = route.get(2);
                l.gx = st[0];
                l.gy = st[1];
                l.gz = st[2];
                l.lastVera = m.getLocation();
                lanes.add(l);
            }
        }
    }

    /** Eckpunkte: 18 Ost, 14 Nord, 16 West (Umkehr in Gegenrichtung), 18 Süd, 14 Ost. */
    private List<double[]> wildRoute(int cx, int cz) {
        int[][] wp = {{0, 0}, {18, 0}, {18, -14}, {2, -14}, {2, 4}, {16, 4}};
        CompanionPath.Blocks bl = blocks();
        List<double[]> route = new ArrayList<>();
        CompanionPath.Node from = surface(cx + wp[0][0], cz + wp[0][1]);
        for (int i = 1; i < wp.length; i++) {
            CompanionPath.Node to = surface(cx + wp[i][0], cz + wp[i][1]);
            List<CompanionPath.Node> seg = CompanionPath.find(bl, from, to);
            if (seg.isEmpty()) {
                out.add("wild | abschnitt_ohne_weg | von=" + from + " nach=" + to);
                break;
            }
            for (CompanionPath.Node n : seg) {
                double[] p = {n.x() + 0.5, n.y(), n.z() + 0.5};
                if (route.isEmpty() || !java.util.Arrays.equals(route.get(route.size() - 1), p)) {
                    route.add(p);
                }
            }
            from = seg.get(seg.size() - 1);
        }
        return route;
    }

    private CompanionPath.Node surface(int x, int z) {
        int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        return new CompanionPath.Node(x, y, z);
    }

    /** Strecke als Zellen {x, y, z} relativ (Füße), in Gehreihenfolge. */
    private static List<int[]> cells(String course) {
        List<int[]> c = new ArrayList<>();
        switch (course) {
            // Offenes Feld 30 nach Osten; der Spieler schaut alle 3 s für 1 s zu Vera zurück.
            case "blick_offen" -> line(c, 0, 0, 0, 30, 0, 0);
            // Wie abbiegen_offen, mit Blick zurück.
            case "blick_abbiegen" -> {
                line(c, 0, 0, 0, 14, 0, 0);
                line(c, 14, 0, -1, 14, 0, -16);
            }
            // Offenes Feld: 14 nach Osten, umdrehen, 26 nach Westen (am Start vorbei).
            case "kehrt_offen" -> {
                line(c, 0, 0, 0, 14, 0, 0);
                line(c, 13, 0, 0, -12, 0, 0);
            }
            // Offenes Feld: 12 nach Osten, dann 14 nach Norden.
            case "abbiegen_offen" -> {
                line(c, 0, 0, 0, 12, 0, 0);
                line(c, 12, 0, -1, 12, 0, -14);
            }
            // Gang 1 breit nach Osten mit Abzweig nach Norden bei x=6: bis x=14, zurück bis 6, dann in den Abzweig.
            case "kehrt_gang" -> {
                line(c, 0, 0, 0, 14, 0, 0);
                line(c, 13, 0, 0, 6, 0, 0);
                line(c, 6, 0, -1, 6, 0, -10);
            }
            // Offenes Feld mit Säulen: Runde 10×10 und weiter nach Süden.
            case "schleife" -> {
                line(c, 0, 0, 0, 10, 0, 0);
                line(c, 10, 0, 1, 10, 0, 10);
                line(c, 9, 0, 10, 0, 10, 10);
                line(c, 0, 0, 9, 0, 0, -6);
            }
            // Gewundener Gang 1 breit: Kurven, zwei Stufen hoch, drei hinunter.
            case "gang" -> {
                line(c, 0, 0, 0, 5, 0, 0);
                line(c, 5, 0, 1, 5, 0, 4);
                c.add(new int[] {6, 1, 4});
                c.add(new int[] {7, 1, 4});
                c.add(new int[] {8, 2, 4});
                c.add(new int[] {9, 2, 4});
                line(c, 9, 2, 3, 9, 2, -1);
                c.add(new int[] {10, 1, -1});
                c.add(new int[] {11, 0, -1});
                line(c, 12, 0, -1, 12, 0, -4);
                line(c, 13, 0, -4, 18, 0, -4);
                c.add(new int[] {19, -1, -4});
                line(c, 20, -1, -4, 20, -1, 0);
                line(c, 21, -1, 0, 26, -1, 0);
            }
            // Gang mit einem zweiten Gang 3 tiefer direkt daneben (nur am Anfang verbunden), am Ende Kurve.
            case "parallel" -> {
                line(c, 0, 0, 0, 24, 0, 0);
                line(c, 24, 0, 1, 24, 0, 7);
                line(c, 25, 0, 7, 30, 0, 7);
            }
            // Wendeltreppe im Schacht 3×3 um eine Säule, eine Stufe je Feld, 12 Stufen; danach Gang.
            case "wendel" -> {
                line(c, -4, 0, 0, -1, 0, 0);
                int[][] ring = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}};
                for (int k = 0; k < 12; k++) {
                    int[] r = ring[k % 8];
                    c.add(new int[] {r[0], k, r[1]});
                }
                line(c, 3, 11, 1, 10, 11, 1);
            }
            // Steg 1 breit an einer Felswand, daneben eine 6 tiefe Schlucht.
            case "schlucht" -> line(c, 0, 0, 0, 30, 0, 0);
            // Offenes Feld mit Bogen – darf sich nicht verschlechtern.
            case "offen" -> {
                line(c, 0, 0, 0, 12, 0, 0);
                line(c, 12, 0, 1, 12, 0, 10);
                line(c, 11, 0, 10, 2, 0, 10);
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

    private void build(String course, int x0, List<int[]> cells) {
        boolean open = course.endsWith("_offen") || course.equals("offen") || course.equals("schlucht")
                || course.equals("schleife") || course.startsWith("blick_");
        if (!open) {
            for (int x = x0 - 8; x <= x0 + 34; x++) {
                for (int z = -12; z <= 12; z++) {
                    for (int y = -6; y <= 15; y++) {
                        set(x, g + y, z, Material.STONE);
                    }
                }
            }
        }
        if (course.equals("schlucht")) {
            for (int x = x0 - 2; x <= x0 + 32; x++) {
                for (int y = -7; y <= -1; y++) {
                    set(x, g + y, 0, Material.STONE);
                }
                for (int y = -7; y <= 4; y++) {
                    set(x, g + y, 1, Material.STONE);
                }
                for (int z = -5; z <= -1; z++) {
                    for (int y = -6; y <= -1; y++) {
                        set(x, g + y, z, Material.AIR);
                    }
                    set(x, g - 7, z, Material.STONE);
                }
            }
            return;
        }
        if (open) {
            for (int x = x0 - 16; x <= x0 + 34; x++) {
                for (int z = -18; z <= 18; z++) {
                    set(x, g - 1, z, Material.STONE);
                }
            }
            if (course.equals("schleife")) {
                // Säulen 2 hoch im Raster: Hindernisse wie Bäume, die Wegsuche muss außen herum.
                for (int x = x0 - 4; x <= x0 + 14; x += 3) {
                    for (int z = -6; z <= 14; z += 3) {
                        boolean onRoute = false;
                        for (int[] c : cells) {
                            if (x0 + c[0] == x && c[2] == z) {
                                onRoute = true;
                            }
                        }
                        if (!onRoute) {
                            set(x, g, z, Material.OAK_LOG);
                            set(x, g + 1, z, Material.OAK_LOG);
                        }
                    }
                }
            }
            return;
        }
        if (course.equals("parallel")) {
            List<int[]> low = new ArrayList<>();
            low.add(new int[] {-1, -1, 0});
            low.add(new int[] {-2, -2, 0});
            low.add(new int[] {-3, -3, 0});
            line(low, -3, -3, -1, 26, -3, -1);
            carve(x0, low);
            List<int[]> link = new ArrayList<>();
            link.add(new int[] {0, 0, 0});
            link.add(new int[] {-1, -1, 0});
            carve(x0, link);
        }
        carve(x0, cells);
    }

    /**
     * Je Zelle Füße und Kopf frei. Stufe hoch: über der unteren Zelle ein Block mehr (Sprung). Stufe hinunter: über der
     * unteren Zelle bis Kopfhöhe der oberen. Böden bleiben stehen.
     */
    private void carve(int x0, List<int[]> cells) {
        for (int i = 0; i < cells.size(); i++) {
            int[] a = cells.get(i);
            int lo = a[1];
            int hi = a[1] + 1;
            if (i + 1 < cells.size()) {
                int[] b = cells.get(i + 1);
                if (b[1] > a[1]) {
                    hi = b[1] + 1;
                } else if (b[1] < a[1]) {
                    for (int y = b[1]; y <= a[1] + 1; y++) {
                        set(x0 + b[0], g + y, b[2], Material.AIR);
                    }
                }
            }
            for (int y = lo; y <= hi; y++) {
                set(x0 + a[0], g + y, a[2], Material.AIR);
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
        };
    }

    private void ghost(Lane l) {
        if (tick < START || l.along >= l.route.size() - 1) {
            return;
        }
        l.along = Math.min(l.route.size() - 1, l.along + WALK);
        int i = (int) Math.floor(l.along);
        double t = l.along - i;
        double[] a = l.route.get(i);
        double[] b = l.route.get(Math.min(i + 1, l.route.size() - 1));
        double nx = a[0] + (b[0] - a[0]) * t;
        double nz = a[2] + (b[2] - a[2]) * t;
        // Höhe wechselt erst, wenn die Füße im nächsten Feld stehen – ein Spieler steht nie im Block.
        double ny = t >= 0.5 ? b[1] : a[1];
        double step = Math.hypot(nx - l.gx, nz - l.gz);
        if (step > 1.0e-6) {
            l.dirX = nx - l.gx;
            l.dirZ = nz - l.gz;
        }
        l.ghostWalked += step;
        l.gx = nx;
        l.gy = ny;
        l.gz = nz;
        l.lookX = l.dirX;
        l.lookZ = l.dirZ;
        if (l.name.startsWith("blick_") && tick % 60 < 20) {
            // Spieler schauen im Gehen zu ihrer Begleiterin (Reden, Nachsehen) – der alte Stand hängt „hinter dem
            // Spieler“ an genau diese Blickrichtung.
            Location v = l.m.getLocation();
            double dx = v.getX() - l.gx;
            double dz = v.getZ() - l.gz;
            if (Math.hypot(dx, dz) > 0.5) {
                l.lookX = dx;
                l.lookZ = dz;
            }
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
            l.oldTrail.record(l.gx, l.gy, l.gz);
            l.trail.record(l.gx, l.gy, l.gz);
            Mannequin m = l.m;
            Location cur = m.getLocation();
            Location player = new Location(w, l.gx, l.gy, l.gz);
            if (m.getEyeLocation().getBlock().getType().isOccluding() || cur.getBlock().getType().isOccluding()) {
                l.inStone++;
            }
            double moved = Math.hypot(cur.getX() - l.lastVera.getX(), cur.getZ() - l.lastVera.getZ());
            if (moved < 3) {
                l.veraWalked += moved;
            }
            double lagBefore = l.lastVera.distance(player);
            double lag = cur.distance(player);
            if (tick >= START && moved > 0.05 && lag > 4 && lag > lagBefore + 0.05) {
                // Entfernt sich, obwohl sie schon zurückliegt: läuft vom Spieler weg (Ursache des Nutzerberichts).
                l.away++;
            }
            l.lastVera = cur.clone();
            if (tick >= START) {
                l.lagSum += lag;
                l.lagN++;
                l.lagMax = Math.max(l.lagMax, lag);
            }
            boolean go = l.neu ? CompanionFollow.shouldWalk(l.walking, lag, DISTANCE) : lag > DISTANCE + 1.5;
            l.walking = go;
            if (!go) {
                l.walk.reset();
                continue;
            }
            double[] goal = l.player ? CompanionFollow.goalPlayer(bl, l.gx, l.gy, l.gz, l.trail)
                    : l.neu ? CompanionFollow.goal(bl, l.gx, l.gy, l.gz, cur.getX(), cur.getZ(), DISTANCE, l.trail)
                    : FollowDeployed.goal(bl, l.gx, l.gy, l.gz, l.lookX, l.lookZ, DISTANCE, l.oldTrail);
            Location gl = new Location(w, goal[0], goal[1], goal[2]);
            boolean walk = gl.getBlock().getType() == Material.WATER
                    || gl.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid();
            if (m.hasGravity() != walk) {
                m.setGravity(walk);
                m.setImmovable(!walk);
                if (!walk) {
                    m.setVelocity(new Vector());
                }
            }
            if (!walk) {
                l.floating++;
                Vector d = gl.toVector().subtract(cur.toVector());
                m.teleport(d.length() <= 0.45 ? gl : cur.clone().add(d.normalize().multiply(0.45)));
                continue;
            }
            double far = CompanionWalk.TELEPORT_FROM + DISTANCE;
            if (cur.distanceSquared(gl) > CompanionWalk.TELEPORT_FROM * CompanionWalk.TELEPORT_FROM
                    || l.neu && lag > far) {
                teleport(l, gl);
                continue;
            }
            Location target;
            if (l.neu) {
                double[] t = l.planner.next(bl, cur.getX(), cur.getY(), cur.getZ(), goal[0], goal[1], goal[2],
                        l.trail.ifFresh(l.gx, l.gy, l.gz), tick, l.walk.stuck());
                l.modes.merge(l.planner.mode(), 1, Integer::sum);
                target = new Location(w, t[0], t[1], t[2]);
            } else {
                target = oldWaypoint(l, cur, gl, bl);
            }
            Vector v = m.getVelocity();
            CompanionWalk.Step s = CompanionWalk.next(l.walk, new CompanionWalk.Body(cur.getX(), cur.getY(),
                    cur.getZ(), v.getY(), m.isOnGround(), m.isInWater(),
                    org.bukkit.Tag.CLIMBABLE.isTagged(cur.getBlock().getType())), target.getX(), target.getY(),
                    target.getZ(), l.neu ? CompanionWalk.speedFor(cur.distance(gl))
                            : cur.distance(gl) > 6 ? CompanionWalk.CATCH_UP : CompanionWalk.SPEED);
            if (s.teleport()) {
                teleport(l, gl);
                continue;
            }
            m.setVelocity(new Vector(s.vx(), s.vy(), s.vz()));
        }
        tick++;
    }

    private void teleport(Lane l, Location goal) {
        l.teleports++;
        l.path = null;
        l.planner.reset();
        l.m.setVelocity(new Vector());
        double[] t = l.neu ? l.trail.behind(DISTANCE) : l.oldTrail.behind(DISTANCE);
        Location to = t != null ? new Location(w, t[0], t[1], t[2]) : goal;
        l.m.teleport(to);
        l.lastVera = to.clone();
        out.add(l.name + " | teleport tick=" + tick + String.format(Locale.ROOT, " nach=%.1f,%.1f,%.1f",
                to.getX(), to.getY(), to.getZ()));
    }

    /** CompanionService.waypoint wie in e9c9278: ohne Weg (oder mit Ein-Punkt-Weg) Trail.ahead. */
    private Location oldWaypoint(Lane l, Location cur, Location goal, CompanionPath.Blocks bl) {
        CompanionPath.Node goalNode = new CompanionPath.Node(goal.getBlockX(), goal.getBlockY(), goal.getBlockZ());
        boolean moved = l.pathGoal == null || Math.abs(l.pathGoal.x() - goalNode.x())
                + Math.abs(l.pathGoal.y() - goalNode.y()) + Math.abs(l.pathGoal.z() - goalNode.z()) > 2;
        boolean replan = l.path == null || moved || l.walk.stuck() >= 10 || tick - l.planned > 40;
        if (replan && tick - l.planned >= 5) {
            l.path = CompanionPath.find(bl, new CompanionPath.Node(cur.getBlockX(),
                    (int) Math.floor(cur.getY() + 0.01), cur.getBlockZ()), goalNode);
            l.index = 0;
            l.pathGoal = goalNode;
            l.planned = tick;
        }
        if (l.path == null || l.path.size() < 2) {
            double[] a = l.oldTrail.ahead(cur.getX(), cur.getY(), cur.getZ());
            l.modes.merge(a != null ? "spur" : "direkt", 1, Integer::sum);
            return a != null ? new Location(w, a[0], a[1], a[2]) : goal;
        }
        l.index = CompanionPath.advance(l.path, l.index, cur.getX(), cur.getY(), cur.getZ());
        if (l.index >= l.path.size() - 1) {
            l.modes.merge("ziel", 1, Integer::sum);
            return goal;
        }
        l.modes.merge("weg", 1, Integer::sum);
        CompanionPath.Node n = l.path.get(l.index);
        return new Location(w, n.x() + 0.5, n.y(), n.z() + 0.5);
    }

    private void finish() {
        Bukkit.getScheduler().cancelTasks(this);
        for (Lane l : lanes) {
            double end = l.m.getLocation().distance(new Location(w, l.gx, l.gy, l.gz));
            out.add(String.format(Locale.ROOT,
                    "ende | %-20s teleports=%d im_stein=%d schweben=%d weg_vom_spieler=%d umweg=%.2f "
                            + "abstand_mittel=%.1f abstand_max=%.1f abstand_ende=%.1f spieler_fertig=%s modi=%s",
                    l.name, l.teleports, l.inStone, l.floating, l.away, l.veraWalked / Math.max(1, l.ghostWalked),
                    l.lagSum / Math.max(1, l.lagN), l.lagMax, end, l.along >= l.route.size() - 1, l.modes));
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("folgen.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("probe | schreiben_fehlgeschlagen | " + e);
        }
        getLogger().info("probe | fertig");
        Bukkit.shutdown();
    }
}
