package com.blackn0va.baumeister.companion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Veras Nachtwache, Kisten-Auskunft und Reparieren – die Teile ohne Bukkit: Nachtzeit, Monster in Worten,
 * Kisten-Verlauf als Satz, welche Verursacher als „Schaden“ gelten.
 *
 * Verursacher gemessen in plugins/CoreProtect/database.db auf dev01 (co_user, 2026-11-05): #creeper, #ghast, #fire,
 * #lightning, #enderman kommen vor. #tnt, #wither, #end_crystal, #fireball stehen vorsorglich dabei – fehlt ein Name,
 * trifft er einfach nichts. Spieler stehen nie in der Liste: Reparieren nimmt nur Schäden durch Natur und Monster
 * zurück, nie Abbau durch Menschen.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class WorldWatch {

    /** CoreProtect-Benutzernamen, deren Abbau Vera reparieren darf. */
    public static final List<String> DAMAGE_CAUSES = List.of("#creeper", "#tnt", "#ghast", "#fireball", "#wither",
            "#end_crystal", "#fire", "#lightning", "#enderman");

    /** Vera warnt höchstens so oft (je Spieler). */
    public static final long WARN_EVERY_MILLIS = 120_000;

    /** Ein Eintrag aus dem Kisten-Verlauf. added = hineingelegt, sonst genommen. */
    public record ChestEntry(String player, boolean added, String item, int amount, long atMillis) {
    }

    private static final Map<String, String[]> MONSTERS = Map.ofEntries(
            Map.entry("zombie", new String[] {"Zombie", "Zombies"}),
            Map.entry("husk", new String[] {"Wüstenzombie", "Wüstenzombies"}),
            Map.entry("drowned", new String[] {"Ertrunkener", "Ertrunkene"}),
            Map.entry("zombie_villager", new String[] {"Zombiedorfbewohner", "Zombiedorfbewohner"}),
            Map.entry("skeleton", new String[] {"Skelett", "Skelette"}),
            Map.entry("stray", new String[] {"Eiswanderer", "Eiswanderer"}),
            Map.entry("creeper", new String[] {"Creeper", "Creeper"}),
            Map.entry("spider", new String[] {"Spinne", "Spinnen"}),
            Map.entry("cave_spider", new String[] {"Höhlenspinne", "Höhlenspinnen"}),
            Map.entry("witch", new String[] {"Hexe", "Hexen"}),
            Map.entry("enderman", new String[] {"Enderman", "Endermen"}),
            Map.entry("phantom", new String[] {"Phantom", "Phantome"}),
            Map.entry("pillager", new String[] {"Plünderer", "Plünderer"}),
            Map.entry("slime", new String[] {"Schleim", "Schleime"}));

    private WorldWatch() {
    }

    /** Nacht im Sinn von „Monster spawnen“: Weltzeit 13000–23000. */
    public static boolean night(long worldTime) {
        long t = Math.floorMod(worldTime, 24000L);
        return t >= 13000 && t < 23000;
    }

    /** „3 Zombies und 1 Creeper“ – die häufigsten zuerst, höchstens drei Sorten, Rest als „und weitere“. */
    public static String monsters(Map<String, Integer> byType) {
        List<Map.Entry<String, Integer>> all = new ArrayList<>(byType.entrySet());
        all.removeIf(e -> e.getValue() <= 0);
        all.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, all.size()); i++) {
            String key = all.get(i).getKey().toLowerCase(Locale.ROOT);
            int n = all.get(i).getValue();
            String[] names = MONSTERS.getOrDefault(key, new String[] {key.replace('_', ' '), key.replace('_', ' ')});
            parts.add(n + " " + (n == 1 ? names[0] : names[1]));
        }
        String text = parts.size() <= 1 ? String.join("", parts)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " und " + parts.getLast();
        return all.size() > 3 ? text + " und weitere" : text;
    }

    /** Kisten-Verlauf in Sätzen, neueste zuerst, höchstens limit Einträge. */
    public static String chestHistory(List<ChestEntry> entries, long nowMillis, int limit) {
        if (entries.isEmpty()) {
            return "An dieser Kiste war laut Protokoll niemand.";
        }
        List<ChestEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Long.compare(b.atMillis(), a.atMillis()));
        List<String> lines = new ArrayList<>();
        for (ChestEntry e : sorted.subList(0, Math.min(limit, sorted.size()))) {
            lines.add(e.player() + " hat vor " + ago(nowMillis - e.atMillis()) + " " + e.amount() + " "
                    + e.item().replace('_', ' ') + (e.added() ? " hineingelegt" : " genommen"));
        }
        String more = sorted.size() > limit ? " (und " + (sorted.size() - limit) + " ältere Einträge)" : "";
        return String.join("; ", lines) + "." + more;
    }

    /** „5 Min.“, „2 Std.“, „3 Tagen“ – für „vor …“. */
    static String ago(long ms) {
        long min = Math.max(0, ms) / 60_000;
        if (min < 1) {
            return "weniger als einer Minute";
        }
        if (min < 60) {
            return min + " Min.";
        }
        long h = min / 60;
        if (h < 48) {
            return h + " Std.";
        }
        return (h / 24) + " Tagen";
    }
}
