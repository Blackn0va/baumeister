package com.blackn0va.baumeister.quest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Tagesaufgaben: drei je Tag, für alle Spieler gleich (gleicher Tag → gleiche Aufgaben, auch nach einem Neustart),
 * aus drei verschiedenen Arten (abbauen, besiegen, angeln/ernten), damit der Tag abwechslungsreich ist.
 *
 * Zählregeln stehen in QuestService: Abbauen nur von Blöcken, die niemand selbst gesetzt hat; Besiegen nur durch den
 * Spieler; Ernten nur reife Pflanzen; alles nur im Überleben/Abenteuer.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class DailyQuests {

    public enum Kind {
        MINE, KILL, FISH, HARVEST
    }

    /**
     * Eine Aufgabe. keys: zählende Block-/Wesen-IDs (Bukkit-Schlüssel ohne „minecraft:“); bei FISH leer = jeder Fang.
     */
    public record Quest(Kind kind, Set<String> keys, int amount, int points, String title) {
        public boolean counts(Kind k, String key) {
            return kind == k && (keys.isEmpty() || keys.contains(key));
        }
    }

    /** Bonus, wenn alle drei Aufgaben des Tages geschafft sind. */
    public static final int ALL_DONE_BONUS = 20;

    private static final List<Quest> MINE = List.of(
            new Quest(Kind.MINE, Set.of("stone", "deepslate"), 128, 10, "Baue 128 Stein ab"),
            new Quest(Kind.MINE, Set.of("coal_ore", "deepslate_coal_ore"), 16, 15, "Baue 16 Kohleerz ab"),
            new Quest(Kind.MINE, Set.of("iron_ore", "deepslate_iron_ore"), 10, 20, "Baue 10 Eisenerz ab"),
            new Quest(Kind.MINE, Set.of("copper_ore", "deepslate_copper_ore"), 16, 15, "Baue 16 Kupfererz ab"),
            new Quest(Kind.MINE, Set.of("oak_log", "spruce_log", "birch_log", "jungle_log", "acacia_log",
                    "dark_oak_log", "mangrove_log", "cherry_log", "pale_oak_log"), 32, 10, "Fälle 32 Baumstämme"),
            new Quest(Kind.MINE, Set.of("sand", "red_sand"), 48, 10, "Grabe 48 Sand ab"),
            new Quest(Kind.MINE, Set.of("diamond_ore", "deepslate_diamond_ore"), 3, 30, "Finde 3 Diamanterze"),
            new Quest(Kind.MINE, Set.of("redstone_ore", "deepslate_redstone_ore"), 8, 20, "Baue 8 Redstone-Erz ab"));
    private static final List<Quest> KILL = List.of(
            new Quest(Kind.KILL, Set.of("zombie", "husk", "drowned"), 10, 15, "Besiege 10 Zombies"),
            new Quest(Kind.KILL, Set.of("skeleton", "stray"), 8, 15, "Besiege 8 Skelette"),
            new Quest(Kind.KILL, Set.of("creeper"), 3, 20, "Besiege 3 Creeper"),
            new Quest(Kind.KILL, Set.of("spider", "cave_spider"), 6, 15, "Besiege 6 Spinnen"),
            new Quest(Kind.KILL, Set.of("enderman"), 2, 25, "Besiege 2 Endermen"));
    private static final List<Quest> OTHER = List.of(
            new Quest(Kind.FISH, Set.of(), 5, 15, "Angle 5 Fänge"),
            new Quest(Kind.HARVEST, Set.of("wheat"), 24, 10, "Ernte 24 reifen Weizen"),
            new Quest(Kind.HARVEST, Set.of("carrots"), 24, 10, "Ernte 24 reife Karotten"),
            new Quest(Kind.HARVEST, Set.of("potatoes"), 24, 10, "Ernte 24 reife Kartoffeln"),
            new Quest(Kind.HARVEST, Set.of("sugar_cane"), 32, 10, "Ernte 32 Zuckerrohr"));

    private DailyQuests() {
    }

    /** Die drei Aufgaben des Tages: je eine aus Abbauen, Besiegen und Angeln/Ernten. */
    public static List<Quest> forDay(LocalDate day) {
        Random r = new Random(day.toEpochDay() * 0x9E3779B97F4A7C15L);
        List<Quest> out = new ArrayList<>();
        out.add(MINE.get(r.nextInt(MINE.size())));
        out.add(KILL.get(r.nextInt(KILL.size())));
        out.add(OTHER.get(r.nextInt(OTHER.size())));
        return out;
    }
}
