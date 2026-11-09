package com.blackn0va.baumeister.warp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Öffentliche Warps ohne Bukkit-Server: setzen, löschen, finden, Besuche zählen, speichern und den Bestand von GWarp
 * übernehmen. Ersetzt GWarp (2026-11-05: „mach bitte alle“ – Vorschlag 1, ein Plugin weniger).
 *
 * Regeln wie bei GWarp auf dev01 (plugins/GWarp/config.yml): jeder Spieler darf eine bestimmte Zahl Warps setzen
 * (Vorgabe 1, default-warp-amount), Namen 1–20 Zeichen (max-warp-length 20), Warps sind für alle sichtbar. Löschen
 * darf nur, wer ihn gesetzt hat, oder ein Admin. Namen nur aus Buchstaben, Ziffern, - und _ – sie stehen im Chat und
 * in anklickbaren Befehlen.
 *
 * Threads: nicht synchronisiert, nur im Hauptthread benutzen. Fehler: fromJson/importGWarp werfen
 * IllegalArgumentException bei unlesbarem Inhalt (der Aufrufer legt die Datei dann beiseite).
 */
public final class WarpBook {

    private static final Pattern NAME = Pattern.compile("[\\p{L}0-9_-]{1,20}");

    /** Ein Warp. visits zählt Besuche anderer Spieler (wie GWarp „visitors“). */
    public record Warp(String name, UUID owner, String ownerName, String world, double x, double y, double z,
            float yaw, float pitch, int visits) {
    }

    public enum Result {
        OK, UPDATED, BAD_NAME, LIMIT, TAKEN, MISSING, NOT_OWNER
    }

    private final Map<String, Warp> warps = new TreeMap<>();

    public static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name.strip()).matches();
    }

    public Optional<Warp> find(String name) {
        return Optional.ofNullable(warps.get(key(name)));
    }

    /** Alle Warps, die meistbesuchten zuerst, dann nach Name. */
    public List<Warp> list() {
        List<Warp> all = new ArrayList<>(warps.values());
        all.sort(Comparator.comparingInt(Warp::visits).reversed().thenComparing(w -> key(w.name())));
        return all;
    }

    public long countOwnedBy(UUID owner) {
        return warps.values().stream().filter(w -> w.owner().equals(owner)).count();
    }

    /**
     * Setzt einen Warp neu oder versetzt einen eigenen. limit = höchstens so viele eigene (≤ 0: unbegrenzt);
     * admin darf fremde Warps versetzen.
     */
    public Result set(Warp w, int limit, boolean admin) {
        if (!validName(w.name())) {
            return Result.BAD_NAME;
        }
        Warp old = warps.get(key(w.name()));
        if (old != null) {
            if (!old.owner().equals(w.owner()) && !admin) {
                return Result.TAKEN;
            }
            warps.put(key(w.name()), new Warp(old.name(), old.owner(), old.ownerName(), w.world(), w.x(), w.y(),
                    w.z(), w.yaw(), w.pitch(), old.visits()));
            return Result.UPDATED;
        }
        if (limit > 0 && countOwnedBy(w.owner()) >= limit) {
            return Result.LIMIT;
        }
        warps.put(key(w.name()), w);
        return Result.OK;
    }

    public Result delete(String name, UUID by, boolean admin) {
        Warp old = warps.get(key(name));
        if (old == null) {
            return Result.MISSING;
        }
        if (!old.owner().equals(by) && !admin) {
            return Result.NOT_OWNER;
        }
        warps.remove(key(name));
        return Result.OK;
    }

    /** Besuch zählen – nur Fremde, wie GWarp mit warp-owner-add-visitor: false. */
    public void visit(String name, UUID visitor) {
        Warp w = warps.get(key(name));
        if (w != null && !w.owner().equals(visitor)) {
            warps.put(key(name), new Warp(w.name(), w.owner(), w.ownerName(), w.world(), w.x(), w.y(), w.z(),
                    w.yaw(), w.pitch(), w.visits() + 1));
        }
    }

    public int size() {
        return warps.size();
    }

    public String toJson() {
        JsonArray a = new JsonArray();
        for (Warp w : warps.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.name());
            o.addProperty("besitzer", w.owner().toString());
            o.addProperty("besitzername", w.ownerName());
            o.addProperty("welt", w.world());
            o.addProperty("x", w.x());
            o.addProperty("y", w.y());
            o.addProperty("z", w.z());
            o.addProperty("yaw", w.yaw());
            o.addProperty("pitch", w.pitch());
            o.addProperty("besuche", w.visits());
            a.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("warps", a);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static WarpBook fromJson(String json) {
        WarpBook b = new WarpBook();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("warps")) {
                JsonObject o = e.getAsJsonObject();
                Warp w = new Warp(o.get("name").getAsString(), UUID.fromString(o.get("besitzer").getAsString()),
                        o.has("besitzername") ? o.get("besitzername").getAsString() : "?",
                        o.get("welt").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                        o.get("z").getAsDouble(), o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat(),
                        o.has("besuche") ? o.get("besuche").getAsInt() : 0);
                if (!validName(w.name())) {
                    throw new IllegalArgumentException("ungültiger Warpname: " + w.name());
                }
                b.warps.put(key(w.name()), w);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("warps.json unlesbar: " + e.getMessage(), e);
        }
        return b;
    }

    /**
     * Bestand aus plugins/GWarp/Warps.data (YAML: Warps.&lt;name&gt;.{name, owner, ownername, loc.{x,y,z,pitch,yaw,
     * world}, visitors}). Einträge mit ungültigem Namen oder ohne Ort werden übersprungen und gezählt.
     */
    public static Imported importGWarp(String yaml) {
        YamlConfiguration yc = new YamlConfiguration();
        try {
            yc.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("Warps.data unlesbar: " + e.getMessage(), e);
        }
        WarpBook b = new WarpBook();
        int skipped = 0;
        ConfigurationSection all = yc.getConfigurationSection("Warps");
        if (all != null) {
            for (String k : all.getKeys(false)) {
                ConfigurationSection s = all.getConfigurationSection(k);
                ConfigurationSection loc = s == null ? null : s.getConfigurationSection("loc");
                String name = s == null ? null : s.getString("name", k);
                UUID owner;
                try {
                    owner = s == null ? null : UUID.fromString(s.getString("owner", ""));
                } catch (IllegalArgumentException e) {
                    owner = null;
                }
                if (loc == null || owner == null || !validName(name) || loc.getString("world") == null) {
                    skipped++;
                    continue;
                }
                b.warps.put(key(name), new Warp(name, owner, s.getString("ownername", "?"), loc.getString("world"),
                        loc.getDouble("x"), loc.getDouble("y"), loc.getDouble("z"), (float) loc.getDouble("yaw"),
                        (float) loc.getDouble("pitch"), s.getInt("visitors", 0)));
            }
        }
        return new Imported(b, skipped);
    }

    public record Imported(WarpBook book, int skipped) {
    }
}
