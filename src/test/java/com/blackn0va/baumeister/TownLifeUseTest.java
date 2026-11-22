package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.town.Roster;
import com.blackn0va.baumeister.town.TownSettings;
import com.blackn0va.baumeister.town.WorkSpots;
import com.blackn0va.baumeister.town.use.Actor;
import com.blackn0va.baumeister.town.use.BukkitUse;
import com.blackn0va.baumeister.town.use.FakeUseWorld;
import com.blackn0va.baumeister.town.use.UseWorld;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * Einhängepunkte des Benutzens in TownLife (AK-44): Rechteprüfung kommt durch onInteract, nachts mit Bett bleibt der
 * Bewohner, und der Laufweg öffnet eine Tür, die nur die erweiterte Wegsuche kennt. Die Regeln selbst prüft
 * town/use/TownUseTest; hier geht es darum, dass TownLife sie wirklich aufruft.
 */
class TownLifeUseTest {

    private static final Roster.Person LOTTE = Roster.all().getFirst();

    /** Umgebung wie TownLifeTest.FakeEnv, aber mit Welt-Sicht für das Benutzen und der Wegsuche aus derselben Welt. */
    private static final class UseEnv implements TownLife.Env {
        final TownLifeTest.FakeEnv base = new TownLifeTest.FakeEnv();
        final FakeUseWorld uw;

        UseEnv(FakeUseWorld uw) {
            this.uw = uw;
        }

        @Override
        public Entity entity(UUID id) {
            return base.entity(id);
        }

        @Override
        public Collection<? extends Player> players() {
            return base.players();
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
        }

        @Override
        public CompanionPath.Blocks pathBlocks(World w) {
            return uw.pathBlocks();
        }

        @Override
        public boolean climbable(Location at) {
            return false;
        }

        @Override
        public Location surfaceAt(World w, int x, int z) {
            return null;
        }

        @Override
        public List<WorkSpots.Spot> workSpots() {
            return List.of();
        }

        @Override
        public UseWorld useWorld(World w) {
            return uw;
        }

        @Override
        public Actor actor(Mannequin m) {
            Body b = bodies.get(m.getUniqueId());
            return b == null ? null : b.actor();
        }

        final java.util.Map<UUID, Body> bodies = new java.util.HashMap<>();
    }

    /** Ein Mannequin am Boden, das Geschwindigkeit und Teleport mitschreibt; der Test bewegt es Tick für Tick. */
    private static final class Body {
        final UUID id = UUID.randomUUID();
        final List<String> calls = new CopyOnWriteArrayList<>();
        Location at;
        Vector velocity = new Vector();
        final Mannequin proxy;

        Body(Location start) {
            at = start;
            proxy = (Mannequin) Proxy.newProxyInstance(Mannequin.class.getClassLoader(),
                    new Class<?>[] {Mannequin.class}, (p, m, a) -> {
                        calls.add(m.getName());
                        return switch (m.getName()) {
                            case "getUniqueId" -> id;
                            case "getLocation" -> at.clone();
                            case "getEyeLocation" -> at.clone().add(0, 1.62, 0);
                            case "getWorld" -> at.getWorld();
                            case "isValid", "isOnGround" -> true;
                            case "getVelocity" -> velocity.clone();
                            case "setVelocity" -> {
                                velocity = ((Vector) a[0]).clone();
                                yield null;
                            }
                            case "teleport" -> {
                                at = ((Location) a[0]).clone();
                                yield true;
                            }
                            case "equals" -> p == a[0];
                            case "hashCode" -> id.hashCode();
                            case "toString" -> "Bewohner";
                            default -> TownLifeTest.none(m.getReturnType());
                        };
                    });
        }

        Actor actor() {
            return new Actor() {
                @Override
                public UUID id() {
                    return id;
                }

                @Override
                public double x() {
                    return at.getX();
                }

                @Override
                public double y() {
                    return at.getY();
                }

                @Override
                public double z() {
                    return at.getZ();
                }

                @Override
                public void face(double x, double y, double z) {
                }

                @Override
                public void swing() {
                }

                @Override
                public void lie(double x, double y, double z, float yaw) {
                    calls.add("lie");
                    at = new Location(at.getWorld(), x, y, z);
                }

                @Override
                public Runnable sit(double x, double y, double z, float yaw) {
                    return null;
                }

                @Override
                public void rise(double x, double y, double z) {
                    calls.add("rise");
                }

                @Override
                public void effect(String sound, String particle, double x, double y, double z) {
                }
            };
        }
    }

    private static World world(List<Player> players, long time) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getPlayers" -> new ArrayList<>(players);
                    case "isChunkLoaded" -> true;
                    case "getHighestBlockYAt" -> 63;
                    case "getTime" -> time;
                    case "getName" -> "testwelt";
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Welt";
                    default -> TownLifeTest.none(m.getReturnType());
                });
    }

    private record Setup(TownLife life, UseEnv env, Body body, TownLife.Resident resident, World world) {
    }

    private static Setup setup(FakeUseWorld uw, long time, Location start, Location player) {
        List<Player> players = new ArrayList<>();
        World w = world(players, time);
        UseEnv env = new UseEnv(uw);
        TownLife life = new TownLife(null, env, TownLifeTest.MARKER);
        life.configure(new TownSettings(true, 4, TownSettings.DEFAULT_GAP_SECONDS * 1000L, true), null);
        Location s = start.clone();
        s.setWorld(w);
        Body body = new Body(s);
        Location pl = player.clone();
        pl.setWorld(w);
        Player p = TownLifeTest.player(pl, new ArrayList<>());
        players.add(p);
        env.base.players.add(p);
        env.base.entities.put(body.id, body.proxy);
        env.bodies.put(body.id, body);
        TownLife.Resident r = life.adopt(LOTTE, body.proxy, s);
        return new Setup(life, env, body, r, w);
    }

    @Test
    void ownRightsCheckPassesOnInteractEverythingElseOfResidentsIsCancelled() {
        TownLifeTest.Town t = new TownLifeTest.Town(3);
        EntityInteractEvent check = new BukkitUse.Check(t.lotte.proxy, null);
        t.life.onInteract(check);
        assertFalse(check.isCancelled(), "eigene Rechteprüfung nicht absagen – sonst wäre jede Tür verboten");
        EntityInteractEvent plate = new EntityInteractEvent(t.lotte.proxy, null);
        t.life.onInteract(plate);
        assertTrue(plate.isCancelled(), "Druckplatte & Co. weiter abgesagt");
    }

    @Test
    void atNightWithABedHeStaysLyingInsteadOfVanishing() {
        // Weltzeit 15000 = 21 Uhr (Nacht). Bettkopf bei 1/64/1 nach Osten, Stehplatz 1/64/0 direkt neben ihm.
        FakeUseWorld uw = new FakeUseWorld().bed(1, 64, 1, "EAST", false);
        Setup s = setup(uw, 15_000L, new Location(null, 0.5, 64, 0.5), new Location(null, 6.5, 64, 0.5));
        for (int i = 0; i < 10; i++) {
            s.life.step();
        }
        assertEquals(1, s.life.count(), "liegt im Bett, ist nicht verschwunden (Grund: " + s.life.lastGone() + ")");
        assertTrue(s.life.use().sleeping(s.body.id));
        assertTrue(s.body.calls.contains("lie"));
    }

    @Test
    void atNightWithoutABedHeGoesAsBefore() {
        Setup s = setup(new FakeUseWorld(), 15_000L, new Location(null, 0.5, 64, 0.5),
                new Location(null, 6.5, 64, 0.5));
        for (int i = 0; i < 3; i++) {
            s.life.step();
        }
        assertEquals(0, s.life.count());
        assertEquals("schlafen", s.life.lastGone());
    }

    @Test
    void walkingOpensTheDoorOnlyTheUsePathKnows() {
        // Mauer bei x=4, Tür bei z=5; der Bewohner steht bei z=2,5 und will nach 8,5/64/2,5 (Weltzeit 1000 = Arbeit).
        // Geradeaus träfe er Mauer, nicht Tür: Nur die Wegsuche mit Türen (TownUse.blocks) führt ihn an die Tür, und
        // nur der Laufweg-Haken (TownUse.pass) macht sie auf.
        FakeUseWorld uw = new FakeUseWorld();
        for (int z = -30; z <= 30; z++) {
            uw.put(4, 64, z, "STONE").put(4, 65, z, "STONE");
        }
        uw.door(4, 64, 5, "OAK_DOOR", false);
        Setup s = setup(uw, 1_000L, new Location(null, 0.5, 64, 2.5), new Location(null, 0.5, 64, -6.5));
        s.resident.work = new Location(s.world, 8.5, 64, 2.5);
        for (int i = 0; i < 400 && s.body.at.getX() < 7; i++) {
            s.life.step();
            s.body.at.add(s.body.velocity.getX(), 0, s.body.velocity.getZ());
        }
        assertTrue(uw.writes.contains("open 4 64 5 OAK_DOOR"), "Tür auf dem Weg geöffnet: " + uw.writes);
        assertTrue(s.body.at.getX() > 7, "durch die Tür angekommen: " + s.body.at);
    }
}
