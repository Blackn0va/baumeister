package com.blackn0va.baumeister.relay;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Einstieg des Relays (Unit relay/deploy/baumeister-relay.service).
 *
 * <pre>
 * java -jar baumeister-relay.jar [--konfig DATEI]                 Relay starten (Vorgabe)
 * java -jar baumeister-relay.jar [--konfig DATEI] --prüfen        Konfiguration und Schlüssel prüfen
 * java -jar baumeister-relay.jar [--konfig DATEI] --fingerabdruck Server-Pin ausgeben (für die App)
 * </pre>
 * --pruefen geht auch (ASCII, z. B. für Terminals ohne UTF-8). Vorgabe für DATEI: /etc/baumeister-relay/
 * relay.properties.
 *
 * Exitcodes: 0 in Ordnung · 1 Laufzeitfehler (Port belegt …; systemd startet neu) · 2 --prüfen fand keine erlaubte App
 * · 64 falscher Aufruf · 78 Konfiguration/Schlüssel unbrauchbar (EX_CONFIG; die Unit startet dann NICHT im Kreis neu,
 * RestartPreventExitStatus=78 – eine kaputte Datei wird durch Neustarts nicht besser).
 *
 * Threads: Hauptthread wartet, bis das Relay endet (SIGTERM → Shutdown-Hook → Relay.close).
 * Fehler: deutsche Meldung auf stderr bzw. im Log, nie mit Geheimnissen.
 */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_WARNINGS = 2;
    static final int EXIT_USAGE = 64;
    static final int EXIT_CONFIG = 78;

    private static final Logger LOG = RelayLog.LOG;

    static {
        // Allererstes im Prozess: JSSE liest diese Eigenschaften genau einmal (siehe Tls.hardenJsse).
        Tls.hardenJsse();
    }

    private enum Mode {
        RUN,
        CHECK,
        FINGERPRINT,
        HELP
    }

    private Main() {
    }

    public static void main(String[] args) {
        // UTF-8 unabhängig von LANG: Unter sudo oder systemd-run gilt oft LANG=C, Java schriebe dann „?“ statt „ü“
        // in die Meldungen von --prüfen. Das Log (RelayLog) schreibt aus demselben Grund UTF-8.
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = run(args, System.getenv(), out, err);
        if (code != EXIT_OK) {
            System.exit(code);
        }
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        Path file = RelayConfig.DEFAULT_FILE;
        Mode mode = Mode.RUN;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--konfig" -> {
                    if (i + 1 >= args.length) {
                        err.println("baumeister-relay: --konfig braucht eine Datei.");
                        return EXIT_USAGE;
                    }
                    try {
                        file = Path.of(args[++i]);
                    } catch (InvalidPathException e) {
                        err.println("baumeister-relay: „" + args[i] + "“ ist kein gültiger Pfad.");
                        return EXIT_USAGE;
                    }
                }
                case "--prüfen", "--pruefen" -> mode = Mode.CHECK;  // umlaut-ok: ASCII-Schreibweise des Schalters
                case "--fingerabdruck" -> mode = Mode.FINGERPRINT;
                case "--hilfe", "-h", "--help" -> mode = Mode.HELP;
                default -> {
                    err.println("baumeister-relay: unbekannter Schalter „" + args[i] + "“.");
                    usage(err);
                    return EXIT_USAGE;
                }
            }
        }
        if (mode == Mode.HELP) {
            usage(out);
            return EXIT_OK;
        }
        RelayConfig config;
        KeyMaterial keys;
        try {
            config = RelayConfig.load(file, env);
            keys = KeyMaterial.load(config.keystore(), config.password());
        } catch (ConfigException e) {
            err.println("baumeister-relay: " + e.getMessage());
            return EXIT_CONFIG;
        }
        return switch (mode) {
            case FINGERPRINT -> {
                out.println(keys.pin());
                yield EXIT_OK;
            }
            case CHECK -> check(config, keys, out);
            default -> serve(config, keys, env);
        };
    }

    private static int check(RelayConfig c, KeyMaterial k, PrintStream out) {
        out.println("Konfiguration in Ordnung: " + c.file());
        out.println("  Plugin-Seite:  " + c.local().getAddress().getHostAddress() + ":" + c.local().getPort());
        out.println("  App-Seite:     " + c.external().getAddress().getHostAddress() + ":" + c.external().getPort());
        out.println("  Server-Pin:    sha256:" + Pins.brief(k.pin()) + "… (ganz: --fingerabdruck)");
        out.println("  Statusdatei:   " + c.status());
        if (c.pins().isEmpty()) {
            out.println("WARNUNG: Keine App erlaubt (erlaubt.<name>=sha256:… fehlt). Das Relay hält 18080 trotzdem "
                    + "belegt und weist jede App ab.");
            return EXIT_WARNINGS;
        }
        c.pins().forEach((name, pin) -> out.println("  Erlaubte App:  " + name + " (sha256:" + Pins.brief(pin)
                + "…)"));
        return EXIT_OK;
    }

    private static int serve(RelayConfig config, KeyMaterial keys, Map<String, String> env) {
        RelayLog.toStdout(Level.INFO);
        PinStore pins = new PinStore(config, () -> env);
        Relay relay = new Relay(config, keys, Limits.standard(), pins, why -> System.exit(EXIT_ERROR));
        try {
            relay.start();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // Start gescheitert (meist: Port belegt – läuft der SSH-Tunnel noch?). Exit 1: systemd versucht es neu.
            LOG.log(Level.SEVERE, "relay | start_fehlgeschlagen | grund=" + e.getClass().getSimpleName() + ": "
                    + e.getMessage(), e);
            relay.close();
            return EXIT_ERROR;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(relay::close, "relay-ende"));
        try {
            relay.awaitStop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            relay.close();
        }
        return EXIT_OK;
    }

    private static void usage(PrintStream s) {
        s.println("Aufruf: java -jar baumeister-relay.jar [--konfig DATEI] [--prüfen | --fingerabdruck]");
        s.println("  ohne Schalter     Relay starten (Plugin-Seite 127.0.0.1:18080, App-Seite :25580)");
        s.println("  --prüfen          Konfiguration und Schlüssel prüfen (auch --pruefen)");  // umlaut-ok
        s.println("  --fingerabdruck   Server-Pin ausgeben (sha256:…, kommt in die Host-App)");
        s.println("  --konfig DATEI    Vorgabe " + RelayConfig.DEFAULT_FILE);
    }
}
