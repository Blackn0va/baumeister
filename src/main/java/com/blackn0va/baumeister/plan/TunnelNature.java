package com.blackn0va.baumeister.plan;

import java.util.Set;

/**
 * Was ein Block für den Tunnel- und Brückenbau ist (TunnelShape.Ground) – nach der ID, ohne Bukkit. Der Baumeister
 * gräbt nur durch Natur: Gestein, Erde, Erz, Kies, Eis, Höhlenbewuchs, Wasser und Lava. Alles andere gilt als
 * Gebautes (Wunsch 2026-11-16, „er baut schöne Tunnel“ – nicht durch fremde Keller): Bretter, Bruchstein, Fackeln,
 * Schienen, Spinnweben, Baumstämme. Daran endet der Bau, statt etwas abzureißen. Lieber einmal zu früh aufhören als
 * einmal durch eine Basis graben, die kein Grundstücksschutz deckt. Einzige Ausnahme: die eigenen Steine eines
 * Mineschachts, den die Welt erzeugt hat ({@link #inStructure}).
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
    /**
     * Mineschächte, die die Welt erzeugt hat (Wunsch 2026-11-16, „er wird irgendwann durch ca. die komplette Karte
     * gehen“): Ein gerader Tunnel kreuzt unter Tage alle paar hundert Blöcke einen. Vorher endete er an deren
     * Brettern (Gebautes) – quer über die Karte hieße das, an jedem Schacht von Hand freizuräumen. Jetzt gräbt er
     * durch, aber nur durch die Steine des Schachts selbst und nur innerhalb seiner Teile (TunnelSite fragt die
     * Bauwerksgrenzen der Welt ab): Was ein Spieler in einen Schacht gebaut hat (Glas, Laternen, Truhen …), bleibt
     * Gebautes. Andere Bauwerke (Festung, Antike Stadt, Prüfungskammer, Dorf) bleiben ganz – dort lebt jemand, liegt
     * Beute, oder ein Spieler hat sich eingerichtet. Wer die Liste auf alle Bauwerke ausweitet, gräbt durch Farmen in
     * Netherfestungen.
     */
    private static final Set<String> DIGGABLE_STRUCTURES = Set.of("mineshaft", "mineshaft_mesa");
    /**
     * Fackeln gehören dazu: Vanilla hängt an Stützbalken mit 5 % eine Wandfackel (gemessen 2026-11-16, Paper 26.2,
     * frische Welt: Tunnel hielt nach 21 Abschnitten an einer Wandfackel neben den Schachtbrettern), und wer einen
     * Schacht erkundet, steckt selbst welche hin. Ohne sie endete ein Tunnel an jedem beleuchteten Schacht.
     */
    private static final Set<String> MINESHAFT_BLOCKS = Set.of("oak_planks", "oak_fence", "oak_log",
            "dark_oak_planks", "dark_oak_fence", "dark_oak_log", "rail", "cobweb", "iron_chain", "chain", "wall_torch",
            "torch");

    private TunnelNature() {
    }

    /** Bauwerk der Welt (Schlüssel ohne „minecraft:“), dessen eigene Steine der Tunnel abbauen darf. */
    public static boolean diggableStructure(String structure) {
        return DIGGABLE_STRUCTURES.contains(structure);
    }

    /**
     * Was ein Block ist, der innerhalb eines Teils des Bauwerks structure liegt: Bretter, Zaun, Stamm, Schiene,
     * Spinnweben und Kette eines Mineschachts sind Natur (mit Wasser darin Flüssigkeit, damit es zuerst ausgeräumt
     * wird) – alles andere bleibt, was g sagt.
     */
    public static TunnelShape.Ground inStructure(String structure, String id, boolean waterlogged,
            TunnelShape.Ground g) {
        if (g == TunnelShape.Ground.BUILT && DIGGABLE_STRUCTURES.contains(structure) && MINESHAFT_BLOCKS.contains(id)) {
            return waterlogged ? TunnelShape.Ground.FLUID : TunnelShape.Ground.SOLID;
        }
        return g;
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
