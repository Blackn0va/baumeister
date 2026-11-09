package com.blackn0va.baumeister.terrain;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

/**
 * Echte Höhen aus den öffentlichen Terrain-Kacheln (Terrarium-Format, AWS Open Data „elevation-tiles-prod“, ohne
 * Schlüssel): je Pixel Höhe = R·256 + G + B/256 − 32768 Meter. Zoom 15 ≈ 4,8 m je Pixel am Äquator, 3,9 m auf der
 * Breite des Grand Canyon; dazwischen wird bilinear auf 1 m gerechnet. Gröbere Zooms (z. B. 10 ≈ 153 m je Pixel) für
 * Übersichten wie den Höhenbezug der Erdwelt.
 *
 * Warum Kacheln statt Höhen-API: Eine API je Punkt wären Millionen Aufrufe; eine Kachel deckt 256×256 Pixel.
 * Jede Kachel wird einmal geholt und als PNG unter cacheDir gespeichert (dauerhaft – Höhen ändern sich nicht).
 * Datenquelle nennen: Terrain-Kacheln von Mapzen/AWS (u. a. SRTM, USGS 3DEP, GMTED, ETOPO).
 *
 * Speicher: Vorher hielt eine ConcurrentHashMap jede je geladene Kachel für immer – für einen Ort mit 25 km Radius
 * 250 MB, für die ganze Erde (Erdwelt) ohne Ende. Jetzt höchstens `capacity` Kacheln (Vorgabe 256 ≈ 64 MB), die am
 * längsten nicht benutzte fliegt raus (sie liegt ja auf der Platte). Wer die Grenze entfernt, lässt den Server beim
 * Erkunden der Erdwelt volllaufen.
 *
 * Threads: elevation()/tile() aus beliebig vielen Threads (Weltgenerator). Vorher lief jeder Download unter einem
 * globalen synchronized – alle Generator-Threads standen hinter einer einzigen Kachel. Jetzt hat jede Kachel ein
 * eigenes Future: verschiedene Kacheln laden gleichzeitig, dieselbe Kachel genau einmal, alle anderen warten darauf.
 * Treffer kosten kein Schloss; nur das Aufräumen beim Nachladen ist synchronisiert.
 *
 * Fehler: IOException beim Holen (Netz, HTTP ≠ 200, kaputtes PNG). Ein Fehlschlag wird nie dauerhaft gemerkt (vorher
 * wäre eine leere/kaputte Kachel für immer im Speicher geblieben, vgl. den Overpass-Vorfall 502 im Stadtbau), aber
 * FAILURE_BLOCK lang: Bis dahin bekommt jeder Aufruf derselben Kachel sofort denselben Fehler, ohne Netz. Ganz ohne
 * Sperre (Stand vor 2026-10-28, Prüferbefund) fragte jede der 18 × 18 Säulen eines Erdwelt-Chunks die tote Kachel neu
 * an – gemessen 972 Abrufe je Chunk, bei geschluckten Paketen 972 × 10 s Verbindungsaufbau ≈ 2,7 h je Generator-Thread.
 * Wer die Sperre entfernt, holt diesen Sturm zurück.
 *
 * Platte: Nur eine Kachel, die sich dekodieren lässt, wird gespeichert. Vorher landete jede HTTP-200-Antwort ungeprüft
 * im Cache – eine Proxy-Fehlerseite oder ein abgeschnittener Body lag danach für immer als .png dort und wurde nie neu
 * geholt (Prüferbefund 2026-10-28). Eine schon gespeicherte kaputte Datei wird gelöscht und neu geladen.
 */
public final class ElevationTiles {

    /** Vorgabe-Zoom (Gelände 1:1). */
    public static final int ZOOM = 15;
    /** Vorgabe der Speichergrenze: 256 Kacheln × 256 KB ≈ 64 MB. */
    public static final int DEFAULT_CAPACITY = 256;
    /** Web-Mercator endet hier; darüber gibt es keine Kacheln (Pole: Wert der Randzeile). */
    private static final double MAX_LAT = 85.0511287798;
    private static final String URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png";
    private static final String USER_AGENT = "Baumeister-Minecraft-Plugin/0.1";
    /**
     * So lange gilt eine gescheiterte Kachel als gescheitert. Kurz genug, dass ein kurzer Netzaussetzer nicht lange
     * nachwirkt; lang genug, dass ein Chunk (Sekundenbruchteile) und alle Generator-Threads daneben die tote Kachel nur
     * einmal anfragen.
     */
    static final long FAILURE_BLOCK_NANOS = 30_000_000_000L;
    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Bytequelle einer Kachel (PNG). Normal HTTP; Tests geben eine Attrappe ohne Netz. */
    @FunctionalInterface
    public interface TileSource {
        byte[] fetch(int zoom, int tx, int ty) throws IOException;
    }

    /**
     * Eine Kachel im Speicher: wird genau einmal geladen; lastUse für die LRU-Auswahl. Gescheitert bleibt sie bis
     * failedUntil (System.nanoTime-Zeitachse) in der Tabelle, danach ersetzt sie der nächste Aufruf durch einen neuen
     * Versuch.
     */
    private static final class Entry {
        final CompletableFuture<float[]> data = new CompletableFuture<>();
        volatile long lastUse;
        volatile long failedUntil;
    }

    private final Path cacheDir;
    private final int zoom;
    private final int capacity;
    private final TileSource source;
    /** Uhr der Fehlersperre; Tests drehen sie vor, statt 30 s zu warten. */
    private final LongSupplier nanoClock;
    private final Map<Long, Entry> tiles = new ConcurrentHashMap<>();
    /**
     * Uhr der LRU: zählt nur beim Nachladen hoch. Ein Treffer schreibt den aktuellen Stand ohne CAS – sonst stritten
     * acht Generator-Threads bei jedem Pixel um denselben Zähler. Feiner als „seit dem letzten Nachladen“ muss die
     * Reihenfolge nicht sein.
     */
    private final AtomicLong clock = new AtomicLong();
    private final Object evictLock = new Object();

    public ElevationTiles(Path cacheDir) {
        this(cacheDir, ZOOM, DEFAULT_CAPACITY);
    }

    public ElevationTiles(Path cacheDir, int zoom, int capacity) {
        this(cacheDir, zoom, capacity, new HttpSource());
    }

    /** Mit austauschbarer Bytequelle (Tests: synthetische Kacheln ohne Netz). */
    public ElevationTiles(Path cacheDir, int zoom, int capacity, TileSource source) {
        this(cacheDir, zoom, capacity, source, System::nanoTime);
    }

    /** Mit austauschbarer Uhr für die Fehlersperre (Tests). */
    ElevationTiles(Path cacheDir, int zoom, int capacity, TileSource source, LongSupplier nanoClock) {
        if (zoom < 0 || zoom > 15) {
            throw new IllegalArgumentException("Zoom 0–15: " + zoom);
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("Speichergrenze mindestens 1 Kachel: " + capacity);
        }
        this.cacheDir = cacheDir;
        this.zoom = zoom;
        this.capacity = capacity;
        this.source = source;
        this.nanoClock = nanoClock;
    }

    public int zoom() {
        return zoom;
    }

    /** Kachel-Spalte und -Zeile (Web-Mercator) für eine Lage, als Gleitkomma – der Nachkommaanteil ist die Pixelstelle. */
    public static double tileX(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * (1 << zoom);
    }

    public static double tileY(double lat, int zoom) {
        double r = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << zoom);
    }

    /** Breite der Mitte einer Pixelzeile (Umkehrung von tileY). */
    public static double pixelLat(int gy, int zoom) {
        double n = Math.PI * (1 - 2 * (gy + 0.5) / (256.0 * (1 << zoom)));
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** Länge der Mitte einer Pixelspalte. */
    public static double pixelLon(int gx, int zoom) {
        return (gx + 0.5) / (256.0 * (1 << zoom)) * 360.0 - 180.0;
    }

    /** Höhe in Metern an (lat, lon), bilinear zwischen den vier Nachbarpixeln. */
    public double elevation(double lat, double lon) throws IOException {
        return elevation(lat, lon, null);
    }

    /**
     * Wie elevation(lat, lon), legt aber die vier Rohpixel in corners ab ({h00, h10, h01, h11}; null = nicht nötig).
     * Die Erdwelt braucht sie, um Wasser von Land zu unterscheiden (WaterTable: Wasserflächen und Meeresboden sind in
     * Terrarium ganzzahlig, Land nicht). Dieselbe Rechnung wie vorher, Wert für Wert – wer sie umstellt, verschiebt
     * jede noch nicht erzeugte Säule der Erdwelt gegen die schon erzeugten.
     *
     * Tempo: Liegen alle vier Pixel in derselben Kachel (bei Zoom 15 fast immer), wird die Kachel einmal statt viermal
     * geholt – vier Tabellenzugriffe weniger je Säule, gleiche Werte.
     */
    public double elevation(double lat, double lon, double[] corners) throws IOException {
        double la = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        double fx = tileX(lon, zoom) * 256 - 0.5;
        double fy = tileY(la, zoom) * 256 - 0.5;
        int px = (int) Math.floor(fx);
        int py = (int) Math.floor(fy);
        double ax = fx - px;
        double ay = fy - py;
        double h00;
        double h10;
        double h01;
        double h11;
        int n = 256 << zoom;
        int x0 = Math.floorMod(px, n);
        if ((x0 & 255) != 255 && (py & 255) != 255 && py >= 0 && py + 1 < n) {
            float[] t = tile(x0 >> 8, py >> 8);
            int i = (py & 255) * 256 + (x0 & 255);
            h00 = t[i];
            h10 = t[i + 1];
            h01 = t[i + 256];
            h11 = t[i + 257];
        } else {
            h00 = pixel(px, py);
            h10 = pixel(px + 1, py);
            h01 = pixel(px, py + 1);
            h11 = pixel(px + 1, py + 1);
        }
        if (corners != null) {
            corners[0] = h00;
            corners[1] = h10;
            corners[2] = h01;
            corners[3] = h11;
        }
        return (h00 * (1 - ax) + h10 * ax) * (1 - ay) + (h01 * (1 - ax) + h11 * ax) * ay;
    }

    /**
     * Ein Pixel in globalen Pixelkoordinaten dieses Zooms. Spalten laufen über die Datumsgrenze weiter (vorher fragte
     * lon 180 die nicht vorhandene Kachel 2^zoom an → HTTP 403/404), Zeilen werden an den Mercator-Rändern geklemmt.
     */
    public double pixel(int gx, int gy) throws IOException {
        int n = 256 << zoom;
        int x = Math.floorMod(gx, n);
        int y = Math.max(0, Math.min(n - 1, gy));
        float[] t = tile(x >> 8, y >> 8);
        return t[(y & 255) * 256 + (x & 255)];
    }

    /** Kachel laden (Speicher → Platte → Quelle). Spalte wird über die Datumsgrenze umgebrochen. */
    public float[] tile(int tx, int ty) throws IOException {
        int n = 1 << zoom;
        int x = Math.floorMod(tx, n);
        if (ty < 0 || ty >= n) {
            throw new IOException("Höhendaten: Kachelzeile " + ty + " gibt es bei Zoom " + zoom + " nicht");
        }
        long key = (long) x << 32 | (ty & 0xffffffffL);
        Entry e;
        while (true) {
            e = tiles.get(key);
            if (e == null) {
                Entry fresh = new Entry();
                e = tiles.putIfAbsent(key, fresh);
                if (e == null) {
                    e = fresh;
                    load(x, ty, fresh);
                    break;
                }
            }
            if (e.data.isCompletedExceptionally() && nanoClock.getAsLong() - e.failedUntil >= 0) {
                // Sperre abgelaufen: den alten Fehlschlag entfernen und neu versuchen (genau ein Thread gewinnt das
                // putIfAbsent, die anderen warten wieder auf dessen Future).
                tiles.remove(key, e);
                continue;
            }
            break;
        }
        long now = clock.get();
        if (e.lastUse != now) {
            e.lastUse = now;
        }
        return await(e);
    }

    /** Lädt im aufrufenden Thread; andere Threads mit derselben Kachel warten auf das Future. */
    private void load(int tx, int ty, Entry e) {
        e.lastUse = clock.incrementAndGet();
        try {
            e.data.complete(read(tx, ty));
        } catch (IOException ex) {
            // Fehlschlag kurz merken: Wartende bekommen den Fehler, Nachzügler bis failedUntil ebenso – ohne Netz.
            fail(e, ex);
        } finally {
            if (!e.data.isDone()) {
                // Laufzeitfehler (z. B. kaputtes Bild im Decoder): Wartende dürfen nicht ewig hängen.
                fail(e, new IOException("Höhendaten: Laden von " + tx + "/" + ty + " abgebrochen"));
            }
        }
        // Nach dem Laden frisch stempeln: Während eines langsamen Downloads haben andere Kacheln die Uhr weitergedreht.
        // Auch gescheiterte Einträge zählen zur Grenze – sonst wüchse die Tabelle bei Netzausfall mit jeder Kachel.
        e.lastUse = clock.incrementAndGet();
        evict();
    }

    private void fail(Entry e, IOException ex) {
        // Erst die Frist, dann das Ergebnis: Wer den Fehlschlag sieht, sieht auch die Frist (volatile vor dem Future).
        e.failedUntil = nanoClock.getAsLong() + FAILURE_BLOCK_NANOS;
        e.data.completeExceptionally(ex);
    }

    private static float[] await(Entry e) throws IOException {
        try {
            return e.data.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Höhendaten: unterbrochen", ex);
        } catch (ExecutionException ex) {
            Throwable c = ex.getCause();
            throw new IOException(c != null ? c.getMessage() : ex.getMessage(), c != null ? c : ex);
        }
    }

    /** Längst nicht benutzte fertige Kacheln entfernen, bis die Grenze wieder stimmt. Laufende Ladevorgänge bleiben. */
    private void evict() {
        if (tiles.size() <= capacity) {
            return;
        }
        synchronized (evictLock) {
            while (tiles.size() > capacity) {
                Map.Entry<Long, Entry> oldest = null;
                for (Map.Entry<Long, Entry> m : tiles.entrySet()) {
                    Entry v = m.getValue();
                    if (!v.data.isDone()) {
                        // Läuft noch – wer es jetzt entfernte, ließe einen zweiten Thread dieselbe Kachel holen.
                        continue;
                    }
                    if (oldest == null || v.lastUse < oldest.getValue().lastUse) {
                        oldest = m;
                    }
                }
                if (oldest == null) {
                    return;
                }
                tiles.remove(oldest.getKey(), oldest.getValue());
            }
        }
    }

    /** Für Tests: liegt die Kachel gerade im Speicher? */
    boolean inMemory(int tx, int ty) {
        Entry e = tiles.get((long) Math.floorMod(tx, 1 << zoom) << 32 | (ty & 0xffffffffL));
        return e != null && e.data.isDone() && !e.data.isCompletedExceptionally();
    }

    /** Für Tests: Anzahl der Kacheln im Speicher (fertig oder im Laden). */
    int inMemoryCount() {
        return tiles.size();
    }

    /** Kachel von der Platte, sonst aus der Quelle – auf die Platte kommt nur, was sich dekodieren ließ. */
    private float[] read(int tx, int ty) throws IOException {
        Path file = cacheDir.resolve(String.format(Locale.ROOT, "%d_%d_%d.png", zoom, tx, ty));
        if (Files.isRegularFile(file)) {
            byte[] stored = Files.readAllBytes(file);
            try {
                return decode(stored);
            } catch (IOException ex) {
                // Kaputte Datei (aus einem Stand vor dieser Prüfung oder von Hand): weg damit und neu holen, sonst
                // scheiterte diese Kachel für immer.
                LOG.warning(() -> "höhen | kachel_kaputt | datei=" + file.getFileName() + " grund=" + ex.getMessage()
                        + " – wird neu geladen");
                Files.deleteIfExists(file);
            }
        }
        byte[] body = source.fetch(zoom, tx, ty);
        // Erst prüfen, dann speichern: Eine Fehlerseite mit HTTP 200 oder ein abgeschnittener Body wirft hier und
        // erreicht die Platte nie. Wer die Reihenfolge umdreht, cacht kaputte Kacheln wieder dauerhaft.
        float[] data = decode(body);
        Files.createDirectories(cacheDir);
        // Erst unter einem eindeutigen .tmp, dann umbenennen: Ein abgebrochener Download darf nie als gültige Kachel
        // liegen bleiben. Eindeutig, weil Gelände und Erdwelt je eine eigene Instanz auf denselben Ordner haben.
        Path tmp = Files.createTempFile(cacheDir, file.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, body);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return data;
    }

    static float[] decode(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null || img.getWidth() != 256 || img.getHeight() != 256) {
            throw new IOException("Höhendaten: Kachel ist kein 256×256-PNG");
        }
        float[] out = new float[256 * 256];
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                int rgb = img.getRGB(x, y);
                int r = rgb >> 16 & 255;
                int g = rgb >> 8 & 255;
                int b = rgb & 255;
                out[y * 256 + x] = (float) (r * 256 + g + b / 256.0 - 32768);
            }
        }
        return out;
    }

    /** Die öffentlichen Terrarium-Kacheln über HTTPS (Redirect.NEVER: ein Umleitungsziel wäre ein Fehler). */
    private static final class HttpSource implements TileSource {

        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        @Override
        public byte[] fetch(int zoom, int tx, int ty) throws IOException {
            HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(Locale.ROOT, URL, zoom, tx, ty)))
                    .timeout(Duration.ofSeconds(60)).header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<byte[]> r;
            try {
                r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Höhendaten: unterbrochen", e);
            }
            if (r.statusCode() != 200) {
                throw new IOException("Höhendaten: HTTP " + r.statusCode() + " für Kachel " + zoom + "/" + tx + "/"
                        + ty);
            }
            return r.body();
        }
    }
}
