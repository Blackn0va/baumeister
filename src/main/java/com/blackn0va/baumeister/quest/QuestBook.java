package com.blackn0va.baumeister.quest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Stand der Tagesaufgaben: Fortschritt des laufenden Tags, Punkte insgesamt, Serie (Tage in Folge mit allen drei
 * Aufgaben). Ein neuer Tag setzt nur den Fortschritt zurück; Punkte und Serie bleiben.
 *
 * Serie: geht weiter, wenn der vorige vollständige Tag gestern war; sonst beginnt sie bei 1.
 *
 * Threads: nur Hauptthread. Fehler: fromJson wirft IllegalArgumentException bei unlesbarem Inhalt.
 */
public final class QuestBook {

    /** Ergebnis einer Zählung: neu geschaffte Aufgaben (Index), Punkte dafür, ob jetzt alle geschafft sind. */
    public record Gain(List<Integer> finished, int points, boolean allDone) {
        public static final Gain NONE = new Gain(List.of(), 0, false);
    }

    /** Eintrag der Rangliste. */
    public record Rank(UUID player, String name, int points, int streak) {
    }

    private LocalDate day;
    private final Map<UUID, int[]> progress = new HashMap<>();
    private final Map<UUID, TreeSet<Integer>> done = new HashMap<>();
    private final Map<UUID, Integer> points = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();
    private final Map<UUID, LocalDate> lastFull = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    public QuestBook(LocalDate day) {
        this.day = day;
    }

    public LocalDate day() {
        return day;
    }

    /** Neuer Tag: Fortschritt leeren. true = es war wirklich ein Wechsel. */
    public boolean roll(LocalDate today) {
        if (today.equals(day)) {
            return false;
        }
        day = today;
        progress.clear();
        done.clear();
        return true;
    }

    public List<DailyQuests.Quest> quests() {
        return DailyQuests.forDay(day);
    }

    /** Zählt n für die Art/Sorte; liefert, was dadurch neu geschafft wurde. */
    public Gain add(UUID player, String name, DailyQuests.Kind kind, String key, int n) {
        if (n <= 0) {
            return Gain.NONE;
        }
        List<DailyQuests.Quest> qs = quests();
        int[] p = progress.computeIfAbsent(player, k -> new int[qs.size()]);
        TreeSet<Integer> d = done.computeIfAbsent(player, k -> new TreeSet<>());
        List<Integer> finished = new ArrayList<>();
        int gained = 0;
        for (int i = 0; i < qs.size(); i++) {
            DailyQuests.Quest q = qs.get(i);
            if (d.contains(i) || !q.counts(kind, key)) {
                continue;
            }
            p[i] = Math.min(q.amount(), p[i] + n);
            if (p[i] >= q.amount()) {
                d.add(i);
                finished.add(i);
                gained += q.points();
            }
        }
        if (finished.isEmpty()) {
            return Gain.NONE;
        }
        names.put(player, name);
        boolean all = d.size() == qs.size();
        if (all) {
            gained += DailyQuests.ALL_DONE_BONUS;
            LocalDate last = lastFull.get(player);
            streak.put(player, last != null && last.plusDays(1).equals(day) ? streak.getOrDefault(player, 0) + 1 : 1);
            lastFull.put(player, day);
        }
        points.merge(player, gained, Integer::sum);
        return new Gain(List.copyOf(finished), gained, all);
    }

    public int progress(UUID player, int quest) {
        int[] p = progress.get(player);
        return p == null ? 0 : p[quest];
    }

    public boolean done(UUID player, int quest) {
        return done.getOrDefault(player, new TreeSet<>()).contains(quest);
    }

    public int points(UUID player) {
        return points.getOrDefault(player, 0);
    }

    /** Serie, die heute oder gestern noch lief; sonst 0. */
    public int streak(UUID player) {
        LocalDate last = lastFull.get(player);
        return last != null && !last.plusDays(1).isBefore(day) ? streak.getOrDefault(player, 0) : 0;
    }

    public List<Rank> ranking() {
        List<Rank> out = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : points.entrySet()) {
            out.add(new Rank(e.getKey(), names.getOrDefault(e.getKey(), "?"), e.getValue(), streak(e.getKey())));
        }
        out.sort(Comparator.comparingInt(Rank::points).reversed().thenComparing(Rank::name));
        return out;
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("tag", day.toString());
        JsonObject players = new JsonObject();
        java.util.Set<UUID> all = new java.util.TreeSet<>();
        all.addAll(points.keySet());
        all.addAll(progress.keySet());
        for (UUID id : all) {
            JsonObject o = new JsonObject();
            o.addProperty("name", names.getOrDefault(id, "?"));
            o.addProperty("punkte", points.getOrDefault(id, 0));
            o.addProperty("serie", streak.getOrDefault(id, 0));
            if (lastFull.containsKey(id)) {
                o.addProperty("zuletzt_alle", lastFull.get(id).toString());
            }
            JsonArray pr = new JsonArray();
            int[] p = progress.get(id);
            if (p != null) {
                for (int v : p) {
                    pr.add(v);
                }
            }
            o.add("fortschritt", pr);
            JsonArray d = new JsonArray();
            done.getOrDefault(id, new TreeSet<>()).forEach(d::add);
            o.add("erledigt", d);
            players.add(id.toString(), o);
        }
        root.add("spieler", players);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static QuestBook fromJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            QuestBook b = new QuestBook(LocalDate.parse(root.get("tag").getAsString()));
            int n = b.quests().size();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("spieler").entrySet()) {
                UUID id = UUID.fromString(e.getKey());
                JsonObject o = e.getValue().getAsJsonObject();
                b.names.put(id, o.get("name").getAsString());
                b.points.put(id, o.get("punkte").getAsInt());
                b.streak.put(id, o.get("serie").getAsInt());
                if (o.has("zuletzt_alle")) {
                    b.lastFull.put(id, LocalDate.parse(o.get("zuletzt_alle").getAsString()));
                }
                JsonArray pr = o.getAsJsonArray("fortschritt");
                if (pr.size() == n) {
                    int[] p = new int[n];
                    for (int i = 0; i < n; i++) {
                        p[i] = pr.get(i).getAsInt();
                    }
                    b.progress.put(id, p);
                }
                TreeSet<Integer> d = new TreeSet<>();
                o.getAsJsonArray("erledigt").forEach(x -> d.add(x.getAsInt()));
                if (!d.isEmpty()) {
                    b.done.put(id, d);
                }
            }
            return b;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("aufgaben.json unlesbar: " + e.getMessage(), e);
        }
    }
}
