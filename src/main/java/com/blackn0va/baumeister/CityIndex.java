package com.blackn0va.baumeister;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Welche Städte wo gebaut sind: Ort, Mitte laut Karte (lat/lon), Radius, Welt und Bauplatz-Basis – das, was
 * /bm ort &lt;Stadt&gt; &lt;Straße&gt; braucht, um eine Straße aus den Kartendaten in Blockkoordinaten umzurechnen
 * (Wunsch 2026-10-30). Vorher wusste nach dem Bau niemand mehr, wo die
 * Mitte lag: Der Fortsetzen-Stand wird am Ende gelöscht, und der Reisepunkt wandert zu den Gebäuden (TravelSpot).
 *
 * Datei plugins/Baumeister/stadt/staedte.json, eine Liste im Format des Fortsetzen-Stands (CityResume.toJson/parse).
 * Ein Ort steht einmal darin; ein neuer Bau desselben Namens ersetzt den Eintrag (der alte Bau hat dann keinen
 * Straßenweg mehr – sein Reisepunkt bleibt).
 *
 * Threads: put auf dem Hauptthread, lesen beliebig (synchronized). Fehler: Lesen und Schreiben loggen und werfen nicht
 * (ein fehlendes Verzeichnis kostet nur den Straßenweg); unbrauchbare Einträge werden übersprungen.
 */
final class CityIndex {

    private static final Logger LOG = Logger.getLogger("Baumeister");

    /** Treffer für den Anfang eines /bm-ort-Arguments: die Stadt und ab welchem Wort die Straße beginnt. */
    record Match(CityResume.State city, int rest) {
    }

    private final Path file;
    private final List<CityResume.State> cities = new ArrayList<>();

    CityIndex(Path file) {
        this.file = file;
        load();
    }

    synchronized void put(CityResume.State s) {
        cities.removeIf(c -> key(c.place()).equals(key(s.place())));
        cities.add(s.at(0, false));
        save();
    }

    /**
     * Eintrag entfernen (/bm ort löschen). true, wenn es ihn gab. Vorher blieb ein Eintrag für immer: Nach dem
     * Zurücksetzen der Stadtwelt (dev01 2026-10-30, 18:46) führte /bm ort Eberdingen &lt;Straße&gt; auf leeres Land,
     * weil der genau passende alte Name vor „71735 Eberdingen“ gewinnt.
     */
    synchronized boolean remove(String place) {
        boolean gone = cities.removeIf(c -> key(c.place()).equals(key(place)));
        if (gone) {
            save();
        }
        return gone;
    }

    synchronized List<CityResume.State> all() {
        return List.copyOf(cities);
    }

    /**
     * Längster Wortanfang, der eine gebaute Stadt nennt. Ein Ort aus einer Postleitzahl („71735 Eberdingen“) passt
     * auch ohne die Zahl („Eberdingen“), ein genau passender Name hat aber Vorrang. Leer, wenn kein Anfang passt oder
     * hinter der Stadt nichts mehr steht (dann ist es ein gewöhnlicher Reisepunkt).
     */
    synchronized Optional<Match> prefix(List<String> words) {
        for (int n = words.size() - 1; n >= 1; n--) {
            String head = key(String.join(" ", words.subList(0, n)));
            for (CityResume.State c : cities) {
                if (key(c.place()).equals(head)) {
                    return Optional.of(new Match(c, n));
                }
            }
            for (CityResume.State c : cities) {
                if (key(withoutPostcode(c.place())).equals(head)) {
                    return Optional.of(new Match(c, n));
                }
            }
        }
        return Optional.empty();
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String withoutPostcode(String name) {
        return name.replaceFirst("^\\d{4,5}\\s+", "");
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement el = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement e : el.getAsJsonArray()) {
                CityResume.State s = e.isJsonObject() ? CityResume.parse(e.getAsJsonObject()) : null;
                if (s == null) {
                    LOG.warning(() -> "stadt | verzeichnis_eintrag_ungültig | datei=" + file + " eintrag="
                            + Texts.clean(e.toString(), 120));
                    continue;
                }
                cities.add(s);
            }
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            LOG.log(Level.WARNING, "stadt | verzeichnis_unlesbar | datei=" + file, e);
        }
    }

    private void save() {
        JsonArray a = new JsonArray();
        for (CityResume.State s : cities) {
            a.add(CityResume.toJson(s));
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, a.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "stadt | verzeichnis_speichern_fehlgeschlagen | datei=" + file, e);
        }
    }
}
