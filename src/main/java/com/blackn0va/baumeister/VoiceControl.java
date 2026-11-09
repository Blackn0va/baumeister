package com.blackn0va.baumeister;

import com.blackn0va.baumeister.help.HelpBook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Sprache bedienen statt Config tippen: /bm sprache … (siehe run) und die Seite „Stimme“ im Hilfebuch (2026-11-08).
 * Vorher hieß jede Änderung: config.yml auf dem Server bearbeiten, Pfad zur .onnx von Hand eintragen, /bm neuladen –
 * und welche Stimme wie klingt, ließ sich im Spiel gar nicht vergleichen. Jetzt: Stimme anklicken, der Betroffene
 * (Vera oder der Baumeister) sagt sofort einen Probesatz. Vera hat eine eigene Stimme samt Cartoon-Ton.
 *
 * Zur Wahl stehen nur Piper-Modelle, die schon auf dem Server liegen (Ordner plugins/Baumeister/stimmen/ und der
 * Ordner der aktuellen Stimme), je mit .onnx.json daneben. Der Befehl nimmt nur Namen aus dieser Liste an – nie einen
 * Pfad. Wer das auf freie Pfade öffnet, lässt einen Admin-Klick beliebige Dateien als Modell an Piper geben
 * (OWASP A01/A03). Geändert werden nur sprache.an, sprache.stimme, sprache.vera-stimme und
 * sprache.vera-tonhöhe; geschrieben über saveConfig wie die NPC-UUID.
 *
 * Threads: Hauptthread. Fehler: Dateifehler beim Auflisten heißen „keine Stimmen“, Grund im Log.
 */
final class VoiceControl {

    private static final Logger LOG = Logger.getLogger("Baumeister");
    static final String PROBE = "Hallo! So klinge ich. Sag Bescheid, wenn ich bauen soll.";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,48}");
    private static final int MAX_VOICES = 24;

    private VoiceControl() {
    }

    /** Ordner mit Stimmen: plugins/Baumeister/stimmen/ und der Ordner der aktuellen Stimme (ohne Doppel). */
    static List<Path> dirs(Path dataFolder, String model) {
        List<Path> out = new ArrayList<>();
        out.add(dataFolder.resolve("stimmen").toAbsolutePath().normalize());
        if (model != null && !model.isBlank()) {
            Path parent = Path.of(model).toAbsolutePath().normalize().getParent();
            if (parent != null && !out.contains(parent)) {
                out.add(parent);
            }
        }
        return out;
    }

    /** Name → Modell-Datei aller Stimmen (.onnx mit .onnx.json daneben), nach Namen sortiert, höchstens 24. */
    static Map<String, Path> voices(Path dataFolder, String model) {
        Map<String, Path> found = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Path dir : dirs(dataFolder, model)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(f -> f.getFileName().toString().endsWith(".onnx") && Files.isRegularFile(f)
                        && Files.isRegularFile(f.resolveSibling(f.getFileName() + ".json"))).forEach(f -> {
                            String n = f.getFileName().toString();
                            String name = n.substring(0, n.length() - ".onnx".length());
                            if (NAME.matcher(name).matches()) {
                                found.putIfAbsent(name, f);
                            }
                        });
            } catch (IOException e) {
                LOG.log(Level.WARNING, "sprache | stimmen_unlesbar | ordner=" + dir, e);
            }
        }
        Map<String, Path> out = new LinkedHashMap<>();
        found.entrySet().stream().limit(MAX_VOICES).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Name der Stimme aus dem Modellpfad („…/de_DE-thorsten-medium.onnx“ → „de_DE-thorsten-medium“), sonst "". */
    static String nameOf(String model) {
        if (model == null || model.isBlank()) {
            return "";
        }
        String n = Path.of(model).getFileName().toString();
        return n.endsWith(".onnx") ? n.substring(0, n.length() - ".onnx".length()) : n;
    }

    /** Kurzer Zustand fürs Buch (≤ 17 Zeichen), leer wenn alles bereit ist. */
    static String shortProblem(LocalVoice.Settings s, boolean running) {
        if (!s.enabled()) {
            return "";
        }
        if (s.piper().isEmpty() || !Files.isExecutable(Path.of(s.piper()))) {
            return "Piper fehlt";
        }
        if (s.problem().isPresent()) {
            return "Stimme fehlt";
        }
        return running ? "" : "startet nicht";
    }

    /** Seite „Stimme“ im Hilfebuch: Baumeister- und Vera-Stimme, Cartoon-Ton. */
    static HelpBook.Voice panel(BaumeisterPlugin plugin) {
        ConfigurationSection c = plugin.getConfig();
        LocalVoice.Settings s = LocalVoice.Settings.from(c);
        LocalVoice.Settings v = LocalVoice.Settings.forVera(c);
        return new HelpBook.Voice(s.enabled(), nameOf(s.model()),
                List.copyOf(voices(plugin.getDataFolder().toPath(), s.model()).keySet()),
                shortProblem(s, plugin.localVoice() != null), nameOf(v.model()), v.pitch() > 1.0);
    }

    /** Tonhöhe aus „normal“, „cartoon“ oder einer Zahl (1,3 / 1.3); leer bei Unsinn. */
    static Optional<Double> pitch(String word) {
        return switch (word.toLowerCase(java.util.Locale.ROOT)) {
            case "normal", "aus" -> Optional.of(1.0);
            case "cartoon", "toon", "an" -> Optional.of(LocalVoice.CARTOON);
            default -> {
                try {
                    double d = Double.parseDouble(word.replace(',', '.'));
                    yield d >= 0.7 && d <= 1.8 ? Optional.of(d) : Optional.empty();
                } catch (NumberFormatException e) {
                    yield Optional.empty();
                }
            }
        };
    }

    /**
     * /bm sprache [an|aus|stimme &lt;name&gt;|vera &lt;name&gt;|ton &lt;normal|cartoon|zahl&gt;|probe [vera|baumeister]]
     * (nur Admins; geprüft in BaumeisterCommand). stimme = Baumeister, vera = Vera, ton = Veras Tonhöhe.
     */
    static void run(BaumeisterPlugin plugin, CommandSender s, String[] args) {
        String verb = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "status";
        ConfigurationSection c = plugin.getConfig();
        switch (verb) {
            case "an", "aus" -> {
                c.set("sprache.an", verb.equals("an"));
                saved(plugin, s, "an=" + verb.equals("an"), true);
            }
            case "stimme", "vera" -> {
                if (args.length < 3) {
                    status(plugin, s);
                    return;
                }
                LocalVoice.Settings now = LocalVoice.Settings.from(c);
                Optional<Path> pick = Optional.ofNullable(voices(plugin.getDataFolder().toPath(), now.model())
                        .get(args[2]));
                if (pick.isEmpty()) {
                    Texts.info(s, "Diese Stimme liegt nicht auf dem Server. /bm sprache zeigt, welche es gibt.");
                    return;
                }
                boolean vera = verb.equals("vera");
                c.set(vera ? "sprache.vera-stimme" : "sprache.stimme", pick.get().toString());
                saved(plugin, s, (vera ? "vera=" : "baumeister=") + args[2], vera);
            }
            case "ton" -> {
                Optional<Double> p = args.length < 3 ? Optional.empty() : pitch(args[2]);
                if (p.isEmpty()) {
                    Texts.info(s, "/bm sprache ton normal | cartoon | 0,7–1,8 – Veras Tonhöhe.");
                    return;
                }
                c.set("sprache.vera-tonhöhe", p.get());
                saved(plugin, s, "vera_tonhöhe=" + p.get(), true);
            }
            case "probe" -> {
                if (s instanceof Player p) {
                    boolean vera = !(args.length > 2 && args[2].equalsIgnoreCase("baumeister"));
                    plugin.speakProbe(p, vera ? VERA_PROBE : PROBE, vera);
                } else {
                    Texts.info(s, "Die Probe hört nur ein Spieler im Spiel.");
                }
            }
            default -> status(plugin, s);
        }
    }

    /** Vera stellt sich vor – kurz, damit man den Klang sofort hört. */
    static final String VERA_PROBE = "Hey! Ich bin Vera, dein Kumpel. Wollen wir was Cooles bauen?";

    /** Nach jeder Änderung: speichern, Stimmen neu öffnen, Zustand zeigen, Probe des Betroffenen, Buch wieder auf. */
    private static void saved(BaumeisterPlugin plugin, CommandSender s, String what, boolean vera) {
        plugin.saveConfig();
        plugin.reopenVoice();
        LOG.info(() -> "sprache | geändert | von=" + s.getName() + " " + what);
        status(plugin, s);
        if (s instanceof Player p && (plugin.localVoice() != null || plugin.veraVoice() != null)) {
            plugin.speakProbe(p, vera ? VERA_PROBE : PROBE, vera);
        }
        if (s instanceof Player p && plugin.help() != null) {
            plugin.help().openChapter(p, HelpBook.VOICE_CHAPTER);
        }
    }

    private static void status(BaumeisterPlugin plugin, CommandSender s) {
        LocalVoice.Settings st = LocalVoice.Settings.from(plugin.getConfig());
        LocalVoice.Settings v = LocalVoice.Settings.forVera(plugin.getConfig());
        Map<String, Path> all = voices(plugin.getDataFolder().toPath(), st.model());
        Texts.info(s, "Sprache vom Server: " + (st.enabled() ? "an" : "aus") + " · Baumeister: "
                + (nameOf(st.model()).isEmpty() ? "keine" : nameOf(st.model())) + " · Vera: "
                + (nameOf(v.model()).isEmpty() ? "keine" : nameOf(v.model())) + String.format(java.util.Locale.GERMANY,
                        " (Tonhöhe %.2f)", v.pitch()) + " · Quelle für Vera: " + (st.preferServer() ? "Server" : "App"));
        st.problem().filter(x -> st.enabled()).ifPresent(x -> Texts.info(s, "Geht noch nicht: " + x + "."));
        String vv = plugin.veraVoice() == null ? "" : plugin.veraVoice().prewarmState();
        String bv = plugin.localVoice() == null ? "" : plugin.localVoice().prewarmState();
        if (!vv.isEmpty() || !bv.isEmpty()) {
            Texts.info(s, "Vorrat fester Sätze: Vera " + (vv.isEmpty() ? "–" : vv) + " · Baumeister "
                    + (bv.isEmpty() ? "–" : bv));
        }
        Texts.info(s, all.isEmpty() ? "Keine Stimmen gefunden. Piper-Modelle (.onnx samt .onnx.json) gehören nach "
                + "plugins/Baumeister/stimmen/." : "Stimmen: " + String.join(" · ", all.keySet())
                + " – /bm sprache stimme|vera <name> · ton normal|cartoon · an · aus · probe");
    }
}
