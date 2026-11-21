package com.blackn0va.baumeister;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Sprachweg des Begleiters zum Baumeister-Host (Wunsch 2026-11-04).
 *
 * Schnittstelle Version 1, Gegenstück im Host: host/src/server/companion_api.cpp – beide Seiten gleich halten:
 *   POST &lt;Host aus llm.url&gt;/baumeister/v1/begleiter/sagen  {spieler, text}    – Host liest vor (TTS)
 *   GET  &lt;Host aus llm.url&gt;/baumeister/v1/begleiter/gehoert  – Long-Poll, Host hält bis 20 s,  umlaut-ok: URL ASCII
 *        mit ?imspiel=Name1,Name2&amp;begleiter=Name (heardUri)
 *        Antwort {saetze: [{spieler, text}]} (leer nach Ablauf)                        // umlaut-ok: JSON-Schlüssel ASCII
 * Mikrofon, Erkennung und Vorlesen laufen in der Host-App auf dem PC des Spielers; Ton verlässt den PC nie, über den
 * Tunnel geht nur Text.
 *
 * Fail-open wie HostReporter: Ohne Host bleibt der Begleiter im Chat voll nutzbar; geloggt wird höchstens einmal je
 * Minute. Der Long-Poll läuft in einer Schleife im HttpClient-Pool, nie im Hauptthread; nach einem Fehler wartet er
 * 5 s (sonst hämmerte er bei geschlossenem Tunnel im Kreis).
 *
 * Sätze aus „gehört“ nimmt der Long-Poll nur für Spieler an, die zur App im Kopf X-Baumeister-App gehören, sobald
 * fernverbindung.apps Einträge hat (VoiceAppBinding, Plan A4 2026-11-08). Verworfenes steht im Log, höchstens eine
 * Zeile je Minute samt Anzahl – eine fremde App, die im Kreis antwortet, schreibt das Log sonst voll.
 *
 * Threads: say aus dem Hauptthread, Abschluss und Long-Poll im HttpClient-Pool; erkannte Sätze gehen über heard an den
 * Aufrufer, der sie selbst auf den Hauptthread bringt. Fehler: wirft nicht.
 */
final class CompanionVoiceLink {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final String SAY_PATH = "/baumeister/v1/begleiter/sagen";
    static final String HEARD_PATH = "/baumeister/v1/begleiter/gehoert";  // umlaut-ok: URL-Pfad ASCII
    // Simple Voice Chat (2026-11-04): Ton aus dem Spiel erkennen lassen, Stimme als Ton fürs Spiel holen.
    static final String HEAR_PATH = "/baumeister/v1/begleiter/hoeren";  // umlaut-ok: URL-Pfad ASCII
    static final String VOICE_PATH = "/baumeister/v1/begleiter/stimme";
    private static final long ERROR_LOG_NANOS = 60_000_000_000L;
    private static final long RETRY_MS = 5_000;

    private final URI sayUri;
    private final URI heardUri;
    private final URI hearUri;
    private final URI voiceUri;
    private final HttpClient http;
    private final LongSupplier clock;
    private volatile long lastErrorLog = Long.MIN_VALUE / 2;
    /** Host gar nicht erreichbar (Verbindungsfehler): Zustandswechsel statt Minutentakt im Log (OutageLog). */
    private final OutageLog outage;
    private volatile boolean running;
    /** Wer über „gehört“ sprechen darf (fernverbindung.apps); ungebunden = wie vor Plan A4. */
    private final VoiceAppBinding binding;
    private volatile long lastDropLog = Long.MIN_VALUE / 2;
    private final AtomicInteger droppedSinceLog = new AtomicInteger();
    /** begleiter.name für den Vortext der Erkennung in der App (heardUri, begleiter=). */
    private final String companionName;

    CompanionVoiceLink(URI base, HttpClient http, LongSupplier clock, VoiceAppBinding binding) {
        this(base, http, clock, binding, CompanionSettings.DEFAULT_NAME);
    }

    CompanionVoiceLink(URI base, HttpClient http, LongSupplier clock, VoiceAppBinding binding, String companionName) {
        this.companionName = companionName;
        this.sayUri = base.resolve(SAY_PATH);
        this.heardUri = base.resolve(HEARD_PATH);
        this.hearUri = base.resolve(HEAR_PATH);
        this.voiceUri = base.resolve(VOICE_PATH);
        this.http = http;
        this.clock = clock;
        this.outage = new OutageLog(clock);
        this.binding = binding;
    }

    /** null, wenn begleiter.sprache aus ist oder keine Adresse feststeht. */
    static CompanionVoiceLink from(FileConfiguration c, URI llmUrl) {
        if (!c.getBoolean("begleiter.sprache", true)) {
            return null;
        }
        URI base = HostReporter.target(c.getString("gui.url", ""), llmUrl);
        if (base == null) {
            return null;
        }
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        VoiceAppBinding binding = VoiceAppBinding.from(c);
        if (!binding.bound()) {
            // Einmal je Start bzw. /bm neuladen: Ohne Einträge gilt wie über den SSH-Tunnel jeder Satz für den Spieler,
            // den die App nennt (VoiceAppBinding, Kopf).
            LOG.info(() -> "begleiter | sprache_ohne_app_bindung | fernverbindung.apps leer – Sätze aus der App gelten "
                    + "für den Spieler, den sie nennt (Betrieb über SSH-Tunnel)");
        } else if (binding.describe().equals("-")) {
            LOG.warning(() -> "begleiter | sprache_an_apps_gebunden | apps=- – kein gültiger Eintrag unter "
                    + "fernverbindung.apps, jeder Satz aus einer App wird verworfen");
        } else {
            LOG.info(() -> "begleiter | sprache_an_apps_gebunden | apps=" + binding.describe());
        }
        // Name aus derselben Prüfung wie für den Begleiter selbst (CompanionSettings) – /bm neuladen ruft from()
        // erneut, ein umbenannter Begleiter kommt so mit dem nächsten Abholen in der App an.
        return new CompanionVoiceLink(base, http, System::nanoTime, binding, CompanionSettings.from(c).name());
    }

    /** Vorlesen lassen – der Host spricht nur, wenn spieler der dort eingetragene Name ist. */
    void say(String player, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("spieler", player);
        o.addProperty("text", text);
        try {
            http.sendAsync(HttpRequest.newBuilder(sayUri)
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(o.toString(), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.discarding())
                    .whenComplete((r, err) -> {
                        if (err != null) {
                            noteError("sagen", err.getClass().getSimpleName());
                            return;
                        }
                        noteReached();
                        if (r.statusCode() != 200) {
                            noteError("sagen", "http_" + r.statusCode());
                        }
                    });
        } catch (RuntimeException e) {
            // Bewusst fail-open (siehe Kopf): Vorlesen ist Zugabe, der Chat hat den Text schon.
            noteError("sagen", e.getClass().getSimpleName());
        }
    }

    /**
     * Satz aus Simple Voice Chat erkennen lassen (PCM 16 kHz, 16 bit, mono, little-endian). Ergebnis: Text, "" = nichts
     * verstanden, leer (Optional.empty) = Host aus oder Erkennung nicht eingeschaltet. Wirft nie.
     */
    java.util.concurrent.CompletableFuture<java.util.Optional<String>> hear(String player, byte[] pcm16k) {
        URI uri = URI.create(hearUri + "?spieler=" + java.net.URLEncoder.encode(player, StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pcm16k)).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(r -> {
                    noteReached();
                    if (r.statusCode() != 200) {
                        noteError("hören", "http_" + r.statusCode());
                        return java.util.Optional.<String>empty();
                    }
                    try {
                        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                        return java.util.Optional.of(o.get("text").getAsString());
                    } catch (RuntimeException e) {
                        noteError("hören", "antwort_unlesbar");
                        return java.util.Optional.<String>empty();
                    }
                })
                .exceptionally(err -> {
                    noteError("hören", err.getClass().getSimpleName());
                    return java.util.Optional.empty();
                });
    }

    /** Stimme als Ton fürs Spiel: PCM 48 kHz mono als Samples, leer = Host aus (dann bleibt es beim Chat). Wirft nie. */
    java.util.concurrent.CompletableFuture<java.util.Optional<short[]>> voice(String player, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("spieler", player);
        o.addProperty("text", text);
        HttpRequest req = HttpRequest.newBuilder(voiceUri)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(o.toString(), StandardCharsets.UTF_8)).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(r -> {
                    noteReached();
                    if (r.statusCode() != 200) {
                        noteError("stimme", "http_" + r.statusCode());
                        return java.util.Optional.<short[]>empty();
                    }
                    return java.util.Optional.of(
                            com.blackn0va.baumeister.companion.VoiceCapture.fromLittleEndian(r.body()));
                })
                .exceptionally(err -> {
                    noteError("stimme", err.getClass().getSimpleName());
                    return java.util.Optional.empty();
                });
    }

    /** Spieler, die gerade über Simple Voice Chat sprechen (für ?imspiel=); ohne Aufruf von inGame niemand. */
    private volatile java.util.function.Supplier<java.util.Collection<String>> inGame = java.util.List::of;

    void inGame(java.util.function.Supplier<java.util.Collection<String>> names) {
        this.inGame = names;
    }

    /**
     * Abhol-Adresse mit den Spielern im Voice-Chat: „…/gehoert?imspiel=Blackkn0va,Steve“. Wunsch 2026-11-04 – vorher
     * nahm die App-Taste (Vorgabe V, dieselbe
     * Taste wie das Menü von Simple Voice Chat) parallel auf, und jeder Satz kam doppelt an. Mit dieser Liste lässt die
     * App ihre Taste ruhen. Nur gültige Namen, höchstens 32 (Adresse bleibt kurz).  umlaut-ok: URL ASCII
     *
     * Dazu „begleiter=Name“ (Wunsch 2026-11-20, AK-4): Vorher stand im Vortext der Erkennung fest „Vera“, ein anderer
     * begleiter.name wurde schlechter erkannt, und ohne erkannten Namen nimmt das Plugin die Anrede nicht an. Kodiert
     * mit URLEncoder (Leerzeichen „+“, Umlaute %C3%xx) – beides lassen Relay und App durch (docs/kanal-v1.md,
     * Abschnitt 5). Ein Name außerhalb von CompanionSettings.NAME fällt weg, die App bleibt dann bei „Vera“.
     */
    static URI heardUri(URI heard, java.util.Collection<String> names, String companion) {
        String list = names.stream().filter(n -> n != null && n.matches("[A-Za-z0-9_]{3,16}")).limit(32)
                .collect(java.util.stream.Collectors.joining(","));
        java.util.List<String> query = new java.util.ArrayList<>(2);
        if (!list.isEmpty()) {
            query.add("imspiel=" + list);
        }
        if (companion != null && CompanionSettings.NAME.matcher(companion).matches()) {
            query.add("begleiter=" + java.net.URLEncoder.encode(companion, StandardCharsets.UTF_8));
        }
        return query.isEmpty() ? heard : URI.create(heard + "?" + String.join("&", query));
    }

    /** Long-Poll starten; heard bekommt (spieler, text) im HttpClient-Pool. */
    void listen(BiConsumer<String, String> heard) {
        running = true;
        poll(heard);
    }

    void stop() {
        running = false;
    }

    private void poll(BiConsumer<String, String> heard) {
        if (!running) {
            return;
        }
        HttpRequest req = HttpRequest.newBuilder(heardUri(heardUri, inGame.get(), companionName))
                .timeout(Duration.ofSeconds(35))
                .GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((r, err) -> {
                    boolean ok = err == null && r.statusCode() == 200;
                    if (err == null) {
                        noteReached();
                    }
                    if (ok) {
                        accept(r.body(), r.headers().allValues(VoiceAppBinding.HEADER), heard);
                    } else {
                        noteError("gehört", err != null ? err.getClass().getSimpleName() : "http_" + r.statusCode());
                    }
                    if (ok) {
                        poll(heard);
                    } else {
                        java.util.concurrent.CompletableFuture.runAsync(() -> poll(heard),
                                java.util.concurrent.CompletableFuture.delayedExecutor(RETRY_MS,
                                        java.util.concurrent.TimeUnit.MILLISECONDS));
                    }
                });
    }

    /**
     * Sätze einer gehört-Antwort an heard – nur die, die zur App im Kopf gehören (VoiceAppBinding.check). Der Kopf
     * kommt vom Relay, nicht aus dem Body: Die App selbst kann sich so keinen anderen Namen geben.
     *
     * @param app alle Werte des Kopfes X-Baumeister-App
     */
    void accept(String body, List<String> app, BiConsumer<String, String> heard) {
        deliver(body, (player, text) -> {
            VoiceAppBinding.Verdict v = binding.check(app, player);
            if (v == VoiceAppBinding.Verdict.ACCEPT) {
                heard.accept(player, text);
            } else {
                noteDropped(player, app, v);
            }
        });
    }

    /**
     * Verworfener Satz: sofort eine Zeile, danach höchstens eine je Minute mit der Zahl der verworfenen seit der
     * letzten. Spieler- und App-Name nur, wenn sie wie Namen aussehen – beide stammen von außen (ASVS V7.3.1, keine
     * Log-Injektion). Den Text des Satzes nie: Er gehört dem, der gesprochen hat.
     */
    private void noteDropped(String player, List<String> app, VoiceAppBinding.Verdict v) {
        int count = droppedSinceLog.incrementAndGet();
        long now = clock.getAsLong();
        if (now - lastDropLog < ERROR_LOG_NANOS) {
            return;
        }
        lastDropLog = now;
        droppedSinceLog.set(0);
        String who = player != null && VoiceAppBinding.PLAYER_NAME.matcher(player).matches() ? player : "ungültig";
        String from = VoiceAppBinding.logName(app);
        LOG.warning(() -> "begleiter | gehört_verworfen | spieler=" + who + " app=" + from + " grund=" + v.code
                + " anzahl=" + count + " – nur Spieler dieser App (fernverbindung.apps)");
    }

    /** {saetze: [{spieler, text}]} – fremde oder kaputte Einträge fallen still weg (Host ist eigener Prozess). umlaut-ok */
    static void deliver(String body, BiConsumer<String, String> heard) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("saetze")) {  // umlaut-ok: JSON-Schlüssel ASCII
                return;
            }
            JsonArray list = root.getAsJsonObject().getAsJsonArray("saetze");  // umlaut-ok: JSON-Schlüssel ASCII
            for (int i = 0; i < Math.min(8, list.size()); i++) {
                if (!list.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject o = list.get(i).getAsJsonObject();
                if (o.has("spieler") && o.has("text") && o.get("spieler").isJsonPrimitive()
                        && o.get("text").isJsonPrimitive()) {
                    String text = o.get("text").getAsString().trim();
                    if (!text.isEmpty() && text.length() <= 400) {
                        heard.accept(o.get("spieler").getAsString(), text);
                    }
                }
            }
        } catch (RuntimeException e) {
            // Kaputtes JSON vom Host: Satz verloren, nächster Poll läuft weiter (fail-open, reine Eingabehilfe).
            LOG.info(() -> "begleiter | gehört_unlesbar | grund=" + e.getClass().getSimpleName());
        }
    }

    /**
     * Verbindungsfehler (Host aus) über OutageLog: eine Zeile beim Ausfall, dann alle 15 min. Antwortet der Host mit
     * einem Fehlercode („http_503“: Erkennung belegt), bleibt es beim Minutentakt – das ist kein Ausfall, und es soll
     * den Zustand von OutageLog nicht zwischen „weg“ und „da“ springen lassen.
     */
    private void noteError(String what, String why) {
        if (!why.startsWith("http_") && !why.equals("antwort_unlesbar")) {
            long minutes = outage.failure();
            if (minutes != OutageLog.UP) {
                LOG.info(() -> "begleiter | host_nicht_erreichbar | weg=" + what + " grund=" + why
                        + (minutes > 0 ? " seit_min=" + minutes : "") + " – Chat geht weiter, nur ohne Sprache");
            }
            return;
        }
        long now = clock.getAsLong();
        if (now - lastErrorLog < ERROR_LOG_NANOS) {
            return;
        }
        lastErrorLog = now;
        LOG.info(() -> "begleiter | host_fehler | weg=" + what + " grund=" + why);
    }

    private void noteReached() {
        long minutes = outage.success();
        if (minutes != OutageLog.UP) {
            LOG.info(() -> "begleiter | host_wieder_da | ausfall_min=" + minutes);
        }
    }
}
