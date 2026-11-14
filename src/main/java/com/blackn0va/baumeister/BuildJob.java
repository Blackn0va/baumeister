package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.BlockTiers;
import com.blackn0va.baumeister.plan.Tier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Repeater;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Ein laufender Bau. Tickt auf dem Hauptthread; pro Aktion läuft ein Bauarbeiter in Reichweite,
 * schaut hin, schwingt den Arm und setzt den Block mit dem Geräusch, das ein Spieler hören würde.
 *
 * Ablauf: PRÜFEN (volle Behälter, Rechte; in Portionen) → BAUEN → FERTIG. Erst wenn jeder Block geprüft ist,
 * wird der erste gesetzt – ein halber Bau im fremden Plot ist schlimmer als keiner.
 *
 * Helfer (Nutzerwunsch 2026-10-28): Bei großen Bauten arbeiten bis zu drei NPCs. Gebaut wird in
 * Abschnitten (gleicher Plan-Schritt, gleiche Schicht, gleiche Art); einen Abschnitt teilen sich
 * die Arbeiter nach Himmelsrichtung um die Bauwerksmitte, und keiner beginnt den nächsten, bevor
 * alle fertig sind. So bleibt „von unten nach oben“ und „Halt vor Fackel“ erhalten. Wer die
 * Sperre zwischen den Abschnitten entfernt, lässt Helfer die nächste Schicht über Lücken bauen.
 *
 * Helferzahl im Lauf (2026-10-30, /bm helfer, Chat „mehr helfer“): {@link #helpers(int)} holt Helfer dazu oder schickt
 * sie weg und verteilt den Rest des laufenden Abschnitts sofort neu. Ohne das Neuverteilen stünde ein neuer Helfer bis
 * zum nächsten Abschnitt herum – bei einer Schicht mit 400 Blöcken minutenlang –, und die Arbeit eines weggeschickten
 * Helfers bliebe liegen. Wie viele es zu Beginn sind, entscheidet {@link HelperWish}.
 */
final class BuildJob {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Spieler-Reichweite im Kreativmodus ist 5; 4,3 lässt Luft, damit es nicht nach Schummeln aussieht. */
    private static final double REACH = 4.3;
    /** Blöcke pro Tick beim Fliegen, etwa Kreativ-Fluggeschwindigkeit. */
    private static final double SPEED = 0.55;
    /**
     * Prüfphase je Tick nach Zeit (TimeSlice): höchstens CHECK_NANOS, mindestens CHECK_MIN, höchstens CHECK_MAX Aktionen.
     * Vorher fest 300 je Tick – siehe TimeSlice. 10 ms lassen dem Server bei 50 ms je Tick reichlich Luft.
     */
    private static final long CHECK_NANOS = 10_000_000L;
    private static final int CHECK_MIN = 32;
    private static final int CHECK_MAX = 50_000;
    private static final int MAX_NOOP_SKIPS_PER_TICK = 64;
    /** Unter dieser Größe macht ein Abschnitt nur der Baumeister – drei NPCs um vier Blöcke wirken albern. */
    private static final int MIN_SHARED_SEGMENT = 6;
    /** Schnellbau: höchstens so viel Tick-Zeit (ns) – darüber meldet der Server „Can't keep up“. */
    private static final long FAST_TICK_NANOS = 25_000_000L;
    /** Schnellbau: Geräusch und Partikel nur bei jedem n-ten Block, sonst rauscht es nur noch. */
    private static final int FAST_EFFECT_EVERY = 12;

    /**
     * Was eine Zelle beim Setzen braucht (Plan B1–B4): Stufe des neuen Blocks samt Grund, ausdrücklich genannte Zustände
     * (Welt-Namen – ShapeHelper lässt sie, die Physik darf sie nicht ändern), Blockdaten in der neu geschriebenen Fassung
     * mit Weltkoordinaten (null = keine) samt dem, was danach über die API sichtbar sein muss, und das Bauteil (0 = keins).
     */
    record Placement(Tier tier, String reason, Set<String> explicit, String nbt, BlockDataWriter.Expectation expect,
            int group) {

        /** Ohne Stufe und Daten: Wegräumen beim Umbau, Rückgängig-artige Zustände. */
        static final Placement PLAIN = new Placement(Tier.EVERYONE, "", Set.of(), null,
                BlockDataWriter.Expectation.NONE, 0);
    }

    /** Eine Zelle in der Welt. Als PlacementGate.Cell: die Stufe zum Überschreiben liest sie im Moment der Frage. */
    record WorldChange(Block block, BlockData data, Placement placement) implements PlacementGate.Cell {

        WorldChange(Block block, BlockData data) {
            this(block, data, Placement.PLAIN);
        }

        @Override
        public Tier placeTier() {
            return placement.tier();
        }

        @Override
        public String placeReason() {
            return placement.reason();
        }

        @Override
        public Tier removalTier() {
            return BlockTiers.removal(block.getType().getKey().getKey()).tier();
        }

        @Override
        public String removalReason() {
            return BlockTiers.removal(block.getType().getKey().getKey()).reason();
        }

        @Override
        public int group() {
            return placement.group();
        }
    }

    record WorldAction(int step, List<WorldChange> changes) {

        Block anchor() {
            return changes.getFirst().block();
        }

        boolean removal() {
            return changes.stream().allMatch(c -> c.data().getMaterial().isAir());
        }

        int group() {
            return changes.getFirst().placement().group();
        }
    }

    /**
     * originals im Speicher (normaler Bau) oder spool auf der Platte (Stadtbau, sonst null). placed: je Stelle aus
     * originals der Blocktyp, den der Bau zuletzt gesetzt hat (leer = unbekannt, z. B. Stadtbau – dort steht er im
     * Spool).
     */
    record UndoRecord(UUID owner, String ownerName, World world, List<Map.Entry<Block, BlockData>> originals,
            Instant when, UndoSpool spool, Map<Block, Material> placed) {

        UndoRecord(UUID owner, String ownerName, World world, List<Map.Entry<Block, BlockData>> originals,
                Instant when, UndoSpool spool) {
            this(owner, ownerName, world, originals, when, spool, Map.of());
        }

        boolean empty() {
            return originals.isEmpty() && (spool == null || spool.count() == 0);
        }

        long size() {
            return spool != null ? spool.count() : originals.size();
        }
    }

    private enum Phase {
        CHECK, BUILD, DONE
    }

    /** Ein Bauarbeiter: der Baumeister selbst (helper == null) oder ein Helfer. */
    private final class Worker {
        final Mannequin helper;
        final Deque<WorldAction> queue = new ArrayDeque<>();
        int cooldown;

        Worker(Mannequin helper) {
            this.helper = helper;
        }

        Mannequin entity() {
            if (helper == null) {
                return npc.get();
            }
            return helper.isValid() ? helper : null;
        }
    }

    private final BaumeisterPlugin plugin;
    private final BuilderNpc npc;
    private final Settings settings;
    private final UUID ownerId;
    private final String ownerName;
    private final UUID checkAs;
    /** Wessen Stufen beim Setzen gelten (BuildRights); null = Konsole, alle Stufen. */
    private final UUID rightsOf;
    /**
     * Obergrenze des Auftrags (Plan B5, BuildService.Request.cap): CONTENTS, wenn fremder Text (Recherche) im Prompt
     * stand – dann setzt der Bau auch für einen Admin keine gefährlichen Blöcke und keine Befehle.
     */
    private Tier cap = Tier.COMMANDS;
    private final CommandSender replyTo;
    private final List<WorldAction> actions;
    private final List<String> stepNames;
    private final Location center;
    private final Vector frontDir;
    private final int groundY;
    /** Gewünschte Helfer; ab dem Bau zählt workers (ohne den Baumeister). */
    private int helperCount;
    /** true = vom Spieler gewünscht (HelperWish), false = nach Größe – nur für die Ansage. */
    private final boolean helpersWished;
    /** Schnellbau: Aktionen je Tick bei Baubeginn und Arbeiterzahl dazu; mehr Helfer im Lauf = anteilig schneller. */
    private int fastBase;
    private int fastWorkers;
    private final Consumer<BuildJob> onFinish;

    private final Map<Block, BlockData> originals = new LinkedHashMap<>();
    /** Je Stelle der zuletzt gesetzte Blocktyp – das Rückgängig setzt nur zurück, wo der noch steht (UndoCheck). */
    private final Map<Block, Material> placedTypes = new HashMap<>();
    private final List<Worker> workers = new ArrayList<>();
    private Phase phase = Phase.CHECK;
    private BukkitTask task;
    private int checkIndex;
    private int segmentStart;
    private int segmentEnd;
    private int done;
    private int placed;
    /** Stellen, die place() ausgelassen hat, weil dort inzwischen ein Behälter mit Inhalt steht. */
    private int containersSpared;
    private int lastStep = -1;
    private int lastPercent;
    private Instant started;
    /** Längster Tick und Lücken (Ende-Log); die Prüfphase zählt mit. */
    private final TickMeter meter = new TickMeter();
    /** System.nanoTime beim Start (Dauer der Prüfung, auch bei Abbruch) und Beginn des laufenden Ticks (0 = keiner). */
    private long startedNanos;
    private long tickStart;
    /** 0 = Block für Block wie ein Spieler; sonst Aktionen je Tick für alle Arbeiter zusammen. */
    private int fastPerTick;
    /** Chunk-Tickets der Baustelle (chunkKey), gehalten von start() bis finish(). */
    private long[] held = new long[0];
    /** Rechte des Auftraggebers in diesem Bautakt (je Takt neu gelesen, Plan B1). */
    private BuildRights tickRights = BuildRights.OFFLINE;
    private final PlacementGate gate = new PlacementGate(() -> tickRights);
    /** Zellen je Bauteil – das Bauteil kommt ganz oder gar nicht (PlacementGate). */
    private final Map<Integer, List<WorldChange>> groupCells = new HashMap<>();
    /** Wegen fehlender Stufe ausgelassene Blöcke je Stufe und je ein Grund (Hinweis, Ende-Meldung). */
    private final Map<Tier, Integer> skipped = new EnumMap<>(Tier.class);
    private final Map<Tier, String> skipReason = new EnumMap<>(Tier.class);
    private BlockDataWriter dataWriter;
    /** Blöcke, deren Daten nicht geschrieben wurden, weil dort inzwischen ein anderer Block steht. */
    private int dataSkipped;
    private BuildDropGuard.Box dropBox;

    BuildJob(BaumeisterPlugin plugin, BuilderNpc npc, Settings settings, UUID ownerId, String ownerName,
            UUID checkAs, UUID rightsOf, CommandSender replyTo, List<WorldAction> actions, List<String> stepNames,
            Location center, Vector frontDir, int groundY, int helperCount, boolean helpersWished,
            Consumer<BuildJob> onFinish) {
        this.plugin = plugin;
        this.npc = npc;
        this.settings = settings;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.checkAs = checkAs;
        this.rightsOf = rightsOf;
        this.replyTo = replyTo;
        this.actions = actions;
        this.stepNames = stepNames;
        this.center = center;
        this.frontDir = frontDir;
        this.groundY = groundY;
        this.helperCount = HelperWish.clamp(helperCount);
        this.helpersWished = helpersWished;
        this.onFinish = onFinish;
        for (WorldAction a : actions) {
            for (WorldChange c : a.changes()) {
                if (c.placement().group() != 0) {
                    groupCells.computeIfAbsent(c.placement().group(), g -> new ArrayList<>()).add(c);
                }
            }
        }
    }

    /** Obergrenze setzen (vor start). Zurück: dieser Job. */
    BuildJob cap(Tier max) {
        this.cap = max;
        return this;
    }

    /** Soll-Zustand aller Schritte – fürs Baubuch (BuildLedger) nach dem Fertigwerden. */
    List<WorldAction> actions() {
        return actions;
    }

    String ownerName() {
        return ownerName;
    }

    UUID ownerId() {
        return ownerId;
    }

    int total() {
        return actions.size();
    }

    /** Tatsächlich geänderte Blöcke (ohne Aktionen, an denen schon das Richtige stand). */
    int placed() {
        return placed;
    }

    int done() {
        return done;
    }

    boolean running() {
        return phase != Phase.DONE;
    }

    /** Noch in der Prüfung (Rechte, volle Behälter) – für den Fortschrittsbalken (ProgressBarTask). */
    boolean checking() {
        return phase == Phase.CHECK;
    }

    /** Geprüfte Aktionen in der Prüfphase. */
    int checked() {
        return checkIndex;
    }

    /** Mitte der Baustelle – dort sehen umstehende Spieler den Fortschrittsbalken. */
    Location center() {
        return center.clone();
    }

    /** Wo der Baumeister und seine Helfer gerade stehen – Marker auf der Minikarte (Minimap). Server-Thread. */
    List<Location> workerLocations() {
        List<Location> out = new ArrayList<>();
        for (Worker w : workers) {
            Mannequin e = w.entity();
            if (e != null) {
                out.add(e.getLocation());
            }
        }
        return out;
    }

    void start() {
        started = Instant.now();
        startedNanos = System.nanoTime();
        npc.status("prüft den Bauplatz …");
        LOG.info(() -> String.format("bau | prüfung | besitzer=%s aktionen=%d rechteprüfung=%s", ownerName,
                actions.size(), checkAs != null));
        // Erst vorladen, dann halten (ChunkPrefetch, 2026-11-14): Vorher lud addPluginChunkTicket fehlende Chunks
        // synchron, und die Prüfphase las den Rest – gemessen 1,2–2,0 s in 1–2 Ticks an einer neuen Stelle.
        long[] keys = siteChunks();
        ChunkPrefetch.ensure(ChunkPrefetch.of(plugin, center.getWorld()), keys, 2, p -> {
            if (phase == Phase.DONE) {
                if (p != null) {
                    p.release();
                }
                return;  // während des Vorladens abgebrochen
            }
            holdChunks(keys);
            if (p != null) {
                p.release();  // jetzt hält der Bau seine eigenen Tickets (held)
            }
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGuarded, 1L, 1L);
        });
    }

    /**
     * Baustelle samt einem Chunk Rand (Standplätze des NPC liegen bis 2,5 Blöcke außerhalb) für die Dauer des Baus
     * laden. Vorher hielt der BuildJob nichts: Ohne Spieler in der Nähe – /bm bauenbei von der Konsole, oder seit der
     * Warteschlange ein Auftrag, dessen Spieler inzwischen weitergegangen ist – stand der NPC in einem entladenen Chunk
     * („NPC: nicht geladen“), und der Bau blieb ohne Meldung bei 0 stehen (Testserver 2026-10-29: Brücke 15 min bei
     * 0/444, die Burg davor lief nur, weil dort zufällig ein forceload lag). Wer das entfernt, bringt den stillen
     * Hänger zurück. Plugin-Tickets gibt es je Plugin und Chunk nur einmal – ein paralleler Stadtbau, der denselben
     * Chunk hält, verliert ihn mit finish(); das ist unkritisch, weil CityJob seine Chunks je Abschnitt neu bestellt.
     */
    private long[] siteChunks() {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (WorldAction a : actions) {
            Block b = a.anchor();
            box[0] = Math.min(box[0], b.getX() >> 4);
            box[1] = Math.min(box[1], b.getZ() >> 4);
            box[2] = Math.max(box[2], b.getX() >> 4);
            box[3] = Math.max(box[3], b.getZ() >> 4);
        }
        return chunkKeys(box, center.getBlockX() >> 4, center.getBlockZ() >> 4);
    }

    private void holdChunks(long[] keys) {
        World w = center.getWorld();
        held = keys;
        for (long k : held) {
            w.addPluginChunkTicket((int) (k >> 32), (int) k, plugin);
        }
        LOG.fine(() -> "bau | chunks_gehalten | anzahl=" + held.length);
    }

    /**
     * Schlüssel (cx &lt;&lt; 32 | cz) aller Chunks im Rechteck box = {minCx, minCz, maxCx, maxCz} plus ein Chunk Rand und
     * der Chunk der Mitte. Leeres box (keine Aktionen) → nur die Mitte samt Rand.
     */
    static long[] chunkKeys(int[] box, int centerCx, int centerCz) {
        int x0 = Math.min(box[0], centerCx) - 1;
        int z0 = Math.min(box[1], centerCz) - 1;
        int x1 = Math.max(box[2] == Integer.MIN_VALUE ? centerCx : box[2], centerCx) + 1;
        int z1 = Math.max(box[3] == Integer.MIN_VALUE ? centerCz : box[3], centerCz) + 1;
        long[] out = new long[(x1 - x0 + 1) * (z1 - z0 + 1)];
        int n = 0;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cz = z0; cz <= z1; cz++) {
                out[n++] = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** Scheduler-Grenze: eine Ausnahme darf den Bau beenden, aber nicht den Task-Thread vergiften. */
    private void tickGuarded() {
        long t0 = System.nanoTime();
        tickStart = t0;
        try {
            tick();
            meter.tick(t0, System.nanoTime());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "bau | tick_fehler | besitzer=" + ownerName + " fertig=" + done, e);
            finish("Da ist etwas schiefgegangen – ich höre auf. Rückgängig mit /bm rückgängig.");
        } finally {
            tickStart = 0;
        }
    }

    private void tick() {
        switch (phase) {
            case CHECK -> checkTick();
            case BUILD -> buildTick();
            case DONE -> {
            }
        }
    }

    // ---------------------------------------------------------------- Rechteprüfung

    private void checkTick() {
        Player p = null;
        if (checkAs != null) {
            p = Bukkit.getPlayer(checkAs);
            if (p == null) {
                // fail-closed: Ohne Spieler keine Rechteprüfung, also kein Bau.
                finish("Du bist offline gegangen – ohne dich darf ich hier nichts prüfen. Bau abgebrochen.");
                return;
            }
        }
        Player who = p;
        TimeSlice.run(System::nanoTime, CHECK_NANOS, CHECK_MIN, CHECK_MAX, () -> checkNext(who));
        if (phase == Phase.CHECK && checkIndex >= actions.size()) {
            beginBuild();
        }
    }

    /** Eine Aktion prüfen; false = alles geprüft oder Bau abgebrochen (dann ist finish schon gerufen). */
    private boolean checkNext(Player p) {
        if (checkIndex >= actions.size()) {
            return false;
        }
        for (WorldChange c : actions.get(checkIndex).changes()) {
            if (isNoop(c)) {
                continue;
            }
            // Vorher: Ohne checkAs (Konsole, Admin) wurde gar nicht geprüft, und eine volle Kiste im Bauplatz wurde
            // überbaut. Rückgängig sichert nur BlockData – der Inhalt wäre endgültig weg. Deshalb vor der
            // Rechteprüfung und auch ohne Spieler: Treffer bricht vor dem ersten Block ab (fail-closed). Wer das
            // entfernt, lässt den Baumeister Kisteninhalte vernichten, die kein Rückgängig zurückholt.
            Block target = c.block();
            if (ProtectedBlocks.hasContents(target, target.getBlockData())) {
                String what = target.getType().getKey().getKey();
                LOG.info(() -> String.format("bau | behälter_voll | besitzer=%s x=%d y=%d z=%d block=%s", ownerName,
                        target.getX(), target.getY(), target.getZ(), what));
                finish("Da steht ein Block mit Inhalt (" + what + " bei " + target.getX() + " " + target.getY() + " "
                        + target.getZ() + ") – leere oder entferne ihn zuerst, Rückgängig könnte den Inhalt nicht "
                        + "zurückholen. Ich habe nichts angefasst.");
                return false;
            }
            if (p != null && !allowed(p, c)) {
                Block b = c.block();
                LOG.info(() -> String.format("bau | verweigert | besitzer=%s x=%d y=%d z=%d",
                        ownerName, b.getX(), b.getY(), b.getZ()));
                finish("Hier darfst du nicht bauen (" + b.getX() + " " + b.getY() + " " + b.getZ()
                        + ") – ich habe nichts angefasst.");
                return false;
            }
        }
        checkIndex++;
        return checkIndex < actions.size();
    }

    /** Regionsrechte wie ein Spieler (RegionCheck – WorldGuard, PlotSquared …), ASVS V4.1.3. */
    private static boolean allowed(Player p, WorldChange c) {
        return RegionCheck.allowed(p, c.block(), c.data());
    }

    private void beginBuild() {
        phase = Phase.BUILD;
        meter.checkEnded(startedNanos, System.nanoTime());
        dropBox = dropBox();
        if (dropBox != null && plugin.dropGuard() != null) {
            plugin.dropGuard().open(dropBox);
        }
        workers.add(new Worker(null));
        Mannequin main = npc.get();
        Location base = main != null ? main.getLocation() : center.clone();
        for (int i = 1; i <= helperCount; i++) {
            workers.add(new Worker(spawnHelperNear(base, i)));
        }
        if (helperCount > 0) {
            String who = helperCount == 1 ? "einen Helfer" : helperCount + " Helfer";
            Texts.say(npc.name(), base, replyTo, helpersWished ? "Ich hole mir " + who + " dazu!"
                    : "Das ist ein großer Bau – ich hole mir " + who + " dazu!");
        }
        // Block für Block mit Laufen dauert ticksPerBlock je Aktion und Arbeiter. Ein Hochhaus mit 16 810 Blöcken
        // bräuchte so 14 min; über der Zieldauer wird im Schnellbau gesetzt (mehrere Blöcke je Tick, Arbeiter
        // fliegen mit), damit auch große Bauten in etwa der Zieldauer stehen.
        long targetTicks = settings.targetSeconds() * 20L;
        if (normalTicks(actions.size(), settings.ticksPerBlock(), workers.size()) > targetTicks) {
            fastPerTick = (int) Math.min(5000, Math.ceilDiv(actions.size(), targetTicks));
            fastBase = fastPerTick;
            fastWorkers = workers.size();
            Texts.say(npc.name(), base, replyTo, "Großes Ding – ich baue im Schnellgang (" + fastPerTick * 20
                    + " Blöcke/s).");
        }
        LOG.info(() -> String.format("bau | los | besitzer=%s aktionen=%d schritte=%d helfer=%d schnell=%d prüfung_ms=%d "
                + "prüf_ticks=%d", ownerName, actions.size(), stepNames.size(), helperCount, fastPerTick, meter.checkMillis(),
                meter.ticks() + 1));
        npc.status("baut 0/" + actions.size());
    }

    // ---------------------------------------------------------------- Bauen

    private void buildTick() {
        // Rechte je Takt neu: verlassener Kreativmodus, Weltwechsel, entzogenes Recht greifen sofort (Plan B1).
        // Kein Spieler und keine echte Konsole (Befehlsblock, execute as): nur, was jeder darf (Befund 9).
        tickRights = (rightsOf == null && !BuildRights.isConsole(replyTo) ? BuildRights.OFFLINE
                : BuildRights.now(rightsOf, center.getWorld())).capped(cap);
        if (fastPerTick > 0) {
            fastTick();
            if (phase != Phase.DONE) {
                logProgress();
            }
            return;
        }
        if (allIdle()) {
            if (segmentEnd >= actions.size()) {
                long secs = Duration.between(started, Instant.now()).toSeconds();
                finish("Fertig! " + placed + " Blöcke in " + secs + " s. Gefällt's dir nicht? /bm rückgängig");
                return;
            }
            nextSegment();
        }
        for (Worker w : workers) {
            work(w);
        }
        logProgress();
    }

    /**
     * Schnellbau: bis zu fastPerTick Aktionen je Tick, reihum auf die Arbeiter verteilt, über Abschnittsgrenzen hinweg
     * (die Sperre bleibt: der nächste Abschnitt beginnt erst, wenn alle Warteschlangen leer sind). Höchstens
     * FAST_TICK_NANOS – dann ist dieser Tick zu Ende, auch wenn das Budget nicht verbraucht ist.
     */
    private void fastTick() {
        long deadline = System.nanoTime() + FAST_TICK_NANOS;
        int budget = fastPerTick;
        while (budget > 0 && System.nanoTime() < deadline) {
            if (allIdle()) {
                if (segmentEnd >= actions.size()) {
                    long secs = Duration.between(started, Instant.now()).toSeconds();
                    finish("Fertig! " + placed + " Blöcke in " + secs + " s. Gefällt's dir nicht? /bm rückgängig");
                    return;
                }
                nextSegment();
            }
            for (Worker w : workers) {
                WorldAction a = w.queue.pollFirst();
                if (a == null) {
                    continue;
                }
                done++;
                if (isNoop(a)) {
                    continue;
                }
                budget--;
                Mannequin m = w.entity();
                boolean show = m != null && done % FAST_EFFECT_EVERY == 0;
                if (show) {
                    Location target = a.anchor().getLocation().add(0.5, 0.5, 0.5);
                    m.teleport(BuilderNpc.facing(standFor(a), target));
                }
                place(a, show ? m : null);
            }
        }
        if (done % 10 == 0 || fastPerTick > 10) {
            npc.status("baut " + done + "/" + actions.size());
        }
    }

    /**
     * Alle Warteschlangen leer? Als Schleife statt workers.stream().allMatch: fastTick fragt das in seiner inneren
     * Schleife bis zu fastPerTick-mal je Tick, jeder Stream legte dabei Objekte an.
     */
    private boolean allIdle() {
        for (Worker w : workers) {
            if (!w.queue.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void logProgress() {
        int percent = actions.isEmpty() ? 100 : done * 100 / actions.size();
        if (percent / 10 > lastPercent / 10) {
            lastPercent = percent;
            LOG.info(() -> String.format("bau | fortschritt | %d%% aktionen=%d/%d gesetzt=%d", percent, done,
                    actions.size(), placed));
        }
    }

    /** Nächster Abschnitt: gleicher Schritt, gleiche Schicht, gleiche Art (Abriss/Bau). */
    private void nextSegment() {
        segmentStart = segmentEnd;
        WorldAction first = actions.get(segmentStart);
        int end = segmentStart + 1;
        while (end < actions.size()) {
            WorldAction a = actions.get(end);
            if (a.step() != first.step() || a.anchor().getY() != first.anchor().getY()
                    || a.removal() != first.removal()) {
                break;
            }
            end++;
        }
        segmentEnd = end;
        if (first.step() != lastStep) {
            lastStep = first.step();
            int count = 0;
            for (WorldAction a : actions) {
                if (a.step() == first.step()) {
                    count++;
                }
            }
            String what = first.step() < stepNames.size() ? stepNames.get(first.step()) : "?";
            int stepCount = count;
            LOG.info(() -> String.format("bau | schritt | n=%d/%d was=\"%s\" aktionen=%d", first.step() + 1,
                    stepNames.size(), what, stepCount));
        }
        distribute(actions.subList(segmentStart, segmentEnd));
    }

    private void distribute(List<WorldAction> segment) {
        if (workers.size() == 1 || segment.size() < MIN_SHARED_SEGMENT) {
            workers.getFirst().queue.addAll(segment);
            return;
        }
        // Nach Himmelsrichtung um die Mitte verteilen; die Reihenfolge innerhalb bleibt die des
        // Compilers (nächster Nachbar), jeder läuft also an „seiner“ Seite entlang.
        int n = workers.size();
        for (WorldAction a : segment) {
            Block b = a.anchor();
            workers.get(sector(b.getX(), b.getZ(), center.getX(), center.getZ(), n)).queue.add(a);
        }
    }

    /** Arbeiter 0..n-1 für einen Block: gleich große Winkel um die Mitte. Paketweit für den Test. */
    static int sector(int bx, int bz, double cx, double cz, int n) {
        double angle = Math.atan2(bz + 0.5 - cz, bx + 0.5 - cx);
        return (int) Math.floor((angle + Math.PI) / (2 * Math.PI) * n) % n;
    }

    /**
     * So lange (Ticks) braucht ein Bau Block für Block. Vorher nur Aktionen × ticksPerBlock / Arbeiter – das Laufen
     * zwischen den Blöcken fehlte, und die Schätzung lag um den Faktor 3 daneben (Paper-Testkopie 2026-10-30/31,
     * 3 Arbeiter: Bunker geschätzt 105 s, gebaut in 331 s; Taschenrechner 42 s → 140 s; Burg 44 s → 127 s). Damit liefen
     * Bauten bis 5,5 min Block für Block, obwohl über bau.zieldauer-sekunden (120) der Schnellgang gelten soll. Wer
     * den Faktor auf 1 zurückdreht, bringt die langen Bauten zurück.
     */
    static long normalTicks(int actions, int ticksPerBlock, int workers) {
        return (long) actions * ticksPerBlock * WALK_FACTOR / Math.max(1, workers);
    }

    /** Gemessen 2,9–3,3 (siehe normalTicks). */
    static final int WALK_FACTOR = 3;

    // ---------------------------------------------------------------- Helfer im Lauf

    /** Helfer jetzt (im Bau) bzw. geplant (in der Prüfung). */
    int helpers() {
        return phase == Phase.BUILD ? workers.size() - 1 : helperCount;
    }

    /**
     * Helferzahl ändern (0–16). In der Prüfphase gilt sie ab Baubeginn, im Bau sofort: Neue Helfer erscheinen neben
     * dem Baumeister, weggeschickte verschwinden, und der Rest des Abschnitts wird neu verteilt (Reihenfolge des
     * Abschnitts bleibt, die Sperre zum nächsten auch). Im Schnellgang steigt das Tempo anteilig mit.
     */
    void helpers(int n) {
        helperCount = HelperWish.clamp(n);
        if (phase != Phase.BUILD) {
            return;
        }
        Mannequin main = npc.get();
        Location base = main != null ? main.getLocation() : center.clone();
        while (workers.size() - 1 < helperCount) {
            workers.add(new Worker(spawnHelperNear(base, workers.size())));
        }
        while (workers.size() - 1 > helperCount) {
            Worker gone = workers.removeLast();
            if (gone.helper != null) {
                gone.helper.remove();
            }
            workers.getFirst().queue.addAll(gone.queue);
        }
        // Rest des Abschnitts in Abschnittsreihenfolge neu verteilen. Über die Identität: equals eines Records
        // verglich Block und Daten, zwei gleiche Aktionen in einem Abschnitt fielen dann zusammen.
        Set<WorldAction> open = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Worker w : workers) {
            open.addAll(w.queue);
            w.queue.clear();
        }
        List<WorldAction> rest = new ArrayList<>(open.size());
        for (WorldAction a : actions.subList(segmentStart, segmentEnd)) {
            if (open.contains(a)) {
                rest.add(a);
            }
        }
        distribute(rest);
        if (fastBase > 0) {
            fastPerTick = (int) Math.min(5000, Math.max(1, (long) fastBase * workers.size() / fastWorkers));
        }
        int count = helperCount;
        LOG.info(() -> String.format("bau | helfer | besitzer=%s helfer=%d offen=%d schnell=%d", ownerName, count,
                rest.size(), fastPerTick));
    }

    private Mannequin spawnHelperNear(Location base, int number) {
        // Abwechselnd links/rechts, ab dem dritten eine Reihe weiter – 16 Helfer auf zwei Feldern stünden ineinander.
        Vector side = new Vector(-frontDir.getZ(), 0, frontDir.getX()).multiply(number % 2 == 1 ? 2 : -2);
        Vector back = frontDir.clone().multiply(-((number - 1) / 2));
        return npc.spawnHelper(base.clone().add(side).add(back), number);
    }

    private void work(Worker w) {
        if (w.cooldown > 0) {
            w.cooldown--;
            return;
        }
        int skips = 0;
        while (!w.queue.isEmpty() && isNoop(w.queue.peekFirst()) && skips < MAX_NOOP_SKIPS_PER_TICK) {
            w.queue.pollFirst();
            done++;
            skips++;
        }
        WorldAction a = w.queue.peekFirst();
        if (a == null) {
            return;
        }
        Location target = a.anchor().getLocation().add(0.5, 0.5, 0.5);
        Mannequin m = w.entity();
        if (m == null) {
            if (w.helper == null) {
                npc.ensureNear(standFor(a));
            } else {
                // Helfer weg (z. B. per /kill): seine Arbeit übernimmt der Baumeister.
                workers.getFirst().queue.addAll(w.queue);
                w.queue.clear();
            }
            return;
        }
        // Vorher: außer Reichweite → nur ein Schritt, sonst nichts. standFor bleibt aber auf Bodenhöhe; lag ein Block
        // mehr als ~3 Blöcke unter dem Boden (Brückenbogen am Ufer, Fundament), kam der NPC nie in Reichweite und lief
        // jeden Tick „einen Schritt“ zu dem Platz, an dem er schon stand – Brücke 15 min bei 0/444 (Testserver
        // 2026-10-29). Jetzt: angekommen ist angekommen, er setzt den Block vom Standplatz aus. Wer das zurückdreht,
        // bringt den stillen Hänger für jeden Bau mit tiefen Teilen zurück.
        if (BuilderNpc.eye(m.getLocation()).distance(target) > REACH
                && !BuilderNpc.stepToward(m, standFor(a), target, SPEED)) {
            return;
        }
        BuilderNpc.lookAt(m, target);
        place(a, m);
        w.queue.pollFirst();
        done++;
        w.cooldown = settings.ticksPerBlock() - 1;
        if (w.helper == null && done % 10 == 0) {
            npc.status("baut " + done + "/" + actions.size());
        }
    }

    private void place(WorldAction a, Mannequin m) {
        // Stufen (Plan B1/B6): Rechte dieses Takts gegen „was hin soll“ und „was dort steht“; ein Paar und ein Bauteil
        // kommen ganz oder gar nicht.
        PlacementGate.Decision d = gate.action(a.changes(), g -> groupCells.getOrDefault(g, List.of()));
        if (!d.allowed()) {
            skip(a, d);
            return;
        }
        BlockData first = null;
        BlockData removedLast = null;
        // Zweiteiliges (Tür, Bett, hohe Pflanze, Doppeltruhe) ohne Physik: Zwischen den beiden Setz-Aufrufen stünde
        // sonst kurz eine Hälfte allein, und Vanilla lässt sie beim nächsten Nachbar-Update zerfallen.
        boolean pair = a.changes().size() > 1;
        List<WorldChange> withData = null;
        for (WorldChange c : a.changes()) {
            Block b = c.block();
            BlockData before = b.getBlockData();
            // Die Prüfung vor dem Bau reicht nicht allein: Während des Baus kann ein Spieler eine Kiste füllen, die
            // ein früherer Schritt gesetzt hat. Dann diese Stelle auslassen statt den Bau abzubrechen – der halbe Bau
            // steht schon. Wer das entfernt, vernichtet den Inhalt; Rückgängig sichert nur BlockData.
            if (ProtectedBlocks.hasContents(b, before)) {
                containersSpared++;
                continue;
            }
            originals.putIfAbsent(b, before);
            BlockData data;
            if (pair) {
                data = c.data();
                b.setBlockData(data, false);
            } else {
                data = setLikeABuild(b, c.data(), c.placement().explicit());
            }
            placedTypes.put(b, data.getMaterial());
            touched(b);
            placed++;
            // Gefährliches und Befehle je Zelle ins Log (Sicherheitsprüfung 2026-11-08, Befund 11, ASVS V7.2.2): Was
            // der Baumeister so setzt, sieht CoreProtect nicht (kein Ereignis), und ein Befehlsblock stand sonst in
            // keinem Protokoll.
            if (c.placement().tier().ordinal() >= Tier.DANGEROUS.ordinal()) {
                String cmd = c.placement().expect() == null || c.placement().expect().command() == null ? "-"
                        : Texts.clean(c.placement().expect().command(), 120);
                Tier tier = c.placement().tier();
                LOG.info(() -> String.format("bau | stufe_gesetzt | besitzer=%s stufe=%s block=%s welt=%s x=%d y=%d "
                        + "z=%d befehl=%s", ownerName, tier, data.getMaterial().getKey().getKey(),
                        b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), cmd));
            }
            if (c.placement().nbt() != null) {
                if (withData == null) {
                    withData = new ArrayList<>(2);
                }
                withData.add(c);
            }
            if (first == null) {
                first = data;
            }
            if (data.getMaterial().isAir()) {
                removedLast = before;
            }
        }
        if (withData != null) {
            for (WorldChange c : withData) {
                writeData(c);
            }
        }
        if (m == null) {
            return;  // Schnellbau: Arm, Geräusch und Partikel nur bei jedem n-ten Block
        }
        m.swingMainHand();
        Location at = a.anchor().getLocation().add(0.5, 0.5, 0.5);
        World w = at.getWorld();
        if (removedLast != null) {
            BuilderNpc.hold(m, null);
            w.playSound(at, removedLast.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1f, 0.9f);
            w.spawnParticle(Particle.BLOCK, at, 12, 0.3, 0.3, 0.3, 0, removedLast);
        } else if (first != null) {
            BuilderNpc.hold(m, first.getPlacementMaterial());
            w.playSound(at, first.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1f, 0.8f);
        }
    }

    /**
     * Eine Zelle setzen wie der Bau (ohne Paare) – auch für den Selbsttest „jeder Block“ (BlockSelfTest), damit der
     * genau diesen Weg prüft. Physik an, damit Nachbarn (Zaunverbindungen, Treppenecken, Redstone) wie bei einem Spieler
     * nachziehen. Ausdrücklich Genanntes (Plan B3) rechnet ShapeHelper nicht um; ändert Minecraft es beim Setzen selbst
     * (Schienenform, Leitungsstärke), wird der genannte Zustand ohne Physik noch einmal gesetzt – die Nachbarn haben
     * dann schon reagiert. Zurück: was gesetzt werden sollte.
     */
    static BlockData setLikeABuild(Block b, BlockData target, Set<String> explicit) {
        BlockData data = ShapeHelper.adjust(b, target, explicit);
        b.setBlockData(data, true);
        if (!explicit.isEmpty() && b.getType() == data.getMaterial() && !b.getBlockData().equals(data)) {
            b.setBlockData(data, false);
        }
        return data;
    }

    /** Gesetzte Zelle für den Fallschutz merken: Was daneben gleich abfällt, fällt nicht (BuildDropGuard). */
    private void touched(Block b) {
        BuildDropGuard g = plugin.dropGuard();
        if (g != null) {
            g.touched(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }
    }

    /**
     * Blockdaten im selben Tick wie der Block (Plan B4) – nur, wenn dort jetzt wirklich dieser Blocktyp steht. Ein
     * Spieler, der in derselben Sekunde abbaut, oder eine Physik, die den Block ersetzt hat, bekommt keine Daten.
     */
    private void writeData(WorldChange c) {
        Block b = c.block();
        if (b.getType() != c.data().getMaterial()) {
            dataSkipped++;
            LOG.info(() -> String.format("bau | daten_ausgelassen | besitzer=%s x=%d y=%d z=%d grund=blocktyp", ownerName,
                    b.getX(), b.getY(), b.getZ()));
            return;
        }
        if (dataWriter == null) {
            dataWriter = new BlockDataWriter();
        }
        dataWriter.apply(b, c.placement().nbt(), c.placement().expect());
    }

    /** Aktion ohne Stufe: auslassen; beim ersten Mal je Stufe sagen, welches Recht fehlt. */
    private void skip(WorldAction a, PlacementGate.Decision d) {
        int n = a.changes().size();
        boolean first = !skipped.containsKey(d.missing());
        skipped.merge(d.missing(), n, Integer::sum);
        skipReason.putIfAbsent(d.missing(), d.reason());
        if (first) {
            String what = a.group() != 0 ? "Ein Bauteil enthält " + d.reason() + " – das braucht "
                    + BuildRights.missing(d.missing()) + ", ich lasse das ganze Bauteil weg."
                    : d.reason() + " braucht " + BuildRights.missing(d.missing()) + " – solche Blöcke lasse ich aus.";
            Texts.info(replyTo, "Hinweis: " + what);
            Tier tier = d.missing();
            LOG.info(() -> String.format("bau | stufe_fehlt | besitzer=%s stufe=%s grund=%s", ownerName, tier,
                    d.reason()));
        }
    }

    /** Kasten der Baustelle plus 1 Block Rand (BuildDropGuard); null ohne Aktionen. */
    private BuildDropGuard.Box dropBox() {
        if (actions.isEmpty()) {
            return null;
        }
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE};
        for (WorldAction a : actions) {
            for (WorldChange c : a.changes()) {
                Block b = c.block();
                box[0] = Math.min(box[0], b.getX());
                box[1] = Math.min(box[1], b.getY());
                box[2] = Math.min(box[2], b.getZ());
                box[3] = Math.max(box[3], b.getX());
                box[4] = Math.max(box[4], b.getY());
                box[5] = Math.max(box[5], b.getZ());
            }
        }
        return new BuildDropGuard.Box(center.getWorld().getUID(), box[0] - 1, box[1] - 1, box[2] - 1, box[3] + 1,
                box[4] + 1, box[5] + 1);
    }

    private static boolean isNoop(WorldAction a) {
        return a.changes().stream().allMatch(BuildJob::isNoop);
    }

    private static boolean isNoop(WorldChange c) {
        Block b = c.block();
        // Mit Blockdaten nie „schon richtig“: Der Block mag stimmen, seine Daten (Befehl, Text, Inhalt) nicht.
        if (c.placement().nbt() != null) {
            return false;
        }
        if (c.data().getMaterial().isAir()) {
            return b.getType().isAir();
        }
        return b.getBlockData().equals(c.data());
    }

    /**
     * Wo ein Arbeiter steht, um a zu setzen: 2,5 Blöcke vom Block weg, nach außen (weg von der
     * Bauwerksmitte), eine Etage tiefer – so wie man vor einer Wand schwebt und nach vorn baut.
     */
    private Location standFor(WorldAction a) {
        Location blockCenter = a.anchor().getLocation().add(0.5, 0, 0.5);
        Vector out = blockCenter.toVector().subtract(center.toVector()).setY(0);
        if (out.lengthSquared() < 0.25) {
            out = frontDir.clone();
        }
        out.normalize().multiply(2.5);
        Location stand = blockCenter.add(out);
        stand.setY(Math.max(groundY, a.anchor().getY() - 1));
        return stand;
    }

    /** Zwei Blöcke vor dem vordersten gesetzten Block, auf Bodenhöhe in der Mittelachse. */
    private Location landingSpot() {
        double reach = 0;
        for (WorldAction a : actions) {
            Vector off = a.anchor().getLocation().add(0.5, 0, 0.5).toVector().subtract(center.toVector());
            reach = Math.max(reach, off.dot(frontDir));
        }
        Location spot = center.clone().add(frontDir.clone().multiply(reach + 2));
        spot.setY(groundY);
        return spot;
    }

    // ---------------------------------------------------------------- Ende

    void cancel(String reason) {
        if (phase != Phase.DONE) {
            finish(reason);
        }
    }

    /** Bauteile, die ihren Eingang nur bei einem Nachbar-Update prüfen. */
    private static final java.util.Set<Material> REDSTONE = java.util.EnumSet.of(Material.REPEATER,
            Material.COMPARATOR, Material.REDSTONE_WIRE, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH,
            Material.REDSTONE_LAMP);
    private static final BlockFace[] POKE = {BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
        BlockFace.WEST};
    /** Ab so vielen Redstone-Teilen kein Anstupsen – das wäre kein Schaltkreis mehr, sondern Deko. */
    private static final int MAX_REFRESH = 4000;

    /**
     * Jedes Redstone-Teil des Baus einmal anstupsen: in einer freien Nachbarzelle kurz Glas setzen und wieder
     * entfernen (beides mit Physik). Gemessen 2026-10-28 (Logik-Compiler, XOR): Ein Verstärker, den das Plugin
     * neben einen schon geführten Draht setzt, bleibt aus – Vanilla prüft den Eingang nur beim Setzen durch einen
     * Spieler oder bei einem Nachbar-Update. Die Schaltung zeigte nach dem Bau den falschen Zustand und heilte erst
     * beim ersten Umschalten. Wer das entfernt, baut Schaltungen, die anfangs lügen.
     */
    private void refreshRedstone() {
        List<Block> parts = new ArrayList<>();
        for (Block b : originals.keySet()) {
            if (REDSTONE.contains(b.getType())) {
                parts.add(b);
            }
        }
        if (parts.isEmpty() || parts.size() > MAX_REFRESH) {
            return;
        }
        for (Block b : parts) {
            for (BlockFace f : POKE) {
                Block n = b.getRelative(f);
                if (n.getType().isAir()) {
                    touched(n);
                    n.setType(Material.GLASS, true);
                    n.setType(Material.AIR, true);
                    break;
                }
            }
        }
        // Speicher auf 0 erst NACH dem Anstupsen: Erst dann stehen alle Riegel so, wie die fertige Schaltung sie hält.
        int cleared = 0;
        for (Block b : parts) {
            BlockData d = b.getBlockData();
            if (frozenHigh(d)) {
                ((Repeater) d).setPowered(false);
                b.setBlockData(d, true);
                cleared++;
            }
        }
        int latches = cleared;
        LOG.info(() -> "bau | redstone_aufgefrischt | teile=" + parts.size() + " speicher_gelöscht=" + latches);
    }

    /**
     * Ein verriegelter Verstärker, der an ist, hat beim Bau einen Zwischenzustand eingefangen. Gemessen 2026-11-14
     * (Spigot 1.21.11, akkumulator_4bit, ohne Last): Nach Süden gebaut stand der Slave-Riegel der Stufe 3 3 von 3 Mal
     * auf „an/zu“, Q3 = 1 vor dem ersten Takt – die Summenlampe leuchtete kurz, solange ihr Volladdierer halb gebaut
     * war, und der Slave hielt das fest. Nach Nord, Ost und West war er aus. Welcher Teil der Bau-Reihenfolge das
     * entscheidet, ist nicht untersucht – die Reihenfolge allein festzuzurren hielte jede andere Schaltung nicht davon
     * ab. Ein frisch gebauter Speicher beginnt jetzt immer bei 0; in der Regression (47 Maschinen × 4 Richtungen) griff
     * das genau einmal, eben dort. Wer das entfernt, baut Rechenwerke, die je nach Richtung mit Müll starten.
     */
    static boolean frozenHigh(BlockData d) {
        return d instanceof Repeater r && r.isLocked() && r.isPowered();
    }

    /**
     * Absturz-Audit 2026-10-30: refreshRedstone lief zwei Ticks NACH dem Freigeben der Chunk-Tickets – ohne Spieler in
     * der Nähe las und setzte es dann Blöcke in schon entlassenen Chunks (synchrones Nachladen auf dem Server-Thread).
     * Jetzt hält die Baustelle ihre Tickets, bis das Auffrischen gelaufen ist. Fährt das Plugin gerade herunter, gibt
     * es keinen späteren Tick mehr: dann sofort freigeben, ohne Auffrischen. Wer die Reihenfolge wieder umdreht, holt
     * das Nachladen zurück.
     */
    static void afterBuild(boolean placedAny, boolean enabled, java.util.function.Consumer<Runnable> later,
            Runnable refresh, Runnable release) {
        if (!placedAny || !enabled) {
            release.run();
            return;
        }
        later.accept(() -> {
            try {
                refresh.run();
            } finally {
                release.run();
            }
        });
    }

    private void finish(String message) {
        // Abbruch in der Prüfphase (Kiste, verweigert, offline): Prüfdauer bis hier. Vorher stand dann prüfung_ms=0.
        if (phase == Phase.CHECK && startedNanos != 0) {
            meter.checkEnded(startedNanos, System.nanoTime());
        }
        phase = Phase.DONE;
        if (task != null) {
            task.cancel();
        }
        World site = center.getWorld();
        long[] toRelease = held;
        held = new long[0];
        BuildDropGuard.Box box = dropBox;
        dropBox = null;
        // Nach dem Auffrischen des Redstones (2 Takte später) – auch dort fällt beim Nachbar-Update noch etwas ab.
        Runnable release = () -> {
            for (long k : toRelease) {
                site.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
            }
            if (box != null && plugin.dropGuard() != null) {
                plugin.dropGuard().close(box);
            }
        };
        for (Worker w : workers) {
            if (w.helper != null) {
                w.helper.remove();
            }
        }
        npc.status("bereit");
        npc.hold(null);
        Mannequin m = npc.get();
        if (m != null && placed > 0) {
            // Gemessen 2026-10-28: Ohne das blieb der NPC nach dem letzten Firstblock über dem Dach
            // schweben. Jetzt landet er vor der Front und schaut auf sein Werk.
            m.teleport(BuilderNpc.facing(landingSpot(), center.clone().add(0, 2, 0)));
        }
        Player owner = Bukkit.getPlayer(ownerId == null ? new UUID(0, 0) : ownerId);
        if (owner != null) {
            npc.lookAt(BuilderNpc.eye(owner.getLocation()));
        }
        // Ausgelassene Behälter gehören in jede Schlussmeldung (Fertig wie Abbruch): Sonst wundert sich der Spieler über
        // die Lücke im Bau.
        String spared = containersSpared > 0 ? " " + containersSpared + " Behälter mit Inhalt habe ich stehen lassen."
                : "";
        Texts.say(npc.name(), m != null ? m.getLocation() : center, replyTo, message + spared + skippedText());
        // finish läuft meist mitten in tickGuarded, also vor meter.tick: den laufenden Tick mitzählen, sonst fehlte in
        // der Ende-Zeile gerade der letzte Tick (Prüfbefund 2026-10-29).
        String ticks = tickStart != 0 ? meter.summaryWith(tickStart, System.nanoTime()) : meter.summary();
        int skippedAll = skipped.values().stream().mapToInt(Integer::intValue).sum();
        int dataOk = dataWriter == null ? 0 : dataWriter.applied();
        int dataBad = (dataWriter == null ? 0 : dataWriter.failed()) + dataSkipped;
        LOG.info(() -> String.format("bau | ende | besitzer=%s gesetzt=%d aktionen=%d/%d helfer=%d "
                + "behälter_geschont=%d stufe_ausgelassen=%d daten=%d daten_fehler=%d prüfung_ms=%d dauer_ms=%d %s",
                ownerName, placed, done, actions.size(), helperCount, containersSpared, skippedAll, dataOk, dataBad,
                meter.checkMillis(), started == null ? 0 : Duration.between(started, Instant.now()).toMillis(), ticks));
        afterBuild(placed > 0, plugin.isEnabled(),
                task -> Bukkit.getScheduler().runTaskLater(plugin, task, 2L), this::refreshRedstone, release);
        onFinish.accept(this);
    }

    /** Satz für die Schlussmeldung: was wegen fehlender Stufe fehlt, je Stufe mit Grund und Recht. */
    private String skippedText() {
        if (skipped.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" Ausgelassen:");
        boolean firstTier = true;
        for (Map.Entry<Tier, Integer> e : skipped.entrySet()) {
            sb.append(firstTier ? " " : "; ").append(e.getValue()).append(e.getValue() == 1 ? " Block (" : " Blöcke (")
                    .append(skipReason.get(e.getKey())).append(") – braucht ").append(BuildRights.missing(e.getKey()));
            firstTier = false;
        }
        return sb.append('.').toString();
    }

    UndoRecord undoRecord() {
        return new UndoRecord(ownerId, ownerName, center.getWorld(), new ArrayList<>(originals.entrySet()),
                Instant.now(), null, Map.copyOf(placedTypes));
    }
}
