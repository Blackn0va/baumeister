package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.Roster;
import com.blackn0va.baumeister.town.TownSettings;
import com.blackn0va.baumeister.town.WorkSpots;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * Verdrahtung des Stadtlebens ohne Server (Nachbesserung 2026-11-22): Die Gegenprobe schaltete neun Schutzstellen in
 * TownLife, BaumeisterListener und BaumeisterCommand ab, und die Suite blieb grün. Hier läuft jede davon über die echte
 * Methode, mit einem Fake für die Umgebung (TownLife.Env) und Proxies für Welt, Spieler und Mannequins.
 */
class TownLifeTest {

    static final NamespacedKey MARKER = new NamespacedKey("baumeister", "bewohner");
    private static final Roster.Person LOTTE = Roster.all().getFirst();
    private static final Roster.Person EMIL = Roster.all().get(1);

    // ---------------------------------------------------------------- Fakes

    /** Umgebung zum Einstellen: Entitäten, Spieler, Bau/Vera denkt; Aufgaben laufen sofort. */
    static final class FakeEnv implements TownLife.Env {
        final Map<UUID, Entity> entities = new HashMap<>();
        final List<Player> players = new ArrayList<>();
        volatile boolean building;
        volatile boolean vera;
        Boolean saved;

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
            return building;
        }

        @Override
        public boolean companionThinking() {
            return vera;
        }

        @Override
        public boolean slow() {
            return false;
        }

        @Override
        public Set<String> reservedNames() {
            return Set.of("Baumeister", "Vera");
        }

        @Override
        public void saveEnabled(boolean on) {
            saved = on;
        }

        /** Flache Welt: Boden bis y=63, darüber frei, kein Wasser. */
        @Override
        public com.blackn0va.baumeister.companion.CompanionPath.Blocks pathBlocks(World w) {
            return new com.blackn0va.baumeister.companion.CompanionPath.Blocks() {
                @Override
                public boolean solid(int x, int y, int z) {
                    return y <= 63;
                }

                @Override
                public boolean passable(int x, int y, int z) {
                    return y >= 64;
                }

                @Override
                public boolean water(int x, int y, int z) {
                    return false;
                }
            };
        }

        @Override
        public boolean climbable(Location at) {
            return false;
        }

        final List<WorkSpots.Spot> spots = new ArrayList<>();

        /** Spalte x mit Dach auf y=70 über dem Boden (Markthalle); Integer.MIN_VALUE = keine. */
        int roofX = Integer.MIN_VALUE;

        private boolean solidAt(int x, int y) {
            return y <= 63 || (x == roofX && y == 70);
        }

        /** Flache Welt: Stehplatz auf y=64, wo der Chunk geladen ist; über einem Dach dessen Oberseite (höchster Block). */
        @Override
        public Location surfaceAt(World w, int x, int z) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            return new Location(w, x + 0.5, x == roofX ? 71 : 64, z + 0.5);
        }

        /** Wie BukkitEnv.standNear über TownLife.standY, mit der Welt oben. */
        @Override
        public Location standNear(World w, int x, int y, int z) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            Integer at = TownLife.standY(y, h -> solidAt(x, h), h -> !solidAt(x, h));
            return at == null ? null : new Location(w, x + 0.5, at, z + 0.5);
        }

        @Override
        public List<WorkSpots.Spot> workSpots() {
            if (spotsFail != null) {
                throw spotsFail;
            }
            return spots;
        }

        /** Gesetzt: workSpots wirft das (Ortsquelle kaputt). */
        RuntimeException spotsFail;
    }

    /** Antwort für Methoden, die der Test nicht nachbildet: Vorgabewert des Rückgabetyps. */
    static Object none(Class<?> t) {
        if (t == boolean.class) {
            return false;
        }
        if (t == int.class || t == long.class || t == short.class || t == byte.class) {
            return t == int.class ? 0 : t == long.class ? 0L : t == short.class ? (short) 0 : (byte) 0;
        }
        if (t == double.class || t == float.class) {
            return t == double.class ? 0.0 : 0f;
        }
        return null;
    }

    private static World world(List<Player> players) {
        return world(players, false);
    }

    /** loaded = alle Chunks geladen, Oberfläche bei y=63 (für den Laufweg). */
    static World world(List<Player> players, boolean loaded) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "getPlayers" -> new ArrayList<>(players);
                    case "isChunkLoaded" -> loaded;
                    case "getHighestBlockYAt" -> 63;
                    case "getTime" -> 1000L;
                    case "getName" -> "testwelt";
                    case "equals" -> proxy == a[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Welt";
                    default -> none(m.getReturnType());
                });
    }

    private static PersistentDataContainer pdc(Set<NamespacedKey> keys) {
        return (PersistentDataContainer) Proxy.newProxyInstance(PersistentDataContainer.class.getClassLoader(),
                new Class<?>[] {PersistentDataContainer.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "has" -> keys.contains((NamespacedKey) a[0]);
                    case "set" -> {
                        keys.add((NamespacedKey) a[0]);
                        yield null;
                    }
                    default -> none(m.getReturnType());
                });
    }

    /** Ein Mannequin, das seine Aufrufe mitschreibt („name arg“). */
    static final class FakeMannequin {
        final UUID id = UUID.randomUUID();
        final Set<NamespacedKey> keys = new HashSet<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        Location at;
        boolean removed;
        final Mannequin proxy;

        FakeMannequin(Location start, boolean marked) {
            // Der Proxy liest das Feld (this.at), nicht den Parameter: Der Laufweg-Test verschiebt die Puppe zwischen
            // den Ticks. Vorher las er den Parameter – die Puppe stand still, und CompanionWalk teleportierte nach
            // 40 Ticks; der Test prüfte so nicht, was er prüfen sollte (Gegenprobe 2026-11-22 blieb grün).
            this.at = start;
            if (marked) {
                keys.add(MARKER);
            }
            PersistentDataContainer data = pdc(keys);
            proxy = (Mannequin) Proxy.newProxyInstance(Mannequin.class.getClassLoader(),
                    new Class<?>[] {Mannequin.class}, (p, m, a) -> {
                        calls.add(m.getName() + (a != null && a.length == 1 ? " " + a[0] : ""));
                        return switch (m.getName()) {
                            case "getUniqueId" -> id;
                            case "getLocation" -> this.at.clone();
                            case "getEyeLocation" -> this.at.clone().add(0, 1.62, 0);
                            case "getWorld" -> this.at.getWorld();
                            case "isValid" -> !removed;
                            case "remove" -> {
                                removed = true;
                                yield null;
                            }
                            case "getVelocity" -> new Vector();
                            case "getPersistentDataContainer" -> data;
                            case "equals" -> p == a[0];
                            case "hashCode" -> id.hashCode();
                            case "toString" -> "Mannequin";
                            default -> none(m.getReturnType());
                        };
                    });
        }
    }

    /** Ein Spieler, der mitschreibt, was er im Chat liest. */
    static Player player(Location at, List<String> heard) {
        return player(at, heard, "Probant");
    }

    static Player player(Location at, List<String> heard, String name) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "getLocation" -> at.clone();
                    case "getEyeLocation" -> at.clone().add(0, 1.62, 0);
                    case "getWorld" -> at.getWorld();
                    case "getGameMode" -> GameMode.SURVIVAL;
                    case "sendMessage" -> {
                        if (a[0] instanceof String s) {
                            heard.add(s);
                        }
                        yield null;
                    }
                    case "equals" -> p == a[0];
                    case "hashCode" -> id.hashCode();
                    case "toString" -> "Probant";
                    default -> none(m.getReturnType());
                });
    }

    /** Stadt mit einem Bewohner (Lotte) bei 0/64/0 und einem Spieler im Abstand dist. */
    static final class Town {
        final FakeEnv env = new FakeEnv();
        final List<Player> worldPlayers = new ArrayList<>();
        final World world = world(worldPlayers);
        final List<String> heard = new CopyOnWriteArrayList<>();
        final TownLife life = new TownLife(null, env, MARKER);
        final FakeMannequin lotte = new FakeMannequin(new Location(world, 0.5, 64, 0.5), true);
        final Player probant;

        Town(double dist) {
            probant = player(new Location(world, 0.5 + dist, 64, 0.5), heard);
            worldPlayers.add(probant);
            env.players.add(probant);
            env.entities.put(lotte.id, lotte.proxy);
            life.adopt(LOTTE, lotte.proxy, lotte.at);
        }
    }

    private static TownSettings on() {
        return TownSettings.defaults().withEnabled(true);
    }

    // ---------------------------------------------------------------- Schutzstellen

    /** onInteract: Bewohner lösen nichts aus – fremde Entitäten bleiben unberührt. */
    @Test
    void interactionsOfResidentsAreCancelledOthersNot() {
        Town t = new Town(3);
        EntityInteractEvent ours = new EntityInteractEvent(t.lotte.proxy, null);
        t.life.onInteract(ours);
        assertTrue(ours.isCancelled());
        FakeMannequin stranger = new FakeMannequin(new Location(t.world, 5, 64, 5), false);
        EntityInteractEvent other = new EntityInteractEvent(stranger.proxy, null);
        t.life.onInteract(other);
        assertFalse(other.isCancelled());
    }

    /** prepare: nicht persistent, markiert, unverwundbar, läuft mit Schwerkraft. */
    @Test
    void newResidentIsNotPersistentMarkedAndInvulnerable() {
        FakeMannequin m = new FakeMannequin(new Location(world(List.of()), 0, 64, 0), false);
        TownLife.prepare(m.proxy, MARKER, LOTTE);
        assertTrue(m.keys.contains(MARKER), "PDC-Marke fehlt");
        assertTrue(m.calls.contains("setPersistent false"), m.calls.toString());
        assertFalse(m.calls.contains("setPersistent true"), m.calls.toString());
        assertTrue(m.calls.contains("setInvulnerable true"), m.calls.toString());
        assertTrue(m.calls.contains("setGravity true") && m.calls.contains("setImmovable false"), m.calls.toString());
    }

    /** BaumeisterListener.leftover: Bewohner, die dieser Lauf nicht kennt, gehen beim Chunk-Laden weg. */
    @Test
    void unknownResidentIsLeftoverOnChunkLoad() {
        Town t = new Town(3);
        FakeMannequin old = new FakeMannequin(new Location(t.world, 9, 64, 9), true);
        FakeMannequin plain = new FakeMannequin(new Location(t.world, 9, 64, 9), false);
        assertTrue(BaumeisterListener.leftover(old.proxy, null, null, t.life));
        assertFalse(BaumeisterListener.leftover(t.lotte.proxy, null, null, t.life), "eigener Bewohner bleibt");
        assertFalse(BaumeisterListener.leftover(plain.proxy, null, null, t.life), "fremde Entity bleibt");
    }

    /** step: Modus aus → alle weg, auch wenn die KI antwortet. */
    @Test
    void switchedOffRemovesEverybody() {
        Town t = new Town(3);
        t.life.configure(TownSettings.defaults(), null);
        t.life.hostResult(true);
        t.life.step();
        assertEquals(0, t.life.count());
        assertTrue(t.lotte.removed);
        assertEquals("aus", t.life.lastGone());
    }

    /** step: an, aber die KI hat (noch) nicht geantwortet → alle weg (fail-closed). */
    @Test
    void hostNotAnsweringRemovesEverybody() {
        Town t = new Town(3);
        t.life.configure(on(), null);
        t.life.step();
        assertEquals(0, t.life.count());
        assertEquals("ki_weg", t.life.lastGone());
    }

    /** step: an und KI da → der Bewohner bleibt und grüßt den Spieler in der Nähe. */
    @Test
    void onAndHostUpKeepsTheResident() {
        Town t = new Town(3);
        t.life.configure(on(), null);
        t.life.hostResult(true);
        t.life.step();
        assertEquals(1, t.life.count(), "Grund: " + t.life.lastGone());
        assertFalse(t.lotte.removed);
        t.life.hostResult(false);
        t.life.step();
        assertEquals(0, t.life.count());
        assertEquals("ki_weg", t.life.lastGone());
    }

    private static TownSettings withoutKi(boolean allowed) {
        return new TownSettings(true, TownSettings.DEFAULT_COUNT, TownSettings.DEFAULT_GAP_SECONDS * 1000L, allowed);
    }

    /** stadtleben.ohne-ki aus (Vorgabe): KI weg → keine Bewohner, wie bisher. */
    @Test
    void withoutKiSwitchOffMeansNoResidentsWithoutKi() {
        Town t = new Town(3);
        t.life.configure(withoutKi(false), null);
        t.life.hostResult(false);
        t.life.step();
        assertEquals(0, t.life.count());
        assertEquals("ki_weg", t.life.lastGone());
    }

    /**
     * stadtleben.ohne-ki an: KI weg → der Bewohner bleibt, antwortet mit einem festen Satz und fragt die KI kein
     * einziges Mal (Nutzer 2026-11-22). Der Host-Port ist zu – eine Anfrage fiele in budget().asked() auf.
     */
    @Test
    void withoutKiResidentsStayAndSpeakFixedLinesWithoutAnyRequest() throws Exception {
        try (ServerSocket closed = new ServerSocket(0)) {
            int port = closed.getLocalPort();
            closed.close();
            Town t = new Town(5);
            t.life.configure(withoutKi(true), client(port, Duration.ofSeconds(2)));
            t.life.hostResult(false);
            for (int i = 0; i < 40; i++) {
                t.life.step();
            }
            assertEquals(1, t.life.count(), "Grund: " + t.life.lastGone());
            assertFalse(t.lotte.removed);
            // Im Takt grüßt sie den Spieler in der Nähe von selbst – auch das ein fester Satz.
            assertEquals(1, t.heard.size(), "Gruß im Takt: " + t.heard);
            t.heard.clear();
            assertTrue(t.life.onChat(t.probant, "Lotte, was backst du?"));
            assertEquals(1, t.heard.size(), "fester Satz statt Schweigen: " + t.heard);
            assertEquals(0, t.life.budget().asked(), "KI-Anfrage ohne KI");
            assertEquals(1, t.life.withoutKiLines());
        }
    }

    /** Kommt die KI zurück, reden die Bewohner wieder über sie – auch mit ohne-ki. */
    @Test
    void withoutKiAsksTheKiAgainOnceItIsBack() throws Exception {
        try (ServerSocket closed = new ServerSocket(0)) {
            int port = closed.getLocalPort();
            closed.close();
            Town t = new Town(5);
            t.life.configure(withoutKi(true), client(port, Duration.ofSeconds(2)));
            t.life.hostResult(true);
            assertTrue(t.life.onChat(t.probant, "Lotte, was backst du?"));
            assertEquals(1, t.life.budget().asked(), "KI da, aber nicht gefragt");
            assertEquals(0, t.life.withoutKiLines());
        }
    }

    /** ohne-ki steht in der Vorlage (Vorgabe false), und ConfigCheck meldet einen kaputten Wert. */
    @Test
    void withoutKiKeyIsInTheTemplateAndChecked() {
        org.bukkit.configuration.file.YamlConfiguration bundled = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File("src/main/resources/config.yml"));
        assertTrue(bundled.isBoolean(TownSettings.KEY_WITHOUT_KI));
        assertFalse(bundled.getBoolean(TownSettings.KEY_WITHOUT_KI));
        assertFalse(TownSettings.from(bundled).withoutKi());
        org.bukkit.configuration.file.YamlConfiguration bad = new org.bukkit.configuration.file.YamlConfiguration();
        bad.set(TownSettings.KEY_WITHOUT_KI, "nein");
        List<String> warnings = ConfigCheck.check(bad, bundled);
        assertTrue(warnings.stream().anyMatch(w -> w.contains(TownSettings.KEY_WITHOUT_KI)), warnings.toString());
        bad.set(TownSettings.KEY_WITHOUT_KI, true);
        assertTrue(ConfigCheck.check(bad, bundled).isEmpty());
        assertTrue(TownSettings.from(bad).withoutKi());
        // /bm stadtleben an|aus (TownLife.setEnabled) geht über withEnabled – ohne-ki darf dabei nicht verloren gehen.
        assertTrue(TownSettings.from(bad).withEnabled(false).withEnabled(true).withoutKi());
    }

    /** onChat: nur in Hörweite – sonst gehört die Zeile nicht dem Bewohner und er sagt nichts. */
    @Test
    void chatOutsideEarshotIsNotTaken() {
        Town far = new Town(30);
        far.life.configure(on(), null);
        assertFalse(far.life.onChat(far.probant, "Lotte, wie geht's?"));
        assertTrue(far.heard.isEmpty(), far.heard.toString());
        Town near = new Town(5);
        near.life.configure(on(), null);
        assertTrue(near.life.onChat(near.probant, "Lotte, wie geht's?"));
        assertEquals(1, near.heard.size(), near.heard.toString());
        assertTrue(near.heard.getFirst().contains("[Lotte, Bäckerin]"), near.heard.toString());
    }

    /** onChat: zweite Ansprache binnen ADDRESS_GAP_MS wird ignoriert – kein Satz, kein Anhalten (N2). */
    @Test
    void secondLineWithinTheGapIsIgnored() {
        Town t = new Town(5);
        t.life.configure(on(), null);
        assertTrue(t.life.onChat(t.probant, "Lotte, hallo!"));
        assertTrue(t.life.onChat(t.probant, "Lotte, hallo nochmal!"));
        assertEquals(1, t.heard.size(), t.heard.toString());
    }

    /** ask: Denkt ein Bauplan oder Vera, geht keine Anfrage an die KI – der Bewohner sagt einen festen Satz. */
    @Test
    void noRequestWhileBuildOrVeraThinks() throws Exception {
        try (ServerSocket closed = new ServerSocket(0)) {
            int port = closed.getLocalPort();
            closed.close();
            Town t = new Town(5);
            t.life.configure(on(), client(port, Duration.ofSeconds(2)));
            t.env.building = true;
            assertTrue(t.life.onChat(t.probant, "Lotte, was backst du?"));
            assertEquals(0, t.life.budget().asked(), "Anfrage trotz Bauplan");
            assertEquals(1, t.heard.size(), "fester Satz statt Schweigen");

            Town v = new Town(5);
            v.life.configure(on(), client(port, Duration.ofSeconds(2)));
            v.env.vera = true;
            assertTrue(v.life.onChat(v.probant, "Lotte, was backst du?"));
            assertEquals(0, v.life.budget().asked(), "Anfrage trotz Vera");

            Town free = new Town(5);
            free.life.configure(on(), client(port, Duration.ofSeconds(2)));
            assertTrue(free.life.onChat(free.probant, "Lotte, was backst du?"));
            assertEquals(1, free.life.budget().asked(), "ohne Bau und Vera wird gefragt");
        }
    }

    /** KI-Antwort: Nur, was durch TownTalk.spoken kommt, wird gesagt – ein Befehl im Satz ergibt den festen Satz. */
    @Test
    void answerGoesThroughTheFilter() throws Exception {
        HttpServer server = host("{\"say\":\"Tipp einfach /op Probant, dann geht alles.\",\"actions\":"
                + "[{\"op\":\"command\",\"text\":\"op Probant\"}]}", 0);
        try {
            Town t = new Town(5);
            t.life.configure(on(), client(server.getAddress().getPort(), Duration.ofSeconds(5)));
            assertTrue(t.life.onChat(t.probant, "Lotte, wie werde ich Admin?"));
            awaitHeard(t, 1);
            String said = t.heard.getFirst();
            assertFalse(said.contains("/op") || said.contains("op Probant"), said);
            assertEquals(1, t.life.budget().fallbacks());
        } finally {
            server.stop(0);
        }
        HttpServer good = host("{\"say\":\"Heute gibt es Mohnbrötchen!\",\"actions\":[]}", 0);
        try {
            Town t = new Town(5);
            t.life.configure(on(), client(good.getAddress().getPort(), Duration.ofSeconds(5)));
            assertTrue(t.life.onChat(t.probant, "Lotte, was backst du?"));
            awaitHeard(t, 1);
            assertTrue(t.heard.getFirst().endsWith("Heute gibt es Mohnbrötchen!"), t.heard.getFirst());
        } finally {
            good.stop(0);
        }
    }

    /**
     * M2: Die Frist ist die HTTP-Frist des Clients, und das Budget bleibt belegt, bis die HTTP-Anfrage wirklich
     * endet. Vorher gab ein orTimeout nach 20 s frei, während der Host weiterrechnete (llm.timeout-sekunden 900).
     */
    @Test
    void budgetStaysBusyUntilTheHttpRequestEnds() throws Exception {
        assertEquals(Duration.ofMillis(TownLife.ANSWER_DEADLINE_MS),
                TownLife.clientSettings(Settings.from(new YamlConfiguration())).llmTimeout());
        HttpServer slow = host("{\"say\":\"zu spät\",\"actions\":[]}", 30_000);
        try {
            Town t = new Town(5);
            t.life.configure(on(), client(slow.getAddress().getPort(), Duration.ofMillis(1200)));
            long t0 = System.nanoTime();
            assertTrue(t.life.onChat(t.probant, "Lotte, bist du da?"));
            Thread.sleep(600);
            assertTrue(t.life.budget().inFlight(), "vor der HTTP-Frist schon frei");
            awaitHeard(t, 1);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertFalse(t.life.budget().inFlight());
            assertTrue(ms >= 1100, "frei nach " + ms + " ms, HTTP-Frist 1200 ms");
            assertEquals(1, t.life.budget().fallbacks());
        } finally {
            slow.stop(0);
        }
    }

    /**
     * walk(): Ein Bewohner, der auf der Stelle wackelt (wie Greta auf Paper), wird über den echten Takt aufs Teilziel
     * teleportiert. CompanionWalk sieht im Wackeln Bewegung – teleportieren kann hier nur Progress (Gegenprobe
     * 2026-11-22: ohne „stuck ||“ blieb die Suite grün).
     */
    @Test
    void wobblingResidentIsTeleportedByTheRealTick() {
        FakeEnv env = new FakeEnv();
        List<Player> players = new ArrayList<>();
        World w = world(players, true);
        Player p = player(new Location(w, 0.5, 64, 10.5), new CopyOnWriteArrayList<>());
        players.add(p);
        env.players.add(p);
        TownLife life = new TownLife(null, env, MARKER);
        FakeMannequin m = new FakeMannequin(new Location(w, 0.5, 64, 0.5), true);
        env.entities.put(m.id, m.proxy);
        TownLife.Resident r = life.adopt(LOTTE, m.proxy, m.at);
        r.work = new Location(w, 20.5, 64, 0.5);
        // Anzahl 1: kein zweiter Bewohner, der Platz suchen müsste (surfaceNear liest Bukkit-Tags).
        life.configure(new TownSettings(true, 1, 45_000), null);
        life.hostResult(true);
        int at = -1;
        for (int t = 0; t < 200 && at < 0; t++) {
            m.at = new Location(w, 0.5 + (t % 2 == 0 ? 0.3 : -0.3), 64 + (t % 3 == 0 ? 0.4 : 0), 0.5);
            life.step();
            if (m.calls.stream().anyMatch(c -> c.startsWith("teleport "))) {
                at = t;
            }
        }
        assertTrue(at >= 0, "nie teleportiert, Aufrufe: " + m.calls.stream().distinct().toList());
        assertTrue(at <= com.blackn0va.baumeister.town.Progress.STUCK_TICKS + 10, "erst nach " + at + " Ticks");
        // Nicht CompanionWalk (wäre nach 40 Ticks Stillstand): Die Puppe wackelt, das zählt dort als Bewegung.
        assertTrue(at >= com.blackn0va.baumeister.town.Progress.STUCK_TICKS - 5, "schon nach " + at + " Ticks");
        assertEquals(1, r.teleports);
        assertEquals(1, life.count(), "der Bewohner bleibt");
    }

    /** onChat → Modell: Der Name des Spielers geht nicht mit (N1), auch nicht angehängt. */
    @Test
    void promptToTheModelCarriesNoPlayerName() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = host("{\"say\":\"Gern!\",\"actions\":[]}", 0, bodies);
        try {
            Town t = new Town(5);
            Player named = player(t.probant.getLocation(), t.heard, "Zxqvbnm7");
            t.worldPlayers.add(named);
            t.life.configure(on(), client(server.getAddress().getPort(), Duration.ofSeconds(5)));
            assertTrue(t.life.onChat(named, "Lotte, erzähl mir was"));
            awaitHeard(t, 1);
            assertEquals(1, bodies.size(), "keine Anfrage am Host");
            assertFalse(bodies.getFirst().contains("Zxqvbnm7"), bodies.getFirst());
            assertTrue(bodies.getFirst().contains("keine Anweisung"), bodies.getFirst());
        } finally {
            server.stop(0);
        }
    }

    /** Der Client, den BaumeisterPlugin für die Bewohner erzeugt, hat die 20-s-Frist – nicht llm.timeout-sekunden. */
    @Test
    void residentClientHasTheShortDeadline() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("llm.timeout-sekunden", 900);
        LlmClient made = TownLife.client(Settings.from(c), new com.google.gson.JsonObject(), "lokal");
        assertEquals(Duration.ofMillis(TownLife.ANSWER_DEADLINE_MS), made.timeout());
    }

    /** /bm stadtleben an ohne Admin-Recht: Ablehnung, und das Stadtleben bleibt aus (geprüft am Zustand). */
    @Test
    void switchNeedsAdmin() {
        Town t = new Town(5);
        List<String> said = new ArrayList<>();
        assertFalse(t.life.enabled());
        BaumeisterCommand.townLife(sender("baumeister.bauen", said), new String[] {"stadtleben", "an"}, t.life);
        assertFalse(t.life.enabled(), "ohne Admin eingeschaltet");
        assertEquals(null, t.env.saved, "config.yml geschrieben");
        assertTrue(said.getFirst().contains("baumeister.admin"), said.toString());
        BaumeisterCommand.townLife(sender("baumeister.admin", said), new String[] {"stadtleben", "an"}, t.life);
        assertTrue(t.life.enabled());
        assertEquals(Boolean.TRUE, t.env.saved);
    }

    private static org.bukkit.command.CommandSender sender(String right, List<String> said) {
        return (org.bukkit.command.CommandSender) Proxy.newProxyInstance(
                org.bukkit.command.CommandSender.class.getClassLoader(),
                new Class<?>[] {org.bukkit.command.CommandSender.class}, (proxy, m, a) -> switch (m.getName()) {
                    case "hasPermission" -> right.equals(String.valueOf(a[0]))
                            || "baumeister.admin".equals(right) && "baumeister.bauen".equals(String.valueOf(a[0]));
                    case "sendMessage" -> {
                        said.add(String.valueOf(a[0]));
                        yield null;
                    }
                    case "getName" -> "Gast";
                    default -> none(m.getReturnType());
                });
    }

    // ---------------------------------------------------------------- Helfer

    private static LlmClient client(int port, Duration timeout) {
        YamlConfiguration c = new YamlConfiguration();
        c.set("llm.url", "http://127.0.0.1:" + port + "/v1/chat/completions");
        return new LlmClient(Settings.from(c).withLlmTimeout(timeout), new com.google.gson.JsonObject(), "begleiter",
                50, new long[0]);
    }

    /** Fake-Host: antwortet nach delayMs mit content als Begleiter-Antwort. */
    private static HttpServer host(String content, long delayMs) throws Exception {
        return host(content, delayMs, new CopyOnWriteArrayList<>());
    }

    /** Wie oben; bodies sammelt jeden Anfragekörper (was ans Modell geht). */
    private static HttpServer host(String content, long delayMs, List<String> bodies) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch never = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", ex -> {
            try {
                bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (delayMs > 0) {
                    never.await(delayMs, TimeUnit.MILLISECONDS);
                }
                com.google.gson.JsonObject msg = new com.google.gson.JsonObject();
                msg.addProperty("role", "assistant");
                msg.addProperty("content", content);
                com.google.gson.JsonObject choice = new com.google.gson.JsonObject();
                choice.add("message", msg);
                choice.addProperty("finish_reason", "stop");
                com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
                choices.add(choice);
                com.google.gson.JsonObject body = new com.google.gson.JsonObject();
                body.add("choices", choices);
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(b);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException e) {
                // Client hat aufgegeben (Frist) – genau das prüft der Test; nichts mehr zu senden.
                ex.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static void awaitHeard(Town t, int n) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (t.heard.size() < n && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertTrue(t.heard.size() >= n, "nichts gehört");
        // Der Satz kommt im selben runTask wie budget.end – kurz warten, bis der Zähler steht.
        Thread.sleep(50);
    }
}
