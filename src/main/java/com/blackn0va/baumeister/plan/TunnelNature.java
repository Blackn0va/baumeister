package com.blackn0va.baumeister.plan;

import java.util.Set;

/**
 * Was ein Block für den Tunnel- und Brückenbau ist (TunnelShape.Ground) – nach der ID, ohne Bukkit. Der Baumeister
 * gräbt nur durch Natur: Gestein, Erde, Erz, Kies, Eis, Höhlenbewuchs, Wasser und Lava. Alles andere gilt als
 * Gebautes (Wunsch 2026-11-16, „er baut schöne Tunnel“ – nicht durch fremde Keller): Bretter, Bruchstein, Fackeln,
 * Schienen, Spinnweben, Baumstämme. Daran endet der Bau, statt etwas abzureißen. Lieber einmal zu früh aufhören (ein
 * Mineschacht mitten im Berg) als einmal durch eine Basis graben, die kein Grundstücksschutz deckt.
 *
 * Bewusst Gebautes, obwohl die Welt es erzeugt: verdächtiger Sand und Kies (Archäologie-Beute ginge verloren),
 * Glowstone außerhalb des Nethers, Kürbis und Melone, Stämme (eine Blockhütte sähe genauso aus) und Laub, das ein
 * Spieler gesetzt hat (persistent – liest TunnelSite aus den Blockdaten, hier zählt nur natürliches Laub als offen).
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class TunnelNature {

    private static final Set<String> FLUIDS = Set.of("water", "lava", "bubble_column", "kelp", "kelp_plant",
            "seagrass", "tall_seagrass");
    /** Festes Natürliches, das SiteSurvey.TERRAIN (Oberfläche) nicht kennt: Höhlen, Druse, tiefes Dunkel, Nether. */
    private static final Set<String> SOLID = Set.of("tuff", "calcite", "smooth_basalt", "amethyst_block",
            "budding_amethyst", "dripstone_block", "sculk", "sculk_catalyst", "sculk_sensor", "sculk_shrieker",
            "pale_moss_block", "rooted_dirt", "raw_iron_block", "raw_copper_block", "infested_stone",
            "infested_deepslate", "ancient_debris", "crimson_nylium", "warped_nylium", "nether_wart_block",
            "warped_wart_block", "bone_block", "sulfur", "cinnabar", "potent_sulfur");
    /** Nur im Nether natürlich. */
    private static final Set<String> SOLID_NETHER = Set.of("glowstone", "shroomlight", "crimson_stem",
            "warped_stem");
    /** Offen, darf weg (Bewuchs, Höhlendeko, Feuer) – zusätzlich zu SiteSurvey.isPlant. */
    private static final Set<String> OPEN = Set.of("sculk_vein", "small_amethyst_bud", "medium_amethyst_bud",
            "large_amethyst_bud", "amethyst_cluster", "pale_moss_carpet", "pale_hanging_moss", "crimson_roots",
            "warped_roots", "nether_sprouts", "crimson_fungus", "warped_fungus", "weeping_vines",
            "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "chorus_plant", "chorus_flower",
            "azalea", "flowering_azalea", "fire", "soul_fire", "frogspawn", "sulfur_spike", "snow");
    private static final Set<String> NOT_NATURAL = Set.of("suspicious_sand", "suspicious_gravel", "pumpkin", "melon",
            "farmland", "dirt_path");

    private TunnelNature() {
    }

    /**
     * @param waterlogged natürliches Wasser in einem Block (Tropfstein unter Wasser) zählt als Flüssigkeit; ein
     *                    gesetzter Block mit Wasser darin bleibt Gebautes
     * @param nether      im Nether (Glowstone und Pilzstämme sind dort Natur)
     */
    public static TunnelShape.Ground of(String id, boolean waterlogged, boolean nether) {
        if (BlockTiers.isAir(id)) {
            return TunnelShape.Ground.OPEN;
        }
        if (FLUIDS.contains(id)) {
            return TunnelShape.Ground.FLUID;
        }
        if (BlockTiers.REMOVAL_DANGEROUS.contains(id)) {
            return TunnelShape.Ground.FORBIDDEN;
        }
        if (NOT_NATURAL.contains(id)) {
            return TunnelShape.Ground.BUILT;
        }
        boolean open = OPEN.contains(id) || SiteSurvey.isPlant(id) || id.endsWith("_leaves");
        if (open) {
            return waterlogged ? TunnelShape.Ground.FLUID : TunnelShape.Ground.OPEN;
        }
        if (SiteSurvey.isTerrain(id) || SOLID.contains(id) || nether && SOLID_NETHER.contains(id)) {
            return TunnelShape.Ground.SOLID;
        }
        return TunnelShape.Ground.BUILT;
    }
}
