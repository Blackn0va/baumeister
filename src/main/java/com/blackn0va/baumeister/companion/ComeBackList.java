package com.blackn0va.baumeister.companion;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Wer Vera bei sich haben will: Jeder, der sie einmal gerufen hat, bis er sie mit „tschüss“ wegschickt. Bei jedem
 * Beitreten kommt sie zu diesen Spielern von selbst (CompanionService.join).
 *
 * Vorher „Rückkehrliste“: Der Eintrag verschwand beim Wiederkommen und entstand nur, wenn Vera beim Verlassen gerade
 * da war. Vorfall dev01 2026-11-05 17:27: Der Spieler kam mit Simple Voice Chat, die Liste war leer (zwei Neustarts
 * dazwischen, einer davon mit der alten Fassung ohne Datei), Vera kam nicht – und ohne Vera schneidet das Plugin
 * niemanden mit (canHear, Datenschutz). Er sprach „Hey Vera“, und niemand hörte zu.
 *
 * Jetzt bleibt der Eintrag beim Beitreten stehen. Wer das zurückdreht (remove beim join), bringt den Vorfall zurück:
 * Ein einziger Neustart oder Absturz ohne gespeicherte Datei, und Vera bleibt weg.
 *
 * Datenschutz bleibt: Nur wer selbst gerufen hat, steht hier; „tschüss“ nimmt ihn heraus.
 *
 * Threads: nur Hauptthread. Fehler: fromJson wirft bei kaputter Datei (der Aufrufer entscheidet, fail-open).
 */
public final class ComeBackList {

    private final Set<UUID> players = new TreeSet<>();

    /** Gerufen (auch beim Beitreten): bleibt auf der Liste. true = neu dazugekommen, also speichern. */
    public boolean summoned(UUID player) {
        return players.add(player);
    }

    /** „Tschüss“ / „/bm begleiter weg“: kommt nicht mehr von selbst. true = war drauf, also speichern. */
    public boolean dismissed(UUID player) {
        return players.remove(player);
    }

    /** Beim Beitreten: Soll Vera von selbst kommen? Der Eintrag bleibt stehen. */
    public boolean wantsCompanion(UUID player) {
        return players.contains(player);
    }

    public int size() {
        return players.size();
    }

    public String toJson() {
        JsonArray a = new JsonArray();
        players.forEach(id -> a.add(id.toString()));
        return a.toString();
    }

    public static ComeBackList fromJson(String json) {
        ComeBackList list = new ComeBackList();
        for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
            list.players.add(UUID.fromString(e.getAsString()));
        }
        return list;
    }
}
