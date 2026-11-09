package com.blackn0va.baumeister;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Welt-Listen der Config (aufgaben.welten, begleiter.abenteuer.welten) für fremde Server (Wunsch 2026-11-08).
 *
 * Vorher stand dort fest „world, world_nether, world_the_end“ – die Farmwelt von dev01. Gemessen auf einem frischen
 * Paper 26.2 mit level-name=meinewelt: dreimal „abenteuer | welt_fehlt“ im Startlog, Vera bot nirgends ein Abenteuer
 * an, und keine Tagesaufgabe zählte. Jetzt steht in der mitgelieferten Config „hauptwelt“: die Hauptwelt des Servers
 * (erste geladene Welt, also level-name) samt ihrem Nether und End, wie immer sie heißen. Feste Namen gehen weiter;
 * dev01 hat sie ausdrücklich in seiner config.yml (die Migration ergänzt nur fehlende Schlüssel).
 *
 * Threads: Hauptthread (Bukkit.getWorlds). Fehler: keine Welt geladen → „world“ als Hauptwelt (Bukkit-Vorgabe).
 */
final class MainWorlds {

    /** Platzhalter in den Listen für die Hauptwelt samt Nether und End. */
    static final String TOKEN = "hauptwelt";

    /** Vorgabe der Listen, wenn der Schlüssel fehlt. */
    static final java.util.List<String> DEFAULT = java.util.List.of(TOKEN);

    /** So heißt die Welt im Chat, wenn spielwelt-name fehlt. */
    static final String DEFAULT_LABEL = "Hauptwelt";

    private MainWorlds() {
    }

    /**
     * Ersetzt „hauptwelt“ durch main, main_nether und main_the_end (Namensregel von Spigot und Paper, auch 26.x: dort
     * heißen die Welten weiter so, nur die Ordner liegen anders). Alles kleingeschrieben, Reihenfolge bleibt. Rein,
     * testbar.
     */
    static Set<String> expand(Collection<String> names, String main) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            String w = n.strip().toLowerCase(Locale.ROOT);
            if (w.equals(TOKEN)) {
                String m = main.toLowerCase(Locale.ROOT);
                out.add(m);
                out.add(m + "_nether");
                out.add(m + "_the_end");
            } else if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return Set.copyOf(out);
    }

    /** Name der Hauptwelt (level-name); vor dem Laden der Welten die Bukkit-Vorgabe „world“. */
    static String mainName() {
        java.util.List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? "world" : worlds.getFirst().getName();
    }

    /** Wie die Spieler die Welt nennen, in der Abenteuer und Tagesaufgaben gelten (spielwelt-name, höchstens 24). */
    static String label(ConfigurationSection root) {
        String raw = root == null ? null : root.getString("spielwelt-name", DEFAULT_LABEL);
        return raw == null || raw.isBlank() ? DEFAULT_LABEL : Texts.clean(raw.strip(), 24);
    }
}
