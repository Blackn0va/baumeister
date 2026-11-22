package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.companion.CompanionWalk;
import com.blackn0va.baumeister.town.DayPlan;
import com.blackn0va.baumeister.town.HostWatch;
import com.blackn0va.baumeister.town.Population;
import com.blackn0va.baumeister.town.Progress;
import com.blackn0va.baumeister.town.Roster;
import com.blackn0va.baumeister.town.Route;
import com.blackn0va.baumeister.town.TalkBudget;
import com.blackn0va.baumeister.town.TickCost;
import com.blackn0va.baumeister.town.TownLines;
import com.blackn0va.baumeister.town.TownSettings;
import com.blackn0va.baumeister.town.TownTalk;
import com.blackn0va.baumeister.town.WorkSpots;
import com.blackn0va.baumeister.town.memory.TownVoice;
import com.blackn0va.baumeister.town.presence.Gestures;
import com.blackn0va.baumeister.town.presence.Presence;
import com.google.gson.JsonObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Stadtleben (Vertrag v008, Wunsch 2026-11-21): Ist der Modus an und die lokale KI erreichbar, laufen einige Bewohner
 * (Mannequins) nahe den online Spielern herum, folgen einem Tagesablauf nach Weltzeit, schauen Spieler an, grüßen
 * und reden per KI – ohne die Welt zu verändern.
 *
 * Die Regeln stehen ohne Bukkit testbar im Paket town (Tagesablauf, Sollzahl, KI-Budget, Filter, Wegsuche-Drossel,
 * Messung). Diese Klasse liegt im Wurzelpaket, weil sie LlmClient, Texts und MannequinCompat braucht, die hier
 * paketweit sind.
 *
 * Grenzen, alle mit Absicht:
 * - Keine Weltänderung: Bewohner setzen und brechen nichts, öffnen keine Türen (Wegsuche ohne Türen), lösen keine
 *   Druckplatten aus und zertreten kein Ackerland (EntityInteractEvent abgesagt). KI-Aktionen werden verworfen.
 * - Nicht persistent (setPersistent(false)) und mit PDC-Marke „bewohner“; was nach einem harten Abbruch übrig bleibt,
 *   räumt BaumeisterListener.onEntitiesLoad weg (isStale).
 * - Takt: Wegsuche nur in geladenen Chunks (CompanionService.pathBlocks liest ungeladene als Fels), höchstens eine
 *   Suche je Tick, beim Bau oder laufender Lag-Wache seltener; kein getTPS. Gemessen wird jeder Takt (TickCost).
 * - Chat: Angesprochen wird ein Bewohner nur, wenn die Zeile mit seinem Namen beginnt und der Spieler in Hörweite
 *   (TownTalk.HEAR) steht. Gespeichert wird nichts, auch nicht im Log (dort stehen nur Name, Anlass, Dauer).
 *
 * Threads: Hauptthread; KI-Antworten kommen per runTask zurück. Fehler: KI-Fehler → fester Satz, nie Ausnahme.
 */
final class TownLife implements Listener {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Spazier-Tempo: 3,2 Blöcke/s, langsamer als ein gehender Spieler (4,3) – Bewohner schlendern. */
    static final double SPEED = 0.16;
    /** So nah gilt ein Ziel als erreicht (Blöcke, waagrecht). */
    static final double ARRIVE = 1.2;
    /**
     * Frist einer Bewohner-Antwort als HTTP-Frist des Clients (clientSettings). Ein 7B-Satz braucht auf dem PC
     * 7–11 s (Live-Probe 2026-11-21); danach fester Satz.
     */
    static final long ANSWER_DEADLINE_MS = 20_000;
    /** Abstand, ab dem ein Bewohner einem Spieler Platz macht (waagrecht). */
    static final double YIELD = 1.3;
    /** Spieler in dieser Nähe werden angeschaut. */
    static final double LOOK = 8.0;
    /** Spieler in dieser Nähe werden gegrüßt. */
    static final double GREET = 5.0;
    static final long GREET_AGAIN_MS = 5 * 60_000L;
    /** Zwei Bewohner reden höchstens so oft miteinander. */
    static final long MEET_AGAIN_MS = 3 * 60_000L;
    static final double MEET = 4.0;
    /** Abstand zwischen zwei Ansprachen desselben Spielers an denselben Bewohner. */
    static final long ADDRESS_GAP_MS = 5_000;

    private final BaumeisterPlugin plugin;
    private final NamespacedKey marker;
    private final List<Resident> residents = new ArrayList<>();
    private final HostWatch host = new HostWatch();
    private final TickCost cost = new TickCost();
    private final Map<String, Long> meetings = new HashMap<>();
    /** Wann ein Spieler einen Bewohner zuletzt angesprochen hat (Abklingzeit, nur im Speicher). */
    private final Map<String, Long> addressed = new HashMap<>();
    /** Grund, aus dem zuletzt ein Bewohner ging (Log und Test). */
    private String lastGone = "";
    private TalkBudget budget = new TalkBudget(TownSettings.defaults().talkGapMillis());
    private TownSettings settings = TownSettings.defaults();
    private LlmClient llm;
    private BukkitTask task;
    private long tick;
    private long lastSpawnTick = Long.MIN_VALUE / 2;
    private int spawnCursor;
    private int playerCursor;
    private int refused;
    private int spawned;
    /** Gedächtnis, Gespräch, Pseudonyme und feste Sätze (Vertrag v013, AK-41); Logik in town/memory/TownVoice. */
    private TownVoice voice = TownVoice.inMemory();

    /** Ein Bewohner im Spiel. */
    static final class Resident {
        final Roster.Person person;
        final UUID entity;
        final Location home;
        Location work;
        /** Woran er arbeitet; null = Platz nahe der Wohnung (kein vorhandener Ort passte). */
        WorkSpots.Choice workAt;
        Location plaza;
        long plazaUntil;
        final CompanionWalk.State walk = new CompanionWalk.State();
        final Route route = new Route();
        final Progress progress = new Progress();
        DayPlan.Goal goal;
        boolean reached;
        int reachedCount;
        int teleports;
        double walked;
        double lastX = Double.NaN;
        double lastZ;
        long nightSince;
        /** Aktuelles Teilziel (Route.leg) samt Ziel, zu dem es gehört; fest, bis es erreicht ist. */
        double[] leg;
        Location legFor;
        long nextSwing;
        long lastSpoke;
        long busyUntil;
        final Map<UUID, Long> greeted = new HashMap<>();
        final long born = System.currentTimeMillis();

        Resident(Roster.Person person, UUID entity, Location home) {
            this.person = person;
            this.entity = entity;
            this.home = home;
        }
    }

    /**
     * Alles, was TownLife von außen braucht – im Betrieb Bukkit und das Plugin (BukkitEnv), im Test ein Fake
     * (TownLifeTest). Vorher rief TownLife Bukkit und plugin.service() direkt; die Gegenprobe 2026-11-22 schaltete neun
     * Schutzstellen ab, und die Suite blieb grün, weil keine davon ohne Server zu erreichen war.
     */
    interface Env {
        Entity entity(UUID id);

        Collection<? extends Player> players();

        /** Im Hauptthread ausführen (aus einem fremden Thread heraus). */
        void now(Runnable r);

        void later(Runnable r, long ticks);

        /** Denkt gerade ein Bauplan (BuildService.planning)? */
        boolean buildPlanning();

        /** Beantwortet gerade ein Begleiter eine Frage (CompanionService.anyThinking)? */
        boolean companionThinking();

        /** Bau läuft oder Lag-Wache misst: Stadtleben drosseln. */
        boolean slow();

        /** Namen, die kein Bewohner tragen darf: Baumeister, Begleiter. */
        Set<String> reservedNames();

        /** stadtleben.an in config.yml schreiben. */
        void saveEnabled(boolean on);

        /** Welt für die Wegsuche (im Betrieb CompanionService.pathBlocks: nur geladene Chunks, ohne Türen). */
        CompanionPath.Blocks pathBlocks(World w);

        /** Steht der Bewohner in Leiter, Ranke oder Gerüst? */
        boolean climbable(Location at);

        /**
         * Stehplatz auf der Oberfläche über (x, z): fester Boden (kein Wasser, keine Lava, kein Magma, kein Laub),
         * darüber zwei Blöcke frei; null, wenn nicht oder der Chunk nicht geladen ist. Vorher stand das in surfaceNear
         * und las Bukkit-Tags direkt – ohne Server lädt deren Klasse nicht, also war jede Ortswahl ungetestet.
         */
        Location surfaceAt(World w, int x, int z);

        /**
         * Stehplatz nahe der Höhe y über (x, z) – fester Boden, darüber zwei Blöcke frei, höchstens STAND_DOWN tiefer
         * oder STAND_UP höher; null, wenn keiner da oder der Chunk nicht geladen ist. Für Orte mit bekannter Höhe
         * (Warp, Reisepunkt, Bau): surfaceAt nimmt den höchsten Block, unter einem Dach also das Dach.
         */
        default Location standNear(World w, int x, int y, int z) {
            return null;
        }

        /** Vorhandene Orte, an denen Bewohner arbeiten können (Warps, Reisepunkte, Bauten), noch ungefiltert. */
        List<WorkSpots.Spot> workSpots();

        /** Wo der Baumeister gerade baut (Ort des NPC), null = kein Bau. Für die Lage im Prompt (AK-41). */
        default Location buildSite() {
            return null;
        }
    }

    /**
     * Was BukkitEnv vom Plugin braucht, je ein Getter. Vorher griff BukkitEnv direkt auf BaumeisterPlugin zu – ein
     * JavaPlugin lässt sich ohne Server nicht bauen, also blieb jeder Einstieg ungetestet (Gedächtnis „Offen
     * Stadtleben v1“). Jetzt steckt die Logik (Drosseln, Namen, Scheduler, Ortsquellen) in BukkitEnv und ist mit einem
     * Fake prüfbar (TownLifeTest.bukkitEnv*); of() ist nur noch Durchreichen.
     */
    interface Parts {
        /** Denkt ein Bauplan (BuildService.planning)? */
        boolean planning();

        /** Läuft ein Bau (BuildService.busy)? */
        boolean building();

        /** Laufende Messungen der Lag-Wache (LagWatch.active). */
        int lagActive();

        boolean companionThinking();

        /** Name des Baumeisters, null = keiner. */
        String npcName();

        /** Name des Begleiters, null = kein Begleiter. */
        String companionName();

        void saveEnabled(boolean on);

        /** Baumeister-Warps (/warp), Name → Ort; Warps in nicht geladenen Welten fehlen. */
        Map<String, Location> warps();

        /** Reisepunkte (/bm orte). */
        List<PlaceRegistry.Place> places();

        /** Ordner des Baubuchs (BuildService: plugins/Baumeister/bauten). */
        Path ledgerDir();

        /** Ort des Baumeisters, solange er baut; sonst null. */
        default Location buildSite() {
            return null;
        }

        static Parts of(BaumeisterPlugin p) {
            return new Parts() {
                @Override
                public Location buildSite() {
                    boolean busy = p.service() != null && p.service().busy();
                    return busy && p.npc() != null && p.npc().get() != null ? p.npc().get().getLocation() : null;
                }

                @Override
                public boolean planning() {
                    return p.service() != null && p.service().planning();
                }

                @Override
                public boolean building() {
                    return p.service() != null && p.service().busy();
                }

                @Override
                public int lagActive() {
                    return p.lagWatch() == null ? 0 : p.lagWatch().active();
                }

                @Override
                public boolean companionThinking() {
                    return p.companion() != null && p.companion().anyThinking();
                }

                @Override
                public String npcName() {
                    return p.npc() == null ? null : p.npc().name();
                }

                @Override
                public String companionName() {
                    return p.companion() == null ? null : p.companion().name();
                }

                @Override
                public void saveEnabled(boolean on) {
                    p.getConfig().set(TownSettings.KEY_ENABLED, on);
                    p.saveConfig();
                }

                @Override
                public Map<String, Location> warps() {
                    Map<String, Location> out = new LinkedHashMap<>();
                    if (p.warps() != null) {
                        for (String n : p.warps().names()) {
                            p.warps().location(n).ifPresent(l -> out.put(n, l));
                        }
                    }
                    return out;
                }

                @Override
                public List<PlaceRegistry.Place> places() {
                    return p.places() == null ? List.of() : p.places().all();
                }

                @Override
                public Path ledgerDir() {
                    // Derselbe Ordner wie in BuildService (new BuildLedger(…resolve("bauten"))).
                    return p.getDataFolder().toPath().resolve("bauten");
                }
            };
        }
    }

    /** Env im Betrieb. */
    static final class BukkitEnv implements Env {

        /** Baubuch so oft neu lesen – Bauten kommen selten hinzu, und gelesen wird außerhalb des Hauptthreads. */
        static final long LEDGER_REFRESH_MS = 5 * 60_000L;
        /** Name des EssentialsX-Plugins (plugin.yml dort: „name: Essentials“, geprüft im Jar 2.22.1). */
        static final String ESSENTIALS = "Essentials";

        private final Server server;
        private final Plugin owner;
        private final Parts parts;
        private volatile List<BuildLedger.Entry> builds = List.of();
        private volatile long buildsAt = Long.MIN_VALUE / 2;
        private volatile boolean buildsLoading;
        /**
         * Essentials-Warps höchstens alle ESSENTIALS_REFRESH_MS neu lesen. Gemessen 2026-11-22 (Testserver): Eine
         * kaputte Warp-Datei lässt Essentials bei JEDEM getWarp einen Stacktrace ins Log schreiben – ohne Puffer bei
         * jedem neuen Bewohner, also bis alle 2 s.
         */
        static final long ESSENTIALS_REFRESH_MS = 60_000L;
        private List<WorkSpots.Spot> essentialsSpots = List.of();
        private long essentialsAt = Long.MIN_VALUE / 2;

        BukkitEnv(Server server, Plugin owner, Parts parts) {
            this.server = server;
            this.owner = owner;
            this.parts = parts;
        }

        @Override
        public Entity entity(UUID id) {
            return server.getEntity(id);
        }

        @Override
        public Collection<? extends Player> players() {
            return server.getOnlinePlayers();
        }

        @Override
        public void now(Runnable r) {
            // Nach dem Abschalten nimmt der Scheduler keine Aufgabe mehr an (IllegalPluginAccessException); eine späte
            // KI-Antwort fällt dann still weg.
            if (owner.isEnabled()) {
                server.getScheduler().runTask(owner, r);
            }
        }

        @Override
        public void later(Runnable r, long ticks) {
            server.getScheduler().runTaskLater(owner, r, ticks);
        }

        @Override
        public boolean buildPlanning() {
            return parts.planning();
        }

        @Override
        public boolean companionThinking() {
            return parts.companionThinking();
        }

        @Override
        public boolean slow() {
            return parts.building() || parts.lagActive() > 0;
        }

        @Override
        public Location buildSite() {
            return parts.buildSite();
        }

        @Override
        public Set<String> reservedNames() {
            Set<String> out = new HashSet<>();
            if (parts.npcName() != null) {
                out.add(parts.npcName());
            }
            if (parts.companionName() != null) {
                out.add(parts.companionName());
            }
            return out;
        }

        @Override
        public void saveEnabled(boolean on) {
            parts.saveEnabled(on);
        }

        @Override
        public CompanionPath.Blocks pathBlocks(World w) {
            return CompanionService.pathBlocks(w);
        }

        @Override
        public boolean climbable(Location at) {
            return org.bukkit.Tag.CLIMBABLE.isTagged(at.getBlock().getType());
        }

        @Override
        public Location surfaceAt(World w, int x, int z) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            int y = w.getHighestBlockYAt(x, z);
            Material g = w.getBlockAt(x, y, z).getType();
            if (!g.isSolid() || g == Material.MAGMA_BLOCK || org.bukkit.Tag.LEAVES.isTagged(g)) {
                return null;
            }
            if (!w.getBlockAt(x, y + 1, z).isPassable() || !w.getBlockAt(x, y + 2, z).isPassable()) {
                return null;
            }
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }

        @Override
        public Location standNear(World w, int x, int y, int z) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            Integer at = standY(y, h -> {
                Material g = w.getBlockAt(x, h, z).getType();
                return g.isSolid() && g != Material.MAGMA_BLOCK && !org.bukkit.Tag.LEAVES.isTagged(g);
            }, h -> {
                // Frei heißt begehbar UND keine Flüssigkeit, kein Feuer: isPassable gilt auch für Wasser, Lava, Feuer
                // und Spinnweben (keine Kollisionsform). Vorher lehnte surfaceAt eine Flüssigkeit oben ab, standNear
                // hätte einen Bewohner an einem Becken mit den Füßen ins Wasser gestellt (Gegenprobe Welle 10).
                org.bukkit.block.Block b = w.getBlockAt(x, h, z);
                Material m = b.getType();
                return b.isPassable() && !b.isLiquid() && !org.bukkit.Tag.FIRE.isTagged(m) && m != Material.COBWEB
                        && m != Material.POWDER_SNOW && m != Material.SWEET_BERRY_BUSH;
            });
            return at == null ? null : new Location(w, x + 0.5, at, z + 0.5);
        }

        @Override
        public List<WorkSpots.Spot> workSpots() {
            List<WorkSpots.Spot> out = new ArrayList<>();
            out.addAll(source("essentials", this::essentials));
            out.addAll(source("warps", this::baumeisterWarps));
            out.addAll(source("reisepunkte", () -> places(parts.places())));
            out.addAll(source("bauten", () -> {
                refreshBuilds();
                return builds(builds, id -> {
                    World w = server.getWorld(id);
                    return w == null ? null : w.getName();
                });
            }));
            return out;
        }

        /** Quellen, die schon einmal geworfen haben – gewarnt wird je Quelle nur einmal. */
        private final Set<String> failedSources = new HashSet<>();

        /**
         * Eine Ortsquelle, abgesichert. Bewusst fail-open: Ein Arbeitsort ist Schmuck, kein Schutz. Wirft eine Quelle
         * (fremdes Plugin, kaputte Liste), fehlen nur ihre Orte, die übrigen und der Platz-Rückfall bleiben. Vorher
         * (Prüfung des Dirigenten 2026-11-22) lief eine Ausnahme aus places()/warps() durch spawn bis zum Scheduler,
         * und der schon eingetragene Bewohner blieb ohne Arbeitsort verfolgt. Wer den catch entfernt, bringt das zurück.
         */
        private List<WorkSpots.Spot> source(String name, java.util.function.Supplier<List<WorkSpots.Spot>> read) {
            try {
                return read.get();
            } catch (RuntimeException e) {
                if (failedSources.add(name)) {
                    LOG.log(java.util.logging.Level.WARNING, "stadtleben | ortsquelle_fehlgeschlagen | quelle=" + name
                            + " grund=" + e, e);
                } else {
                    LOG.fine(() -> "stadtleben | ortsquelle_fehlgeschlagen | quelle=" + name + " grund=" + e);
                }
                return List.of();
            }
        }

        private List<WorkSpots.Spot> essentials() {
            Plugin ess = server.getPluginManager().getPlugin(ESSENTIALS);
            if (ess == null || !ess.isEnabled()) {
                return List.of();
            }
            long now = System.currentTimeMillis();
            if (now - essentialsAt >= ESSENTIALS_REFRESH_MS) {
                essentialsSpots = essentialsWarps(ess);
                essentialsAt = now;
            }
            return essentialsSpots;
        }

        private List<WorkSpots.Spot> baumeisterWarps() {
            List<WorkSpots.Spot> out = new ArrayList<>();
            for (Map.Entry<String, Location> w : parts.warps().entrySet()) {
                Location l = w.getValue();
                if (out.size() >= WorkSpots.MAX_PER_SOURCE) {
                    break;
                }
                if (l.getWorld() != null) {
                    out.add(WorkSpots.Spot.point(WorkSpots.Source.WARP, w.getKey(), l.getWorld().getName(), l.getX(),
                            l.getY(), l.getZ()));
                }
            }
            return out;
        }

        /** Baubuch im Hintergrund neu lesen, höchstens alle LEDGER_REFRESH_MS; bis dahin gilt der letzte Stand. */
        private void refreshBuilds() {
            long now = System.currentTimeMillis();
            if (buildsLoading || now - buildsAt < LEDGER_REFRESH_MS) {
                return;
            }
            long before = buildsAt;
            buildsLoading = true;
            buildsAt = now;
            Path dir = parts.ledgerDir();
            // Nur lesen: Diese BuildLedger-Instanz schreibt nie (kein add/forget). BuildLedger schreibt index.json per
            // ATOMIC_MOVE, ein halber Stand ist also nicht zu sehen.
            try {
                server.getScheduler().runTaskAsynchronously(owner, () -> {
                    try {
                        builds = new BuildLedger(dir).entries();
                    } finally {
                        buildsLoading = false;
                    }
                });
            } catch (RuntimeException e) {
                // Der Scheduler nimmt nichts mehr an (Abschalten: IllegalPluginAccessException). Vorher blieb
                // buildsLoading dann für immer true, und das Baubuch wurde nie wieder gelesen. Zurücksetzen, damit der
                // nächste Bewohner es erneut versucht; die Ausnahme selbst meldet source("bauten").
                buildsLoading = false;
                buildsAt = before;
                throw e;
            }
        }
    }

    /**
     * EssentialsX-Warps über Reflexion: Ohne harte Abhängigkeit lädt keine Essentials-Klasse, und ohne das Plugin
     * läuft alles andere (softdepend). Aufgerufen werden nur IEssentials.getWarps(), IWarps.getList() und
     * IWarps.getWarp(String) – geprüft mit javap im Jar EssentialsX 2.22.1 (2026-11-22).
     *
     * Bewusst fail-open: Ein Fehler im fremden Plugin (andere Fassung, Warp in einer nicht geladenen Welt wirft
     * InvalidWorldException) kostet nur diesen Warp bzw. die Essentials-Warps, nie den Takt.
     */
    static List<WorkSpots.Spot> essentialsWarps(Object essentials) {
        List<WorkSpots.Spot> out = new ArrayList<>();
        try {
            Object warps = essentials.getClass().getMethod("getWarps").invoke(essentials);
            if (warps == null) {
                return out;
            }
            Method getWarp = warps.getClass().getMethod("getWarp", String.class);
            Object names = warps.getClass().getMethod("getList").invoke(warps);
            if (!(names instanceof Collection<?> list)) {
                return out;
            }
            for (Object name : list) {
                if (out.size() >= WorkSpots.MAX_PER_SOURCE) {
                    break;
                }
                String n = String.valueOf(name);
                try {
                    if (getWarp.invoke(warps, n) instanceof Location l && l.getWorld() != null) {
                        out.add(WorkSpots.Spot.point(WorkSpots.Source.WARP, n, l.getWorld().getName(), l.getX(),
                                l.getY(), l.getZ()));
                    }
                } catch (InvocationTargetException e) {
                    LOG.fine(() -> "stadtleben | essentials_warp_übersprungen | warp=" + Texts.clean(n, 32) + " grund="
                            + e.getCause());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Einmal laut, danach leise: Gefragt wird bei jedem neuen Bewohner (bis alle 2 s), eine unpassende
            // Essentials-Fassung schriebe sonst das Log mit Stacktraces voll.
            if (!essentialsWarned) {
                essentialsWarned = true;
                LOG.log(java.util.logging.Level.WARNING, "stadtleben | essentials_warps_unlesbar | grund=" + e, e);
            } else {
                LOG.fine(() -> "stadtleben | essentials_warps_unlesbar | grund=" + e);
            }
        }
        return out;
    }

    private static volatile boolean essentialsWarned;

    /** Reisepunkte als Arbeitsorte. */
    static List<WorkSpots.Spot> places(List<PlaceRegistry.Place> all) {
        List<WorkSpots.Spot> out = new ArrayList<>();
        for (PlaceRegistry.Place p : all) {
            if (out.size() >= WorkSpots.MAX_PER_SOURCE) {
                break;
            }
            out.add(WorkSpots.Spot.point(WorkSpots.Source.PLACE, p.name(), p.world(), p.x(), p.y(), p.z()));
        }
        return out;
    }

    /** Fertige Bauten aus dem Baubuch; worldName: UUID → Name, null = Welt nicht geladen (fällt heraus). */
    static List<WorkSpots.Spot> builds(List<BuildLedger.Entry> entries,
            java.util.function.Function<UUID, String> worldName) {
        List<WorkSpots.Spot> out = new ArrayList<>();
        for (BuildLedger.Entry e : entries) {
            if (out.size() >= WorkSpots.MAX_PER_SOURCE) {
                break;
            }
            String w = e.world() == null ? null : worldName.apply(e.world());
            if (w != null) {
                // Auftrag plus Kurzkennung als Name: Zwei Bauten mit demselben Auftrag („haus“) sind zwei Orte –
                // sonst teilten sie sich die Belegung (WorkSpots.MAX_PER_SPOT).
                String id = e.id() == null ? "" : e.id().substring(0, Math.min(8, e.id().length()));
                out.add(new WorkSpots.Spot(WorkSpots.Source.BUILD, Texts.clean(e.order(), 40) + " #" + id, w,
                        e.minX(), e.minY(), e.minZ(), e.maxX(), e.maxZ()));
            }
        }
        return out;
    }

    private final Env env;
    /**
     * Spieler-Auftritt (T-033, town.presence): Tab-Eintrag, Kommen/Gehen-Zeile, Ausrüstung, Gesten. null = ohne (Tests,
     * die nur den Tagesablauf prüfen). Einhängepunkte nur in spawn, forget, speak und stepResident.
     */
    private Presence presence;

    void presence(Presence p) {
        this.presence = p;
    }

    TownLife(BaumeisterPlugin plugin) {
        this(plugin, new BukkitEnv(plugin.getServer(), plugin, Parts.of(plugin)), new NamespacedKey(plugin,
                "bewohner"));
    }

    /** Für Tests: plugin darf null sein (dann kein start()). */
    TownLife(BaumeisterPlugin plugin, Env env, NamespacedKey marker) {
        this.plugin = plugin;
        this.env = env;
        this.marker = marker;
        if (plugin != null) {
            voice = TownVoice.open(plugin.getDataFolder().toPath().resolve("stadtleben"));
        }
    }

    /** /bm stadtleben vergessen [bewohner] (Admin-Prüfung im Befehl); null = alle. -1 = Bewohner unbekannt. */
    int forgetMemory(String resident) {
        return voice.forget(resident);
    }

    TownVoice voice() {
        return voice;
    }

    /** Alle Bewohner mit Ort – für Hörweite in Gespräch und Lage. */
    private Map<Roster.Person, Location> town() {
        Map<Roster.Person, Location> out = new HashMap<>();
        for (Resident o : residents) {
            if (env.entity(o.entity) instanceof Mannequin om) {
                out.put(o.person, om.getLocation());
            }
        }
        return out;
    }

    private TownTalk.Situation situation(Resident r, Mannequin m, long now) {
        return voice.situation(r.person, m.getLocation(), r.goal == DayPlan.Goal.WORK && r.reached, env.players(),
                town(), env.buildSite(), now);
    }

    private String fixed(Resident r, Mannequin m, TownLines.Occasion o) {
        return voice.fixed(o, r.person, m.getWorld(), ThreadLocalRandom.current().nextInt(1 << 20));
    }

    /**
     * Einstellungen für den KI-Client der Bewohner: HTTP-Frist ANSWER_DEADLINE_MS statt llm.timeout-sekunden
     * (Vorgabe 900 s). Nur so endet die Anfrage am Host mit der Frist, statt dort weiterzurechnen (Settings.withLlmTimeout).
     */
    /**
     * Der KI-Client der Bewohner, wie BaumeisterPlugin ihn benutzt: eigener Client (eigener OutageLog), Begleiter-
     * Schema, kurze Antworten, ein Versuch, HTTP-Frist ANSWER_DEADLINE_MS. Eigene Fabrik statt Aufruf im Plugin
     * (Gegenprobe 2026-11-22: „clientSettings(s)“ durch „s“ ersetzt blieb grün, weil der Plugin-Start keinen Test hat).
     */
    static LlmClient client(Settings s, com.google.gson.JsonObject schema, String model) {
        return new LlmClient(clientSettings(s), schema, "begleiter", TownTalk.MAX_TOKENS, new long[0]).withModel(model);
    }

    static Settings clientSettings(Settings s) {
        java.time.Duration d = java.time.Duration.ofMillis(ANSWER_DEADLINE_MS);
        return s.withLlmTimeout(s.llmTimeout().compareTo(d) < 0 ? s.llmTimeout() : d);
    }

    /** Beim Start und bei /bm neuladen. Bewohner bleiben, nur Einstellungen und Client wechseln. */
    void configure(TownSettings s, LlmClient client) {
        boolean was = settings.enabled();
        this.settings = s;
        this.llm = client;
        this.budget.gap(s.talkGapMillis());
        if (s.enabled() && !was) {
            host.askSoon();
        }
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | einstellungen | an=%s anzahl=%d ki_abstand_s=%d ohne_ki=%s",
                s.enabled(), s.count(), s.talkGapMillis() / 1000, s.withoutKi()));
    }

    /** /bm neuladen: neue Einstellungen, derselbe KI-Client. */
    void reload(TownSettings s) {
        configure(s, llm);
        if (!s.enabled()) {
            removeAll("aus");
        }
    }

    void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        removeAll("abschalten");
        voice.save();
    }

    boolean enabled() {
        return settings.enabled();
    }

    /** Für das Modulverzeichnis (ModuleService): Hat die KI-Prüfung schon geantwortet, und war sie da? */
    boolean kiAnswered() {
        return host.answered();
    }

    boolean kiUp() {
        return host.up();
    }

    /** stadtleben.ohne-ki an (Modulverzeichnis: „aktiv, ohne KI“). */
    boolean withoutKi() {
        return settings.withoutKi();
    }

    /** Feste Sätze, die ohne KI statt einer Anfrage kamen (Test und /bm stadtleben status). */
    private int withoutKiLines;

    int withoutKiLines() {
        return withoutKiLines;
    }

    /** /bm stadtleben an|aus (Admin-Prüfung im Befehl). Schreibt config.yml, damit es einen Neustart übersteht. */
    void setEnabled(boolean on, CommandSender by) {
        settings = settings.withEnabled(on);
        env.saveEnabled(on);
        if (on) {
            host.askSoon();
        } else {
            removeAll("aus");
        }
        LOG.info(() -> "stadtleben | geschaltet | an=" + on + " von=" + Texts.clean(by.getName(), 16));
    }

    boolean isOurs(Entity e) {
        return e.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    /** Ein Bewohner, den dieser Lauf nicht kennt (Absturz, Neustart) – weg damit. */
    boolean isStale(Entity e) {
        if (!isOurs(e)) {
            return false;
        }
        for (Resident r : residents) {
            if (r.entity.equals(e.getUniqueId())) {
                return false;
            }
        }
        return true;
    }

    /** Bewohner lösen nichts aus: keine Druckplatte, kein Stolperdraht, kein zertretenes Ackerland. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(EntityInteractEvent e) {
        if (isOurs(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    /**
     * Die Eigenschaften eines neuen Bewohners. Eigene Methode, damit der Test (TownLifeTest) sie an einem Fake prüft:
     * nicht persistent (sonst überlebten Bewohner einen Absturz als herrenlose Puppen), Marke für die Altlast-Prüfung,
     * unverwundbar, Schwerkraft an und beweglich (Laufen wie Vera, CompanionWalk), keine Kollision mit Spielern.
     */
    static void prepare(Mannequin e, NamespacedKey marker, Roster.Person person) {
        e.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        e.setCustomName(person.name());
        e.setCustomNameVisible(true);
        MannequinCompat.description(e, person.job());
        MannequinCompat.showDescription(e);
        e.setInvulnerable(true);
        e.setGravity(true);
        e.setImmovable(false);
        e.setSilent(true);
        e.setCollidable(false);
        e.setPersistent(false);
        e.setRemoveWhenFarAway(false);
    }

    // ---------------------------------------------------------------- Takt

    /** Was im laufenden Tick geschah, und die Ursache des teuersten Ticks seit der letzten Messung. */
    private int tickSpawns;
    private int tickTeleports;
    private int tickSearches;
    private long worstNanos;
    private String worstCause = "";

    private void tick() {
        long t0 = System.nanoTime();
        try {
            step();
        } finally {
            // Immer gemessen, auch ausgeschaltet: Das ist die Nulllinie der Messtabelle (AK-25).
            long took = System.nanoTime() - t0;
            cost.add(took);
            if (took > worstNanos) {
                // Für die Frage „woher kommen einzelne 36-ms-Ticks auf Paper?“: was in genau diesem Tick geschah.
                worstNanos = took;
                worstCause = "bewohner=" + residents.size() + " erschienen=" + tickSpawns + " teleports="
                        + tickTeleports + " wegsuchen=" + tickSearches;
            }
            tickSpawns = 0;
            tickTeleports = 0;
            tickSearches = 0;
        }
    }

    void step() {
        tick++;
        long now = System.currentTimeMillis();
        if (settings.enabled() && llm != null && host.due(now)) {
            llm.health().thenAccept(ok -> env.now(() -> hostResult(ok)));
        }
        // Eine Schutzstelle für beides (Population.leave): Modus aus oder KI weg → alle sofort weg, kein Takt. Mit
        // stadtleben.ohne-ki zählt die fehlende KI nicht (Population.present).
        if (Population.leave(settings.enabled(), Population.present(host.up(), settings.withoutKi()),
                DayPlan.Phase.WORK) == Population.Leave.REMOVE_NOW) {
            if (!residents.isEmpty()) {
                removeAll(settings.enabled() ? "ki_weg" : "aus");
            }
            return;
        }
        // Gedrosselt, solange ein Bau läuft oder die Lag-Wache misst (AK-25): halbe Bewegungsrate, Wegsuche nur
        // jeden 8. Tick. Die Lag-Wache zählt Objekte und Schaltvorgänge – Bewohner sollen sie nicht verfälschen.
        boolean slow = env.slow();
        int[] plans = {slow ? (tick % 8 == 0 ? 1 : 0) : 1};
        // Reihum beginnen: Sonst bekäme immer derselbe Bewohner die eine Planerlaubnis des Ticks.
        List<Resident> order = new ArrayList<>(residents);
        int n = order.size();
        for (int k = 0; k < n; k++) {
            int i = (int) Math.floorMod(tick + k, (long) n);
            if (slow && (tick + i) % 2 != 0) {
                continue;
            }
            stepResident(order.get(i), now, plans);
        }
        if (tick % 20 == 0) {
            meetings(now);
        }
        if (tick % 1200 == 0 && voice.memory().dirty()) {
            voice.save();
        }
        spawnIfDue();
    }

    void hostResult(boolean ok) {
        boolean before = host.up();
        host.result(ok, System.currentTimeMillis());
        if (before != ok || !host.answered()) {
            LOG.info(() -> "stadtleben | ki | erreichbar=" + ok);
        }
    }

    private void spawnIfDue() {
        List<Player> eligible = new ArrayList<>();
        for (Player p : env.players()) {
            if (p.getGameMode() != GameMode.SPECTATOR && DayPlan.phase(p.getWorld().getTime()) != DayPlan.Phase.NIGHT) {
                eligible.add(p);
            }
        }
        int target = Population.target(settings.enabled(), Population.present(host.up(), settings.withoutKi()),
                DayPlan.Phase.WORK, settings.count(),
                eligible.size());
        if (!Population.maySpawn(residents.size(), target, tick, lastSpawnTick)) {
            return;
        }
        lastSpawnTick = tick;
        Player anchor = eligible.get(Math.floorMod(playerCursor++, eligible.size()));
        Set<String> taken = new HashSet<>();
        residents.forEach(r -> taken.add(r.person.name()));
        Set<String> reserved = new HashSet<>(env.reservedNames());
        for (Player p : env.players()) {
            reserved.add(p.getName());
        }
        List<Roster.Person> pick = Roster.pick(1, taken, reserved, spawnCursor++);
        if (pick.isEmpty()) {
            return;
        }
        Location at = surfaceNear(anchor.getLocation(), 10, 22);
        if (at == null) {
            return;
        }
        spawn(pick.getFirst(), at);
    }

    private void spawn(Roster.Person person, Location at) {
        Mannequin m = at.getWorld().spawn(at, Mannequin.class, e -> prepare(e, marker, person));
        tickSpawns++;
        // Erst eintragen, dann schmücken: Wirft der Schmuck, bleibt der Bewohner verfolgt und wird wieder entfernt.
        Resident r = adopt(person, m, at);
        dress(m, person);
        r.work = chooseWork(r, at);
        if (presence != null) {
            presence.appeared(person.name(), person.job(), person.color(), m);
        }
        spawned++;
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | erschienen | bewohner=%s beruf=%s welt=%s x=%d y=%d "
                + "z=%d", TownTalk.key(person.name()), person.job(), at.getWorld().getName(), at.getBlockX(),
                at.getBlockY(), at.getBlockZ()));
    }

    /** So viele vorhandene Orte probiert chooseWork, bevor es auf den Platz zurückfällt. */
    static final int TRY_SPOTS = 3;

    /** Schon einmal gewarnt, dass Env.workSpots geworfen hat (danach still, sonst bis alle 2 s ein Stacktrace). */
    private boolean workSpotsFailed;

    /**
     * Arbeitsort eines neuen Bewohners (AK-30): der beste vorhandene Ort (WorkSpots.rank), an dem sich ein Stehplatz
     * findet; höchstens TRY_SPOTS Orte, dann der alte Platz 12–22 Blöcke von der Wohnung, zuletzt die Wohnung selbst.
     * Mehr Versuche lohnen nicht: Ein Ort ohne Stehplatz (Wasser, Dach in Laub) ist meist von Nachbarn umgeben, die es
     * auch nicht haben, und jeder Versuch liest bis zu sieben Spalten.
     */
    Location chooseWork(Resident r, Location home) {
        World w = home.getWorld();
        List<double[]> near = new ArrayList<>();
        for (Player p : env.players()) {
            if (p.getGameMode() != GameMode.SPECTATOR && w.equals(p.getWorld())) {
                near.add(new double[] {p.getLocation().getX(), p.getLocation().getZ()});
            }
        }
        Map<String, Integer> taken = new HashMap<>();
        for (Resident o : residents) {
            if (o != r && o.workAt != null) {
                taken.merge(o.workAt.spot().key(), 1, Integer::sum);
            }
        }
        List<WorkSpots.Spot> spots;
        try {
            spots = env.workSpots();
        } catch (RuntimeException e) {
            // Bewusst fail-open, wie BukkitEnv.source: chooseWork läuft in spawn NACH adopt – eine Ausnahme hier ließe
            // den eingetragenen Bewohner ohne Arbeitsort zurück und ginge bis zum Scheduler (Prüfung 2026-11-22).
            // Ohne Orte bleibt der Platz nahe der Wohnung.
            if (!workSpotsFailed) {
                workSpotsFailed = true;
                LOG.log(java.util.logging.Level.WARNING, "stadtleben | orte_fehlgeschlagen | grund=" + e, e);
            }
            spots = List.of();
        }
        List<WorkSpots.Choice> ranked = WorkSpots.rank(spots, w.getName(), home.getX(), home.getZ(), near,
                w::isChunkLoaded, taken);
        for (int i = 0; i < Math.min(TRY_SPOTS, ranked.size()); i++) {
            WorkSpots.Choice c = ranked.get(i);
            Location stand = standAt(new Location(w, c.x(), c.spot().y(), c.z()));
            if (stand != null) {
                r.workAt = c;
                LOG.info(() -> String.format(Locale.ROOT, "stadtleben | arbeitsort | bewohner=%s quelle=%s ort=%s x=%d "
                        + "y=%d z=%d", TownTalk.key(r.person.name()), c.spot().source().label(),
                        Texts.clean(c.spot().name(), 48), stand.getBlockX(), stand.getBlockY(), stand.getBlockZ()));
                return stand;
            }
        }
        r.workAt = null;
        Location plain = surfaceNear(home, 12, 22);
        Location work = plain != null ? plain : home.clone();
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | arbeitsort | bewohner=%s quelle=%s orte=%d x=%d y=%d "
                + "z=%d", TownTalk.key(r.person.name()), WorkSpots.Source.NEAR.label(), ranked.size(),
                work.getBlockX(), work.getBlockY(), work.getBlockZ()));
        return work;
    }

    /** Stehplatz genau am Ort, sonst bis 2,5 Blöcke daneben (der Warp selbst kann auf einer Stufe liegen). */
    private Location standAt(Location at) {
        // Vorher nur surfaceAt (höchster Block): Ein Warp unter einem Dach bis 12 Blöcke höher legte den Stehplatz aufs
        // Dach (Gegenprobe T-023, 2026-11-22). Jetzt zuerst die Höhe des Ortes; wer das zurückdreht, schickt Bewohner
        // auf Markthallen und Bahnhofsdächer.
        Location near = env.standNear(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (near != null) {
            return near;
        }
        Location exact = env.surfaceAt(at.getWorld(), at.getBlockX(), at.getBlockZ());
        if (exact != null && Math.abs(exact.getY() - at.getY()) <= 12) {
            return exact;
        }
        return surfaceNear(at, 1, 2.5);
    }

    /** So weit sucht standNear unter bzw. über der Höhe des Ortes (Warps stehen meist auf dem Boden, selten 1 höher). */
    static final int STAND_DOWN = 3;
    static final int STAND_UP = 2;

    /**
     * Füße-Höhe eines Stehplatzes nahe y: Boden bei h−1 fest, h und h+1 frei; zuerst y selbst, dann abwechselnd tiefer
     * und höher. null, wenn keiner. Rein, damit die Suche ohne Server getestet ist (BukkitEnv liest nur die Blöcke).
     */
    static Integer standY(int y, java.util.function.IntPredicate solid, java.util.function.IntPredicate free) {
        for (int d = 0; d <= Math.max(STAND_DOWN, STAND_UP); d++) {
            for (int h : d == 0 ? new int[] {y} : new int[] {y - d, y + d}) {
                if ((h < y && y - h > STAND_DOWN) || (h > y && h - y > STAND_UP)) {
                    continue;
                }
                if (solid.test(h - 1) && free.test(h) && free.test(h + 1)) {
                    return h;
                }
            }
        }
        return null;
    }

    /** Einen Bewohner verfolgen (spawn; im Test mit einem Fake-Mannequin). */
    Resident adopt(Roster.Person person, Mannequin m, Location home) {
        Resident r = new Resident(person, m.getUniqueId(), home.clone());
        r.work = home.clone();
        residents.add(r);
        return r;
    }

    /** Lederjacke in der Farbe des Berufs – alle Mannequins tragen sonst denselben Standard-Skin. */
    private static void dress(Mannequin m, Roster.Person p) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null) {
            return;
        }
        ItemStack jacket = new ItemStack(Material.LEATHER_CHESTPLATE);
        if (jacket.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(p.color()));
            jacket.setItemMeta(meta);
        }
        // Kein setChestplateDropChance: Mannequins sind keine Mobs, Bukkit wirft dann IllegalArgumentException
        // (gemessen 2026-11-21, Spigot 1.21.11) – der Spawn brach ab und ließ ein unverfolgtes Mannequin stehen. Sie
        // lassen ohnehin nichts fallen: unverwundbar, und entfernt wird per remove().
        eq.setChestplate(jacket);
    }

    private void stepResident(Resident r, long now, int[] plans) {
        Entity e = env.entity(r.entity);
        if (!(e instanceof Mannequin m) || !m.isValid()) {
            // Chunk entladen (nicht persistent → weg) oder entfernt: vergessen, ein neuer erscheint bei einem Spieler.
            forget(r, "entladen");
            return;
        }
        Location cur = m.getLocation();
        World w = cur.getWorld();
        Player near = null;
        double nearSq = Double.MAX_VALUE;
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double d = p.getLocation().distanceSquared(cur);
            if (d < nearSq) {
                nearSq = d;
                near = p;
            }
        }
        if (near == null || nearSq > Population.FAR_FROM_PLAYERS * Population.FAR_FROM_PLAYERS) {
            remove(r, m, "fern");
            return;
        }
        if (!Double.isNaN(r.lastX)) {
            r.walked += Math.hypot(cur.getX() - r.lastX, cur.getZ() - r.lastZ);
        }
        r.lastX = cur.getX();
        r.lastZ = cur.getZ();
        DayPlan.Phase phase = DayPlan.phase(w.getTime());
        if (Population.leave(true, true, phase) == Population.Leave.GO_HOME) {
            if (r.nightSince == 0) {
                r.nightSince = now;
            }
            if (now - r.nightSince > Population.NIGHT_LEAVE_MS || r.goal == DayPlan.Goal.SLEEP && r.reached) {
                remove(r, m, "schlafen");
                return;
            }
        } else {
            r.nightSince = 0;
        }
        Location goal = goal(r, phase.goal(), near, now);
        boolean talking = now < r.busyUntil;
        double h = Math.hypot(goal.getX() - cur.getX(), goal.getZ() - cur.getZ());
        if (talking || h < ARRIVE && Math.abs(goal.getY() - cur.getY()) < 2.5) {
            if (!talking && !r.reached) {
                r.reached = true;
                r.reachedCount++;
                LOG.fine(() -> "stadtleben | ziel | bewohner=" + TownTalk.key(r.person.name()) + " ziel=" + r.goal);
            }
            stand(m);
            activity(r, m, phase, near, nearSq, now);
            gesture(r, m, talking ? Gestures.Activity.TALK : r.goal == DayPlan.Goal.WORK && r.reached
                    ? Gestures.Activity.WORK : Gestures.Activity.IDLE, now);
        } else {
            walk(r, m, cur, goal, plans);
            gesture(r, m, Gestures.Activity.WALK, now);
        }
        yieldTo(m, near);
        greet(r, m, near, nearSq, now);
    }

    /** Ziel der Phase; wechselt das Ziel, wird neu geplant und „erreicht“ neu gezählt. */
    private Location goal(Resident r, DayPlan.Goal kind, Player near, long now) {
        if (kind != r.goal) {
            r.goal = kind;
            r.reached = false;
            r.route.reset();
            r.plaza = null;
            hold(r, null);
        }
        return switch (kind) {
            case WORK -> r.work;
            case HOME, SLEEP -> r.home;
            case PLAZA -> {
                // Bummeln: alle 20–40 s ein neuer Fleck in der Nähe des nächsten Spielers.
                if (r.plaza == null || r.reached && now > r.plazaUntil) {
                    Location spot = surfaceNear(near.getLocation(), 3, 8);
                    if (spot != null) {
                        r.plaza = spot;
                        r.reached = false;
                        r.route.reset();
                    } else if (r.plaza == null) {
                        r.plaza = r.home;
                    }
                    r.plazaUntil = now + 20_000 + ThreadLocalRandom.current().nextLong(20_000);
                }
                yield r.plaza;
            }
        };
    }

    private void walk(Resident r, Mannequin m, Location cur, Location goal, int[] plans) {
        World w = cur.getWorld();
        // Teilziel fest halten, bis es erreicht ist: Wanderte es jeden Tick mit, wollte jede Route jeden Tick neu
        // planen (Route.wantsPlan: Ziel mehr als 1 Block gewandert).
        if (r.leg == null || r.legFor != goal
                || Math.hypot(r.leg[0] - cur.getX(), r.leg[2] - cur.getZ()) < 2.0 && !sameXz(r.leg, goal)) {
            double[] xz = Route.leg(cur.getX(), cur.getZ(), goal.getX(), goal.getZ());
            int lx = (int) Math.floor(xz[0]);
            int lz = (int) Math.floor(xz[1]);
            if (!w.isChunkLoaded(lx >> 4, lz >> 4)) {
                // Nie in ungeladene Chunks: dort sieht sie niemand, und Lesen würde Chunks laden (PlotSquared).
                stand(m);
                r.leg = null;
                return;
            }
            double y = sameXz(new double[] {xz[0], 0, xz[1]}, goal) ? goal.getY() : w.getHighestBlockYAt(lx, lz) + 1;
            r.leg = new double[] {xz[0], y, xz[1]};
            r.legFor = goal;
            r.progress.reset(tick);
        }
        double[] leg = {r.leg[0], r.leg[2]};
        double ly = r.leg[1];
        double legDist = Math.hypot(Math.hypot(leg[0] - cur.getX(), leg[1] - cur.getZ()), ly - cur.getY());
        boolean stuck = r.progress.stuck(legDist, tick);
        int stalled = r.progress.stalled(tick);
        boolean mayPlan = plans[0] > 0 && r.route.wantsPlan(leg[0], ly, leg[1], tick, stalled);
        if (mayPlan) {
            plans[0]--;
            tickSearches++;
        }
        CompanionPath.Blocks blocks = env.pathBlocks(w);
        double[] t = r.route.next(blocks, cur.getX(), cur.getY(), cur.getZ(), leg[0], ly, leg[1], tick,
                stalled, mayPlan);
        Vector v = m.getVelocity();
        CompanionWalk.Step step = CompanionWalk.next(r.walk, new CompanionWalk.Body(cur.getX(), cur.getY(),
                cur.getZ(), v.getY(), m.isOnGround(), m.isInWater(),
                env.climbable(cur)), t[0], t[1], t[2], SPEED);
        if (stuck || step.teleport()) {
            // Nur beim Feststecken – Progress (3 s ohne Annäherung) oder CompanionWalk (40 Ticks ohne Bewegung) – auf
            // das Teilziel, nicht ans Ziel.
            r.teleports++;
            tickTeleports++;
            r.walk.reset();
            r.route.reset();
            r.leg = null;
            m.setVelocity(new Vector());
            Location to = new Location(w, Math.floor(leg[0]) + 0.5, ly, Math.floor(leg[1]) + 0.5);
            m.teleport(to);
            r.lastX = Double.NaN;
            LOG.fine(() -> "stadtleben | feststecken | bewohner=" + TownTalk.key(r.person.name()));
            return;
        }
        m.setVelocity(new Vector(step.vx(), step.vy(), step.vz()));
        if (step.moving()) {
            Location dir = cur.clone();
            dir.setDirection(new Vector(step.vx(), 0, step.vz()));
            m.setRotation(dir.getYaw(), 0);
        }
    }

    private static boolean sameXz(double[] leg, Location goal) {
        return Math.abs(leg[0] - goal.getX()) < 1.0e-6 && Math.abs(leg[2] - goal.getZ()) < 1.0e-6;
    }

    private static void stand(Mannequin m) {
        Vector v = m.getVelocity();
        if (Math.abs(v.getX()) + Math.abs(v.getZ()) > 1.0e-3) {
            m.setVelocity(new Vector(0, v.getY(), 0));
        }
    }

    /** Sichtbare Tätigkeit ohne Weltänderung: Werkzeug in der Hand, Arm schwingen, Spieler anschauen. */
    private void activity(Resident r, Mannequin m, DayPlan.Phase phase, Player near, double nearSq, long now) {
        if (r.goal == DayPlan.Goal.WORK && r.reached) {
            hold(r, m);
            // Arm schwingen ohne Presence (Tests) wie bisher; mit Presence übernimmt Gestures – auch wenn ein Spieler
            // zusieht (vorher schwang der Arm nur, wenn keiner näher als LOOK stand).
            if (presence == null && now >= r.nextSwing && nearSq > LOOK * LOOK) {
                m.swingMainHand();
                r.nextSwing = now + 1500 + ThreadLocalRandom.current().nextLong(2500);
            }
        }
        if (nearSq <= LOOK * LOOK && tick % 5 == 0) {
            BuilderNpc.lookAt(m, near.getEyeLocation());
        }
        if (tick % 100 == 0) {
            MannequinCompat.description(m, r.person.job() + " · " + phase.label());
        }
    }

    /** Werkzeug des Berufs in die Hand (m != null) bzw. Hand leeren (m == null: beim nächsten hold mit Entity). */
    private void hold(Resident r, Mannequin m) {
        Entity e = m != null ? m : env.entity(r.entity);
        if (!(e instanceof Mannequin mm)) {
            return;
        }
        EntityEquipment eq = mm.getEquipment();
        if (eq == null) {
            return;
        }
        Material want = m == null ? null : Material.matchMaterial(r.person.tool());
        Material has = eq.getItemInMainHand().getType();
        if (want == null ? !has.isAir() : has != want) {
            BuilderNpc.hold(mm, want);
        }
    }

    private void gesture(Resident r, Mannequin m, Gestures.Activity a, long now) {
        if (presence != null) {
            presence.gesture(r.person.name(), m, a, now);
        }
    }

    /** Platz machen: steht ein Spieler direkt vor ihm, tritt der Bewohner einen Schritt zur Seite. */
    private static void yieldTo(Mannequin m, Player p) {
        Location a = m.getLocation();
        Location b = p.getLocation();
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        double h = Math.hypot(dx, dz);
        if (h >= YIELD || Math.abs(a.getY() - b.getY()) > 2 || !m.isOnGround()) {
            return;
        }
        double s = 0.2 / Math.max(h, 0.1);
        Vector v = m.getVelocity();
        m.setVelocity(new Vector(dx * s, v.getY(), dz * s));
    }

    /** Gelegentlich grüßen: einmal je Spieler und GREET_AGAIN_MS, nie über die KI (TownLines). */
    private void greet(Resident r, Mannequin m, Player p, double distSq, long now) {
        if (distSq > GREET * GREET || now - r.lastSpoke < 20_000) {
            return;
        }
        Long last = r.greeted.get(p.getUniqueId());
        if (last != null && now - last < GREET_AGAIN_MS) {
            return;
        }
        r.greeted.put(p.getUniqueId(), now);
        BuilderNpc.lookAt(m, p.getEyeLocation());
        m.swingMainHand();
        voice.passedBy(r.person, p, now);
        speak(r, m, fixed(r, m, TownLines.Occasion.GREET));
    }

    // ---------------------------------------------------------------- Reden

    /** Zwei Bewohner nah beieinander und ein Spieler hört zu: einer sagt etwas, der andere antwortet. */
    private void meetings(long now) {
        for (int i = 0; i < residents.size(); i++) {
            for (int j = i + 1; j < residents.size(); j++) {
                Resident a = residents.get(i);
                Resident b = residents.get(j);
                String key = a.person.name() + "|" + b.person.name();
                Long last = meetings.get(key);
                if (last != null && now - last < MEET_AGAIN_MS || now < a.busyUntil || now < b.busyUntil) {
                    continue;
                }
                Entity ea = env.entity(a.entity);
                Entity eb = env.entity(b.entity);
                if (!(ea instanceof Mannequin ma) || !(eb instanceof Mannequin mb)
                        || !ma.getWorld().equals(mb.getWorld())
                        || ma.getLocation().distanceSquared(mb.getLocation()) > MEET * MEET
                        || !heard(ma.getLocation())) {
                    continue;
                }
                meetings.put(key, now);
                a.busyUntil = now + 8_000;
                b.busyUntil = now + 8_000;
                BuilderNpc.lookAt(ma, mb.getEyeLocation());
                BuilderNpc.lookAt(mb, ma.getEyeLocation());
                voice.met(a.person, b.person, now);
                // Vorher antwortete b immer mit einem festen Gruß, egal was a sagte. Jetzt fragt b die KI mit a's Satz
                // im Gespräch (TownVoice) – das Budget entscheidet wie bei jeder Anfrage, sonst ein fester Satz.
                ask(a, TownTalk.meeting(b.person), "treffen", fixed(a, ma, TownLines.Occasion.MEET), () -> {
                    Entity later = env.entity(b.entity);
                    if (later instanceof Mannequin lb && lb.isValid() && residents.contains(b)) {
                        ask(b, TownTalk.meetingReply(a.person), "treffen_antwort",
                                fixed(b, lb, TownLines.Occasion.MEET_REPLY), null);
                    }
                });
                return;
            }
        }
    }

    /** Hört überhaupt ein Spieler zu? Sonst lohnt keine KI-Anfrage. */
    private static boolean heard(Location at) {
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= TownTalk.HEAR * TownTalk.HEAR) {
                return true;
            }
        }
        return false;
    }

    /**
     * Chatzeile, Hauptthread (BaumeisterListener). true = ein Bewohner hat sie genommen. Nur wenn sie mit seinem
     * Namen beginnt und der Spieler in Hörweite steht; die Zeile wird nirgends gespeichert.
     */
    boolean onChat(Player p, String message) {
        if (residents.isEmpty()) {
            return false;
        }
        List<String> names = new ArrayList<>();
        residents.forEach(r -> names.add(r.person.name()));
        Optional<String> who = TownTalk.addressee(message, names);
        if (who.isEmpty()) {
            return false;
        }
        for (Resident r : residents) {
            if (!r.person.name().equals(who.get())) {
                continue;
            }
            Entity e = env.entity(r.entity);
            if (!(e instanceof Mannequin m) || !inEarshot(m.getLocation(), p.getLocation())) {
                return false;
            }
            long now = System.currentTimeMillis();
            // Abklingzeit je Spieler und Bewohner: Die Zeile gehört dem Bewohner (true), aber er antwortet nicht –
            // auch keinen festen Satz und ohne stehen zu bleiben. Sonst ließe sich ein Bewohner im Takt anhalten und
            // das KI-Budget mit einer Zeile je Sekunde leerräumen.
            String key = r.person.name() + "|" + p.getUniqueId();
            Long lastHeard = addressed.get(key);
            if (lastHeard != null && now - lastHeard < ADDRESS_GAP_MS) {
                return true;
            }
            addressed.put(key, now);
            r.busyUntil = now + 10_000;
            stand(m);
            BuilderNpc.lookAt(m, p.getEyeLocation());
            // Spielernamen im Satz (auch der eigene) werden zu Pseudonymen, bevor irgendetwas damit passiert.
            String heard = voice.scrub(TownTalk.withoutName(message, r.person.name()), env.players());
            if (heard.isBlank()) {
                speak(r, m, fixed(r, m, TownLines.Occasion.GREET) + " Ich bin " + r.person.name() + ", "
                        + r.person.job() + ".");
                return true;
            }
            // Lage vor dem Satz: Das Gespräch im Prompt endet mit dem Satz davor, der neue steht in fromPlayer.
            TownTalk.Situation s = situation(r, m, now);
            voice.heard(r.person, p, heard, m.getLocation(), town(), now);
            ask(r, s, TownTalk.fromPlayer(voice.alias(p), heard), "spieler", fixed(r, m,
                    TownLines.occasionFor(heard)), null);
            return true;
        }
        return false;
    }

    /**
     * Eine KI-Anfrage für einen Bewohner, wenn das Budget es erlaubt; sonst, bei Fehler, Zeitablauf oder gefiltertem
     * Satz der feste Satz fallback. after läuft nach dem Sprechen (Antwort des Gegenübers).
     */
    private void ask(Resident r, String user, String reason, String fallback, Runnable after) {
        ask(r, env.entity(r.entity) instanceof Mannequin m ? situation(r, m, System.currentTimeMillis())
                : TownTalk.Situation.basic(DayPlan.Phase.WORK), user, reason, fallback, after);
    }

    private void ask(Resident r, TownTalk.Situation s, String user, String reason, String fallback, Runnable after) {
        long now = System.currentTimeMillis();
        boolean building = env.buildPlanning();
        boolean vera = env.companionThinking();
        Entity e0 = env.entity(r.entity);
        // Ohne KI im Modus ohne-ki: gar keine Anfrage – sonst liefe jeder Satz erst in die 20-s-Frist eines toten Hosts.
        // Nur in diesem Modus: Ohne ihn gibt es bei fehlender KI ohnehin keine Bewohner, und der bestehende Weg bleibt.
        if (settings.withoutKi() && !host.up()) {
            withoutKiLines++;
            if (e0 instanceof Mannequin m) {
                speak(r, m, fallback);
            }
            runLater(after);
            return;
        }
        if (llm == null || !budget.begin(now, building, vera)) {
            refused++;
            LOG.fine(() -> "stadtleben | ki_gespart | bewohner=" + TownTalk.key(r.person.name()) + " anlass=" + reason
                    + " bau_denkt=" + building + " vera_denkt=" + vera);
            if (e0 instanceof Mannequin m) {
                speak(r, m, fallback);
            }
            runLater(after);
            return;
        }
        Entity e = e0;
        if (e instanceof Mannequin m) {
            MannequinCompat.description(m, r.person.job() + " · überlegt …");
        }
        List<JsonObject> messages = List.of(LlmClient.message("system", TownTalk.system(r.person, s)),
                LlmClient.message("user", TownTalk.user(s, user)));
        long t0 = System.nanoTime();
        // Kein orTimeout mehr: Das beendete nur das abhängige Future, die HTTP-Anfrage rechnete am Host weiter, und
        // das Budget gab schon frei (Paper-Probe 2026-11-21: 20,04 s Plugin, llama bis 21,07 s). Jetzt hat der Client
        // selbst die Frist (clientSettings), und das Budget wird erst frei, wenn die HTTP-Anfrage wirklich zu Ende ist.
        llm.complete(messages).whenComplete((reply, err) -> {
            env.now(() -> {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Optional<String> said = answer(reply, err, r.person.name());
                budget.end(ms, said.isPresent());
                if (err != null && connectionLost(err)) {
                    host.down(System.currentTimeMillis());
                }
                LOG.info(() -> String.format(Locale.ROOT, "stadtleben | ki | bewohner=%s anlass=%s ms=%d ok=%s%s",
                        TownTalk.key(r.person.name()), reason, ms, said.isPresent(),
                        err != null ? " grund=" + LlmClient.describe(err) : ""));
                Entity now2 = env.entity(r.entity);
                if (residents.contains(r) && now2 instanceof Mannequin m && m.isValid()) {
                    speak(r, m, said.orElse(fallback));
                    runLater(after);
                }
            });
        });
    }

    /** Was aus einer KI-Antwort gesagt wird: nur ein Satz, der durch TownTalk.spoken kommt; sonst leer (fester Satz). */
    static Optional<String> answer(LlmClient.Reply reply, Throwable err, String name) {
        return err == null && reply != null ? TownTalk.spoken(reply.content(), name) : Optional.empty();
    }

    /** Hört der Spieler bei p den Bewohner bei m? Gleiche Welt, höchstens TownTalk.HEAR Blöcke. */
    static boolean inEarshot(Location m, Location p) {
        return m.getWorld() != null && m.getWorld().equals(p.getWorld())
                && m.distanceSquared(p) <= TownTalk.HEAR * TownTalk.HEAR;
    }

    private void runLater(Runnable after) {
        if (after != null) {
            env.later(after, 60L);
        }
    }

    private static boolean connectionLost(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.ConnectException || c instanceof LlmClient.HostDownException) {
                return true;
            }
        }
        return false;
    }

    /** Sagen: hörbar in TownTalk.HEAR um den Bewohner, kurz auch über seinem Kopf. */
    private void speak(Resident r, Mannequin m, String text) {
        String clean = Texts.clean(text, TownTalk.MAX_SAY);
        if (clean.isEmpty()) {
            return;
        }
        r.lastSpoke = System.currentTimeMillis();
        voice.said(r.person, clean, m.getLocation(), town(), r.lastSpoke);
        Location at = m.getLocation();
        // Spielerformat „<Lotte> …“ (T-033); vorher grün „[Lotte, Bäckerin] …“.
        String line = Presence.chatLine(r.person.name(), clean);
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= TownTalk.HEAR * TownTalk.HEAR) {
                p.sendMessage(line);
            }
        }
        MannequinCompat.description(m, Texts.clean(clean, 60));
    }

    // ---------------------------------------------------------------- Ort, Entfernen, Status

    /**
     * Stehplatz auf der Oberfläche min–max Blöcke von around (Env.surfaceAt), höchstens 12 Blöcke über oder unter
     * around; null, wenn sechs Versuche nichts finden.
     */
    private Location surfaceNear(Location around, double min, double max) {
        World w = around.getWorld();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            double a = rnd.nextDouble(Math.PI * 2);
            double d = min + rnd.nextDouble(max - min);
            int x = (int) Math.floor(around.getX() + Math.cos(a) * d);
            int z = (int) Math.floor(around.getZ() + Math.sin(a) * d);
            Location at = env.surfaceAt(w, x, z);
            if (at != null && Math.abs(at.getY() - around.getY()) <= 12) {
                return at;
            }
        }
        return null;
    }

    private void remove(Resident r, Mannequin m, String reason) {
        m.remove();
        forget(r, reason);
    }

    private void forget(Resident r, String reason) {
        residents.remove(r);
        if (presence != null) {
            presence.left(r.person.name());
        }
        voice.left(r.person);
        lastGone = reason;
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | weg | bewohner=%s grund=%s strecke=%.1f ziele=%d "
                + "teleports=%d wegsuchen=%d minuten=%.1f", TownTalk.key(r.person.name()), reason, r.walked,
                r.reachedCount, r.teleports, r.route.searches(), (System.currentTimeMillis() - r.born) / 60_000.0));
    }

    private void removeAll(String reason) {
        for (Resident r : new ArrayList<>(residents)) {
            Entity e = env.entity(r.entity);
            if (e != null) {
                e.remove();
            }
            forget(r, reason);
        }
        meetings.clear();
    }

    /** /bm stadtleben status. Admins sehen zusätzlich je Bewohner Strecke, erreichte Ziele und Teleports. */
    void status(CommandSender s, boolean admin) {
        long now = System.currentTimeMillis();
        long ago = host.secondsSinceAnswer(now);
        Texts.info(s, "Stadtleben: " + (settings.enabled() ? "an" : "aus") + " · KI: "
                + (!host.answered() ? "noch nicht geprüft" : host.up() ? "erreichbar" : "NICHT erreichbar")
                + (ago >= 0 ? " (vor " + ago + " s geprüft)" : "") + " · Bewohner: " + residents.size() + " von "
                + settings.count() + (settings.withoutKi() ? " · ohne KI erlaubt (feste Sätze: " + withoutKiLines
                        + ")" : ""));
        long askAgo = budget.secondsSinceAsk(now);
        long[] mm = budget.timesMeanMax();
        Texts.info(s, "Letzte KI-Anfrage: " + (askAgo < 0 ? "keine" : "vor " + askAgo + " s, "
                + (budget.inFlight() ? "läuft" : budget.lastMillis() + " ms, " + (budget.lastOk() ? "beantwortet"
                        : "fester Satz"))) + " · Anfragen " + budget.asked() + " (beantwortet " + budget.answered()
                + ", Rückfall " + budget.fallbacks() + ", gespart " + refused + ") · Antwortzeit Mittel " + mm[0]
                + " ms, höchstens " + mm[1] + " ms · Abstand " + settings.talkGapMillis() / 1000 + " s");
        double[] c = cost.millis();
        Texts.info(s, String.format(Locale.GERMANY, "Takt (letzte %d Ticks): Mittel %.3f ms · p99 %.3f ms · höchstens "
                + "%.3f ms · teuerster Tick seit Messung %.3f ms (%s)", cost.size(), c[0], c[1], c[2],
                worstNanos / 1e6, worstCause));
        if (admin) {
            for (Resident r : residents) {
                Entity e = env.entity(r.entity);
                Texts.info(s, String.format(Locale.GERMANY, "%s (%s): %s · Strecke %.1f · Ziele %d · Teleports %d · "
                        + "Wegsuchen %d · Arbeit %s · seit %.1f min%s", r.person.name(), r.person.job(), r.goal == null
                        ? "–" : r.goal.name().toLowerCase(Locale.ROOT), r.walked, r.reachedCount, r.teleports,
                        r.route.searches(), r.workAt == null ? WorkSpots.Source.NEAR.label()
                                : r.workAt.spot().source().label() + " " + Texts.clean(r.workAt.spot().name(), 32),
                        (now - r.born) / 60_000.0, e == null ? "" : String.format(Locale.ROOT,
                                " · bei %d %d %d", e.getLocation().getBlockX(), e.getLocation().getBlockY(),
                                e.getLocation().getBlockZ())));
            }
        }
    }

    /** Messung neu beginnen (/bm stadtleben messung), für die Live-Probe mit 0, 4 und 12 Bewohnern. */
    void resetCost() {
        cost.clear();
        worstNanos = 0;
        worstCause = "";
    }

    String lastGone() {
        return lastGone;
    }

    TalkBudget budget() {
        return budget;
    }

    int count() {
        return residents.size();
    }

    int spawned() {
        return spawned;
    }
}
