package com.blackn0va.baumeister.companion;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Das Abenteuer mit Vera: Minecraft vom ersten Holz bis zum Enderdrachen, in Etappen, nur in der Farmwelt.
 *
 * Wunsch 2026-11-04.
 *
 * Fortschritt aus dem, was der Spieler WÄHREND des Abenteuers hat und wo er war (Merkmale, flags), nicht aus den
 * Errungenschaften von Minecraft: Die gelten serverweit und sind bei jedem, der schon im Kreativmodus oder in der
 * Plotwelt war, längst erledigt (enter_the_nether, iron_tools …) – das Abenteuer wäre beim Start schon vorbei.
 * Merkmale bleiben, einmal gesehen, im Spielbuch (AdventureBook); verbrauchte Gegenstände nehmen nichts zurück.
 *
 * Etappen laufen nur vorwärts. Ist eine spätere Etappe erfüllt (Eisenspitzhacke ohne Bett), springt das Abenteuer
 * dorthin – eine Geschichte, die auf ein übersprungenes Bett wartet, während der Spieler schon Diamanten hat, wäre
 * keine Hilfe.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class Adventure {

    public enum Mode {
        /** Vera kämpft mit, nach dem Tod geht es weiter. */
        NORMAL,
        /** Ein Leben: Vera rät nur, ein Tod beendet das Abenteuer. */
        HARDCORE
    }

    /** Wohin Vera auf „wo geht's lang?“ zeigt (gerechnet in CompanionAdventure, nie im Modell). */
    public enum Guide {
        TREES, VILLAGE, CAVES, DIAMONDS, RUINED_PORTAL, FORTRESS, PEARLS, STRONGHOLD, DRAGON, NONE
    }

    public record Stage(String flag, String title, String goal, String hint, Guide guide) {
    }

    public static final List<Stage> STAGES = List.of(
            new Stage("holz", "Holz sammeln", "Schlag Holz von einem Baum, mindestens fünf Stämme.",
                    "Halt die linke Maustaste auf einem Baumstamm gedrückt. Aus einem Stamm werden vier Bretter.",
                    Guide.TREES),
            new Stage("holzspitzhacke", "Werkbank und Holzspitzhacke", "Bau eine Werkbank und eine Holzspitzhacke.",
                    "Vier Bretter ergeben eine Werkbank. Darauf drei Bretter oben und zwei Stöcke in die Mitte – "
                            + "fertig ist die Spitzhacke.", Guide.NONE),
            new Stage("stein", "Steinzeit", "Bau Stein ab und mach dir Steinwerkzeug.",
                    "Grab an einem Hang oder ein paar Blöcke nach unten, bis grauer Stein kommt. Drei Bruchstein und "
                            + "zwei Stöcke ergeben die Steinspitzhacke.", Guide.NONE),
            new Stage("bett", "Ein sicherer Schlafplatz", "Mach dir ein Bett, bevor die Nacht kommt.",
                    "Drei Wolle von Schafen und drei Bretter ergeben ein Bett. Schlafen überspringt die Nacht und "
                            + "setzt deinen Wiedereinstieg. In Dörfern stehen oft schon Betten.", Guide.VILLAGE),
            new Stage("essen", "Essen sichern", "Sammel Vorrat: mindestens sechs Stück Essen.",
                    "Kühe, Schweine und Schafe geben Fleisch, im Ofen gebraten macht es richtig satt. Drei Weizen "
                            + "sind ein Brot.", Guide.VILLAGE),
            new Stage("eisen", "Eisenzeit", "Bau Eisenerz ab und schmelz es im Ofen zu Barren.",
                    "Eisenerz ist hellbraun gesprenkelt und liegt in Höhlen, am häufigsten um Höhe 16. Abbauen mit der "
                            + "Steinspitzhacke, schmelzen mit Kohle im Ofen.", Guide.CAVES),
            new Stage("eisenwerkzeug", "Eisenwerkzeug", "Mach dir eine Eisenspitzhacke.",
                    "Drei Eisenbarren und zwei Stöcke. Erst mit Eisen kannst du Diamanten abbauen.", Guide.CAVES),
            new Stage("rüstung", "Rüstung", "Zieh dir Eisenrüstung an.",
                    "Vierundzwanzig Barren reichen für die ganze Rüstung, schon ein Brustpanzer hilft viel. Ein "
                            + "Schild aus Eisen und Brettern hält Pfeile ab.", Guide.CAVES),
            new Stage("diamant", "Diamanten", "Finde Diamanten.",
                    "Diamanten liegen ganz unten, um Höhe minus 58. Grab dich treppenförmig runter, nie senkrecht, und "
                            + "nimm einen Wassereimer gegen Lava mit.", Guide.DIAMONDS),
            new Stage("nether", "Ab in den Nether", "Bau ein Netherportal und geh hindurch.",
                    "Obsidian entsteht, wenn Wasser auf stille Lava fließt; abbauen nur mit der Diamantspitzhacke. "
                            + "Rahmen vier breit, fünf hoch, mit dem Feuerzeug anzünden. Ruinenportale haben oft schon "
                            + "Obsidian.", Guide.RUINED_PORTAL),
            new Stage("lohe", "Lohenruten", "Hol dir Lohenruten aus einer Netherfestung.",
                    "Netherfestungen sind aus dunklen Netherziegeln. Lohen schießen Feuerbälle – nimm viel Essen mit "
                            + "und kämpf aus der Deckung.", Guide.FORTRESS),
            new Stage("perlen", "Enderperlen", "Sammel zwölf Enderperlen.",
                    "Endermen lassen Perlen fallen. Schau ihnen nicht in die Augen, außer du willst kämpfen; im "
                            + "Wirrwald des Nethers gibt es viele. Bauern im Dorf tauschen auch welche.", Guide.PEARLS),
            new Stage("end", "Die Festung und das End", "Folge den Enderaugen zur Festung und spring ins End.",
                    "Lohenstaub und Enderperle ergeben ein Enderauge. Wirf es und lauf ihm nach; wo es nach unten "
                            + "fliegt, liegt die Festung. Setz die Augen in den Portalrahmen.", Guide.STRONGHOLD),
            new Stage("drache", "Der Enderdrache", "Besiege den Enderdrachen.",
                    "Zerstör zuerst die Kristalle auf den Obsidiansäulen, am besten mit Pfeilen. Schlag zu, wenn der "
                            + "Drache auf dem Brunnen in der Mitte landet.", Guide.DRAGON));

    /** Essbares, das als Vorrat zählt (Etappe „essen“). */
    static final Set<String> FOOD = Set.of("bread", "cooked_beef", "cooked_porkchop", "cooked_mutton",
            "cooked_chicken", "cooked_rabbit", "cooked_cod", "cooked_salmon", "baked_potato", "golden_carrot",
            "carrot", "apple", "beetroot", "melon_slice", "sweet_berries", "pumpkin_pie", "mushroom_stew",
            "rabbit_stew", "beef", "porkchop", "mutton", "chicken", "potato", "cookie");

    private Adventure() {
    }

    /**
     * Merkmale aus dem Inventar (IDs ohne „minecraft:“, Anzahl über alle Plätze inklusive Rüstung) und der Lage.
     *
     * @param environment "normal", "nether" oder "the_end" (World.Environment klein)
     */
    public static Set<String> flags(Map<String, Integer> items, boolean spawnPoint, String environment) {
        Set<String> out = new HashSet<>();
        if (sum(items, id -> id.endsWith("_log") || id.endsWith("_stem")) >= 5
                || sum(items, id -> id.endsWith("_planks")) >= 8 || has(items, "crafting_table")) {
            out.add("holz");
        }
        if (has(items, "wooden_pickaxe")) {
            out.add("holzspitzhacke");
        }
        if (has(items, "stone_pickaxe") || has(items, "stone_axe") || has(items, "stone_sword")
                || has(items, "stone_shovel")) {
            out.add("stein");
        }
        if (spawnPoint || sum(items, id -> id.endsWith("_bed")) > 0) {
            out.add("bett");
        }
        if (sum(items, FOOD::contains) >= 6) {
            out.add("essen");
        }
        if (has(items, "iron_ingot") || has(items, "iron_block")) {
            out.add("eisen");
        }
        if (has(items, "iron_pickaxe")) {
            out.add("eisenwerkzeug");
        }
        if (sum(items, id -> id.matches("(iron|diamond|netherite)_(helmet|chestplate|leggings|boots)")) > 0) {
            out.add("rüstung");
        }
        if (has(items, "diamond") || sum(items, id -> id.startsWith("diamond_")) > 0) {
            out.add("diamant");
        }
        if ("nether".equals(environment)) {
            out.add("nether");
        }
        if (has(items, "blaze_rod") || has(items, "blaze_powder")) {
            out.add("lohe");
        }
        if (count(items, "ender_pearl") + count(items, "ender_eye") >= 12) {
            out.add("perlen");
        }
        if ("the_end".equals(environment)) {
            out.add("end");
        }
        if (has(items, "dragon_egg")) {
            out.add("drache");
        }
        return out;
    }

    /**
     * Neue Etappe: höchste erfüllte Etappe + 1, aber nie zurück; danach weiter, solange die nächste erfüllt ist.
     * STAGES.size() = geschafft.
     */
    public static int advance(int current, Set<String> flags) {
        int next = Math.max(0, current);
        for (int i = STAGES.size() - 1; i >= next; i--) {
            if (flags.contains(STAGES.get(i).flag())) {
                next = i + 1;
                break;
            }
        }
        while (next < STAGES.size() && flags.contains(STAGES.get(next).flag())) {
            next++;
        }
        return next;
    }

    public static boolean finished(int stage) {
        return stage >= STAGES.size();
    }

    /** „Etappe 6 von 14: Eisenzeit“ bzw. „alle 14 Etappen geschafft“. */
    public static String progress(int stage) {
        if (finished(stage)) {
            return "alle " + STAGES.size() + " Etappen geschafft";
        }
        return "Etappe " + (stage + 1) + " von " + STAGES.size() + ": " + STAGES.get(stage).title();
    }

    private static boolean has(Map<String, Integer> items, String id) {
        return count(items, id) > 0;
    }

    private static int count(Map<String, Integer> items, String id) {
        return items.getOrDefault(id, 0);
    }

    private static int sum(Map<String, Integer> items, java.util.function.Predicate<String> which) {
        int n = 0;
        for (Map.Entry<String, Integer> e : items.entrySet()) {
            if (which.test(e.getKey())) {
                n += e.getValue();
            }
        }
        return n;
    }
}
