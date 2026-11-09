package com.blackn0va.baumeister.relay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Die erlaubten App-Pins, wie sie gerade in der Konfiguration stehen.
 *
 * Neu geladen wird im Betrieb: Der Takt sieht alle 2 s nach Änderungszeit und Größe der Datei, und jeder Handshake
 * tut es auch. Ein entfernter Pin gilt damit für die nächste Verbindung sofort, und die laufende Sitzung dieses Pins
 * schließt der Takt mit 1008 „pin_entzogen“ – ohne Neustart (docs/kanal-v1.md, Abschnitt 1).
 *
 * Fail-closed beim Neuladen: Ist die geänderte Datei ungültig (Tippfehler, halb gespeichert), gilt KEIN Pin mehr, bis
 * sie wieder stimmt. Vorher-Stand behalten hieße: Wer einen Pin entfernt und sich dabei vertippt, entzieht nichts.
 * Andere Werte (Ports, Schlüssel) wirken erst nach einem Neustart; das Log sagt es.
 *
 * Threads: nameFor() lock-frei (volatile Karte); refresh() synchronisiert. Fehler: werden geloggt, nie geworfen.
 */
final class PinStore {

    private static final Logger LOG = RelayLog.LOG;

    private final Path file;
    private final Supplier<Map<String, String>> env;
    private final RelayConfig started;
    private volatile Map<String, String> nameByPin;
    private Object stamp;

    /** Aus einer geladenen Konfiguration; file null = feste Pins (Tests ohne Datei). */
    PinStore(RelayConfig config, Supplier<Map<String, String>> env) {
        this.file = config.file();
        this.env = env;
        this.started = config;
        this.nameByPin = invert(config.pins());
        // Kein Stempel: Der erste refresh (nach 2 s) liest die Datei sicher noch einmal (zweite Sicherheitsprüfung
        // 2026-11-08, N4). Vorher galt der Stempel von NACH dem Entschlüsseln des Schlüssels – wer die Datei in
        // diesem Fenster änderte (Pin entzogen), dessen Änderung galt erst bei der nächsten Änderung.
        this.stamp = null;
    }

    /** Feste Pins ohne Datei (Tests). */
    PinStore(Map<String, String> pinsByName) {
        this.file = null;
        this.env = Map::of;
        this.started = null;
        this.nameByPin = invert(pinsByName);
        this.stamp = null;
    }

    /** Name zum Pin oder null. */
    String nameFor(String pin) {
        return nameByPin.get(pin);
    }

    int size() {
        return nameByPin.size();
    }

    /** Pins direkt ersetzen (Tests). */
    void replace(Map<String, String> pinsByName) {
        nameByPin = invert(pinsByName);
    }

    /** Neu laden, falls sich die Datei geändert hat. true: Die Pins wurden neu gesetzt. */
    synchronized boolean refresh() {
        if (file == null) {
            return false;
        }
        Object now = stamp(file);
        if (now.equals(stamp)) {
            return false;
        }
        stamp = now;
        try {
            RelayConfig c = RelayConfig.load(file, env.get());
            nameByPin = invert(c.pins());
            LOG.info(() -> "relay | pins_neu_geladen | anzahl=" + c.pins().size() + " apps=" + summary(c.pins()));
            if (started != null && (!c.local().equals(started.local()) || !c.external().equals(started.external())
                    || !c.keystore().equals(started.keystore()) || !c.password().equals(started.password())
                    || !c.status().equals(started.status()))) {
                LOG.warning("relay | konfig_geändert | wirkt_erst_nach=neustart (nur erlaubt.* gilt sofort)");
            }
        } catch (ConfigException e) {
            nameByPin = Map.of();
            LOG.log(Level.SEVERE, "relay | konfig_ungültig | alle_apps_gesperrt=ja grund=" + e.getMessage());
        }
        return true;
    }

    /** Name=Kurzpin, fürs Log. */
    static String summary(Map<String, String> pinsByName) {
        StringBuilder b = new StringBuilder();
        pinsByName.forEach((name, pin) -> b.append(b.isEmpty() ? "" : ",").append(name).append('=')
                .append(Pins.brief(pin)));
        return b.isEmpty() ? "-" : b.toString();
    }

    private static Map<String, String> invert(Map<String, String> pinsByName) {
        Map<String, String> m = new HashMap<>();
        pinsByName.forEach((name, pin) -> m.put(pin, name));
        return Map.copyOf(m);
    }

    /** Änderungszeit und Größe; fehlt die Datei, ein eigener Wert – der Wechsel löst ein Neuladen (und Sperren) aus. */
    private static Object stamp(Path file) {
        if (file == null) {
            return "";
        }
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            // Volle Auflösung (ext4: ns) statt toMillis(): zwei Änderungen in derselben Millisekunde zählen beide.
            return a.lastModifiedTime() + ":" + a.size() + ":" + a.fileKey();
        } catch (IOException e) {
            return "fehlt:" + e.getClass().getSimpleName();
        }
    }
}
