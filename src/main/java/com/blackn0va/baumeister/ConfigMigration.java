package com.blackn0va.baumeister;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bringt eine bestehende plugins/Baumeister/config.yml auf den Stand der mitgelieferten Vorlage: fehlende Schlüssel
 * kommen samt ihrem Kommentar dazu, vorhandene Werte bleiben, wie sie sind.
 *
 * Warum: saveDefaultConfig schreibt die Vorlage nur, wenn es noch gar keine Datei gibt. Auf dev01 liegt seit dem
 * ersten Deploy (2026-10-28) eine Config, die seither von Hand ergänzt wurde (stadt:, Grenzen) – erde:, weltbau:,
 * lag-ms … fehlen dort. Der Code hat für jeden Schlüssel eine Vorgabe, es läuft also; aber der Admin sieht die
 * Schlüssel nicht und weiß nicht, was er einstellen kann. Die Config ersetzen geht nicht (NPC-UUID, angehobene
 * Grenzen).
 *
 * Regeln:
 * - Vorhandene Werte werden NIE geändert – auch nicht „repariert“. Unsinn meldet ConfigCheck, mehr nicht.
 * - Ein Schlüssel, dessen Elternteil in der Datei ein Wert statt eines Abschnitts ist, wird nicht ergänzt (sonst
 *   überschriebe der neue Abschnitt diesen Wert).
 * - Ersetzt ein neuer Schlüssel einen alten (LEGACY), kommt er nur dazu, wenn der alte fehlt oder noch die alte
 *   Vorgabe ist: stadt.overpass-urls nicht bei eigener stadt.overpass-url. Seit CityService.overpassUrls
 *   (2026-11-01) gilt eine Liste in der Datei vor der Einzeladresse – eine ergänzte Vorgabe-Liste würde die
 *   eigene Instanz des Admins also still überstimmen. Wer diese Regel zurückdreht, schickt eigene Overpass-Nutzer wieder an die
 *   öffentlichen Dienste.
 * - Vor dem Schreiben eine Sicherung unter sicherung/ (die letzten 10), geschrieben wird über eine Temp-Datei und
 *   Umbenennen; danach wird die Datei neu gelesen und jeder alte Wert verglichen – weicht einer ab, kommt die
 *   Sicherung zurück.
 * - Ist die Datei kein gültiges YAML, wird nichts geschrieben (sonst wäre der Inhalt des Admins weg).
 *
 * Kommentare: YamlConfiguration liest sie seit 1.18 mit (parseComments); Einrückung und Listen schreibt Bukkit in
 * seiner eigenen Form – dieselbe, die /bm spawn beim Merken der NPC-UUID über saveConfig schon immer erzeugt.
 *
 * Threads: nur beim Start (onEnable, Hauptthread), vor dem ersten getConfig(). Fehler: IOException beim Schreiben
 * wird geloggt, die alte Datei bleibt; der Server startet in jedem Fall weiter (fehlende Schlüssel haben Vorgaben im
 * Code – bewusst fail-open, weil die Migration nur Dokumentation ergänzt, nie Verhalten).
 */
final class ConfigMigration {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final int KEEP_BACKUPS = 10;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    /** Neuer Schlüssel → alter Schlüssel, den er ersetzt, und der alte Wert, bei dem beide dasselbe bewirken. */
    record Successor(String oldKey, Object neutralOldValue) {
    }

    static final Map<String, Successor> SUCCESSORS = Map.of(
            "stadt.overpass-urls", new Successor("stadt.overpass-url", CityService.OVERPASS_DEFAULT.getFirst()));

    /**
     * @param added   ergänzte Schlüssel (Blätter und neue Abschnitte), in Reihenfolge der Vorlage
     * @param skipped nicht ergänzt, mit Grund
     * @param backup  Sicherung vor dem Schreiben, null wenn nichts geschrieben wurde
     */
    record Result(List<String> added, List<String> skipped, Path backup) {

        boolean changed() {
            return backup != null;
        }
    }

    private ConfigMigration() {
    }

    /** Beim Start aus onEnable: Vorlage aus dem JAR, Datei im Datenordner, Ergebnis und ConfigCheck ins Log. */
    /**
     * @return false, wenn die config.yml des Admins kein gültiges YAML ist – dann startet das Plugin nicht.
     *     Vorher lief es mit den JAR-Vorgaben weiter, und das nächste saveConfig (NPC-UUID, Reisepunkte …)
     *     überschrieb die Datei des Admins mit fast nichts. Wer hier wieder true liefert, holt diesen Datenverlust
     *     zurück. /bm neuladen ist schon fail-closed (ConfigReload.prepare).
     */
    static boolean onStart(JavaPlugin plugin) {
        Path file = plugin.getDataFolder().toPath().resolve("config.yml");
        String broken = brokenReason(file);
        if (broken != null) {
            LOG.severe(() -> "konfig | kaputt | datei=" + file + " grund=" + broken
                    + " – Plugin bleibt aus, die Datei wird nicht angefasst");
            return false;
        }
        YamlConfiguration bundled;
        try {
            bundled = bundled(plugin);
        } catch (IOException | InvalidConfigurationException e) {
            LOG.log(Level.WARNING, "konfig | vorlage_unlesbar | " + e.getMessage(), e);
            return true;
        }
        try {
            Result r = migrate(file, bundled, plugin.getDataFolder().toPath().resolve("sicherung"),
                    LocalDateTime.now());
            if (r.changed()) {
                LOG.info(() -> "konfig | ergänzt | anzahl=" + r.added().size() + " schlüssel="
                        + String.join(",", r.added()) + " sicherung=" + r.backup().getFileName());
            }
            for (String s : r.skipped()) {
                LOG.info(() -> "konfig | nicht_ergänzt | " + s);
            }
        } catch (InvalidConfigurationException e) {
            // Oben schon ausgeschlossen; bleibt als Sicherung, falls die Datei zwischen beiden Lesungen kaputtgeht.
            LOG.severe(() -> "konfig | kaputt | datei=" + file + " grund=" + oneLine(e.getMessage()));
            return false;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "konfig | ergänzen_fehlgeschlagen | alte Datei bleibt", e);
        }
        try {
            YamlConfiguration actual = load(file);
            for (String w : ConfigCheck.check(actual, bundled)) {
                LOG.warning(() -> "konfig | prüfung | " + w);
            }
        } catch (IOException | InvalidConfigurationException e) {
            LOG.warning(() -> "konfig | prüfung_unmöglich | " + oneLine(e.getMessage()));
        }
        return true;
    }

    /**
     * Grund, warum die Datei kein gültiges YAML ist, oder null (gültig oder noch nicht vorhanden – die legt
     * saveDefaultConfig an). Ein Lesefehler (IOException) zählt nicht als kaputt: Den meldet Bukkit selbst, und
     * die Datei bleibt unberührt, weil migrate dann ebenfalls nicht schreibt.
     */
    static String brokenReason(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            load(file);
            return null;
        } catch (InvalidConfigurationException e) {
            return oneLine(e.getMessage());
        } catch (IOException e) {
            return null;
        }
    }

    /** Die mitgelieferte config.yml (im JAR). */
    static YamlConfiguration bundled(JavaPlugin plugin) throws IOException, InvalidConfigurationException {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                throw new IOException("config.yml fehlt im JAR");
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    static YamlConfiguration parse(Reader reader) throws IOException, InvalidConfigurationException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().parseComments(true);
        y.load(reader);
        return y;
    }

    static YamlConfiguration load(Path file) throws IOException, InvalidConfigurationException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(r);
        }
    }

    /**
     * Ergänzt fehlende Schlüssel. Ohne Datei passiert nichts (die schreibt saveDefaultConfig).
     *
     * @throws InvalidConfigurationException Datei ist kein gültiges YAML – dann ist nichts geschrieben
     */
    static Result migrate(Path file, YamlConfiguration bundled, Path backupDir, LocalDateTime now)
            throws IOException, InvalidConfigurationException {
        if (!Files.isRegularFile(file)) {
            return new Result(List.of(), List.of(), null);
        }
        YamlConfiguration cfg = load(file);
        Map<String, Object> before = leaves(cfg);
        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> blockers = new HashSet<>();
        for (String key : bundled.getKeys(true)) {
            if (cfg.contains(key, true)) {
                continue;
            }
            String blocker = blockingAncestor(cfg, key);
            if (blocker != null) {
                // Nur einmal je Abschnitt melden, nicht für jeden seiner Unterschlüssel.
                if (blockers.add(blocker)) {
                    skipped.add(blocker + " ist in der Datei ein Wert, kein Abschnitt – Unterschlüssel nicht ergänzt");
                }
                continue;
            }
            Successor succ = SUCCESSORS.get(key);
            if (succ != null && cfg.contains(succ.oldKey(), true)
                    && !Objects.equals(cfg.get(succ.oldKey()), succ.neutralOldValue())) {
                skipped.add(key + " – " + succ.oldKey() + " ist eigens gesetzt und wird nicht überstimmt (wirkt derzeit "
                        + "trotzdem nicht, siehe Prüfung)");
                continue;
            }
            if (bundled.isConfigurationSection(key)) {
                cfg.createSection(key);
            } else {
                cfg.set(key, bundled.get(key));
            }
            copyComments(bundled, cfg, key);
            added.add(key);
        }
        if (added.isEmpty()) {
            return new Result(List.of(), skipped, null);
        }
        Path backup = backup(file, backupDir, now);
        writeAtomically(file, cfg.saveToString());
        verify(file, before, backup);
        return new Result(added, skipped, backup);
    }

    /** Ein Vorfahr des Schlüssels, der in der Datei ein Wert statt eines Abschnitts ist – oder null. */
    private static String blockingAncestor(ConfigurationSection cfg, String key) {
        int dot = key.indexOf('.');
        while (dot > 0) {
            String parent = key.substring(0, dot);
            if (cfg.contains(parent, true) && !cfg.isConfigurationSection(parent)) {
                return parent;
            }
            dot = key.indexOf('.', dot + 1);
        }
        return null;
    }

    private static void copyComments(YamlConfiguration from, YamlConfiguration to, String key) {
        List<String> above = from.getComments(key);
        if (!above.isEmpty()) {
            to.setComments(key, above);
        }
        List<String> inline = from.getInlineComments(key);
        if (!inline.isEmpty()) {
            to.setInlineComments(key, inline);
        }
    }

    /** Alle Blätter (keine Abschnitte) mit Wert. */
    static Map<String, Object> leaves(ConfigurationSection c) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String key : c.getKeys(true)) {
            if (!c.isConfigurationSection(key)) {
                out.put(key, c.get(key));
            }
        }
        return out;
    }

    static Path backup(Path file, Path dir, LocalDateTime now) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve("config-" + STAMP.format(now) + ".yml");
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        prune(dir);
        return target;
    }

    /** Nur die letzten KEEP_BACKUPS Sicherungen behalten – die Namen sortieren sich nach der Zeit. */
    private static void prune(Path dir) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.list(dir)) {
            all = s.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("config-") && n.endsWith(".yml");
            }).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        for (int i = 0; i < all.size() - KEEP_BACKUPS; i++) {
            Files.deleteIfExists(all.get(i));
        }
    }

    private static void writeAtomically(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".neu");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Manche Dateisysteme (Netzlaufwerke) können kein atomares Ersetzen – dann ein normales Ersetzen, die
            // Sicherung liegt ja schon.
            LOG.fine(() -> "konfig | nicht_atomar | " + e.getMessage());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Gegenlesen nach dem Schreiben: Jeder alte Wert muss unverändert da sein. Sonst Sicherung zurück und Fehler –
     * eine Migration, die Werte verliert, ist schlimmer als keine.
     */
    private static void verify(Path file, Map<String, Object> before, Path backup) throws IOException {
        Map<String, Object> after;
        try {
            after = leaves(load(file));
        } catch (InvalidConfigurationException e) {
            Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("ergänzte Datei ist kein gültiges YAML – Sicherung zurückgespielt", e);
        }
        for (Map.Entry<String, Object> e : before.entrySet()) {
            if (!Objects.equals(e.getValue(), after.get(e.getKey()))) {
                Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
                throw new IOException("Wert von " + e.getKey() + " wäre geändert worden (" + e.getValue() + " → "
                        + after.get(e.getKey()) + ") – Sicherung zurückgespielt");
            }
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
