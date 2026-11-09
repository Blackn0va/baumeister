package com.blackn0va.baumeister;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Tick-Budget eines Stadtbau-Arbeiters: wie viele Zellen er in diesem Tick noch anfassen darf.
 *
 * Vorher kostete jede Zelle eine Einheit des Budgets (stadt.blöcke-pro-tick), auch eine, die schon stimmte und nur
 * gelesen wurde. Gemessen 2026-10-29 (Testserver, Bad Kreuznach 400 m, Flachwelt): 1 865 317 Zellen geprüft, davon
 * 1 014 564 gesetzt – 46 % der Zellen waren Luft auf Luft oder gleiche Blöcke. Nach JFR kostet eine solche Zelle
 * (getBlockAt + getBlockData + equals) rund ein Fünftel einer gesetzten (dazu setBlockData, Rückgängig,
 * Behälterprüfung); gerechnet wird mit einem Viertel, etwas teurer als gemessen. Der Bau lief genau am Budget
 * (~33 000 Zellen/s bei 2 000 je Tick), der Server hatte Luft: mittlere Tickzeit 9,9 ms.
 * Jetzt: jede angefasste Zelle kostet 1, eine gesetzte zusammen samePerBlock (Vorgabe 4); das Budget heißt weiter
 * „Blöcke je Tick“ und meint gesetzte Blöcke. Die Zeitgrenze je Tick (stadt.ms-pro-tick) bleibt die harte Obergrenze
 * für langsame Rechner (dev01) – das Budget wird nur in Zellen, nie in Zeit großzügiger.
 *
 * Warum einstellbar (stadt.gleiche-zellen-je-block, Prüfbefund 2026-10-29): Das Verhältnis 1:4 ist auf dem Laptop
 * gemessen, nicht auf dem Celeron von dev01. Weil es Kosten gleichsetzt, ist der teuerste Tick über fertigem Gelände
 * so teuer wie vorher der teuerste über frischem – stimmt das Verhältnis dort nicht, stellt 1 exakt das alte Verhalten
 * her (jede Zelle eine Einheit), ohne neuen Bau.
 *
 * Wer die Vorgabe wieder auf 1 stellt, bremst jeden Bau über schon stehendem Gelände (zweiter Lauf, Weltbau über
 * Wiesen) auf das Tempo, als würde jeder Block neu gesetzt.
 *
 * Threads: nur Hauptthread. Fehler: keine.
 */
final class BuildBudget {

    /** Config-Schlüssel: so viele schon stimmende Zellen kosten so viel wie ein gesetzter Block. */
    static final String CONFIG_KEY = "stadt.gleiche-zellen-je-block";
    static final int DEFAULT_SAME_PER_BLOCK = 4;
    static final int MIN_SAME_PER_BLOCK = 1;
    /** Mehr als 8 ist nie gemessen worden; das Budget würde in Zellen beliebig groß. */
    static final int MAX_SAME_PER_BLOCK = 8;

    private final int set;
    private int units;

    /** blocks = gesetzte Blöcke, die dieser Arbeiter in diesem Tick höchstens schafft; Verhältnis wie vorgegeben. */
    BuildBudget(int blocks) {
        this(blocks, DEFAULT_SAME_PER_BLOCK);
    }

    /** samePerBlock: so viele schon stimmende Zellen kosten so viel wie ein gesetzter Block (1 = wie vor 2026-10-29). */
    BuildBudget(int blocks, int samePerBlock) {
        this.set = clamp(samePerBlock);
        this.units = Math.max(1, blocks) * set;
    }

    /** Wert aus der Config; fehlt der Schlüssel (alte config.yml auf dev01), gilt die Vorgabe. */
    static int samePerBlock(ConfigurationSection cfg) {
        return clamp(cfg == null ? DEFAULT_SAME_PER_BLOCK : cfg.getInt(CONFIG_KEY, DEFAULT_SAME_PER_BLOCK));
    }

    static int clamp(int samePerBlock) {
        return Math.max(MIN_SAME_PER_BLOCK, Math.min(MAX_SAME_PER_BLOCK, samePerBlock));
    }

    boolean left() {
        return units > 0;
    }

    /** Zelle angefasst (gelesen, verglichen) – egal, ob sie danach gesetzt wird. */
    void touched() {
        units--;
    }

    /** Die eben angefasste Zelle wurde auch gesetzt: Rest auf den vollen Preis nachbuchen. */
    void placed() {
        units -= set - 1;
    }
}
