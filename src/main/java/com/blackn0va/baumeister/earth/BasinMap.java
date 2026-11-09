package com.blackn0va.baumeister.earth;

import com.blackn0va.baumeister.terrain.ElevationTiles;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Senken unter Meereshöhe auf einem groben Gitter (Zoom 7 ≈ 1,2 km je Pixel am Äquator): Hängt eine Stelle unter 0 m
 * über Zellen unter 0 m mit dem Ozean zusammen (offen) – oder ist sie ringsum von Land umschlossen (geschlossen)?
 *
 * Warum: Vorher war jede Säule unter 0 m Meer. Death Valley (−86 m), das Tote Meer (Seefläche −412 m), die Qattara-
 * Senke, Turpan und das Kaspische Becken liefen bis Meereshöhe voll Wasser – Hunderte Blöcke tief mitten im Land.
 *
 * Offen ist eine Zusammenhangskomponente der Zellen unter 0 m (4er-Nachbarschaft), wenn sie
 * - eine Zelle von deepSeed Metern Tiefe oder mehr enthält – der Ozean (Schwarzes Meer: Bosporus ist auf Zoom 7 zu,
 *   aber −2 212 m tief; das Kaspische Meer reicht in den Daten nur bis −773 m),
 * - an den Rand der Mercator-Karte reicht (Polarmeer) oder größer als maxPixels ist (im Zweifel Meer), oder
 * - nirgends tiefer als shallow Meter liegt: Eine flache Lagune ist auf Zoom 7 nur zufällig abgeschnitten; echte
 *   Senken liegen tiefer (Lake Eyre −15 m, die übrigen gemessenen unter −80 m).
 * Im Zweifel offen: Das ist der Stand vor dieser Karte (Wasser bis Meereshöhe).
 *
 * Gemessen 2026-10-29 (BasinMapProbe, echte Terrarium-Kacheln): geschlossen Badwater, Totes Meer, Jericho, See
 * Genezareth, Qattara, Saltonsee, Assalsee, Turpan, Lake Eyre, Kaspisches Meer samt Wolgadelta und Kara-Bogaz-Gol;
 * offen Nordsee, Wattenmeer, Ostsee, Schwarzes und Asowsches Meer, Persischer Golf, Hudson Bay, Ostsibirische See.
 * Einige Fjorde und das Kurische Haff sind geschlossen (auf Zoom 7 abgeschnitten) – dort zeigen die feinen Daten aber
 * eine Wasserfläche (0 m bzw. −1 m), die WaterTable als Wasserfläche malt.
 *
 * Warum ohne Fenster: Die erste Fassung suchte nur im Fenster ±2 bzw. ±5 Kacheln um die gerechnete Kachel und
 * nannte „reicht an den Fensterrand“ offen. Gemessen: Das Kaspische Meer war von der Kachel bei 42° N aus geschlossen,
 * von Atyrau (47° N) aus offen – an der Kachelgrenze wäre der Wasserspiegel um 29 Blöcke gesprungen. Jetzt hängt das
 * Ergebnis nur an der Komponente selbst, egal von welcher Stelle aus sie gefunden wird und in welcher Reihenfolge
 * Chunks entstehen. Wer wieder ein Fenster einführt, holt die Sprünge zurück.
 *
 * Threads: threadsicher. Gelesen wird ohne Schloss. Eine Suche hält nur das Schloss ihrer Start-Ergebniskachel:
 * Vorher lief jede Suche unter EINEM Schloss samt Kachel-Download (Nordsee 3,5 s, Ostsibirische See bis 24 s, bei
 * Netzausfall bis 60 s Zeitlimit) – jeder Generator-Thread, der irgendwo ein neues Pixel unter 0 m brauchte, wartete
 * so lange mit (Prüfer-Befund 2026-10-29). Jetzt warten nur Threads, die in derselben Ergebniskachel starten (und
 * danach das fertige Ergebnis lesen); zwei Suchen derselben Komponente aus verschiedenen Kacheln rechnen höchstens
 * doppelt – mit demselben Ergebnis, weil es nur an der Komponente hängt. Ergebnisse sind Bytes (atomar geschrieben),
 * ein Leser sieht ein Pixel entweder unbekannt (sucht dann selbst) oder fertig. Fehler: IOException, wenn Kacheln
 * fehlen – der Aufrufer wertet das als offen und merkt die Säule als geschätzt.
 */
public final class BasinMap {

    /** Kacheln liefern (im Plugin ElevationTiles::tile); Spalten über die Datumsgrenze umgebrochen. */
    @FunctionalInterface
    public interface Tiles {
        float[] tile(int tx, int ty) throws IOException;
    }

    /** Ergebnis je Pixel; 0 = noch nicht gerechnet. */
    static final byte UNKNOWN = 0;
    static final byte LAND = 1;
    static final byte OPEN = 2;
    static final byte CLOSED = 3;
    /** Suchradius in Pixeln, wenn das grobe Pixel selbst nicht unter 0 m liegt (Küste, schmale Bucht). */
    static final int NEAR = 2;
    /**
     * Höchstens so viele Ergebniskacheln (je 64 KB) im Speicher; darüber fällt die am längsten nicht gelesene heraus
     * (Werte sind reproduzierbar). Vorher leerte clear() ALLE auf einmal – wer abwechselnd zwei große Gegenden
     * erkundete, löste die Sekunden-Suchen immer wieder aus.
     */
    static final int CACHE_LIMIT = 128;
    private static final double MAX_LAT = 85.0511287798;

    /** Ergebniskachel samt letztem Lesezugriff (für die Verdrängung). */
    private static final class Labels {
        final byte[] b = new byte[256 * 256];
        volatile long used;
    }

    private final Tiles tiles;
    private final int zoom;
    private final double deepSeed;
    private final double shallow;
    private final long maxPixels;
    private final int cacheLimit;
    private final Map<Long, Labels> labels = new ConcurrentHashMap<>();
    /** Ein Schloss je Start-Ergebniskachel (nicht je Suche entfernt: höchstens eins je Kachel, 16 Byte). */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong();

    /**
     * @param zoom      Zoom der Kacheln (Plugin: 7)
     * @param deepSeed  so tief (m, positiv) gilt eine Zelle als Ozean
     * @param shallow   Komponenten, die nirgends so tief (m, positiv) reichen, gelten als offen
     * @param maxPixels größere Komponenten gelten als offen (Schutz gegen eine Suche ohne Ende)
     */
    public BasinMap(Tiles tiles, int zoom, double deepSeed, double shallow, long maxPixels) {
        this(tiles, zoom, deepSeed, shallow, maxPixels, CACHE_LIMIT);
    }

    /** @param cacheLimit höchstens so viele Ergebniskacheln im Speicher (Tests: wenige) */
    BasinMap(Tiles tiles, int zoom, double deepSeed, double shallow, long maxPixels, int cacheLimit) {
        this.tiles = tiles;
        this.zoom = zoom;
        this.deepSeed = deepSeed;
        this.shallow = shallow;
        this.maxPixels = maxPixels;
        this.cacheLimit = cacheLimit;
    }

    /** Hängt die Stelle mit dem Ozean zusammen? Liegt das grobe Pixel nicht unter 0 m, entscheidet die Umgebung. */
    public boolean open(double lat, double lon) throws IOException {
        int n = 256 << zoom;
        int gx = (int) Math.floor(ElevationTiles.tileX(lon, zoom) * 256);
        double la = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        int gy = Math.max(0, Math.min(n - 1, (int) Math.floor(ElevationTiles.tileY(la, zoom) * 256)));
        byte own = label(gx, gy);
        if (own != LAND) {
            return own == OPEN;
        }
        boolean closed = false;
        for (int r = 1; r <= NEAR; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue;
                    }
                    int y = gy + dy;
                    if (y < 0 || y >= n) {
                        continue;
                    }
                    byte b = label(gx + dx, y);
                    if (b == OPEN) {
                        return true;
                    }
                    closed |= b == CLOSED;
                }
            }
        }
        // Ringsum Land und keine Senke in der Nähe: im Zweifel offen (siehe Klassenkommentar).
        return !closed;
    }

    /** Ergebnis am globalen Pixel (gx wird umgebrochen, gy muss auf der Karte liegen). */
    byte label(int gx, int gy) throws IOException {
        int n = 256 << zoom;
        int x = Math.floorMod(gx, n);
        long key = key(x >> 8, gy >> 8);
        int i = (gy & 255) * 256 + (x & 255);
        byte known = known(key, i);
        if (known != UNKNOWN) {
            return known;
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            known = known(key, i);
            if (known != UNKNOWN) {
                return known;
            }
            return search(x, gy);
        }
    }

    private byte known(long key, int i) {
        Labels l = labels.get(key);
        if (l == null) {
            return UNKNOWN;
        }
        // Nur bei Treffern stempeln, die gelesen werden – ein Zähler je Kachelzugriff, kein Schloss.
        l.used = clock.incrementAndGet();
        return l.b[i];
    }

    private static long key(int tx, int ty) {
        return (long) tx << 32 | (ty & 0xffffffffL);
    }

    /** Ergebniskachel; legt sie an und verdrängt dabei die am längsten nicht gelesene, wenn es zu viele sind. */
    private byte[] labelsOf(long key) {
        Labels l = labels.get(key);
        if (l == null) {
            l = labels.computeIfAbsent(key, k -> {
                Labels fresh = new Labels();
                fresh.used = clock.incrementAndGet();
                return fresh;
            });
            evict(key);
        }
        return l.b;
    }

    /** Über dem Limit die ältesten Kacheln entfernen (nie die gerade angelegte). Selten: nur beim Anlegen. */
    private void evict(long keep) {
        while (labels.size() > cacheLimit) {
            long oldest = Long.MAX_VALUE;
            Long victim = null;
            for (Map.Entry<Long, Labels> e : labels.entrySet()) {
                if (e.getKey() != keep && e.getValue().used < oldest) {
                    oldest = e.getValue().used;
                    victim = e.getKey();
                }
            }
            if (victim == null) {
                return;
            }
            labels.remove(victim);
        }
    }

    /** Ergebniskacheln im Speicher (Tests). */
    int cachedTiles() {
        return labels.size();
    }

    /**
     * Breitensuche ab (x, y) durch alle Zellen unter 0 m. Bricht ab, sobald die Komponente offen ist (Ozean: meist nach
     * wenigen Pixeln); alle besuchten Pixel bekommen das Ergebnis. Abgebrochene Komponenten werden beim nächsten noch
     * unbekannten Pixel weitergesucht – mit demselben Ergebnis, weil es nur von der Komponente abhängt.
     */
    private byte search(int x, int y) throws IOException {
        int n = 256 << zoom;
        Map<Long, float[]> data = new HashMap<>();
        Map<Long, long[]> seen = new HashMap<>();
        float v0 = value(data, x, y);
        if (!(v0 < 0)) {
            labelsOf(key(x >> 8, y >> 8))[(y & 255) * 256 + (x & 255)] = LAND;
            return LAND;
        }
        long[] queue = new long[1024];
        int head = 0;
        int tail = 0;
        queue[tail++] = pack(x, y);
        mark(seen, x, y);
        boolean open = false;
        double min = v0;
        while (head < tail) {
            long p = queue[head++];
            int px = (int) (p >> 32);
            int py = (int) p;
            float v = value(data, px, py);
            min = Math.min(min, v);
            if (v <= -deepSeed || tail > maxPixels) {
                open = true;
                break;
            }
            for (int d = 0; d < 4; d++) {
                int nx = Math.floorMod(px + (d == 0 ? 1 : d == 1 ? -1 : 0), n);
                int ny = py + (d == 2 ? 1 : d == 3 ? -1 : 0);
                if (ny < 0 || ny >= n) {
                    open = true;  // Rand der Mercator-Karte: Polarmeer, nie eine Senke
                    break;
                }
                if (!(value(data, nx, ny) < 0) || !mark(seen, nx, ny)) {
                    continue;
                }
                if (tail == queue.length) {
                    queue = Arrays.copyOf(queue, queue.length * 2);
                }
                queue[tail++] = pack(nx, ny);
            }
            if (open) {
                break;
            }
        }
        byte result = open || min > -shallow ? OPEN : CLOSED;
        // Alles Besuchte (auch noch in der Schlange) bekommt das Ergebnis; bei Abbruch ist der Rest der Komponente
        // noch unbekannt und wird bei Bedarf weitergesucht.
        for (int k = 0; k < tail; k++) {
            int px = (int) (queue[k] >> 32);
            int py = (int) queue[k];
            labelsOf(key(px >> 8, py >> 8))[(py & 255) * 256 + (px & 255)] = result;
        }
        return result;
    }

    private static long pack(int x, int y) {
        return (long) x << 32 | (y & 0xffffffffL);
    }

    private float value(Map<Long, float[]> data, int x, int y) throws IOException {
        long k = key(x >> 8, y >> 8);
        float[] t = data.get(k);
        if (t == null) {
            // Während einer Suche festhalten: Die LRU der Kacheln hat weniger Plätze, als eine große Suche berührt.
            t = tiles.tile(x >> 8, y >> 8);
            data.put(k, t);
        }
        return t[(y & 255) * 256 + (x & 255)];
    }

    /** Als besucht markieren; false, wenn schon besucht. */
    private static boolean mark(Map<Long, long[]> seen, int x, int y) {
        long[] bits = seen.computeIfAbsent(key(x >> 8, y >> 8), k -> new long[1024]);
        int i = (y & 255) * 256 + (x & 255);
        long m = 1L << (i & 63);
        if ((bits[i >> 6] & m) != 0) {
            return false;
        }
        bits[i >> 6] |= m;
        return true;
    }
}
