package com.blackn0va.baumeister.relay;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

/**
 * Das Relay als Ganzes: Plugin-Seite (LocalListener), App-Seite (PublicListener), Verteilung (Hub) und der Takt.
 *
 * Start: zuerst die Plugin-Seite binden (ab da gehört 18080 dem Relay), dann die App-Seite. Der Takt (1 s) schickt
 * Pings, erkennt Stille (45 s) und hängende Schreiber, lädt die Pins nach, schließt Sitzungen mit entzogenem Pin
 * (1008) und fasst gedrosselte Abweisungen zusammen. Alle 10 min eine Zeile zum Speicher fürs Journal.
 *
 * Threads: start/close aus dem Hauptthread bzw. dem Shutdown-Hook; der Rest siehe die Teile.
 * Fehler: start() wirft IOException (Port belegt) oder GeneralSecurityException (TLS); fatal() beendet den Prozess.
 */
final class Relay implements AutoCloseable {

    private static final Logger LOG = RelayLog.LOG;
    private static final long MEMORY_LOG_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final RelayConfig config;
    private final KeyMaterial keys;
    private final Limits limits;
    private final PinStore pins;
    private final Consumer<String> onFatal;
    private final ByteBudget budget;
    private final Hub hub;
    private final HandshakeGate gate;
    private final PinTrustManager trust;
    private final RelayLog.Throttle throttle = new RelayLog.Throttle(20, TimeUnit.MINUTES.toNanos(1));
    private final ScheduledThreadPoolExecutor scheduler;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final String id;
    private LocalListener local;
    private PublicListener external;
    private volatile boolean closing;
    private long lastPinCheck;
    private long lastMemoryLog;

    /** onFatal: Port verloren o. Ä. – im Betrieb System.exit(1), damit systemd neu startet; Tests zählen mit. */
    Relay(RelayConfig config, KeyMaterial keys, Limits limits, PinStore pins, Consumer<String> onFatal) {
        Tls.hardenJsse();
        this.config = config;
        this.keys = keys;
        this.limits = limits;
        this.pins = pins;
        this.onFatal = onFatal;
        this.budget = new ByteBudget(limits.byteBudget());
        this.hub = new Hub(limits, budget, new StatusFile(config.status()));
        this.gate = new HandshakeGate(limits);
        this.trust = new PinTrustManager(pins);
        this.scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "relay-takt");
            t.setDaemon(true);
            return t;
        });
        // Jede Verbindung plant einen Fristen-Riegel und sagt ihn meist wieder ab – abgesagte sofort entfernen, sonst
        // sammelten sich bei einer Flut Tausende in der Warteschlange des Takts.
        this.scheduler.setRemoveOnCancelPolicy(true);
        String version = Relay.class.getPackage().getImplementationVersion();
        this.id = "baumeister-relay/" + (version != null ? version : "dev");
    }

    void start() throws IOException, GeneralSecurityException {
        SSLContext ctx = Tls.serverContext(keys, trust);
        local = new LocalListener(this);
        local.bind(config.local());
        external = new PublicListener(this, ctx, trust, pins, gate);
        // Scheitert 25580, bleibt 18080 trotzdem belegt (zweite Sicherheitsprüfung 2026-11-08, N2): Vorher gab
        // start() dann auch 18080 frei, und ein lokaler Prozess, der 25580 vorher belegte, erzwang so eine
        // Neustart-Schleife, in der 18080 meist frei war. Jetzt läuft die Plugin-Seite weiter (ohne App: RST), und das
        // Relay versucht 25580 im Takt erneut.
        try {
            external.bind(config.external());
        } catch (IOException e) {
            LOG.warning("relay | öffentlich_belegt | adresse=" + config.external() + " grund=" + e.getMessage()
                    + " – 18080 bleibt belegt, neuer Versuch im Takt");
            externalPending = true;
        }
        hub.writeStatus();
        long now = System.nanoTime();
        lastPinCheck = now;
        lastMemoryLog = now;
        scheduler.scheduleWithFixedDelay(this::tick, limits.tick().toMillis(), limits.tick().toMillis(),
                TimeUnit.MILLISECONDS);
        local.start();
        if (!externalPending) {
            external.start();
        }
        LOG.info("relay | gestartet | fassung=" + id + " lokal=" + local.describe() + " öffentlich="
                + external.describe() + " server_pin=" + Pins.brief(keys.pin()) + " apps=" + PinStore.summary(
                config.pins()) + " budget_kb=" + budget.limit() / 1024 + " heap_max_mb="
                + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        if (pins.size() == 0) {
            LOG.warning("relay | keine_app_erlaubt | 18080 bleibt belegt, jede App wird abgewiesen "
                    + "(erlaubt.<name>=sha256:… in " + config.file() + ")");
        }
    }

    /** 25580 war beim Start belegt (N2): im Takt erneut versuchen. */
    private volatile boolean externalPending;

    /** Takt: Pings, Stille, hängende Schreiber, Pins, Sperrliste, Log-Summen. */
    private void tick() {
        try {
            long now = System.nanoTime();
            if (externalPending) {
                try {
                    external.bind(config.external());
                    external.start();
                    externalPending = false;
                    LOG.info("relay | öffentlich_gebunden | adresse=" + config.external());
                } catch (IOException e) {
                    LOG.fine(() -> "relay | öffentlich_weiter_belegt | grund=" + e.getMessage());
                }
            }
            if (now - lastPinCheck >= limits.pinReload().toNanos()) {
                lastPinCheck = now;
                pins.refresh();
            }
            for (Session s : hub.sessions()) {
                if (!s.app.equals(pins.nameFor(s.pin))) {
                    s.close(1008, "pin_entzogen", "pin_entzogen");
                    continue;
                }
                if (now - s.lastFrame() > limits.silence().toNanos()) {
                    s.close(0, "", "stille");
                    continue;
                }
                long w = s.writingSince();
                if (w != 0 && now - w > limits.silence().toNanos()) {
                    s.close(0, "", "schreiben_hängt");
                    continue;
                }
                if (now - s.lastPing() >= limits.ping().toNanos()) {
                    s.ping();
                }
            }
            for (LocalExchange e : hub.exchanges()) {
                long w = e.writingSince();
                if (w != 0 && now - w > limits.silence().toNanos()) {
                    // Das Plugin liest seit 45 s nicht: abbruch an die App, RST – sonst hielte es Thread und Budget.
                    e.abort("plugin_liest_nicht");
                }
            }
            gate.cleanup(now);
            throttle.flush();
            if (now - lastMemoryLog >= MEMORY_LOG_NANOS) {
                lastMemoryLog = now;
                logMemory();
            }
        } catch (RuntimeException e) {
            // Thread-Grenze: Wirft die Aufgabe, plant der ScheduledExecutor sie still nie wieder ein – kein Ping, keine
            // Stille-Erkennung, kein Pin-Entzug mehr. Deshalb hier fangen und laut melden.
            LOG.log(Level.SEVERE, "relay | takt_fehler", e);
        }
    }

    void logMemory() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        LOG.info("relay | speicher | heap_belegt_mb=" + usedMb + " heap_max_mb=" + rt.maxMemory() / (1024 * 1024)
                + " budget_kb=" + budget.used() / 1024 + "/" + budget.limit() / 1024 + " budget_spitze_kb="
                + budget.peak() / 1024 + " apps=" + hub.sessions().size() + " lokal=" + local.active()
                + " wartend=" + hub.waitingCount() + " sperrliste=" + gate.size());
    }

    /** Port verloren o. Ä.: Ohne den Port ist das Relay wertlos – Prozess beenden, systemd startet neu. */
    void fatal(String why) {
        LOG.severe("relay | fatal | grund=" + why);
        onFatal.accept(why);
    }

    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        LOG.info("relay | endet");
        if (external != null) {
            external.close();
        }
        for (Session s : hub.sessions()) {
            s.close(1001, "relay_endet", "relay_endet");
        }
        for (LocalExchange e : hub.exchanges()) {
            e.lost("relay_endet");
        }
        if (local != null) {
            local.close();
        }
        // shutdown statt shutdownNow: Der Takt (periodisch) endet, geplante Riegel laufen noch – sonst blieben die
        // Sockets eben geschlossener Sitzungen offen (Close-Riegel nach 2 s), bis der Prozess endet.
        scheduler.shutdown();
        stopped.countDown();
    }

    void awaitStop() throws InterruptedException {
        stopped.await();
    }

    boolean closing() {
        return closing;
    }

    int localPort() {
        return local.port();
    }

    int externalPort() {
        return external.port();
    }

    String id() {
        return id;
    }

    Hub hub() {
        return hub;
    }

    ByteBudget budget() {
        return budget;
    }

    Limits limits() {
        return limits;
    }

    ScheduledExecutorService scheduler() {
        return scheduler;
    }

    RelayLog.Throttle throttle() {
        return throttle;
    }

    PinTrustManager trust() {
        return trust;
    }

    HandshakeGate gate() {
        return gate;
    }

    PinStore pins() {
        return pins;
    }
}
