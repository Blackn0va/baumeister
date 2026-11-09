package com.blackn0va.baumeister;

import com.blackn0va.baumeister.BuildJob.UndoRecord;
import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.CityPlanner.Cell;
import com.blackn0va.baumeister.osm.CityPlanner.Section;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Mannequin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Schnellbau einer Stadt: mehrere NPCs, jeder nimmt sich den nächsten Bauabschnitt (Bodenkachel bzw. Gebäude) und
 * setzt je Tick seinen Anteil am Budget. Die Abschnitte kommen Etappe für Etappe aus {@link CityStages}; während eine
 * Etappe gebaut wird, lädt und plant die nächste im Hintergrund.
 *
 * Warum anders als BuildJob: Ein Ort hat Hunderttausende bis Millionen Blöcke; Block für Block mit Blick, Arm und
 * Geräusch dauerte Stunden. Hier: ohne Physik, gleiche Blöcke werden übersprungen, die NPCs fliegen zu ihrem Abschnitt
 * und schwingen den Arm – sichtbar, aber nicht der Engpass. Budget (Blöcke je Tick) und Helferzahl lassen sich im Lauf
 * ändern (/bm tempo, /bm helfer), Pause/Weiter/Stopp ebenso.
 *
 * Tick-Bremse (dev01 2026-10-28, Dorf Hepberg: „Running 21442ms or 428 ticks behind“ beim ersten Bau, beim zweiten am
 * selben Ort nichts): Teuer ist nicht das Setzen, sondern das Erzeugen neuer Chunks. Deshalb je Tick höchstens
 * chunksPerTick noch nicht geladene Chunks anfassen und höchstens msPerTick Millisekunden arbeiten; der Rest kommt im
 * nächsten Tick. Wer das zurückdreht, friert den Server bei jedem neuen Gebiet sekundenlang ein.
 *
 * Rückgängig: über UndoSpool auf der Platte, ohne Obergrenze.
 *
 * Gelände (2026-10-28, Weltbau in der Erdwelt): Mit HeightField folgt der Boden dem Gelände je Säule, Gebäude bekommen
 * eine Basis über ihrem höchsten Geländepunkt und talseitig einen Sockel (TerrainFit). Ohne HeightField gilt exakt das
 * alte Verhalten (eine feste Höhe baseY + y). Rückgängig und Kistenschutz laufen für Sockel und Gelände-Zellen durch
 * dieselbe Schleife wie immer – wer die Höhenrechnung aus work() herauszieht, muss beides mitnehmen.
 */
final class CityJob {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Alle so viele Zellen auf die Uhr schauen – System.nanoTime je Block wäre spürbar teurer als nötig. */
    private static final int CLOCK_EVERY = 64;

    private final BaumeisterPlugin plugin;
    private final BuilderNpc npc;
    private final CommandSender requester;
    private final UUID owner;
    private final String ownerName;
    private final World world;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final String place;
    private final StageSource stages;
    private final UndoSpool spool;
    private final Consumer<CityJob> onFinish;
    /** null = flach wie bisher (baseY + y); sonst Höhen aus dem Gelände, siehe TerrainFit. */
    private final HeightField ground;
    /** Block für Sockelzellen (TerrainFit.PLINTH), nur mit Gelände. */
    private final BlockData plinth;
    /** Text der Straßenschilder – eigener kleiner Takt, nicht im Bau-Takt (siehe SignWriter). */
    private final SignWriter signs;
    /**
     * Erst true, wenn der Lauf über „Fertig!“ endet und keine Etappe übersprungen wurde – Stopp, Fehler und Lücken
     * nicht (der Weltbau merkt sich nur Vollständiges, siehe complete()).
     */
    private boolean completed;

    /**
     * Ein Abschnitt in der Warteschlange. absolute: y in cells ist schon Weltkoordinate (Gelände, TerrainFit), sonst
     * relativ zu baseY. Der Rest eines halb gebauten Abschnitts behält so seine einmal gerechnete Basis – rechnete
     * man sie für den Rest neu, käme ein Haus mit weniger Säulen auf eine andere Höhe und zerbräche in zwei Teile.
     */
    /** stage: Etappe, zu der der Abschnitt gehört – für den Fortsetzungspunkt nach einem Neustart (resumeStage). */
    private record Task(Section section, List<Cell> cells, boolean absolute, int[] box, int stage) {
    }

    /** Abschnitte in Planreihenfolge; genommen wird nur über take() (Reihenfolge an geteilten Wänden). */
    private final SectionQueue<Task> queue = new SectionQueue<>(Task::box);
    private final List<BlockData> palette = new ArrayList<>();
    private final List<Worker> workers = new ArrayList<>();
    /** Geplante und schon vorbereitete Etappe (StagePrep im Hintergrund), null = nichts in Arbeit. */
    private CompletableFuture<StagePrep.Prepared> loading;
    /** Etappe, die gerade geladen wird, und wie oft sie schon gescheitert ist. */
    private int loadingStage;
    private int stageAttempts;
    private static final int STAGE_RETRIES = 2;
    private int nextStage;
    private int stagesDone;
    private int stagesFailed;
    private int budget;
    /** Wie viele schon stimmende Zellen einen gesetzten Block kosten (stadt.gleiche-zellen-je-block, BuildBudget). */
    private final int samePerBlock;
    private final int chunksPerTick;
    private final long nanosPerTick;
    private int wantedHelpers;
    private boolean paused;
    private boolean running;
    private boolean waitingSaid;
    private long planned;
    private long processed;
    private long placed;
    /** Zellen, die ausgelassen wurden, weil dort ein Behälter mit Inhalt steht. */
    private long containersSpared;
    private int lastTenth = -1;
    private long lastReport = System.nanoTime();
    /**
     * Chunks mit Plugin-Ticket, älteste zuerst. Gemessen 2026-10-28 auf dem Testserver, 400 m um Bad Kreuznach auf
     * frischem Gelände einer normalen Welt (Berge, Bäume – wie dev01):
     *   - ohne Bremse: 181 Ticks über 50 ms, 21 über 200 ms, längster 2055 ms, „Can't keep up“;
     *   - höchstens 8 neue Chunks und 25 ms je Tick: 159 / 17 / 1195 ms – ein einzelner neu erzeugter Chunk kostet
     *     auf dem Hauptthread allein schon über 200 ms, dagegen hilft kein Zeitbudget;
     *   - nur Bremse, ohne Ticket (Flachwelt): 6 min 52 s statt 53 s, weil der Server die Chunks gleich wieder entlud.
     *   - alle Chunks einer Etappe auf einmal mit Ticket: Server bis 17,9 s im Rückstand. Spigot lädt einen Chunk beim
     *     Ticket offenbar sofort selbst (erster Tick 2,7 s) – Erzeugen im Hintergrund gibt es hier nicht.
     * Jetzt: Tickets halten die Chunks der laufenden Etappe geladen (900 decken zwei Etappen samt Überhang), neue gehen
     * nur im Takt der Lastregelung raus. Wirklich schnell wird es nur auf schon erzeugtem oder flachem Gelände
     * (/bm stadtwelt): Flachwelt 400 m in 53 s ohne „Can't keep up“.
     */
    // Seit 2026-11-01 in ChunkHolder: unter Paper erst asynchron laden, dann das Ticket (siehe dort, Messung).
    private final ChunkHolder held;
    private static final int MAX_HELD = 900;
    /**
     * In hohen Welten (Erd-/Geländewelt, 4 064 Blöcke) höchstens so viele: eine Etappe (256 Chunks) plus Rand. Ein
     * Chunk kostet dort rund achtmal so viel Speicher (gemessen 2026-10-28: 16 × 16 Chunks samt Ladering +289 MB statt
     * +36 MB); mit 900 lief der Testserver (3 GB) beim Weltbau in den OutOfMemoryError. Wer das wieder auf 900 setzt,
     * braucht dafür gut 1 GB Heap allein für die gehaltenen Chunks.
     */
    private static final int MAX_HELD_TALL = 300;
    private final int maxHeld;
    /**
     * Lastregelung, siehe LoadControl. Gemessen 2026-10-28 (normale Welt, frisches Gelände, 400 m): feste Rate 8 Tickets
     * je Tick → Server 22,8 s im Rückstand. Eine feste Zahl passt nie zugleich zum Laptop und zum Celeron auf dev01.
     */
    private final LoadControl load;
    private long lastGap;
    /** Längster eigener Tick in ns – belegt im Log, ob die Tick-Bremse hält. */
    private long maxTickNanos;
    private int ticksOver50;
    private int ticksOver200;
    /** Abstand zwischen zwei Ticks dieses Baus – enthält alles, was der Server dazwischen tut (Chunks, Licht, Speichern). */
    private long lastTickStart;
    private long maxGapNanos;
    private int gapsOver100;
    private int gapsOver1000;
    /** Vorzubestellende Chunks in Bau-Reihenfolge; je Tick gehen höchstens chunksPerTick davon als Ticket raus. */
    private final java.util.LinkedHashSet<Long> toOrder = new java.util.LinkedHashSet<>();
    private Instant started;
    private BukkitTask task;
    /** Stadtansicht im Host; null = aus. hostJob kennzeichnet diesen Bau dort (neuer Bau ersetzt die Karte). */
    private final HostReporter host;
    /** Blöcke je Rasterzelle für die Draufsicht (CityRaster.cellFor), 0 = keine Draufsicht. */
    private final int rasterCell;
    /** Meldet den Fortsetzungspunkt, sobald er sich ändert (CityService schreibt ihn auf die Platte). */
    private java.util.function.IntConsumer onResumePoint;
    /** Jede Etappe beim Baubeginn (CityService versetzt damit den Reisepunkt, TravelSpot). */
    private Consumer<CityPlanner.Plan> onStage;
    private int lastResumePoint = -1;
    private long lastResumeCheck;
    private final String hostJob = HostReporter.newJob();

    private final class Worker {
        final Mannequin helper;  // null = der Baumeister selbst
        Task task;
        int cursor;
        int sinceMove;

        Worker(Mannequin helper) {
            this.helper = helper;
        }

        Mannequin body() {
            return helper == null ? npc.get() : helper.isValid() ? helper : null;
        }
    }

    /** first = schon geplante Etappe 0 (aus der Vorschau von /bm stadt), die übrigen plant der Lauf selbst. */
    CityJob(BaumeisterPlugin plugin, BuilderNpc npc, CommandSender requester, UUID owner, String ownerName,
            Location base, StageSource stages, CityPlanner.Plan first, int budget, int helpers, int chunksPerTick,
            int msPerTick, int lagMs, UndoSpool spool, Consumer<CityJob> onFinish) {
        this(plugin, npc, requester, owner, ownerName, base, stages, first, budget, helpers, chunksPerTick, msPerTick,
                lagMs, spool, null, onFinish);
    }

    /** Wie oben; ground != null setzt den Ort auf das Gelände (siehe Klassenkommentar), baseY zählt dann nicht. */
    CityJob(BaumeisterPlugin plugin, BuilderNpc npc, CommandSender requester, UUID owner, String ownerName,
            Location base, StageSource stages, CityPlanner.Plan first, int budget, int helpers, int chunksPerTick,
            int msPerTick, int lagMs, UndoSpool spool, HeightField ground, Consumer<CityJob> onFinish) {
        this(plugin, npc, requester, owner, ownerName, base, stages, first, 0, budget, helpers, chunksPerTick,
                msPerTick, lagMs, spool, ground, onFinish);
    }

    /**
     * Fortsetzen ab Etappe startStage (first = deren Plan) – nach einem Neustart (CityResume). Die Etappen davor
     * stehen schon; die Zählung „Etappe k von n“ läuft ab startStage weiter.
     */
    CityJob(BaumeisterPlugin plugin, BuilderNpc npc, CommandSender requester, UUID owner, String ownerName,
            Location base, StageSource stages, CityPlanner.Plan first, int startStage, int budget, int helpers,
            int chunksPerTick, int msPerTick, int lagMs, UndoSpool spool, HeightField ground,
            Consumer<CityJob> onFinish) {
        this.plugin = plugin;
        this.npc = npc;
        this.requester = requester;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = base.getWorld();
        this.maxHeld = maxHeld(world.getMaxHeight() - world.getMinHeight());
        this.held = new ChunkHolder(ChunkHolder.of(world, plugin), maxHeld);
        this.baseX = base.getBlockX();
        this.baseY = base.getBlockY();
        this.baseZ = base.getBlockZ();
        this.place = stages.place();
        this.stages = stages;
        this.budget = budget;
        this.wantedHelpers = helpers;
        this.chunksPerTick = chunksPerTick;
        this.nanosPerTick = msPerTick * 1_000_000L;
        this.load = new LoadControl(lagMs * 1_000_000L, chunksPerTick);
        this.spool = spool;
        this.onFinish = onFinish;
        this.ground = ground;
        this.plinth = ground == null && !stages.absolute() ? null
                : Bukkit.createBlockData("minecraft:" + TerrainFit.PLINTH_BLOCK);
        // plugin ist nur im Test null (CityJobTest baut den Job ohne Server).
        this.samePerBlock = plugin == null ? BuildBudget.DEFAULT_SAME_PER_BLOCK
                : BuildBudget.samePerBlock(plugin.getConfig());
        // Etappe 0 wie alle anderen im Hintergrund vorbereiten; pollLoading übernimmt sie im ersten Tick, ein Fehler
        // läuft in die Wiederholung über planAndPrepare(0). Prüfbefund 2026-10-29: Vorher sortierte StagePrep sie hier
        // im Konstruktor, also im Hauptthread bei /bm stadt los und bei JEDER Weltbau-Kachel – gemessen 110–150 ms für
        // 237 154 Zellen (Etappe 1 Bad Kreuznach), 410–460 ms für 1 Mio. auf dem Laptop (dev01 nicht gemessen,
        // dort langsamer), und keine Ende-Zeile zeigte es (läuft außerhalb von tickGuarded). Wer das wieder synchron
        // macht, bringt diese Spitze bei jedem Start zurück.
        loadingStage = startStage;
        stagesDone = startStage;
        this.signs = new SignWriter(plugin, world, baseX, baseY, baseZ, stages.absolute());
        // Stadtansicht im Host (HostReporter): null = aus oder ohne Plugin (Test).
        this.host = plugin == null ? null : plugin.hostReporter();
        this.rasterCell = host == null ? 0 : CityRaster.cellFor(stages.count());
        nextStage = startStage + 1;
        int bx = baseX;
        int bz = baseZ;
        int cell = rasterCell;
        loading = CompletableFuture.supplyAsync(() -> StagePrep.prepare(first, bx, bz, cell));
    }

    /**
     * Etappe k planen und im ForkJoin-Pool vorbereiten (StagePrep) – der Hauptthread übernimmt nur noch fertige
     * Listen. baseX/baseZ sind final, StagePrep ist rein: kein Zugriff auf Welt oder Felder im Hintergrund.
     */
    private CompletableFuture<StagePrep.Prepared> planAndPrepare(int k) {
        int bx = baseX;
        int bz = baseZ;
        int cell = rasterCell;
        return stages.plan(k).thenApplyAsync(p -> StagePrep.prepare(p, bx, bz, cell));
    }

    void start() {
        running = true;
        started = Instant.now();
        workers.add(new Worker(null));
        adjustHelpers();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGuarded, 1L, 1L);
        if (host != null) {
            host.start(hostJob, stages.absolute() ? "weltbau" : "stadt", place, world.getName(), stages.count(), baseX,
                    baseZ);
        }
        LOG.info(() -> String.format("stadt | start | ort=%s etappen=%d budget=%d helfer=%d chunks_je_tick=%d "
                + "ms_je_tick=%d gleiche_je_block=%d", place, stages.count(), budget, wantedHelpers, chunksPerTick,
                nanosPerTick / 1_000_000, samePerBlock));
    }

    boolean running() {
        return running;
    }

    /** Über „Fertig!“ beendet (nicht gestoppt, nicht abgebrochen) und ohne übersprungene Etappe. */
    boolean completed() {
        return completed;
    }

    /** Etappen, die nach allen Wiederholungen übersprungen wurden. */
    int stagesFailed() {
        return stagesFailed;
    }

    /**
     * Vollständig heißt: keine Etappe übersprungen. Prüfbefund 2026-10-28: Vorher galt jedes „Fertig!“ als fertig,
     * auch mit Lücken – der Weltbau trug die Kachel dann dauerhaft in weltbau.txt ein, und die fehlende 256-m-Etappe
     * baute nie wieder jemand (Automatik und /bm weltbau &lt;Ort&gt; filtern Fertiges heraus). Wer das lockert, macht
     * jede Lücke dauerhaft.
     */
    static boolean complete(int stagesFailed) {
        return stagesFailed == 0;
    }

    UUID owner() {
        return owner;
    }

    CommandSender requester() {
        return requester;
    }

    void pause(boolean p) {
        paused = p;
    }

    boolean paused() {
        return paused;
    }

    void budget(int blocksPerTick) {
        budget = blocksPerTick;
    }

    int budget() {
        return budget;
    }

    void helpers(int n) {
        wantedHelpers = n;
        adjustHelpers();
    }

    int helpers() {
        return workers.size() - 1;
    }

    /** Geschätzter Anteil 0..0,99 für den Fortschrittsbalken (ProgressBars.cityFraction). */
    double fraction() {
        return ProgressBars.cityFraction(processed, planned, stagesDone, stages.count());
    }

    String place() {
        return place;
    }

    /** Mitte des Orts – dort sehen umstehende Spieler den Fortschrittsbalken. */
    Location center() {
        return new Location(world, baseX + 0.5, baseY, baseZ + 0.5);
    }

    String progress() {
        long secs = Math.max(1, Duration.between(started, Instant.now()).toSeconds());
        long rate = processed / secs;
        // Restzeit grob: bisheriger Schnitt je Etappe × offene Etappen.
        long perStage = stagesDone > 0 ? planned / stagesDone : planned;
        // Erst nach 30 s schätzen – in den ersten Sekunden lädt die Karte, und die Rate ist Unsinn („noch ca. 14 h“).
        long left = rate > 0 && secs >= 30
                ? ((long) (stages.count() - stagesDone) * perStage + (planned - processed)) / rate : -1;
        return String.format("%s: Etappe %d von %d, %,d Blöcke gesetzt (%,d geprüft), %,d Blöcke/s, %d Helfer%s%s%s%s",
                place, Math.min(stagesDone, stages.count()), stages.count(), placed, processed, rate, helpers(),
                stages.mapProgress(), left >= 0 ? ", noch ca. " + formatDuration(left) : "", loading != null && queue.isEmpty()
                        && idle() && !paused ? " – lädt Karte" : "", paused ? " – PAUSE" : "");
    }

    private static String formatDuration(long secs) {
        return secs >= 7200 ? secs / 3600 + " h " + secs % 3600 / 60 + " min" : secs >= 120 ? secs / 60 + " min"
                : secs + " s";
    }

    void cancel(String why) {
        if (!running) {
            return;
        }
        finish(why);
    }

    private void addStage(StagePrep.Prepared prepared) {
        CityPlanner.Plan p = prepared.plan();
        // Die Palette wächst über die Etappen nur an (ein Planer), Indizes bleiben gültig.
        for (int i = palette.size(); i < p.palette().size(); i++) {
            String name = p.palette().get(i);
            BlockData d;
            try {
                d = Bukkit.createBlockData("minecraft:" + name);
            } catch (IllegalArgumentException e) {
                d = null;  // unbekannter Block in dieser Serverfassung: Zellen fallen weg
                LOG.warning("stadt | block_unbekannt | block=" + name);
            }
            palette.add(d);
        }
        boolean absolute = stages.absolute();  // Weltbau: Höhen schon im Planungs-Thread gerechnet
        for (int i = 0; i < p.sections().size(); i++) {
            Section s = p.sections().get(i);
            queue.add(new Task(s, s.cells(), absolute, prepared.boxes().get(i), stagesDone));
        }
        for (long k : prepared.chunks()) {
            toOrder.add(k);
        }
        signs.add(p.signs());
        if (host != null) {
            host.stage(hostJob, stagesDone, prepared.raster());
        }
        planned += p.blocks();
        stagesDone++;
        if (onStage != null) {
            onStage.accept(p);
        }
        requestNext();
    }

    /** Nächste Etappe vorplanen, während diese gebaut wird. */
    private void requestNext() {
        // Erst wenn die laufende Etappe fast abgearbeitet ist. Vorher (Messung 2026-10-28) stieß jede fertig geplante
        // Etappe sofort die nächste an – nach wenigen Sekunden lagen alle 13 im Speicher, genau was der Etappenbau
        // verhindern soll. Eine Etappe hat ~100–300 Abschnitte; bei QUEUE_LOW bleibt Zeit fürs Planen und Vorbestellen.
        if (loading == null && nextStage < stages.count() && queue.size() <= QUEUE_LOW) {
            loadingStage = nextStage++;
            stageAttempts = 0;
            loading = planAndPrepare(loadingStage);
        }
    }

    private static final int QUEUE_LOW = 40;

    private void adjustHelpers() {
        Mannequin main = npc.get();
        Location around = main != null ? main.getLocation() : new Location(world, baseX + 0.5, baseY + 3, baseZ + 0.5);
        while (helpers() < wantedHelpers && running) {
            int n = helpers() + 1;
            workers.add(new Worker(npc.spawnHelper(around.clone().add(n % 2 == 0 ? 2 : -2, 1, n), n)));
        }
        while (helpers() > wantedHelpers) {
            Worker w = workers.removeLast();
            if (w.task != null && w.cursor < w.task.cells().size()) {
                // Angefangener Abschnitt geht zurück an die anderen: Rest als eigener Abschnitt vorn einreihen.
                queue.putBack(new Task(w.task.section(), w.task.cells().subList(w.cursor, w.task.cells().size()),
                        w.task.absolute(), w.task.box(), w.task.stage()));
            }
            if (w.helper != null) {
                w.helper.remove();
            }
        }
    }

    private boolean idle() {
        return workers.stream().allMatch(w -> w.task == null || w.cursor >= w.task.cells().size());
    }

    /** Scheduler-Grenze: eine Ausnahme beendet den Stadtbau, vergiftet aber nicht den Task-Thread. */
    private void tickGuarded() {
        long t0 = System.nanoTime();
        if (lastTickStart != 0) {
            long gap = t0 - lastTickStart;
            maxGapNanos = Math.max(maxGapNanos, gap);
            gapsOver100 += gap > 100_000_000L ? 1 : 0;
            gapsOver1000 += gap > 1_000_000_000L ? 1 : 0;
            lastGap = gap;
        }
        lastTickStart = t0;
        try {
            tick();
            long took = System.nanoTime() - t0;
            maxTickNanos = Math.max(maxTickNanos, took);
            ticksOver50 += took > 50_000_000L ? 1 : 0;
            ticksOver200 += took > 200_000_000L ? 1 : 0;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "stadt | tick_fehler | ort=" + place + " gesetzt=" + placed, e);
            finish("Da ist etwas schiefgegangen – ich höre auf. Was steht, räumt " + stages.undoCommand() + " weg.");
        }
    }

    private void tick() {
        if (!running || paused) {
            return;
        }
        pollLoading();
        if (!running) {
            return;
        }
        requestNext();
        int allowed = load.onTick(lastGap);
        if (allowed < 0) {
            return;  // Server hinkt hinterher: diesen Tick nichts tun, er soll aufholen
        }
        long deadline = System.nanoTime() + nanosPerTick;
        int[] chunkLoads = {allowed};
        // Gedrosselt vorbestellen: 300 Tickets auf einmal hielten den Server 17,9 s an (Messung 2026-10-28).
        for (java.util.Iterator<Long> it = toOrder.iterator(); it.hasNext() && chunkLoads[0] > 0;) {
            long k = it.next();
            it.remove();
            if (!held.contains(k)) {
                hold((int) (k >> 32), (int) k);
                chunkLoads[0]--;
            }
        }
        int share = Math.max(1, budget / workers.size());
        boolean anyWork = false;
        for (Worker w : workers) {
            anyWork |= work(w, share, deadline, chunkLoads);
        }
        if (!anyWork && queue.isEmpty()) {
            if (loading == null && nextStage >= stages.count()) {
                completed = complete(stagesFailed);
                long secs = Duration.between(started, Instant.now()).toSeconds();
                finish("Fertig! " + place + ": " + String.format("%,d", placed) + " Blöcke in " + stages.count()
                        + " Etappen, " + formatDuration(secs) + "." + (stagesFailed > 0 ? " " + stagesFailed
                        + " Etappe(n) ohne Kartendaten übersprungen." : "") + (spool != null && !spool.failed()
                        ? " Gefällt's nicht? " + stages.undoCommand() : ""));
                return;
            }
            if (!waitingSaid) {
                waitingSaid = true;
                npc.status("lädt die Karte für Etappe " + (stagesDone + 1));
            }
        }
        if (host != null) {
            reportToHost();
        }
        checkResumePoint();
        // Meldung alle 10 % der Etappen oder jede Minute – vorher nur alle 10 %: bei Hepberg (193 Etappen) kam
        // minutenlang nichts, und der Bau sah aus, als stünde er (dev01 2026-10-28).
        int tenth = stages.count() == 0 ? 10 : stagesDone * 10 / stages.count();
        long now = System.nanoTime();
        if (tenth > lastTenth || now - lastReport > 60_000_000_000L) {
            lastTenth = Math.max(lastTenth, tenth);
            lastReport = now;
            LOG.info(() -> "stadt | fortschritt | " + progress());
            if (tenth < 10 && Duration.between(started, Instant.now()).toSeconds() >= 5) {
                Texts.info(requester, progress());
            }
        }
    }

    void onStage(Consumer<CityPlanner.Plan> listener) {
        this.onStage = listener;
    }

    void onResumePoint(java.util.function.IntConsumer listener) {
        this.onResumePoint = listener;
    }

    /**
     * Erste Etappe, die noch nicht ganz steht: die kleinste unter wartenden Abschnitten, angefangenen Abschnitten der
     * Arbeiter und der gerade ladenden Etappe; sonst die nächste noch nicht geplante. Ab hier setzt ein Neustart fort –
     * eine halb gebaute Etappe noch einmal zu bauen schadet nicht (gleiche Blöcke werden übersprungen), eine
     * übersprungene fehlte für immer.
     */
    int resumeStage() {
        int min = loading != null ? loadingStage : nextStage;
        min = Math.min(min, queue.minOf(Task::stage, Integer.MAX_VALUE));
        for (Worker w : workers) {
            if (w.task != null && w.cursor < w.task.cells().size()) {
                min = Math.min(min, w.task.stage());
            }
        }
        return Math.min(min, stages.count());
    }

    /** Höchstens einmal je Sekunde den Fortsetzungspunkt prüfen und bei Änderung melden. */
    private void checkResumePoint() {
        if (onResumePoint == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastResumeCheck < 1_000_000_000L) {
            return;
        }
        lastResumeCheck = now;
        int k = resumeStage();
        if (k != lastResumePoint) {
            lastResumePoint = k;
            onResumePoint.accept(k);
        }
    }

    /** Höchstens einmal je Sekunde (HostReporter drosselt selbst, ohne JSON zu bauen). */
    private void reportToHost() {
        long secs = Math.max(1, Duration.between(started, Instant.now()).toSeconds());
        Mannequin m = npc.get();
        int[] at = m == null ? null : new int[] {m.getLocation().getBlockX(), m.getLocation().getBlockZ()};
        host.progress(hostJob, stagesDone, stagesDone > 0 ? stagesDone - 1 : -1, placed, planned,
                (double) processed / secs, paused, at);
    }

    private void pollLoading() {
        if (loading == null || !loading.isDone()) {
            return;
        }
        CompletableFuture<StagePrep.Prepared> done = loading;
        loading = null;
        try {
            StagePrep.Prepared p = done.join();
            waitingSaid = false;
            addStage(p);
            npc.status("baut " + place + " – Etappe " + stagesDone + "/" + stages.count());
        } catch (CompletionException | java.util.concurrent.CancellationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (stageAttempts < STAGE_RETRIES && running) {
                // München 2026-10-28: Overpass antwortete einmal mit 502, und ohne Wiederholung fielen alle 9
                // Etappen dieser Kachel weg. Jetzt dieselbe Etappe nach 30 s erneut (die Kachel ist aus dem Speicher).
                stageAttempts++;
                int k = loadingStage;
                LOG.log(Level.WARNING, "stadt | etappe_wiederholung | ort=" + place + " etappe=" + (k + 1) + " versuch="
                        + stageAttempts, cause);
                loading = CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(30, java.util.concurrent.TimeUnit.SECONDS))
                        .thenCompose(x -> planAndPrepare(k));
                return;
            }
            stagesFailed++;
            stagesDone++;
            LOG.log(Level.WARNING, "stadt | etappe_fehlgeschlagen | ort=" + place + " etappe=" + stagesDone, cause);
            Texts.info(requester, "Etappe " + stagesDone + " übersprungen: " + cause.getMessage());
            if (stagesFailed >= 3 && stagesFailed == stagesDone) {
                finish("Der Kartendienst liefert nichts – ich höre auf. Später erneut mit /bm stadt versuchen.");
                return;
            }
            requestNext();
        }
    }

    /** Wie viele Chunks ein Job festhalten darf – abhängig von der Welthöhe (siehe MAX_HELD_TALL). */
    static int maxHeld(int worldHeight) {
        return worldHeight > 384 ? MAX_HELD_TALL : MAX_HELD;
    }

    private void hold(int cx, int cz) {
        held.hold(cx, cz);
    }

    /** Rechtecke der Abschnitte, an denen die anderen Arbeiter gerade bauen. */
    private List<int[]> runningBoxes(Worker self) {
        List<int[]> out = new ArrayList<>(workers.size());
        for (Worker o : workers) {
            if (o != self && o.task != null && o.cursor < o.task.cells().size()) {
                out.add(o.task.box());
            }
        }
        return out;
    }

    /** false = nichts zu tun für diesen Arbeiter. */
    private boolean work(Worker w, int share, long deadline, int[] chunkLoads) {
        if (w.task == null || w.cursor >= w.task.cells().size()) {
            if (queue.isEmpty()) {
                w.task = null;
                return false;
            }
            // Reihenfolge des Plans bei geteilten Stellen (StagePrep.blocked/takeNext): Ein früherer Abschnitt, der
            // dieselbe Fläche berührt, muss erst fertig sein. Ist keiner der vordersten frei, wartet dieser Arbeiter –
            // true, damit der Bau nicht als fertig gilt.
            Task next = queue.take(runningBoxes(w));
            if (next == null) {
                w.task = null;
                return true;
            }
            // Gelände erst hier, beim Übernehmen: Die Höhen eines Abschnitts kosten nur so lange Speicher, wie er
            // gebaut wird – nicht für die ganze Etappe in der Warteschlange. Nur für ein HeightField ohne I/O: Der
            // Weltbau rechnet die Höhen im Planungs-Thread (StageSource.absolute()), weil das Höhenfeld der Erdwelt
            // fehlende Höhenkacheln synchron lädt – hier im Takt hielte das den Server an (Prüfbefund 2026-10-28).
            w.task = ground == null || next.absolute() ? next
                    : new Task(next.section(), TerrainFit.fit(next.section(), ground, baseX, baseZ), true, next.box(),
                            next.stage());
            w.cursor = 0;
            w.sinceMove = Integer.MAX_VALUE;
        }
        boolean absolute = w.task.absolute();
        List<Cell> cells = w.task.cells();
        int end = cells.size();
        // Gesetzte Blöcke kosten voll, gleiche nur ein Viertel (BuildBudget, gemessen 2026-10-29).
        BuildBudget left = new BuildBudget(share, samePerBlock);
        Cell last = null;
        int n = 0;
        for (; w.cursor < end && left.left(); w.cursor++) {
            if (++n % CLOCK_EVERY == 0 && System.nanoTime() > deadline) {
                break;
            }
            Cell c = cells.get(w.cursor);
            int x = baseX + c.x();
            int z = baseZ + c.z();
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                long k = ((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
                if (!held.contains(k) && chunkLoads[0] > 0) {
                    chunkLoads[0]--;  // gebraucht, aber noch nicht bestellt: vorziehen
                    toOrder.remove(k);
                    hold(x >> 4, z >> 4);
                }
                break;  // bestellt, der Server erzeugt ihn (siehe held und Lastregelung) – nie selbst erzwingen
            }
            BlockData data = data(c);
            processed++;
            left.touched();
            if (data == null) {
                continue;
            }
            int y = absolute ? c.y() : baseY + c.y();
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Block b = world.getBlockAt(x, y, z);
            BlockData before = b.getBlockData();
            if (before.equals(data)) {
                continue;  // schon so – Luft auf Luft kostet so fast nichts
            }
            // Der UndoSpool sichert nur BlockData (getAsString), nie den Inhalt. Eine überbaute volle Kiste käme bei
            // /bm rückgängig leer zurück. Ein Ort ist zu groß, um vorher alles zu prüfen und dann abzubrechen – also
            // diese Zelle auslassen. Der Namensvergleich davor hält getState() aus dem heißen Pfad. Wer das entfernt,
            // vernichtet beim Stadtbau Kisteninhalte, die kein Rückgängig zurückholt.
            if (ProtectedBlocks.hasContents(b, before)) {
                containersSpared++;
                continue;
            }
            if (data.getMaterial() == Material.AIR && before.getMaterial().isAir()) {
                continue;
            }
            if (spool != null && !spool.failed()) {
                try {
                    spool.record(x, y, z, before);
                } catch (IllegalStateException e) {
                    LOG.log(Level.WARNING, "stadt | rückgängig_aus | ort=" + place, e);
                    Texts.info(requester, "Rückgängig ist für diesen Bau aus: " + e.getMessage());
                }
            }
            b.setBlockData(data, false);
            placed++;
            left.placed();
            last = c;
        }
        Mannequin body = w.body();
        if (body != null && last != null && ++w.sinceMove >= 8) {
            // Alle 8 Ticks zum Abschnitt fliegen und dorthin schauen – sichtbar, ohne den Server zu belasten.
            w.sinceMove = 0;
            Location target = new Location(world, baseX + last.x() + 0.5, (absolute ? 0 : baseY) + last.y() + 0.5,
                    baseZ + last.z() + 0.5);
            Location stand = target.clone().add(2.5, 2, 2.5);
            if (!world.isChunkLoaded(stand.getBlockX() >> 4, stand.getBlockZ() >> 4)) {
                stand = target.clone().add(0, 2, 0);  // Nachbar-Chunk nicht erzeugt: Teleport dorthin lüde ihn sofort
            }
            body.teleport(BuilderNpc.facing(stand, target));
            body.swingMainHand();
            BlockData shown = data(last);
            BuilderNpc.hold(body, shown.getMaterial().isItem() ? shown.getMaterial() : null);
        }
        return true;
    }

    private BlockData data(Cell c) {
        return c.mat() == TerrainFit.PLINTH ? plinth : palette.get(c.mat());
    }

    private void finish(String why) {
        running = false;
        if (task != null) {
            task.cancel();
        }
        if (loading != null) {
            loading.cancel(false);
            loading = null;
        }
        stages.close();
        queue.clear();
        signs.close();
        held.releaseAll();
        for (Worker w : workers) {
            if (w.helper != null) {
                w.helper.remove();
            }
        }
        workers.clear();
        if (spool != null && !spool.failed()) {
            try {
                spool.finish();
            } catch (IllegalStateException e) {
                LOG.log(Level.WARNING, "stadt | rückgängig_aus | ort=" + place, e);
            }
        }
        npc.status("bereit");
        if (host != null) {
            host.end(hostJob, completed ? "fertig" : why.startsWith("Da ist etwas schiefgegangen") ? "fehler"
                    : "abgebrochen", placed);
        }
        // In jede Schlussmeldung (Fertig wie Stopp), sonst wundert sich der Spieler über die Lücken im Ort.
        Texts.info(requester, containersSpared > 0 ? why + " " + String.format("%,d", containersSpared)
                + " Behälter mit Inhalt habe ich stehen lassen." : why);
        LOG.info(() -> String.format("stadt | ende | ort=%s gesetzt=%d geprüft=%d etappen=%d/%d rückgängig=%d "
                + "behälter_geschont=%d längster_tick_ms=%d ticks_über_50ms=%d ticks_über_200ms=%d "
                + "längste_lücke_ms=%d lücken_über_100ms=%d lücken_über_1s=%d erholungsticks=%d chunk_rate=%.2f "
                + "blöcke_je_s=%d tickets=%d ticket_ms=%d längstes_ticket_ms=%d chunks_async=%d grund=%s",
                place, placed, processed, stagesDone, stages.count(), spool == null ? 0 : spool.count(),
                containersSpared, maxTickNanos / 1_000_000, ticksOver50, ticksOver200, maxGapNanos / 1_000_000,
                gapsOver100, gapsOver1000, load.recoveries(), load.rate(),
                started == null ? 0 : placed * 1000 / Math.max(1, Duration.between(started, Instant.now()).toMillis()),
                held.tickets(), held.ticketNanos() / 1_000_000, held.maxTicketNanos() / 1_000_000,
                held.asyncOrders(), why));
        onFinish.accept(this);
    }

    UndoRecord undoRecord() {
        UndoSpool usable = spool != null && !spool.failed() ? spool : null;
        if (usable == null && spool != null) {
            spool.delete();
        }
        return new UndoRecord(owner, ownerName, world, List.of(), Instant.now(), usable);
    }
}
