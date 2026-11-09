package com.blackn0va.baumeister.plan;

import java.util.Set;

/**
 * Zweiteilige Blöcke (Plan B3): Türen, hohe Pflanzen, hohes Seegras, Kannenpflanze, kleines Tropfblatt, Betten und
 * Doppeltruhen. Eine Hälfte allein zerfällt beim nächsten Nachbar-Update (Türen, Pflanzen, Betten) oder wird von
 * Minecraft zur Einzeltruhe gemacht. Deshalb setzt der Bau beide Hälften in EINER Aktion (BuildJob, ohne Physik
 * dazwischen), und der Compiler ergänzt die zweite Hälfte, wenn der Plan nur den Block nennt.
 *
 * Vorher sperrte BlockRules hohe Pflanzen und Betten ganz („die untere Hälfte allein zerfällt“); Türen hatten als
 * einzige eine Paar-Regel.
 *
 * Lokale Richtungen: back = +z, front = −z, right = +x, left = −x (wie Orientation); Himmelsrichtungen im Nordrahmen.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class TwoPart {

    /** Untere/obere Hälfte über half=lower/upper. */
    private static final Set<String> TALL = Set.of("tall_grass", "large_fern", "sunflower", "lilac", "rose_bush",
            "peony", "tall_seagrass", "pitcher_plant", "small_dripleaf", "pitcher_crop");

    private TwoPart() {
    }

    /** Erste und zweite Hälfte an ihren Stellen. */
    public record Pair(Pos firstPos, BlockSpec first, Pos secondPos, BlockSpec second) {
    }

    public static boolean isVertical(BlockSpec spec) {
        return spec.isDoor() || TALL.contains(spec.id());
    }

    public static boolean isBed(BlockSpec spec) {
        return spec.id().endsWith("_bed");
    }

    public static boolean isChest(BlockSpec spec) {
        return spec.id().equals("chest") || spec.id().equals("trapped_chest") || spec.id().endsWith("copper_chest");
    }

    /**
     * Ergänzt die zweite Hälfte, wenn der Plan nur den Block nennt (kein half bzw. part): Tür/Pflanze unten bei p und
     * oben darüber, Bett mit Fuß bei p und Kopf in Blickrichtung (facing, sonst back – vom Spieler weg). null = nichts
     * zu ergänzen (schon eine Hälfte genannt, oder kein zweiteiliger Block). Doppeltruhen ergänzt der Compiler nicht –
     * eine Truhe ohne type ist eine Einzeltruhe.
     */
    public static Pair expand(Pos p, BlockSpec spec) {
        if (isVertical(spec) && !spec.props().containsKey("half")) {
            return new Pair(p, spec.with("half", "lower"), p.up(), spec.with("half", "upper"));
        }
        if (isBed(spec) && !spec.props().containsKey("part")) {
            BlockSpec base = spec.props().containsKey("facing") ? spec : spec.with("facing", "back");
            Pos d = offset(base.props().get("facing"));
            if (d == null) {
                return null;
            }
            return new Pair(p, base.with("part", "foot"), p.add(d.x(), d.y(), d.z()), base.with("part", "head"));
        }
        return null;
    }

    /** Ist spec die zweite Hälfte (kommt mit der ersten)? Obere Hälfte, Bettkopf, rechte Truhenhälfte. */
    public static boolean isSecond(BlockSpec spec) {
        if (isVertical(spec)) {
            return "upper".equals(spec.props().get("half"));
        }
        if (isBed(spec)) {
            return "head".equals(spec.props().get("part"));
        }
        if (isChest(spec)) {
            return "right".equals(spec.props().get("type"));
        }
        return false;
    }

    /** Ist spec die erste Hälfte eines Paars? */
    public static boolean isFirst(BlockSpec spec) {
        if (isVertical(spec)) {
            return "lower".equals(spec.props().get("half"));
        }
        if (isBed(spec)) {
            return "foot".equals(spec.props().get("part"));
        }
        if (isChest(spec)) {
            return "left".equals(spec.props().get("type"));
        }
        return false;
    }

    /**
     * Wo die zweite Hälfte zur ersten (an p) liegen muss und wie sie aussehen muss, damit sie zusammengehören; null bei
     * Blöcken ohne zweite Hälfte oder unlesbarer Richtung. Truhe: Bei type=left liegt die andere Hälfte im
     * Uhrzeigersinn neben facing (ChestBlock.getConnectedDirection).
     */
    public static Pos partnerOf(Pos p, BlockSpec first) {
        if (isVertical(first)) {
            return p.up();
        }
        String facing = Orientation.applyPending(first).props().get("facing");
        if (facing == null) {
            return null;
        }
        if (isBed(first)) {
            Pos d = offset(facing);
            return d == null ? null : p.add(d.x(), d.y(), d.z());
        }
        if (isChest(first)) {
            Pos d = offset(StateTransform.rotate(facing, 1));
            return d == null ? null : p.add(d.x(), d.y(), d.z());
        }
        return null;
    }

    /** Gehört second an partnerOf(first) wirklich dazu (gleicher Block, passende Hälfte, gleiche Richtung)? */
    public static boolean matches(BlockSpec first, BlockSpec second) {
        if (!first.id().equals(second.id()) || !isSecond(second)) {
            return false;
        }
        if (isVertical(first)) {
            return true;
        }
        return java.util.Objects.equals(Orientation.applyPending(first).props().get("facing"),
                Orientation.applyPending(second).props().get("facing"));
    }

    /** Lokaler Versatz einer Richtung (lokale Namen oder Himmelsrichtung im Nordrahmen); null bei up/down. */
    static Pos offset(String dir) {
        return switch (StateTransform.toCompass(dir)) {
            case "north" -> new Pos(0, 0, 1);
            case "south" -> new Pos(0, 0, -1);
            case "east" -> new Pos(1, 0, 0);
            case "west" -> new Pos(-1, 0, 0);
            default -> null;
        };
    }
}
