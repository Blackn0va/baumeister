package com.blackn0va.baumeister;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Meldet Stadtbau und Weltbau an den Baumeister-Host, damit dort die Stadt beim Bauen als Karte zu sehen ist
 * (Wunsch 2026-10-29).
 *
 * Schnittstelle Version 1, Gegenstück im Host: host/src/server/city_api.cpp – beide Seiten gleich halten:
 *   POST &lt;scheme://host:port aus llm.url&gt;/baumeister/v1/stadt, JSON, höchstens 256 KiB je Nachricht,
 *   event start | stage | progress | end (Felder siehe die Methoden unten), Kategorien siehe CityRaster;
 *   dazu welt (ohne Auftrag): Kachelübersicht des Weltbaus, siehe world().
 * Derselbe Weg wie die LLM-Anfragen: auf dev01 über den SSH-Tunnel (127.0.0.1:18080), auf einem lokalen Server direkt.
 *
 * Reine Anzeige, deshalb bewusst fail-open: Ist der Host nicht da (Tunnel weg, App zu), baut der Baumeister weiter;
 * gemeldet wird das höchstens einmal je Minute im Log. Nie blockierend: Senden über sendAsync, höchstens eine Anfrage
 * zugleich. progress wird zusammengefasst (nur die jüngste zählt), start/stage/end landen in einer begrenzten
 * Warteschlange – läuft sie über, fällt die älteste Etappe weg (die Karte hat dann eine Lücke, der Bau nichts).
 *
 *
 * Wiederaufnahme (Prüfbefund 2026-11-02): Der Host verwirft stage und progress eines Auftrags, dessen start er nicht
 * gesehen hat (city_api.cpp, „fremder oder alter Auftrag“), und die Übersicht ging nur bei Änderungen raus. Vorher:
 * Startet die App mitten in einer Kachel neu (der Tunnel fällt mit, dev01 2026-11-01 21:28:13 ConnectException), blieb
 * die Karte bis zur nächsten Kachel leer, die Übersicht bis zur nächsten Änderung. Die leere Karte, nach der der Nutzer
 * am 2026-11-02 fragte, hatte einen anderen Grund: Overpass lieferte die Karte erst nach 4 min (502, 504), bis dahin gab
 * es nichts zu zeichnen. Jetzt: Nach einem Fehler schickt die erste gelungene Sendung start, alle bekannten
 * Etappen des laufenden Auftrags und die Übersicht erneut; die Übersicht geht außerdem spätestens jede Minute
 * unverändert noch einmal raus (Host neu gestartet, ohne dass hier ein Fehler ankam). Wer das zurückdreht, bekommt
 * die leere Karte nach jedem Abriss des Tunnels zurück.
 *
 * Threads: Aufrufe aus dem Hauptthread, Abschluss im HttpClient-Pool – der Zustand ist über this synchronisiert.
 * Fehler: wirft nicht.
 */
final class HostReporter {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final String PATH = "/baumeister/v1/stadt";
    /** start + bis zu 63 Etappen im Rückstau; eine Etappe ist höchstens ~173 KB (360² als Base64). */
    static final int MAX_QUEUE = 64;
    static final long PROGRESS_NANOS = 1_000_000_000L;
    /** Unveränderte Übersicht spätestens so oft erneut – der Host kann neu gestartet sein, ohne dass hier etwas scheiterte. */
    static final long WORLD_REFRESH_NANOS = 60_000_000_000L;

    /** Sender – im Test ohne Netz ersetzbar. Liefert den Abschluss (HTTP-Status oder Fehler). */
    interface Transport {
        java.util.concurrent.CompletableFuture<Integer> post(URI uri, String json);
    }

    private final URI uri;
    private final Transport transport;
    private final LongSupplier clock;
    private final Deque<String> queue = new ArrayDeque<>();
    private String pendingProgress;
    /** Jüngste Weltbau-Übersicht, noch nicht gesendet; lastWorld = zuletzt übernommene (nur Änderungen senden). */
    private String pendingWorld;
    private String lastWorld;
    private long lastWorldAt = Long.MIN_VALUE / 2;
    /** start und Etappen des laufenden Auftrags – zum erneuten Senden nach einem Ausfall des Hosts. */
    private String currentStart;
    private final Deque<String> currentStages = new ArrayDeque<>();
    /** Eine Sendung ist gescheitert; die nächste gelungene stößt die Wiederaufnahme an. */
    private boolean lost;
    private boolean inFlight;
    private long lastProgress = Long.MIN_VALUE / 2;
    /** Zustandswechsel statt Minutentakt im Log (OutageLog, Vorfall 2026-11-04 19:26–20:19). */
    private final OutageLog outage;
    private int dropped;

    HostReporter(URI uri, Transport transport, LongSupplier clock) {
        this.uri = uri;
        this.transport = transport;
        this.clock = clock;
        this.outage = new OutageLog(clock);
    }

    /** null, wenn abgeschaltet (gui.stadt-anzeige: false) oder keine brauchbare Adresse. */
    static HostReporter from(FileConfiguration c, URI llmUrl) {
        if (!c.getBoolean("gui.stadt-anzeige", true)) {
            return null;
        }
        URI target = target(c.getString("gui.url", ""), llmUrl);
        if (target == null) {
            LOG.warning("gui | adresse_ungültig | gui.url=" + c.getString("gui.url", "") + " – Stadtansicht aus");
            return null;
        }
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        Transport t = (u, json) -> http.sendAsync(HttpRequest.newBuilder(u)
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
        return new HostReporter(target, t, System::nanoTime);
    }

    /**
     * Ziel aus gui.url (ganze Adresse) oder aus llm.url: gleiches Schema, gleicher Host und Port, Pfad PATH. Nur http/
     * https mit Host – sonst null.
     */
    static URI target(String configured, URI llmUrl) {
        try {
            if (configured != null && !configured.isBlank()) {
                URI u = URI.create(configured.trim());
                return u.getHost() != null && ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) ? u
                        : null;
            }
            if (llmUrl == null || llmUrl.getHost() == null) {
                return null;
            }
            return new URI(llmUrl.getScheme(), null, llmUrl.getHost(), llmUrl.getPort(), PATH, null, null);
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            return null;
        }
    }

    static String newJob() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    void start(String job, String kind, String place, String world, int stages, int originX, int originZ) {
        JsonObject o = head("start", job);
        o.addProperty("kind", kind);
        o.addProperty("place", Texts.clean(place, 120));
        o.addProperty("world", world.length() > 64 ? world.substring(0, 64) : world);
        o.addProperty("stages", Math.max(1, Math.min(10_000, stages)));
        JsonArray origin = new JsonArray();
        origin.add(originX);
        origin.add(originZ);
        o.add("origin", origin);
        String json = o.toString();
        synchronized (this) {
            // Neuer Auftrag: Rückstau des alten ist für den Host wertlos (er verwirft fremde Aufträge ohnehin).
            queue.clear();
            pendingProgress = null;
            currentStart = json;
            currentStages.clear();
        }
        enqueue(json);
    }

    void stage(String job, int index, CityRaster.Raster r) {
        if (r == null) {
            return;
        }
        JsonObject o = head("stage", job);
        o.addProperty("index", index);
        o.addProperty("x0", r.x0());
        o.addProperty("z0", r.z0());
        o.addProperty("cell", r.cell());
        o.addProperty("w", r.w());
        o.addProperty("h", r.h());
        o.addProperty("cells", Base64.getEncoder().encodeToString(r.cells()));
        String json = o.toString();
        synchronized (this) {
            // Gleiche Grenze wie die Warteschlange: start + 63 Etappen, die ältesten fallen zuerst.
            if (currentStages.size() >= MAX_QUEUE - 1) {
                currentStages.pollFirst();
            }
            currentStages.addLast(json);
        }
        enqueue(json);
    }

    /** Höchstens einmal je Sekunde; dazwischen nichts (kein JSON gebaut). npc darf null sein. */
    void progress(String job, int stagesDone, int currentStage, long blocks, long planned, double rate,
            boolean paused, int[] npc) {
        long now = clock.getAsLong();
        synchronized (this) {
            if (now - lastProgress < PROGRESS_NANOS) {
                return;
            }
            lastProgress = now;
        }
        JsonObject o = head("progress", job);
        o.addProperty("stagesDone", stagesDone);
        o.addProperty("currentStage", currentStage);
        o.addProperty("blocks", blocks);
        o.addProperty("planned", planned);
        o.addProperty("rate", Double.isFinite(rate) ? rate : 0);
        o.addProperty("paused", paused);
        if (npc != null) {
            JsonArray a = new JsonArray();
            a.add(npc[0]);
            a.add(npc[1]);
            o.add("npc", a);
        }
        synchronized (this) {
            pendingProgress = o.toString();
        }
        pump();
    }

    static final int MAX_DONE = 4096;
    static final int MAX_TILE_QUEUE = 256;
    static final int MAX_PLAYERS = 64;

    /**
     * Kachelübersicht des Weltbaus (Nachricht „welt“, ohne Auftrag): Kacheln als Index {tx, tz}, current null = keine,
     * phase wartet | karte | planen | bereit | bauen, players als Blockkoordinaten {x, z}. Gekappt wie im Host (fertig
     * die jüngsten 4096, Warteschlange 256, Spieler 64). Gesendet wird nur, was sich geändert hat; zwischen zwei
     * Sendungen gewinnt die jüngste.
     */
    void world(String world, int tile, java.util.List<int[]> done, java.util.List<int[]> failed,
            java.util.List<int[]> queue, int[] current, String phase, boolean auto, java.util.List<int[]> players) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("event", "welt");
        o.addProperty("world", world.length() > 64 ? world.substring(0, 64) : world);
        o.addProperty("tile", tile);
        o.add("done", pairs(done, MAX_DONE, true));
        o.add("failed", pairs(failed, MAX_DONE, true));
        o.add("queue", pairs(queue, MAX_TILE_QUEUE, false));
        if (current != null) {
            o.add("current", pair(current));
        }
        o.addProperty("phase", phase);
        o.addProperty("auto", auto);
        o.add("players", pairs(players, MAX_PLAYERS, false));
        String json = o.toString();
        long now = clock.getAsLong();
        synchronized (this) {
            if (json.equals(lastWorld) && now - lastWorldAt < WORLD_REFRESH_NANOS) {
                return;
            }
            lastWorld = json;
            lastWorldAt = now;
            pendingWorld = json;
        }
        pump();
    }

    private static JsonArray pair(int[] p) {
        JsonArray a = new JsonArray();
        a.add(p[0]);
        a.add(p[1]);
        return a;
    }

    /** newest: bei Überlänge die letzten max behalten (jüngste fertige Kacheln), sonst die ersten. */
    private static JsonArray pairs(java.util.List<int[]> list, int max, boolean newest) {
        JsonArray a = new JsonArray();
        int from = newest ? Math.max(0, list.size() - max) : 0;
        int to = newest ? list.size() : Math.min(list.size(), max);
        for (int i = from; i < to; i++) {
            a.add(pair(list.get(i)));
        }
        return a;
    }

    void end(String job, String reason, long blocks) {
        JsonObject o = head("end", job);
        o.addProperty("reason", reason);
        o.addProperty("blocks", blocks);
        synchronized (this) {
            pendingProgress = null;  // nach dem Ende kein Fortschritt mehr
            currentStart = null;
            currentStages.clear();
        }
        enqueue(o.toString());
    }

    synchronized int dropped() {
        return dropped;
    }

    private static JsonObject head(String event, String job) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("event", event);
        o.addProperty("job", job);
        return o;
    }

    private void enqueue(String json) {
        synchronized (this) {
            if (queue.size() >= MAX_QUEUE) {
                queue.pollFirst();
                dropped++;
            }
            queue.addLast(json);
        }
        pump();
    }

    /** Nächste Nachricht senden, falls gerade keine unterwegs ist. Reihenfolge: Warteschlange vor Fortschritt. */
    private void pump() {
        String next;
        synchronized (this) {
            if (inFlight) {
                return;
            }
            next = queue.pollFirst();
            if (next == null && pendingWorld != null) {
                next = pendingWorld;
                pendingWorld = null;
            }
            if (next == null) {
                next = pendingProgress;
                pendingProgress = null;
            }
            if (next == null) {
                return;
            }
            inFlight = true;
        }
        final String sending = next;
        java.util.concurrent.CompletableFuture<Integer> f;
        try {
            f = transport.post(uri, sending);
        } catch (RuntimeException e) {
            f = java.util.concurrent.CompletableFuture.failedFuture(e);
        }
        f.whenComplete((status, err) -> {
            boolean failed = err != null || status == null || status >= 300;
            if (failed) {
                noteError(err != null ? err.getClass().getSimpleName() + ": " + err.getMessage() : "HTTP " + status);
            } else {
                long minutes = outage.success();
                if (minutes != OutageLog.UP) {
                    LOG.info(() -> "gui | host_wieder_da | ziel=" + uri + " ausfall_min=" + minutes);
                }
            }
            synchronized (this) {
                inFlight = false;
                if (failed) {
                    lost = true;
                } else if (lost) {
                    lost = false;
                    resend(sending);
                }
            }
            pump();
        });
    }

    /**
     * Nach einem Ausfall: start und bekannte Etappen des laufenden Auftrags vor den Rückstau, Übersicht neu. Etappen,
     * die noch in der Warteschlange stehen, kommen nicht doppelt; war die gelungene Sendung selbst der start, kennt der
     * Host den Auftrag schon. Nur unter this aufrufen.
     */
    private void resend(String delivered) {
        if (currentStart != null && !currentStart.equals(delivered)) {
            Deque<String> next = new ArrayDeque<>();
            next.add(currentStart);
            next.addAll(currentStages);
            for (String q : queue) {
                if (!next.contains(q)) {
                    next.add(q);
                }
            }
            while (next.size() > MAX_QUEUE) {
                // Überlauf: älteste Etappe nach dem start weg, wie in enqueue.
                String keep = next.pollFirst();
                next.pollFirst();
                next.addFirst(keep);
                dropped++;
            }
            queue.clear();
            queue.addAll(next);
        }
        if (pendingWorld == null && lastWorld != null) {
            pendingWorld = lastWorld;
        }
    }

    private void noteError(String why) {
        long minutes = outage.failure();
        if (minutes == OutageLog.UP) {
            return;
        }
        LOG.info(() -> "gui | host_nicht_erreichbar | ziel=" + uri + " grund=" + why
                + (minutes > 0 ? " seit_min=" + minutes : "") + " – reine Anzeige, der Bau läuft weiter");
    }
}
