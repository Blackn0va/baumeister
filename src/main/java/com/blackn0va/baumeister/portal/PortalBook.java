package com.blackn0va.baumeister.portal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Register der verbundenen Portale (2026-11-15): Öffnung, Ziel und Anzeige. Ohne Bukkit, damit Suche, Ersetzen und
 * Speichern testbar sind.
 *
 * Ziel als Text: „warp:&lt;name&gt;“ (WarpService), „welt:&lt;name&gt;“ (Spawn der Welt) oder „spawn“ (Spawn der
 * Hauptwelt). Text statt aufgelöstem Ort: Versetzt jemand den Warp oder den Weltspawn, führt das Portal mit dorthin,
 * statt an die alte Stelle.
 *
 * Threads: nicht synchronisiert, nur im Hauptthread benutzen. Fehler: fromJson wirft IllegalArgumentException bei
 * unlesbarem Inhalt (der Dienst legt die Datei dann beiseite, statt sie stumm zu überschreiben).
 */
public final class PortalBook {

    public static final String SPAWN = "spawn";
    public static final String WARP = "warp:";
    public static final String WORLD = "welt:";
    /** Warpnamen wie WarpBook, Weltnamen wie Ordnernamen. */
    private static final Pattern WARP_NAME = Pattern.compile("[\\p{L}0-9_-]{1,20}");
    private static final Pattern WORLD_NAME = Pattern.compile("[\\p{L}0-9_.-]{1,64}");
    public static final int MAX_LABEL = 32;

    /** labelEntity: UUID des Schilds (TextDisplay) über dem Portal, leer wenn keins steht. */
    public record Portal(String world, PortalFrame.Opening opening, String target, String label, UUID by,
            String labelEntity) {

        public String id() {
            return PortalBook.id(world, opening);
        }

        public Portal withLabelEntity(String uuid) {
            return new Portal(world, opening, target, label, by, uuid);
        }
    }

    private final Map<String, Portal> portals = new LinkedHashMap<>();
    /** „welt/x/y/z“ jeder Zelle → id; Portale sind höchstens 21 × 21, das bleibt klein. */
    private final Map<String, String> cells = new HashMap<>();

    public static String id(String world, PortalFrame.Opening o) {
        return world + "/" + o.axis().name().toLowerCase(Locale.ROOT) + "/" + o.x0() + "/" + o.y0() + "/" + o.z0();
    }

    private static String cell(String world, int x, int y, int z) {
        return world + "/" + x + "/" + y + "/" + z;
    }

    public static boolean validTarget(String target) {
        if (target == null) {
            return false;
        }
        if (target.equals(SPAWN)) {
            return true;
        }
        if (target.startsWith(WARP)) {
            return WARP_NAME.matcher(target.substring(WARP.length())).matches();
        }
        return target.startsWith(WORLD) && WORLD_NAME.matcher(target.substring(WORLD.length())).matches();
    }

    public Optional<Portal> at(String world, int x, int y, int z) {
        String id = cells.get(cell(world, x, y, z));
        return id == null ? Optional.empty() : Optional.ofNullable(portals.get(id));
    }

    public Optional<Portal> find(String id) {
        return Optional.ofNullable(portals.get(id));
    }

    /**
     * Neu eintragen. Ein Portal, das sich mit dem neuen eine Zelle teilt, wird ersetzt und zurückgegeben – sonst
     * zeigten zwei Einträge auf dieselben Blöcke, und welches Ziel gilt, hinge an der Reihenfolge der Datei.
     */
    public List<Portal> put(Portal p) {
        if (!validTarget(p.target())) {
            throw new IllegalArgumentException("ungültiges Ziel: " + p.target());
        }
        List<Portal> replaced = new ArrayList<>();
        for (int[] c : p.opening().cells()) {
            at(p.world(), c[0], c[1], c[2]).ifPresent(old -> {
                if (!replaced.contains(old)) {
                    replaced.add(old);
                }
            });
        }
        replaced.forEach(old -> remove(old.id()));
        portals.put(p.id(), p);
        for (int[] c : p.opening().cells()) {
            cells.put(cell(p.world(), c[0], c[1], c[2]), p.id());
        }
        return replaced;
    }

    public Optional<Portal> remove(String id) {
        Portal old = portals.remove(id);
        if (old == null) {
            return Optional.empty();
        }
        for (int[] c : old.opening().cells()) {
            cells.remove(cell(old.world(), c[0], c[1], c[2]));
        }
        return Optional.of(old);
    }

    public List<Portal> list() {
        return List.copyOf(portals.values());
    }

    public int size() {
        return portals.size();
    }

    public String toJson() {
        JsonArray a = new JsonArray();
        for (Portal p : portals.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("welt", p.world());
            o.addProperty("achse", p.opening().axis().name().toLowerCase(Locale.ROOT));
            o.addProperty("x", p.opening().x0());
            o.addProperty("y", p.opening().y0());
            o.addProperty("z", p.opening().z0());
            o.addProperty("breite", p.opening().width());
            o.addProperty("hoch", p.opening().height());
            o.addProperty("ziel", p.target());
            o.addProperty("anzeige", p.label());
            o.addProperty("von", p.by().toString());
            o.addProperty("schild", p.labelEntity());
            a.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("portale", a);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static PortalBook fromJson(String json) {
        PortalBook b = new PortalBook();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("portale")) {
                JsonObject o = e.getAsJsonObject();
                PortalFrame.Axis axis = PortalFrame.Axis.valueOf(o.get("achse").getAsString().toUpperCase(Locale.ROOT));
                int width = o.get("breite").getAsInt();
                int height = o.get("hoch").getAsInt();
                if (width < PortalFrame.MIN_WIDTH || width > PortalFrame.MAX || height < PortalFrame.MIN_HEIGHT
                        || height > PortalFrame.MAX) {
                    throw new IllegalArgumentException("Portalmaße " + width + " × " + height);
                }
                PortalFrame.Opening opening = new PortalFrame.Opening(axis, o.get("x").getAsInt(),
                        o.get("y").getAsInt(), o.get("z").getAsInt(), width, height);
                b.put(new Portal(o.get("welt").getAsString(), opening, o.get("ziel").getAsString(),
                        o.has("anzeige") ? o.get("anzeige").getAsString() : "", UUID.fromString(o.get("von")
                                .getAsString()), o.has("schild") ? o.get("schild").getAsString() : ""));
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("portale.json unlesbar: " + e.getMessage(), e);
        }
        return b;
    }
}
