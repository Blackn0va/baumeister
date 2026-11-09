package com.blackn0va.baumeister;

import com.blackn0va.baumeister.osm.CityModel;
import com.blackn0va.baumeister.osm.CityPlanner;
import com.blackn0va.baumeister.osm.OsmClient;
import com.blackn0va.baumeister.osm.OsmImport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Ein Ort in Etappen: Kartenkacheln (1280 m = 5 × 5 Etappen) holen, daraus Etappen (256 m) planen – eine nach der
 * anderen, während die vorige gebaut wird.
 *
 * Vorher (bis 2026-10-28): eine Overpass-Abfrage für den ganzen Umkreis und ein Plan mit allen Blöcken im Speicher,
 * gedeckelt bei 6 Mio. Blöcken. Ein Dorf ging, eine Großstadt nicht (München: zig Millionen Blöcke, Antworten von
 * hunderten MB). Jetzt hält der Speicher höchstens zwei Kartenkacheln und zwei Etappen; die Kacheln liegen 30 Tage auf
 * der Platte (karten/), damit ein zweiter Bau den öffentlichen Dienst nicht erneut belastet.
 *
 * Kacheln sind genau 5 × 5 Etappen, symmetrisch um die Mitte (Etappe 0 = −128..128, Kachel 0 = −640..640). Erste
 * Fassung: Kacheln von 1024 m mit Etappen von 256 m – die Grenzen lagen versetzt, eine Etappe am Kachelrand ragte
 * 128 m über ihre Kachel hinaus, der Rand der Abfrage deckte nur 64 m; dort fehlten Gebäude (beim Durchdenken des
 * Live-Tests gefunden, Test stageLiesInsideItsMapTile).
 *
 * Reihenfolge: Kachel für Kachel von der Mitte nach außen, darin Etappe für Etappe von der Mitte nach außen – so wird
 * jede Kachel nur einmal geladen. Jede Kachel wird mit 64 m Rand abgefragt: Gebäude und Straßen an der Etappengrenze
 * sind so in beiden Nachbaretappen bekannt (CityPlanner ordnet sie eindeutig zu).
 *
 * Threads: plan() vom Hauptthread; Abfragen asynchron (HttpClient), Einlesen und Planen nacheinander in einem eigenen
 * Thread. Fehler: als fehlgeschlagene CompletableFuture; „ausgelastet“ (429/504) wird bis zu dreimal nach 30 s
 * wiederholt.
 */
final class CityStages implements StageSource {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final int STAGE = 256;
    static final int PER_TILE = 5;
    static final int TILE = STAGE * PER_TILE;
    static final int MARGIN = 64;
    private static final double M_PER_DEG_LAT = 110_540.0;
    private static final double M_PER_DEG_LON = 111_320.0;
    private static final Duration CACHE_MAX_AGE = Duration.ofDays(30);
    /** Runden über alle Overpass-Instanzen; zwischen zwei Runden 30 s Pause. */
    private static final int BUSY_ROUNDS = 3;

    /** Etappe: Mittelpunkt, Ausschnitt und zugehörige Kartenkachel. */
    record Stage(int cx, int cz, CityPlanner.Clip clip, int tileX, int tileZ) {
    }

    private final OsmClient osm;
    private final Path cacheDir;
    private final String place;
    private final double lat;
    private final double lon;
    private final int radius;
    private final long maxCells;
    private final List<Stage> stages;
    private final CityPlanner planner = new CityPlanner();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Baumeister-Stadtplanung");
        t.setDaemon(true);
        return t;
    });
    /** Zuletzt benutzte Kacheln, höchstens zwei (aktuelle + nächste). */
    private final Map<Long, CompletableFuture<CityModel>> models = new LinkedHashMap<>(4, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<CityModel>> eldest) {
            return size() > 2;
        }
    };

    CityStages(OsmClient osm, Path cacheDir, String place, double lat, double lon, int radius, long maxCells) {
        this.osm = osm;
        this.cacheDir = cacheDir;
        this.place = place;
        this.lat = lat;
        this.lon = lon;
        this.radius = radius;
        this.maxCells = maxCells;
        this.stages = layout(radius);
    }

    @Override
    public String place() {
        return place;
    }

    int radius() {
        return radius;
    }

    double lat() {
        return lat;
    }

    double lon() {
        return lon;
    }

    long maxCells() {
        return maxCells;
    }

    @Override
    public int count() {
        return stages.size();
    }

    int tiles() {
        return (int) stages.stream().map(s -> key(s.tileX(), s.tileZ())).distinct().count();
    }

    /** Alle Etappen, die den Kreis berühren, in Bau-Reihenfolge (siehe Klassenkommentar). Rein, testbar. */
    static List<Stage> layout(int radius) {
        List<Stage> out = new ArrayList<>();
        int n = Math.floorDiv(radius + STAGE / 2, STAGE);
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                int cx = i * STAGE;
                int cz = j * STAGE;
                int x0 = cx - STAGE / 2;
                int z0 = cz - STAGE / 2;
                // Nächster Punkt des Quadrats zur Mitte – liegt er außerhalb des Kreises, bleibt die Etappe leer.
                long nx = Math.max(x0, Math.min(0, x0 + STAGE - 1));
                long nz = Math.max(z0, Math.min(0, z0 + STAGE - 1));
                if (nx * nx + nz * nz > (long) radius * radius) {
                    continue;
                }
                int tx = Math.floorDiv(i + PER_TILE / 2, PER_TILE);
                int tz = Math.floorDiv(j + PER_TILE / 2, PER_TILE);
                out.add(new Stage(cx, cz, new CityPlanner.Clip(x0, z0, x0 + STAGE, z0 + STAGE, radius), tx, tz));
            }
        }
        out.sort(Comparator.<Stage>comparingDouble(s -> Math.hypot(s.tileX(), s.tileZ()))
                .thenComparingInt(s -> s.tileX()).thenComparingInt(s -> s.tileZ())
                .thenComparingDouble(s -> Math.hypot(s.cx(), s.cz())));
        return List.copyOf(out);
    }

    /** Etappe k planen (lädt die Kachel, falls nötig – meist liegt sie dank downloadAll schon auf der Platte). */
    @Override
    public CompletableFuture<CityPlanner.Plan> plan(int k) {
        Stage s = stages.get(k);
        return model(s.tileX(), s.tileZ()).thenApplyAsync(m -> {
            long t0 = System.nanoTime();
            CityPlanner.Plan p = planner.plan(m, s.clip(), maxCells);
            LOG.info(() -> String.format("stadt | etappe_geplant | ort=%s etappe=%d/%d blöcke=%d gebäude=%d dauer_ms=%d",
                    place, k + 1, stages.size(), p.blocks(), p.buildings(), (System.nanoTime() - t0) / 1_000_000));
            return p;
        }, worker);
    }

    /** Dateien und Rechteck einer Kartenkachel. */
    private record TileFiles(Path file, List<Path> older, double south, double west, double north, double east) {
        List<Path> candidates() {
            List<Path> all = new ArrayList<>(older.size() + 1);
            all.add(file);
            all.addAll(older);
            return all;
        }
    }

    private TileFiles files(int tx, int tz) {
        // Kachel mit Rand, aber nicht weiter als der Kreis (+ Rand) – ein kleiner Ort fragt so nicht 1 km² ab.
        int lim = radius + MARGIN;
        int[] box = tileBox(tx, tz);
        int x0 = Math.max(-lim, box[0] - MARGIN);
        int z0 = Math.max(-lim, box[1] - MARGIN);
        int x1 = Math.min(lim, box[2] + MARGIN);
        int z1 = Math.min(lim, box[3] + MARGIN);
        double cos = Math.cos(Math.toRadians(lat));
        double north = lat - z0 / M_PER_DEG_LAT;
        double south = lat - z1 / M_PER_DEG_LAT;
        double west = lon + x0 / (M_PER_DEG_LON * cos);
        double east = lon + x1 / (M_PER_DEG_LON * cos);
        // Vorsilbe = Abfrageinhalt (cacheName): Kacheln einer älteren Abfrage gelten nicht. Ausnahme bleibt der
        // Altbestand vor den Etappen (legacyCache, nur ein Ort aus einer Kachel, spätestens 2026-11-27 abgelaufen) –
        // ihm fehlen Türme, Gleise, Zäune und Parkplätze, er hilft aber, wenn Overpass überlastet ist.
        // Geschrieben wird gzip („.json.gz“): Koordinatentext schrumpft auf einen Bruchteil, 30 Tage Kacheln
        // einer Großstadt lagen sonst als hunderte MB im Plugin-Ordner. Gelesen wird zuerst .json.gz, dann die
        // unkomprimierte Kachel der Fassung davor und der Altbestand vor den Etappen – beide bleiben gültig, ein
        // Wechsel der Fassung darf den Dienst nicht erneut belasten.
        Path file = cacheFile(cacheDir, south, west, north, east);
        List<Path> older = new ArrayList<>();
        older.add(cacheDir.resolve(cacheName(south, west, north, east)));
        Path legacy = legacyCache();
        if (legacy != null && tiles() == 1) {
            older.add(legacy);
        }
        return new TileFiles(file, List.copyOf(older), south, west, north, east);
    }

    /** Kacheln, die gerade vom Dienst kommen – Bau und downloadAll teilen sich dieselbe Abfrage. */
    private final java.util.concurrent.ConcurrentHashMap<Long, CompletableFuture<Void>> inflight =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger readyTiles = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean closed;
    private volatile boolean downloading;
    private volatile boolean downloadDone;
    private volatile int downloadTiles;

    /** Kachel liegt frisch auf der Platte (oder kommt gerade); fertig, wenn sie lesbar ist. */
    private CompletableFuture<Void> ensureCached(int tx, int tz) {
        TileFiles f = files(tx, tz);
        for (Path c : f.candidates()) {
            if (fresh(c)) {
                return CompletableFuture.completedFuture(null);
            }
        }
        long k = key(tx, tz);
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> running = inflight.putIfAbsent(k, mine);
        if (running != null) {
            return running;
        }
        json(f.file(), f.older(), f.south(), f.west(), f.north(), f.east(), 0).whenComplete((j, err) -> {
            inflight.remove(k, mine);
            if (err != null) {
                mine.completeExceptionally(err);
            } else {
                mine.complete(null);
            }
        });
        return mine;
    }

    /**
     * Alle Kacheln des Orts in Bau-Reihenfolge nacheinander auf die Platte legen (Wunsch 2026-10-29). Vorher holte der
     * Bau jede 1280-m-Kachel erst,
     * wenn ihre erste Etappe dran war – bei ausgelastetem Overpass stand er dann minutenlang (dev01, Sindelfingen: 5,5
     * von 10 min). Eine Abfrage zugleich: die nächste erst, wenn die vorige fertig oder endgültig gescheitert ist
     * (Schonung der öffentlichen Dienste); braucht der Bau eine Kachel, die gerade lädt, wartet er auf dieselbe
     * Abfrage (inflight). Scheitert eine Kachel ganz, geht es mit der nächsten weiter – der Bau fragt sie später selbst.
     * Nur Platte, kein Heap: eingelesen wird erst beim Planen.
     */
    void downloadAll() {
        if (downloading) {
            return;
        }
        downloading = true;
        List<long[]> order = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Stage s : stages) {
            if (seen.add(key(s.tileX(), s.tileZ()))) {
                order.add(new long[] {s.tileX(), s.tileZ()});
            }
        }
        downloadTiles = order.size();
        long t0 = System.nanoTime();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (long[] tile : order) {
            chain = chain.thenCompose(v -> closed ? CompletableFuture.<Void>completedFuture(null)
                    : ensureCached((int) tile[0], (int) tile[1]).handle((x, err) -> {
                        if (err != null && (closed || err instanceof CancellationException
                                || err.getCause() instanceof CancellationException)) {
                            // Abgebrochen (close), kein Fehler des Dienstes – nicht als „fehlgeschlagen“ melden.
                            LOG.fine(() -> "stadt | karte_vorab_abgebrochen | ort=" + place + " kachel=" + tile[0]
                                    + "/" + tile[1]);
                        } else if (err != null) {
                            LOG.info(() -> "stadt | karte_vorab_fehlgeschlagen | ort=" + place + " kachel=" + tile[0]
                                    + "/" + tile[1] + " grund=" + err.getMessage());
                        } else {
                            readyTiles.incrementAndGet();
                        }
                        return null;
                    }));
        }
        chain.whenComplete((v, err) -> {
            downloadDone = true;
            LOG.info(() -> String.format("stadt | karten_vorab_fertig | ort=%s kacheln=%d/%d dauer_s=%d", place,
                    readyTiles.get(), order.size(), (System.nanoTime() - t0) / 1_000_000_000L));
        });
    }

    /**
     * Nur während downloadAll läuft – danach leer, auch wenn Kacheln gescheitert sind (die holt der Bau selbst; ein
     * stehendes „Karte 11/12“ sähe aus wie ein Hänger).
     */
    @Override
    public String mapProgress() {
        return downloading && !downloadDone ? ", Karte " + readyTiles.get() + "/" + downloadTiles : "";
    }

    /**
     * Die Kacheln des Orts, die frisch im Zwischenspeicher liegen – für /bm ort &lt;Stadt&gt; &lt;Straße&gt;
     * (StreetFinder). Nie Netz: Wer nach einer Straße fragt, soll nicht Overpass belasten; eine abgelaufene Kachel
     * fehlt dann eben.
     */
    List<Path> cachedTiles() {
        List<Path> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Stage s : stages) {
            if (!seen.add(key(s.tileX(), s.tileZ()))) {
                continue;
            }
            for (Path c : files(s.tileX(), s.tileZ()).candidates()) {
                if (fresh(c)) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** Kacheln, die downloadAll schon auf der Platte hat (für „Karte n/m“ im Fortschritt). */
    int readyTiles() {
        return readyTiles.get();
    }

    private synchronized CompletableFuture<CityModel> model(int tx, int tz) {
        return models.computeIfAbsent(key(tx, tz), k -> {
            TileFiles tf = files(tx, tz);
            // Erst auf die (womöglich schon laufende) Abfrage von downloadAll warten, dann lesen; ist sie gescheitert,
            // fragt json selbst noch einmal mit allen Ausweichdiensten.
            CompletableFuture<String> data = ensureCached(tx, tz).handle((v, err) -> null)
                    .thenCompose(v -> json(tf.file(), tf.older(), tf.south(), tf.west(), tf.north(), tf.east(), 0));
            CompletableFuture<CityModel> f = data
                    .thenApplyAsync(j -> OsmImport.parse(j, place, lat, lon, radius), worker);
            // Gescheiterte Kachel nicht behalten – sonst scheitern alle ihre Etappen sofort mit (München 2026-10-28).
            f.whenCompleteAsync((m, err) -> {
                if (err != null) {
                    synchronized (CityStages.this) {
                        models.remove(k, f);
                    }
                }
            }, worker);  // async: nie innerhalb von computeIfAbsent an der Map ändern
            return f;
        });
    }

    /**
     * Zwischenspeicher aus der Zeit vor den Etappen („lat_lon_radius.json“, eine Umkreis-Abfrage). dev01 2026-10-28:
     * Hepberg lag so schon auf der Platte, die neue Fassung suchte nur unter Kachelnamen und fragte – bei überlastetem
     * Dienst vergeblich – neu an. Deckt die alte Abfrage den Kreis ab (gleiche Mitte, Radius ≥), reicht sie für einen
     * Ort aus einer Kachel.
     */
    private Path legacyCache() {
        String prefix = String.format(Locale.ROOT, "%.5f_%.5f_", lat, lon);
        try (var files = Files.list(cacheDir)) {
            return files.filter(p -> {
                String n = p.getFileName().toString();
                if (!n.startsWith(prefix) || !n.endsWith(".json")) {
                    return false;
                }
                String r = n.substring(prefix.length(), n.length() - ".json".length());
                return r.matches("\\d{1,5}") && Integer.parseInt(r) >= radius;
            }).findFirst().orElse(null);
        } catch (IOException e) {
            return null;  // kein Ordner, kein Altbestand
        }
    }

    /**
     * Kachel aus dem Zwischenspeicher (file, dann older in dieser Reihenfolge) oder vom Dienst; geschrieben wird nur
     * file (gzip).
     */
    private CompletableFuture<String> json(Path file, List<Path> older, double s, double w, double n, double e,
            int attempt) {
        // Vorher prüfte nur downloadAll „closed“, und das erst vor der nächsten Kachel: Eine laufende Kachelabfrage
        // fragte nach /bm stadt stopp oder Neuplanen bis zu BUSY_ROUNDS × Dienste weiter bei öffentlichen
        // Overpass-Instanzen an – parallel zum neuen Lauf, gegen die Regel „eine Overpass-Abfrage zugleich“. Dazu kam
        // das Rauschen „karte_vorab_fehlgeschlagen … RejectedExecutionException“ (supplyAsync auf den beendeten
        // worker). Jetzt endet jeder Aufruf nach close() sofort, auch die Wiederholung nach der Pause (sie läuft über
        // thenCompose wieder hier durch). Wer das zurückdreht, belastet fremde Dienste für eine Antwort, die niemand
        // mehr liest.
        if (closed) {
            return CompletableFuture.failedFuture(new CancellationException("geschlossen"));
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(file);
        candidates.addAll(older);
        for (Path c : candidates) {
            if (fresh(c)) {
                LOG.info(() -> "stadt | karte_aus_zwischenspeicher | datei=" + c.getFileName());
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return readCache(c);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }, worker);
            }
        }
        long t0 = System.nanoTime();
        int mirror = attempt % osm.mirrors();
        return osm.fetchBox(s, w, n, e, mirror).thenApply(j -> {
            // bytes = entpackte Länge (Zeichen) wie vor gzip, Logs bleiben vergleichbar; Dateigröße zeigt die Platte.
            LOG.info(() -> String.format("stadt | karte_geladen | datei=%s bytes=%d dauer_ms=%d dienst=%s",
                    file.getFileName(), j.length(), (System.nanoTime() - t0) / 1_000_000, osm.mirrorName(mirror)));
            try {
                writeCache(file, j);
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "stadt | zwischenspeicher_schreiben_fehlgeschlagen | datei=" + file.getFileName(),
                        ex);
            }
            return j;
        }).exceptionallyCompose(err -> {
            Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
            // Ausgelastet oder nicht erreichbar: sofort die nächste Instanz; erst nach einer ganzen Runde 30 s warten.
            boolean retry = cause instanceof OsmClient.BusyException || cause instanceof IOException;
            int next = attempt + 1;
            if (closed) {
                // Geschlossen, während die Abfrage lief: keine Wiederholung planen und kein „kartendienst_ausgelastet“.
                return CompletableFuture.failedFuture(new CancellationException("geschlossen"));
            }
            if (retry && next < BUSY_ROUNDS * osm.mirrors()) {
                long wait = next % osm.mirrors() == 0 ? 30 : 0;
                LOG.info(() -> "stadt | kartendienst_ausgelastet | dienst=" + osm.mirrorName(mirror) + " grund="
                        + cause.getMessage() + " nächster=" + osm.mirrorName(next) + " warte_s=" + wait);
                return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(wait, TimeUnit.SECONDS))
                        .thenCompose(x -> json(file, older, s, w, n, e, next));
            }
            if (retry) {
                return CompletableFuture.failedFuture(new IllegalStateException("Alle " + osm.mirrors()
                        + " Kartendienste sind ausgelastet (" + cause.getMessage() + ") – bitte in ein paar Minuten "
                        + "erneut versuchen.", cause));
            }
            return CompletableFuture.failedFuture(cause);
        });
    }

    /**
     * Name einer Kartenkachel im Zwischenspeicher (ohne „.gz“). Vorsilbe = Kennung des Abfrageinhalts
     * ({@link OsmClient#cacheTag()}); bis 2026-10-28 „v2“ von Hand (mit building:part). Seit 2026-10-29 holt die
     * Abfrage auch Gleise, Zäune und Parkplätze – eine v2-Kachel würde sie 30 Tage lang still weglassen, deshalb gilt
     * sie nicht mehr. Der Weltbau benutzt denselben Namen: eine Kachel, die /bm stadt schon geholt hat, belastet den
     * Dienst kein zweites Mal – und umgekehrt.
     */
    static String cacheName(double south, double west, double north, double east) {
        return String.format(Locale.ROOT, "%s_%.5f_%.5f_%.5f_%.5f.json", OsmClient.cacheTag(), south, west, north,
                east);
    }

    /** Datei einer Kartenkachel, gzip (siehe writeCache). */
    static Path cacheFile(Path cacheDir, double south, double west, double north, double east) {
        return cacheDir.resolve(cacheName(south, west, north, east) + ".gz");
    }

    /**
     * Liegt die Datei vor und ist jünger als 30 Tage? Ein unlesbarer Zeitstempel gilt als „nicht da“ – fail-open
     * bewusst: Ein kaputter Zwischenspeicher ist kein Grund, nicht neu zu laden.
     */
    static boolean fresh(Path c) {
        try {
            return Files.isRegularFile(c)
                    && Files.getLastModifiedTime(c).toInstant().isAfter(Instant.now().minus(CACHE_MAX_AGE));
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "stadt | zwischenspeicher_unlesbar | datei=" + c.getFileName(), ex);
            return false;
        }
    }

    /** Zwischenspeicher lesen: „.gz“ entpacken, sonst Klartext (Kacheln bis 2026-10-28 und Altbestand). */
    static String readCache(Path p) throws IOException {
        if (p.getFileName().toString().endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /**
     * Kachel gzip-komprimiert schreiben, erst in eine Nachbardatei, dann umbenennen. Vorher direkt in die Zieldatei:
     * Bei Klartext-JSON scheiterte ein abgebrochener Schreibvorgang nur beim Einlesen; ein abgeschnittener gzip-Strom
     * hätte die Kachel 30 Tage lang mit EOFException blockiert. Wer das Umbenennen entfernt, holt sich das zurück.
     */
    static void writeCache(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        synchronized (this) {
            models.clear();
        }
    }

    /** Fläche der Kachel ohne Rand: {x0, z0, x1, z1}, x1/z1 ausschließlich. */
    static int[] tileBox(int tx, int tz) {
        int half = TILE / 2;
        return new int[] {tx * TILE - half, tz * TILE - half, tx * TILE + half, tz * TILE + half};
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
