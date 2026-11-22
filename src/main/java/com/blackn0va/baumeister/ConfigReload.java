package com.blackn0va.baumeister;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * /bm neuladen [erzwingen]: config.yml neu lesen, ohne laufende Bauten zu zerstören. Was wann gilt, entscheidet
 * ReloadPlan; hier nur Lesen, Prüfen und Ausführen auf dem Hauptthread.
 *
 * Reihenfolge mit Absicht: Erst die Datei in eine eigene YamlConfiguration lesen und prüfen (prepare: YAML,
 * Settings.from), DANN plugin.reloadConfig(). Vorher rief neuladen reloadConfig zuerst. Ist die Datei kein gültiges
 * YAML, fängt Bukkit das in YamlConfiguration.loadConfiguration ab (SEVERE im Log, gelesen im Bytecode von
 * spigot-api 1.21.11) und liefert eine LEERE Config mit den JAR-Vorgaben – Settings.from lief damit fehlerfrei durch,
 * der Admin las „Konfiguration neu geladen.“, und ab da galten überall die Vorlagenwerte statt seiner eigenen
 * (auf dev01: bau.max-blöcke, stadt.blöcke-pro-tick 500, eigene Grenzen). Jetzt bleibt bei einer kaputten Datei
 * alles, wie es war (fail-closed). Wer prepare wieder über loadConfiguration laufen lässt, holt das zurück
 * (ConfigReloadTest.brokenFileChangesNothing).
 *
 * Threads: nur Hauptthread. Fehler: unlesbare Datei/ungültige Werte → Meldung, nichts geändert.
 */
final class ConfigReload {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    /** Höchstens so viele Prüfzeilen an den Admin; der Rest steht im Log. */
    static final int MAX_WARNINGS = 8;

    private final BaumeisterPlugin plugin;
    /**
     * Werte beim Start (onEnable). „Wirkt erst nach Neustart“ wird gegen diesen Stand gerechnet, nicht gemerkt: Wer
     * erde.tiefe ändert und wieder zurückstellt, soll danach keinen Neustart mehr angemahnt bekommen.
     */
    private final Map<String, Object> atStart;

    ConfigReload(BaumeisterPlugin plugin) {
        this.plugin = plugin;
        this.atStart = flat(plugin.getConfig(), defaults(plugin.getConfig()));
    }

    /** Schlüssel, deren Wert in der Config vom Stand beim Start abweicht und die erst ein Neustart übernimmt. */
    Set<String> restartPending() {
        return ReloadPlan.restartPending(atStart, flat(plugin.getConfig(), defaults(plugin.getConfig())));
    }

    private static Configuration defaults(Configuration c) {
        Configuration d = c.getDefaults();
        return d != null ? d : new YamlConfiguration();
    }

    void run(CommandSender sender, String[] args) {
        boolean force = args.length > 1 && List.of("erzwingen", "force").contains(args[1].toLowerCase(Locale.ROOT));
        YamlConfiguration bundled;
        try {
            bundled = ConfigMigration.bundled(plugin);
        } catch (IOException | InvalidConfigurationException e) {
            LOG.log(Level.WARNING, "konfig | vorlage_unlesbar", e);
            Texts.info(sender, "Vorlage im JAR nicht lesbar: " + e.getMessage() + " – nichts geändert.");
            return;
        }
        Prepared p = prepare(plugin.getDataFolder().toPath().resolve("config.yml"), bundled);
        if (p.error() != null) {
            LOG.warning(() -> "konfig | neuladen_abgelehnt | " + oneLine(p.error()));
            Texts.info(sender, p.error() + " – nichts geändert.");
            return;
        }
        YamlConfiguration fresh = p.fresh();
        Settings freshSettings = p.settings();
        List<String> warnings = p.warnings();
        BuildService service = plugin.service();
        boolean busy = service.busy();
        // statusLine ist im Leerlauf genau „bereit“ oder „bereit, N Bau(ten) rückgängig machbar“ – eine eigene
        // Abfrage des Stapels gibt es nicht, und BuildService bleibt in diesem Paket unangetastet.
        boolean undoPresent = !busy && !service.statusLine().equals("bereit");
        WorldBuildService weltbau = plugin.weltbau();
        ReloadPlan.Outcome plan = ReloadPlan.decide(new ReloadPlan.Input(plugin.settings(), freshSettings,
                flat(plugin.getConfig(), bundled), flat(fresh, bundled), busy, undoPresent, force,
                plugin.city().running(), weltbau != null && weltbau.running()));

        plugin.reloadConfig();
        if (plugin.town() != null) {
            // Stadtleben ohne neuen KI-Client: Nur Schalter, Anzahl und Abstand ändern sich.
            plugin.town().reload(com.blackn0va.baumeister.town.TownSettings.from(plugin.getConfig()));
        }
        try {
            if (plan.replaceBuildService()) {
                // Baut BuildService samt LlmClient und Bauteilen neu, benennt den NPC um, lädt die Kartendienste.
                plugin.rebuildBuildService();
            } else {
                plugin.city().reload();
                if (plan.reloadModules() && plugin.schematics() instanceof SchematicLibrary lib) {
                    lib.reload();
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "konfig | neuladen_fehlgeschlagen", e);
            Texts.info(sender, "Neu laden fehlgeschlagen: " + e.getMessage()
                    + " – der laufende Baumeister arbeitet mit den alten Bau-Einstellungen weiter.");
            return;
        }
        Set<String> restartPending = restartPending();
        LOG.info(() -> "konfig | neu_geladen | geändert=" + String.join(",", plan.changed()) + " baudienst_neu="
                + plan.replaceBuildService() + " bau_läuft=" + busy + " erzwingen=" + force + " neustart_nötig="
                + String.join(",", restartPending));
        for (String line : plan.lines()) {
            Texts.info(sender, line);
        }
        for (String w : warnings) {
            LOG.warning(() -> "konfig | prüfung | " + w);
        }
        for (int i = 0; i < Math.min(MAX_WARNINGS, warnings.size()); i++) {
            Texts.info(sender, "Prüfung: " + warnings.get(i));
        }
        if (warnings.size() > MAX_WARNINGS) {
            Texts.info(sender, "… und " + (warnings.size() - MAX_WARNINGS) + " weitere Hinweise im Server-Log.");
        }
        Set<String> older = new LinkedHashSet<>(restartPending);
        older.removeAll(plan.restartKeys());
        if (!older.isEmpty()) {
            Texts.info(sender, "Noch von früher offen, wirkt erst nach Neustart: " + String.join(", ", older) + ".");
        }
    }

    /** Gelesene und geprüfte Datei, oder error (dann ist nichts anderes gesetzt). */
    record Prepared(YamlConfiguration fresh, Settings settings, List<String> warnings, String error) {
    }

    /**
     * Datei lesen und prüfen, ohne irgendetwas im Plugin anzufassen. Kein YamlConfiguration.loadConfiguration: Das
     * schluckt Syntaxfehler und gibt eine leere Config zurück (siehe Klassenkommentar).
     */
    static Prepared prepare(Path file, YamlConfiguration bundled) {
        YamlConfiguration fresh;
        try {
            fresh = ConfigMigration.load(file);
        } catch (InvalidConfigurationException e) {
            return new Prepared(null, null, List.of(),
                    "config.yml ist kein gültiges YAML: " + firstLine(e.getMessage()));
        } catch (IOException e) {
            return new Prepared(null, null, List.of(), "config.yml nicht lesbar: " + e.getMessage());
        }
        List<String> warnings = ConfigCheck.check(fresh, bundled);
        fresh.setDefaults(bundled);
        try {
            return new Prepared(fresh, Settings.from(fresh), warnings, null);
        } catch (IllegalArgumentException e) {
            return new Prepared(null, null, warnings, "Ungültig: " + e.getMessage());
        }
    }

    /**
     * Alle Blätter mit dem Wert, der im Code ankommt: Schlüssel aus Vorlage und Datei, fehlende mit der Vorgabe.
     * Ohne die Vorgaben sähe ein Schlüssel, der in der Datei fehlt, wie „geändert“ aus.
     */
    static Map<String, Object> flat(Configuration cfg, Configuration bundled) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(bundled.getKeys(true));
        keys.addAll(cfg.getKeys(true));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) {
            if (cfg.isConfigurationSection(k) || bundled.isConfigurationSection(k) && !cfg.contains(k, true)) {
                continue;
            }
            Object v = cfg.contains(k, true) ? cfg.get(k) : bundled.get(k);
            out.put(k, v);
        }
        return out;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
