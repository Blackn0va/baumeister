package com.blackn0va.baumeister;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Der Server spricht selbst – Piper auf dem Server statt der Stimme aus der App (Wunsch 2026-11-08). Verity JE spricht
 * mit der öffentlichen Piper-Stimme en_US-ryan-medium (Modellkarte: CC BY-NC-SA 4.0); welche
 * Stimme gilt, steht in sprache.stimme – das Plugin bringt keine mit.
 *
 * Gemessen 2026-11-08 auf dev01 (Celeron J4125): 8 s Sprache in 2,6 s samt Laden des Modells, RAM-Spitze 153 MB je
 * Satz – also rund dreimal schneller als Echtzeit. Deshalb je Satz ein eigener Prozess (kein dauerhaft belegter
 * Speicher) mit nice 19, damit der Server-Takt vorgeht, und ein Zwischenspeicher je Stimme: Was einmal gesagt wurde
 * („Ich fange an!“, Farm-Anleitungen), kommt beim nächsten Mal sofort.
 *
 * Sicherheit (OWASP A03): Der Text geht nur über stdin an Piper, nie in die Kommandozeile; Pfade kommen allein aus der
 * Config. Länge gekappt (sprache.max-zeichen), Prozess nach 30 s beendet, Ausgabe höchstens 8 MB.
 *
 * Threads: ein eigener Faden für Piper und Dateien; voice() gibt sofort ein Future zurück. Fehler: nie nach außen –
 * leeres Ergebnis, Grund einmal im Log (dann bleibt es beim Chat bzw. der App).
 */
final class LocalVoice {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final int OUT_RATE = 48_000;
    private static final long TIMEOUT_SECONDS = 30;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** Vera als Cartoon-Kumpel: Tonhöhe, die „cartoon“ in /bm sprache ton setzt. */
    static final double CARTOON = 1.25;

    /**
     * Einstellungen aus sprache.*. pitch: Tonhöhe als Faktor (1 = Stimme wie aufgenommen). Höher klingt nach
     * Zeichentrick: Die Samples laufen um pitch schneller, und Piper spricht dafür gedehnt (lengthScale), damit das
     * Tempo nur leicht steigt statt um den ganzen Faktor.
     */
    record Settings(boolean enabled, String piper, String model, boolean preferServer, boolean builder, int maxChars,
            long cacheBytes, double pitch) {

        Settings(boolean enabled, String piper, String model, boolean preferServer, boolean builder, int maxChars,
                long cacheBytes) {
            this(enabled, piper, model, preferServer, builder, maxChars, cacheBytes, 1.0);
        }

        /** Stimme des Baumeisters (sprache.stimme, sprache.tonhöhe). */
        static Settings from(ConfigurationSection root) {
            if (root == null) {
                return new Settings(false, "", "", true, true, 400, 300L << 20);
            }
            return new Settings(root.getBoolean("sprache.an", false),
                    root.getString("sprache.piper", "").strip(), root.getString("sprache.stimme", "").strip(),
                    !"app".equalsIgnoreCase(root.getString("sprache.quelle", "server").strip()),
                    root.getBoolean("sprache.baumeister", true),
                    Math.clamp(root.getInt("sprache.max-zeichen", 400), 20, 1000),
                    Math.clamp(root.getLong("sprache.speicher-mb", 300), 0, 10_000) << 20,
                    pitchOf(root.getDouble("sprache.tonhöhe", 1.0)));
        }

        /**
         * Veras Stimme: sprache.vera-stimme (leer = wie der Baumeister) und sprache.vera-tonhöhe. Eigene Stimme, weil
         * ein Modell für beide entweder den Baumeister zur Zeichentrickfigur oder Vera zum Nachrichtensprecher macht.
         */
        static Settings forVera(ConfigurationSection root) {
            Settings s = from(root);
            if (root == null) {
                return s;
            }
            String vera = root.getString("sprache.vera-stimme", "").strip();
            return new Settings(s.enabled(), s.piper(), vera.isEmpty() ? s.model() : vera, s.preferServer(),
                    s.builder(), s.maxChars(), s.cacheBytes(), pitchOf(root.getDouble("sprache.vera-tonhöhe",
                            CARTOON)));
        }

        static double pitchOf(double v) {
            return Double.isFinite(v) ? Math.clamp(v, 0.7, 1.8) : 1.0;
        }

        /** Dehnung für Piper: bei höherer Tonhöhe langsamer sprechen, netto etwa 10 % schneller als normal. */
        double lengthScale() {
            return pitch <= 1.0 ? 1.0 : pitch / 1.1;
        }

        /** Grund, warum es nicht geht, oder leer. */
        Optional<String> problem() {
            if (piper.isEmpty() || !Files.isExecutable(Path.of(piper))) {
                return Optional.of("sprache.piper zeigt auf kein ausführbares Piper (" + piper + ")");
            }
            if (model.isEmpty() || !Files.isRegularFile(Path.of(model))
                    || !Files.isRegularFile(Path.of(model + ".json"))) {
                return Optional.of("sprache.stimme: Modell oder " + model + ".json fehlt");
            }
            return Optional.empty();
        }
    }

    /** Text → rohe 16-Bit-Samples (little endian) in der Rate des Modells. Austauschbar für Tests. */
    @FunctionalInterface
    interface Engine {
        byte[] synthesize(String text) throws IOException;
    }

    private final Settings settings;
    private final Engine engine;
    private final int rate;
    private final Path cache;
    private final ExecutorService worker;
    private final AtomicInteger writes = new AtomicInteger();
    private volatile String lastError = "";

    private LocalVoice(Settings settings, Engine engine, int rate, Path cache) {
        this.settings = settings;
        this.engine = engine;
        this.rate = rate;
        this.cache = cache;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Baumeister-Stimme");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, e) -> LOG.log(Level.SEVERE, "sprache | thread_fehler", e));
            return t;
        });
    }

    /** Aus der Config; leer, wenn aus oder unbrauchbar (Grund im Log). */
    static Optional<LocalVoice> open(Settings s, Path dataFolder) {
        if (!s.enabled()) {
            return Optional.empty();
        }
        Optional<String> problem = s.problem();
        if (problem.isPresent()) {
            LOG.warning(() -> "sprache | aus | grund=" + problem.get());
            return Optional.empty();
        }
        int rate = sampleRate(Path.of(s.model() + ".json"));
        // Eigener Zwischenspeicher je Tonhöhe: Piper spricht bei höherer Tonhöhe gedehnt, die Rohdaten unterscheiden sich.
        String voice = Path.of(s.model()).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_")
                + (s.pitch() == 1.0 ? "" : String.format(java.util.Locale.ROOT, "_t%.2f", s.pitch()));
        Path cache = dataFolder.resolve("sprache").resolve(voice);
        LOG.info(() -> "sprache | bereit | stimme=" + voice + " rate=" + rate + " tonhöhe=" + s.pitch() + " quelle="
                + (s.preferServer() ? "server" : "app"));
        return Optional.of(new LocalVoice(s, piperEngine(s), rate, cache));
    }

    /** Für Tests: mit eigener Engine und Rate. */
    static LocalVoice forTest(Settings s, Engine engine, int rate, Path cache) {
        return new LocalVoice(s, engine, rate, cache);
    }

    Settings settings() {
        return settings;
    }

    /** Stimme für einen Satz: PCM 48 kHz mono, leer bei Fehler. Wirft nie. */
    CompletableFuture<Optional<short[]>> voice(String text) {
        String t = speakable(text, settings.maxChars());
        if (t.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] raw = cached(t);
                // Tonhöhe: als käme es mit pitch-facher Rate – kürzer und höher (Zeichentrick-Klang).
                return Optional.of(resample(raw, (int) Math.round(rate * settings.pitch()), OUT_RATE));
            } catch (IOException | RuntimeException e) {
                String why = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (!why.equals(lastError)) {
                    lastError = why;
                    LOG.warning(() -> "sprache | fehler | grund=" + why);
                }
                return Optional.<short[]>empty();
            }
        }, worker);
    }

    void stop() {
        worker.shutdownNow();
        ExecutorService p = prewarmer;
        if (p != null) {
            p.shutdownNow();
        }
    }

    private volatile ExecutorService prewarmer;
    private final AtomicInteger prewarmDone = new AtomicInteger();
    private volatile int prewarmTotal;

    /** Stand des Vorrats: „fertig/gesamt“, leer wenn keiner läuft oder lief. */
    String prewarmState() {
        return prewarmTotal == 0 ? "" : prewarmDone.get() + "/" + prewarmTotal;
    }

    /**
     * Feste Sätze vorab sprechen (sprache-vorrat.txt): Dann kommen sie sofort aus dem Zwischenspeicher – auch ohne
     * LLM, denn ohne Modell antworten Vera und der Baumeister nur mit diesen Sätzen. Eigener Faden mit niedrigster
     * Priorität, Piper mit nice 19; Live-Sätze laufen über worker und warten nie hinter dem Vorrat. Schon
     * gesprochene Sätze kosten nur einen Dateiblick.
     */
    void prewarm(List<String> lines) {
        if (lines.isEmpty() || settings.cacheBytes() <= 0) {
            return;
        }
        ExecutorService p = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Baumeister-Stimme-Vorrat");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.setUncaughtExceptionHandler((th, e) -> LOG.log(Level.SEVERE, "sprache | vorrat_fehler", e));
            return t;
        });
        prewarmer = p;
        prewarmTotal = lines.size();
        prewarmDone.set(0);
        p.execute(() -> {
            long t0 = System.nanoTime();
            int fresh = 0;
            int failed = 0;
            for (String line : lines) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                String t = speakable(line, settings.maxChars());
                try {
                    if (!t.isEmpty() && !Files.isRegularFile(cache.resolve(key(t) + ".raw"))) {
                        cached(t);
                        fresh++;
                    }
                } catch (IOException | RuntimeException e) {
                    failed++;
                }
                int done = prewarmDone.incrementAndGet();
                if (done % 100 == 0) {
                    LOG.info("sprache | vorrat | stimme=" + cache.getFileName() + " stand=" + done + "/" + lines.size());
                }
            }
            long s = (System.nanoTime() - t0) / 1_000_000_000L;
            int f = fresh;
            int x = failed;
            LOG.info(() -> "sprache | vorrat_fertig | stimme=" + cache.getFileName() + " sätze=" + lines.size()
                    + " neu=" + f + " fehler=" + x + " dauer_s=" + s);
        });
    }

    private byte[] cached(String text) throws IOException {
        Path file = cache.resolve(key(text) + ".raw");
        if (Files.isRegularFile(file)) {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return Files.readAllBytes(file);
        }
        long t0 = System.nanoTime();
        byte[] raw = engine.synthesize(text);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.fine(() -> "sprache | gesprochen | zeichen=" + text.length() + " ms=" + ms + " audio_ms="
                + raw.length * 500L / rate);
        if (settings.cacheBytes() > 0 && raw.length > 0) {
            Files.createDirectories(cache);
            // Eigener Name je Faden: Vorrat (prewarm) und Live-Satz können denselben Satz gleichzeitig schreiben.
            Path tmp = cache.resolve(key(text) + "." + Thread.currentThread().threadId() + ".neu");
            Files.write(tmp, raw);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (writes.incrementAndGet() % 25 == 0) {
                trim();
            }
        }
        return raw;
    }

    /** Älteste Sätze löschen, bis der Speicher unter sprache.speicher-mb liegt. */
    void trim() throws IOException {
        if (!Files.isDirectory(cache)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(cache)) {
            files = new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".raw")).toList());
        }
        long total = 0;
        for (Path p : files) {
            total += Files.size(p);
        }
        files.sort(Comparator.comparingLong(p -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));
        for (Path p : files) {
            if (total <= settings.cacheBytes()) {
                break;
            }
            total -= Files.size(p);
            Files.deleteIfExists(p);
        }
    }

    /** Text so, wie er gesprochen werden soll: ohne Farbcodes und Befehle, gekappt an einer Wortgrenze. */
    static String speakable(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                // „/bm vorschau“ fällt weg, der Satz danach bleibt (vorher fraß die Regel jedes kleine Wort danach).
                .replaceAll("(?<!\\S)/[a-zäöü]+(?: [a-zäöü]+)?", " ")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").strip();
        // Aussprache wie im Host (SpeechText): Krieper statt Kreeper, keine Zeichennamen, Abkürzungen ausgeschrieben.
        t = SpeechText.forSpeech(t);
        if (t.length() > maxChars) {
            int cut = t.lastIndexOf(' ', maxChars);
            t = t.substring(0, cut > maxChars / 2 ? cut : maxChars).strip();
        }
        return t;
    }

    static String key(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 fehlt", e);
        }
    }

    /** 16 Bit little endian in Rate from → Samples in Rate to (linear, reicht für Sprache). */
    static short[] resample(byte[] raw, int from, int to) {
        int n = raw.length / 2;
        if (n == 0) {
            return new short[0];
        }
        short[] in = new short[n];
        for (int i = 0; i < n; i++) {
            in[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
        }
        if (from == to) {
            return in;
        }
        int m = (int) ((long) n * to / from);
        short[] out = new short[m];
        for (int i = 0; i < m; i++) {
            double pos = (double) i * from / to;
            int a = (int) pos;
            int b = Math.min(a + 1, n - 1);
            double f = pos - a;
            out[i] = (short) Math.round(in[a] * (1 - f) + in[b] * f);
        }
        return out;
    }

    /** audio.sample_rate aus der Modell-JSON von Piper, sonst 22050. */
    static int sampleRate(Path json) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(Files.readString(json))
                    .getAsJsonObject();
            return o.getAsJsonObject("audio").get("sample_rate").getAsInt();
        } catch (IOException | RuntimeException e) {
            return 22_050;
        }
    }

    /** Piper als eigener Prozess: Text über stdin, rohe Samples über stdout, unter Linux mit nice 19. */
    private static Engine piperEngine(Settings s) {
        return text -> {
            List<String> cmd = new ArrayList<>();
            if (Files.isExecutable(Path.of("/usr/bin/nice"))) {
                cmd.addAll(List.of("/usr/bin/nice", "-n", "19"));
            }
            cmd.addAll(List.of(s.piper(), "--model", s.model(), "--output_raw"));
            if (s.lengthScale() != 1.0) {
                cmd.addAll(List.of("--length_scale", String.format(java.util.Locale.ROOT, "%.3f", s.lengthScale())));
            }
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (OutputStream in = p.getOutputStream()) {
                in.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            }
            byte[] out;
            try (InputStream o = p.getInputStream()) {
                out = o.readNBytes(MAX_BYTES);
            }
            try {
                if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("Piper brauchte länger als " + TIMEOUT_SECONDS + " s");
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("unterbrochen", e);
            }
            if (p.exitValue() != 0) {
                throw new IOException("Piper endete mit " + p.exitValue());
            }
            return out;
        };
    }
}
