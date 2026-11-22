package com.blackn0va.baumeister.town;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wer in der Stadt lebt: fester, erweiterbarer Satz aus Name, Beruf, Charakter, Werkzeug und Kleidungsfarbe
 * (Vertrag v008, AK-23). Erweitern = eine Zeile in PEOPLE; RosterTest hält die Namen eindeutig und ansprechbar.
 *
 * Fest statt vom Modell erfunden: Der Name ist das Rufwort im Chat („Lotte, wie geht's?“) und muss vorher feststehen;
 * ein erfundener Name könnte mit einem Spieler, mit Vera oder dem Baumeister zusammenfallen. Deshalb fallen Namen, die
 * dem Begleiter oder dem Baumeister gleichen, beim Auswählen weg (pick).
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public final class Roster {

    /**
     * Ein Bewohner. tool = Material-Name (Bukkit), was er bei der Arbeit in der Hand hält; color = Lederkleidung
     * (RGB), damit man Berufe von weitem unterscheidet – alle Mannequins tragen sonst denselben Standard-Skin.
     */
    public record Person(String name, String job, String character, String tool, int color, String workLine) {
    }

    static final List<Person> PEOPLE = List.of(
            new Person("Lotte", "Bäckerin", "fröhlich und herzlich", "bread", 0xF2E8D5, "Frische Brötchen!"),
            new Person("Emil", "Schmied", "brummig, aber gutmütig", "iron_axe", 0x4A3B2F, "Noch ein Hufeisen …"),
            new Person("Greta", "Gärtnerin", "ruhig und geduldig", "wooden_hoe", 0x4E8A3A, "Die Rosen blühen gut."),
            new Person("Paul", "Fischer", "verträumt, erzählt gern", "fishing_rod", 0x2F5E8C, "Heute beißen sie."),
            new Person("Frieda", "Händlerin", "gesprächig und neugierig", "emerald", 0x8B5CF6, "Gute Ware, guter Preis!"),
            new Person("Anton", "Holzfäller", "kräftig und wortkarg", "stone_axe", 0x8C3A2F, "Das Holz muss rein."),
            new Person("Ida", "Bibliothekarin", "klug und ein bisschen förmlich", "book", 0x5A4A7A,
                    "Psst, hier wird gelesen."),
            new Person("Karl", "Bauer", "bodenständig und gemütlich", "wheat", 0xC9A227, "Der Weizen steht gut."),
            new Person("Mila", "Malerin", "fantasievoll und verspielt", "brush", 0xE0607E, "Noch ein bisschen Blau …"),
            new Person("Oskar", "Postbote", "eilig, aber freundlich", "paper", 0xD9A13B, "Post für die Stadt!"),
            new Person("Hanna", "Ärztin", "fürsorglich und sachlich", "glass_bottle", 0xE8F0F2, "Alle gesund heute?"),
            new Person("Theo", "Musiker", "witzig und gut gelaunt", "note_block", 0x3A7A7A, "La-la-laa!"));

    private Roster() {
    }

    public static List<Person> all() {
        return PEOPLE;
    }

    /**
     * Bis zu count Bewohner, die gerade nicht da sind (taken) und nicht wie der Begleiter oder der Baumeister heißen
     * (reserved, ohne Groß-/Kleinschreibung). In fester Reihenfolge ab start, damit nicht jedes Mal dieselben kommen.
     */
    public static List<Person> pick(int count, Set<String> taken, Set<String> reserved, int start) {
        Set<String> blocked = lower(reserved);
        Set<String> busy = lower(taken);
        List<Person> out = new ArrayList<>();
        int n = PEOPLE.size();
        for (int i = 0; i < n && out.size() < count; i++) {
            Person p = PEOPLE.get(Math.floorMod(start + i, n));
            String key = p.name().toLowerCase(Locale.ROOT);
            if (!blocked.contains(key) && !busy.contains(key)) {
                out.add(p);
            }
        }
        return out;
    }

    private static Set<String> lower(Set<String> names) {
        Set<String> out = new java.util.HashSet<>();
        for (String s : names) {
            if (s != null) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
