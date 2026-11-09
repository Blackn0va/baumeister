package com.blackn0va.baumeister;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Das Modpaket direkt vom Server (Wunsch 2026-11-08 – gewählt: „Plugin liefert selbst aus … aber alle 6 mods“).
 *
 * Vorher zeigte /mods nur die Liste und „gibt dir der Admin“, oder einen festen Link aus mods.link, der irgendwo liegen
 * musste. Jetzt bekommt jeder Spieler mit /mods einen eigenen Link (24 h gültig) auf eine Seite dieses kleinen Servers,
 * die genau die Dateien aus plugins/Baumeister/modpaket/ anbietet, die tools/modpaket/Modpaket-Erstellen.ps1 dort ablegt:
 * das .mrpack und das ZIP mit allen Jars (auch dem Baumeister-Client). Ohne gültigen Link gibt es nichts – das Paket
 * enthält fremde Mods, deren Lizenzen eine öffentliche Weitergabe oft nicht erlauben.
 *
 * Sicherheit (OWASP A01/A05, ASVS V12.3.1, V13.1): nur GET/HEAD auf genau /m/&lt;token&gt; und /m/&lt;token&gt;/&lt;datei&gt;, Datei
 * nur aus der festen Liste im Ordner (keine Pfade aus der Anfrage), Token 128 Bit aus SecureRandom, unbekannt und
 * abgelaufen beide 404 (kein Orakel), Anfrage höchstens 8 KiB und 10 s, höchstens 4 Verbindungen gleichzeitig und 2 je
 * Adresse, jede Verbindung spätestens nach 15 min zu (langsame Leser). Kein Token im Log. Nur HTTP: Ohne Zertifikat
 * warnt der Browser einmal; der Nutzer hat das so gewählt. Die Mods von Modrinth prüft der Launcher im .mrpack per
 * SHA-512, den Baumeister-Client nicht.
 *
 * HTTPS (Wunsch 2026-11-08): Stehen unter mods.download.zertifikat/-schlüssel die
 * PEM-Dateien von certbot (Let's Encrypt), läuft derselbe Port mit TLS 1.3/1.2 und die Links beginnen mit https. Die
 * Uhr sieht jede Minute nach: Taucht das Zertifikat auf oder wird es verlängert, bindet der Server den Port neu – ohne
 * Neustart. Fehlt es oder ist es kaputt, bleibt es bei http (Warnung im Log), statt den Download ganz abzuschalten.
 *
 * Aus (Vorgabe): mods.download.an: false. Ein fremder Server öffnet keinen Port, den er nicht will.
 *
 * Threads: Annahme in einem Daemon-Thread, Antworten in einem Pool aus 4 Threads, Links vom Hauptthread
 * (ConcurrentHashMap). Fehler: Port belegt → Warnung im Log, /mods fällt auf mods.link bzw. „gibt dir der Admin“ zurück.
 */
final class ModDownloads {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Gültigkeit eines Links. */
    static final long TTL_MILLIS = TimeUnit.HOURS.toMillis(24);
    /** Gleichzeitige Verbindungen insgesamt (Pool) und je Adresse. */
    static final int WORKERS = 4;
    static final int PER_ADDRESS = 2;
    /** Kopf der Anfrage höchstens so groß und so langsam. */
    static final int MAX_HEAD = 8 * 1024;
    static final int READ_TIMEOUT_MS = 10_000;
    /** Spätestens dann wird eine Verbindung geschlossen (40 MB bei 50 kB/s ≈ 13 min). */
    static final long MAX_CONNECTION_MILLIS = TimeUnit.MINUTES.toMillis(15);
    /** Gültige Links höchstens (danach fallen die ältesten weg). */
    static final int MAX_GRANTS = 2000;

    /** Die Dateien, die das Skript schreibt – und nur diese. */
    static final Pattern FILE = Pattern.compile("Baumeisterland-(\\d+(?:\\.\\d+){1,2})(\\.mrpack|-mods\\.zip)");
    private static final Pattern REQUEST = Pattern.compile(
            "(GET|HEAD) /m/([A-Za-z0-9_-]{22})(?:/([A-Za-z0-9.+_-]{1,80}))? HTTP/1\\.[01]");

    /** Einstellungen aus mods.download.*. */
    record Settings(boolean enabled, String address, int port, String bind, String cert, String key) {

        Settings(boolean enabled, String address, int port, String bind) {
            this(enabled, address, port, bind, "", "");
        }

        static Settings from(ConfigurationSection root) {
            if (root == null) {
                return new Settings(false, "", 25581, "0.0.0.0");
            }
            String address = root.getString("mods.download.adresse", "").strip();
            int port = root.getInt("mods.download.port", 25581);
            boolean on = root.getBoolean("mods.download.an", false);
            return new Settings(on, address, port, root.getString("mods.download.binden", "0.0.0.0").strip(),
                    root.getString("mods.download.zertifikat", "").strip(),
                    root.getString("mods.download.schlüssel", "").strip());
        }

        boolean wantsTls() {
            return !cert.isEmpty() && !key.isEmpty();
        }

        /** Grund, warum es nicht geht, oder leer. */
        Optional<String> problem() {
            if (!enabled) {
                return Optional.empty();
            }
            if (!address.matches("[A-Za-z0-9.-]{1,253}")) {
                return Optional.of("mods.download.adresse fehlt oder ist kein Name/IPv4 (z. B. mein-server.ddns.net)");
            }
            if (port < 1024 || port > 65535) {
                return Optional.of("mods.download.port " + port + " liegt nicht zwischen 1024 und 65535");
            }
            return Optional.empty();
        }
    }

    /** Ein Link: für wen, bis wann. */
    record Grant(UUID player, String name, long expires) {
    }

    /** Eine Datei der Seite. */
    record Offer(String name, Path path, long size) {
    }

    private final Settings settings;
    private final Path folder;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final Map<InetAddress, AtomicInteger> perAddress = new ConcurrentHashMap<>();
    private volatile ServerSocket server;
    /** Läuft der Port gerade mit TLS? Dann https-Links. */
    private volatile boolean tls;
    /** Änderungszeit und Größe beider PEM-Dateien beim letzten Binden („“ = keine). */
    private String tlsStamp = "";
    private ThreadPoolExecutor pool;
    private ScheduledExecutorService reaper;

    ModDownloads(Settings settings, Path folder) {
        this(settings, folder, System::currentTimeMillis);
    }

    ModDownloads(Settings settings, Path folder, LongSupplier clock) {
        this.settings = settings;
        this.folder = folder;
        this.clock = clock;
    }

    /** Startet den Server, wenn eingeschaltet. false = aus oder gescheitert (Grund im Log). */
    synchronized boolean start() {
        if (!settings.enabled()) {
            return false;
        }
        Optional<String> problem = settings.problem();
        if (problem.isPresent()) {
            LOG.warning(() -> "mods | download_aus | grund=" + problem.get());
            return false;
        }
        pool = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(WORKERS),
                daemon("Baumeister-Mods"));
        reaper = Executors.newSingleThreadScheduledExecutor(daemon("Baumeister-Mods-Uhr"));
        try {
            bind(tlsContext());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "mods | download_aus | port=" + settings.port() + " grund=" + e.getMessage(), e);
            pool.shutdownNow();
            reaper.shutdownNow();
            return false;
        }
        if (settings.wantsTls()) {
            reaper.scheduleWithFixedDelay(this::watchCertificate, 60, 60, TimeUnit.SECONDS);
        }
        LOG.info(() -> "mods | download_bereit | adresse=" + settings.address() + " port=" + settings.port()
                + " tls=" + tls + " dateien=" + offers().stream().map(Offer::name).toList());
        return true;
    }

    /** SSLContext aus den PEM-Dateien, oder null (kein TLS gewollt, Dateien fehlen oder sind kaputt). */
    private javax.net.ssl.SSLContext tlsContext() {
        tlsStamp = stamp();
        if (!settings.wantsTls() || tlsStamp.isEmpty()) {
            if (settings.wantsTls()) {
                LOG.warning(() -> "mods | download_ohne_tls | grund=Zertifikat fehlt (" + settings.cert() + ")");
            }
            return null;
        }
        try {
            return PemTls.context(Path.of(settings.cert()), Path.of(settings.key()), clock.getAsLong());
        } catch (IOException | RuntimeException e) {
            LOG.warning(() -> "mods | download_ohne_tls | grund=" + e.getMessage());
            return null;
        }
    }

    /** „Zeit:Größe|Zeit:Größe“ beider PEM-Dateien, leer wenn eine fehlt oder unlesbar ist. */
    private String stamp() {
        try {
            Path c = Path.of(settings.cert());
            Path k = Path.of(settings.key());
            if (!Files.isReadable(c) || !Files.isReadable(k)) {
                return "";
            }
            return Files.getLastModifiedTime(c).toMillis() + ":" + Files.size(c) + "|"
                    + Files.getLastModifiedTime(k).toMillis() + ":" + Files.size(k);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Jede Minute: neues oder verlängertes Zertifikat → Port mit TLS neu binden. */
    synchronized void watchCertificate() {
        if (server == null || stamp().equals(tlsStamp)) {
            return;
        }
        javax.net.ssl.SSLContext ctx = tlsContext();
        if (ctx == null && tls) {
            return;  // kaputtes neues Zertifikat: beim alten bleiben, Grund steht im Log
        }
        try {
            closeServer();
            bind(ctx);
            LOG.info(() -> "mods | zertifikat_neu | tls=" + tls);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "mods | neu_binden_fehlgeschlagen | port=" + settings.port(), e);
        }
    }

    /** Port binden (mit TLS, wenn ctx da ist) und eine Annahme-Schleife für genau diesen Socket starten. */
    private void bind(javax.net.ssl.SSLContext ctx) throws IOException {
        ServerSocket s;
        if (ctx != null) {
            javax.net.ssl.SSLServerSocket ss = (javax.net.ssl.SSLServerSocket) ctx.getServerSocketFactory()
                    .createServerSocket();
            ss.setEnabledProtocols(java.util.Arrays.stream(ss.getSupportedProtocols())
                    .filter(p -> p.equals("TLSv1.3") || p.equals("TLSv1.2")).toArray(String[]::new));
            s = ss;
        } else {
            s = new ServerSocket();
        }
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName(settings.bind()), settings.port()), 16);
        server = s;
        tls = ctx != null;
        Thread accept = daemon("Baumeister-Mods-Annahme").newThread(() -> acceptLoop(s));
        accept.start();
    }

    private void closeServer() {
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "mods | schließen", e);
            }
        }
    }

    /** https oder http – wie der Port gerade läuft. */
    String scheme() {
        return tls ? "https" : "http";
    }

    synchronized void stop() {
        closeServer();
        if (pool != null) {
            pool.shutdownNow();
        }
        if (reaper != null) {
            reaper.shutdownNow();
        }
    }

    boolean running() {
        ServerSocket s = server;
        return s != null && !s.isClosed();
    }

    /** Port, auf dem der Server wirklich lauscht, sonst -1. */
    int boundPort() {
        ServerSocket s = server;
        return s == null ? -1 : s.getLocalPort();
    }

    /**
     * Neuer Link für einen Spieler, wenn der Server läuft und es etwas zu laden gibt. Hauptthread. Je Spieler gilt
     * höchstens der neueste Link (ein alter fällt weg), insgesamt höchstens MAX_GRANTS.
     */
    Optional<String> link(UUID player, String name) {
        if (!running() || offers().isEmpty()) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        grants.entrySet().removeIf(e -> e.getValue().expires() <= now || e.getValue().player().equals(player));
        if (grants.size() >= MAX_GRANTS) {
            grants.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().expires()))
                    .ifPresent(e -> grants.remove(e.getKey()));
        }
        byte[] b = new byte[16];
        random.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        grants.put(token, new Grant(player, name, now + TTL_MILLIS));
        return Optional.of(scheme() + "://" + settings.address() + ":" + settings.port() + "/m/" + token);
    }

    /** Die angebotenen Dateien: je Art die neueste Fassung aus dem Ordner. */
    List<Offer> offers() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Offer> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            List<Path> files = s.filter(Files::isRegularFile)
                    .filter(p -> FILE.matcher(p.getFileName().toString()).matches()).toList();
            for (String kind : List.of(".mrpack", "-mods.zip")) {
                files.stream().filter(p -> p.getFileName().toString().endsWith(kind))
                        .max(Comparator.comparing(ModDownloads::versionKey))
                        .ifPresent(p -> out.add(new Offer(p.getFileName().toString(), p, size(p))));
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "mods | ordner_unlesbar | ordner=" + folder, e);
        }
        return List.copyOf(out);
    }

    /** Mods im Paket aus der Liste des Skripts (…-mods.txt), ohne Endung „.jar“; leer, wenn es keine gibt. */
    List<String> modNames() {
        Optional<Path> list = ModsInfo.newestList(folder);
        if (list.isEmpty()) {
            return List.of();
        }
        try {
            return ModsInfo.parse(Files.readAllLines(list.get(), StandardCharsets.UTF_8)).entries().stream()
                    .map(e -> e.file().replaceFirst("\\.jar$", "")).toList();
        } catch (IOException e) {
            LOG.log(Level.FINE, "mods | liste_unlesbar", e);
            return List.of();
        }
    }

    /** Minecraft-Fassung aus dem Dateinamen des Pakets („Baumeisterland-26.2.mrpack“ → 26.2). */
    static String mcVersion(List<Offer> offers) {
        for (Offer o : offers) {
            Matcher m = FILE.matcher(o.name());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return "";
    }

    /** „26.2“ → „00026.00002.00000“, damit 26.10 nach 26.9 kommt. */
    private static String versionKey(Path p) {
        Matcher m = FILE.matcher(p.getFileName().toString());
        if (!m.matches()) {
            return "";
        }
        StringBuilder k = new StringBuilder();
        String[] parts = (m.group(1) + ".0.0").split("\\.");
        for (int i = 0; i < 3; i++) {
            k.append(String.format(Locale.ROOT, "%05d.", Integer.parseInt(parts[i])));
        }
        return k.toString();
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            Socket c;
            try {
                c = s.accept();
            } catch (IOException e) {
                if (!s.isClosed()) {
                    LOG.log(Level.WARNING, "mods | annahme_fehler", e);
                }
                continue;
            }
            InetAddress from = c.getInetAddress();
            AtomicInteger n = perAddress.computeIfAbsent(from, k -> new AtomicInteger());
            if (n.incrementAndGet() > PER_ADDRESS) {
                release(from, n);
                closeQuietly(c);
                continue;
            }
            try {
                pool.execute(() -> {
                    try {
                        handle(c);
                    } finally {
                        release(from, n);
                    }
                });
            } catch (RejectedExecutionException e) {
                release(from, n);
                busy(c);
            }
        }
    }

    private void release(InetAddress from, AtomicInteger n) {
        if (n.decrementAndGet() <= 0) {
            perAddress.remove(from, n);
        }
    }

    private void busy(Socket c) {
        try (c) {
            c.setSoTimeout(READ_TIMEOUT_MS);
            send(c.getOutputStream(), 503, "text/plain; charset=utf-8", ("Gerade zu viele Downloads – gleich noch "
                    + "einmal.\n").getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            LOG.log(Level.FINE, "mods | besetzt", e);
        }
    }

    private void handle(Socket c) {
        java.util.concurrent.ScheduledFuture<?> limit = reaper.schedule(() -> closeQuietly(c), MAX_CONNECTION_MILLIS,
                TimeUnit.MILLISECONDS);
        try (c) {
            c.setSoTimeout(READ_TIMEOUT_MS);
            String line = requestLine(c.getInputStream());
            OutputStream out = c.getOutputStream();
            if (line == null) {
                send(out, 400, "text/plain; charset=utf-8", "Ungültige Anfrage.\n".getBytes(StandardCharsets.UTF_8),
                        false);
                return;
            }
            Matcher m = REQUEST.matcher(line);
            if (!m.matches()) {
                boolean method = line.startsWith("GET ") || line.startsWith("HEAD ");
                send(out, method ? 404 : 405, "text/plain; charset=utf-8",
                        (method ? "Nicht gefunden.\n" : "Nur GET.\n").getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            boolean head = m.group(1).equals("HEAD");
            Grant g = grants.get(m.group(2));
            if (g == null || g.expires() <= clock.getAsLong()) {
                send(out, 404, "text/plain; charset=utf-8", ("Dieser Link gilt nicht (mehr). Im Spiel /mods – dann "
                        + "gibt es einen neuen.\n").getBytes(StandardCharsets.UTF_8), head);
                return;
            }
            List<Offer> offers = offers();
            if (m.group(3) == null) {
                send(out, 200, "text/html; charset=utf-8", page(m.group(2), g, offers, modNames(),
                        settings.address(), mcVersion(offers)).getBytes(StandardCharsets.UTF_8), head);
                return;
            }
            Optional<Offer> file = offers.stream().filter(o -> o.name().equals(m.group(3))).findFirst();
            if (file.isEmpty()) {
                send(out, 404, "text/plain; charset=utf-8", "Nicht gefunden.\n".getBytes(StandardCharsets.UTF_8), head);
                return;
            }
            sendFile(out, file.get(), head);
            if (!head) {
                LOG.info(() -> "mods | download | spieler=" + g.name() + " datei=" + file.get().name() + " bytes="
                        + file.get().size());
            }
        } catch (SocketTimeoutException e) {
            LOG.fine(() -> "mods | zeitüberschreitung");
        } catch (IOException e) {
            LOG.fine(() -> "mods | verbindung | grund=" + e.getMessage());
        } finally {
            limit.cancel(false);
        }
    }

    /** Erste Zeile der Anfrage; den Rest des Kopfs liest es bis zur Leerzeile mit (höchstens MAX_HEAD). */
    static String requestLine(InputStream in) throws IOException {
        byte[] buf = new byte[MAX_HEAD];
        int n = 0;
        while (n < buf.length) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buf[n++] = (byte) b;
            if (n >= 4 && buf[n - 4] == '\r' && buf[n - 3] == '\n' && buf[n - 2] == '\r' && buf[n - 1] == '\n') {
                String head = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                return head.substring(0, head.indexOf("\r\n"));
            }
        }
        return null;
    }

    /**
     * Die Seite hinter dem Link (Wunsch 2026-11-08). Vorher eine nackte Liste mit
     * zwei Links im Browser-Standardstil. Jetzt dunkel wie das Spielmenü, Akzent wie die Baumeister-App (#8b5cf6), je
     * Weg eine Karte mit Knopf und drei Schritten, darunter die Mods im Paket und die Serveradresse. Alles in der Seite
     * (CSP default-src 'none' – keine Schrift, kein Bild, kein Skript von außen). Dateinamen kommen nur aus FILE,
     * Spielername und Modliste werden escaped.
     */
    static String page(String token, Grant g, List<Offer> offers, List<String> mods, String server, String mc) {
        String name = esc(g.name().replaceAll("[^A-Za-z0-9_]", ""));
        String until = java.time.format.DateTimeFormatter.ofPattern("dd.MM. 'um' HH:mm", Locale.GERMANY)
                .format(java.time.Instant.ofEpochMilli(g.expires()).atZone(java.time.ZoneId.of("Europe/Berlin")));
        StringBuilder cards = new StringBuilder();
        for (Offer o : offers) {
            boolean pack = o.name().endsWith(".mrpack");
            String size = String.format(Locale.GERMANY, "%.1f MB", o.size() / 1048576.0);
            cards.append("<section class=card><div class=tag>").append(pack ? "Empfohlen" : "Ohne Zusatzprogramm")
                    .append("</div><h2>").append(pack ? "Prism Launcher oder Modrinth App" : "Normaler Minecraft-Launcher")
                    .append("</h2><a class=btn href=\"/m/").append(token).append('/').append(o.name()).append("\">")
                    .append("Herunterladen <small>").append(esc(o.name())).append(" · ").append(size)
                    .append("</small></a><ol>");
            if (pack) {
                cards.append("<li>Prism: <b>Hinzufügen → Importieren</b>, Datei wählen. Modrinth App: <b>+ → Aus "
                        + "Datei</b>.</li><li>Der Launcher lädt Fabric und die Mods selbst und prüft sie.</li>"
                        + "<li>Die neue Instanz starten – fertig.</li>");
            } else {
                cards.append("<li>Fabric-Installer für Minecraft ").append(esc(mc)).append(" ausführen (fabricmc.net)."
                        + "</li><li>Die ZIP in <code>%APPDATA%\\.minecraft\\mods</code> auspacken.</li>"
                        + "<li>Im Launcher das Profil <b>fabric-loader-").append(esc(mc)).append("</b> starten.</li>");
            }
            cards.append("</ol></section>");
        }
        StringBuilder list = new StringBuilder();
        for (String m : mods) {
            list.append("<li>").append(esc(m)).append("</li>");
        }
        String css = ":root{--bg:#121318;--panel:#1c1e26;--line:#2c2f3a;--fg:#e8e8ee;--muted:#9a9cab;--acc:#8b5cf6;"
                + "--ok:#4ade80}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);"
                + "font:16px/1.5 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}"
                + "main{max-width:880px;margin:0 auto;padding:40px 16px 56px}"
                + "header{display:flex;align-items:center;gap:16px;margin-bottom:8px}"
                + ".logo{width:52px;height:52px;border-radius:12px;background:linear-gradient(135deg,#5b8c32 0 50%,"
                + "#7a5230 50%);box-shadow:inset 0 -6px 0 rgba(0,0,0,.25)}"
                + "h1{margin:0;font-size:28px;letter-spacing:.2px}h1 span{color:var(--acc)}"
                + ".sub{color:var(--muted);margin:2px 0 0}"
                + ".note{margin:20px 0 28px;padding:12px 16px;border:1px solid var(--line);border-left:4px solid "
                + "var(--acc);border-radius:10px;background:var(--panel);color:var(--muted)}.note b{color:var(--fg)}"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px}"
                + ".card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:20px}"
                + ".tag{display:inline-block;font-size:12px;text-transform:uppercase;letter-spacing:.08em;"
                + "color:var(--acc);font-weight:600}h2{font-size:19px;margin:6px 0 16px}"
                + ".btn{display:block;text-align:center;background:var(--acc);color:#fff;text-decoration:none;"
                + "font-weight:600;padding:12px 14px;border-radius:10px}.btn:hover{filter:brightness(1.1)}"
                + ".btn small{display:block;font-weight:400;opacity:.85;font-size:13px}"
                + "ol{margin:16px 0 0;padding-left:20px;color:var(--muted)}ol b{color:var(--fg)}li{margin:4px 0}"
                + "code{background:#262935;padding:1px 6px;border-radius:6px;font-size:14px;color:var(--fg)}"
                + "h3{font-size:15px;margin:32px 0 10px;color:var(--muted);font-weight:600}"
                + ".mods{display:flex;flex-wrap:wrap;gap:8px;list-style:none;padding:0;margin:0}"
                + ".mods li{background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:4px 12px;"
                + "font-size:14px;margin:0}"
                + ".join{margin-top:28px;display:flex;flex-wrap:wrap;align-items:center;gap:12px;padding:16px;"
                + "border-radius:14px;background:var(--panel);border:1px solid var(--line)}"
                + ".join code{font-size:16px;color:var(--ok)}footer{margin-top:28px;color:var(--muted);font-size:13px}";
        return "<!doctype html>\n<html lang=\"de\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>Baumeisterland – Modpaket</title>"
                + "<style>" + css + "</style></head><body><main>"
                + "<header><div class=logo></div><div><h1>Baumeister<span>land</span></h1>"
                + "<p class=sub>Modpaket für Minecraft " + esc(mc) + " mit Fabric</p></div></header>"
                + "<div class=note>Für <b>" + name + "</b> · gültig bis <b>" + until + " Uhr</b>. Bitte nicht "
                + "weitergeben – im Spiel gibt <code>/mods</code> jedem seinen eigenen Link.</div>"
                + (offers.isEmpty() ? "<div class=card>Gerade liegt kein Paket bereit. Sag dem Admin Bescheid.</div>"
                        : "<div class=grid>" + cards + "</div>")
                + (mods.isEmpty() ? "" : "<h3>Im Paket</h3><ul class=mods>" + list + "</ul>")
                + "<div class=join><span>Danach verbinden mit</span><code>" + esc(server) + "</code></div>"
                + "<footer>Minecraft " + esc(mc) + " · Baumeister</footer>"
                + "</main></body></html>\n";
    }

    /** HTML-Text escapen (OWASP A03): & < > " '. */
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void sendFile(OutputStream out, Offer o, boolean head) throws IOException {
        long size = Files.size(o.path());
        String type = o.name().endsWith(".mrpack") ? "application/x-modrinth-modpack+zip" : "application/zip";
        String hdr = "HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + size
                + "\r\nContent-Disposition: attachment; filename=\"" + o.name() + "\"\r\n" + common();
        out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
        if (!head) {
            try (InputStream in = Files.newInputStream(o.path())) {
                in.transferTo(out);
            }
        }
        out.flush();
    }

    private static void send(OutputStream out, int code, String type, byte[] body, boolean head) throws IOException {
        String reason = switch (code) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Service Unavailable";
        };
        String hdr = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + body.length + "\r\n" + (code == 405 ? "Allow: GET, HEAD\r\n" : "") + common();
        out.write(hdr.getBytes(StandardCharsets.ISO_8859_1));
        if (!head) {
            out.write(body);
        }
        out.flush();
    }

    /** Gleiche Kopfzeilen für alle Antworten (OWASP Secure Headers): nichts zwischenspeichern, nichts nachladen. */
    private static String common() {
        return "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n"
                + "X-Frame-Options: DENY\r\nConnection: close\r\n\r\n";
    }

    private static void closeQuietly(Socket c) {
        try {
            c.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "mods | schließen", e);
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, e) -> LOG.log(Level.SEVERE, "mods | thread_fehler | thread="
                    + th.getName(), e));
            return t;
        };
    }
}
