package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tunnel, Treppe und Brücke Block für Block, in der Reihenfolge, in der man gräbt bzw. baut (Wunsch 2026-11-16). Lokal
 * wie SiteFrame mit Abstand 0: (0,0,0) sind die Füße des Spielers, x nach rechts, z in Baurichtung, y nach oben;
 * Abschnitt k liegt bei z = k.
 *
 * Tunnel und Treppe je Abschnitt: erst die Hülle (Decke, Wände, Boden bzw. Stufe) – sie ersetzt Kies, Wasser und Lava,
 * bevor innen etwas offen ist; dann die Front des nächsten Abschnitts zu, wo dort Luft oder Flüssigkeit steht (sonst
 * liefe eine Höhle mit Lava von vorn hinein); dann innen ausräumen von oben nach unten; zuletzt Bogen, Kette und
 * Laterne (hängend erst, wenn die Decke steht). Die Front räumt der nächste Abschnitt wieder aus, nach dem letzten
 * bleibt sie als Abschluss. Wer die Reihenfolge umdreht (innen zuerst), lässt Lava, Wasser und Kies in den Gang.
 *
 * Brücke je Abschnitt: an jedem zweiten Lichtabschnitt Pfeiler unter beiden Geländerkanten bis zum Grund (auch im Meer,
 * höchstens {@link #PILLAR_MAX} tief, von unten nach oben) samt Querbalken, dann das Deck auf der Höhe, auf der der
 * Spieler steht, den Gehweg frei (ein Hügel bis zwei Blöcke hoch wird zum Hohlweg), das Geländer und darauf die
 * Laternen. Steht Wasser über dem Deck oder ein Berg im Weg, endet die Brücke davor.
 *
 * Jede Aktion trägt ihren Abschnitt als step – daran hängen Standplatz und Mitbauen im BuildJob. start/anchor erlauben
 * das Weiterbauen (nächster Abschnitt nach einem fertigen Stück, Lichtabstand läuft weiter).
 *
 * Was im Boden steckt, liest {@link Site}: Gebautes oder Grundgestein innen (wegräumen hieße abreißen) beendet den Bau
 * vor diesem Abschnitt; in der Hülle bleibt es stehen und dient als Wand. Ein neuer Tunnel beginnt beim ersten
 * Abschnitt, in dem es etwas zu graben gibt (Keller, Höhle oder Wiese davor bleiben unberührt). Eine Treppe läuft je
 * Abschnitt einen Block höher, bis der Spieler auf ihr über der Oberfläche steht. Die Treppe nach unten (Wunsch
 * 2026-11-16, „die Treppe muss auch von oben nach unten gebaut werden können“) läuft je Abschnitt einen tiefer, bis zur
 * Stufenzahl, zur Höhe (Spec.depth) oder bis aufs Grundgestein. Höhlen unterwegs dichtet sie ab wie der Tunnel. Ein
 * erster Entwurf hielt vor jedem Hohlraum an (gerechnet am Abstieg in einen Gang): Die letzte Stufe lag dann über
 * dessen Decke, oder der Spieler fiel vier Blöcke hinein und kam nicht mehr hinauf. Quer durch einen Gang geht sie nie
 * (TunnelService, TunnelScan.crossing) – hinein führt eine Treppe nach oben aus dem Gang.
 *
 * Röhre (Wunsch 2026-11-16, „ein Tunnel muss gerade gehen – er wird irgendwann durch ca. die komplette Karte gehen“):
 * Bricht der Tunnel ins Freie (Tal, Schlucht) oder läuft er in einen See oder ins Meer, geht er auf derselben Höhe
 * geradeaus weiter – als geschlossene Röhre mit derselben Hülle und demselben Abschluss wie im Fels, in Augenhöhe mit
 * Fenstern, an jedem zweiten Lichtabschnitt auf Pfeilern bis zum Grund. Vorher endete er dort (THROUGH, WATER): Ein
 * Tunnel quer über die Karte hätte an jedem Tal neu angesetzt werden müssen, und „bau weiter“ fand an einem offenen
 * Ende nichts zum Weitergraben. Wer nur durch den Berg will („bis ins Freie“, Spec.daylight), bekommt das alte Ende.
 * Wer die Röhre zurückdreht, lässt jeden Tunnel quer über die Karte am ersten Tal enden.
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class TunnelShape {

    /** Alle so viele Abschnitte ein Gurtbogen mit Licht – bei 6 ist die dunkelste Stelle noch Lichtstufe 12. */
    public static final int LIGHT_EVERY = 6;
    /** Der erste Gurtbogen mit Licht sitzt im dritten Abschnitt, damit der Eingang nicht dunkel bleibt. */
    static final int LIGHT_AT = 2;
    /** Brückenpfeiler an jedem zweiten Lichtabschnitt – unter den Laternen. */
    static final int PILLAR_EVERY = 2 * LIGHT_EVERY;
    static final int PILLAR_AT = LIGHT_AT + LIGHT_EVERY;
    /** Spec.anchor ohne Vorgabe: Licht und Pfeiler zählen ab dem ersten gebauten Abschnitt. */
    public static final int NO_ANCHOR = Integer.MIN_VALUE;
    /** Geländerpfosten der Brücke alle so viele Abschnitte (Balustrade). */
    static final int POST_EVERY = 3;
    /** Tiefer als so viele Blöcke unter dem Deck bekommt eine Stelle keinen Pfeiler (tiefer Ozean, Schlucht). */
    public static final int PILLAR_MAX = 48;

    /** Was an einer Stelle steht – liest TunnelService aus der Welt. */
    public enum Ground {
        /** Gestein, Erde, Erz, Kies, Eis … – darf weg. */
        SOLID,
        /** Luft, Pflanzen, Flechten, Schneedecke – nichts zu graben. */
        OPEN,
        /** Wasser, Lava, Seegras – innen weg, in Hülle und Front zu. */
        FLUID,
        /** Kein Gelände: Bauwerk eines Spielers, Mineschacht, Dungeon, Baumstamm … */
        BUILT,
        /** Grundgestein, Weltrand, Portalrahmen … – nie entfernen. */
        FORBIDDEN
    }

    /** Sicht in den Boden, lokal. sky = über der Oberfläche (über dem obersten festen Block der Säule). */
    public interface Site {
        Ground at(Pos local);

        boolean sky(Pos local);

        /** Block-ID für Ansagen („Eichenbretter bei …“). */
        String id(Pos local);

        /**
         * Innenraum eines vorhandenen Gangs, aus dem eine Treppe steigt (TunnelScan.room): dort keine Hülle, keine
         * Front, keine Deko – sonst setzte die Treppe Wände quer in den Gang. Ohne Gang: nie.
         */
        default boolean room(Pos local) {
            return false;
        }
    }

    public enum Stop {
        /** Gewünschte Länge erreicht. */
        LENGTH,
        /** Innen (Brücke: auf dem Gehweg oder im Deck) steht etwas Gebautes. */
        BUILT,
        /** Innen Grundgestein oder Weltrand. */
        FORBIDDEN,
        /** Der Tunnel bricht ins Freie durch – nur „bis ins Freie“ (Spec.daylight), sonst geht er als Röhre weiter. */
        THROUGH,
        /**
         * Über der Oberfläche steht Wasser oder Lava (See, Meer): Treppe ohne Ausgang, Tunnel „bis ins Freie“;
         * Brücke: Wasser über dem Deck.
         */
        WATER,
        /** Brücke: ein Berg im Weg (fester Boden auf Kopfhöhe). */
        HILL,
        /** Treppe: oben angekommen. */
        SURFACE,
        /** Treppe: in der erlaubten Länge keine Oberfläche. */
        NO_SURFACE,
        /** Treppe: der Spieler steht schon oben. */
        ALREADY_UP,
        /** Treppe: die gewünschte Höhe ist erreicht (Spec.depth). */
        DEPTH
    }

    /**
     * Was gebaut werden soll.
     *
     * @param half      halbe Innenbreite (Gehweg), x von -half bis half
     * @param height    Innenhöhe bzw. lichte Höhe über dem Deck
     * @param length    so viele Abschnitte bauen (Tunnel, Brücke) bzw. höchstens so viele Stufen (0 = bis oben)
     * @param scanLimit bis zu diesem Abschnitt (z) höchstens – Grenze der Ausdehnung
     * @param start     erster Abschnitt (Weiterbauen: der nach dem letzten fertigen)
     * @param anchor    Abschnitt, ab dem Licht und Pfeiler zählen (darf negativ sein: Licht im fertigen Stück davor);
     *                  {@link #NO_ANCHOR} = der erste gebaute
     * @param entrance  neuer Bau: Eingang als Gurtbogen, Keller und Wiese davor überspringen. Beim Weiterbauen nie –
     *                  sonst bliebe eine Höhle direkt hinter dem fertigen Stück ohne Hülle.
     * @param daylight  Tunnel endet, wo er ins Freie bricht oder Wasser von oben käme („durch den Berg“, „bis ins
     *                  Freie“) – sonst läuft er dort als Röhre weiter
     * @param depth     Treppe: lokale Fußhöhe, bei der sie endet („bis Höhe -59“), {@link #NO_DEPTH} ohne
     */
    public record Spec(TunnelOrder.Kind kind, int half, int height, int length, int scanLimit, int start, int anchor,
            boolean entrance, TunnelStyle style, boolean daylight, int depth) {

        public static Spec of(TunnelOrder.Kind kind, TunnelOrder.Size size, int length, int scanLimit,
                TunnelStyle style) {
            return new Spec(kind, size.half, size.height, length, scanLimit, 0, NO_ANCHOR, true, style, false,
                    NO_DEPTH);
        }
    }

    /** Spec.depth ohne Zielhöhe. */
    public static final int NO_DEPTH = Integer.MIN_VALUE;

    /**
     * @param first    erster gebauter Abschnitt (-1 = keiner)
     * @param last     letzter gebauter Abschnitt
     * @param stopAt   wo es aufhörte (lokal), null bei LENGTH
     * @param blocks   höchstens so viele Blöcke je ID (ohne Luft) – was schon dasteht, kostet beim Bau nichts
     * @param openFrom erster Abschnitt, der als Röhre im Freien liegt (Tal, See), -1 = keiner – für die Ansage
     */
    public record Result(CompiledBuild build, Spec spec, int first, int last, Stop stop, Pos stopAt, String stopId,
            int lights, Map<String, Integer> blocks, int openFrom) {

        public boolean empty() {
            return first < 0;
        }

        /** Gebaute Abschnitte (Länge des Tunnels bzw. Stufen samt Absatz). */
        public int slices() {
            return first < 0 ? 0 : last - first + 1;
        }
    }

    private TunnelShape() {
    }

    /**
     * Boden- bzw. Stufenhöhe des Abschnitts k: Tunnel und Brücke immer -1, Treppe ab k = 1 je Abschnitt höher, Treppe
     * nach unten je Abschnitt tiefer.
     */
    public static int base(TunnelOrder.Kind kind, int k) {
        if (k < 1) {
            return -1;
        }
        return kind == TunnelOrder.Kind.STAIRS_UP ? k - 1 : kind == TunnelOrder.Kind.STAIRS_DOWN ? -k - 1 : -1;
    }

    /** Wo der Arbeiter steht, während er Abschnitt k baut: mitten im Abschnitt davor, auf dessen Boden. */
    public static Pos stand(TunnelOrder.Kind kind, int k) {
        return new Pos(0, base(kind, k - 1) + 1, k - 1);
    }

    /** Kurzform für neue Bauten (Tests, Aufträge ohne Weiterbauen). */
    public static Result plan(TunnelOrder.Kind kind, TunnelOrder.Size size, int length, int scanLimit,
            TunnelStyle style, Site site) {
        return plan(Spec.of(kind, size, length, scanLimit, style), site);
    }

    public static Result plan(Spec spec, Site site) {
        Builder b = new Builder(spec, site);
        TunnelOrder.Kind kind = spec.kind();
        boolean up = kind == TunnelOrder.Kind.STAIRS_UP;
        boolean down = kind == TunnelOrder.Kind.STAIRS_DOWN;
        boolean stairs = up || down;
        boolean bridge = kind == TunnelOrder.Kind.BRIDGE;
        int maxSlices = stairs ? (spec.length() > 0 ? spec.length() + (spec.start() == 0 ? 1 : 0) : spec.scanLimit())
                : spec.length();
        if (up && spec.start() == 0 && site.sky(new Pos(0, 0, 0))) {
            return b.result(Stop.ALREADY_UP, new Pos(0, 0, 0));
        }
        for (int k = spec.start(); k < spec.scanLimit() && b.count() < maxSlices; k++) {
            int base = base(kind, k);
            List<Pos> inner = b.interior(k);
            if (bridge) {
                Stop stop = b.bridgeBlocked(k, inner);
                if (stop != null) {
                    return b.result(stop, b.blockedAt);
                }
                b.emitBridge(k);
                continue;
            }
            if (!stairs && spec.entrance() && b.first < 0 && b.allOpen(inner)) {
                continue;  // Keller, Höhle oder Wiese vor dem Fels: dort nichts bauen
            }
            boolean out = !stairs && site.sky(new Pos(0, base + b.h + 1, k));
            if (out) {
                if (b.first < 0 && spec.entrance()) {
                    continue;  // noch draußen vor dem Hang
                }
                if (!b.tube) {
                    return b.result(Stop.THROUGH, new Pos(0, base + 1, k));
                }
            }
            List<Pos> removed = new ArrayList<>(inner);
            if (stairs && k >= 1) {
                removed.addAll(b.stepCells(k));
            }
            for (Pos p : removed) {
                Ground g = site.at(p);
                if (g == Ground.BUILT || g == Ground.FORBIDDEN) {
                    return b.result(g == Ground.BUILT ? Stop.BUILT : Stop.FORBIDDEN, p);
                }
            }
            // Die Röhre schließt See und Meer genauso aus wie Wasser im Fels (Hülle und Front vor dem Ausräumen).
            for (Pos p : b.tube ? List.<Pos>of() : b.allCells(k)) {
                if (site.sky(p) && site.at(p) == Ground.FLUID) {
                    return b.result(Stop.WATER, p);
                }
            }
            if (out && b.openFrom < 0) {
                b.openFrom = k;
            }
            b.emit(k);
            if (up && site.sky(new Pos(0, base + 1, k))) {
                return b.result(Stop.SURFACE, null);
            }
            if (stairs && k >= 1 && spec.depth() != NO_DEPTH
                    && (up ? base + 1 >= spec.depth() : base + 1 <= spec.depth())) {
                return b.result(Stop.DEPTH, null);
            }
        }
        if (up && spec.length() == 0 && spec.depth() == NO_DEPTH) {
            return b.result(Stop.NO_SURFACE, null);
        }
        return b.result(Stop.LENGTH, null);
    }

    /** Sammelt die Aktionen; Abschnitt = step. */
    private static final class Builder {
        final Spec spec;
        final TunnelOrder.Kind kind;
        final int w;
        final int h;
        final TunnelStyle style;
        final Site site;
        /** Tunnel, der über der Oberfläche als Röhre weiterläuft (nicht bei „bis ins Freie“, nie bei der Treppe). */
        final boolean tube;
        final List<Action> actions = new ArrayList<>();
        final Map<String, Integer> blocks = new LinkedHashMap<>();
        int first = -1;
        int last = -1;
        int openFrom = -1;
        int lights;
        Pos min;
        Pos max;
        Pos blockedAt;

        Builder(Spec spec, Site site) {
            this.spec = spec;
            this.kind = spec.kind();
            this.w = spec.half();
            this.h = spec.height();
            this.style = spec.style();
            this.site = site;
            this.tube = kind == TunnelOrder.Kind.TUNNEL && !spec.daylight();
        }

        boolean stairs() {
            return kind.stairs();
        }

        int count() {
            return first < 0 ? 0 : last - first + 1;
        }

        List<Pos> interior(int k) {
            int base = base(kind, k);
            List<Pos> out = new ArrayList<>();
            for (int y = base + h; y > base; y--) {
                for (int x = -w; x <= w; x++) {
                    out.add(new Pos(x, y, k));
                }
            }
            return out;
        }

        List<Pos> stepCells(int k) {
            int base = base(kind, k);
            List<Pos> out = new ArrayList<>();
            for (int x = -w; x <= w; x++) {
                out.add(new Pos(x, base, k));
            }
            return out;
        }

        /** Jede Zelle, die Abschnitt k anfassen kann (für die Wasserprüfung über der Oberfläche). */
        List<Pos> allCells(int k) {
            int base = base(kind, k);
            List<Pos> out = new ArrayList<>();
            for (int y = base; y <= base + h + 1; y++) {
                for (int x = -w - 1; x <= w + 1; x++) {
                    out.add(new Pos(x, y, k));
                }
            }
            for (int y = base + 1; y <= base + h; y++) {
                for (int x = -w; x <= w; x++) {
                    out.add(new Pos(x, y, k + 1));
                }
            }
            return out;
        }

        boolean allOpen(List<Pos> cells) {
            for (Pos p : cells) {
                if (site.at(p) != Ground.OPEN) {
                    return false;
                }
            }
            return true;
        }

        /** Relativ zum Anker – beim Weiterbauen läuft der Abstand der Lichter über die Stücke hinweg. */
        private int rel(int k) {
            int a = spec.anchor() != NO_ANCHOR ? spec.anchor() : first < 0 ? k : first;
            return k - a;
        }

        boolean lightSlice(int k) {
            return Math.floorMod(rel(k), LIGHT_EVERY) == LIGHT_AT;
        }

        boolean ribSlice(int k) {
            return spec.entrance() && (first < 0 || first == k) || lightSlice(k);
        }

        boolean pillarSlice(int k) {
            return Math.floorMod(rel(k), PILLAR_EVERY) == PILLAR_AT;
        }

        // ------------------------------------------------------------ Tunnel und Treppe

        void emit(int k) {
            int base = base(kind, k);
            boolean light = lightSlice(k);
            BlockSpec shell = ribSlice(k) ? style.rib() : style.wall();
            boolean embedded = light && !style.hangingLight();
            // Kleiner Tunnel mit Laterne: Nische oben in der Wand (statt der Wandzelle), darüber ein tragender Block –
            // im Gang selbst hinge sie auf Kopfhöhe.
            Pos niche = null;
            if (light && style.hangingLight() && w == 0) {
                int side = Math.floorMod(Math.floorDiv(rel(k), LIGHT_EVERY), 2) == 0 ? 1 : -1;
                Pos support = new Pos(side * (w + 1), base + h + 1, k);
                Pos at = new Pos(side * (w + 1), base + h, k);
                if (usable(support) && usable(at) && !site.room(support) && !site.room(at)) {
                    niche = at;
                }
            }
            // 0. Röhre über Luft oder Wasser: an jedem zweiten Lichtabschnitt Pfeiler unter beiden Wänden bis zum
            // Grund (von unten nach oben, wie die Brücke) samt Querbalken unter dem Boden.
            Ground under = site.at(new Pos(0, base - 1, k));
            if (tube && pillarSlice(k) && (under == Ground.OPEN || under == Ground.FLUID)) {
                List<Pos> left = column(-w - 1, base, k);
                List<Pos> right = column(w + 1, base, k);
                if (left != null && right != null && (!left.isEmpty() || !right.isEmpty())) {
                    for (List<Pos> col : List.of(left, right)) {
                        for (int i = col.size() - 1; i >= 0; i--) {
                            put(col.get(i), i == 0 ? style.rib() : style.wall(), k);  // oben ein Kapitell
                        }
                    }
                    for (int x = -w; x <= w; x++) {
                        Pos p = new Pos(x, base - 1, k);
                        Ground g = site.at(p);
                        if (g == Ground.OPEN || g == Ground.FLUID) {
                            put(p, style.rib(), k);
                        }
                    }
                }
            }
            // 1. Hülle: Decke, Wände (oben → unten), Boden bzw. Stufe – bevor innen etwas offen ist.
            for (int x = -w; x <= w; x++) {
                shellCell(new Pos(x, base + h + 1, k), embedded && x == 0 ? style.light() : shell, k);
            }
            if (niche != null) {
                put(new Pos(niche.x(), niche.y() + 1, k), style.wall(), k);
            }
            int wallBottom = stairs() ? base : base + 1;
            for (int y = base + h; y >= wallBottom; y--) {
                for (int side = -1; side <= 1; side += 2) {
                    Pos p = new Pos(side * (w + 1), y, k);
                    if (!p.equals(niche)) {
                        shellCell(p, window(p, k, base) ? style.window()
                                : y == wallBottom && style.trim() != null && !ribSlice(k) ? style.trim() : shell, k);
                    }
                }
            }
            for (int x = -w; x <= w; x++) {
                Pos p = new Pos(x, base, k);
                if (stairs() && k >= 1) {
                    // Nach oben über der Oberfläche keine Stufe (dort ist der Ausgang); nach unten auch im Freien
                    // (ein Hang vor dem Einstieg). Die volle Seite zeigt zur höheren Stufe: hinauf nach vorn
                    // (back), hinab zum Spieler (front).
                    boolean down = kind == TunnelOrder.Kind.STAIRS_DOWN;
                    if (down || !site.sky(p)) {
                        BlockSpec step = style.step() == null ? style.floor()
                                : style.step().withExplicit("facing", down ? "front" : "back")
                                        .withExplicit("half", "bottom");
                        put(p, step, k);  // ersetzt den Boden; Gebautes dort hat plan() schon abgewiesen
                    }
                } else {
                    shellCell(p, x == 0 && w >= 1 && style.runner() != null ? style.runner() : style.floor(), k);
                }
            }
            // 2. Front des nächsten Abschnitts zu, wo dort Luft oder Flüssigkeit steht – in der Röhre auch über der
            // Oberfläche: Im See hält sie das Wasser draußen, und am Ende ist sie die Stirn, an der TunnelScan das
            // Ende erkennt.
            for (int y = base + 1; y <= base + h; y++) {
                for (int x = -w; x <= w; x++) {
                    Pos p = new Pos(x, y, k + 1);
                    Ground g = site.at(p);
                    if ((tube || !site.sky(p)) && !site.room(p) && (g == Ground.OPEN || g == Ground.FLUID)) {
                        put(p, style.wall(), k);
                    }
                }
            }
            // 3. Innen ausräumen, je von oben nach unten: erst Flüssiges, dann den Rest. Andersherum stünde eine Zelle
            // schon offen, während daneben noch Lava steht, und sie liefe hinein (nur im ersten Abschnitt möglich – in
            // allen späteren hat die Front des vorigen Abschnitts Flüssiges schon zugesetzt).
            List<Pos> inner = interior(k);
            for (Pos p : inner) {
                if (site.at(p) == Ground.FLUID) {
                    put(p, BlockSpec.of("air"), k);
                }
            }
            List<Pos> deco = new ArrayList<>();
            List<BlockSpec> decoSpec = new ArrayList<>();
            for (Pos p : inner) {
                BlockSpec d = site.room(p) ? null : decoration(p, k, base, light);
                if (d != null) {
                    deco.add(p);
                    decoSpec.add(d);
                } else if (site.at(p) != Ground.FLUID) {
                    put(p, BlockSpec.of("air"), k);
                }
            }
            // 4. Bogen, Kette, Laterne – Angehängtes zuletzt, die Laterne nach ihrer Kette.
            for (int i = 0; i < deco.size(); i++) {
                if (!isLantern(decoSpec.get(i))) {
                    put(deco.get(i), decoSpec.get(i), k);
                }
            }
            for (int i = 0; i < deco.size(); i++) {
                if (isLantern(decoSpec.get(i))) {
                    put(deco.get(i), decoSpec.get(i), k);
                    lights++;
                }
            }
            if (niche != null) {
                put(niche, style.light().withExplicit("hanging", "true"), k);
                lights++;
            }
            if (embedded && usable(new Pos(0, base + h + 1, k))) {
                lights++;
            }
            done(k);
        }

        /** Was innen an p statt Luft hinkommt: Bogen oben in den Ecken, Kette und hängende Laterne in der Mitte. */
        private BlockSpec decoration(Pos p, int k, int base, boolean light) {
            int top = base + h;
            // Bogen: umgedrehte Treppe, die volle Seite zur Wand (facing nach außen, half=top) – so rundet sie die
            // obere Ecke ab, statt einen Absatz in den Gang zu ragen.
            if (style.arch() != null && w >= 1 && p.y() == top && Math.abs(p.x()) == w && (tube || !site.sky(p))) {
                return style.arch().withExplicit("facing", p.x() > 0 ? "right" : "left").withExplicit("half", "top");
            }
            // Hängt nur unter einer Decke im Boden: Über der Oberfläche (Treppenausgang) fiele sie als Gegenstand ab.
            if (light && style.hangingLight() && w >= 1 && p.x() == 0
                    && (tube || !site.sky(new Pos(0, top + 1, k)))) {
                if (style.chain() != null && p.y() == top) {
                    return style.chain().withExplicit("axis", "y");
                }
                if (p.y() == (style.chain() != null ? top - 1 : top)) {
                    return style.light().withExplicit("hanging", "true");
                }
            }
            return null;
        }

        // ------------------------------------------------------------ Brücke

        /** Warum die Brücke vor Abschnitt k endet, oder null. Setzt blockedAt. */
        Stop bridgeBlocked(int k, List<Pos> walkway) {
            int base = base(kind, k);
            for (Pos p : walkway) {
                Ground g = site.at(p);
                Stop s = switch (g) {
                    case FLUID -> Stop.WATER;  // das Deck läge unter Wasser
                    case BUILT -> Stop.BUILT;
                    case FORBIDDEN -> Stop.FORBIDDEN;
                    case SOLID -> p.y() == base + h ? Stop.HILL : null;  // darunter: Hohlweg
                    case OPEN -> null;
                };
                if (s != null) {
                    blockedAt = p;
                    return s;
                }
            }
            for (int x = -w - 1; x <= w + 1; x++) {
                Pos p = new Pos(x, base, k);
                Ground g = site.at(p);
                if (g == Ground.BUILT || g == Ground.FORBIDDEN) {
                    blockedAt = p;
                    return g == Ground.BUILT ? Stop.BUILT : Stop.FORBIDDEN;
                }
            }
            return null;
        }

        void emitBridge(int k) {
            int base = base(kind, k);
            int edge = w + 1;
            boolean light = lightSlice(k);
            // 1. Pfeiler unter beiden Kanten bis zum Grund, von unten nach oben, mit Querbalken – nur, wo es unter dem
            // Deck überhaupt Luft oder Wasser gibt und beide Seiten Grund finden.
            if (pillarSlice(k)) {
                List<Pos> left = column(-edge, base - 1, k);
                List<Pos> right = column(edge, base - 1, k);
                if (left != null && right != null && (!left.isEmpty() || !right.isEmpty())) {
                    for (List<Pos> col : List.of(left, right)) {
                        for (int i = col.size() - 1; i >= 0; i--) {
                            put(col.get(i), i == 0 ? style.rib() : style.wall(), k);  // oben ein Kapitell
                        }
                    }
                    for (int x = -w; x <= w; x++) {
                        Pos p = new Pos(x, base - 1, k);
                        Ground g = site.at(p);
                        if (g == Ground.OPEN || g == Ground.FLUID) {
                            put(p, style.rib(), k);
                        }
                    }
                }
            }
            // 2. Deck auf Standhöhe, Kanten unter dem Geländer mit.
            for (int x = -edge; x <= edge; x++) {
                put(new Pos(x, base, k), x == 0 && w >= 1 && style.runner() != null ? style.runner() : style.floor(),
                        k);
            }
            // 3. Gehweg frei: Pflanzen weg, ein flacher Hügel wird zum Hohlweg – von oben nach unten.
            for (Pos p : interior(k)) {
                put(p, BlockSpec.of("air"), k);
            }
            // 4. Geländer; ein Leuchtblock (Seelaterne …) wird selbst zum Pfosten.
            boolean post = light && !style.hangingLight();
            BlockSpec railing = style.railing() != null ? style.railing() : style.floor();
            for (int side = -1; side <= 1; side += 2) {
                Pos p = new Pos(side * edge, base + 1, k);
                Ground g = site.at(p);
                if (g != Ground.BUILT && g != Ground.FORBIDDEN) {
                    // Balustrade (Wunsch 2026-11-16, „edel“): alle 3 Blöcke ein gemauerter Pfosten – auf jedem
                    // zweiten steht die Laterne (Lichtabschnitte fallen auf Pfosten: 6 ist ein Vielfaches von 3).
                    boolean column = style.railing() != null
                            && Math.floorMod(rel(k), POST_EVERY) == LIGHT_AT % POST_EVERY;
                    put(p, post ? style.light() : column ? style.rib() : railing, k);
                    if (post) {
                        lights++;
                    }
                }
            }
            // 5. Laternen auf dem Geländer.
            if (light && style.hangingLight()) {
                for (int side = -1; side <= 1; side += 2) {
                    Pos p = new Pos(side * edge, base + 2, k);
                    Ground g = site.at(p);
                    if (g == Ground.OPEN || g == Ground.SOLID) {
                        put(p, style.light().withExplicit("hanging", "false"), k);
                        lights++;
                    }
                }
            }
            done(k);
        }

        /**
         * Pfeilerzellen unter (x, top, k) bis zum Grund, von oben nach unten; leer = der Grund liegt direkt darunter,
         * null = zu tief (über {@link #PILLAR_MAX}) – dann bekommt dieser Abschnitt keinen Pfeiler.
         */
        private List<Pos> column(int x, int top, int k) {
            List<Pos> out = new ArrayList<>();
            for (int y = top; y > top - PILLAR_MAX; y--) {
                Pos p = new Pos(x, y, k);
                Ground g = site.at(p);
                if (g == Ground.OPEN || g == Ground.FLUID) {
                    out.add(p);
                } else {
                    return out;  // fest, gebaut oder Grundgestein trägt
                }
            }
            return null;
        }

        // ------------------------------------------------------------ gemeinsam

        private void done(int k) {
            if (first < 0) {
                first = k;
            }
            last = k;
        }

        private boolean isLantern(BlockSpec s) {
            return TunnelStyle.HANGING_LIGHTS.contains(s.id());
        }

        /**
         * Hüllzelle: Gebautes und Grundgestein bleiben als Wand stehen; über der Oberfläche nur in der Röhre; im
         * Innenraum eines Gangs, aus dem eine Treppe steigt, nie.
         */
        private void shellCell(Pos p, BlockSpec block, int k) {
            if (usable(p) && !site.room(p)) {
                put(p, block, k);
            }
        }

        /**
         * Fenster statt Wand: in der Röhre, wo die Wand über der Oberfläche steht (Tal, See, Meer), in Augenhöhe, nie
         * im Gurtbogen. Im Fels sähe man nur Stein; in einer Höhle wäre es ein Loch in die Dunkelheit.
         */
        private boolean window(Pos p, int k, int base) {
            return tube && style.window() != null && !ribSlice(k) && p.y() >= base + 2
                    && p.y() <= Math.max(base + 2, base + h - 1) && site.sky(p);
        }

        /** Weder Gebautes noch Grundgestein, und im Boden oder in der Röhre – dort darf die Hülle hin. */
        private boolean usable(Pos p) {
            if (!tube && site.sky(p)) {
                return false;
            }
            Ground g = site.at(p);
            return g != Ground.BUILT && g != Ground.FORBIDDEN;
        }

        private void put(Pos p, BlockSpec spec, int k) {
            actions.add(new Action(k, List.of(new Change(p, spec))));
            if (!spec.isAir()) {
                blocks.merge(spec.id(), 1, Integer::sum);
            }
            min = min == null ? p : new Pos(Math.min(min.x(), p.x()), Math.min(min.y(), p.y()),
                    Math.min(min.z(), p.z()));
            max = max == null ? p : new Pos(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()),
                    Math.max(max.z(), p.z()));
        }

        Result result(Stop stop, Pos at) {
            List<String> names = new ArrayList<>();
            int slices = Math.max(0, last + 1);
            String what = switch (kind) {
                case TUNNEL -> "tunnel ";
                case STAIRS_UP, STAIRS_DOWN -> "treppe ";
                case BRIDGE -> "brücke ";
            };
            for (int i = 0; i < slices; i++) {
                names.add(what + (i + 1));
            }
            CompiledBuild build = new CompiledBuild(List.copyOf(actions), List.of(),
                    min == null ? new Pos(0, 0, 0) : min, max == null ? new Pos(0, 0, 0) : max, List.copyOf(names));
            return new Result(build, spec, first, last, stop, at, at == null ? null : site.id(at), lights,
                    Map.copyOf(blocks), openFrom);
        }
    }
}
