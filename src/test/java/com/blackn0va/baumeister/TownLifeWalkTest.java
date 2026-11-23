package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.town.Gathering;
import com.blackn0va.baumeister.town.Progress;
import com.blackn0va.baumeister.town.Roster;
import com.blackn0va.baumeister.town.TownSettings;
import com.blackn0va.baumeister.town.Unstuck;
import com.blackn0va.baumeister.town.WorkSpots;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * Laufen und Ziele über den echten Takt (Vertrag v013, AK-42): Umweg um eine Mauer ohne Teleport, Feststecken
 * stufenweise und nie sichtbar teleportiert, Folgen und Bleiben auf Zuruf, Treffen in kleinen Gruppen.
 *
 * Eigene Fakes statt TownLifeTest.FakeEnv: Hier bewegt sich die Puppe wirklich – jeder Takt wendet die gesetzte
 * Geschwindigkeit auf ihren Ort an, Mauern halten sie auf (je Achse). Ohne das prüfte ein Feststeck-Test nur, ob
 * irgendwann „teleport“ gerufen wird, nicht ob der Bewohner ankommt.
 */
class TownLifeWalkTest {

    static final NamespacedKey MARKER = TownLifeTest.MARKER;

    static Roster.Person person(String name) {
        return Roster.all().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    /** Flache Welt (Boden y ≤ 63) mit Mauern (x, z) von y 64 bis 66. */
    static final class Env implements TownLife.Env {
        final Map<UUID, Entity> entities = new HashMap<>();
        final List<Player> players = new ArrayList<>();
        final Set<Long> walls = new HashSet<>();
        /** Wasser auf Bodenhöhe (y 63), darunter fest. */
        final Set<Long> water = new HashSet<>();

        static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        boolean wall(int x, int z) {
            return walls.contains(key(x, z));
        }

        boolean wet(int x, int z) {
            return water.contains(key(x, z));
        }

        void wall(int x0, int z0, int x1, int z1) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    walls.add(key(x, z));
                }
            }
        }

        @Override
        public Entity entity(UUID id) {
            return entities.get(id);
        }

        @Override
        public Collection<? extends Player> players() {
            return players;
        }

        @Override
        public void now(Runnable r) {
            r.run();
        }

        @Override
        public void later(Runnable r, long ticks) {
            r.run();
        }

        @Override
        public boolean buildPlanning() {
            return false;
        }

        @Override
        public boolean companionThinking() {
            return false;
        }

        @Override
        public boolean slow() {
            return false;
        }

        @Override
        public Set<String> reservedNames() {
            return Set.of();
        }

        @Override
        public void saveEnabled(boolean on) {
            // Kein config.yml im Test.
        }

        @Override
        public CompanionPath.Blocks pathBlocks(World w) {
            return new CompanionPath.Blocks() {
                @Override
                public boolean solid(int x, int y, int z) {
                    return y <= 63 && !(y == 63 && wet(x, z)) || y <= 66 && wall(x, z);
                }

                @Override
                public boolean passable(int x, int y, int z) {
                    return y >= 64 && !(y <= 66 && wall(x, z)) || y == 63 && wet(x, z);
                }

                @Override
                public boolean water(int x, int y, int z) {
                    return y == 63 && wet(x, z);
                }
            };
        }

        @Override
        public boolean climbable(Location at) {
            return false;
        }

        @Override
        public Location surfaceAt(World w, int x, int z) {
            return wall(x, z) || wet(x, z) ? null : new Location(w, x + 0.5, 64, z + 0.5);
        }

        @Override
        public List<WorkSpots.Spot> workSpots() {
            return List.of();
        }

        /** Feldarbeit (TownLifeFieldTest): Blöcke und Hände; null = keine. */
        com.blackn0va.baumeister.town.work.Plot plot;
        com.blackn0va.baumeister.town.work.FieldWork.Hands hands;
        World named;
        /** Bauen (AK-45, TownLifeBuildTest): Blockliste des Katalogs und der Baudienst; null = aus. */
        com.blackn0va.baumeister.plan.BlockCatalog catalog;
        TownLifeBuildTest.Builder builder;
        /** Feld-Rückgängig (BuildService.undoTown); null = Vorgabe von Env. */
        java.util.function.BiFunction<org.bukkit.command.CommandSender, String, Map.Entry<Boolean, String>> townUndo;

        @Override
        public Map.Entry<Boolean, String> townUndo(org.bukkit.command.CommandSender s, String field) {
            return townUndo == null ? TownLife.Env.super.townUndo(s, field) : townUndo.apply(s, field);
        }

        @Override
        public com.blackn0va.baumeister.plan.BlockCatalog blockCatalog() {
            return catalog;
        }

        @Override
        public String townBuild(com.blackn0va.baumeister.town.work.TownBuilds.Order order, Player actor,
                org.bukkit.entity.Mannequin body, String name, java.util.function.Consumer<Boolean> done) {
            return builder == null ? "aus" : builder.start(order, actor, name, done);
        }

        @Override
        public com.blackn0va.baumeister.town.work.Plot plot(World w) {
            return plot;
        }

        @Override
        public com.blackn0va.baumeister.town.work.FieldWork.Hands hands(World w, Player actor,
                com.blackn0va.baumeister.town.work.Field f, com.blackn0va.baumeister.town.work.FieldJob job,
                java.util.function.Consumer<String> log) {
            return hands;
        }

        @Override
        public World world(String name) {
            return named != null && named.getName().equals(name) ? named : null;
        }
    }

    static World world(List<Player> players, long time) {
        return world(players, time, "testwelt");
    }

    static World world(List<Player> players, long time, String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getPlayers" -> new ArrayList<>(players);
                    case "isChunkLoaded" -> true;
                    case "getHighestBlockYAt" -> 63;
                    case "getTime" -> time;
                    case "getName" -> name;
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Welt";
                    default -> TownLifeTest.none(m.getReturnType());
                });
    }

    /** Puppe mit Ort und Geschwindigkeit; move() wendet die Geschwindigkeit an, Mauern halten je Achse auf. */
    static final class Puppet {
        final UUID id = UUID.randomUUID();
        final Set<NamespacedKey> keys = new HashSet<>(Set.of(MARKER));
        final List<String> calls = new CopyOnWriteArrayList<>();
        Location at;
        Vector vel = new Vector();
        boolean removed;
        final Mannequin proxy;

        Puppet(Location start) {
            at = start;
            PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
                    PersistentDataContainer.class.getClassLoader(), new Class<?>[] {PersistentDataContainer.class},
                    (p, m, a) -> "has".equals(m.getName()) ? keys.contains((NamespacedKey) a[0])
                            : TownLifeTest.none(m.getReturnType()));
            proxy = (Mannequin) Proxy.newProxyInstance(Mannequin.class.getClassLoader(),
                    new Class<?>[] {Mannequin.class}, (p, m, a) -> {
                        calls.add(m.getName() + (a != null && a.length == 1 ? " " + a[0] : ""));
                        return switch (m.getName()) {
                            case "getUniqueId" -> id;
                            case "getLocation" -> at.clone();
                            case "getEyeLocation" -> at.clone().add(0, 1.62, 0);
                            case "getWorld" -> at.getWorld();
                            case "isValid" -> !removed;
                            case "isOnGround" -> true;
                            case "getVelocity" -> vel.clone();
                            case "setVelocity" -> {
                                vel = ((Vector) a[0]).clone();
                                yield null;
                            }
                            case "teleport" -> {
                                at = ((Location) a[0]).clone();
                                yield true;
                            }
                            case "remove" -> {
                                removed = true;
                                yield null;
                            }
                            case "getPersistentDataContainer" -> data;
                            case "equals" -> p == a[0];
                            case "hashCode" -> id.hashCode();
                            case "toString" -> "Puppe";
                            default -> TownLifeTest.none(m.getReturnType());
                        };
                    });
        }

        void move(Env env) {
            double nx = at.getX() + vel.getX();
            double nz = at.getZ() + vel.getZ();
            if (!env.wall((int) Math.floor(nx), (int) Math.floor(at.getZ()))) {
                at.setX(nx);
            }
            if (!env.wall((int) Math.floor(at.getX()), (int) Math.floor(nz))) {
                at.setZ(nz);
            }
        }

        boolean teleported() {
            return calls.stream().anyMatch(c -> c.startsWith("teleport "));
        }
    }

    /** Spieler, dessen Ort der Test verschieben kann. */
    static Player player(AtomicReference<Location> at, List<String> heard, String name, boolean admin) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "getLocation" -> at.get().clone();
                    case "getEyeLocation" -> at.get().clone().add(0, 1.62, 0);
                    case "getWorld" -> at.get().getWorld();
                    case "getGameMode" -> GameMode.SURVIVAL;
                    case "hasPermission" -> admin;
                    case "sendMessage" -> {
                        if (a[0] instanceof String s) {
                            heard.add(s);
                        }
                        yield null;
                    }
                    case "equals" -> p == a[0];
                    case "hashCode" -> id.hashCode();
                    case "toString" -> name;
                    default -> TownLifeTest.none(m.getReturnType());
                });
    }

    /** Eine Stadt: Welt zur Uhrzeit time, ein Spieler bei playerAt, Bewohner nach Bedarf. */
    static final class Town {
        final Env env = new Env();
        final List<Player> worldPlayers = new ArrayList<>();
        final World world;
        final List<String> heard = new CopyOnWriteArrayList<>();
        final AtomicReference<Location> playerAt = new AtomicReference<>();
        final Player probant;
        final TownLife life = new TownLife(null, env, MARKER);
        final List<Puppet> puppets = new ArrayList<>();

        Town(long time, double px, double pz, int count) {
            this(time, px, pz, count, false);
        }

        Town(long time, double px, double pz, int count, boolean admin) {
            world = world(worldPlayers, time);
            env.named = world;
            playerAt.set(new Location(world, px, 64, pz));
            probant = player(playerAt, heard, "Probant", admin);
            worldPlayers.add(probant);
            env.players.add(probant);
            life.configure(new TownSettings(true, count, 45_000), null);
            life.hostResult(true);
        }

        TownLife.Resident add(String name, double x, double z) {
            Puppet p = new Puppet(new Location(world, x, 64, z));
            puppets.add(p);
            env.entities.put(p.id, p.proxy);
            return life.adopt(person(name), p.proxy, p.at);
        }

        void run(int ticks) {
            for (int t = 0; t < ticks; t++) {
                life.step();
                puppets.forEach(p -> p.move(env));
            }
        }
    }

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) {
            s += v;
        }
        return s;
    }

    // ---------------------------------------------------------------- Umweg und Feststecken

    /**
     * Bewohner in einem Becher aus Mauern (offen nach Westen), Arbeit östlich dahinter: Der Weg führt erst 5 Blöcke vom
     * Ziel weg. Vorher maß Progress die Luftlinie zum Teilziel – nach 3 s ohne Annäherung kam der Teleport, obwohl er
     * auf dem Weg war. Jetzt zählt der Restweg: ankommen ohne Teleport und ohne eine einzige Stufe von Unstuck.
     */
    @Test
    void detourAroundAWallNeedsNoTeleport() {
        Town t = new Town(1000, 10.5, 12.5, 1);
        t.env.wall(4, -6, 4, 6);
        t.env.wall(-4, -6, 4, -6);
        t.env.wall(-4, 6, 4, 6);
        TownLife.Resident r = t.add("Lotte", 0.5, 0.5);
        r.work = new Location(t.world, 10.5, 64, 0.5);
        int arrived = -1;
        for (int k = 0; k < 600 && arrived < 0; k++) {
            t.run(1);
            Location at = t.puppets.getFirst().at;
            if (Math.hypot(at.getX() - 10.5, at.getZ() - 0.5) < TownLife.ARRIVE) {
                arrived = k;
            }
        }
        assertTrue(arrived > 0, "nicht angekommen, steht bei " + t.puppets.getFirst().at);
        assertFalse(t.puppets.getFirst().teleported(), "teleportiert");
        assertEquals(0, r.teleports);
        assertEquals(0, sum(t.life.unstuckCounts()), "Feststecken auf dem Weg: "
                + java.util.Arrays.toString(t.life.unstuckCounts()));
        assertTrue(arrived > Progress.STUCK_TICKS * 2, "Umweg zu kurz für die Prüfung: " + arrived + " Takte");
    }

    /**
     * Ein Bewohner, der auf der Stelle wackelt (Greta auf Paper), vor den Augen eines Spielers: Er plant neu, weicht
     * aus (mit Sprung), nimmt einen Umweg und macht Pause – teleportiert wird nie. Ersetzt den alten Test „wird nach
     * 3 s teleportiert“ (TownLifeTest bis T-035).
     */
    @Test
    void wobblingResidentInSightIsNeverTeleported() {
        Town t = new Town(1000, 0.5, 10.5, 1);
        TownLife.Resident r = t.add("Lotte", 0.5, 0.5);
        r.work = new Location(t.world, 20.5, 64, 0.5);
        Puppet m = t.puppets.getFirst();
        boolean jumped = false;
        for (int k = 0; k < 6 * Progress.STUCK_TICKS; k++) {
            m.at = new Location(t.world, 0.5 + (k % 2 == 0 ? 0.3 : -0.3), 64, 0.5);
            t.life.step();
            jumped |= m.vel.getY() >= com.blackn0va.baumeister.companion.CompanionWalk.JUMP - 1e-9;
        }
        assertFalse(m.teleported(), "vor den Augen des Spielers teleportiert");
        long[] u = t.life.unstuckCounts();
        assertTrue(u[Unstuck.Action.REPLAN.ordinal()] >= 1 && u[Unstuck.Action.SIDESTEP.ordinal()] >= 1
                && u[Unstuck.Action.DETOUR.ordinal()] >= 1, java.util.Arrays.toString(u));
        assertTrue(u[Unstuck.Action.PAUSE.ordinal()] >= 1, "keine Pause: " + java.util.Arrays.toString(u));
        assertTrue(jumped, "beim Ausweichen nicht gesprungen");
        assertEquals(1, t.life.count(), "der Bewohner bleibt");
    }

    /** Derselbe Bewohner ungesehen (Spieler 30 Blöcke weg): Teleport erst nach allen anderen Stufen. */
    @Test
    void wobblingResidentOutOfSightIsTeleportedOnlyLast() {
        Town t = new Town(1000, 0.5, Unstuck.VIEW + 6, 1);
        TownLife.Resident r = t.add("Lotte", 0.5, 0.5);
        r.work = new Location(t.world, 20.5, 64, 0.5);
        Puppet m = t.puppets.getFirst();
        int at = -1;
        for (int k = 0; k < 8 * Progress.STUCK_TICKS && at < 0; k++) {
            m.at = new Location(t.world, 0.5 + (k % 2 == 0 ? 0.3 : -0.3), 64, 0.5);
            t.life.step();
            if (m.teleported()) {
                at = k;
            }
        }
        assertTrue(at >= 0, "nie teleportiert");
        assertTrue(at >= 3 * Progress.STUCK_TICKS, "Teleport schon nach " + at + " Takten – vor den anderen Stufen");
        long[] u = t.life.unstuckCounts();
        assertTrue(u[Unstuck.Action.REPLAN.ordinal()] >= 1 && u[Unstuck.Action.SIDESTEP.ordinal()] >= 1
                && u[Unstuck.Action.DETOUR.ordinal()] >= 1, java.util.Arrays.toString(u));
        assertEquals(1, r.teleports);
    }

    /**
     * Arbeitsplatz ohne Ort in der Nähe (Platz 12–22 Blöcke von der Wohnung): nur einer, zu dem ein trockener Weg
     * führt. Ein Wasserband östlich der Wohnung, zu breit für die Wegsuche drumherum – vorher lag jeder zweite Platz
     * dahinter (gemessen im Parcours T-035: Ausweichen und Pause im Kreis, steht ohne Grund 1,8 % → 4,9 %).
     */
    @Test
    void workSpotIsOneTheResidentCanReach() {
        Town t = new Town(1000, 0.5, 0.5, 1);
        for (int x = 6; x <= 9; x++) {
            for (int z = -40; z <= 40; z++) {
                t.env.water.add(Env.key(x, z));
            }
        }
        TownLife.Resident r = t.add("Lotte", 0.5, 0.5);
        for (int i = 0; i < 25; i++) {
            Location work = t.life.chooseWork(r, r.home);
            assertTrue(work.getX() < 6, "Arbeitsplatz jenseits des Wassers: " + work);
        }
    }

    /** Neue Bewohner erscheinen nur, wo ein Weg zum Spieler führt – nicht jenseits eines Flusses. */
    @Test
    void residentsAppearOnThePlayersSideOfTheWater() {
        Town t = new Town(1000, 0.5, 0.5, 1);
        for (int x = 6; x <= 9; x++) {
            for (int z = -40; z <= 40; z++) {
                t.env.water.add(Env.key(x, z));
            }
        }
        int found = 0;
        for (int i = 0; i < 25; i++) {
            Location at = t.life.spawnSpot(t.playerAt.get());
            if (at != null) {
                found++;
                assertTrue(at.getX() < 6, "erscheint jenseits des Wassers: " + at);
            }
        }
        assertTrue(found > 0, "nie ein Platz gefunden");
    }

    // ---------------------------------------------------------------- Zuruf

    @Test
    void residentFollowsOnCallAndStaysOnCall() {
        Town t = new Town(1000, 6.5, 0.5, 1);
        TownLife.Resident r = t.add("Lotte", 0.5, 0.5);
        r.work = new Location(t.world, -20.5, 64, 0.5);
        assertTrue(t.life.onChat(t.probant, "Lotte, komm mit"));
        assertTrue(t.life.following("Lotte"));
        // Format seit T-033 wie Spielerchat („<Lotte> …“).
        assertTrue(t.heard.stream().anyMatch(s -> s.contains("Lotte")), "keine Bestätigung: " + t.heard);
        // Der Spieler geht nach Osten; Lotte folgt (statt zur Arbeit im Westen) und bleibt dicht hinter ihm.
        for (int k = 0; k < 400; k++) {
            Location p = t.playerAt.get();
            if (k % 4 == 0 && p.getX() < 30) {
                t.playerAt.set(new Location(t.world, p.getX() + 0.5, 64, 0.5));
            }
            t.run(1);
        }
        Location lotte = t.puppets.getFirst().at;
        double d = Math.hypot(t.playerAt.get().getX() - lotte.getX(), t.playerAt.get().getZ() - lotte.getZ());
        assertTrue(lotte.getX() > 20, "nicht gefolgt, steht bei " + lotte);
        assertTrue(d < 5, "zu weit hinten: " + d);
        // Gleich danach „bleib hier“ – wirkt trotz Abklingzeit der Ansprache.
        assertTrue(t.life.onChat(t.probant, "Lotte, bleib hier"));
        assertFalse(t.life.following("Lotte"));
        Location stay = t.puppets.getFirst().at.clone();
        t.playerAt.set(new Location(t.world, 0.5, 64, 0.5));
        t.run(200);
        Location after = t.puppets.getFirst().at;
        assertTrue(Math.hypot(after.getX() - stay.getX(), after.getZ() - stay.getZ()) < TownLife.ARRIVE + 0.5,
                "nicht stehen geblieben: " + stay + " → " + after);
    }

    @Test
    void followEndsWhenThePlayerChangesWorld() {
        Town t = new Town(1000, 4.5, 0.5, 1);
        t.add("Lotte", 0.5, 0.5);
        // Ein zweiter Spieler bleibt in der Welt, damit Lotte nicht einfach „fern“ geht.
        Player other = player(new AtomicReference<>(new Location(t.world, 0.5, 64, 8.5)), t.heard, "Zweite", false);
        t.worldPlayers.add(other);
        t.env.players.add(other);
        assertTrue(t.life.onChat(t.probant, "Lotte, folge mir"));
        t.run(5);
        assertTrue(t.life.following("Lotte"));
        World nether = world(new ArrayList<>(), 1000, "testwelt_nether");
        t.worldPlayers.remove(t.probant);
        t.playerAt.set(new Location(nether, 4.5, 64, 0.5));
        t.run(1);
        assertFalse(t.life.following("Lotte"), "folgt in eine andere Welt");
        assertEquals(1, t.life.count(), "Lotte bleibt in ihrer Welt");
    }

    @Test
    void goHomeOnCall() {
        Town t = new Town(1000, 4.5, 6.5, 1);
        TownLife.Resident r = t.add("Lotte", 0.5, 0.5);
        r.work = new Location(t.world, 12.5, 64, 0.5);
        t.run(60);
        assertTrue(t.puppets.getFirst().at.getX() > 5, "nicht zur Arbeit gegangen");
        t.playerAt.set(t.puppets.getFirst().at.clone().add(0, 0, 6));
        assertTrue(t.life.onChat(t.probant, "Lotte, geh nach Hause"));
        t.run(200);
        Location at = t.puppets.getFirst().at;
        assertTrue(Math.hypot(at.getX() - 0.5, at.getZ() - 0.5) < TownLife.ARRIVE + 0.5, "nicht heim: " + at);
    }

    // ---------------------------------------------------------------- Treffen

    /**
     * Mittags finden sich nahe Bewohner zu Gruppen von 2–4 zusammen, gehen zum Treffpunkt und stehen dort beisammen;
     * gathered (Einhängepunkt für Gespräche) kommt einmal je Gruppe.
     */
    @Test
    void residentsMeetInSmallGroupsAtNoon() {
        Town t = new Town(6000, 0.5, 20.5, 5);
        String[] names = {"Lotte", "Emil", "Greta", "Paul", "Frieda"};
        for (int i = 0; i < names.length; i++) {
            t.add(names[i], i * 3.0 + 0.5, 0.5);
        }
        t.run(400);
        assertTrue(t.life.gatheringsDone() >= 1, "kein Treffen zustande gekommen");
        // Wer gerade in einer Gruppe steht: 2–4 je Gruppe, alle nah am Treffpunkt.
        Map<TownLife.Meet, List<TownLife.Resident>> groups = new HashMap<>();
        for (int i = 0; i < names.length; i++) {
            TownLife.Resident r = resident(t, names[i]);
            if (r.meet != null && r.meet.assembled) {
                groups.computeIfAbsent(r.meet, k -> new ArrayList<>()).add(r);
            }
        }
        assertFalse(groups.isEmpty(), "niemand steht beisammen");
        for (Map.Entry<TownLife.Meet, List<TownLife.Resident>> g : groups.entrySet()) {
            int n = g.getValue().size();
            assertTrue(n >= Gathering.MIN && n <= Gathering.MAX, "Gruppe mit " + n);
            for (TownLife.Resident r : g.getValue()) {
                Location at = t.env.entities.get(r.entity).getLocation();
                assertTrue(at.distance(g.getKey().point) < Gathering.SLOT + TownLife.ARRIVE + 0.5,
                        r.person.name() + " steht nicht am Treffpunkt: " + at);
            }
        }
    }

    /** Wer unterwegs zum Treffpunkt redet, ist noch nicht angekommen (sonst stünde er als „da“ in der Gruppe). */
    @Test
    void talkingOnTheWayIsNotArrival() {
        Town t = new Town(6000, 0.5, 20.5, 2);
        TownLife.Resident a = t.add("Lotte", 0.5, 0.5);
        t.add("Emil", 3.5, 0.5);
        t.run(21);
        assertNotNull(a.meet, "keine Gruppe gebildet");
        a.meetArrived = false;
        a.busyUntil = System.currentTimeMillis() + 60_000;
        t.puppets.getFirst().at = new Location(t.world, 12.5, 64, 12.5);
        t.life.step();
        assertFalse(a.meetArrived, "redend 12 Blöcke entfernt als angekommen gezählt");
    }

    /** Wasser zwischen zwei Bewohnern: keine gemeinsame Gruppe – einer stünde die Mittagspause über am Ufer. */
    @Test
    void residentsAcrossWaterDoNotFormAGroup() {
        Town t = new Town(6000, 0.5, 12.5, 2);
        for (int x = 6; x <= 9; x++) {
            for (int z = -40; z <= 40; z++) {
                t.env.water.add(Env.key(x, z));
            }
        }
        t.add("Lotte", 0.5, 0.5);
        t.add("Emil", 14.5, 0.5);
        for (int k = 0; k < 200; k++) {
            t.run(1);
            for (TownLife.Resident r : t.life.residentsForTest()) {
                assertTrue(r.meet == null, r.person.name() + " in einer Gruppe über das Wasser");
            }
        }
    }

    @Test
    void noGroupsOutsideTheNoonBreak() {
        Town t = new Town(1000, 0.5, 20.5, 3);
        t.add("Lotte", 0.5, 0.5);
        t.add("Emil", 3.5, 0.5);
        t.add("Greta", 6.5, 0.5);
        t.run(200);
        assertEquals(0, t.life.gatheringsDone());
        assertNotNull(t.life.runLine());
    }

    private static TownLife.Resident resident(Town t, String name) {
        for (Puppet p : t.puppets) {
            // Bewohner über seine Entität finden: adopt hat die Puppe eingetragen.
            for (TownLife.Resident r : residents(t.life)) {
                if (r.entity.equals(p.id) && r.person.name().equals(name)) {
                    return r;
                }
            }
        }
        throw new AssertionError(name + " fehlt");
    }

    private static List<TownLife.Resident> residents(TownLife life) {
        return life.residentsForTest();
    }
}
