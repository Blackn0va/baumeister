package com.blackn0va.baumeister.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verzeichnis der Bauwerk-Dateien aus plugins/Baumeister/schematics/ (Wunsch 2026-11-08) – Namen, Anzeigenamen,
 * Herkunft und die Erkennung im
 * Chat-Auftrag. Ohne Bukkit und ohne Dateien, damit es testbar ist; das Lesen macht FileSchematics.
 *
 * Erkannt wird eng wie bei QuickOrders: Der Auftrag besteht nur aus Füllwörtern, dem Namen einer Familie
 * („Taktgeber“, „Tür- und Toranlage“) und höchstens einer Nummer („Variante 3“, „Nr. 3“, „3“). Ohne Nummer gilt
 * Variante 1, und die Antwort sagt, wie viele es gibt. Alles andere („ein Haus mit Taktgeber im Keller“) bleibt beim
 * LLM, das dann die passenden Namen als Hinweis bekommt (hint).
 *
 * Warum nicht alle Namen in den LLM-Prompt: 800 Einträge kosteten jeden Auftrag Tausende Token – derselbe Grund, aus
 * dem VanillaTemplates.all() leer ist.
 *
 * Threads: unveränderlich nach dem Bau. Fehler: keine – unbekannte Wörter heißen „kein Treffer“.
 */
public final class SchematicCatalog {

    /** Ein Eintrag: name = Bauteil-Name ([a-z0-9_]), display = „Taktgeber, Variante 3“, family = „Taktgeber“. */
    public record Entry(String name, String display, String family, int variant, String description, String source) {
    }

    /** Treffer: welches Bauteil, wie viele Varianten die Familie hat. */
    public record Match(Entry entry, int variants) {
    }

    public static final Pattern NAME = Pattern.compile("[a-z0-9_]{2,32}");
    private static final Pattern VARIANT = Pattern.compile("(.*?),?\\s*Variante\\s+(\\d+)\\s*$");
    /**
     * Wörter ohne Inhalt für den Auftrag. Seit 2026-11-15 auch Anrede und Verben wie im TemplateCatalog: Vorher fiel
     * „kannst du mir einen Wüstentempel bauen?“ durch und baute das Tempelhaus aus dem Wüstendorf (Review). Bewusst
     * nicht: Größe, Anzahl, Ort, Material („groß“, „mit“, „aus“) – das sind Zusätze, die woanders hingehören.
     */
    private static final Set<String> FILLER = Set.of("bau", "baue", "bitte", "mir", "uns", "mal", "ein", "eine",
            "einen", "das", "die", "der", "den", "hier", "hin", "variante", "nr", "nummer", "schematic", "baumeister",
            "bauen", "mach", "mache", "machen", "errichte", "stell", "stelle", "setz", "setze", "kannst",
            "koenntest", "wuerdest", "du", "ich", "moechte", "will", "wir", "haette", // umlaut-ok: normalisierte Form
            "gern", "gerne", "doch", "jetzt", "sofort",
            "echt", "echtes", "echten", "echte", "original", "typisch", "typischen", "minecraft", "vanilla", "vera",
            "und", "noch", "auch");

    private final Map<String, Entry> byName;
    /** Familie (normalisiert) → Einträge nach Variante. */
    private final Map<String, List<Entry>> families = new LinkedHashMap<>();
    /** Wörter, die eine Familie treffen: der eigene Name und Zweitnamen (normalisiert) → Familie (normalisiert). */
    private final Map<String, String> keys = new LinkedHashMap<>();
    /** Stichwort (normalisiert) → genaue Herkunft; die Suche nach dem Stichwort listet genau diese Familien. */
    private final Map<String, String> tags = new LinkedHashMap<>();

    public SchematicCatalog(List<Entry> entries) {
        this(entries, Map.of());
    }

    /**
     * Mit Zweitnamen je Familie (Anzeigename → weitere Namen), etwa „Hexenhütte“ → „Sumpfhütte“. Ein Zweitname, der
     * selbst eine Familie ist oder schon einer anderen gehört, zählt nicht – der eigene Name geht immer vor.
     */
    public SchematicCatalog(List<Entry> entries, Map<String, List<String>> aliases) {
        this(entries, aliases, Map.of());
    }

    /**
     * Dazu Stichwörter für die Katalogsuche, die genau eine Herkunft treffen: Wert = Anfang der Herkunftsspalte
     * („spiel“ → „Minecraft, auf diesem Server erzeugt“, „technik“ → „Technik Wiki“). Eine freie Suche in der
     * Herkunftsspalte reicht dafür nicht: „minecraft“ steckt auch in „Technik Wiki (de.minecraft.wiki)“.
     */
    public SchematicCatalog(List<Entry> entries, Map<String, List<String>> aliases, Map<String, String> tags) {
        tags.forEach((word, source) -> this.tags.put(RequestHints.normalize(word), source));
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Entry e : entries) {
            m.putIfAbsent(e.name(), e);
        }
        byName = Map.copyOf(m);
        for (Entry e : m.values()) {
            families.computeIfAbsent(RequestHints.normalize(e.family()), k -> new ArrayList<>()).add(e);
        }
        families.values().forEach(l -> l.sort(Comparator.comparingInt(Entry::variant)));
        families.keySet().forEach(k -> keys.put(k, k));
        aliases.forEach((family, names) -> {
            String target = RequestHints.normalize(family);
            if (families.containsKey(target)) {
                for (String n : names) {
                    keys.putIfAbsent(RequestHints.normalize(n), target);
                }
            }
        });
    }

    /** Eintrag aus einer index.tsv-Zeile bzw. einem Dateinamen; display „X, Variante 3“ → family X, variant 3. */
    public static Entry entry(String name, String display, String description, String source) {
        String d = display == null || display.isBlank() ? name.replace('_', ' ') : display.strip();
        Matcher m = VARIANT.matcher(d);
        String family = m.matches() ? m.group(1).strip() : d;
        int variant = m.matches() ? Integer.parseInt(m.group(2)) : 1;
        return new Entry(name, d, family, variant, description == null ? "" : description.strip(),
                source == null ? "" : source.strip());
    }

    public Optional<Entry> find(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public int size() {
        return byName.size();
    }

    /** Familien mit Anzahl; Suchwort in Name, Zweitname oder Herkunft (normalisiert, Teilwort), alphabetisch. */
    public List<Map.Entry<String, Integer>> families(String filter) {
        String f = RequestHints.normalize(filter == null ? "" : filter);
        List<Map.Entry<String, Integer>> out = new ArrayList<>();
        java.util.Set<String> hit = new java.util.LinkedHashSet<>();
        String tagged = tags.get(f);
        if (tagged != null) {
            families.forEach((key, l) -> {
                if (l.getFirst().source().startsWith(tagged)) {
                    hit.add(key);
                }
            });
            for (String family : hit) {
                List<Entry> l = families.get(family);
                out.add(Map.entry(l.getFirst().family(), l.size()));
            }
            out.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
            return out;
        }
        keys.forEach((key, family) -> {
            if (f.isEmpty() || key.contains(f)) {
                hit.add(family);
            }
        });
        // Herkunft nur über Stichwörter (tags: „spiel“, „technik“). Die freie Teilwortsuche in der Herkunftsspalte
        // listete bei „mine“ alle 234 Familien („Minecraft“) und bei „erz“ alle Spiel-Bauwerke („erzeugt“).
        for (String family : hit) {
            List<Entry> l = families.get(family);
            out.add(Map.entry(l.getFirst().family(), l.size()));
        }
        out.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Alle Varianten einer Familie (Name wie in families), nach Nummer; leer bei unbekannter Familie. */
    public List<Entry> variants(String family) {
        String key = keys.get(RequestHints.normalize(family == null ? "" : family));
        List<Entry> l = key == null ? null : families.get(key);
        return l == null ? List.of() : List.copyOf(l);
    }

    /** Chat-Auftrag nur aus Füllwörtern, einem Familiennamen und höchstens einer Nummer → dieses Bauteil. */
    public Optional<Match> match(String prompt) {
        List<String> tokens = new ArrayList<>(RequestHints.tokens(RequestHints.normalize(prompt)));
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        tokens = joinSplitCompounds(tokens);
        String bestKey = null;
        int bestStart = -1;
        int bestLen = 0;
        for (String key : keys.keySet()) {
            String[] parts = key.split(" ");
            for (int i = 0; i + parts.length <= tokens.size(); i++) {
                if (parts.length > bestLen && List.of(parts).equals(tokens.subList(i, i + parts.length))) {
                    bestKey = key;
                    bestStart = i;
                    bestLen = parts.length;
                }
            }
            // „Dropaufzug“ für „Drop-Aufzug“: zusammengeschrieben zählt als ein Wort.
            String joined = key.replace(" ", "");
            for (int i = 0; parts.length > 1 && i < tokens.size(); i++) {
                if (bestLen < 1 && tokens.get(i).equals(joined)) {
                    bestKey = key;
                    bestStart = i;
                    bestLen = 1;
                }
            }
        }
        if (bestKey == null) {
            return Optional.empty();
        }
        Integer number = null;
        for (int i = 0; i < tokens.size(); i++) {
            if (i >= bestStart && i < bestStart + bestLen) {
                continue;
            }
            String t = tokens.get(i);
            java.util.OptionalInt num = t.matches("\\d{1,3}") ? java.util.OptionalInt.of(Integer.parseInt(t))
                    : RequestHints.number(t);
            if (num.isPresent() && !FILLER.contains(t)) {
                if (number != null) {
                    return Optional.empty();
                }
                number = num.getAsInt();
            } else if (!FILLER.contains(t)) {
                return Optional.empty();
            }
        }
        List<Entry> family = families.get(keys.get(bestKey));
        int want = number == null ? 1 : number;
        return family.stream().filter(e -> e.variant() == want).findFirst()
                .map(e -> new Match(e, family.size()));
    }

    /**
     * Zwei Nachbarwörter, die zusammen einen einteiligen Schlüssel ergeben, werden eins: „ozean monument“ →
     * „ozeanmonument“, „wuesten tempel“ (aus „Wüsten-Tempel“) → „wuestentempel“. Spracheingabe trennt Komposita gern;
     * vorher ging „bau mir ein Ozean Monument“ ans LLM, obwohl drei Monumente bereitlagen (Review 2026-11-15).
     */
    private List<String> joinSplitCompounds(List<String> tokens) {
        List<String> out = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            if (i + 1 < tokens.size() && !keys.containsKey(tokens.get(i)) && !keys.containsKey(tokens.get(i + 1))
                    && keys.containsKey(tokens.get(i) + tokens.get(i + 1))) {
                out.add(tokens.get(i) + tokens.get(i + 1));
                i++;
            } else {
                out.add(tokens.get(i));
            }
        }
        return out;
    }

    /**
     * Hinweis für das LLM bei freien Aufträgen: Bauteile der Familien, deren Name im Auftrag vorkommt (höchstens
     * max Zeilen, je Familie die ersten drei Varianten). Leer, wenn keine Familie vorkommt.
     */
    public String hint(String prompt, int max) {
        String text = " " + RequestHints.normalize(prompt) + " ";
        List<String> lines = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map.Entry<String, String> k : keys.entrySet()) {
            if (!text.contains(" " + k.getKey() + " ") || !seen.add(k.getValue())) {
                continue;
            }
            List<Entry> fam = families.get(k.getValue());
            for (Entry e : fam.subList(0, Math.min(3, fam.size()))) {
                if (lines.size() < max) {
                    lines.add("- " + e.name() + ": " + e.display());
                }
            }
        }
        return lines.isEmpty() ? "" : "\nBauteile aus der Sammlung (op module, name wie hier; ungeprüft, aus Bauwerk-"
                + "Dateien):\n" + String.join("\n", lines) + "\n";
    }
}
