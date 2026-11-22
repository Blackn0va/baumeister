package com.blackn0va.baumeister.town.work;

import java.util.Set;

/**
 * Was Bewohner nie anfassen (Vertrag v013, Nicht-Ziele: „nie Behälter, nie Redstone“) und was sie überhaupt anfassen
 * dürfen. Zweistufig, damit eine Lücke in einer Liste nicht reicht:
 * 1. Erlaubnisliste: abgebaut werden nur Feldfrüchte und natürliche Stämme, gesetzt nur Saat, Setzlinge, Blumen und
 *    Trampelpfad, und nur auf Luft (Pfad: auf Grasblock).
 * 2. Sperre: Steht am Block oder an einem der sechs Nachbarn ein Behälter oder ein Redstone-Bauteil, fällt die Arbeit
 *    weg. Ein abgebauter Stamm würde sonst einen angehängten Hebel oder Knopf abreißen (Physik), und eine Kiste neben
 *    dem Feld ist fremdes Eigentum.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class Safety {

    /** Feldfrüchte, die geerntet und nachgepflanzt werden (Block = Saat, Stufe 0). */
    public static final Set<String> CROPS = Set.of("wheat", "carrots", "potatoes", "beetroots");
    /** Holzarten mit Stamm, Laub und Setzling gleichen Namens (Mangrove fehlt: Wurzeln und Keimling sind anders). */
    public static final Set<String> WOODS = Set.of("oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "cherry",
            "pale_oak");
    public static final Set<String> FLOWERS = Set.of("poppy", "dandelion", "cornflower", "oxeye_daisy");
    /** Boden, auf dem ein Baum wächst und ein Setzling gepflanzt wird. */
    public static final Set<String> GROUND = Set.of("dirt", "grass_block", "podzol", "coarse_dirt", "rooted_dirt",
            "moss_block");

    static final Set<String> CONTAINERS = Set.of("chest", "trapped_chest", "barrel", "furnace", "blast_furnace",
            "smoker", "hopper", "dropper", "dispenser", "brewing_stand", "ender_chest", "chiseled_bookshelf",
            "decorated_pot", "crafter", "lectern", "jukebox", "beehive", "bee_nest", "campfire", "soul_campfire",
            "shelf");
    static final Set<String> REDSTONE = Set.of("redstone_wire", "redstone_torch", "redstone_wall_torch", "repeater",
            "comparator", "lever", "observer", "piston", "sticky_piston", "piston_head", "moving_piston",
            "daylight_detector", "target", "tripwire", "tripwire_hook", "redstone_block", "redstone_lamp", "note_block",
            "rail", "powered_rail", "detector_rail", "activator_rail", "tnt", "sculk_sensor",
            "calibrated_sculk_sensor", "lightning_rod", "copper_bulb", "redstone_ore", "deepslate_redstone_ore");

    private Safety() {
    }

    /** Behälter? Auch alle Shulkerkisten und gefärbten/kupfernen Varianten. */
    public static boolean container(String t) {
        return t != null && (CONTAINERS.contains(t) || t.endsWith("shulker_box") || t.endsWith("_shelf")
                || t.endsWith("copper_chest"));
    }

    /** Redstone-Bauteil? Knöpfe, Druckplatten und Kupferbirnen in allen Sorten eingeschlossen. */
    public static boolean redstone(String t) {
        return t != null && (REDSTONE.contains(t) || t.endsWith("_button") || t.endsWith("_pressure_plate")
                || t.endsWith("copper_bulb"));
    }

    public static boolean forbidden(String t) {
        return container(t) || redstone(t);
    }

    /** Steht an (x, y, z) oder einem der sechs Nachbarn etwas Verbotenes? */
    public static boolean nearForbidden(Plot p, int x, int y, int z) {
        return forbidden(p.type(x, y, z)) || forbidden(p.type(x + 1, y, z)) || forbidden(p.type(x - 1, y, z))
                || forbidden(p.type(x, y + 1, z)) || forbidden(p.type(x, y - 1, z)) || forbidden(p.type(x, y, z + 1))
                || forbidden(p.type(x, y, z - 1));
    }

    public static boolean log(String t) {
        return t != null && t.endsWith("_log") && WOODS.contains(t.substring(0, t.length() - 4));
    }

    public static boolean leaves(String t) {
        return t != null && (t.endsWith("_leaves") && WOODS.contains(t.substring(0, t.length() - 7))
                || t.equals("azalea_leaves") || t.equals("flowering_azalea_leaves"));
    }

    /** Holzart eines Stamms („oak_log“ → „oak“), sonst null. */
    public static String wood(String logType) {
        return log(logType) ? logType.substring(0, logType.length() - 4) : null;
    }

    /** Erlaubnisliste (Stufe 1): Darf diese Änderung überhaupt geschehen? */
    public static boolean allowed(Task.Change c) {
        if (forbidden(c.expect()) || forbidden(c.type())) {
            return false;
        }
        return switch (c.kind()) {
            case BREAK -> CROPS.contains(c.expect()) || log(c.expect());
            case PLACE -> c.expect().equals("air") && (CROPS.contains(c.type()) || FLOWERS.contains(c.type())
                    || c.type().endsWith("_sapling") && WOODS.contains(c.type().substring(0, c.type().length() - 8)))
                    || c.expect().equals("grass_block") && c.type().equals("dirt_path");
        };
    }
}
