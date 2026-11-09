package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Pos;

import java.util.Set;

import org.bukkit.World;

/**
 * Prüfsumme über die Blockarten im Scan-Kasten eines Bauplatzes (SiteFrame.SCAN_MIN..SCAN_MAX, ~50 000 Blöcke wie
 * SiteFrame.survey()).
 *
 * Wozu: Eine Bauvorschau wird oft erst Minuten später bestätigt, manchmal aus der Warteschlange heraus. Überbau-Schutz
 * und die Entscheidung „mittig vor den Spieler oder in Scan-Koordinaten“ hingen am Scan beim Planen. Steht inzwischen
 * etwas anderes da, darf der alte Plan nicht gebaut werden – ComfortFlow vergleicht deshalb die Prüfsumme beim Zeigen
 * mit der beim Bestätigen (fail-closed). Derselbe Kasten wie der Überbau-Schutz: Außerhalb schützt auch ein sofortiger
 * Bau nichts.
 *
 * Was ohne Spieler von selbst wächst, sich ausbreitet oder vergeht, zählt nicht einzeln (bucket): Vorher ging jede
 * Blockart einzeln ein, und Gras, das sich auf Erde ausbreitet, wachsender Seetang, Zuckerrohr, Bambus oder Kaktus,
 * Ranken, fallender Schnee, Feuer oder trocknender Ackerboden hätten ein „ja“ innerhalb der 120 s scheitern lassen,
 * obwohl niemand gebaut hat (Prüferbefund; live nicht gemessen). Solche Arten zählen jetzt wie das, woraus sie
 * wachsen: Luft, Erde oder Wasser. Alles, was nur ein Spieler setzt (Baublöcke, Laub, Feldfrüchte, Blumen), bleibt
 * einzeln drin – wer die Liste erweitert, schwächt den Schutz für Spielerbauten.
 *
 * Threads: nur Hauptthread (liest Blöcke; ungeladene Chunks werden dabei geladen, wie beim Scan). Fehler: keine.
 */
final class SiteFingerprint {

    /** Wächst in Luft hinein oder vergeht zu Luft (Zufallstick, Wetter): zählt wie Luft. */
    private static final Set<String> VOLATILE = Set.of(
            "air", "cave_air", "void_air", "sugar_cane", "bamboo", "bamboo_sapling", "cactus", "vine",
            "cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant", "twisting_vines",
            "twisting_vines_plant", "pointed_dripstone", "brown_mushroom", "red_mushroom", "snow", "fire",
            "soul_fire");

    /** Erdböden, die ineinander übergehen (Gras und Myzel breiten sich aus, Ackerboden trocknet). */
    private static final Set<String> SOIL = Set.of("grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt",
            "mycelium", "farmland", "dirt_path");

    /** Wasser friert in kalten Biomen, Seetang wächst ins Wasser hinein, Blasensäulen entstehen und vergehen. */
    private static final Set<String> WATER = Set.of("water", "ice", "frosted_ice", "kelp", "kelp_plant",
            "bubble_column");

    private SiteFingerprint() {
    }

    static long of(SiteFrame f) {
        World w = f.world();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        long h = 1125899906842597L;
        for (int x = SiteFrame.SCAN_MIN.x(); x <= SiteFrame.SCAN_MAX.x(); x++) {
            for (int y = SiteFrame.SCAN_MIN.y(); y <= SiteFrame.SCAN_MAX.y(); y++) {
                for (int z = SiteFrame.SCAN_MIN.z(); z <= SiteFrame.SCAN_MAX.z(); z++) {
                    var b = f.blockAt(new Pos(x, y, z));
                    h = mix(h, b.getY() < minY || b.getY() >= maxY ? -1 : bucket(b.getType().getKey().getKey()));
                }
            }
        }
        return h;
    }

    /** Wert einer Blockart (ID ohne „minecraft:“) für die Prüfsumme: 0 = von selbst veränderlich, 1 Erde, 2 Wasser. */
    static int bucket(String id) {
        if (VOLATILE.contains(id)) {
            return 0;
        }
        if (SOIL.contains(id)) {
            return 1;
        }
        if (WATER.contains(id)) {
            return 2;
        }
        // 3 + Hash: nie 0..2, verschiedene Arten (fast immer) verschiedene Werte.
        return 3 + (id.hashCode() & 0x3FFFFFFF);
    }

    /** Reihenfolge zählt: dieselben Blöcke an anderen Stellen ergeben eine andere Summe. */
    static long mix(long h, int value) {
        return 31 * h + value;
    }
}
