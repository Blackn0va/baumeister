package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
 * der neue Teil genauso weiter. Schaut der Spieler quer zum Gang, misst er die Länge als Breite und erkennt nichts.
 *
 * Gerade weiter (Wunsch 2026-11-16, „ein Tunnel muss gerade gehen – er wird irgendwann durch ca. die komplette Karte
 * gehen“): {@link #around} sucht in allen vier Richtungen, damit „bau weiter“ dem Gang folgt und nicht dem Blick.
 * Vorher galt nur die Blickrichtung – wer beim Sagen zur Seite schaute, bekam „nichts erkannt“ und einen neuen Tunnel
 * quer zum alten. Ein Gang, der ins Freie mündet (alter Durchbruch, Ende über einem Tal), hat dort ein offenes Ende
 * (Found.open): Weiterbauen setzt die Röhre über das Tal an. Eine Öffnung in eine Höhle oder einen Raum bleibt kein
 * Ende – dort stünde die Röhre in fremdem Hohlraum.
 *
 * Threads: zustandslos. Fehler: keine – nichts erkannt heißt leer.
 */
public final class TunnelScan {

    /** Block an einer Stelle, Zustände lokal (facing=back heißt: in Blickrichtung). */
    public interface Look {
        BlockSpec at(Pos local);

        /** Über der Oberfläche (wie TunnelShape.Site.sky)? Ohne dieses Wissen nie – dann gibt es kein offenes Ende. */
        default boolean sky(Pos local) {
            return false;
        }
    }

    /**
     * @param end    Füße auf dem letzten fertigen Abschnitt, mittig – dort beginnt das Weiterbauen mit Abschnitt 1
     * @param anchor Lichtanker relativ zu end (TunnelShape.Spec.anchor), {@link TunnelShape#NO_ANCHOR} ohne Licht
     * @param open   der Tunnel mündet dort ins Freie (kein Fels davor) – weiter geht es als Röhre
     * @param trim   Sockel (unterste Wandreihe), wenn er sich von der Wand abhebt, sonst null
     * @param runner Mittelstreifen im Boden bzw. Deck, wenn er sich abhebt, sonst null
     */
    public record Found(TunnelOrder.Kind kind, int half, int height, Pos end, int anchor, String wall, String floor,
            String rib, String arch, String step, String light, String chain, String railing, boolean open,
            String trim, String runner) {
    }

    /** Erkanntes und die Himmelsrichtung, in der es weitergeht. */
    public record Directed(Cardinal dir, Found found) {
    }

    /**
     * Ein Gang, aus dem eine Treppe hinaufführt ({@link #room}), lokal zur Treppe.
     *
     * @param inside Innenraum des Gangs (frei oder eigene Deko) – dort baut die Treppe weder Wand noch Front noch Deko
     * @param own    Stellen mit Steinen des Gangs (Wand, Decke, Boden, Bogen, Licht …) – die darf sie abbauen
     * @param ids    die Steine des Gangs
     */
    public record Room(Set<Pos> inside, Set<Pos> own, Set<String> ids) {

        /** Dieselben Stellen um o verschoben – lokal zum Bau statt zu den Füßen im Gang ({@link Crossing#feet}). */
        public Room shifted(Pos o) {
            Set<Pos> in = new HashSet<>();
            for (Pos p : inside) {
                in.add(p.add(o.x(), o.y(), o.z()));
            }
            Set<Pos> mine = new HashSet<>();
            for (Pos p : own) {
                mine.add(p.add(o.x(), o.y(), o.z()));
            }
            return new Room(Set.copyOf(in), Set.copyOf(mine), ids);
        }
    }

    /**
     * Ein Gang im Weg eines Baus ({@link #crossing}).
     *
     * @param feet   Füße in diesem Gang, lokal zum Bau
     * @param tunnel der Gang dort ({@link #passage}), Richtung in Weltkoordinaten
     */
    public record Crossing(Pos feet, Directed tunnel) {
    }

    /** So weit sucht er nach vorn das Ende. */
    public static final int MAX_WALK = 128;
    /** Breiter als 2 × 3 + 1 ist kein Gang mehr (oder der Spieler schaut quer). */
    static final int MAX_HALF = 3;
    static final int MAX_HEIGHT = 6;
    /** Aus so vielen Abschnitten vor dem Ende kommen Steine und Lichter. */
    static final int SAMPLE = 12;
    /** So viele Abschnitte hinter der Stelle, an der ein Bau an Gebautes stößt, sucht {@link #crossing} einen Gang. */
    public static final int CROSSING_AHEAD = 6;

    private static final Set<String> PASSABLE = Set.of("short_grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "snow", "glow_lichen", "vine", "moss_carpet", "redstone_wire", "tripwire", "ladder");

    private TunnelScan() {
    }

    /**
     * In allen vier Richtungen suchen, die Blickrichtung zuerst: Endet der Gang dort am Fels, gilt sie. Sonst das
     * nächste Ende am Fels in einer anderen Richtung, und erst dann ein offenes Ende (Blickrichtung zuerst) – ein Gang
     * hat meist einen offenen Eingang hinter dem Spieler, und dort weiterzubauen hieße, die Röhre aus dem Eingang
     * heraus in die Wiese zu setzen.
     *
     * @param looks Sicht für eine Blickrichtung (lokal wie {@link #scan})
     */
    public static Optional<Directed> around(Cardinal facing, Function<Cardinal, Look> looks) {
        Directed face = null;
        Directed open = null;
        for (Cardinal d : List.of(facing, facing.clockwise(), facing.clockwise().opposite(), facing.opposite())) {
            Optional<Found> f = scan(looks.apply(d));
            if (f.isEmpty()) {
                continue;
            }
            Directed hit = new Directed(d, f.get());
            if (!hit.found().open()) {
                if (d == facing) {
                    return Optional.of(hit);
                }
                if (face == null || hit.found().end().z() < face.found().end().z()) {
                    face = hit;
                }
            } else if (open == null) {
                open = hit;
            }
        }
        return Optional.ofNullable(face != null ? face : open);
    }

    /**
     * Treppe aus einem Tunnel heraus (Nutzer 2026-11-16, „wie mache ich die Treppe nach oben?“): Vorher hielt eine
     * Treppe, die im Gang begann, sofort an dessen eigener Laterne bzw. dessen Bogen (gemessen: 0 Stufen, „Gebautes“).
     * Hier stehen Innenraum und Steine des Gangs, damit die Treppe dessen Hülle durchbrechen darf und keine eigenen
     * Wände quer in den Gang setzt (sonst wäre er an der Treppe zu).
     *
     * @param hit    der Gang, in dem der Spieler steht ({@link #around})
     * @param stairs Blickrichtung der Treppe – die Stellen kommen lokal zu ihr heraus (Ursprung wie hit: die Füße)
     * @param tunnel Sicht in Richtung hit.dir()
     * @param reach  so viele Abschnitte den Gang entlang, vor und hinter dem Spieler
     */
    public static Room room(Directed hit, Cardinal stairs, Look tunnel, int reach) {
        Found f = hit.found();
        Orientation along = new Orientation(hit.dir());
        Orientation up = new Orientation(stairs);
        Set<String> ids = new HashSet<>();
        for (String id : new String[] {f.wall(), f.floor(), f.rib(), f.arch(), f.light(), f.chain(), f.trim(),
            f.runner(), TunnelStyle.WINDOW}) {
            if (id != null) {
                ids.add(id);
            }
        }
        int c = f.end().x();
        Set<Pos> inside = new HashSet<>();
        Set<Pos> own = new HashSet<>();
        for (int z = -reach; z <= reach; z++) {
            for (int y = -1; y <= f.height(); y++) {
                for (int x = c - f.half() - 1; x <= c + f.half() + 1; x++) {
                    Pos p = new Pos(x, y, z);
                    BlockSpec s = tunnel.at(p);
                    Pos local = up.toLocalOffset(along.toWorldOffset(p));
                    boolean mine = ids.contains(s.id());
                    if (y >= 0 && y < f.height() && Math.abs(x - c) <= f.half() && (free(s) || mine)) {
                        inside.add(local);
                    }
                    if (mine) {
                        own.add(local);
                    }
                }
            }
        }
        return new Room(Set.copyOf(inside), Set.copyOf(own), Set.copyOf(ids));
    }

    public static Optional<Found> scan(Look look) {
        if (!free(look.at(new Pos(0, 0, 0))) || !free(look.at(new Pos(0, 1, 0)))) {
            return Optional.empty();
        }
        Optional<Found> stairs = stairs(look, false);
        if (stairs.isPresent()) {
            return stairs;
        }
        // Treppe nach unten (Wunsch 2026-11-16): auf einer Stufe, die nach vorn fällt.
        stairs = stairs(look, true);
        if (stairs.isPresent()) {
            return stairs;
        }
        if (ceiling(look, 0, 0) > 0) {
            return tunnel(look);
        }
        return bridge(look);
    }

    // ---------------------------------------------------------------- Treppe

    /**
     * Treppe, auf deren Stufe der Spieler steht. down: nach vorn fallend (Stufen mit facing=front) – je Abschnitt einen
     * tiefer statt höher; sonst alles wie hinauf.
     */
    private static Optional<Found> stairs(Look look, boolean down) {
        int s = down ? -1 : 1;
        java.util.function.Predicate<BlockSpec> step = down ? TunnelScan::descending : TunnelScan::climbing;
        if (!step.test(look.at(new Pos(0, -1, 0))) || !step.test(look.at(new Pos(0, -1 - s, -1)))
                && !step.test(look.at(new Pos(0, -1 + s, 1)))) {
            return Optional.empty();
        }
        int l = 0;
        while (l < MAX_HALF && step.test(look.at(new Pos(-l - 1, -1, 0)))) {
            l++;
        }
        int r = 0;
        while (r < MAX_HALF && step.test(look.at(new Pos(r + 1, -1, 0)))) {
            r++;
        }
        int c = Math.floorDiv(r - l, 2);
        int half = (l + r + 1) / 2;
        // Bis zur letzten Stufe: jede nächste einen höher (bzw. tiefer) und einen weiter.
        int e = 0;
        while (e < MAX_WALK && step.test(look.at(new Pos(c, -1 + s * (e + 1), e + 1)))) {
            e++;
        }
        // Höhe dort, wo der Spieler steht: Am Ende liegt eine fertige Treppe schon im Freien (keine Decke mehr).
        int measured = ceilingAt(look, c, 0, 0);
        int height = measured == 0 ? TunnelOrder.Size.NORMAL.height : Math.max(2, Math.min(MAX_HEIGHT, measured));
        Pos end = new Pos(c, s * e, e);
        Tally walls = new Tally();
        Tally trims = new Tally();
        Tally steps = new Tally();
        Tally lights = new Tally();
        int lastLight = Integer.MIN_VALUE;
        for (int z = Math.max(0, e - SAMPLE); z <= e; z++) {
            steps.add(look.at(new Pos(c, s * z - 1, z)).id());
            trims.add(look.at(new Pos(-l - 1, s * z - 1, z)).id());
            trims.add(look.at(new Pos(r + 1, s * z - 1, z)).id());
            for (int y = s * z; y < s * z + height; y++) {
                walls.add(look.at(new Pos(-l - 1, y, z)).id());
                walls.add(look.at(new Pos(r + 1, y, z)).id());
            }
            BlockSpec top = look.at(new Pos(c, s * z + height - 1, z));
            if (TunnelStyle.HANGING_LIGHTS.contains(top.id())) {
                lights.add(top.id());
                lastLight = z;
            }
        }
        String wall = walls.wallBlock();
        return Optional.of(new Found(down ? TunnelOrder.Kind.STAIRS_DOWN : TunnelOrder.Kind.STAIRS_UP, half, height,
                end, anchorFrom(lastLight, e), wall, steps.top(), walls.second(), null, steps.top(), lights.top(), null,
                null, false, apart(trims.wallBlock(), wall), null));
    }

    private static boolean climbing(BlockSpec s) {
        return s.isStairs() && "back".equals(s.props().get("facing")) && !"top".equals(s.props().get("half"));
    }

    /** Stufe einer Treppe nach unten: die volle Seite zum Spieler (front), also nach vorn fallend. */
    private static boolean descending(BlockSpec s) {
        return s.isStairs() && "front".equals(s.props().get("facing")) && !"top".equals(s.props().get("half"));
    }

    // ---------------------------------------------------------------- Tunnel

    /** Querschnitt an den Füßen: l/r freie Zellen links/rechts, c Mitte, halbe Breite, Höhe. */
    private record Section(int l, int r, int c, int half, int height) {
    }

    /** Querschnitt eines Gangs an den Füßen – null, wenn es keiner ist (Raum, quer geschaut, keine Decke). */
    private static Section section(Look look) {
        int l = 0;
        while (l <= MAX_HALF && free(look.at(new Pos(-l - 1, 0, 0)))) {
            l++;
        }
        int r = 0;
        while (r <= MAX_HALF && free(look.at(new Pos(r + 1, 0, 0)))) {
            r++;
        }
        if (l > MAX_HALF || r > MAX_HALF) {
            return null;  // Raum oder quer geschaut
        }
        int c = Math.floorDiv(r - l, 2);
        int height = ceiling(look, c, 0);
        if (height < 2 || height > MAX_HEIGHT || !corridor(look, c, l, r, height, -1)
                && !corridor(look, c, l, r, height, 1)) {
            return null;
        }
        return new Section(l, r, c, (l + r + 1) / 2, height);
    }

    private static Optional<Found> tunnel(Look look) {
        Section s = section(look);
        if (s == null) {
            return Optional.empty();
        }
        int l = s.l();
        int r = s.r();
        int c = s.c();
        int height = s.height();
        // Wie weit der Gang hinter dem Spieler reicht – ein Raum ist kein Gang (siehe unten).
        int behind = 0;
        while (behind < 2 * MAX_WALK && corridor(look, c, l, r, height, -behind - 1)) {
            behind++;
        }
        // Nach vorn, solange es ein Gang ist; am Fels ist das Ende. Mündet er ins Freie (Decke dort über der
        // Oberfläche), ist das ein offenes Ende; öffnet er sich in eine Höhle oder einen Raum, gibt es keins.
        int e = 0;
        boolean open = false;
        while (true) {
            if (e >= MAX_WALK) {
                return Optional.empty();
            }
            Pos next = new Pos(c, 0, e + 1);
            if (!free(look.at(next)) && !free(look.at(next.up()))) {
                break;
            }
            if (!corridor(look, c, l, r, height, e + 1)) {
                if (look.sky(new Pos(c, height, e + 1))) {
                    open = true;
                    break;
                }
                return Optional.empty();
            }
            e++;
        }
        // Mindestens doppelt so lang wie breit (und 4 Blöcke): Ein Keller von 7 × 7 hätte sonst als Tunnel von 7
        // Blöcken Breite gegolten, und „bau weiter“ hätte ihn als Riesentunnel in den Fels verlängert.
        if (behind + 1 + e < Math.max(4, 2 * (l + r + 1))) {
            return Optional.empty();
        }
        // Die letzten SAMPLE Abschnitte auch hinter dem Spieler: Vorher nur ab seinen Füßen – stand er direkt am Ende
        // und war der letzte Abschnitt ein Gurtbogen, gewann dessen Stein (Probe 2026-11-16, Spigot: Fortsetzung aus
        // poliertem Tiefenschiefer statt Tiefenschieferziegeln). Wer einen Tunnel quer über die Karte immer wieder
        // fortsetzt, steht genau dort.
        return Optional.of(stones(look, s, Math.max(-behind, e - SAMPLE), e, new Pos(c, 0, e), open));
    }

    /** Steine, Bogen und Lichter eines Gangs mit Querschnitt s aus den Abschnitten from..to. */
    private static Found stones(Look look, Section s, int from, int to, Pos end, boolean open) {
        int l = s.l();
        int r = s.r();
        int c = s.c();
        int height = s.height();
        Tally walls = new Tally();
        Tally trims = new Tally();
        Tally floors = new Tally();
        Tally runners = new Tally();
        Tally arches = new Tally();
        Tally lights = new Tally();
        Tally chains = new Tally();
        int lastLight = Integer.MIN_VALUE;
        for (int z = from; z <= to; z++) {
            // Unterste Wandreihe für sich (Sockel, „edel“ seit 2026-11-16): Mitgezählt hätte sie als zweithäufigster
            // Stein den Gurtbogen verdrängt – das nächste Stück bekäme Ringe aus Sockelstein.
            for (int y = 0; y < height; y++) {
                Tally row = y == 0 ? trims : walls;
                row.add(look.at(new Pos(-l - 1, y, z)).id());
                row.add(look.at(new Pos(r + 1, y, z)).id());
            }
            for (int x = -l; x <= r; x++) {
                (x == c && l + r >= 2 ? runners : floors).add(look.at(new Pos(x, -1, z)).id());
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
        String wall = walls.wallBlock();
        String floor = floors.wallBlockOr(wall);
        return new Found(TunnelOrder.Kind.TUNNEL, s.half(), height, end, anchorFrom(lastLight, end.z()), wall, floor,
                walls.second(), arches.top(), null, lights.top(), chains.top(), null, open,
                apart(trims.wallBlock(), wall), apart(runners.wallBlock(), floor));
    }

    /**
     * Gang an den Füßen, ohne sein Ende zu suchen – für eine Treppe aus ihm heraus ({@link #room}) und für einen Bau,
     * der ihn kreuzt ({@link #crossing}). {@link #around} braucht ein Ende höchstens {@link #MAX_WALK} Blöcke vor dem
     * Spieler: Mitten in einem Tunnel quer über die Karte fand es keins, und die Treppe nach oben hielt dort sofort an
     * der Decke des Gangs („Gebautes“) – genau in dem Tunnel, aus dem der Nutzer am 2026-11-16 hinauswollte. Steine
     * kommen aus den Abschnitten rund um die Füße; Found.end ist die Mitte des Gangs an den Füßen.
     */
    public static Optional<Directed> passage(Cardinal facing, Function<Cardinal, Look> looks) {
        for (Cardinal d : List.of(facing, facing.clockwise(), facing.clockwise().opposite(), facing.opposite())) {
            Look look = looks.apply(d);
            if (!free(look.at(new Pos(0, 0, 0))) || !free(look.at(new Pos(0, 1, 0)))) {
                return Optional.empty();
            }
            Section s = section(look);
            if (s == null) {
                continue;
            }
            int behind = 0;
            while (behind < SAMPLE && corridor(look, s.c(), s.l(), s.r(), s.height(), -behind - 1)) {
                behind++;
            }
            int ahead = 0;
            while (ahead < SAMPLE && corridor(look, s.c(), s.l(), s.r(), s.height(), ahead + 1)) {
                ahead++;
            }
            if (behind + 1 + ahead >= Math.max(4, 2 * (s.l() + s.r() + 1))) {
                return Optional.of(new Directed(d, stones(look, s, -behind, ahead, new Pos(s.c(), 0, 0), false)));
            }
        }
        return Optional.empty();
    }

    /**
     * Ein Gang dort, wo ein Bau an Gebautes stößt (Wunsch 2026-11-16, „trotzdem“): ab Abschnitt k nach vorn, je
     * Abschnitt in der Mitte von oben nach unten, Füße mit Boden darunter und einem Gang drumherum. Ohne diese Prüfung
     * setzte ein Tunnel mit „trotzdem“ Wände und Front quer in den Innenraum des anderen Gangs – der wäre an der
     * Stelle zu –, und eine Treppe nach unten bräche durch seinen Boden.
     *
     * @param build     Sicht des Baus (lokal zu ihm, (0,0,0) = Füße am Start)
     * @param kind      Art des Baus – sie bestimmt die Höhe je Abschnitt (TunnelShape.base)
     * @param passageAt Gang an Füßen, lokal zum Bau ({@link #passage} an der Stelle in der Welt)
     */
    public static Optional<Crossing> crossing(Look build, TunnelOrder.Kind kind, int k,
            Function<Pos, Optional<Directed>> passageAt) {
        for (int j = Math.max(1, k); j <= k + CROSSING_AHEAD; j++) {
            int base = TunnelShape.base(kind, j);
            for (int y = base + MAX_HEIGHT; y >= base - MAX_HEIGHT; y--) {
                Pos feet = new Pos(0, y, j);
                if (free(build.at(feet)) && free(build.at(feet.up())) && solid(build.at(feet.down()))) {
                    Optional<Directed> d = passageAt.apply(feet);
                    if (d.isPresent()) {
                        return Optional.of(new Crossing(feet, d.get()));
                    }
                }
            }
        }
        return Optional.empty();
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
        Tally runners = new Tally();
        Tally railings = new Tally();
        Tally posts = new Tally();
        Tally lights = new Tally();
        int lastLight = Integer.MIN_VALUE;
        for (int z = Math.max(0, e - SAMPLE); z <= e; z++) {
            for (int x = left + 1; x < right; x++) {
                (x == c && right - left - 1 >= 3 ? runners : decks).add(look.at(new Pos(x, -1, z)).id());
            }
            for (int side : new int[] {left, right}) {
                BlockSpec rail = look.at(new Pos(side, 0, z));
                if (TunnelStyle.BLOCK_LIGHTS.contains(rail.id())) {
                    lights.add(rail.id());
                    lastLight = z;
                } else if (TunnelStyle.isWallBlock(rail.id())) {
                    posts.add(rail.id());  // Pfosten der Balustrade (voller Stein statt Mauer)
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
                deck, deck, posts.wallBlock(), null, null, lights.top(), null,
                railings.top() != null ? railings.top() : posts.wallBlock(), false, null,
                apart(runners.wallBlock(), deck)));
    }

    /** a, wenn es sich von b abhebt (Sockel gegen Wand, Streifen gegen Boden), sonst null. */
    private static String apart(String a, String b) {
        return a != null && !a.equals(b) ? a : null;
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
