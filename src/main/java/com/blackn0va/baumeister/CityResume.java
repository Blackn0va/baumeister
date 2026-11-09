package com.blackn0va.baumeister;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stand eines laufenden Stadtbaus auf der Platte, damit er Pause und Server-Neustart übersteht (Wunsch 2026-10-29).
 * Vorher brach
 * CityService.shutdown jeden Stadtbau ab – Eberdingen (2449 Etappen, ~2 h) hätte nach jedem Neustart von vorn
 * begonnen.
 *
 * Gespeichert wird, was für ein neues CityStages und einen CityJob ab Etappe k nötig ist: Ort mit lat/lon (kein
 * Nominatim beim Fortsetzen), Radius, Welt und Ursprung, Fortsetzungspunkt (CityJob.resumeStage), Pause, Auftraggeber.
 * Die Kartenkacheln liegen im Zwischenspeicher (karten/), das Fortsetzen fragt Overpass also nur für fehlende.
 *
 * Fail-closed beim Lesen: Eine Datei, die nicht passt (Welt fehlt, Werte außerhalb), wird nie geraten, sondern als
 * „fortsetzen.json.kaputt“ beiseitegelegt und gemeldet – ein falsch fortgesetzter Stadtbau überbaute eine fremde Stelle.
 *
 * Threads: Hauptthread. Fehler: save/delete loggen und werfen nicht (der Bau läuft auch ohne Fortsetzen weiter).
 */
final class CityResume {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** travel: Gebäudeabschnitte des aktuellen Reisepunkts (TravelSpot), 0 = noch keiner. */
    record State(String place, double lat, double lon, int radius, long maxStageBlocks, String world, int x, int y,
            int z, int stage, int stages, boolean paused, UUID owner, String ownerName, int travel) {

        State at(int stage, boolean paused) {
            return new State(place, lat, lon, radius, maxStageBlocks, world, x, y, z, stage, stages, paused, owner,
                    ownerName, travel);
        }

        State withTravel(int travel) {
            return new State(place, lat, lon, radius, maxStageBlocks, world, x, y, z, stage, stages, paused, owner,
                    ownerName, travel);
        }
    }

    private final Path file;

    CityResume(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    void save(State s) {
        JsonObject o = toJson(s);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "stadt | fortsetzen_speichern_fehlgeschlagen | datei=" + file, e);
        }
    }

    /** Ein Stand als JSON – auch für das Städte-Verzeichnis (CityIndex), damit beide Dateien gleich lesbar bleiben. */
    static JsonObject toJson(State s) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("ort", s.place());
        o.addProperty("lat", s.lat());
        o.addProperty("lon", s.lon());
        o.addProperty("radius", s.radius());
        o.addProperty("max_blöcke_je_etappe", s.maxStageBlocks());
        o.addProperty("welt", s.world());
        o.addProperty("x", s.x());
        o.addProperty("y", s.y());
        o.addProperty("z", s.z());
        o.addProperty("etappe", s.stage());
        o.addProperty("etappen", s.stages());
        o.addProperty("pause", s.paused());
        if (s.owner() != null) {
            o.addProperty("besitzer", s.owner().toString());
        }
        o.addProperty("besitzer_name", s.ownerName());
        o.addProperty("reise_gebäude", s.travel());
        return o;
    }

    void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "stadt | fortsetzen_löschen_fehlgeschlagen | datei=" + file, e);
        }
    }

    /** Leer, wenn keine Datei da ist. Eine unbrauchbare Datei wird beiseitegelegt (siehe Klassenkommentar). */
    Optional<State> load() {
        if (!exists()) {
            return Optional.empty();
        }
        try {
            JsonElement el = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            State s = parse(el.getAsJsonObject());
            if (s != null) {
                return Optional.of(s);
            }
            LOG.warning("stadt | fortsetzen_ungültig | datei=" + file + " – beiseitegelegt");
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            LOG.log(Level.WARNING, "stadt | fortsetzen_unlesbar | datei=" + file + " – beiseitegelegt", e);
        }
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".kaputt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "stadt | fortsetzen_beiseitelegen_fehlgeschlagen | datei=" + file, e);
        }
        return Optional.empty();
    }

    /** null bei fehlenden oder unplausiblen Werten. Rein, testbar. */
    static State parse(JsonObject o) {
        if (o == null || !o.has("v") || o.get("v").getAsInt() != 1) {
            return null;
        }
        String place = str(o, "ort");
        String world = str(o, "welt");
        if (place == null || world == null || world.isBlank()) {
            return null;
        }
        double lat = o.has("lat") ? o.get("lat").getAsDouble() : Double.NaN;
        double lon = o.has("lon") ? o.get("lon").getAsDouble() : Double.NaN;
        if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) {
            return null;
        }
        int radius = num(o, "radius");
        int stage = num(o, "etappe");
        int stages = num(o, "etappen");
        if (radius < 30 || radius > 60_000 || stages < 1 || stage < 0 || stage > stages) {
            return null;
        }
        int x = num(o, "x");
        int y = num(o, "y");
        int z = num(o, "z");
        if (Math.abs((long) x) > 30_000_000L || Math.abs((long) z) > 30_000_000L || y < -2048 || y > 2048) {
            return null;
        }
        long maxBlocks = o.has("max_blöcke_je_etappe") ? o.get("max_blöcke_je_etappe").getAsLong() : 12_000_000L;
        UUID owner = null;
        if (o.has("besitzer")) {
            try {
                owner = UUID.fromString(o.get("besitzer").getAsString());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        String ownerName = str(o, "besitzer_name");
        // Fehlt in Ständen vor 2026-10-30: dann 0, der Reisepunkt wandert ab der nächsten Etappe mit Gebäuden.
        int travel = o.has("reise_gebäude") ? num(o, "reise_gebäude") : 0;
        if (travel < 0) {
            return null;
        }
        return new State(place, lat, lon, radius, maxBlocks, world, x, y, z, stage,
                stages, o.has("pause") && o.get("pause").getAsBoolean(), owner, ownerName == null ? "?" : ownerName,
                travel);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static int num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : Integer.MIN_VALUE;
    }
}
