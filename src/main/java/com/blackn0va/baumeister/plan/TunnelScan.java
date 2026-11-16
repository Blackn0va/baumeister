package com.blackn0va.baumeister.plan;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vorhandene Tunnel, Treppen und Brücken erkennen, um dort weiterzubauen (Wunsch 2026-11-16: „er muss Tunnel und
 * Brücken erkennen und dort weiter bauen“) – auch alte, fremde Bauweisen und selbst gegrabene Gänge. Gelesen wird nur,
 * was um den Spieler steht, in seiner Blickrichtung: lokal wie TunnelShape, (0,0,0) = seine Füße, z nach vorn.
 *
 * Reihenfolge: Steht er auf einer Treppenstufe, die nach vorn steigt, ist es eine Treppe; hat er eine Decke über sich
 * und links und rechts Wände, ein Tunnel; steht er im Freien auf einem Deck mit Geländer auf beiden Seiten, eine
 * Brücke. Dann geht es nach vorn bis zum Ende: beim Tunnel bis zum Fels (öffnet er sich vorher in eine Höhle oder ins
 * Freie, ist dort kein Ende zum Weitergraben), bei der Treppe bis zur letzten Stufe, bei der Brücke bis dahin, wo das
 * Deck fehlt. Maße, Steine, Bogen, Lichter und ihr Abstand kommen aus den letzten Abschnitten vor dem Ende – so läuft
 * der neue Teil genauso weiter. Schaut der Spieler quer zum Gang, misst er die Länge als Breite und erkennt nichts:
 * „schau in die Richtung, in die es weitergehen soll“.
 *
 * Threads: zustandslos. Fehler: keine – nichts erkannt heißt leer.
 */
public final class TunnelScan {

    /** Block an einer Stelle, Zustände lokal (facing=back heißt: in Blickrichtung). */
    public interface Look {
        BlockSpec at(Pos local);
    }

    /**
     * @param end    Füße auf dem letzten fertigen Abschnitt, mittig – dort beginnt das Weiterbauen mit Abschnitt 1
     * @param anchor Lichtanker relativ zu end (TunnelShape.Spec.anchor), {@link TunnelShape#NO_ANCHOR} ohne Licht
     */
    public record Found(TunnelOrder.Kind kind, int half, int height, Pos end, int anchor, String wall, String floor,
            String rib, String arch, String step, String light, String chain, String railing) {
    }

    /** So weit sucht er nach vorn das Ende. */
    public static final int MAX_WALK = 128;
    /** Breiter als 2 × 3 + 1 ist kein Gang mehr (oder der Spieler schaut quer). */
    static final int MAX_HALF = 3;
    static final int MAX_HEIGHT = 6;
    /** Aus so vielen Abschnitten vor dem Ende kommen Steine und Lichter. */
    static final int SAMPLE = 12;

    private static final Set<String> PASSABLE = Set.of("short_grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "snow", "glow_lichen", "vine", "moss_carpet", "redstone_wire", "tripwire", "ladder");

    private TunnelScan() {
    }

    public static Optional<Found> scan(Look look) {
        if (!free(look.at(new Pos(0, 0, 0))) || !free(look.at(new Pos(0, 1, 0)))) {
            return Optional.empty();
        }
        Optional<Found> stairs = stairs(look);
        if (stairs.isPresent()) {
            return stairs;
        }
        if (ceiling(look, 0, 0) > 0) {
            return tunnel(look);
        }
        return bridge(look);
    }

    // ---------------------------------------------------------------- Treppe

    private static Optional<Found> stairs(Look look) {
        if (!climbing(look.at(new Pos(0, -1, 0))) || !climbing(look.at(new Pos(0, -2, -1)))
                && !climbing(look.at(new Pos(0, 0, 1)))) {
            return Optional.empty();
        }
        int l = 0;
        while (l < MAX_HALF && climbing(look.at(new Pos(-l - 1, -1, 0)))) {
            l++;
        }
        int r = 0;
        while (r < MAX_HALF && climbing(look.at(new Pos(r + 1, -1, 0)))) {
            r++;
        }
        int c = Math.floorDiv(r - l, 2);
        int half = (l + r + 1) / 2;
        // Bis zur letzten Stufe: jede nächste einen höher und einen weiter.
        int e = 0;
        while (e < MAX_WALK && climbing(look.at(new Pos(c, e, e + 1)))) {
            e++;
        }
        // Höhe dort, wo der Spieler steht: Am Ende liegt eine fertige Treppe schon im Freien (keine Decke mehr).
        int measured = ceilingAt(look, c, 0, 0);
        int height = measured == 0 ? TunnelOrder.Size.NORMAL.height : Math.max(2, Math.min(MAX_HEIGHT, measured));
        Pos end = new Pos(c, e, e);
        Tally walls = new Tally();
        Tally steps = new Tally();
        Tally lights = new Tally();
        int lastLight = Integer.MIN_VALUE;
        for (int z = Math.max(0, e - SAMPLE); z <= e; z++) {
            steps.add(look.at(new Pos(c, z - 1, z)).id());
            for (int y = z; y < z + height; y++) {
                walls.add(look.at(new Pos(-l - 1, y, z)).id());
                walls.add(look.at(new Pos(r + 1, y, z)).id());
            }
            BlockSpec top = look.at(new Pos(c, z + height - 1, z));
            if (TunnelStyle.HANGING_LIGHTS.contains(top.id())) {
                lights.add(top.id());
                lastLight = z;
            }
        }
        return Optional.of(new Found(TunnelOrder.Kind.STAIRS_UP, half, height, end, anchorFrom(lastLight, e),
                walls.wallBlock(), steps.top(), walls.second(), null, steps.top(), lights.top(), null, null));
    }

    private static boolean climbing(BlockSpec s) {
        return s.isStairs() && "back".equals(s.props().get("facing")) && !"top".equals(s.props().get("half"));
    }

    // ---------------------------------------------------------------- Tunnel

    private static Optional<Found> tunnel(Look look) {
        int l = 0;
        while (l <= MAX_HALF && free(look.at(new Pos(-l - 1, 0, 0)))) {
            l++;
        }
        int r = 0;
        while (r <= MAX_HALF && free(look.at(new Pos(r + 1, 0, 0)))) {
            r++;
        }
        if (l > MAX_HALF || r > MAX_HALF) {
            return Optional.empty();  // Raum oder quer geschaut
        }
        int c = Math.floorDiv(r - l, 2);
        int half = (l + r + 1) / 2;
        int height = ceiling(look, c, 0);
        if (height < 2 || height > MAX_HEIGHT || !corridor(look, c, l, r, height, -1)
                && !corridor(look, c, l, r, height, 1)) {
            return Optional.empty();
        }
        // Wie weit der Gang hinter dem Spieler reicht – ein Raum ist kein Gang (siehe unten).
        int behind = 0;
        while (behind < 2 * MAX_WALK && corridor(look, c, l, r, height, -behind - 1)) {
            behind++;
        }
        // Nach vorn, solange es ein Gang ist; am Fels ist das Ende. Öffnet er sich vorher, gibt es kein Ende zum
        // Graben.
        int e = 0;
        while (true) {
            if (e >= MAX_WALK) {
                return Optional.empty();
            }
            Pos next = new Pos(c, 0, e + 1);
            if (!free(look.at(next)) && !free(look.at(next.up()))) {
                break;
            }
            if (!corridor(look, c, l, r, height, e + 1)) {
                return Optional.empty();
            }
            e++;
        }
        // Mindestens doppelt so lang wie breit (und 4 Blöcke): Ein Keller von 7 × 7 hätte sonst als Tunnel von 7
        // Blöcken Breite gegolten, und „bau weiter“ hätte ihn als Riesentunnel in den Fels verlängert.
        if (behind + 1 + e < Math.max(4, 2 * (l + r + 1))) {
            return Optional.empty();
        }
        Tally walls = new Tally();
        Tally floors = new Tally();
        Tally arches = new Tally();
        Tally lights = new Tally();
        Tally chains = new Tally();
        int lastLight = Integer.MIN_VALUE;
        for (int z = Math.max(0, e - SAMPLE); z <= e; z++) {
            for (int y = 0; y < height; y++) {
                walls.add(look.at(new Pos(-l - 1, y, z)).id());
                walls.add(look.at(new Pos(r + 1, y, z)).id());
            }
            for (int x = -l; x <= r; x++) {
                floors.add(look.at(new Pos(x, -1, z)).id());
                walls.add(look.at(new Pos(x, height, z)).id());
            }
            BlockSpec corner = look.at(new Pos(r, height - 1, z));
            if (corner.isStairs()) {
                arches.add(corner.id());
            }
            for (int y = height - 1; y >= Math.max(1, height - 2); y--) {
                BlockSpec hang = look.at(new Pos(c, y, z));
                if (TunnelStyle.HANGING_LIGHTS.contains(hang.id())) {
                    lights.add(hang.id());
                    lastLight = z;
                } else if (TunnelStyle.CHAINS.contains(hang.id())) {
                    chains.add(hang.id());
                }
            }
            for (int side = -1; side <= 1; side += 2) {
                BlockSpec niche = look.at(new Pos(side < 0 ? -l - 1 : r + 1, height - 1, z));
                if (TunnelStyle.HANGING_LIGHTS.contains(niche.id())) {
                    lights.add(niche.id());
                    lastLight = z;
                }
            }
            BlockSpec embedded = look.at(new Pos(c, height, z));
            if (TunnelStyle.BLOCK_LIGHTS.contains(embedded.id())) {
                lights.add(embedded.id());
                lastLight = z;
            }
        }
        return Optional.of(new Found(TunnelOrder.Kind.TUNNEL, half, height, new Pos(c, 0, e), anchorFrom(lastLight, e),
                walls.wallBlock(), floors.wallBlockOr(walls.wallBlock()), walls.second(), arches.top(), null,
                lights.top(), chains.top(), null));
    }

    /**
     * Abschnitt z ist ein Stück Gang: Mitte frei, Wände an den gemessenen Stellen (-l-1 und r+1, von den Füßen aus),
     * Decke in der gemessenen Höhe, Boden darunter.
     */
    private static boolean corridor(Look look, int c, int l, int r, int height, int z) {
        return free(look.at(new Pos(c, 0, z))) && !free(look.at(new Pos(-l - 1, 0, z)))
                && !free(look.at(new Pos(r + 1, 0, z))) && ceilingAt(look, c, 0, z) == height
                && solid(look.at(new Pos(c, -1, z)));
    }

    /** Freie Höhe über (x, 0, z) bis zur Decke, Laternen und Ketten zählen als frei; 0 = keine Decke in Reichweite. */
    private static int ceiling(Look look, int x, int z) {
        return ceilingAt(look, x, 0, z);
    }

    private static int ceilingAt(Look look, int x, int y0, int z) {
        for (int y = y0; y < y0 + MAX_HEIGHT + 1; y++) {
            BlockSpec s = look.at(new Pos(x, y, z));
            if (!free(s) && !TunnelStyle.HANGING_LIGHTS.contains(s.id()) && !TunnelStyle.CHAINS.contains(s.id())) {
                return y - y0;
            }
        }
        return 0;
    }

    // ---------------------------------------------------------------- Brücke

    private static Optional<Found> bridge(Look look) {
        if (!solid(look.at(new Pos(0, -1, 0)))) {
            return Optional.empty();
        }
        int l = 0;
        while (l <= MAX_HALF && free(look.at(new Pos(-l - 1, 0, 0)))) {
            l++;
        }
        int r = 0;
        while (r <= MAX_HALF && free(look.at(new Pos(r + 1, 0, 0)))) {
            r++;
        }
        if (l > MAX_HALF || r > MAX_HALF) {
            return Optional.empty();  // freies Feld oder quer geschaut
        }
        int left = -l - 1;
        int right = r + 1;
        if (!solid(look.at(new Pos(left, -1, 0))) || !solid(look.at(new Pos(right, -1, 0)))) {
            return Optional.empty();  // Geländer ohne Deck darunter: keine Brücke
        }
        int c = Math.floorDiv(r - l, 2);
        int half = (l + r + 1) / 2;
        int e = 0;
        // Deck nur Festes: Liegt es auf Höhe der Wasseroberfläche, galt sonst das Wasser hinter dem Ende als Deck
        // (Paper-Probe 2026-11-16: Ende am Beckenrand 28 Blöcke weiter, dort setzte „bau weiter“ neu an).
        while (e < MAX_WALK && solid(look.at(new Pos(c, -1, e + 1))) && free(look.at(new Pos(c, 0, e + 1)))) {
            e++;
        }
        if (e >= MAX_WALK) {
            return Optional.empty();
        }
        Tally decks = new Tally();
        Tally railings = new Tally();
        Tally lights = new Tally();
        int lastLight = Integer.MIN_VALUE;
        for (int z = Math.max(0, e - SAMPLE); z <= e; z++) {
            for (int x = left + 1; x < right; x++) {
                decks.add(look.at(new Pos(x, -1, z)).id());
            }
            for (int side : new int[] {left, right}) {
                BlockSpec rail = look.at(new Pos(side, 0, z));
                if (TunnelStyle.BLOCK_LIGHTS.contains(rail.id())) {
                    lights.add(rail.id());
                    lastLight = z;
                } else {
                    railings.add(rail.id());
                }
                BlockSpec on = look.at(new Pos(side, 1, z));
                if (TunnelStyle.HANGING_LIGHTS.contains(on.id())) {
                    lights.add(on.id());
                    lastLight = z;
                }
            }
        }
        // Pfeiler wie das Deck: Was unter der Deckkante steht, ist über Land Erdreich – das zu zählen hieße, künftig
        // Pfeiler aus Erde oder Stein zu setzen.
        String deck = decks.wallBlockOr(decks.top());
        return Optional.of(new Found(TunnelOrder.Kind.BRIDGE, half, 3, new Pos(c, 0, e), anchorFrom(lastLight, e),
                deck, deck, null, null, null, lights.top(), null, railings.top()));
    }

    // ---------------------------------------------------------------- gemeinsam

    /** Lichtanker relativ zum Ende: das letzte Licht lag bei z, also (k - anchor) % 6 == LIGHT_AT an z - e. */
    private static int anchorFrom(int lastLight, int end) {
        return lastLight == Integer.MIN_VALUE ? TunnelShape.NO_ANCHOR : lastLight - end - TunnelShape.LIGHT_AT;
    }

    /** Trägt: weder durchgehbar noch Wasser oder Lava – Boden eines Gangs, Deck einer Brücke. */
    static boolean solid(BlockSpec s) {
        String id = s.id();
        return !free(s) && !id.equals("water") && !id.equals("lava") && !id.equals("bubble_column");
    }

    /** Durchgehbar: Luft, kleine Pflanzen, Teppiche, Fackeln, Schienen. */
    static boolean free(BlockSpec s) {
        String id = s.id();
        return s.isAir() || id.equals("cave_air") || id.equals("void_air") || PASSABLE.contains(id)
                || id.endsWith("_carpet") || id.endsWith("torch") || id.endsWith("rail");
    }

    /** Häufigkeiten je ID; top = die häufigste, bei Gleichstand die kleinere ID (stabil). */
    private static final class Tally {
        final Map<String, Integer> counts = new HashMap<>();

        void add(String id) {
            counts.merge(id, 1, Integer::sum);
        }

        String top() {
            return best(id -> true, 1);
        }

        /** Häufigster Mauerstein (TunnelStyle.isWallBlock), sonst null. */
        String wallBlock() {
            return best(TunnelStyle::isWallBlock, 1);
        }

        String wallBlockOr(String fallback) {
            String w = wallBlock();
            return w != null ? w : fallback;
        }

        /** Zweiter Mauerstein mit mindestens 3 Vorkommen – im Tunnel der Gurtbogen, sonst null. */
        String second() {
            String first = wallBlock();
            return best(id -> TunnelStyle.isWallBlock(id) && !id.equals(first), 3);
        }

        private String best(java.util.function.Predicate<String> fits, int min) {
            String best = null;
            int n = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                int v = e.getValue();
                if (v < min || !fits.test(e.getKey())) {
                    continue;
                }
                if (v > n || v == n && e.getKey().compareTo(best) < 0) {
                    best = e.getKey();
                    n = v;
                }
            }
            return best;
        }
    }
}
