package com.blackn0va.baumeister;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blackn0va.baumeister.town.Roster;
import com.blackn0va.baumeister.town.TownSettings;
import com.blackn0va.baumeister.town.WorkSpots;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stadtleben v009 (AK-30): Arbeitsorte an vorhandenen Orten und die Verdrahtung, die bis 2026-11-22 ohne Test war –
 * BukkitEnv (Server, Scheduler, Plugin-Teile, Ortsquellen) und die Anmeldung als Listener in onEnable.
 */
class TownLifeWiringTest {

    private static final Roster.Person LOTTE = Roster.all().getFirst();
    private static final Roster.Person EMIL = Roster.all().get(1);
    private static final Roster.Person THIRD = Roster.all().get(2);

    // ---------------------------------------------------------------- Arbeitsorte

    /** Stadt in einer flachen, ganz geladenen Welt mit einem Spieler bei 10/64/0. */
    private static final class Town {
        final TownLifeTest.FakeEnv env = new TownLifeTest.FakeEnv();
        final List<Player> worldPlayers = new ArrayList<>();
        final World world = TownLifeTest.world(worldPlayers, true);
        final TownLife life = new TownLife(null, env, TownLifeTest.MARKER);

        Town() {
            Player p = TownLifeTest.player(new Location(world, 10.5, 64, 0.5), new CopyOnWriteArrayList<>());
            worldPlayers.add(p);
            env.players.add(p);
            life.configure(TownSettings.defaults().withEnabled(true), null);
        }

        TownLife.Resident resident(Roster.Person who) {
            TownLifeTest.FakeMannequin m = new TownLifeTest.FakeMannequin(new Location(world, 0.5, 64, 0.5), true);
            env.entities.put(m.id, m.proxy);
            return life.adopt(who, m.proxy, m.at);
        }

        Location home() {
            return new Location(world, 0.5, 64, 0.5);
        }
    }

    /** Log-Zeilen des Plugins mitschreiben (Beleg „arbeitsort“). */
    private static final class Lines extends Handler implements AutoCloseable {
        final List<String> lines = new CopyOnWriteArrayList<>();
        private final Logger log = Logger.getLogger("Baumeister");

        Lines() {
            log.addHandler(this);
        }

        @Override
        public void publish(LogRecord r) {
            lines.add(r.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            log.removeHandler(this);
        }
    }

    @Test
    void residentWorksAtAWarpNearThePlayers() {
        Town t = new Town();
        t.env.spots.add(WorkSpots.Spot.point(WorkSpots.Source.WARP, "Markt", "testwelt", 30.5, 64, 0.5));
        TownLife.Resident r = t.resident(LOTTE);
        try (Lines log = new Lines()) {
            Location work = t.life.chooseWork(r, t.home());
            assertEquals(30, work.getBlockX(), "am Warp, nicht auf dem Platz: " + work);
            assertEquals(0, work.getBlockZ());
            assertNotNull(r.workAt);
            assertEquals(WorkSpots.Source.WARP, r.workAt.spot().source());
            assertTrue(log.lines.stream().anyMatch(l -> l.startsWith("stadtleben | arbeitsort | bewohner=lotte "
                    + "quelle=warp ort=Markt")), log.lines.toString());
        }
    }

    @Test
    void residentWorksInFrontOfAFinishedBuild() {
        Town t = new Town();
        t.env.spots.add(new WorkSpots.Spot(WorkSpots.Source.BUILD, "haus #1", "testwelt", 20, 64, -5, 29, 4));
        TownLife.Resident r = t.resident(LOTTE);
        Location work = t.life.chooseWork(r, t.home());
        assertEquals(WorkSpots.Source.BUILD, r.workAt.spot().source());
        assertEquals((int) Math.floor(20 - WorkSpots.BESIDE), work.getBlockX(), "vor der Westwand: " + work);
        assertEquals(0, work.getBlockZ());
    }

    /** Ohne passenden Ort der alte Platz nahe der Wohnung – auch, wenn der Ort zu weit von jedem Spieler liegt. */
    @Test
    void farSpotFallsBackToThePlaza() {
        Town t = new Town();
        t.env.spots.add(WorkSpots.Spot.point(WorkSpots.Source.WARP, "Fern", "testwelt", 300.5, 64, 0.5));
        TownLife.Resident r = t.resident(LOTTE);
        try (Lines log = new Lines()) {
            Location work = t.life.chooseWork(r, t.home());
            assertNull(r.workAt);
            double d = Math.hypot(work.getX() - 0.5, work.getZ() - 0.5);
            assertTrue(d >= 11 && d <= 23, "Platz 12–22 Blöcke von der Wohnung: " + work);
            assertTrue(log.lines.stream().anyMatch(l -> l.contains("quelle=platz")), log.lines.toString());
        }
    }

    /** Höchstens zwei am selben Ort; der dritte nimmt den nächsten oder den Platz. */
    @Test
    void atMostTwoResidentsShareAWarp() {
        Town t = new Town();
        t.env.spots.add(WorkSpots.Spot.point(WorkSpots.Source.WARP, "Markt", "testwelt", 30.5, 64, 0.5));
        TownLife.Resident a = t.resident(LOTTE);
        a.work = t.life.chooseWork(a, t.home());
        TownLife.Resident b = t.resident(EMIL);
        b.work = t.life.chooseWork(b, t.home());
        TownLife.Resident c = t.resident(THIRD);
        t.life.chooseWork(c, t.home());
        assertNotNull(a.workAt);
        assertNotNull(b.workAt);
        assertNull(c.workAt, "dritter am selben Warp");
    }

    // ---------------------------------------------------------------- BukkitEnv

    /** Plugin-Teile zum Einstellen. */
    private static final class FakeParts implements TownLife.Parts {
        boolean planning;
        boolean building;
        int lag;
        boolean vera;
        String npc = "Baumeister";
        String companion = "Vera";
        Boolean saved;
        final Map<String, Location> warps = new LinkedHashMap<>();
        final List<PlaceRegistry.Place> places = new ArrayList<>();
        Path ledger;
        boolean placesFail;

        @Override
        public boolean planning() {
            return planning;
        }

        @Override
        public boolean building() {
            return building;
        }

        @Override
        public int lagActive() {
            return lag;
        }

        @Override
        public boolean companionThinking() {
            return vera;
        }

        @Override
        public String npcName() {
            return npc;
        }

        @Override
        public String companionName() {
            return companion;
        }

        @Override
        public void saveEnabled(boolean on) {
            saved = on;
        }

        @Override
        public Map<String, Location> warps() {
            return warps;
        }

        @Override
        public List<PlaceRegistry.Place> places() {
            if (placesFail) {
                throw new IllegalStateException("orte.json kaputt");
            }
            return places;
        }

        @Override
        public Path ledgerDir() {
            return ledger;
        }
    }

    /** Server mit Scheduler, Plugin-Manager und einer Welt; schreibt jeden Scheduler-Aufruf mit. */
    private static final class FakeServer {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<UUID, Entity> entities = new LinkedHashMap<>();
        final List<Player> online = new ArrayList<>();
        final Map<String, Plugin> plugins = new LinkedHashMap<>();
        final World world = TownLifeTest.world(new ArrayList<>(), true);
        final UUID worldId = UUID.randomUUID();
        final Server proxy;
        boolean enabled = true;
        /** Gesetzt: runTaskAsynchronously wirft wie beim Abschalten (IllegalPluginAccessException). */
        boolean asyncRefused;
        final Plugin owner = plugin(() -> enabled);

        FakeServer() {
            BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
                    new Class<?>[] {BukkitScheduler.class}, (p, m, a) -> {
                        switch (m.getName()) {
                            case "runTask", "runTaskAsynchronously" -> {
                                if (asyncRefused && m.getName().equals("runTaskAsynchronously")) {
                                    calls.add("abgelehnt");
                                    throw new IllegalStateException("Plugin abgeschaltet");
                                }
                                calls.add(m.getName() + (a[0] == owner ? " owner" : " fremd"));
                                ((Runnable) a[1]).run();
                            }
                            case "runTaskLater" -> {
                                calls.add("runTaskLater" + (a[0] == owner ? " owner " : " fremd ") + a[2]);
                                ((Runnable) a[1]).run();
                            }
                            default -> throw new UnsupportedOperationException(m.getName());
                        }
                        return null;
                    });
            PluginManager pm = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                    new Class<?>[] {PluginManager.class}, (p, m, a) -> switch (m.getName()) {
                        case "getPlugin" -> plugins.get((String) a[0]);
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
            proxy = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "getEntity" -> entities.get((UUID) a[0]);
                        case "getOnlinePlayers" -> online;
                        case "getScheduler" -> scheduler;
                        case "getPluginManager" -> pm;
                        case "getWorld" -> a[0] instanceof UUID id && id.equals(worldId) ? world : null;
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }
    }

    private static Plugin plugin(java.util.function.BooleanSupplier enabled) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isEnabled" -> enabled.getAsBoolean();
                    case "getName" -> "Baumeister";
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    case "toString" -> "Plugin";
                    default -> TownLifeTest.none(m.getReturnType());
                });
    }

    /** Jeder Einstieg geht an den Server dieses Plugins bzw. an das richtige Plugin-Teil. */
    @Test
    void bukkitEnvRoutesEveryEntryToServerAndPlugin() {
        FakeServer s = new FakeServer();
        FakeParts parts = new FakeParts();
        TownLife.BukkitEnv env = new TownLife.BukkitEnv(s.proxy, s.owner, parts);
        TownLifeTest.FakeMannequin m = new TownLifeTest.FakeMannequin(new Location(s.world, 0, 64, 0), true);
        s.entities.put(m.id, m.proxy);
        assertSame(m.proxy, env.entity(m.id));
        assertNull(env.entity(UUID.randomUUID()));
        Player p = TownLifeTest.player(new Location(s.world, 0, 64, 0), new ArrayList<>());
        s.online.add(p);
        assertEquals(List.of(p), List.copyOf((Collection<? extends Player>) env.players()));

        boolean[] ran = new boolean[3];
        env.now(() -> ran[0] = true);
        env.later(() -> ran[1] = true, 60L);
        assertTrue(ran[0] && ran[1]);
        assertEquals(List.of("runTask owner", "runTaskLater owner 60"), s.calls);
        s.enabled = false;
        env.now(() -> ran[2] = true);
        assertFalse(ran[2], "nach dem Abschalten keine Aufgabe mehr");

        assertFalse(env.slow());
        parts.building = true;
        assertTrue(env.slow(), "Bau läuft");
        parts.building = false;
        parts.lag = 1;
        assertTrue(env.slow(), "Lag-Wache misst");
        parts.lag = 0;
        assertFalse(env.buildPlanning());
        parts.planning = true;
        assertTrue(env.buildPlanning());
        assertFalse(env.companionThinking());
        parts.vera = true;
        assertTrue(env.companionThinking());

        assertEquals(java.util.Set.of("Baumeister", "Vera"), env.reservedNames());
        parts.companion = null;
        parts.npc = null;
        assertTrue(env.reservedNames().isEmpty(), "ohne Begleiter kein null im Satz");
        env.saveEnabled(true);
        assertEquals(Boolean.TRUE, parts.saved);
    }

    /** EssentialsX nur über Reflexion – Attrappe mit denselben Methodennamen wie IEssentials/IWarps (javap 2.22.1). */
    public interface FakeEssentials extends Plugin {
        FakeWarps getWarps();
    }

    public static final class FakeWarps {
        final Map<String, Location> all = new LinkedHashMap<>();

        public Collection<String> getList() {
            return List.copyOf(all.keySet());
        }

        int asked;

        public Location getWarp(String name) throws Exception {
            asked++;
            Location l = all.get(name);
            if (l == null) {
                // Wie Essentials bei einem Warp in einer nicht geladenen Welt (InvalidWorldException).
                throw new Exception("Welt fehlt");
            }
            return l;
        }
    }

    private static FakeEssentials essentials(FakeWarps warps, boolean enabled) {
        return (FakeEssentials) Proxy.newProxyInstance(FakeEssentials.class.getClassLoader(),
                new Class<?>[] {FakeEssentials.class}, (p, m, a) -> switch (m.getName()) {
                    case "getWarps" -> warps;
                    case "isEnabled" -> enabled;
                    case "getName" -> "Essentials";
                    default -> TownLifeTest.none(m.getReturnType());
                });
    }

    /** Alle vier Quellen kommen an: Essentials-Warps, Baumeister-Warps, Reisepunkte, Bauten aus dem Baubuch. */
    @Test
    void bukkitEnvCollectsEveryKindOfPlace(@TempDir Path dir) throws Exception {
        FakeServer s = new FakeServer();
        FakeParts parts = new FakeParts();
        FakeWarps ess = new FakeWarps();
        ess.all.put("Marktplatz", new Location(s.world, 30, 64, 0));
        ess.all.put("Kaputt", null);
        s.plugins.put("Essentials", essentials(ess, true));
        parts.warps.put("Hafen", new Location(s.world, 0, 64, 30));
        parts.places.add(new PlaceRegistry.Place("Rathaus", PlaceRegistry.Kind.CUSTOM, "testwelt", -20, 70, 0, 0, 0,
                Instant.EPOCH));
        new BuildLedger(dir).add(UUID.randomUUID(), "Probant", "haus", s.worldId, 0L,
                List.of(new BuildLedger.Cell(5, 64, 5, "minecraft:stone"), new BuildLedger.Cell(9, 66, 9,
                        "minecraft:stone")), false);
        parts.ledger = dir;
        TownLife.BukkitEnv env = new TownLife.BukkitEnv(s.proxy, s.owner, parts);
        List<WorkSpots.Spot> spots = env.workSpots();
        List<String> got = spots.stream().map(x -> x.source().label() + " " + x.name() + " " + x.world()).toList();
        assertTrue(got.contains("warp Marktplatz testwelt"), got.toString());
        assertTrue(got.contains("warp Hafen testwelt"), got.toString());
        assertTrue(got.contains("reisepunkt Rathaus testwelt"), got.toString());
        assertTrue(got.stream().anyMatch(g -> g.startsWith("bau haus #") && g.endsWith(" testwelt")), got.toString());
        assertFalse(got.toString().contains("Kaputt"), "Warp, der wirft, fällt nur selbst heraus");
        WorkSpots.Spot house = spots.stream().filter(x -> x.source() == WorkSpots.Source.BUILD).findFirst().orElseThrow();
        assertEquals(List.of(5.0, 64.0, 5.0, 9.0, 9.0), List.of(house.minX(), house.y(), house.minZ(), house.maxX(),
                house.maxZ()));
        assertTrue(s.calls.contains("runTaskAsynchronously owner"), "Baubuch nicht im Hauptthread gelesen");
        // Gepuffert: Der nächste Bewohner fragt Essentials nicht erneut (kaputte Warp-Datei = Stacktrace je Aufruf).
        assertEquals(2, ess.asked);
        assertTrue(env.workSpots().stream().anyMatch(x -> x.name().equals("Marktplatz")));
        assertEquals(2, ess.asked, "Essentials-Warps binnen ESSENTIALS_REFRESH_MS neu gelesen");

        s.plugins.put("Essentials", essentials(ess, false));
        assertFalse(env.workSpots().stream().anyMatch(x -> x.name().equals("Marktplatz")),
                "abgeschaltetes Essentials liefert nichts");
    }

    /** Wirft die Ortsquelle selbst, bekommt der schon eingetragene Bewohner trotzdem einen Arbeitsort (Platz). */
    @Test
    void throwingWorkSpotsStillGiveAWorkplace() {
        Town t = new Town();
        t.env.spotsFail = new IllegalStateException("Quelle kaputt");
        TownLife.Resident r = t.resident(LOTTE);
        Location work = t.life.chooseWork(r, t.home());
        assertNotNull(work);
        assertNull(r.workAt);
        double d = Math.hypot(work.getX() - 0.5, work.getZ() - 0.5);
        assertTrue(d >= 11 && d <= 23, "Platz nahe der Wohnung: " + work);
        assertEquals(1, t.life.count(), "Bewohner bleibt verfolgt");
    }

    /** Eine kaputte Quelle (Reisepunkte) kostet nur ihre Orte – Warps und Bauten bleiben. */
    @Test
    void oneThrowingSourceKeepsTheOthers(@TempDir Path dir) {
        FakeServer s = new FakeServer();
        FakeParts parts = new FakeParts();
        parts.warps.put("Hafen", new Location(s.world, 0, 64, 30));
        parts.places.add(new PlaceRegistry.Place("Rathaus", PlaceRegistry.Kind.CUSTOM, "testwelt", -20, 70, 0, 0, 0,
                Instant.EPOCH));
        parts.placesFail = true;
        parts.ledger = dir;
        TownLife.BukkitEnv env = new TownLife.BukkitEnv(s.proxy, s.owner, parts);
        List<String> got = env.workSpots().stream().map(WorkSpots.Spot::name).toList();
        assertEquals(List.of("Hafen"), got);
    }

    /** Lehnt der Scheduler die Hintergrundaufgabe ab, liest der nächste Aufruf das Baubuch erneut (nicht für immer aus). */
    @Test
    void refusedLedgerReadIsRetried(@TempDir Path dir) {
        FakeServer s = new FakeServer();
        FakeParts parts = new FakeParts();
        new BuildLedger(dir).add(UUID.randomUUID(), "Probant", "haus", s.worldId, 0L,
                List.of(new BuildLedger.Cell(5, 64, 5, "minecraft:stone")), false);
        parts.ledger = dir;
        TownLife.BukkitEnv env = new TownLife.BukkitEnv(s.proxy, s.owner, parts);
        s.asyncRefused = true;
        assertTrue(env.workSpots().isEmpty(), "abgelehnt: noch keine Bauten, aber keine Ausnahme");
        s.asyncRefused = false;
        assertTrue(env.workSpots().stream().anyMatch(x -> x.source() == WorkSpots.Source.BUILD),
                "nach der Ablehnung nie wieder gelesen: " + s.calls);
        assertEquals(List.of("abgelehnt", "runTaskAsynchronously owner"), s.calls);
    }

    /** Ein fremdes Objekt ohne getWarps (andere Essentials-Fassung) kostet nur die Essentials-Warps. */
    @Test
    void essentialsWithoutWarpsApiGivesNothing() {
        assertTrue(TownLife.essentialsWarps(new Object()).isEmpty());
    }

    // ---------------------------------------------------------------- Anmeldung im Plugin

    /**
     * BaumeisterPlugin.onEnable meldet TownLife als Listener an und startet den Takt. Ein JavaPlugin lässt sich ohne
     * Server nicht starten, also prüft der Test den Quelltext von onEnable (ohne Kommentare) – fehlt die Zeile, schützt
     * onInteract keine Druckplatte mehr, und die Suite bliebe sonst grün (Gedächtnis „Offen Stadtleben v1“).
     */
    @Test
    void onEnableRegistersTownLifeAsListenerAndStartsIt() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/blackn0va/baumeister/BaumeisterPlugin.java"),
                StandardCharsets.UTF_8);
        String body = method(src, "public void onEnable()");
        String code = body.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        Matcher reg = Pattern.compile("registerEvents\\(\\s*town\\s*,\\s*this\\s*\\)").matcher(code);
        assertTrue(reg.find(), "registerEvents(town, this) fehlt in onEnable");
        int start = code.indexOf("town.start()");
        assertTrue(start > reg.start(), "town.start() fehlt oder steht vor der Anmeldung");
        assertTrue(code.indexOf("load()") >= 0 && code.indexOf("load()") < reg.start(),
                "load() (legt town an) muss vor der Anmeldung laufen");
        // Die Anmeldung wirkt nur, weil TownLife Handler hat – onInteract ist einer.
        Method onInteract = TownLife.class.getMethod("onInteract", org.bukkit.event.entity.EntityInteractEvent.class);
        assertNotNull(onInteract.getAnnotation(EventHandler.class));
    }

    /** Rumpf einer Methode ab ihrer Signatur bis zur passenden schließenden Klammer. */
    private static String method(String src, String signature) {
        int at = src.indexOf(signature);
        assertTrue(at >= 0, signature + " nicht gefunden");
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(open, i + 1);
            }
        }
        throw new AssertionError("Rumpf von " + signature + " endet nicht");
    }
}
