package com.blackn0va.baumeister.companion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Spielbuch des Abenteuers: je Spieler der laufende Lauf (Etappe, Modus, Tode, gesehene Merkmale) und die beste
 * Hardcore-Etappe. Datei begleiter-abenteuer.json im Plugin-Ordner, damit ein Neustart von dev01 die Geschichte nicht
 * zurücksetzt.
 *
 * Hardcore heißt hier: ein Leben FÜR DAS ABENTEUER. Der Tod beendet den Lauf und trägt die erreichte Etappe als
 * Bestleistung ein; der Spielmodus des Spielers bleibt, wie er ist (Zuschauermodus wie im echten Hardcore würde ihn in
 * der geteilten Farmwelt aussperren – das entscheidet ein Admin, nicht Vera).
 *
 * Threads: nur Hauptthread (CompanionAdventure). Fehler: fromJson überspringt kaputte Einträge statt zu werfen.
 */
public final class AdventureBook {

    /** doneAt: Etappe → Zeitpunkt (ms), zu dem sie geschafft war – fürs Tagebuch (AdventureDiary). */
    public record Run(int stage, Adventure.Mode mode, int deaths, long startedMillis, Set<String> flags,
            Map<Integer, Long> doneAt) {
        public Run {
            flags = Set.copyOf(flags);
            doneAt = Map.copyOf(doneAt);
        }

        public Run(int stage, Adventure.Mode mode, int deaths, long startedMillis, Set<String> flags) {
            this(stage, mode, deaths, startedMillis, flags, Map.of());
        }

        Run withFlags(Set<String> more, long nowMillis) {
            Set<String> all = new TreeSet<>(flags);
            all.addAll(more);
            int next = Adventure.advance(stage, all);
            Map<Integer, Long> times = new java.util.TreeMap<>(doneAt);
            for (int i = stage; i < next; i++) {
                times.putIfAbsent(i, nowMillis);
            }
            return new Run(next, mode, deaths, startedMillis, all, times);
        }
    }

    /**
     * Ergebnis eines Todes: Lauf vorbei (Hardcore) oder weiter mit einem Tod mehr. teammates: Mitspieler, deren Lauf
     * dadurch ebenfalls endete (Hardcore im Team: ein Leben für alle).
     */
    public record Death(boolean over, int stage, int deaths, boolean newBest, java.util.List<UUID> teammates) {
        public Death(boolean over, int stage, int deaths, boolean newBest) {
            this(over, stage, deaths, newBest, java.util.List.of());
        }
    }

    private final Map<UUID, Run> runs = new HashMap<>();
    /**
     * Team-Abenteuer: Mitglied → gemeinsame Menge (alle Mitglieder teilen dasselbe Set-Objekt). Was einer schafft,
     * zählt für alle; jeder behält seinen eigenen Lauf (Tode, Tagebuch), die Etappen laufen gleich.
     */
    private final Map<UUID, Set<UUID>> teams = new HashMap<>();
    private final Map<UUID, Integer> best = new HashMap<>();
    private int skipped;

    /** Beim Lesen übersprungene Einträge (fromJson) – CompanionAdventure meldet sie im Log. */
    public int skipped() {
        return skipped;
    }

    /** Eine Zeile der Rangliste: laufende Etappe (oder -1) und Hardcore-Bestleistung. */
    public record Rank(UUID player, int stage, Adventure.Mode mode, int best) {
        /** Wonach sortiert wird: das Weiteste, das der Spieler je erreicht hat. */
        public int score() {
            return Math.max(stage, best);
        }
    }

    /**
     * Rangliste aller Spieler mit Lauf oder Bestleistung, weitester zuerst; bei Gleichstand der mit laufendem
     * Abenteuer, dann nach UUID (stabil). Für „Vera, wer ist am weitesten?“ – mehrere Spieler, ein Server.
     */
    public java.util.List<Rank> ranking(int limit) {
        java.util.Set<UUID> ids = new java.util.HashSet<>(runs.keySet());
        ids.addAll(best.keySet());
        return ids.stream().map(id -> {
            Run r = runs.get(id);
            return new Rank(id, r == null ? -1 : r.stage(), r == null ? null : r.mode(), best(id));
        }).sorted(java.util.Comparator.comparingInt(Rank::score).reversed()
                .thenComparing(rk -> rk.stage() < 0)
                .thenComparing(Rank::player)).limit(Math.max(0, limit)).toList();
    }

    public Optional<Run> run(UUID player) {
        return Optional.ofNullable(runs.get(player));
    }

    public int best(UUID player) {
        return best.getOrDefault(player, 0);
    }

    /** Neuer Lauf, ein alter wird ersetzt. Merkmale schon beim Start (wer mit Holz kommt, hat Etappe 1). */
    public Run start(UUID player, Adventure.Mode mode, long nowMillis, Set<String> flags) {
        leave(player);
        Run r = new Run(0, mode, 0, nowMillis, Set.of()).withFlags(flags, nowMillis);
        runs.put(player, r);
        return r;
    }

    public void stop(UUID player) {
        leave(player);
        runs.remove(player);
    }

    /** Alle im Team des Spielers, er selbst eingeschlossen (allein: nur er). */
    public Set<UUID> team(UUID player) {
        Set<UUID> t = teams.get(player);
        return t == null ? Set.of(player) : Set.copyOf(t);
    }

    /**
     * guest kommt ins Abenteuer von host: übernimmt dessen Stand (Etappe, Merkmale, Modus, Startzeit, Zeiten), eigene
     * Tode beginnen bei 0. false, wenn host kein Abenteuer hat oder beide dieselben sind.
     */
    public boolean join(UUID host, UUID guest) {
        Run h = runs.get(host);
        if (h == null || host.equals(guest)) {
            return false;
        }
        leave(guest);
        runs.put(guest, new Run(h.stage(), h.mode(), 0, h.startedMillis(), h.flags(), h.doneAt()));
        Set<UUID> t = teams.computeIfAbsent(host, k -> {
            Set<UUID> s = new java.util.LinkedHashSet<>();
            s.add(host);
            return s;
        });
        t.add(guest);
        teams.put(guest, t);
        return true;
    }

    /** Aus dem Team (Lauf bleibt allein bestehen). Bleibt einer übrig, ist er wieder allein. */
    public void leave(UUID player) {
        Set<UUID> t = teams.remove(player);
        if (t == null) {
            return;
        }
        t.remove(player);
        if (t.size() <= 1) {
            t.forEach(teams::remove);
        }
    }

    /**
     * Wie observe, aber fürs ganze Team: Die Merkmale gelten für jeden Lauf im Team. Liefert je Mitglied vorher und
     * nachher (nur Mitglieder mit Lauf).
     */
    public Map<UUID, Run[]> observeTeam(UUID player, Set<String> flags, long nowMillis) {
        Map<UUID, Run[]> out = new java.util.LinkedHashMap<>();
        for (UUID m : team(player)) {
            observe(m, flags, nowMillis).ifPresent(c -> out.put(m, c));
        }
        return out;
    }

    /** Neue Merkmale eintragen; liefert den Lauf vorher und nachher (gleich, wenn sich nichts tat), sonst leer. */
    public Optional<Run[]> observe(UUID player, Set<String> flags) {
        return observe(player, flags, System.currentTimeMillis());
    }

    public Optional<Run[]> observe(UUID player, Set<String> flags, long nowMillis) {
        Run before = runs.get(player);
        if (before == null) {
            return Optional.empty();
        }
        Run after = before.withFlags(flags, nowMillis);
        runs.put(player, after);
        return Optional.of(new Run[] {before, after});
    }

    public Optional<Death> died(UUID player) {
        Run r = runs.get(player);
        if (r == null) {
            return Optional.empty();
        }
        if (r.mode() == Adventure.Mode.HARDCORE) {
            java.util.List<UUID> others = new java.util.ArrayList<>(team(player));
            others.remove(player);
            for (UUID m : others) {
                Run mr = runs.remove(m);
                if (mr != null && mr.stage() > best(m)) {
                    best.put(m, mr.stage());
                }
                teams.remove(m);
            }
            teams.remove(player);
            runs.remove(player);
            boolean newBest = r.stage() > best(player);
            if (newBest) {
                best.put(player, r.stage());
            }
            return Optional.of(new Death(true, r.stage(), r.deaths() + 1, newBest, java.util.List.copyOf(others)));
        }
        Run after = new Run(r.stage(), r.mode(), r.deaths() + 1, r.startedMillis(), r.flags(), r.doneAt());
        runs.put(player, after);
        return Optional.of(new Death(false, after.stage(), after.deaths(), false));
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        JsonObject rs = new JsonObject();
        runs.forEach((id, r) -> {
            JsonObject o = new JsonObject();
            o.addProperty("etappe", r.stage());
            o.addProperty("modus", r.mode().name().toLowerCase(java.util.Locale.ROOT));
            o.addProperty("tode", r.deaths());
            o.addProperty("start", r.startedMillis());
            JsonArray f = new JsonArray();
            new TreeSet<>(r.flags()).forEach(f::add);
            o.add("merkmale", f);
            JsonObject times = new JsonObject();
            new java.util.TreeMap<>(r.doneAt()).forEach((s, ms) -> times.addProperty(String.valueOf(s), ms));
            o.add("zeiten", times);
            rs.add(id.toString(), o);
        });
        root.add("läufe", rs);
        JsonObject bs = new JsonObject();
        best.forEach((id, s) -> bs.addProperty(id.toString(), s));
        root.add("bestleistung", bs);
        JsonArray ts = new JsonArray();
        new java.util.LinkedHashSet<>(teams.values()).forEach(t -> {
            JsonArray a = new JsonArray();
            t.forEach(id -> a.add(id.toString()));
            ts.add(a);
        });
        root.add("teams", ts);
        return root.toString();
    }

    public static AdventureBook fromJson(String json) {
        AdventureBook book = new AdventureBook();
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return book;  // unlesbare Datei: leeres Buch, CompanionAdventure loggt den Grund
        }
        if (!root.isJsonObject()) {
            return book;
        }
        JsonObject o = root.getAsJsonObject();
        if (o.has("läufe") && o.get("läufe").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("läufe").entrySet()) {
                try {
                    UUID id = UUID.fromString(e.getKey());
                    JsonObject r = e.getValue().getAsJsonObject();
                    Set<String> flags = new TreeSet<>();
                    if (r.has("merkmale")) {
                        r.getAsJsonArray("merkmale").forEach(x -> flags.add(x.getAsString()));
                    }
                    Adventure.Mode mode = Adventure.Mode.valueOf(r.get("modus").getAsString()
                            .toUpperCase(java.util.Locale.ROOT));
                    int stage = Math.clamp(r.get("etappe").getAsInt(), 0, Adventure.STAGES.size());
                    Map<Integer, Long> times = new java.util.TreeMap<>();
                    if (r.has("zeiten") && r.get("zeiten").isJsonObject()) {
                        // Ältere Spielbücher (vor dem Tagebuch) haben keine Zeiten – dann bleibt die Liste leer.
                        r.getAsJsonObject("zeiten").entrySet().forEach(x -> times.put(Integer.parseInt(x.getKey()),
                                x.getValue().getAsLong()));
                    }
                    book.runs.put(id, new Run(stage, mode, Math.max(0, r.get("tode").getAsInt()),
                            r.get("start").getAsLong(), flags, times));
                } catch (RuntimeException bad) {
                    // Einzelner kaputter Eintrag (Hand-Edit): überspringen und zählen, der Rest gilt.
                    book.skipped++;
                }
            }
        }
        if (o.has("bestleistung") && o.get("bestleistung").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("bestleistung").entrySet()) {
                try {
                    book.best.put(UUID.fromString(e.getKey()), Math.clamp(e.getValue().getAsInt(), 0,
                            Adventure.STAGES.size()));
                } catch (RuntimeException bad) {
                    book.skipped++;
                }
            }
        }
        if (o.has("teams") && o.get("teams").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("teams")) {
                try {
                    Set<UUID> t = new java.util.LinkedHashSet<>();
                    e.getAsJsonArray().forEach(x -> t.add(UUID.fromString(x.getAsString())));
                    // Nur Mitglieder mit Lauf; ein Team braucht mindestens zwei.
                    t.removeIf(id -> !book.runs.containsKey(id) || book.teams.containsKey(id));
                    if (t.size() >= 2) {
                        t.forEach(id -> book.teams.put(id, t));
                    }
                } catch (RuntimeException bad) {
                    book.skipped++;
                }
            }
        }
        return book;
    }
}
