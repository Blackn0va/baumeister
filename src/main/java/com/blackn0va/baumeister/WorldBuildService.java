package com.blackn0va.baumeister;

import com.blackn0va.baumeister.WorldTiles.GeoBox;
import com.blackn0va.baumeister.WorldTiles.Tile;
import com.blackn0va.baumeister.earth.EarthProjection;
import com.blackn0va.baumeister.earth.EarthWorld;
import com.blackn0va.baumeister.earth.HeightField;
import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.OsmClient;
import com.blackn0va.baumeister.osm.OsmImport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Weltbau: OpenStreetMap auf das Gelände der Erdwelt setzen – Kachel für Kachel (WorldTiles, 1024 × 1024 Blöcke),
 * von Hand (/bm weltbau &lt;Ort&gt;) oder automatisch rund um die Spieler (/bm weltbau auto an).
 *
 * Ablauf je Kachel: Ecken über die Projektion der Erdwelt → lat/lon-Rechteck samt Rand → Karte aus dem Zwischenspeicher
 * (karten/, dieselben Dateien wie /bm stadt) oder gedrosselt vom Kartendienst → OsmImport mit der Projektion der
 * Erdwelt minus Kachelmitte → CityPlanner in 16 Etappen, Höhen aus dem Höhenfeld der Erdwelt gleich mit (TerrainFit,
 * im Weltbau-Thread) → CityJob. Vollständige Kacheln stehen in erde/weltbau.txt und werden nie wieder gebaut – ein
 * zweiter Bau würde überschreiben, was Spieler dort inzwischen verändert haben. Rückgängig nur über
 * /bm weltbau rückgängig (TileUndo), nicht über den allgemeinen Stapel.
 *
 * Schonung der öffentlichen Dienste (Nutzerwunsch 2026-10-28): höchstens eine Overpass-Abfrage gleichzeitig, dazwischen
 * mindestens weltbau.abstand-sekunden (Throttle); nie zwei Weltbau-Jobs; nichts, solange /bm stadt lädt oder baut.
 * Eine Kachel nach der anderen – erst wenn sie steht, wird die nächste geladen. Anders als CityStages fragt der
 * Weltbau bei „ausgelastet“ NICHT sofort den nächsten Dienst, sondern erst nach Ablauf der Drossel: Im Automatikbetrieb
 * sitzt kein Mensch davor, der auf die Kachel wartet.
 *
 * Threads: alles vom Hauptthread (Bukkit-Takt alle 20 Ticks); Lesen, Einlesen und Planen der Karte in einem eigenen
 * Thread, Antworten kommen per runTask zurück. Fehler: eine Kachel, die dreimal je Dienst scheitert oder sich nicht
 * planen lässt, wird für diese Sitzung aufgegeben (Meldung, Log). Ist die Merkliste unlesbar, baut der Weltbau gar
 * nicht (fail-closed: sonst würde er fertige, inzwischen bespielte Kacheln überbauen).
 */
final class WorldBuildService {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** So oft sieht die Automatik nach, wo die Spieler sind. */
    static final long SCAN_MILLIS = 10_000;
    /** Versuche je Kachel: so viele Runden über alle Overpass-Instanzen. */
    private static final int ROUNDS = 3;

    private enum Phase { WAIT_FETCH, FETCHING, PLANNING, READY, BUILDING }

    private record Order(Tile tile, CommandSender requester) {
    }

    private final BaumeisterPlugin plugin;
    private final BuilderNpc npc;
    private final Path doneFile;
    private final Path cacheDir;
    private final LongSupplier clock = () -> System.nanoTime() / 1_000_000L;
    private final Throttle throttle;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Baumeister-Weltbau");
        t.setDaemon(true);
        return t;
    });
    private final Set<Long> done = new LinkedHashSet<>();
    private final Set<Long> gaps = new HashSet<>();
    private final Set<Long> failed = new HashSet<>();
    private final Deque<Order> manual = new ArrayDeque<>();
    /** Rückgängig der Kacheln – eigener Speicher, nicht der Stapel von /bm rückgängig (siehe TileUndo). */
    private final TileUndo undo;
    /** Grund, warum der Weltbau gesperrt ist (Merkliste unlesbar), sonst null. */
    private String blocked;
    private List<Tile> nearby = List.of();
    private boolean auto;
    private long lastScan;
    private boolean scanned;
    private boolean saidNoEarth;
    /** Speicher knapp (HeapGuard) – einmal melden, nicht jede Sekunde. */
    private boolean saidTight;
    private BukkitTask timer;

    // Laufende Kachel – höchstens eine.
    private Tile current;
    /** current hat die Automatik gewählt (nicht /bm weltbau <Ort>) – nur solche Kacheln verwirft keepAutoTile. */
    private boolean currentAuto;
    /** Alle Kacheln um Spieler in der Erdwelt (vor dem Filtern fertiger), Schlüssel – aus dem letzten scan(). */
    private Set<Long> nearPlayers = Set.of();
    private CommandSender requester;
    private Phase phase;
    private GeoBox box;
    /** Abfragen der laufenden Kachel: eine, über der Datumsgrenze zwei (GeoBox.parts), samt Versuchen je Teil. */
    private final TileFetch fetch = new TileFetch();
    /** Zählt bei jedem Stopp hoch; Antworten eines älteren Laufs werden verworfen. */
    private int generation;
    private WorldTileSource source;
    private CityPlanner.Plan first;
    /** Geländehöhe der Kachelmitte aus prepare() – im Weltbau-Thread gerechnet, nicht im Takt. */
    private int centerY;
    private CityJob job;

    WorldBuildService(BaumeisterPlugin plugin, BuilderNpc npc) {
        this.plugin = plugin;
        this.npc = npc;
        Path data = plugin.getDataFolder().toPath();
        this.doneFile = data.resolve("erde").resolve("weltbau.txt");
        this.cacheDir = data.resolve("karten");
        this.throttle = new Throttle(clock, gapMillis());
        this.undo = new TileUndo(undoDepth());
        loadDone();
        auto = plugin.getConfig().getBoolean("weltbau.auto", false) && blocked == null;
    }

    void start() {
        timer = Bukkit.getScheduler().runTaskTimer(plugin, this::tickGuarded, 20L, 20L);
        LOG.info(() -> "weltbau | bereit | fertig=" + done.size() + " auto=" + auto + " abstand_s="
                + gapMillis() / 1000 + (blocked != null ? " gesperrt=" + blocked : ""));
    }

    boolean running() {
        return job != null && job.running();
    }

    /** Laufender Bau oder null – für den Fortschrittsbalken (ProgressBarTask). */
    CityJob activeJob() {
        return running() ? job : null;
    }

    /** Läuft gerade eine Abfrage beim Kartendienst? /bm stadt wartet dann (eine Abfrage zur Zeit). */
    boolean fetching() {
        return throttle.inFlight();
    }

    private long gapMillis() {
        return cfg("weltbau.abstand-sekunden", 20, 10, 3600) * 1000L;
    }

    /**
     * Wie viele Kacheln sich zurücksetzen lassen. Jede belegt unter rückgängig/ rund 2,8 MB je Million Blöcke
     * (gemessen 2026-10-28) – eine Innenstadtkachel also zig MB.
     */
    private int undoDepth() {
        return cfg("weltbau.rückgängig-tiefe", 3, 0, 20);
    }

    private int cfg(String key, int def, int min, int max) {
        return Math.max(min, Math.min(max, plugin.getConfig().getInt(key, def)));
    }

    // ------------------------------------------------------------------ Merkliste

    private void loadDone() {
        if (!Files.isRegularFile(doneFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(doneFile, StandardCharsets.UTF_8)) {
                Tile t = WorldTiles.parseLine(line);
                if (t != null) {
                    done.add(t.key());
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // fail-closed: Ohne Merkliste wüsste der Weltbau nicht, welche Kacheln schon stehen, und überbaute sie.
            blocked = doneFile + " unlesbar (" + e.getMessage() + ")";
            done.clear();
            LOG.log(Level.SEVERE, "weltbau | merkliste_unlesbar | datei=" + doneFile, e);
        }
    }

    /** Ganze Liste in eine Nachbardatei, dann umbenennen – ein Abbruch mittendrin hinterlässt nie eine halbe Liste. */
    private void saveDone() throws IOException {
        StringBuilder sb = new StringBuilder("# Weltbau: fertige Kacheln der Erdwelt (tx tz), 1 Kachel = 1024 × 1024 "
                + "Blöcke ab x = tx · 1024, z = tz · 1024. Zeile löschen = Kachel wird wieder gebaut.\n");
        for (long k : done) {
            sb.append(WorldTiles.formatLine(new Tile((int) (k >> 32), (int) k))).append('\n');
        }
        Files.createDirectories(doneFile.getParent());
        Path tmp = doneFile.resolveSibling(doneFile.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, doneFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ Takt

    /** Scheduler-Grenze: Eine Ausnahme beendet die laufende Kachel, nicht den Takt. */
    private void tickGuarded() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "weltbau | takt_fehler | kachel=" + current, e);
            giveUp("interner Fehler: " + e.getMessage());
        }
    }

    private void tick() {
        if (blocked != null) {
            return;
        }
        reportToHost();
        throttle.gap(gapMillis());  // nach /bm neuladen gilt der neue Abstand ab der nächsten Abfrage
        EarthWorld ew = plugin.earthWorld();
        long now = clock.getAsLong();
        if (auto && (!scanned || now - lastScan >= SCAN_MILLIS)) {
            scanned = true;
            lastScan = now;
            scan(ew);
        }
        if (current != null && currentAuto && phase != null
                && !keepAutoTile(phase == Phase.WAIT_FETCH || phase == Phase.READY, auto, scanned,
                        nearPlayers.contains(current.key()))) {
            dropCurrent(auto ? "kein_spieler_mehr_dort" : "automatik_aus");
        }
        if (current == null) {
            pickNext(ew);
        }
        if (current == null) {
            return;
        }
        if (phase == Phase.WAIT_FETCH || phase == Phase.READY) {
            // Keine neue Arbeit, solange zu viele hohe Chunks geladen sind (HeapGuard: gemessen OOM auf 3 GB). Laufendes
            // läuft weiter.
            if (HeapGuard.tight()) {
                if (!saidTight) {
                    saidTight = true;
                    LOG.warning(() -> "weltbau | speicher_knapp | " + HeapGuard.describe() + " kachel=" + current
                            + " – warte, bis Chunks entladen sind");
                }
                return;
            }
            saidTight = false;
        }
        switch (phase) {
            case WAIT_FETCH -> fetch(ew);
            case READY -> startJob(ew);
            case BUILDING -> prefetchNext(ew);
            default -> {
                // FETCHING/PLANNING warten auf ihre Antwort.
            }
        }
    }

    /** Kachel, deren Karte gerade vorausgeladen wird (höchstens eine), sonst null. */
    private Tile prefetching;

    /**
     * Während eine Kachel gebaut wird, die Karte der nächsten schon in den Zwischenspeicher legen. Vorher holte der
     * Weltbau sie erst nach dem Ende der Kachel – auf dev01 36–38 s je Kachel plus Drossel (gemessen 2026-10-29), in
     * denen nichts gebaut wurde; bei ausgelastetem Overpass Minuten. Gleiche Drossel wie der normale Abruf (eine
     * Abfrage zugleich, weltbau.abstand-sekunden dazwischen), nur eine Kachel voraus, nur in den Zwischenspeicher (kein
     * Einlesen, kein Speicher im Heap). Kacheln über der Datumsgrenze (zwei Abfragen) holt weiter der normale Weg.
     * Scheitert es, passiert nichts: fetch() fragt dann wie bisher selbst.
     */
    private void prefetchNext(EarthWorld ew) {
        if (prefetching != null || ew == null || cityBusy() || !throttle.ready()) {
            return;
        }
        Tile next = peekNext();
        if (next == null) {
            return;
        }
        GeoBox b = WorldTiles.box(ew.projection(), next, WorldTiles.MARGIN);
        if (b == null || b.crossesDateline()) {
            return;
        }
        Path file = CityStages.cacheFile(cacheDir, b.south(), b.west(), b.north(), b.east());
        if (CityStages.fresh(file)) {
            return;
        }
        OsmClient osm = plugin.city().osm();
        String mirrorName = osm.mirrorName(0);
        prefetching = next;
        LOG.info(() -> "weltbau | karte_voraus | kachel=" + next + " dienst=" + mirrorName);
        long t0 = System.nanoTime();
        CompletableFuture<String> answer;
        try {
            // startReleasing statt start: Niemand sonst führt diese Antwort zu end() (Vorfall 2026-10-31, Throttle).
            answer = throttle.startReleasing(() -> osm.fetchBox(b.south(), b.west(), b.north(), b.east(), 0),
                    this::onMain);
        } catch (RuntimeException e) {
            prefetching = null;
            LOG.log(Level.INFO, "weltbau | karte_voraus_fehler | kachel=" + next, e);
            return;
        }
        answer.whenComplete((json, err) -> {
            // Schreiben im Hintergrund (Platte), Zustand zurück im Hauptthread.
            if (err == null && json != null) {
                try {
                    CityStages.writeCache(file, json);
                    LOG.info(() -> "weltbau | karte_voraus_fertig | kachel=" + next + " bytes=" + json.length()
                            + " dauer_ms=" + (System.nanoTime() - t0) / 1_000_000);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "weltbau | karte_voraus_schreiben | kachel=" + next, e);
                }
            } else {
                LOG.info(() -> "weltbau | karte_voraus_fehlgeschlagen | kachel=" + next + " grund="
                        + (err == null ? "leer" : err.getMessage()) + " – wird beim Wählen normal geholt");
            }
            onMain(() -> prefetching = null);
        });
    }

    /** Die Kachel, die pickNext als Nächstes wählen würde – ohne etwas aus der Warteschlange zu nehmen. */
    private Tile peekNext() {
        for (Order o : manual) {
            if (!done.contains(o.tile().key()) && !o.tile().equals(current)) {
                return o.tile();
            }
        }
        if (auto) {
            for (Tile t : nearby) {
                if (!t.equals(current) && !done.contains(t.key()) && !failed.contains(t.key())
                        && !gaps.contains(t.key())) {
                    return t;
                }
            }
        }
        return null;
    }

    /** Zuletzt an den Host gemeldet (ms, clock); höchstens alle REPORT_MILLIS, HostReporter sendet nur Änderungen. */
    private long lastHostReport = Long.MIN_VALUE / 2;
    private static final long REPORT_MILLIS = 2_000;

    /**
     * Kachelübersicht für die Stadtansicht im Host (Wunsch 2026-10-29) – fertige,
     * gescheiterte, wartende und laufende Kacheln, Automatik und Spieler der Erdwelt. Billig im Takt: höchstens alle 2 s,
     * nur Mengen umkopieren; das JSON und der Vergleich mit der letzten Sendung stecken in HostReporter.world.
     */
    private void reportToHost() {
        HostReporter host = plugin.hostReporter();
        if (host == null) {
            return;
        }
        long now = clock.getAsLong();
        if (now - lastHostReport < REPORT_MILLIS) {
            return;
        }
        lastHostReport = now;
        World w = worldOf(plugin.earthWorld());
        if (w == null) {
            return;
        }
        List<int[]> doneTiles = new ArrayList<>(done.size());
        for (long k : done) {
            doneTiles.add(new int[] {(int) (k >> 32), (int) k});
        }
        List<int[]> failedTiles = new ArrayList<>(failed.size());
        for (long k : failed) {
            failedTiles.add(new int[] {(int) (k >> 32), (int) k});
        }
        Set<Long> seen = new HashSet<>();
        List<int[]> queued = new ArrayList<>();
        if (current != null) {
            seen.add(current.key());
        }
        for (Order o : manual) {
            if (seen.add(o.tile().key())) {
                queued.add(new int[] {o.tile().tx(), o.tile().tz()});
            }
        }
        for (Tile t : nearby) {
            if (seen.add(t.key()) && !done.contains(t.key())) {
                queued.add(new int[] {t.tx(), t.tz()});
            }
        }
        List<int[]> players = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            players.add(new int[] {p.getLocation().getBlockX(), p.getLocation().getBlockZ()});
        }
        String what = current == null || phase == null ? "wartet" : switch (phase) {
            case WAIT_FETCH, FETCHING -> "karte";
            case PLANNING -> "planen";
            case READY -> "bereit";
            case BUILDING -> "bauen";
        };
        host.world(w.getName(), WorldTiles.SIZE, doneTiles, failedTiles, queued,
                current == null ? null : new int[] {current.tx(), current.tz()}, what, auto, players);
    }

    private boolean cityBusy() {
        return plugin.city().running() || plugin.city().loading();
    }

    private static World worldOf(EarthWorld ew) {
        return ew == null ? null : ew.world();
    }

    /** Wo sind Spieler in der Erdwelt – welche Kacheln um sie fehlen noch (nächste zuerst)? */
    private void scan(EarthWorld ew) {
        World w = worldOf(ew);
        if (w == null) {
            nearby = List.of();
            if (!saidNoEarth) {
                saidNoEarth = true;
                LOG.info("weltbau | auto_wartet | grund=erdwelt_fehlt");
            }
            return;
        }
        saidNoEarth = false;
        List<double[]> players = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            players.add(new double[] {p.getLocation().getX(), p.getLocation().getZ()});
        }
        if (players.isEmpty()) {
            nearby = List.of();
            nearPlayers = Set.of();
            return;
        }
        int radius = cfg("weltbau.radius-kacheln", 1, 0, 4);
        List<Tile> around = WorldTiles.near(players, radius);
        Set<Long> keys = new HashSet<>();
        for (Tile t : around) {
            keys.add(t.key());
        }
        nearPlayers = keys;
        nearby = WorldTiles.todo(around, ew.projection(), skipSet(), gaps);
    }

    private Set<Long> skipSet() {
        Set<Long> skip = new HashSet<>(done);
        skip.addAll(failed);
        if (current != null) {
            skip.add(current.key());
        }
        return skip;
    }

    private void pickNext(EarthWorld ew) {
        World w = worldOf(ew);
        if (w == null) {
            if (!manual.isEmpty()) {
                for (Order o : manual) {
                    Texts.info(o.requester(), "Weltbau: Die Erdwelt ist nicht geladen – Kachel " + o.tile()
                            + " fällt weg.");
                }
                manual.clear();
            }
            return;
        }
        if (cityBusy()) {
            return;  // nicht während /bm stadt (eine Abfrage zur Zeit, ein Stadtbau zur Zeit)
        }
        Order next = null;
        boolean byAuto = false;
        while (!manual.isEmpty() && next == null) {
            Order o = manual.pollFirst();
            if (!done.contains(o.tile().key())) {
                next = o;
            }
        }
        if (next == null && auto) {
            for (Tile t : nearby) {
                if (!done.contains(t.key()) && !failed.contains(t.key()) && !gaps.contains(t.key())) {
                    next = new Order(t, Bukkit.getConsoleSender());
                    byAuto = true;
                    break;
                }
            }
        }
        if (next == null) {
            return;
        }
        GeoBox b = WorldTiles.box(ew.projection(), next.tile(), WorldTiles.MARGIN);
        if (b == null) {
            gaps.add(next.tile().key());
            Texts.info(next.requester(), "Weltbau: Kachel " + next.tile() + " liegt in einer Lücke der Erdwelt – "
                    + "übersprungen.");
            LOG.info("weltbau | kachel_in_lücke | kachel=" + next.tile());
            return;
        }
        current = next.tile();
        currentAuto = byAuto;
        requester = next.requester();
        box = b;
        fetch.start(b);
        phase = Phase.WAIT_FETCH;
        LOG.info(() -> String.format(Locale.ROOT, "weltbau | kachel_gewählt | kachel=%s süd=%.5f west=%.5f nord=%.5f "
                + "ost=%.5f", current, box.south(), box.west(), box.north(), box.east()));
    }

    // ------------------------------------------------------------------ Karte

    private void fetch(EarthWorld ew) {
        if (worldOf(ew) == null) {
            giveUp("Die Erdwelt ist nicht mehr geladen.");
            return;
        }
        Path file = CityStages.cacheFile(cacheDir, box.south(), box.west(), box.north(), box.east());
        int gen = generation;
        Tile tile = current;
        EarthProjection projection = ew.projection();
        HeightField ground = ew.ground();
        if (CityStages.fresh(file)) {
            phase = Phase.PLANNING;
            LOG.info(() -> "weltbau | karte_aus_zwischenspeicher | kachel=" + tile + " datei=" + file.getFileName());
            CompletableFuture.supplyAsync(() -> {
                try {
                    return CityStages.readCache(file);
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            }, worker).thenApply(json -> prepare(json, tile, projection, ground))
                    .whenComplete((r, err) -> onMain(() -> onPrepared(gen, r, err)));
            return;
        }
        if (cityBusy() || !throttle.ready()) {
            return;  // Drossel: der Takt fragt in einer Sekunde wieder
        }
        OsmClient osm = plugin.city().osm();
        int mirror = fetch.attempts() % osm.mirrors();
        String mirrorName = osm.mirrorName(mirror);  // vor der Drossel: wirft es, ist noch nichts belegt
        GeoBox b = fetch.query();
        String partText = fetch.partText();
        LOG.info(() -> "weltbau | karte_anfrage | kachel=" + tile + " dienst=" + mirrorName + " versuch="
                + (fetch.attempts() + 1) + partText);
        long t0 = System.nanoTime();
        // throttle.start statt begin() + fetchBox(): Wirft der Anstoß synchron, gibt start() die Drossel frei, und
        // tickGuarded gibt die Kachel auf – sonst bliebe die Drossel für immer belegt (siehe Throttle.start).
        CompletableFuture<String> answer = throttle.start(() -> osm.fetchBox(b.south(), b.west(), b.north(),
                b.east(), mirror));
        phase = Phase.FETCHING;
        answer.whenComplete((json, err) -> onMain(() -> onFetched(gen, tile, file, projection, ground, json, err,
                mirrorName, (System.nanoTime() - t0) / 1_000_000)));
    }

    private void onFetched(int gen, Tile tile, Path file, EarthProjection projection, HeightField ground,
            String answer, Throwable err, String mirror, long ms) {
        throttle.end();  // immer – auch für einen gestoppten Lauf hat der Dienst gearbeitet
        if (gen != generation) {
            return;
        }
        if (err != null) {
            Throwable cause = unwrap(err);
            boolean retry = cause instanceof OsmClient.BusyException || cause instanceof IOException;
            int max = ROUNDS * plugin.city().osm().mirrors();
            boolean again = fetch.failed(retry, max);
            LOG.log(Level.WARNING, "weltbau | karte_fehlgeschlagen | kachel=" + tile + " dienst=" + mirror + " versuch="
                    + fetch.attempts() + "/" + max + " dauer_ms=" + ms + " grund=" + cause.getMessage());
            if (again) {
                phase = Phase.WAIT_FETCH;  // nächster Dienst, frühestens nach Ablauf der Drossel
                return;
            }
            giveUp("Kartendienst: " + cause.getMessage());
            return;
        }
        LOG.info(() -> "weltbau | karte_geladen | kachel=" + tile + " bytes=" + answer.length() + " dauer_ms=" + ms
                + " dienst=" + mirror + fetch.partText());
        if (!fetch.answered(answer)) {
            // Über der Datumsgrenze: der zweite Teil kommt wie jede Abfrage erst nach Ablauf der Drossel.
            phase = Phase.WAIT_FETCH;
            return;
        }
        List<String> answers = fetch.answers();
        phase = Phase.PLANNING;
        CompletableFuture.supplyAsync(() -> {
            // Zusammenführen im Weltbau-Thread: eine kaputte Antwort wirft hier und landet in onPrepared (aufgeben),
            // nicht im Bukkit-Takt.
            String json = TileFetch.merged(answers);
            try {
                CityStages.writeCache(file, json);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "weltbau | zwischenspeicher_schreiben_fehlgeschlagen | datei="
                        + file.getFileName(), e);
            }
            return prepare(json, tile, projection, ground);
        }, worker).whenComplete((r, e) -> onMain(() -> onPrepared(gen, r, e)));
    }

    /** centerY: Geländeoberkante der Kachelmitte (Standplatz des Baumeisters), MIN_VALUE ohne Gelände. */
    private record Prepared(WorldTileSource source, CityPlanner.Plan first, int centerY) {
    }

    /**
     * Im Weltbau-Thread: Karte einlesen (Projektion der Erdwelt), erste Etappe samt Geländehöhen planen, Höhe der
     * Kachelmitte bestimmen. Alles, was das Höhenfeld fragt, gehört hierher und nicht in startJob/CityJob: Das
     * Höhenfeld der Erdwelt lädt fehlende Höhenkacheln im aufrufenden Thread (Platte, PNG, bei Cache-Fehlschlag HTTP
     * mit 60 s Zeitlimit) – auf dem Hauptthread hinge der Server (Prüfbefund 2026-10-28).
     */
    private Prepared prepare(String json, Tile tile, EarthProjection projection, HeightField ground) {
        double[] center = projection.toGeo(tile.centerX(), tile.centerZ());
        if (center == null) {
            throw new IllegalStateException("Kachelmitte liegt in einer Netzlücke");
        }
        String name = "Erdkachel " + tile;
        int radius = (int) Math.ceil(WorldTiles.SIZE / 2.0 * Math.sqrt(2)) + WorldTiles.MARGIN;
        CityModel model = OsmImport.parse(json, name, center[0], center[1], radius,
                WorldTiles.projector(projection, tile));
        long maxCells = plugin.getConfig().getLong("stadt.max-blöcke-je-etappe", 12_000_000L);
        WorldTileSource src = new WorldTileSource(name, model, worker, maxCells, ground, tile.centerX(),
                tile.centerZ());
        CityPlanner.Plan plan = src.planNow(0);
        return new Prepared(src, plan, ground.surfaceY(tile.centerX(), tile.centerZ()));
    }

    private void onPrepared(int gen, Prepared r, Throwable err) {
        if (gen != generation) {
            if (r != null) {
                r.source().close();
            }
            return;
        }
        if (err != null) {
            Throwable cause = unwrap(err);
            LOG.log(Level.WARNING, "weltbau | planen_fehlgeschlagen | kachel=" + current, cause);
            giveUp("Karte nicht planbar: " + cause.getMessage());
            return;
        }
        source = r.source();
        first = r.first();
        centerY = r.centerY();
        phase = Phase.READY;
        startJob(plugin.earthWorld());
    }

    // ------------------------------------------------------------------ Bauen

    private void startJob(EarthWorld ew) {
        // buildingInWorld statt busy: Der Weltbau braucht die KI nie und wartet nicht mehr auf eine LLM-Planung.
        if (cityBusy() || plugin.service().buildingInWorld()) {
            return;  // geplant bleibt geplant – gebaut wird, sobald der NPC frei ist
        }
        World world = worldOf(ew);
        if (world == null) {
            giveUp("Die Erdwelt ist nicht mehr geladen.");
            return;
        }
        Tile tile = current;
        // Höhe aus prepare() – vorher fragte startJob hier ew.ground() im Takt (Prüfbefund 2026-10-28, siehe prepare).
        Location base = new Location(world, tile.centerX(), centerY == Integer.MIN_VALUE ? world.getSeaLevel()
                : centerY, tile.centerZ());
        npc.ensureNear(base.clone().add(0.5, 3, 0.5));
        int helpers = cfg("weltbau.helfer", 2, 0, 16);
        int budget = cfg("stadt.blöcke-pro-tick", 2000, 50, 50_000);
        int chunks = cfg("stadt.neue-chunks-pro-tick", 8, 1, 64);
        int ms = cfg("stadt.ms-pro-tick", 25, 5, 45);
        int lagMs = cfg("stadt.lag-ms", 150, 60, 2000);
        UndoSpool spool = plugin.getConfig().getBoolean("weltbau.rückgängig", true)
                ? new UndoSpool(plugin.getDataFolder().toPath().resolve("rückgängig")
                        .resolve(String.valueOf(System.currentTimeMillis())))
                : null;
        UUID owner = requester instanceof Player p ? p.getUniqueId() : null;
        WorldTileSource src = source;
        source = null;
        CityPlanner.Plan plan = first;
        first = null;
        phase = Phase.BUILDING;
        // Ohne HeightField: Die Zellen der Quelle tragen schon absolute Höhen (WorldTileSource.absolute()) – CityJob
        // rechnet im Takt keine Geländehöhe mehr.
        job = new CityJob(plugin, npc, requester, owner, requester.getName(), base, src, plan, budget, helpers, chunks,
                ms, lagMs, spool, null, j -> onJobFinished(j, tile));
        Texts.info(requester, "Weltbau: Kachel " + tile + " (x " + tile.x0() + "…" + (tile.x0() + WorldTiles.SIZE - 1)
                + ", z " + tile.z0() + "…" + (tile.z0() + WorldTiles.SIZE - 1)
                + ") – Karte © OpenStreetMap-Mitwirkende.");
        job.start();
    }

    private void onJobFinished(CityJob j, Tile tile) {
        // Eigener Speicher statt plugin.service().pushUndo – siehe TileUndo (Prüfbefund 2026-10-28).
        undo.depth(undoDepth());
        undo.add(tile, j.undoRecord());
        if (!j.completed()) {
            // Gestoppt, abgebrochen oder mit übersprungenen Etappen: nicht als fertig merken – und für diese Sitzung
            // auch nicht gleich wieder anfassen. Vorher stand die Kachel weder in done noch in failed, und die
            // Automatik hätte sie im nächsten Takt erneut gewählt: bei einer Etappe, die immer scheitert („zu groß“),
            // ohne Ende. /bm weltbau <Ort> und „auto an“ geben sie wieder frei.
            failed.add(tile.key());
            LOG.info(() -> "weltbau | kachel_unvollständig | kachel=" + tile + " etappen_übersprungen="
                    + j.stagesFailed());
            if (j.stagesFailed() > 0) {
                Texts.info(j.requester(), "Weltbau: Kachel " + tile + " hat Lücken und gilt nicht als fertig. Neuer "
                        + "Versuch: /bm weltbau <Ort> oder Automatik neu an.");
            }
        }
        if (j.completed()) {
            done.add(tile.key());
            try {
                saveDone();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "weltbau | merkliste_schreiben_fehlgeschlagen | datei=" + doneFile, e);
                Texts.info(j.requester(), "Weltbau: Kachel " + tile + " steht, ließ sich aber nicht in " + doneFile
                        + " eintragen – nach einem Neustart würde sie erneut gebaut.");
            }
            LOG.info(() -> "weltbau | kachel_fertig | kachel=" + tile + " fertig_gesamt=" + done.size());
        }
        if (job == j) {
            job = null;
            current = null;
            phase = null;
        }
    }

    /** Laufende Kachel für diese Sitzung aufgeben (nicht als fertig merken). */
    private void giveUp(String why) {
        if (current == null) {
            return;
        }
        Tile tile = current;
        failed.add(tile.key());
        if (source != null) {
            source.close();
            source = null;
        }
        first = null;
        CityJob running = job;
        job = null;
        current = null;
        phase = null;
        if (running != null && running.running()) {
            running.cancel("Weltbau abgebrochen: " + why);
        }
        LOG.warning("weltbau | kachel_aufgegeben | kachel=" + tile + " grund=" + why);
        if (requester != null) {
            Texts.info(requester, "Weltbau: Kachel " + tile + " aufgegeben – " + why);
        }
    }

    /**
     * Darf eine von der Automatik gewählte Kachel bleiben? Vorher blieb sie, bis sie gebaut war – egal, ob die Automatik
     * inzwischen aus war oder alle Spieler weitergereist sind (Wunsch 2026-10-31; die Grand-Canyon-Kachel stand noch
     * an). Jetzt fällt sie weg, solange nichts in Arbeit ist
     * (vor dem Kartenabruf oder geplant, aber noch nicht gebaut): bei Automatik aus oder wenn nach dem letzten Blick
     * kein Spieler mehr in ihrer Nähe ist. Ein laufender Abruf, eine laufende Planung und ein laufender Bau bleiben –
     * die Arbeit beim Kartendienst ist dann schon getan. Verworfen heißt nicht gescheitert: Kommt wieder ein Spieler
     * hin, wird sie gebaut.
     */
    static boolean keepAutoTile(boolean idle, boolean autoOn, boolean scanned, boolean playersNear) {
        if (!idle) {
            return true;
        }
        return autoOn && (!scanned || playersNear);
    }

    /** Laufende Kachel ohne Spur verwerfen (nicht in failed): Antworten älterer Läufe verwirft generation. */
    private void dropCurrent(String why) {
        generation++;
        Tile tile = current;
        if (source != null) {
            source.close();
            source = null;
        }
        first = null;
        current = null;
        currentAuto = false;
        phase = null;
        LOG.info(() -> "weltbau | kachel_verworfen | kachel=" + tile + " grund=" + why);
    }

    private void onMain(Runnable r) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    private static Throwable unwrap(Throwable err) {
        Throwable cause = err;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // ------------------------------------------------------------------ Befehle

    /** /bm weltbau &lt;Ort|lat,lon&gt; [radius] · auto an|aus · status · stop – Rechte prüft der Aufrufer. */
    void command(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            Texts.info(sender, "/" + label + " weltbau <Ort|lat,lon> [radius m] · weltbau auto an|aus · "
                    + "weltbau status · weltbau stop · weltbau rückgängig [tx tz] – OpenStreetMap auf das Gelände der "
                    + "Erdwelt");
            return;
        }
        String verb = args[1].toLowerCase(Locale.ROOT);
        switch (verb) {
            case "status" -> status(sender);
            case "stop" -> stop(sender);
            // umlaut-ok: Alias ohne Umlaut-Tastatur wie bei /bm rückgängig
            case "rückgängig", "rueckgaengig", "undo" -> undoTile(sender, label, args); // umlaut-ok
            case "auto" -> {
                String v = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
                switch (v) {
                    case "an", "ein", "on" -> setAuto(sender, true);
                    case "aus", "off" -> setAuto(sender, false);
                    default -> Texts.info(sender, "Automatik ist " + (auto ? "an" : "aus") + ". Ändern: /" + label
                            + " weltbau auto an|aus");
                }
            }
            default -> {
                int end = args.length;
                Integer radius = null;
                if (args.length > 2 && args[end - 1].matches("\\d{2,5}")) {
                    radius = Integer.parseInt(args[end - 1]);
                    end--;
                }
                enqueuePlace(sender, String.join(" ", Arrays.copyOfRange(args, 1, end)), radius);
            }
        }
    }

    private boolean ready(CommandSender sender) {
        if (blocked != null) {
            Texts.info(sender, "Der Weltbau ist gesperrt: " + blocked + ". Datei prüfen, dann Server neu starten.");
            return false;
        }
        if (worldOf(plugin.earthWorld()) == null) {
            Texts.info(sender, "Die Erdwelt fehlt noch – ohne sie gibt es kein Gelände, auf das der Weltbau bauen "
                    + "könnte. Erst die Erdwelt einrichten und den Server neu starten.");
            return false;
        }
        return true;
    }

    private void setAuto(CommandSender sender, boolean on) {
        if (on && !ready(sender)) {
            return;
        }
        auto = on;
        scanned = false;
        if (on) {
            failed.clear();  // neuer Anlauf: aufgegebene Kacheln dürfen wieder
        }
        LOG.info(() -> "weltbau | auto | an=" + on + " von=" + sender.getName());
        Texts.info(sender, on ? "Weltbau-Automatik an: alle " + SCAN_MILLIS / 1000 + " s sehe ich nach, wo Spieler in "
                + "der Erdwelt sind, und baue die Kacheln um sie (" + side() + "), nächste zuerst. Aus: /bm weltbau "
                + "auto aus" : "Weltbau-Automatik aus. Eine Kachel, an der ich schon baue, mache ich noch fertig (/bm "
                + "weltbau stop bricht sie ab); eine erst gewählte lasse ich fallen.");
    }

    private String side() {
        int n = 2 * cfg("weltbau.radius-kacheln", 1, 0, 4) + 1;
        return n + " × " + n + " Kacheln à " + WorldTiles.SIZE + " m";
    }

    private void enqueuePlace(CommandSender sender, String query, Integer radiusArg) {
        if (!ready(sender)) {
            return;
        }
        int maxRadius = cfg("weltbau.radius-max", 5000, 0, 20_000);
        int fallback = cfg("weltbau.radius-standard", 500, 0, 20_000);
        Texts.info(sender, "Suche „" + query + "“ …");
        plugin.city().osm().resolve(query).whenComplete((place, err) -> onMain(() -> {
            if (err != null) {
                Throwable cause = unwrap(err);
                LOG.log(Level.WARNING, "weltbau | ort_nicht_gefunden | ort=" + query, cause);
                Texts.info(sender, "Das ging nicht: " + cause.getMessage());
                return;
            }
            EarthWorld ew = plugin.earthWorld();
            if (worldOf(ew) == null) {
                Texts.info(sender, "Die Erdwelt ist nicht mehr geladen.");
                return;
            }
            double[] xz = ew.projection().toWorld(place.lat(), place.lon());
            if (xz == null || xz.length < 2 || !Double.isFinite(xz[0]) || !Double.isFinite(xz[1])) {
                Texts.info(sender, place.name() + " liegt nicht auf der Erdwelt.");
                return;
            }
            // Ohne Angabe der ganze Ort laut Nominatim (wie /bm stadt), sonst die Vorgabe.
            int radius = Math.max(0, Math.min(maxRadius, radiusArg != null ? radiusArg
                    : place.extent() > 0 ? place.extent() : fallback));
            List<Tile> around = WorldTiles.around(xz[0], xz[1], radius);
            Set<Long> skip = new HashSet<>(done);
            if (current != null) {
                skip.add(current.key());
            }
            for (Order o : manual) {
                skip.add(o.tile().key());
            }
            List<Tile> todo = WorldTiles.todo(around, ew.projection(), skip, gaps);
            for (Tile t : todo) {
                failed.remove(t.key());  // ausdrücklich verlangt: neuer Versuch
                manual.addLast(new Order(t, sender));
            }
            long inGap = around.stream().filter(t -> gaps.contains(t.key())).count();
            long already = around.stream().filter(t -> done.contains(t.key())).count();
            LOG.info(() -> String.format(Locale.ROOT, "weltbau | ort_eingereiht | ort=%s x=%.0f z=%.0f radius=%d "
                    + "kacheln=%d neu=%d fertig=%d lücke=%d", query, xz[0], xz[1], radius, around.size(), todo.size(),
                    already, inGap));
            Texts.info(sender, String.format(Locale.GERMAN, "%s (x %.0f, z %.0f), Umkreis %d m: %d Kachel(n) eingereiht"
                    + "%s%s. Abstand zwischen zwei Kartenabfragen: %d s. Stand: /bm weltbau status", place.name(),
                    xz[0], xz[1], radius, todo.size(), already > 0 ? ", " + already + " schon fertig" : "",
                    inGap > 0 ? ", " + inGap + " in einer Netzlücke" : "", gapMillis() / 1000));
        }));
    }

    private void status(CommandSender sender) {
        if (blocked != null) {
            Texts.info(sender, "Weltbau gesperrt: " + blocked);
            return;
        }
        Texts.info(sender, "Weltbau: Automatik " + (auto ? "an (" + side() + ")" : "aus") + ", " + done.size()
                + " Kachel(n) fertig, wartend " + manual.size() + " von Hand + " + (auto ? nearby.size() : 0)
                + " in Spielernähe" + (failed.isEmpty() ? "" : ", " + failed.size() + " aufgegeben")
                + (gaps.isEmpty() ? "" : ", " + gaps.size() + " in Netzlücken") + ".");
        if (worldOf(plugin.earthWorld()) == null) {
            Texts.info(sender, "Die Erdwelt fehlt – der Weltbau wartet.");
        }
        if (current == null) {
            Texts.info(sender, "Gerade keine Kachel" + (cityBusy() ? " – /bm stadt läuft, der Weltbau wartet." : "."));
            return;
        }
        String what = switch (phase) {
            case WAIT_FETCH -> throttle.waitMillis() > 0 ? "wartet auf den Kartendienst (noch "
                    + (throttle.waitMillis() + 999) / 1000 + " s, Versuch " + (fetch.attempts() + 1) + ")"
                    : "lädt gleich die Karte";
            case FETCHING -> "lädt die Karte (OpenStreetMap)";
            case PLANNING -> "plant";
            case READY -> "geplant, wartet auf den Baumeister";
            case BUILDING -> job != null ? job.progress() : "baut";
        };
        Texts.info(sender, "Kachel " + current + ": " + what);
    }

    /**
     * /bm weltbau rückgängig [tx tz]: zuletzt gebaute bzw. die genannte Kachel zurücksetzen und aus der Merkliste
     * nehmen. Vorher blieb eine über /bm rückgängig entfernte Kachel in weltbau.txt als fertig stehen und wurde nie
     * wieder gebaut. Für diese Sitzung bleibt sie gesperrt (failed), sonst baute die Automatik sie sofort neu, solange
     * ein Spieler daneben steht; /bm weltbau &lt;Ort&gt; oder „auto an“ geben sie frei.
     */
    private void undoTile(CommandSender sender, String label, String[] args) {
        Tile wanted = null;
        if (args.length >= 4) {
            try {
                wanted = new Tile(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            } catch (NumberFormatException e) {
                Texts.info(sender, "Kachel als zwei Zahlen angeben: /" + label + " weltbau rückgängig <tx> <tz>");
                return;
            }
        } else if (args.length == 3) {
            Texts.info(sender, "Kachel als zwei Zahlen angeben: /" + label + " weltbau rückgängig <tx> <tz>");
            return;
        }
        Tile target = wanted != null ? wanted : undo.latest();
        if (target == null) {
            Texts.info(sender, "Ich habe keine Weltbau-Kachel, die ich zurücksetzen könnte.");
            return;
        }
        if (current != null) {
            // Dieselbe Kachel: ließe ein Gemisch aus beidem stehen. Eine andere: Rückgängig und Weltbau liefen
            // nebeneinander und teilten sich die Plugin-Chunk-Tickets (je Plugin und Chunk nur eines) – das Rückgängig
            // gäbe beim Ende die Tickets der laufenden Kachel frei. Vorher wurde nur dieselbe Kachel abgewiesen.
            Texts.info(sender, "Weltbau arbeitet gerade an Kachel " + current + " – erst /" + label
                    + " weltbau stop oder warten.");
            return;
        }
        TileUndo.Entry e = undo.take(target);
        if (e == null) {
            Texts.info(sender, "Für Kachel " + target + " habe ich nichts zum Zurücksetzen.");
            return;
        }
        Tile tile = e.tile();
        failed.add(tile.key());
        if (done.remove(tile.key())) {
            try {
                saveDone();
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "weltbau | merkliste_schreiben_fehlgeschlagen | datei=" + doneFile, ex);
                Texts.info(sender, "Kachel " + tile + " ließ sich nicht aus " + doneFile + " austragen – nach einem "
                        + "Neustart gilt sie wieder als fertig.");
            }
        }
        LOG.info(() -> "weltbau | rückgängig | kachel=" + tile + " von=" + sender.getName() + " blöcke="
                + e.record().size());
        Texts.info(sender, "Weltbau: Kachel " + tile + " wird zurückgesetzt.");
        plugin.service().restore(sender, e.record());
    }

    private void stop(CommandSender sender) {
        generation++;
        boolean wasAuto = auto;
        auto = false;
        int queued = manual.size();
        manual.clear();
        Tile tile = current;
        CityJob running = job;
        if (source != null) {
            source.close();
            source = null;
        }
        first = null;
        job = null;
        current = null;
        phase = null;
        if (running != null && running.running()) {
            running.cancel("Weltbau gestoppt. Was steht, bleibt stehen – /bm weltbau rückgängig räumt die Kachel "
                    + "weg.");
        }
        LOG.info(() -> "weltbau | stop | von=" + sender.getName() + " kachel=" + tile + " wartend=" + queued);
        Texts.info(sender, tile == null && queued == 0 && !wasAuto ? "Der Weltbau tut gerade nichts."
                : "Weltbau gestoppt" + (tile != null ? " (Kachel " + tile + " nicht fertig)" : "") + ", "
                        + queued + " wartende Kachel(n) verworfen" + (wasAuto ? ", Automatik aus" : "") + ".");
    }

    void shutdown() {
        generation++;
        if (timer != null) {
            timer.cancel();
        }
        CityJob running = job;
        job = null;
        current = null;
        if (running != null && running.running()) {
            running.cancel("Server fährt herunter – Weltbau abgebrochen.");
        }
        if (source != null) {
            source.close();
            source = null;
        }
        worker.shutdownNow();
    }
}
