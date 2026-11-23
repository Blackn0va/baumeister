package com.blackn0va.baumeister;

import com.blackn0va.baumeister.companion.CompanionPath;
import com.blackn0va.baumeister.companion.CompanionWalk;
import com.blackn0va.baumeister.town.DayPlan;
import com.blackn0va.baumeister.town.Follow;
import com.blackn0va.baumeister.town.Gathering;
import com.blackn0va.baumeister.town.HostWatch;
import com.blackn0va.baumeister.town.Population;
import com.blackn0va.baumeister.town.Progress;
import com.blackn0va.baumeister.town.Request;
import com.blackn0va.baumeister.town.Roster;
import com.blackn0va.baumeister.town.Route;
import com.blackn0va.baumeister.town.SafeGround;
import com.blackn0va.baumeister.town.TalkBudget;
import com.blackn0va.baumeister.town.TickCost;
import com.blackn0va.baumeister.town.TownLines;
import com.blackn0va.baumeister.town.TownSettings;
import com.blackn0va.baumeister.town.TownTalk;
import com.blackn0va.baumeister.town.Unstuck;
import com.blackn0va.baumeister.town.WorkSpots;
import com.blackn0va.baumeister.town.memory.TownVoice;
import com.blackn0va.baumeister.town.presence.Gestures;
import com.blackn0va.baumeister.town.presence.Presence;
import com.blackn0va.baumeister.town.use.Actor;
import com.blackn0va.baumeister.town.use.BukkitUse;
import com.blackn0va.baumeister.town.use.TownUse;
import com.blackn0va.baumeister.town.use.UseSettings;
import com.blackn0va.baumeister.town.use.UseWorld;
import com.blackn0va.baumeister.town.work.BuildSettings;
import com.blackn0va.baumeister.town.work.Field;
import com.blackn0va.baumeister.town.work.FieldJob;
import com.blackn0va.baumeister.town.work.FieldWork;
import com.blackn0va.baumeister.town.work.Fields;
import com.blackn0va.baumeister.town.work.Planner;
import com.blackn0va.baumeister.town.work.Plot;
import com.blackn0va.baumeister.town.work.Rate;
import com.blackn0va.baumeister.town.work.Task;
import com.blackn0va.baumeister.town.work.TownBuilds;
import com.blackn0va.baumeister.town.work.WorkSettings;
import com.google.gson.JsonObject;

import java.io.IOException;
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
 * - Weltänderung nur an Türen und in Arbeitsfeldern: Bewohner lösen keine Druckplatten aus und zertreten kein
 *   Ackerland (EntityInteractEvent abgesagt). KI-Aktionen werden verworfen. Seit v013 (AK-44, town/use) öffnen
 *   und schließen sie Türen, Tore und Falltüren auf dem Weg – nur nach Rechteprüfung (BukkitUse.Check), sofort
 *   zurückgestellt –, schlafen in freien Betten, sitzen auf Bänken und stehen an Arbeitsblöcken. Nur mit
 *   stadtleben.weltänderung und nur in einem Feld, das ein Admin gesetzt hat (AK-43), arbeiten Bauer, Holzfäller
 *   und Gärtnerin wirklich – jede Änderung über BlockBreak-/BlockPlace-Ereignisse im Namen eines Spielers
 *   (TownWork, fail-closed), höchstens stadtleben.feld-blöcke-je-minute.
 * - Laufen (AK-42): Wegsuche ohne Wasser, Fortschritt am Restweg, Feststecken stufenweise lösen (Unstuck), Treffpunkte
 *   mittags (Gathering), Folgen und Bleiben auf Zuruf (Request, Follow).
 * - Einhängepunkte für andere Stadtleben-Teile: appeared/gone (Bewohner kommt/geht), speak (sagt etwas), gathered
 *   (eine Gruppe steht beisammen). Wer dort etwas ergänzt, muss am Takt nichts ändern.
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
    /** Benutzen von Türen, Betten, Bänken, Arbeitsblöcken (AK-44); Regeln in town/use, hier nur Einhängepunkte. */
    private final TownUse use = new TownUse(new java.util.SplittableRandom());
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
    /** Arbeitsfelder (AK-43), persistiert über Env.fieldFile. */
    private final Fields fields;
    private WorkSettings work = WorkSettings.defaults();
    private final Rate rate = new Rate(WorkSettings.DEFAULT_RATE);
    /** Blöcke, an denen gerade ein Bewohner arbeitet (Task.key) – zwei nie an derselben Stelle. */
    private final Set<Long> claimed = new HashSet<>();
    /** Gesperrte Blöcke nach Ablehnung oder Feststecken: Schlüssel → bis wann (ms). */
    private final Map<Long, Long> locked = new HashMap<>();
    /** Felder, für die schon gewarnt wurde, dass kein berechtigter Spieler online ist. */
    private final Set<String> noActorWarned = new HashSet<>();
    /** Feldarbeit seit Start (Status und Test). */
    private int fieldChanges;
    private int fieldDenied;
    private int fieldStale;
    /** Bauen im Baufeld (AK-45): Schalter, Katalog (erst beim ersten Bedarf, braucht Env.blockCatalog). */
    private BuildSettings building = BuildSettings.defaults();
    private TownBuilds townBuilds;
    /** Feld → seit wann dort gebaut wird (höchstens ein Bau je Feld), nächster frühester Bau, Suchstelle. */
    private final Map<String, Long> buildRunning = new HashMap<>();
    private final Map<String, Long> buildNextAt = new HashMap<>();
    private final Map<String, Integer> buildCursor = new HashMap<>();
    /** Bauen seit Start (Status und Test): gestartet, fertig, abgebrochen/verweigert, abgelehnt vor dem Start. */
    private int buildsStarted;
    private int buildsDone;
    private int buildsFailed;
    private int buildsRefused;
    /** Laufende Treffen (Gathering). */
    private final List<Meet> meets = new ArrayList<>();
    private int gatherings;
    /** Stufen des Feststeckens seit Messung, je Unstuck.Action. */
    private final long[] unstuckCount = new long[Unstuck.Action.values().length];

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
        /** Messung AK-42: Takte im Spiel und davon „steht ohne Grund“ (will laufen, kommt nicht vom Fleck). */
        long ticksSeen;
        long idleTicks;
        final Unstuck unstuck = new Unstuck();

        /** Nach Unstuck.PAUSE (gesehen, alles versucht) bis zu diesem Takt stehen; wie oft schon, seit er ankam. */
        long pauseUntil;
        int giveUps;
        // Zuruf (Request): folgen, bleiben, heim.
        final Follow follow = new Follow();
        Location followAt;
        Player leader;
        Location stayAt;
        long stayUntil;
        long homeUntil;
        // Treffpunkt (Gathering).
        Meet meet;
        Location meetSlot;
        boolean meetArrived;
        long meetAgainAt;
        // Feldarbeit (AK-43).
        Field field;
        FieldJob job;
        Task task;
        final int[] taskStep = {0};
        Location taskAt;
        boolean taskReady;
        /** Takt, bis zu dem er vor der nächsten Änderung den Arm schwingt, und der nächsten Suche (Takte). */
        long swingUntil;
        long nextScan;
        int scanCursor;
        /** Baut gerade (AK-45): Der Bau (BuildJob.lead) bewegt ihn, der Tagesablauf ruht bis zum Ende. */
        boolean building;

        Resident(Roster.Person person, UUID entity, Location home) {
            this.person = person;
            this.entity = entity;
            this.home = home;
        }
    }

    /** Eine Gruppe am Treffpunkt (Gathering). */
    static final class Meet {
        final List<Resident> members;
        final Location point;
        final long started;
        boolean assembled;
        long until;

        Meet(List<Resident> members, Location point, long started) {
            this.members = members;
            this.point = point;
            this.started = started;
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

        /** Benutzen (AK-44, town/use): Welt-Sicht; null = Benutzen aus (Tests ohne Server). */
        default UseWorld useWorld(World w) {
            return null;
        }

        /** Benutzen: Körper eines Bewohners; null = Benutzen aus. */
        default Actor actor(Mannequin m) {
            return null;
        }

        /** Datei der Arbeitsfelder; null = nur im Speicher (Tests). */
        default Path fieldFile() {
            return null;
        }

        /** Blöcke für die Feldarbeit lesen; null = keine Feldarbeit möglich. */
        default Plot plot(World w) {
            return null;
        }

        /**
         * Hände für die Feldarbeit im Namen von actor (TownWork.hands); null = keine. log bekommt je Änderung eine
         * Zeile.
         */
        default FieldWork.Hands hands(World w, Player actor, Field f, FieldJob job,
                java.util.function.Consumer<String> log) {
            return null;
        }

        /** Welt nach Namen (Feld von der Konsole setzen); null = nicht geladen. */
        default World world(String name) {
            return null;
        }

        /** Hat das Feld eine Ablage-Kiste (TownWork.chest)? null = nicht prüfbar (Mitte nicht geladen). */
        default Boolean fieldChest(World w, Field f) {
            return null;
        }

        /** Block-IDs für den Bau-Katalog (TownBuilds); null = Bauen aus (Tests ohne Katalog). */
        default com.blackn0va.baumeister.plan.BlockCatalog blockCatalog() {
            return null;
        }

        /**
         * Bau im Baufeld starten (AK-45, BuildService.startTown): im Namen von actor, body baut. null = gestartet, done
         * kommt am Ende (true = fertig); sonst der Grund, nichts geändert. Vorgabe: nie (fail-closed).
         */
        default String townBuild(TownBuilds.Order order, Player actor, Mannequin body, String name,
                java.util.function.Consumer<Boolean> done) {
            return "aus";
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

        /** Bewohner-Bau starten (BuildService.startTown); null = gestartet, sonst Grund. */
        default String townBuild(TownBuilds.Order order, Player actor, Mannequin body, String name,
                java.util.function.Consumer<Boolean> done) {
            return "aus";
        }

        static Parts of(BaumeisterPlugin p) {
            return new Parts() {
                @Override
                public String townBuild(TownBuilds.Order order, Player actor, Mannequin body, String name,
                        java.util.function.Consumer<Boolean> done) {
                    return p.service() == null ? "kein_baudienst" : p.service().startTown(actor, body, name, order,
                            done);
                }

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
        /** Benutzen (AK-44); Marke wie TownLife.marker, damit verwaiste Sitze wie verwaiste Bewohner wegkommen. */
        private BukkitUse use;

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

        private BukkitUse use() {
            if (use == null) {
                use = new BukkitUse(server, new NamespacedKey(owner, "bewohner"), MannequinCompat::pose);
            }
            return use;
        }

        @Override
        public UseWorld useWorld(World w) {
            return use().world(w);
        }

        @Override
        public Actor actor(Mannequin m) {
            return use().actor(m);
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
        public Path fieldFile() {
            return owner.getDataFolder().toPath().resolve(Fields.FILE);
        }

        @Override
        public Plot plot(World w) {
            return TownWork.plot(w);
        }

        @Override
        public World world(String name) {
            return server.getWorld(name);
        }

        @Override
        public Boolean fieldChest(World w, Field f) {
            return TownWork.hasChest(w, f);
        }

        @Override
        public com.blackn0va.baumeister.plan.BlockCatalog blockCatalog() {
            return new BukkitBlockCatalog();
        }

        @Override
        public String townBuild(TownBuilds.Order order, Player actor, Mannequin body, String name,
                java.util.function.Consumer<Boolean> done) {
            return parts.townBuild(order, actor, body, name, done);
        }

        @Override
        public FieldWork.Hands hands(World w, Player actor, Field f, FieldJob job,
                java.util.function.Consumer<String> log) {
            return TownWork.hands(w, actor, f, job, server.getPluginManager()::callEvent, log);
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
            // Bauten der Bewohner kennt TownLife selbst (fieldBuildSpots, sofort und mit Rückgängig-Erkennung); aus dem
            // Baubuch kämen sie ein zweites Mal und teilten sich dann die Belegung nicht.
            if (w != null && e.order() != null && e.order().startsWith(TownBuilds.ORDER_PREFIX)) {
                continue;
            }
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
        this.fields = new Fields(env.fieldFile());
        for (String s : fields.load()) {
            LOG.warning(() -> "stadtleben | feld_übersprungen | " + Texts.clean(s, 120));
        }
        if (fields.broken() != null) {
            LOG.warning(() -> "stadtleben | felddatei_unlesbar | grund=" + Texts.clean(fields.broken(), 160)
                    + " – keine Feldarbeit, Datei bleibt unverändert");
        }
        if (plugin != null) {
            voice = TownVoice.open(plugin.getDataFolder().toPath().resolve("stadtleben"));
        }
        use.namer(this::residentKey);
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
        use.configure(plugin != null ? UseSettings.from(plugin.getConfig()) : UseSettings.defaults());
        if (plugin != null) {
            // Hier statt in BaumeisterPlugin/ConfigReload: configure läuft beim Start und (über reload) bei
            // /bm neuladen – eine Stelle, kein neuer Aufruf im Plugin.
            configureWork(WorkSettings.from(plugin.getConfig()));
            configureBuilding(BuildSettings.from(plugin.getConfig()));
        }
        if (s.enabled() && !was) {
            host.askSoon();
        }
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | einstellungen | an=%s anzahl=%d ki_abstand_s=%d ohne_ki=%s",
                s.enabled(), s.count(), s.talkGapMillis() / 1000, s.withoutKi()));
    }

    /** Feldarbeit schalten und begrenzen (configure; im Test direkt). Wechsel → Arbeitsorte der Feldberufe neu. */
    void configureWork(WorkSettings w) {
        boolean was = work.worldChange();
        this.work = w;
        rate.limit(w.blocksPerMinute());
        if (was != w.worldChange()) {
            refreshFieldWork();
        }
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | feldarbeit | weltänderung=%s blöcke_je_minute=%d "
                + "felder=%d", w.worldChange(), w.blocksPerMinute(), fields.all().size()));
    }

    /** Bauen schalten und begrenzen (configure; im Test direkt). */
    void configureBuilding(BuildSettings b) {
        this.building = b;
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | bauen | an=%s je_feld=%d pause_min=%d baufelder=%d",
                b.enabled(), b.perField(), b.pauseMinutes(), fields.all().stream().filter(Field::build).count()));
    }

    BuildSettings building() {
        return building;
    }

    /** {gestartet, fertig, abgebrochen, abgelehnt} seit Start. */
    int[] buildCounts() {
        return new int[] {buildsStarted, buildsDone, buildsFailed, buildsRefused};
    }

    /** Feldberufe suchen ihren Arbeitsort neu (Schalter umgelegt, Feld gesetzt oder entfernt). */
    private void refreshFieldWork() {
        for (Resident r : residents) {
            if (FieldJob.of(r.person.job()) != null) {
                dropTask(r, 0);
                r.work = chooseWork(r, r.home);
                r.reached = false;
                r.route.reset();
            }
        }
    }

    WorkSettings work() {
        return work;
    }

    Fields fields() {
        return fields;
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

    /**
     * Bewohner lösen nichts aus: keine Druckplatte, kein Stolperdraht, kein zertretenes Ackerland. Ausgenommen ist die
     * eigene Rechteprüfung vor dem Benutzen (BukkitUse.Check) – sagte dieser Handler sie ab, wäre jede Tür verboten.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(EntityInteractEvent e) {
        if (isOurs(e.getEntity()) && !(e instanceof BukkitUse.Check)) {
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
        fieldScans = slow ? 0 : 1;
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
            gatherings(now);
            meetings(now);
        }
        if (tick % 1200 == 0 && voice.memory().dirty()) {
            voice.save();
        }
        useTick();
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
        Location at = spawnSpot(anchor.getLocation());
        if (at == null) {
            return;
        }
        spawn(pick.getFirst(), at);
    }

    /**
     * Wo ein neuer Bewohner erscheint: 10–22 Blöcke vom Spieler, und nur dort, von wo ein trockener Weg zu ihm führt
     * (bis 22 Blöcke voll prüfbar, reachable). Vorher irgendwo auf der Oberfläche – gemessen im Parcours T-035 erschien
     * gut die Hälfte jenseits des Wassergrabens und stand mittags am Ufer, weil der Bummelplatz am Spieler ohne
     * Schwimmen unerreichbar war. null = in sechs Versuchen keiner; dann versucht spawnIfDue es später wieder.
     */
    Location spawnSpot(Location player) {
        return reachableNear(player, 10, 22, player);
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
        appeared(r, m);
    }

    /**
     * Einhängepunkt: Ein Bewohner ist erschienen und eingekleidet (Tab-Liste, Beitrittszeile, Gedächtnis). Hier
     * ergänzen statt im Takt. Wirft eine Ergänzung, bleibt der Bewohner verfolgt (adopt lief vorher).
     */
    void appeared(Resident r, Mannequin m) {
        // Absichtlich leer: Stadtleben v1 braucht beim Erscheinen nichts weiter.
    }

    /** Einhängepunkt: Ein Bewohner ist gegangen; reason wie im Log (fern, schlafen, aus, ki_weg, entladen …). */
    void gone(Resident r, String reason) {
        // Absichtlich leer, siehe appeared.
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
        Location inField = fieldWork(r, home);
        if (inField != null) {
            return inField;
        }
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
            spots = new ArrayList<>(env.workSpots());
            spots.addAll(fieldBuildSpots());
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
        // Erreichbar von der Wohnung zuerst (T-035, gemessen im Parcours mit Wassergraben): Bewohner schwimmen nicht
        // mehr, ein Platz jenseits des Wassers hieß ohne Weg in 24 Blöcken Ausweichen und Pause im Kreis.
        Location plain = reachableNear(home, 12, 22, home);
        if (plain == null) {
            plain = surfaceNear(home, 12, 22);
        }
        Location work = plain != null ? plain : home.clone();
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | arbeitsort | bewohner=%s quelle=%s orte=%d x=%d y=%d "
                + "z=%d", TownTalk.key(r.person.name()), WorkSpots.Source.NEAR.label(), ranked.size(),
                work.getBlockX(), work.getBlockY(), work.getBlockZ()));
        return work;
    }

    /** So weit (waagrecht) von der Wohnung darf das Feld eines Feldberufs liegen – wie Population.FAR_FROM_PLAYERS. */
    static final double FIELD_RANGE = 64.0;

    /**
     * Arbeitsort im Feld (AK-43): nur mit stadtleben.weltänderung, nur für Feldberufe, nur das nächste Feld derselben
     * Welt in FIELD_RANGE mit geladener Mitte. null = kein Feld, dann wie bisher (Warp, Reisepunkt, Bau, Platz).
     */
    private Location fieldWork(Resident r, Location home) {
        r.field = null;
        r.job = null;
        FieldJob job = FieldJob.of(r.person.job());
        if (!work.worldChange() || job == null) {
            return null;
        }
        World w = home.getWorld();
        Field f = fields.nearest(w.getName(), home.getX(), home.getZ(), FIELD_RANGE);
        if (f == null || !w.isChunkLoaded(f.x() >> 4, f.z() >> 4)) {
            return null;
        }
        Location stand = standAt(new Location(w, f.x() + 0.5, f.y(), f.z() + 0.5));
        if (stand == null) {
            return null;
        }
        r.field = f;
        r.job = job;
        r.workAt = null;
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | arbeitsort | bewohner=%s quelle=feld ort=%s x=%d y=%d "
                + "z=%d", TownTalk.key(r.person.name()), f.name(), stand.getBlockX(), stand.getBlockY(),
                stand.getBlockZ()));
        return stand;
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
        double moved = Double.isNaN(r.lastX) ? Double.NaN : Math.hypot(cur.getX() - r.lastX, cur.getZ() - r.lastZ);
        if (!Double.isNaN(moved)) {
            r.walked += moved;
        }
        r.lastX = cur.getX();
        r.lastZ = cur.getZ();
        DayPlan.Phase phase = DayPlan.phase(w.getTime());
        if (Population.leave(true, true, phase) == Population.Leave.GO_HOME) {
            if (r.nightSince == 0) {
                r.nightSince = now;
            }
            // Mit Bett (AK-44): bleiben, solange er liegt; der Weg dorthin darf BED_WALK_MS dauern. Ohne Bett wie vorher.
            boolean bed = use.hasBed(r.entity);
            if (!bed && (now - r.nightSince > Population.NIGHT_LEAVE_MS || r.goal == DayPlan.Goal.SLEEP && r.reached)
                    || bed && !use.sleeping(r.entity) && now - r.nightSince > TownUse.BED_WALK_MS) {
                remove(r, m, "schlafen");
                return;
            }
        } else {
            r.nightSince = 0;
        }
        if (r.building) {
            // Der Bau (BuildJob.lead) bewegt ihn und setzt seine Statuszeile; hier liefe er sonst zum Tagesziel zurück
            // und risse den Arbeiter alle paar Takte von der Baustelle.
            return;
        }
        Location special = override(r, cur, now);
        Location goal = special != null ? special : goal(r, phase.goal(), near, now, cur);
        boolean talking = now < r.busyUntil;
        if (tick < r.pauseUntil && !talking) {
            // Nach Unstuck.PAUSE: gesehen und alles versucht – kurz stehen. Zählt als Stehen ohne Grund (ehrlich).
            stand(m);
            measure(r, true);
            yieldTo(m, near);
            return;
        }
        double h = Math.hypot(goal.getX() - cur.getX(), goal.getZ() - cur.getZ());
        boolean arrived = h < ARRIVE && Math.abs(goal.getY() - cur.getY()) < 2.5
                || r.leader != null && Follow.close(Math.hypot(r.leader.getLocation().getX() - cur.getX(),
                        r.leader.getLocation().getZ() - cur.getZ()));
        if (talking || arrived) {
            if (!talking && special == null && !r.reached) {
                r.reached = true;
                r.reachedCount++;
                LOG.fine(() -> "stadtleben | ziel | bewohner=" + TownTalk.key(r.person.name()) + " ziel=" + r.goal);
            }
            r.unstuck.progressed();
            r.giveUps = 0;
            // Wer liegt oder sitzt (town/use), bleibt so – kein Stehen, Anschauen oder Arbeiten.
            if (!use.resting(r.entity)) {
                stand(m);
                if (r.leader != null) {
                    if (tick % 5 == 0) {
                        BuilderNpc.lookAt(m, r.leader.getEyeLocation());
                    }
                } else if (r.meet != null && special == r.meetSlot) {
                    // Nur echte Ankunft zählt: Dieser Zweig läuft auch, wenn er unterwegs redet (talking) – dann
                    // stünde er als „da“ in der Gruppe, obwohl er noch Blöcke entfernt ist (TownLifeWalkTest).
                    r.meetArrived |= arrived;
                    if (tick % 10 == 0) {
                        BuilderNpc.lookAt(m, r.meet.point.clone().add(0, 1.6, 0));
                    }
                } else if (special == null) {
                    activity(r, m, phase, near, nearSq, now);
                }
                gesture(r, m, talking ? Gestures.Activity.TALK : r.goal == DayPlan.Goal.WORK && r.reached
                        ? Gestures.Activity.WORK : Gestures.Activity.IDLE, now);
            }
            measure(r, false);
        } else {
            // Steht ohne Grund: will zum Ziel, ist nicht angekommen, redet nicht – und kam in diesem Takt keine
            // IDLE_MOVE Blöcke voran. Der erste Takt nach Erscheinen oder Teleport zählt nicht (kein Vergleich).
            measure(r, !Double.isNaN(moved) && moved < IDLE_MOVE);
            walk(r, m, cur, goal, plans, now);
            gesture(r, m, Gestures.Activity.WALK, now);
        }
        // Wer liegt oder sitzt, weicht nicht aus und grüßt nicht (sonst rutschte er vom Bett bzw. redete im Schlaf).
        if (!use.resting(r.entity)) {
            yieldTo(m, near);
            greet(r, m, near, nearSq, now);
        }
    }

    /** So lange bleibt ein Bewohner auf „bleib hier“ stehen bzw. geht auf „geh nach Hause“ heim (3 min). */
    static final long STAY_MS = 3 * 60_000L;
    static final long HOME_MS = 3 * 60_000L;
    /** So lange (Takte) steht er nach Unstuck.PAUSE: 4 s. In Takten, damit der Test ohne Wanduhr auskommt. */
    static final long PAUSE_TICKS = 80;

    /**
     * Ziel aus Zuruf oder Treffen, vor dem Tagesablauf: folgen (Punkt hinter dem Spieler), bleiben, heim, Treffpunkt.
     * null = Tagesablauf. Endet das Folgen (Höchstzeit, Weltwechsel, offline, zu weit), steht es im Log.
     */
    private Location override(Resident r, Location cur, long now) {
        r.leader = null;
        if (r.follow.active()) {
            Player p = online(r.follow.player());
            double d = p == null || !p.getWorld().equals(cur.getWorld()) ? 0
                    : Math.hypot(p.getLocation().getX() - cur.getX(), p.getLocation().getZ() - cur.getZ());
            Follow.End end = r.follow.check(p != null, p == null ? null : p.getWorld().getName(), d, now);
            if (end != Follow.End.NONE) {
                r.follow.stop();
                r.followAt = null;
                LOG.info(() -> "stadtleben | folgen_ende | bewohner=" + TownTalk.key(r.person.name()) + " grund="
                        + end.label());
            } else {
                Location pl = p.getLocation();
                // Ziel nur neu, wenn der Spieler mehr als 1,5 Blöcke weiter ist: Ein neues Ziel je Takt hieße je Takt
                // ein neues Teilziel und nie ein Fortschritt (Progress beginnt mit jedem Teilziel von vorn).
                if (r.followAt == null || !r.followAt.getWorld().equals(pl.getWorld())
                        || r.followAt.distanceSquared(pl) > (Follow.KEEP + 1.5) * (Follow.KEEP + 1.5)
                        || r.followAt.distanceSquared(pl) < 1.0) {
                    double[] xz = Follow.behind(pl.getX(), pl.getZ(), cur.getX(), cur.getZ());
                    r.followAt = new Location(pl.getWorld(), xz[0], pl.getY(), xz[1]);
                }
                r.leader = p;
                return r.followAt;
            }
        }
        if (r.stayAt != null) {
            if (now < r.stayUntil) {
                return r.stayAt;
            }
            r.stayAt = null;
        }
        if (now < r.homeUntil) {
            return r.home;
        }
        return r.meet != null ? r.meetSlot : null;
    }

    /** Online-Spieler nach UUID (Env.players, damit der Test ihn stellen kann); null = nicht da. */
    private Player online(UUID id) {
        for (Player p : env.players()) {
            if (p.getUniqueId().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** Weniger waagrechter Weg je Takt gilt in der Messung als Stehen (Spaziertempo 0,16, Wackeln 0,03). */
    static final double IDLE_MOVE = 0.02;

    /** Takte der Laufmessung, über alle Bewohner seit /bm stadtleben messung (auch die schon gegangenen). */
    private long runTicks;
    private long runIdle;
    private long runTeleports;

    private void measure(Resident r, boolean idle) {
        r.ticksSeen++;
        runTicks++;
        if (idle) {
            r.idleTicks++;
            runIdle++;
        }
    }

    /**
     * Laufmessung (AK-42): {Bewohner-Takte, davon steht ohne Grund, Teleports}. Teleports je Bewohner-Stunde =
     * Teleports / (Takte / 72 000), bei 20 Takten je Sekunde.
     */
    long[] runMeasure() {
        return new long[] {runTicks, runIdle, runTeleports};
    }

    /** Stufen des Feststeckens seit Messung, je Unstuck.Action (Test, Status). */
    long[] unstuckCounts() {
        return unstuckCount.clone();
    }

    /** Feldarbeit seit Start: {Änderungen, verboten, verworfen} (Test, Status). */
    int[] fieldCounts() {
        return new int[] {fieldChanges, fieldDenied, fieldStale};
    }

    /** Ziel der Phase; wechselt das Ziel, wird neu geplant und „erreicht“ neu gezählt. */
    private Location goal(Resident r, DayPlan.Goal kind, Player near, long now, Location cur) {
        if (kind != r.goal) {
            r.goal = kind;
            r.reached = false;
            r.route.reset();
            r.plaza = null;
            hold(r, null);
        }
        Location base = switch (kind) {
            // Mit Feldarbeit: zum Platz der laufenden Arbeit, sonst zur Feldmitte (dort sucht er die nächste).
            case WORK -> r.task != null && r.taskAt != null ? r.taskAt : r.work;
            case HOME, SLEEP -> r.home;
            case PLAZA -> {
                // Bummeln: alle 20–40 s ein neuer Fleck in der Nähe des nächsten Spielers.
                if (r.plaza == null || r.reached && now > r.plazaUntil) {
                    // Nur ein Fleck, zu dem ein trockener Weg führt; sonst dort bummeln, wo er gerade ist (jenseits
                    // eines Flusses hinge er sonst die ganze Mittagspause am Ufer).
                    Location spot = reachableNear(near.getLocation(), 3, 8, cur);
                    if (spot == null) {
                        spot = reachableNear(cur, 2, 6, cur);
                    }
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
        // Laufende Feldarbeit: genau an den Stehplatz der Arbeit, nicht an einen Arbeitsblock (TownUse.target).
        return kind == DayPlan.Goal.WORK && r.task != null ? base : useTarget(r, kind, base);
    }

    private void walk(Resident r, Mannequin m, Location cur, Location goal, int[] plans, long now) {
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
            double y = sameXz(new double[] {xz[0], 0, xz[1]}, goal) ? goal.getY() : legY(w, lx, lz, cur.getY());
            r.leg = new double[] {xz[0], y, xz[1]};
            r.legFor = goal;
            r.progress.reset(tick);
        }
        double[] leg = {r.leg[0], r.leg[2]};
        double ly = r.leg[1];
        // Fortschritt am Restweg entlang des geplanten Wegs, ohne Weg an der Luftlinie. Vorher immer Luftlinie: Wer
        // um eine U-Mauer herumging, kam dem Teilziel 3 s nicht näher und wurde sichtbar ans Teilziel teleportiert
        // (Gegenprobe T-035: TownLifeWalkTest.detourAroundAWallNeedsNoTeleport). Ein neuer Weg setzt nur das Maß neu.
        double rest = r.route.remaining(cur.getX(), cur.getY(), cur.getZ());
        double straight = Math.hypot(Math.hypot(leg[0] - cur.getX(), leg[1] - cur.getZ()), ly - cur.getY());
        // Das Maß wechselt je Teilziel höchstens einmal von der Luftlinie auf den Restweg (Progress.stuck mit zwei
        // Maßen). Nicht bei jeder Neuplanung: Wer auf der Stelle wackelt, plant alle 20 Takte neu, und mit neuem Maß
        // zählte schon das Wackeln (0,6 Blöcke) als Annäherung.
        boolean stuck = r.progress.stuck(rest, straight, tick);
        int stalled = r.progress.stalled(tick);
        if (r.progress.improved()) {
            r.unstuck.progressed();
        }
        boolean mayPlan = plans[0] > 0 && r.route.wantsPlan(leg[0], ly, leg[1], tick, stalled);
        if (mayPlan) {
            plans[0]--;
            tickSearches++;
        }
        CompanionPath.Blocks raw = env.pathBlocks(w);
        // Bewohner schwimmen nicht (SafeGround.dry); Türen, die sie benutzen dürfen, sind Durchgang (town/use).
        CompanionPath.Blocks blocks = useBlocks(r, w, SafeGround.dry(raw));
        double[] t = r.route.next(blocks, cur.getX(), cur.getY(), cur.getZ(), leg[0], ly, leg[1], tick,
                stalled, mayPlan);
        usePass(m, w, t);
        Vector v = m.getVelocity();
        CompanionWalk.Step step = CompanionWalk.next(r.walk, new CompanionWalk.Body(cur.getX(), cur.getY(),
                cur.getZ(), v.getY(), m.isOnGround(), m.isInWater(),
                env.climbable(cur)), t[0], t[1], t[2], r.leader != null
                        ? CompanionWalk.speedFor(Math.hypot(t[0] - cur.getX(), t[2] - cur.getZ())) : SPEED);
        if (stuck || step.teleport()) {
            unstick(r, m, cur, raw, leg, ly, now);
            return;
        }
        // Ohne gefundenen Weg geht er geradeaus – dann nie in Wasser, Lava oder über eine hohe Kante (SafeGround).
        if (!r.route.hasPath() && !m.isInWater()
                && !SafeGround.safeAhead(raw, cur.getX(), cur.getY(), cur.getZ(), step.vx(), step.vz())) {
            m.setVelocity(new Vector(0, step.vy(), 0));
            return;
        }
        m.setVelocity(new Vector(step.vx(), step.vy(), step.vz()));
        if (step.moving()) {
            Location dir = cur.clone();
            dir.setDirection(new Vector(step.vx(), 0, step.vz()));
            m.setRotation(dir.getYaw(), 0);
        }
    }

    /** Höhe eines Teilziels: Stehplatz der Oberfläche (kein Wasser, keine Lava, kein Laub), sonst höchster Block. */
    private double legY(World w, int x, int z, double curY) {
        Location s = env.surfaceAt(w, x, z);
        if (s != null) {
            return s.getY();
        }
        return w.getHighestBlockYAt(x, z) + 1;
    }

    /**
     * Feststecken lösen (Unstuck): neu planen, ausweichen mit Sprung, Umweg, erst zuletzt teleportieren – und nur,
     * wenn kein Spieler in Unstuck.VIEW steht. Vorher sofort Teleport aufs Teilziel (gemessen T-035 im Parcours).
     */
    private void unstick(Resident r, Mannequin m, Location cur, CompanionPath.Blocks raw, double[] leg, double ly,
            long now) {
        World w = cur.getWorld();
        Unstuck.Action a = r.unstuck.next(watched(cur));
        unstuckCount[a.ordinal()]++;
        r.walk.reset();
        r.progress.reset(tick);
        double dx = leg[0] - cur.getX();
        double dz = leg[1] - cur.getZ();
        switch (a) {
            case REPLAN -> r.route.reset();
            case SIDESTEP -> {
                double[] s = SafeGround.sidestep(raw, cur.getX(), cur.getY(), cur.getZ(), dx, dz, tick % 2 == 0);
                r.route.reset();
                if (s != null) {
                    r.leg = s;
                    if (m.isOnGround()) {
                        m.setVelocity(new Vector(0, CompanionWalk.JUMP, 0));
                    }
                }
            }
            case DETOUR -> {
                // Teilziel um 60° gedreht, halb so weit (mindestens 4): an einer Mauerkante vorbei.
                double ang = Math.toRadians(tick % 2 == 0 ? 60 : -60);
                double len = Math.max(4.0, Math.hypot(dx, dz) / 2);
                double h = Math.max(1.0e-6, Math.hypot(dx, dz));
                double ux = dx / h;
                double uz = dz / h;
                double nx = cur.getX() + (ux * Math.cos(ang) - uz * Math.sin(ang)) * len;
                double nz = cur.getZ() + (ux * Math.sin(ang) + uz * Math.cos(ang)) * len;
                r.route.reset();
                if (w.isChunkLoaded((int) Math.floor(nx) >> 4, (int) Math.floor(nz) >> 4)) {
                    r.leg = new double[] {nx, legY(w, (int) Math.floor(nx), (int) Math.floor(nz), cur.getY()), nz};
                }
            }
            case TELEPORT -> {
                r.teleports++;
                tickTeleports++;
                runTeleports++;
                r.route.reset();
                r.leg = null;
                m.setVelocity(new Vector());
                m.teleport(new Location(w, Math.floor(leg[0]) + 0.5, ly, Math.floor(leg[1]) + 0.5));
                r.lastX = Double.NaN;
            }
            case PAUSE -> {
                r.pauseUntil = tick + PAUSE_TICKS;
                r.route.reset();
                r.leg = null;
                // Ein anderes Ziel: neuer Bummelfleck, raus aus dem Treffen, Feldarbeit an dieser Stelle sperren.
                r.plaza = null;
                leaveMeet(r);
                dropTask(r, LOCK_STUCK_MS);
                r.giveUps++;
                if (r.giveUps >= 2 && r.goal == DayPlan.Goal.WORK && r.field == null) {
                    // Zweimal alles versucht: Arbeitsplatz in der Nähe, zu dem ein Weg führt (Ausweichziel).
                    Location alt = reachableNear(cur, 4, 12, cur);
                    if (alt != null) {
                        r.work = alt;
                        r.workAt = null;
                        r.giveUps = 0;
                        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | ausweichziel | bewohner=%s x=%d y=%d "
                                + "z=%d", TownTalk.key(r.person.name()), alt.getBlockX(), alt.getBlockY(),
                                alt.getBlockZ()));
                    }
                }
            }
        }
        LOG.fine(() -> "stadtleben | feststecken | bewohner=" + TownTalk.key(r.person.name()) + " schritt="
                + a.name().toLowerCase(Locale.ROOT));
    }

    /** Bis zu diesem Abstand (je Achse) prüft reachable mit einer Wegsuche – knapp unter CompanionPath.RANGE (24). */
    static final int REACH_CHECK = 22;

    /**
     * Führt ein trockener Weg von from nach to? Weiter als REACH_CHECK: angenommen ja (die Teilziele planen dann).
     * Eine Suche kostet bis 1,7 ms (gemessen 2026-11-06); gerufen nur beim Wählen eines Ziels, nicht je Takt.
     */
    private boolean reachable(Location from, Location to) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }
        if (Math.abs(from.getX() - to.getX()) > REACH_CHECK || Math.abs(from.getZ() - to.getZ()) > REACH_CHECK) {
            return true;
        }
        CompanionPath.Node a = new CompanionPath.Node(from.getBlockX(), (int) Math.floor(from.getY() + 0.01),
                from.getBlockZ());
        CompanionPath.Node b = new CompanionPath.Node(to.getBlockX(), (int) Math.floor(to.getY() + 0.01),
                to.getBlockZ());
        return !CompanionPath.find(SafeGround.dry(env.pathBlocks(from.getWorld())), a, b).isEmpty();
    }

    /** Wie surfaceNear, aber nur ein Platz, zu dem von from ein Weg führt; null = keiner in sechs Versuchen. */
    private Location reachableNear(Location around, double min, double max, Location from) {
        for (int i = 0; i < 6; i++) {
            Location at = surfaceNear(around, min, max);
            if (at != null && reachable(from, at)) {
                return at;
            }
        }
        return null;
    }

    /** Sieht ein Spieler (nicht Zuschauer) den Ort – waagrecht in Unstuck.VIEW? */
    private static boolean watched(Location at) {
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR && Math.hypot(p.getLocation().getX() - at.getX(),
                    p.getLocation().getZ() - at.getZ()) <= Unstuck.VIEW) {
                return true;
            }
        }
        return false;
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

    /**
     * Sichtbare Tätigkeit: Werkzeug in der Hand, Arm schwingen, Spieler anschauen. Im Feld (AK-43) mit echter Arbeit,
     * sonst ohne Weltänderung.
     */
    private void activity(Resident r, Mannequin m, DayPlan.Phase phase, Player near, double nearSq, long now) {
        if (r.goal == DayPlan.Goal.WORK && r.field != null && work.worldChange()) {
            fieldActivity(r, m, now);
        } else if (r.goal == DayPlan.Goal.WORK && r.reached) {
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

    // ---------------------------------------------------------------- Feldarbeit (AK-43)

    /** Je Takt sucht höchstens ein Bewohner im Feld nach Arbeit (Planner.find, bis 48 Spalten). */
    private int fieldScans;
    /** So lange schwingt er vor jeder Änderung den Arm (sichtbar: er arbeitet daran). */
    static final long SWING_TICKS = 24;
    /** Abstand zwischen zwei Suchen desselben Bewohners. */
    static final long SCAN_GAP_TICKS = 20;
    /** Sperre eines Blocks nach Ablehnung durch ein Schutz-Plugin bzw. nach Feststecken davor. */
    static final long LOCK_DENIED_MS = 10 * 60_000L;
    static final long LOCK_STUCK_MS = 2 * 60_000L;

    /**
     * Am Arbeitsplatz im Feld: Arbeit suchen, hingehen (goal), Arm schwingen, dann eine Änderung über FieldWork.
     * Ohne berechtigten Spieler online (actor) geschieht nichts – fail-closed, mit einer Warnung je Feld.
     */
    private void fieldActivity(Resident r, Mannequin m, long now) {
        holdTool(m, r.job.tool());
        World w = m.getWorld();
        Player actor = actor(r.field);
        if (actor == null) {
            if (noActorWarned.add(r.field.name())) {
                LOG.warning(() -> "stadtleben | feld_ohne_berechtigten | feld=" + r.field.name()
                        + " – weder der Admin des Feldes noch ein anderer Admin online, keine Änderung");
            }
            idleSwing(r, m, now);
            return;
        }
        noActorWarned.remove(r.field.name());
        Plot plot = env.plot(w);
        if (plot == null) {
            idleSwing(r, m, now);
            return;
        }
        if (r.task == null) {
            if (fieldScans <= 0 || tick < r.nextScan) {
                idleSwing(r, m, now);
                return;
            }
            fieldScans--;
            r.nextScan = tick + SCAN_GAP_TICKS;
            if (tryBuild(r, m, actor, plot, now)) {
                return;
            }
            Planner.Found f = Planner.find(plot, r.field, r.job, r.scanCursor, k -> claimed.contains(k)
                    || locked.getOrDefault(k, 0L) > now);
            r.scanCursor = f.cursor();
            if (f.task() != null) {
                r.task = f.task();
                r.taskStep[0] = 0;
                r.taskReady = false;
                r.taskAt = new Location(w, f.task().standX() + 0.5, f.task().standY(), f.task().standZ() + 0.5);
                claimed.add(f.task().key());
                r.reached = false;
                r.route.reset();
            } else {
                idleSwing(r, m, now);
            }
            return;
        }
        Task.Change next = r.task.changes().get(Math.min(r.taskStep[0], r.task.changes().size() - 1));
        Location block = new Location(w, next.x() + 0.5, next.y() + 0.5, next.z() + 0.5);
        if (!r.taskReady) {
            r.taskReady = true;
            r.swingUntil = tick + SWING_TICKS;
        }
        if (tick < r.swingUntil) {
            if (tick % 6 == 0) {
                BuilderNpc.lookAt(m, block);
                m.swingMainHand();
            }
            return;
        }
        String where = String.format(Locale.ROOT, "bewohner=%s feld=%s x=%d y=%d z=%d", TownTalk.key(r.person.name()),
                r.field.name(), next.x(), next.y(), next.z());
        FieldWork.Hands hands = env.hands(w, actor, r.field, r.job, line -> LOG.info(() -> "stadtleben | feld | "
                + where + " " + line));
        if (hands == null) {
            dropTask(r, 0);
            return;
        }
        BuilderNpc.lookAt(m, block);
        FieldWork.Result res = FieldWork.step(r.task, r.taskStep, plot, hands, rate, now);
        // Saat gleich hinterher, im selben Takt: Zwischen zwei Takten könnte er gehen (Mittag, Zuruf, Nacht).
        while (res == FieldWork.Result.APPLIED && FieldWork.pairedWithNext(r.task, r.taskStep[0] - 1)) {
            fieldChanges++;
            res = FieldWork.step(r.task, r.taskStep, plot, hands, rate, now);
        }
        switch (res) {
            case APPLIED -> {
                fieldChanges++;
                m.swingMainHand();
                r.swingUntil = tick + SWING_TICKS;
            }
            case DONE -> {
                fieldChanges++;
                m.swingMainHand();
                dropTask(r, 0);
            }
            case WAIT -> r.swingUntil = tick + SWING_TICKS;
            case DENIED -> {
                fieldDenied++;
                LOG.info(() -> "stadtleben | feld_verboten | " + where + " – Schutz-Plugin hat abgelehnt, Stelle "
                        + LOCK_DENIED_MS / 60_000 + " min gesperrt");
                dropTask(r, LOCK_DENIED_MS);
            }
            case STALE -> {
                fieldStale++;
                dropTask(r, 0);
            }
        }
    }

    // ---------------------------------------------------------------- Bauen im Baufeld (AK-45)

    /** Ohne Platz im Feld: so lange bis zur nächsten Suche dort (alle Ecken einmal durch kostet sonst jeden Takt). */
    static final long BUILD_NO_ROOM_MS = 60_000L;
    /** Baumeister beschäftigt, Spielerauftrag wartet: kurz warten, dann wieder fragen. */
    static final long BUILD_BUSY_MS = 30_000L;

    private TownBuilds townBuilds() {
        if (townBuilds == null) {
            com.blackn0va.baumeister.plan.BlockCatalog c = env.blockCatalog();
            if (c != null) {
                townBuilds = new TownBuilds(c);
            }
        }
        return townBuilds;
    }

    /**
     * Im Baufeld einen Kleinbau beginnen (AK-45), statt Feldarbeit zu suchen. Alle Schutzstellen fail-closed in
     * TownBuilds.gate (Schalter, Weltänderung, Freigabe, ein Bau je Feld, Obergrenze, Pause), Platz und Überbau-Schutz in
     * TownBuilds.plan, Rechte und Rückgängig in BuildService.startTown. actor ist schon geprüft (fieldActivity: ohne
     * berechtigten Spieler kommt es gar nicht hierher). true = er baut jetzt.
     */
    private boolean tryBuild(Resident r, Mannequin m, Player actor, Plot plot, long now) {
        Field f = fields.get(r.field.name());
        if (f == null || !f.build()) {
            return false;
        }
        List<TownBuilds.Built> all = fields.built(f.name());
        List<TownBuilds.Built> standing = TownBuilds.standing(all, plot);
        if (standing.size() != all.size()) {
            int gone = all.size() - standing.size();
            LOG.info(() -> "stadtleben | bau_weg | feld=" + f.name() + " anzahl=" + gone
                    + " – zurückgebaut oder abgerissen, zählt nicht mehr, Feld ruht " + LOCK_DENIED_MS / 60_000
                    + " min");
            saveBuilt(f.name(), standing);
            // Wer einen Bau zurücknimmt, will ihn los sein. Live gemessen (T-037, Lauf 2, 00:46:01): Ohne Ruhe begann
            // Karl in derselben Sekunde, in der /bm rückgängig die Hütte entfernt hatte, eine neue drei Blöcke daneben.
            buildNextAt.merge(f.name(), now + LOCK_DENIED_MS, Math::max);
        }
        String why = TownBuilds.gate(building, work.worldChange(), f, buildRunning.containsKey(f.name()),
                standing.size(), now, buildNextAt.getOrDefault(f.name(), 0L));
        if (why != null) {
            return false;
        }
        TownBuilds tb = townBuilds();
        if (tb == null) {
            return false;
        }
        TownBuilds.Found found = tb.plan(f, plot, standing, buildCursor.getOrDefault(f.name(), 0),
                k -> claimed.contains(k) || locked.getOrDefault(k, 0L) > now);
        buildCursor.put(f.name(), found.cursor());
        if (found.order() == null) {
            buildNextAt.put(f.name(), now + BUILD_NO_ROOM_MS);
            LOG.fine(() -> "stadtleben | bauen_kein_platz | feld=" + f.name());
            return false;
        }
        TownBuilds.Order o = found.order();
        String who = TownTalk.key(r.person.name());
        // Belegen VOR dem Start: Endet der Bau schon im Aufruf (done sofort), räumt builtDone wieder ab. Andersherum
        // bliebe das Feld danach für immer „läuft“ und der Bewohner für immer „baut“.
        buildRunning.put(f.name(), now);
        r.building = true;
        String refused = env.townBuild(o, actor, m, r.person.name(), ok -> builtDone(f.name(), r, o, ok));
        if (refused != null) {
            buildRunning.remove(f.name());
            r.building = false;
            buildsRefused++;
            buildNextAt.put(f.name(), now + BUILD_BUSY_MS);
            LOG.info(() -> "stadtleben | bauen_abgelehnt | feld=" + f.name() + " bewohner=" + who + " art="
                    + o.kind().key() + " grund=" + refused);
            return false;
        }
        buildsStarted++;
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | bauen | feld=%s bewohner=%s art=%s x=%d y=%d z=%d "
                + "im_namen=%s", f.name(), who, o.kind().key(), o.x(), o.y(), o.z(), actor.getName()));
        return true;
    }

    /** Ende eines Bewohner-Baus (BuildJob → BuildService.startTown → done). Paketweit für Tests. */
    void builtDone(String field, Resident r, TownBuilds.Order o, boolean complete) {
        buildRunning.remove(field);
        r.building = false;
        r.reached = false;
        r.route.reset();
        long now = System.currentTimeMillis();
        World w = env.world(o.world());
        Plot plot = w == null ? null : env.plot(w);
        TownBuilds tb = townBuilds();
        TownBuilds.Built b = complete && plot != null && tb != null ? tb.remember(o, plot, now) : null;
        if (b == null) {
            // Verweigert (Rechteprüfung), abgebrochen oder nichts gesetzt: nichts merken, das Feld ruht wie nach einer
            // Ablehnung bei der Feldarbeit – sonst fragte er jede halbe Minute neu bei WorldGuard an.
            buildsFailed++;
            buildNextAt.put(field, now + LOCK_DENIED_MS);
            LOG.info(() -> "stadtleben | bauen_ende | feld=" + field + " art=" + o.kind().key() + " ergebnis="
                    + (complete ? "nichts_gesetzt" : "abgebrochen"));
            return;
        }
        buildsDone++;
        buildNextAt.put(field, now + building.pauseMillis());
        List<TownBuilds.Built> list = new ArrayList<>(fields.built(field));
        list.add(b);
        saveBuilt(field, list);
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | bauen_fertig | feld=%s art=%s x=%d y=%d z=%d "
                + "bauten=%d", field, o.kind().key(), b.minX(), b.y(), b.minZ(), list.size()));
    }

    private void saveBuilt(String field, List<TownBuilds.Built> list) {
        try {
            fields.setBuilt(field, list);
        } catch (IOException e) {
            // Bauen ist Komfort, der Bau steht schon: fail-open fürs Merken, laut im Log. Nach einem Neustart fehlt der
            // Eintrag – dann zählt er nicht zur Obergrenze, der Platz ist aber belegt (fit sieht den Bau).
            LOG.log(java.util.logging.Level.WARNING, "stadtleben | bauten_speichern_fehlgeschlagen | feld=" + field, e);
        }
    }

    /** Fertige Bauten der Bewohner als Arbeitsorte (WorkSpots, Quelle „bau“). */
    List<WorkSpots.Spot> fieldBuildSpots() {
        List<WorkSpots.Spot> out = new ArrayList<>();
        for (Field f : fields.all()) {
            int i = 0;
            for (TownBuilds.Built b : fields.built(f.name())) {
                i++;
                if (out.size() >= WorkSpots.MAX_PER_SOURCE) {
                    return out;
                }
                out.add(new WorkSpots.Spot(WorkSpots.Source.BUILD, b.kind().german() + " #" + f.name() + "-" + i,
                        f.world(), b.minX(), b.y(), b.minZ(), b.maxX(), b.maxZ()));
            }
        }
        return out;
    }

    /**
     * Für wen der Bewohner arbeitet: der Admin, der das Feld gesetzt hat, wenn er online ist, sonst ein anderer
     * Admin (baumeister.admin) in derselben Welt; null = niemand – dann keine Änderung. Im Namen dieses Spielers fragen
     * die Ereignisse WorldGuard & Co., und CoreProtect schreibt ihn als Verursacher. Nie ein beliebiger Spieler in der
     * Nähe: Dessen Rechte hätten mit dem Feld nichts zu tun (ASVS V4.1.3, geringste Rechte).
     */
    Player actor(Field f) {
        Player other = null;
        for (Player p : env.players()) {
            if (f.owner() != null && p.getUniqueId().equals(f.owner())) {
                return p;
            }
            if (other == null && p.getWorld() != null && p.getWorld().getName().equals(f.world())
                    && p.hasPermission("baumeister.admin")) {
                other = p;
            }
        }
        return other;
    }

    /** Arbeit loslassen (fertig, verworfen, Feststecken); lockMs > 0 sperrt ihre Stelle so lange. */
    private void dropTask(Resident r, long lockMs) {
        if (r.task == null) {
            return;
        }
        claimed.remove(r.task.key());
        if (lockMs > 0) {
            locked.put(r.task.key(), System.currentTimeMillis() + lockMs);
        }
        r.task = null;
        r.taskAt = null;
        r.taskReady = false;
        r.reached = false;
        r.route.reset();
    }

    /** Ohne Arbeit im Feld: Werkzeug halten und ab und zu schwingen wie an jedem Arbeitsort. */
    private void idleSwing(Resident r, Mannequin m, long now) {
        if (now >= r.nextSwing) {
            m.swingMainHand();
            r.nextSwing = now + 1500 + ThreadLocalRandom.current().nextLong(2500);
        }
    }

    private static void holdTool(Mannequin m, String tool) {
        EntityEquipment eq = m.getEquipment();
        Material want = Material.matchMaterial(tool);
        if (eq != null && want != null && eq.getItemInMainHand().getType() != want) {
            BuilderNpc.hold(m, want);
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
                talk(a, ma, b, mb, now);
                return;
            }
        }
    }

    /** a spricht b an, b antwortet (Begegnung, Treffpunkt). */
    private void talk(Resident a, Mannequin ma, Resident b, Mannequin mb, long now) {
        meetings.put(a.person.name() + "|" + b.person.name(), now);
        meetings.put(b.person.name() + "|" + a.person.name(), now);
        a.busyUntil = now + 8_000;
        b.busyUntil = now + 8_000;
        BuilderNpc.lookAt(ma, mb.getEyeLocation());
        BuilderNpc.lookAt(mb, ma.getEyeLocation());
        voice.met(a.person, b.person, now);
        // Vorher antwortete b immer mit einem festen Gruß, egal was a sagte. Jetzt fragt b die KI mit a's Satz im
        // Gespräch (TownVoice) – das Budget entscheidet wie bei jeder Anfrage, sonst ein fester Satz.
        ask(a, TownTalk.meeting(b.person), "treffen", fixed(a, ma, TownLines.Occasion.MEET), () -> {
            Entity later = env.entity(b.entity);
            if (later instanceof Mannequin lb && lb.isValid() && residents.contains(b)) {
                ask(b, TownTalk.meetingReply(a.person), "treffen_antwort",
                        fixed(b, lb, TownLines.Occasion.MEET_REPLY), null);
            }
        });
    }

    /**
     * Treffpunkte (Gathering): laufende Gruppen prüfen (beisammen? Zeit um? zu klein?), mittags freie Bewohner zu neuen
     * Gruppen zusammenführen. Alle 20 Takte aus step.
     */
    private void gatherings(long now) {
        for (Meet g : new ArrayList<>(meets)) {
            g.members.removeIf(r -> !residents.contains(r) || r.meet != g);
            boolean timeUp = g.assembled ? now >= g.until : now - g.started > 2 * Gathering.WAIT_MS;
            if (g.members.size() < Gathering.MIN || timeUp) {
                dissolve(g, now);
                continue;
            }
            if (!g.assembled) {
                int arrived = (int) g.members.stream().filter(r -> r.meetArrived).count();
                if (Gathering.assembled(arrived, g.members.size(), now - g.started)) {
                    for (Resident r : new ArrayList<>(g.members)) {
                        if (!r.meetArrived) {
                            leaveMeet(r);
                        }
                    }
                    g.assembled = true;
                    g.until = now + Gathering.stay(ThreadLocalRandom.current().nextInt(1000));
                    gatherings++;
                    gathered(g.members, g.point, now);
                }
            }
        }
        Map<World, List<Gathering.Member>> free = new HashMap<>();
        Map<Integer, Resident> byId = new HashMap<>();
        for (int i = 0; i < residents.size(); i++) {
            Resident r = residents.get(i);
            Entity e = env.entity(r.entity);
            if (!(e instanceof Mannequin m) || r.meet != null || r.follow.active() || r.stayAt != null
                    || now < r.homeUntil || now < r.meetAgainAt || now < r.busyUntil
                    || DayPlan.phase(m.getWorld().getTime()).goal() != DayPlan.Goal.PLAZA) {
                continue;
            }
            byId.put(i, r);
            free.computeIfAbsent(m.getWorld(), k -> new ArrayList<>())
                    .add(new Gathering.Member(i, m.getLocation().getX(), m.getLocation().getZ()));
        }
        int pick = ThreadLocalRandom.current().nextInt(1000);
        boolean formed = false;
        for (Map.Entry<World, List<Gathering.Member>> e : free.entrySet()) {
            for (List<Integer> ids : Gathering.groups(e.getValue(), pick)) {
                if (formed) {
                    // Höchstens eine neue Gruppe je Aufruf (alle 20 Takte): Jede prüft bis zu vier Wege (reachable).
                    break;
                }
                List<Gathering.Member> ms = e.getValue().stream().filter(m -> ids.contains(m.id())).toList();
                double[] c = Gathering.center(ms);
                World w = e.getKey();
                Location point = env.surfaceAt(w, (int) Math.floor(c[0]), (int) Math.floor(c[1]));
                if (point == null) {
                    point = surfaceNear(new Location(w, c[0], ms.isEmpty() ? 64 : byIdY(byId, ids), c[1]), 1, 4);
                }
                if (point == null) {
                    continue;
                }
                List<Resident> members = new ArrayList<>();
                ids.forEach(id -> members.add(byId.get(id)));
                // Nur wer den Treffpunkt trocken erreicht (T-035, Parcours mit Wassergraben: Mitglieder jenseits des
                // Wassers standen die Mittagspause über am Ufer). Wer es nicht kann, wartet bis zum nächsten Versuch.
                Location at = point;
                members.removeIf(r -> {
                    Entity m = env.entity(r.entity);
                    boolean ok = m != null && reachable(m.getLocation(), at);
                    if (!ok) {
                        r.meetAgainAt = now + Gathering.AGAIN_MS;
                    }
                    return !ok;
                });
                formed = true;
                if (members.size() < Gathering.MIN) {
                    members.forEach(r -> r.meetAgainAt = now + Gathering.AGAIN_MS);
                    continue;
                }
                Meet g = new Meet(members, point, now);
                for (int k = 0; k < members.size(); k++) {
                    Resident r = members.get(k);
                    double[] s = Gathering.slot(point.getX(), point.getZ(), k, members.size());
                    Location slot = env.surfaceAt(w, (int) Math.floor(s[0]), (int) Math.floor(s[1]));
                    r.meet = g;
                    r.meetArrived = false;
                    r.meetSlot = slot != null && Math.abs(slot.getY() - point.getY()) <= 1.5
                            ? new Location(w, s[0], slot.getY(), s[1]) : point.clone();
                    r.route.reset();
                    r.leg = null;
                }
                meets.add(g);
                LOG.fine(() -> "stadtleben | treffpunkt | bewohner=" + members.stream()
                        .map(r -> TownTalk.key(r.person.name())).toList());
            }
        }
    }

    private double byIdY(Map<Integer, Resident> byId, List<Integer> ids) {
        Entity e = env.entity(byId.get(ids.getFirst()).entity);
        return e == null ? 64 : e.getLocation().getY();
    }

    private void dissolve(Meet g, long now) {
        for (Resident r : new ArrayList<>(g.members)) {
            leaveMeet(r);
            r.meetAgainAt = now + Gathering.AGAIN_MS;
        }
        meets.remove(g);
    }

    /** Aus dem Treffen gehen (Zuruf, Feststecken, Gehen). */
    private void leaveMeet(Resident r) {
        if (r.meet != null) {
            r.meet.members.remove(r);
            r.meet = null;
            r.meetSlot = null;
            r.meetArrived = false;
            r.route.reset();
            r.leg = null;
        }
    }

    /**
     * Einhängepunkt: Eine Gruppe steht am Treffpunkt beisammen und bleibt eine Weile (Anlass für ein Gespräch).
     * Vorgabe: die ersten beiden reden wie bei einer Begegnung, wenn ein Spieler zuhört; alle Paare der Gruppe gelten
     * als begegnet, damit meetings nicht gleich danach noch einmal anstößt.
     */
    void gathered(List<Resident> group, Location at, long now) {
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | treffen | bewohner=%s x=%d y=%d z=%d",
                String.join(",", group.stream().map(r -> TownTalk.key(r.person.name())).toList()), at.getBlockX(),
                at.getBlockY(), at.getBlockZ()));
        for (Resident a : group) {
            for (Resident b : group) {
                if (a != b) {
                    meetings.put(a.person.name() + "|" + b.person.name(), now);
                }
            }
        }
        if (group.size() >= 2 && heard(at) && env.entity(group.get(0).entity) instanceof Mannequin ma
                && env.entity(group.get(1).entity) instanceof Mannequin mb) {
            talk(group.get(0), ma, group.get(1), mb, now);
        }
    }

    /** Treffen, die seit Start beisammen standen (Test, Status). */
    int gatheringsDone() {
        return gatherings;
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
            boolean quiet = lastHeard != null && now - lastHeard < ADDRESS_GAP_MS;
            // Spielernamen im Satz (auch der eigene) werden zu Pseudonymen, bevor irgendetwas damit passiert – auch
            // vor dem Zuruf (T-034).
            String heard = voice.scrub(TownTalk.withoutName(message, r.person.name()), env.players());
            int pick = ThreadLocalRandom.current().nextInt(1000);
            Request.Kind req = Request.parse(heard);
            if (req != Request.Kind.NONE) {
                // Zuruf wirkt auch in der Abklingzeit („komm mit“, gleich danach „bleib hier“): Er kostet keine KI
                // und hält den Bewohner nicht an. Nur die Bestätigung fällt dann weg – sonst ließe sich der Chat
                // damit fluten.
                if (!quiet) {
                    addressed.put(key, now);
                }
                stand(m);
                BuilderNpc.lookAt(m, p.getEyeLocation());
                obey(r, m, p, req, now);
                if (!quiet) {
                    speak(r, m, Request.reply(req, pick));
                }
                return true;
            }
            if (quiet) {
                return true;
            }
            addressed.put(key, now);
            r.busyUntil = now + 10_000;
            stand(m);
            BuilderNpc.lookAt(m, p.getEyeLocation());
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
     * Zuruf ausführen (Request): folgen, bleiben, heim. Jeder in Hörweite darf rufen – Bewohner ändern dabei nichts an
     * der Welt, und Folgen endet von selbst (Follow). Im Log nur der Bewohner, nie der Spieler (wie beim Chat).
     */
    private void obey(Resident r, Mannequin m, Player p, Request.Kind req, long now) {
        // Kein Gesprächs-Stillstand (busyUntil): Wer „komm mit“ ruft, will, dass er sofort losgeht.
        r.busyUntil = 0;
        leaveMeet(r);
        dropTask(r, 0);
        r.follow.stop();
        r.followAt = null;
        r.stayAt = null;
        r.homeUntil = 0;
        r.route.reset();
        r.leg = null;
        switch (req) {
            case FOLLOW -> r.follow.start(p.getUniqueId(), p.getWorld().getName(), now);
            case STAY -> {
                r.stayAt = m.getLocation().clone();
                r.stayUntil = now + STAY_MS;
            }
            case HOME -> r.homeUntil = now + HOME_MS;
            case NONE -> {
                // parse lieferte einen Zuruf – NONE kommt hier nicht an.
            }
        }
        LOG.info(() -> "stadtleben | zuruf | bewohner=" + TownTalk.key(r.person.name()) + " art="
                + req.name().toLowerCase(Locale.ROOT));
    }

    /** Folgt dieser Bewohner gerade jemandem (Test)? */
    boolean following(String name) {
        for (Resident r : residents) {
            if (r.person.name().equals(name)) {
                return r.follow.active();
            }
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

    // ---------------------------------------------------------------- Benutzen (AK-44, Regeln in town/use)

    /** Ziel der Phase oder ein Platz an Bett, Bank, Arbeitsblock (TownUse.target); ohne Welt-Sicht unverändert. */
    private Location useTarget(Resident r, DayPlan.Goal kind, Location base) {
        Entity e = env.entity(r.entity);
        if (!(e instanceof Mannequin m) || base == null) {
            return base;
        }
        UseWorld w = env.useWorld(m.getWorld());
        Actor a = w == null ? null : env.actor(m);
        return a == null ? base : use.target(a, w, kind, base, r.person.job(), tick);
    }

    /** Wegsuche-Welt mit benutzbaren Türen als Durchgang (TownUse.blocks); ohne Welt-Sicht die alte. */
    private CompanionPath.Blocks useBlocks(Resident r, World w, CompanionPath.Blocks base) {
        UseWorld uw = env.useWorld(w);
        return uw == null ? base : use.blocks(base, uw, r.entity, tick);
    }

    /** Tür vor ihm öffnen (TownUse.pass); t = nächster Wegpunkt aus Route.next. */
    private void usePass(Mannequin m, World w, double[] t) {
        UseWorld uw = env.useWorld(w);
        Actor a = uw == null ? null : env.actor(m);
        if (a != null) {
            use.pass(a, uw, t[0], t[2], tick);
        }
    }

    /** Türen hinter ihnen schließen, Schläfer wecken, wenn ein Spieler das Bett will (TownUse.tick). */
    private void useTick() {
        Map<UUID, Actor> actors = new HashMap<>();
        for (Resident r : residents) {
            if (env.entity(r.entity) instanceof Mannequin m && m.isValid()) {
                Actor a = env.actor(m);
                if (a != null) {
                    actors.put(r.entity, a);
                }
            }
        }
        use.tick(tick, actors);
    }

    TownUse use() {
        return use;
    }

    /** Name eines Bewohners fürs Log (wie in erschienen/weg), sonst die ersten 8 Zeichen der UUID. */
    private String residentKey(UUID id) {
        for (Resident r : residents) {
            if (r.entity.equals(id)) {
                return TownTalk.key(r.person.name());
            }
        }
        return id.toString().substring(0, 8);
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
        use.forget(r.entity);
        dropTask(r, 0);
        leaveMeet(r);
        lastGone = reason;
        gone(r, reason);
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | weg | bewohner=%s grund=%s strecke=%.1f ziele=%d "
                + "teleports=%d wegsuchen=%d minuten=%.1f steht_ohne_grund=%d/%d", TownTalk.key(r.person.name()),
                reason, r.walked, r.reachedCount, r.teleports, r.route.searches(),
                (System.currentTimeMillis() - r.born) / 60_000.0, r.idleTicks, r.ticksSeen));
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
        Texts.info(s, runLine());
        Texts.info(s, String.format(Locale.GERMANY, "Feldarbeit: %s · Felder %d · Änderungen %d (verboten %d, "
                + "verworfen %d) · Rate %d/%d je Minute · Treffen %d", work.worldChange() ? "an" : "aus (weltänderung)",
                fields.all().size(), fieldChanges, fieldDenied, fieldStale, rate.used(now), work.blocksPerMinute(),
                gatherings));
        Texts.info(s, String.format(Locale.GERMANY, "Bauen: %s · Baufelder %d · gestartet %d, fertig %d, abgebrochen "
                + "%d, abgelehnt %d · läuft %d", building.enabled() ? "an" : "aus (stadtleben.bauen.an)",
                fields.all().stream().filter(Field::build).count(), buildsStarted, buildsDone, buildsFailed,
                buildsRefused, buildRunning.size()));
        double[] c = cost.millis();
        Texts.info(s, String.format(Locale.GERMANY, "Takt (letzte %d Ticks): Mittel %.3f ms · p99 %.3f ms · höchstens "
                + "%.3f ms · teuerster Tick seit Messung %.3f ms (%s)", cost.size(), c[0], c[1], c[2],
                worstNanos / 1e6, worstCause));
        int[] doors = use.doorCounts();
        Texts.info(s, "Benutzen: " + (use.settings().enabled() ? "an" : "aus") + " · Türen geöffnet " + doors[0]
                + ", geschlossen " + doors[1] + ", verweigert " + doors[2] + ", offen " + use.openDoors());
        if (admin) {
            for (Resident r : residents) {
                Entity e = env.entity(r.entity);
                Texts.info(s, String.format(Locale.GERMANY, "%s (%s): %s · Strecke %.1f · Ziele %d · Teleports %d · "
                        + "Wegsuchen %d · Arbeit %s · seit %.1f min%s", r.person.name(), r.person.job(), r.goal == null
                        ? "–" : r.goal.name().toLowerCase(Locale.ROOT), r.walked, r.reachedCount, r.teleports,
                        r.route.searches(), r.field != null ? "feld " + r.field.name() : r.workAt == null
                                ? WorkSpots.Source.NEAR.label()
                                : r.workAt.spot().source().label() + " " + Texts.clean(r.workAt.spot().name(), 32),
                        (now - r.born) / 60_000.0, e == null ? "" : String.format(Locale.ROOT,
                                " · bei %d %d %d", e.getLocation().getBlockX(), e.getLocation().getBlockY(),
                                e.getLocation().getBlockZ())) + walkState(r));
            }
        }
    }

    // ---------------------------------------------------------------- Befehl feld

    static final String FIELD_USAGE = "/bm stadtleben feld setzen <name> <radius> · feld entfernen <name> · feld liste"
            + " · feld bauen <name> an|aus (Konsole: feld setzen <name> <radius> <welt> <x> <y> <z>)";

    /**
     * /bm stadtleben feld … (AK-43). Admin-Prüfung VOR dem Aufruf in BaumeisterCommand.townLife (fail-closed,
     * TownLifeTest.fieldCommandNeedsAdmin). Gesetzt wird an der Stelle des Admins; von der Konsole mit Welt und Ort.
     */
    void fieldCommand(CommandSender s, String[] args) {
        String verb = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "liste";
        switch (verb) {
            case "setzen" -> setField(s, args);
            case "bauen" -> buildField(s, args);
            case "entfernen" -> {
                if (args.length < 4) {
                    Texts.info(s, FIELD_USAGE);
                    return;
                }
                try {
                    if (!fields.remove(args[3])) {
                        Texts.info(s, "Kein Feld „" + Texts.clean(args[3], 24) + "“ – /bm stadtleben feld liste.");
                        return;
                    }
                } catch (IOException e) {
                    LOG.log(java.util.logging.Level.WARNING, "stadtleben | feld_entfernen_fehlgeschlagen", e);
                    Texts.info(s, "Entfernen fehlgeschlagen: " + Texts.clean(e.getMessage(), 120));
                    return;
                }
                LOG.info(() -> "stadtleben | feld_entfernt | feld=" + Field.key(args[3]) + " von="
                        + Texts.clean(s.getName(), 16));
                refreshFieldWork();
                Texts.info(s, "Feld „" + Field.key(args[3]) + "“ entfernt – dort ändern Bewohner nichts mehr.");
            }
            case "liste" -> {
                if (fields.broken() != null) {
                    Texts.info(s, "Felddatei unlesbar (" + Texts.clean(fields.broken(), 120)
                            + ") – keine Feldarbeit, Datei bleibt unverändert.");
                }
                List<Field> all = fields.all();
                Texts.info(s, "Arbeitsfelder: " + all.size() + " von " + Fields.MAX + " · Weltänderung "
                        + (work.worldChange() ? "an" : "aus (stadtleben.weltänderung)") + " · höchstens "
                        + work.blocksPerMinute() + " Blöcke je Minute");
                for (Field f : all) {
                    Boolean chest = env.fieldChest(env.world(f.world()), f);
                    Texts.info(s, String.format(Locale.ROOT, "%s: %s %d %d %d, Radius %d · Ablage %s%s", f.name(),
                            f.world(), f.x(), f.y(), f.z(), f.radius(), chest == null ? "nicht geladen"
                                    : chest ? "Kiste" : "keine (Ernte bleibt liegen)", f.build()
                                    ? " · Baufeld, Bauten " + fields.built(f.name()).size() + " von "
                                            + building.perField() : ""));
                }
            }
            default -> Texts.info(s, FIELD_USAGE);
        }
    }

    /** /bm stadtleben feld bauen <name> an|aus (AK-45). Admin-Prüfung wie bei allen Feldbefehlen vorher. */
    private void buildField(CommandSender s, String[] args) {
        if (args.length < 5 || !args[4].equalsIgnoreCase("an") && !args[4].equalsIgnoreCase("aus")) {
            Texts.info(s, FIELD_USAGE);
            return;
        }
        boolean on = args[4].equalsIgnoreCase("an");
        String problem;
        try {
            problem = fields.setBuild(args[3], on);
        } catch (IOException e) {
            LOG.log(java.util.logging.Level.WARNING, "stadtleben | baufeld_speichern_fehlgeschlagen | feld="
                    + Field.key(args[3]), e);
            Texts.info(s, "Speichern fehlgeschlagen: " + Texts.clean(e.getMessage(), 120));
            return;
        }
        if (problem != null) {
            Texts.info(s, problem);
            return;
        }
        String name = Field.key(args[3]);
        LOG.info(() -> "stadtleben | baufeld | feld=" + name + " an=" + on + " von=" + Texts.clean(s.getName(), 16));
        refreshFieldWork();
        String missing = !building.enabled() ? " Hinweis: stadtleben.bauen.an ist aus – ohne den Schalter baut "
                + "niemand." : !work.worldChange() ? " Hinweis: stadtleben.weltänderung ist aus – ohne den Schalter "
                        + "baut niemand." : "";
        Texts.info(s, on ? "Feld „" + name + "“ ist jetzt Baufeld: Bauer, Holzfäller und Gärtnerin bauen dort nach "
                + "und nach höchstens " + building.perField() + " kleine Dinge (Zaun, Laterne, Beet, Marktstand, "
                + "Brunnen, Hütte) – im Namen eines Admins, Rückgängig mit /bm rückgängig." + missing
                : "Feld „" + name + "“ ist kein Baufeld mehr – was steht, bleibt stehen.");
    }

    private void setField(CommandSender s, String[] args) {
        if (args.length < 5) {
            Texts.info(s, FIELD_USAGE);
            return;
        }
        int radius;
        try {
            radius = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            Texts.info(s, "Radius „" + Texts.clean(args[4], 12) + "“ ist keine Zahl. " + FIELD_USAGE);
            return;
        }
        Location at;
        if (args.length >= 9) {
            World w = env.world(args[5]);
            if (w == null) {
                Texts.info(s, "Welt „" + Texts.clean(args[5], 32) + "“ ist nicht geladen.");
                return;
            }
            try {
                at = new Location(w, Integer.parseInt(args[6]), Integer.parseInt(args[7]), Integer.parseInt(args[8]));
            } catch (NumberFormatException e) {
                Texts.info(s, "x, y und z als ganze Zahlen. " + FIELD_USAGE);
                return;
            }
        } else if (s instanceof Player p) {
            at = p.getLocation();
        } else {
            Texts.info(s, "An der Konsole mit Welt und Ort: " + FIELD_USAGE);
            return;
        }
        UUID owner = s instanceof Player p ? p.getUniqueId() : null;
        Field f = new Field(Field.key(args[3]), at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                radius, owner);
        try {
            String problem = fields.put(f);
            if (problem != null) {
                Texts.info(s, problem);
                return;
            }
        } catch (IOException e) {
            LOG.log(java.util.logging.Level.WARNING, "stadtleben | feld_speichern_fehlgeschlagen | feld="
                    + f.name(), e);
            Texts.info(s, "Speichern fehlgeschlagen: " + Texts.clean(e.getMessage(), 120));
            return;
        }
        LOG.info(() -> String.format(Locale.ROOT, "stadtleben | feld_gesetzt | feld=%s welt=%s x=%d y=%d z=%d "
                + "radius=%d von=%s", f.name(), f.world(), f.x(), f.y(), f.z(), f.radius(), Texts.clean(s.getName(), 16)));
        refreshFieldWork();
        Boolean chest = env.fieldChest(at.getWorld(), f);
        Texts.info(s, String.format(Locale.ROOT, "Feld „%s“ gesetzt: Mitte %d %d %d, %d×%d Blöcke. Ablage: %s.%s",
                f.name(), f.x(), f.y(), f.z(), 2 * radius + 1, 2 * radius + 1, Boolean.TRUE.equals(chest)
                        ? "Kiste an der Mitte" : "keine Kiste bis " + TownWork.CHEST_REACH
                                + " Blöcke von der Mitte – Ernte bleibt liegen",
                work.worldChange() ? "" : " Hinweis: stadtleben.weltänderung ist aus – ohne den Schalter ändert "
                        + "niemand etwas."));
    }

    /**
     * Für die Statuszeile: Feldarbeit (Platz und Schritt), Teilziel, Weg ja/nein, Stufe des Feststeckens. Damit lässt
     * sich ein hängender Bewohner live lesen statt raten (T-035: Holzfäller wackelte 4 min vor einem halben Baum).
     */
    private String walkState(Resident r) {
        StringBuilder s = new StringBuilder();
        if (r.task != null && r.taskAt != null) {
            s.append(String.format(Locale.ROOT, " · Aufgabe %s bei %d %d %d (Schritt %d/%d)",
                    r.task.job().name().toLowerCase(Locale.ROOT), r.taskAt.getBlockX(), r.taskAt.getBlockY(),
                    r.taskAt.getBlockZ(), r.taskStep[0] + 1, r.task.changes().size()));
        }
        if (r.leg != null) {
            s.append(String.format(Locale.ROOT, " · Teilziel %.1f %.1f %.1f · Weg %s · Stufe %d", r.leg[0], r.leg[1],
                    r.leg[2], r.route.hasPath() ? "ja" : "nein", r.unstuck.level()));
        }
        return s.toString();
    }

    /** Messung neu beginnen (/bm stadtleben messung), für die Live-Probe mit 0, 4 und 12 Bewohnern. */
    void resetCost() {
        cost.clear();
        worstNanos = 0;
        worstCause = "";
        runTicks = 0;
        runIdle = 0;
        runTeleports = 0;
        java.util.Arrays.fill(unstuckCount, 0);
    }

    /** Statuszeile der Laufmessung; auch im Log (forget), damit die Live-Probe sie ohne RCON-Abfrage findet. */
    String runLine() {
        double hours = runTicks / 72_000.0;
        return String.format(Locale.GERMANY, "Laufen (seit Messung): %d Bewohner-Takte · steht ohne Grund %.1f %% · "
                + "Teleports %d · je Bewohner-Stunde %.1f · Feststecken: neu geplant %d, ausgewichen %d, Umweg %d, "
                + "Pause %d", runTicks, runTicks == 0 ? 0.0 : 100.0 * runIdle / runTicks, runTeleports,
                hours == 0 ? 0.0 : runTeleports / hours, unstuckCount[Unstuck.Action.REPLAN.ordinal()],
                unstuckCount[Unstuck.Action.SIDESTEP.ordinal()], unstuckCount[Unstuck.Action.DETOUR.ordinal()],
                unstuckCount[Unstuck.Action.PAUSE.ordinal()]);
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

    /** Die Bewohner als Kopie (Tests: Gruppen und Ziele prüfen, ohne den Takt zu stören). */
    List<Resident> residentsForTest() {
        return List.copyOf(residents);
    }

    int spawned() {
        return spawned;
    }
}
