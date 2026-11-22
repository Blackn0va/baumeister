package com.blackn0va.baumeister;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Prüft eine config.yml gegen die mitgelieferte Vorlage und sagt, was der Code stillschweigend anders macht, als der
 * Admin es geschrieben hat.
 *
 * Warum: Bukkit verzeiht alles. `getInt` auf „schnell“ liefert die Vorgabe, `getBoolean` auf „nein“ ebenso, ein
 * Tippfehler im Schlüssel (`max-bloecke` statt `max-blöcke`) wird nie gelesen, und Settings/CityService umlaut-ok
 * begrenzen Zahlen mit Math.clamp ohne Meldung. Der Admin sieht seinen Wert in der Datei und wundert sich, warum er
 * nicht wirkt.
 * Jetzt steht beim Start und bei /bm neuladen je Fall eine Zeile im Log bzw. beim Admin.
 *
 * Nur Warnungen, nie Wirkung: Was gilt, entscheiden weiter Settings.from und die Dienste. Die Grenzen hier spiegeln
 * deren Math.clamp/cfg-Aufrufe. Wer dort eine Grenze ändert, zieht sie hier nach – sonst warnt die Prüfung falsch
 * (ConfigCheckTest gleicht die Settings-Grenzen ab und wird dann rot).
 *
 * Threads: beliebig (reine Rechnung). Fehler: keine – unlesbare Werte werden gemeldet, nicht geworfen.
 */
final class ConfigCheck {

    /** Zulässiger Bereich einer Zahl, so wie der Code sie begrenzt. */
    record Range(double min, double max) {
    }

    /** Spiegel der Math.clamp/cfg-Grenzen in Settings, CityService, WorldBuildService und EarthService.Config. */
    static final Map<String, Range> RANGES = Map.ofEntries(
            Map.entry("llm.timeout-sekunden", new Range(10, 1800)),
            Map.entry("llm.max-tokens", new Range(256, 8192)),
            Map.entry("llm.temperatur", new Range(0.0, 1.5)),
            Map.entry("llm.reparatur-versuche", new Range(0, 3)),
            Map.entry("bau.ticks-pro-block", new Range(1, 40)),
            Map.entry("bau.max-blöcke", new Range(10, 1_000_000)),
            Map.entry("bau.max-ausdehnung", new Range(4, 380)),
            Map.entry("bau.abstand-vor-spieler", new Range(1, 16)),
            Map.entry("bau.undo-tiefe", new Range(1, 50)),
            Map.entry("bau.max-überbauen", new Range(0, 1000)),
            Map.entry("bau.zieldauer-sekunden", new Range(10, 3600)),
            Map.entry("bau.lag-grenze-je-sekunde", new Range(1, 1_000_000)),
            Map.entry("helfer.zweiter-ab-blöcken", new Range(1, Integer.MAX_VALUE)),
            Map.entry("helfer.dritter-ab-blöcken", new Range(1, Integer.MAX_VALUE)),
            Map.entry("stadt.radius-standard", new Range(30, 20_000)),
            Map.entry("stadt.radius-max", new Range(50, 20_000)),
            Map.entry("stadt.radius-max-ganz", new Range(100, 60_000)),
            Map.entry("stadt.blöcke-pro-tick", new Range(50, 50_000)),
            Map.entry("stadt.helfer", new Range(0, 16)),
            Map.entry("stadt.neue-chunks-pro-tick", new Range(1, 64)),
            Map.entry("stadt.ms-pro-tick", new Range(5, 45)),
            Map.entry("stadt.lag-ms", new Range(60, 2000)),
            Map.entry("erde.meerestiefe-max", new Range(0, 1000)),
            Map.entry("erde.tiefe", new Range(4, 512)),
            Map.entry("erde.kacheln-im-speicher", new Range(16, 4096)),
            Map.entry("weltbau.radius-kacheln", new Range(0, 4)),
            Map.entry("weltbau.abstand-sekunden", new Range(10, 3600)),
            Map.entry("weltbau.radius-standard", new Range(0, 20_000)),
            Map.entry("weltbau.radius-max", new Range(0, 20_000)),
            Map.entry("weltbau.helfer", new Range(0, 16)),
            Map.entry("weltbau.rückgängig-tiefe", new Range(0, 20)),
            // Stadtleben (2026-11-21): Spiegel von town.TownSettings.from (TownSettingsTest gleicht ab).
            Map.entry("stadtleben.anzahl", new Range(1, 12)),
            Map.entry("stadtleben.ki-abstand-sekunden", new Range(15, 600)));

    /** Schlüssel mit fester Auswahl. */
    static final Map<String, List<String>> CHOICES = Map.of("erde.projektion", List.of("airocean", "sinus6"));

    /**
     * Ältere Schlüssel, die nicht mehr in der Vorlage stehen. stadt.overpass-url (eine Instanz, Configs vor
     * 2026-10-28) wirkte bis 2026-11-01 praktisch NIE: CityService.reload fragte getStringList("stadt.overpass-urls")
     * samt JAR-Vorgaben, die Liste war nie leer (Prüfbefund 2026-10-29). Jetzt liest CityService.overpassUrls Liste
     * und Einzeladresse nur aus der Datei: Ohne eigene Liste gilt die Einzeladresse. Die Prüfung meldet sie deshalb
     * nur noch als veraltet (bitte in die Liste übernehmen) – und als ignoriert, wenn die Datei eine eigene Liste hat.
     */
    static final Map<String, String> LEGACY = Map.of("stadt.overpass-url", "stadt.overpass-urls");

    private ConfigCheck() {
    }

    /**
     * @param actual   die Datei des Admins, OHNE gesetzte Vorgaben (sonst sähe jeder fehlende Schlüssel gültig aus)
     * @param defaults die mitgelieferte config.yml
     * @return je Auffälligkeit eine deutsche Zeile, leer = alles in Ordnung
     */
    static List<String> check(ConfigurationSection actual, ConfigurationSection defaults) {
        List<String> out = new ArrayList<>();
        Set<String> unknownSections = new HashSet<>();
        for (String key : actual.getKeys(true)) {
            if (unknownSections.stream().anyMatch(s -> key.startsWith(s + "."))) {
                continue;
            }
            if (key.startsWith(VoiceAppBinding.SECTION + ".")
                    || key.equals(VoiceAppBinding.SECTION) && !(actual.get(key) instanceof ConfigurationSection)
                    && !VoiceAppBinding.emptyValue(actual.get(key))) {
                String problem = appProblem(key, actual.get(key));
                if (problem != null) {
                    out.add(problem);
                }
                continue;
            }
            if (LEGACY.containsKey(key)) {
                String successor = LEGACY.get(key);
                Object list = actual.get(successor);
                if (list instanceof List<?> l && l.isEmpty()) {
                    // Ausdrücklich leere Liste: dann und nur dann gilt die alte Einzeladresse.
                    continue;
                }
                if (actual.contains(successor, true)) {
                    out.add("Veralteter Schlüssel '" + key + "' wird ignoriert, weil '" + successor
                            + "' gesetzt ist – kann weg.");
                } else if (defaults.contains(successor, true) && !isNeutral(successor, actual.get(key))) {
                    // Vorher „wird ignoriert: Es gilt die Vorgabe-Liste“ – seit CityService.overpassUrls stimmt das
                    // nicht mehr, die Einzeladresse wirkt. Veraltet bleibt sie trotzdem.
                    out.add("'" + key + "' = " + show(actual.get(key)) + " ist veraltet, gilt aber: bitte als '"
                            + successor + "' übernehmen (Liste mit '- ').");
                }
                continue;
            }
            if (!defaults.contains(key, true)) {
                if (actual.isConfigurationSection(key)) {
                    unknownSections.add(key);
                }
                out.add("Unbekannter Schlüssel '" + key + "' – wird ignoriert" + suggestion(key, defaults) + ".");
                continue;
            }
            String problem = problem(key, actual.get(key), defaults.get(key));
            if (problem != null) {
                out.add(problem);
            }
        }
        return out;
    }

    /**
     * fernverbindung.apps: Die Namen darunter vergibt der Admin (Pin-Namen der Apps im Relay), sie stehen also nie in
     * der Vorlage – ohne diese Ausnahme wäre jeder Eintrag ein „unbekannter Schlüssel“. Geprüft wird die Form. Anders
     * als sonst gilt bei Fehlern nicht die Vorgabe: VoiceAppBinding.from bindet strenger (fail-closed), deshalb sagt
     * jede Meldung, dass Sätze verworfen werden.
     */
    static String appProblem(String key, Object value) {
        if (key.equals(VoiceAppBinding.SECTION)) {
            return "'" + key + "' muss ein Abschnitt sein (App-Name, darunter 'spieler:'), steht: " + show(value)
                    + " – jeder Satz aus einer App wird verworfen, bis das stimmt.";
        }
        String rest = key.substring(VoiceAppBinding.SECTION.length() + 1);
        int dot = rest.indexOf('.');
        String app = dot < 0 ? rest : rest.substring(0, dot);
        if (!VoiceAppBinding.APP_NAME.matcher(app).matches()) {
            // Unterschlüssel einer ungültigen App nicht noch einmal melden.
            return dot >= 0 ? null : "'" + key + "': App-Namen nur aus a–z, 0–9 und '-' (1–32 Zeichen) wie der "
                    + "Pin-Name im Relay – Sätze dieser App werden verworfen.";
        }
        if (dot < 0) {
            return value instanceof ConfigurationSection ? null
                    : "'" + key + "' muss ein Abschnitt mit 'spieler:' sein, steht: " + show(value)
                            + " – Sätze dieser App werden verworfen.";
        }
        String sub = rest.substring(dot + 1);
        if (sub.contains(".")) {
            return null;  // tiefer liegt nichts Gültiges; gemeldet ist schon der Schlüssel darüber
        }
        if (!sub.equals("spieler")) {
            return "Unbekannter Schlüssel '" + key + "' – wird ignoriert (unter einer App gibt es nur 'spieler').";
        }
        if (!(value instanceof List<?> list)) {
            return "'" + key + "' erwartet eine Liste (Zeilen mit '- ' oder [A, B]), steht: " + show(value)
                    + " – diese App darf für niemanden sprechen.";
        }
        List<String> bad = list.stream().filter(o -> !(o instanceof String || o instanceof Integer || o instanceof Long)
                || !VoiceAppBinding.PLAYER_NAME.matcher(String.valueOf(o).strip()).matches())
                .map(String::valueOf).toList();
        return bad.isEmpty() ? null : "'" + key + "' enthält ungültige Spielernamen " + bad
                + " – werden ignoriert (3–16 Zeichen aus A–Z, a–z, 0–9 und _).";
    }

    /** Alter Wert, der dasselbe bewirkt wie der Nachfolger aus der Vorlage (leer oder alte Vorgabe). */
    private static boolean isNeutral(String successor, Object oldValue) {
        ConfigMigration.Successor s = ConfigMigration.SUCCESSORS.get(successor);
        String v = oldValue == null ? "" : String.valueOf(oldValue).strip();
        return v.isEmpty() || s != null && v.equals(String.valueOf(s.neutralOldValue()));
    }

    /** Eine Zeile zu einem Wert, oder null, wenn er so gilt, wie er dasteht. */
    static String problem(String key, Object value, Object def) {
        if (def instanceof ConfigurationSection) {
            return value instanceof ConfigurationSection ? null
                    : "'" + key + "' muss ein Abschnitt mit Unterschlüsseln sein, steht: " + show(value)
                            + " – es gelten die Vorgaben.";
        }
        if (value instanceof ConfigurationSection) {
            return "'" + key + "' ist ein Abschnitt, erwartet ist ein Wert wie " + show(def)
                    + " – es gilt die Vorgabe.";
        }
        if (def instanceof Boolean) {
            return value instanceof Boolean ? null
                    : "'" + key + "' erwartet true oder false, steht: " + show(value) + " – es gilt " + def + ".";
        }
        if (def instanceof Integer || def instanceof Long) {
            if (!(value instanceof Number n)) {
                return "'" + key + "' erwartet eine ganze Zahl, steht: " + show(value) + " – es gilt " + def + ".";
            }
            if (value instanceof Double || value instanceof Float) {
                return "'" + key + "' erwartet eine ganze Zahl, steht: " + show(value) + " – es gilt "
                        + inRange(key, n.longValue()) + ".";
            }
            return outside(key, n.doubleValue());
        }
        if (def instanceof Number) {
            return value instanceof Number n ? outside(key, n.doubleValue())
                    : "'" + key + "' erwartet eine Zahl, steht: " + show(value) + " – es gilt " + def + ".";
        }
        if (def instanceof List) {
            return value instanceof List ? null
                    : "'" + key + "' erwartet eine Liste (Zeilen mit '- '), steht: " + show(value)
                            + " – es gelten die Vorgaben.";
        }
        if (value instanceof List) {
            return "'" + key + "' erwartet einen einzelnen Wert, steht eine Liste – es gilt " + show(def) + ".";
        }
        List<String> choices = CHOICES.get(key);
        if (choices != null && !choices.contains(String.valueOf(value).toLowerCase(Locale.ROOT))) {
            return "'" + key + "' kennt nur " + String.join(", ", choices) + ", steht: " + show(value) + ".";
        }
        return null;
    }

    private static String outside(String key, double v) {
        Range r = RANGES.get(key);
        if (r == null || v >= r.min() && v <= r.max()) {
            return null;
        }
        return "'" + key + "' = " + fmt(v) + " liegt außerhalb " + fmt(r.min()) + "–" + fmt(r.max()) + " – es gilt "
                + fmt(Math.clamp(v, r.min(), r.max())) + ".";
    }

    private static String inRange(String key, long v) {
        Range r = RANGES.get(key);
        return r == null ? String.valueOf(v) : fmt(Math.clamp(v, r.min(), r.max()));
    }

    private static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String show(Object v) {
        if (v instanceof String s) {
            return "'" + s + "'";
        }
        return String.valueOf(v);
    }

    /**
     * Nächster bekannter Schlüssel im selben Abschnitt, wenn er fast gleich ist – der häufigste Fall ist eine Tastatur
     * ohne Umlaute (`max-bloecke`), deshalb wird vor dem Vergleich ä → ae usw. umgeschrieben. umlaut-ok
     */
    static String suggestion(String key, ConfigurationSection defaults) {
        String typed = fold(key);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String known : defaults.getKeys(true)) {
            int d = distance(typed, fold(known));
            if (d < bestDistance) {
                bestDistance = d;
                best = known;
            }
        }
        return best != null && bestDistance <= 3 ? " (gemeint: '" + best + "'?)" : "";
    }

    private static String fold(String s) {
        return s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue") // umlaut-ok
                .replace("ß", "ss"); // umlaut-ok
    }

    /** Levenshtein – Schlüssel sind kurz, quadratisch ist hier egal. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
