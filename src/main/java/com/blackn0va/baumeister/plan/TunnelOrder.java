package com.blackn0va.baumeister.plan;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Grab- und Brückenaufträge ohne LLM (Wunsch 2026-11-16): „grab mit mir einen Tunnel“, „bau einen Tunnel 40 Blöcke
 * schnell“, „bau mir eine Treppe nach oben“, „bau eine Brücke übers Meer“, „bau die Brücke weiter“. Ein Tunnel hängt
 * an dem, was im Boden steckt – Gestein, Höhlen, Wasser, Lava, Kies –, eine Brücke an Wasser und Tiefe darunter;
 * beide entstehen Abschnitt für Abschnitt (TunnelShape). Das kann ein Modell ohne Blick in den Boden nicht planen, und
 * ein Plan aus Grundformen verlöre genau diese Reihenfolge.
 *
 * Eng wie die Schnellaufträge (QuickOrders): Ein Treffer nur, wenn außer Tunnel-, Treppen- oder Brückenwort, Länge,
 * Tempo, Größe, Richtung, Material und Füllwörtern nichts übrig bleibt. „Tunnel mit Schienen“ oder „Treppe zum Dach“
 * bleiben beim LLM. Eine Treppe ohne „nach oben“, „hoch“, „zur Oberfläche“ … ist keine Grabtreppe, sondern ein Bauwerk.
 * „Gang“ zählt bewusst nicht als Tunnelwort: „bau einen Gang“ meint oben meist einen Flur. Bogen-, Balken-, Zug- und
 * Hängebrücke bleiben bei QuickForms bzw. dem LLM – das sind Bauformen mit fester Spannweite, keine Wege.
 *
 * Tempo: ohne Länge baut der Baumeister mit dem Spieler mit (together, „er macht mit“: nie weiter als ein paar Blöcke
 * vor ihm, und ohne Ende, solange er mitgeht); mit „schnell“ baut er allein im Schnellgang; mit Länge ohne „schnell“
 * allein Block für Block. Bei Treppen ist die Zahl die höchste Stufenzahl.
 *
 * resume („bau weiter“, „grab den Tunnel weiter“, Wunsch 2026-11-16): einen vorhandenen Tunnel, eine Treppe oder
 * Brücke erkennen (TunnelScan) und dort weiterbauen. kind ist dann null, wenn der Auftrag nicht sagt, was – „bau
 * weiter“ allein gehört nur dann hierher, wenn am Spieler wirklich etwas erkannt wird; sonst geht er den gewohnten Weg
 * (Gedächtnis, LLM).
 *
 * Quer über die Karte (Wunsch 2026-11-16, „ein Tunnel muss gerade gehen – er wird irgendwann durch ca. die komplette
 * Karte gehen“): Längen bis {@link #MAX_LENGTH}, auch „10.000“ (TunnelService baut sie Stück für Stück). „durch die
 * ganze Karte“ im Schnellgang heißt {@link #MAX_LENGTH}; ohne „schnell“ gräbt er ohnehin mit, solange der Spieler
 * mitgeht. daylight („durch den Berg“, „bis ins Freie“, „auf die andere Seite“): Der Tunnel endet, wo er ins Freie
 * bricht – sonst läuft er dort als Röhre geradeaus weiter (TunnelShape).
 *
 * Threads: zustandslos. Fehler: keine – kein Treffer heißt leer.
 */
public record TunnelOrder(Kind kind, Size size, int length, boolean fast, boolean together, Turn turn,
        String material, boolean fromChest, boolean resume, boolean daylight) {

    public enum Kind {
        TUNNEL, STAIRS_UP, BRIDGE
    }

    /** Innenmaß: SMALL 1 breit × 2 hoch, NORMAL 3 × 3, LARGE 5 × 4 (Brücke: Gehweg und lichte Höhe). */
    public enum Size {
        SMALL(0, 2), NORMAL(1, 3), LARGE(2, 4);

        /** Halbe Innenbreite: x läuft von -half bis half. */
        public final int half;
        public final int height;

        Size(int half, int height) {
            this.half = half;
            this.height = height;
        }
    }

    /** Wohin, bezogen auf die Blickrichtung (AHEAD/LEFT/RIGHT) oder als Himmelsrichtung. */
    public enum Turn {
        AHEAD, LEFT, RIGHT, NORTH, EAST, SOUTH, WEST
    }

    /**
     * Höchste Länge, die der Auftrag nennen darf. Vorher 256 (ein Stück bis bau.max-ausdehnung) – zu kurz für einen
     * Tunnel quer über die Karte; TunnelService baut längere Aufträge jetzt Stück für Stück. Größer nicht: Im
     * Schnellgang lädt und erzeugt jedes Stück die Chunks entlang des Wegs.
     */
    public static final int MAX_LENGTH = 10_000;

    private static final Set<String> TUNNEL = Set.of("tunnel", "tunnels", "stollen");
    private static final Set<String> STAIRS = Set.of("treppe", "treppen", "aufgang", "treppenaufgang", "ausgang");
    // Normalisierte Formen wie RequestHints.normalize (ä → ae …) – daher umlaut-ok.
    private static final Set<String> BRIDGE = Set.of("bruecke", "bruecken", "steg", "stege", // umlaut-ok
            "holzbruecke", "steinbruecke", "brueckenweg"); // umlaut-ok
    private static final Set<String> UP = Set.of("oben", "hoch", "rauf", "herauf", "hinauf", "aufwaerts", // umlaut-ok
            "oberflaeche", "tageslicht", "draussen", "raus"); // umlaut-ok
    private static final Set<String> RESUME = Set.of("weiter", "weiterbauen", "weitergraben", "weitermachen",
            "verlaengern", "verlaengere", "verlaengert", "fortsetzen", "fort"); // umlaut-ok
    private static final Set<String> FAST = Set.of("schnell", "schnelle", "schnellen", "schneller",
            "schnellverfahren", "schnellgang", "sofort", "flott", "turbo", "rasch", "blitzschnell",
            "zuegig"); // umlaut-ok
    private static final Set<String> SMALL = Set.of("klein", "kleine", "kleinen", "kleiner", "kleines", "schmal",
            "schmale", "schmalen", "schmaler", "eng", "enge", "engen", "enger", "1x2", "2x1");
    private static final Set<String> LARGE = Set.of("gross", "grosse", "grossen", "grosser", "grosses", "breit",
            "breite", "breiten", "breiter", "riesig", "riesige", "riesigen", "riesiger", "5x4", "4x5", "5x5");
    private static final Set<String> NORMAL = Set.of("normal", "normale", "normalen", "normaler", "3x3");
    /** „mit mir“ steht unten als Wortpaar; „endlos“/„ohne Ende“ heißt: mitbauen, solange der Spieler geht. */
    private static final Set<String> TOGETHER = Set.of("zusammen", "gemeinsam", "mitgraben", "mitbauen",
            "mitmachen", "wir", "endlos", "endlose", "endlosen", "endloser", "unendlich", "unendliche",
            "unendlichen", "ende");
    private static final Set<String> CHEST = Set.of("kiste", "truhe", "fass", "material", "materialien",
            "materialkiste");
    /** Einheiten und Wörter rund um die Länge („40 Blöcke lang“, „30 Meter weit“). */
    private static final Set<String> UNITS = Set.of("block", "bloecke", "bloecken", "meter", "m", "felder", // umlaut-ok
            "feld", "lang", "lange", "langen", "langer", "weit", "stufen", "stufe");
    private static final Set<String> FILLER = Set.of("bau", "baue", "bauen", "baust", "grab", "grabe", "graben",
            "grabt", "buddel", "buddle", "buddeln", "mach", "mache", "machen", "bitte", "mal", "mir", "uns", "einen",
            "ein", "eine", "den", "die", "das", "der", "dem", "des", "hier", "jetzt", "doch", "noch", "kannst", "du",
            "sollst", "lass", "lasst", "mit", "nach", "zur", "zum", "an", "ans", "bis", "in", "im", "durch", "von",
            "vom", "und", "ab", "los", "neuen", "neue", "neuer", "neues", "ohne", "geradeaus", "vorne", "richtung",
            "rein", "hinein", "berg", "fels", "felsen", "erde", "boden", "untergrund", "unter", "unterirdisch",
            "unterirdischen", "unterirdischer", "meiner", "meinem", "dieses", "diesen", "diese", "dieser", "es", "ihn",
            "sie", "dran", "daran", "weg", "strecke", "um",
            "koenntest", "wuerdest", "fuer", "vorwaerts", "schoen", "schoenen", "schoener", // umlaut-ok
            "huebsch", "huebschen"); // umlaut-ok
    /**
     * Wasser und Tiefe gehören nur zur Brücke („Brücke übers Meer“). „Tunnel unter dem Fluss“ bleibt beim LLM: Ein
     * Tunnel auf Fußhöhe vom Ufer aus liefe nicht unter dem Fluss durch, sondern hinein (dort endet er, Stop.WATER).
     */
    private static final Set<String> CROSSING = Set.of("ueber", "uebers", "meer", "meere", "wasser", "see", // umlaut-ok
            "ozean", "fluss", "tal", "schlucht", "drueber", "hinueber", "rueber"); // umlaut-ok
    private static final Set<String> WITH_WHOM = Set.of("mir", "uns");
    /** „bis ins Freie“, „im Freien“: dort aufhören statt als Röhre weiter (daylight). */
    private static final Set<String> DAYLIGHT = Set.of("freie", "freien");
    /** „durch den Berg“: Wörter nach „durch“ (Artikel übersprungen), die einen Berg meinen – daylight. */
    private static final Set<String> MOUNTAIN = Set.of("berg", "berge", "huegel", "hang", "fels", "felsen", // umlaut-ok
            "felswand", "bergwand");
    private static final Set<String> ARTICLES = Set.of("den", "die", "das", "dem", "einen", "ein", "eine");
    /** „durch die ganze Karte“, „bis ans Ende der Welt“, „quer über die Map“. */
    private static final Set<String> ACROSS = Set.of("karte", "welt", "weltrand", "map");
    private static final Set<String> ACROSS_FILLER = Set.of("ganze", "ganzen", "komplette", "kompletten", "gesamte",
            "gesamten", "ins", "auf", "andere", "anderen", "seite", "felswand", "bergwand", "hang", "berge",
            "quer", "huegel"); // umlaut-ok

    /**
     * Mitbauen: kein „schnell“, und entweder „mit mir/zusammen/endlos“ oder keine Länge. Treppen nie – die Treppe läuft
     * bis zur Oberfläche durch, Abschnitte vor dem Spieler zu halten ergäbe dort nichts.
     */
    public boolean follow() {
        return kind != Kind.STAIRS_UP && !fast && (together || length == 0);
    }

    /** Größe, wie gesagt, sonst NORMAL (beim Weiterbauen gilt die erkannte). */
    public Size sizeOrNormal() {
        return size == null ? Size.NORMAL : size;
    }

    public static Optional<TunnelOrder> parse(String prompt) {
        List<String> t = RequestHints.tokens(RequestHints.sizes(RequestHints.normalize(prompt)));
        boolean tunnel = false;
        boolean stairs = false;
        boolean bridge = false;
        boolean up = false;
        boolean fast = false;
        boolean together = false;
        boolean chest = false;
        boolean resume = false;
        boolean crossing = false;
        boolean daylight = false;
        boolean across = false;
        Size size = null;
        Integer length = null;
        Turn turn = Turn.AHEAD;
        String material = null;
        for (int i = 0; i < t.size(); i++) {
            String w = t.get(i);
            String next = i + 1 < t.size() ? t.get(i + 1) : "";
            OptionalInt n = w.matches("\\d{5}") ? OptionalInt.of(Integer.parseInt(w)) : RequestHints.number(w);
            // „10.000“ bzw. „10 000“: RequestHints.normalize macht aus dem Punkt ein Leerzeichen.
            boolean thousands = n.isPresent() && w.matches("\\d{1,3}") && next.matches("\\d{3}");
            if (thousands) {
                n = OptionalInt.of(Integer.parseInt(w) * 1000 + Integer.parseInt(next));
            }
            if (w.equals("durch")) {
                int j = i + 1;
                while (j < t.size() && ARTICLES.contains(t.get(j))) {
                    j++;
                }
                daylight |= j < t.size() && MOUNTAIN.contains(t.get(j));
            }
            if (w.equals("seite") && i > 0 && (t.get(i - 1).equals("andere") || t.get(i - 1).equals("anderen"))) {
                daylight = true;
            }
            if (TUNNEL.contains(w)) {
                tunnel = true;
            } else if (STAIRS.contains(w)) {
                stairs = true;
            } else if (BRIDGE.contains(w)) {
                bridge = true;
                if (w.startsWith("holz") || w.startsWith("stein")) {
                    String m = w.startsWith("holz") ? "oak_planks" : "stone_bricks";
                    if (material != null && !material.equals(m)) {
                        return Optional.empty();
                    }
                    material = m;
                }
            } else if (UP.contains(w)) {
                up = true;
            } else if (RESUME.contains(w)) {
                resume = true;
            } else if (FAST.contains(w)) {
                fast = true;
            } else if (SMALL.contains(w) || LARGE.contains(w) || NORMAL.contains(w)) {
                Size s = SMALL.contains(w) ? Size.SMALL : LARGE.contains(w) ? Size.LARGE : Size.NORMAL;
                if (size != null && size != s) {
                    return Optional.empty();  // „großen kleinen Tunnel“: unklar, das LLM fragt nach
                }
                size = s;
            } else if (n.isPresent()) {
                // „ein/einen“ stehen nicht in RequestHints.NUMBER_WORDS – „grab einen Tunnel“ hat also keine Länge 1.
                if (length != null) {
                    return Optional.empty();  // zwei Zahlen: welche ist die Länge?
                }
                length = n.getAsInt();
                if (thousands) {
                    i++;
                }
            } else if (TOGETHER.contains(w)) {
                together = true;
            } else if ((w.equals("lass") || w.equals("lasst")) && next.equals("uns")
                    || w.equals("mit") && WITH_WHOM.contains(next)) {
                together = true;
            } else if (CHEST.contains(w)) {
                chest = true;
            } else if (DAYLIGHT.contains(w)) {
                daylight = true;
            } else if (ACROSS.contains(w)) {
                across = true;
            } else if (CROSSING.contains(w)) {
                crossing = true;
            } else if (w.equals("aus") && i + 1 < t.size()) {
                Optional<String> m = RequestHints.material(next);
                if (m.isPresent()) {
                    if (material != null && !material.equals(m.get())) {
                        return Optional.empty();
                    }
                    material = m.get();
                    i++;
                }
                // „aus der Kiste“: die Kiste zählt oben, „der“ ist Füllwort.
            } else if (turnOf(w) != null) {
                Turn tw = turnOf(w);
                if (turn != Turn.AHEAD && turn != tw) {
                    return Optional.empty();
                }
                turn = tw;
            } else if (!FILLER.contains(w) && !UNITS.contains(w) && !ACROSS_FILLER.contains(w) && !w.equals("aus")) {
                return Optional.empty();
            }
        }
        if (tunnel && bridge || stairs && bridge) {
            return Optional.empty();  // „Tunnel und Brücke“: zwei Bauwerke, das LLM soll fragen
        }
        Kind kind;
        if (stairs) {
            if (!up) {
                return Optional.empty();
            }
            kind = Kind.STAIRS_UP;
        } else if (tunnel) {
            kind = up ? Kind.STAIRS_UP : Kind.TUNNEL;
        } else if (bridge) {
            if (up) {
                return Optional.empty();
            }
            kind = Kind.BRIDGE;
        } else if (resume && !up) {
            kind = null;  // „bau weiter“: was, sagt erst der Blick auf die Stelle (TunnelScan)
        } else {
            return Optional.empty();
        }
        if (crossing && kind != Kind.BRIDGE) {
            return Optional.empty();
        }
        int len = length == null ? 0 : length;
        if (length != null && (len < 1 || len > MAX_LENGTH)) {
            return Optional.empty();
        }
        if (across && fast && length == null && kind != Kind.STAIRS_UP) {
            len = MAX_LENGTH;  // „durch die ganze Karte, schnell“ – bis zum Rand der Welt oder MAX_LENGTH
        }
        return Optional.of(new TunnelOrder(kind, size, len, fast, together, turn, material, chest, resume,
                daylight));
    }

    private static Turn turnOf(String w) {
        return switch (w) {
            case "links" -> Turn.LEFT;
            case "rechts" -> Turn.RIGHT;
            case "norden", "nord", "noerdlich" -> Turn.NORTH; // umlaut-ok
            case "osten", "ost", "oestlich" -> Turn.EAST; // umlaut-ok
            case "sueden", "sued", "suedlich" -> Turn.SOUTH; // umlaut-ok
            case "westen", "west", "westlich" -> Turn.WEST;
            default -> null;
        };
    }
}
