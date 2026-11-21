package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.Pos;

import java.util.Set;
import java.util.function.Function;

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
 * Ungeladene Chunks (Absturz-Audit 2026-10-30, AK-8): Vorher las of() einfach drauflos; fehlte ein Chunk – Spieler
 * weitergegangen, „ja“ aus der Warteschlange –, erzeugte bzw. lud Bukkit ihn synchron im Server-Thread (gemessen beim
 * Scan desselben Kastens: bis 4,4 s, siehe ChunkPrefetch). Jetzt lädt whenLoaded() den Kasten vorher über
 * ChunkPrefetch (Paper asynchron, Spigot 2 je Tick), und ofLoaded() liest nur, wenn wirklich alles geladen ist –
 * sonst UNREADABLE, das zu keiner Prüfsumme passt. Wer ofLoaded() wieder durch of() ersetzt, holt den Stillstand
 * zurück; wer einen ungeladenen Chunk als Luft zählt, lässt den Überbau-Schutz dort Bauwerke übersehen (fail-open).
 *
 * Threads: nur Hauptthread (liest Blöcke). Fehler: keine; ein Chunk, der sich nicht laden lässt, ergibt UNREADABLE.
 */
final class SiteFingerprint {

    /** Bauplatz nicht (vollständig) geladen: passt zu keiner Prüfsumme, auch nicht zu sich selbst (ComfortFlow). */
    static final long UNREADABLE = Long.MIN_VALUE;

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

    /** Blockart-ID (ohne „minecraft:“) an einer lokalen Stelle des Rahmens; null = außerhalb der Welthöhe. */
    static Function<Pos, String> ids(SiteFrame f) {
        World w = f.world();
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight();
        return p -> {
            var b = f.blockAt(p);
            return b.getY() < minY || b.getY() >= maxY ? null : b.getType().getKey().getKey();
        };
    }

    /**
     * Dieselbe Summe wie bis 2026-11-21 in of(SiteFrame), nur mit austauschbarer Lesequelle (Test ohne Server).
     * of(SiteFrame) gibt es nicht mehr, damit niemand wieder ohne Ladeprüfung liest – Weg: ofLoaded(host, keys,
     * ids(f)).
     */
    static long of(Function<Pos, String> idAt) {
        long h = 1125899906842597L;
        for (int x = SiteFrame.SCAN_MIN.x(); x <= SiteFrame.SCAN_MAX.x(); x++) {
            for (int y = SiteFrame.SCAN_MIN.y(); y <= SiteFrame.SCAN_MAX.y(); y++) {
                for (int z = SiteFrame.SCAN_MIN.z(); z <= SiteFrame.SCAN_MAX.z(); z++) {
                    String id = idAt.apply(new Pos(x, y, z));
                    h = mix(h, id == null ? -1 : bucket(id));
                }
            }
        }
        return h;
    }

    /**
     * Prüfsumme nur über geladene Chunks: Fehlt einer der keys (SiteFrame.scanChunks), wird gar nichts gelesen und
     * UNREADABLE geliefert – nie als leer gezählt, nie synchron nachgeladen.
     */
    static long ofLoaded(ChunkPrefetch.Host host, long[] keys, Function<Pos, String> idAt) {
        for (long k : keys) {
            if (!host.loaded(ChunkPrefetch.cx(k), ChunkPrefetch.cz(k))) {
                return UNREADABLE;
            }
        }
        return of(idAt);
    }

    /**
     * Bauplatz vorladen (ChunkPrefetch, Tickets bis nach then), dann then auf dem Server-Thread – sofort, im selben
     * Tick, wenn schon alles geladen ist (der Normalfall mit dem Spieler daneben).
     */
    static void whenLoaded(ChunkPrefetch.Host host, long[] keys, Runnable then) {
        ChunkPrefetch.ensure(host, keys, 2, p -> {
            try {
                then.run();
            } finally {
                if (p != null) {
                    p.release();
                }
            }
        });
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
