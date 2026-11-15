package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schnellaufträge „bau mir eine riesige Lobby“ und „bau eine Portalhalle mit 6 Portalen“: Eingang für einen frischen
 * Server, ohne LLM (Wunsch 2026-11-15: „ich will auch einen frischen server einrichten können mit dem baumeister, mit
 * diesen portal hallen burgen, riesen große lobys“).
 *
 * Warum gerechnet: Eine Lobby ist Wiederholung und Symmetrie – Lichtringe im festen Abstand, Portale gleichmäßig über
 * die Wand verteilt, eine Kuppel genau über der Mitte. Das rechnet der Compiler sicher, das kleine Modell nicht (wie
 * beim Bunker gemessen). Und für den Spawn zählt, dass nachts nichts spawnt; das prüft LobbyTest an jeder Variante
 * über die Lichtausbreitung, statt es zu hoffen.
 *
 * <ul>
 * <li><b>Lobby</b>: quadratische Halle (33 bis 81, oder „60x60“), Fensterband, Dach aus Platten, Glaskuppel über
 * einem runden Platz in der Mitte (Spawn), vier Säulen um den Platz, Weg vom Eingang zum Hauptportal, Lichtringe im
 * Boden. Portalrahmen an beiden Seitenwänden und ein großer an der Rückwand.</li>
 * <li><b>Portalhalle</b>: lange Halle mit rotem Teppich, Lichtleisten neben dem Teppich, Oberlicht im Dach,
 * Portalrahmen links und rechts und einer an der Stirnwand.</li>
 * </ul>
 *
 * Portalrahmen: Obsidian, innen 3 × 4 frei (Rückwand 5 × 6), unterste Reihe im Boden – man geht ebenerdig hinein. Der
 * Bau setzt KEINE Portalblöcke: nether_portal ist Stufe „gefährlich“ (BlockTiers), ein Spieler ohne dieses Recht
 * bekäme sonst je Portal eine Absage. Entzünden und mit einem Ziel verbinden ist ein eigener Schritt für Admins.
 *
 * Spawn-sicher, ohne Sonderblöcke: Monster brauchen Lichtstufe 0 (seit 1.18) und eine volle Oberseite. Im Boden liegen
 * Seelaternen als Ringe (Lobby) bzw. Leisten (Portalhalle), das Dach ist aus unteren Platten, Kuppel und Oberlicht
 * aus Glas – auf beidem spawnt nichts.
 *
 * Lage wie Bunker (Plankoordinaten: x rechts, z vom Spieler weg, y 0 = erste Schicht über dem Boden): Boden auf y −1
 * (ersetzt die oberste Erdschicht, Eingang ebenerdig), zwei Schichten Fundament darunter für kleine Senken, Innenraum
 * vorher mit air ausgehöhlt, sonst blieben Hügel und Bäume in der Halle stehen.
 *
 * Threads: zustandslos. Fehler: keine – passt die Halle nicht in bau.max-ausdehnung, gibt es leer zurück (dann plant
 * das LLM).
 */
public final class Lobby {

    /** Normalisierte Formen (RequestHints.normalize). „lobys“ steht so im Wunsch vom 2026-11-15. */
    static final Set<String> LOBBY_WORDS = Set.of("lobby", "lobbys", "lobbies", "loby", "lobys", "spawnlobby",
            "spawnlobbys", "serverlobby", "hub", "serverhub", "spawnhalle", "eingangshalle", "empfangshalle", "foyer");
    static final Set<String> HALL_WORDS = Set.of("portalhalle", "portalhallen", "portalsaal", "portalraum");
    /** „eine Halle mit Portalen“: „Halle“ allein ist zu allgemein, erst mit einem Portalwort eine Portalhalle. */
    static final Set<String> PLAIN_HALL = Set.of("halle", "hallen", "saal");
    static final Set<String> PORTAL_WORDS = Set.of("portal", "portale", "portalen", "portals", "portalrahmen");
    static final Set<String> SMALL = Set.of("klein", "kleine", "kleinen", "kleiner", "kleines");
    static final Set<String> BIG = Set.of("gross", "grosse", "grossen", "grosser", "grosses");
    static final Set<String> HUGE = Set.of("riesig", "riesige", "riesigen", "riesiger", "riesiges", "riesen",
            "riesengross", "riesengrosse", "riesengrossen", "gigantisch", "gigantische", "gigantischen", "gewaltig",
            "gewaltige", "gewaltigen", "mega", "monumental", "monumentale", "monumentalen", "enorm", "enorme",
            "enormen", "xxl");
    /**
     * Beschreiben nur, was ohnehin gebaut wird („für meinen Server“, „am Spawn“, „mit Kuppel“). Jedes andere Wort
     * schickt den Auftrag ans LLM: „eine Lobby mit Kino“ soll das Kino bekommen, nicht still eine Lobby ohne.
     */
    static final Set<String> MODIFIERS = Set.of("spawn", "server", "den", "dem", "zum", "zur", "zu", "am", "als",
            "fuer", "meinen", "meine", "meinem", "unseren", "unsere", "unserem", "neue", "neuen", "neuer", // umlaut-ok
            "neues", "schoene", "schoenen", "schoener", "schoenes", "und", "so", "wie", "welt", "welten", // umlaut-ok
            "alle", "allen", "kuppel", "glaskuppel", "eingang", "echt", "echte", "echten", "sehr", "richtig", "ohne");

    private static final Pattern DIMENSIONS = Pattern.compile("(\\d{1,3})x(\\d{1,3})");

    enum Kind {
        LOBBY, PORTAL_HALL
    }

    enum Size {
        SMALL, NORMAL, BIG, HUGE
    }

    /**
     * portals: gewünschte Zahl, 0 = Vorgabe der Größe, -1 = „ohne Portale“. width/depth 0 = Vorgabe der Größe; sonst
     * aus „60x60“ (quer × in die Tiefe).
     */
    record Order(Kind kind, Size size, int portals, int width, int depth) {
    }

    static final int MIN_LOBBY = 25;
    static final int MAX_LOBBY = 81;
    static final int MIN_HALL_WIDTH = 11;
    static final int MAX_HALL_WIDTH = 31;
    static final int MIN_HALL_DEPTH = 21;
    static final int MAX_HALL_DEPTH = 96;
    /** Höchstens so viele Portale je Seitenwand: Jedes kostet 3 Schritte, der Plan hat höchstens 60. */
    static final int MAX_LOBBY_PER_SIDE = 5;
    static final int MAX_HALL_PER_SIDE = 6;
    /** Rahmen 5 breit plus mindestens 3 Wand dazwischen. */
    private static final int MIN_PITCH = 8;
    /** Eingangs- bzw. Rückwandbereich der Seitenwände ohne Portal. */
    private static final int SIDE_MARGIN = 6;

    private static final String FLOOR = "polished_andesite";
    private static final String WALL = "stone_bricks";
    private static final String PLINTH = "polished_deepslate";
    private static final String CORNICE = "chiseled_stone_bricks";
    private static final String ROOF = "smooth_stone_slab";
    private static final String LIGHT = "sea_lantern";
    private static final String FRAME = "obsidian";

    private Lobby() {
    }

    /**
     * Erkennung auf normalisierten Wörtern (nach RequestHints.sizes, damit „60 mal 60“ als „60x60“ ankommt): ein Lobby-
     * oder Portalhallenwort, sonst nur Füll-, Größen-, Zahl- und Beschreibungswörter. Größe klein und groß zugleich ist
     * kein Treffer; „riesen große“ ist riesig.
     */
    static Optional<Order> parse(List<String> tokens, Set<String> filler) {
        boolean lobby = false;
        boolean hall = false;
        boolean plainHall = false;
        boolean portalWord = false;
        boolean without = false;
        boolean small = false;
        boolean big = false;
        boolean huge = false;
        int count = 0;
        int width = 0;
        int depth = 0;
        for (String t : tokens) {
            Matcher dims = DIMENSIONS.matcher(t);
            OptionalInt n = filler.contains(t) ? OptionalInt.empty() : RequestHints.number(t);
            if (filler.contains(t)) {
                continue;
            } else if (LOBBY_WORDS.contains(t)) {
                lobby = true;
            } else if (HALL_WORDS.contains(t)) {
                hall = true;
            } else if (PLAIN_HALL.contains(t)) {
                plainHall = true;
            } else if (PORTAL_WORDS.contains(t)) {
                portalWord = true;
            } else if (SMALL.contains(t)) {
                small = true;
            } else if (BIG.contains(t)) {
                big = true;
            } else if (HUGE.contains(t)) {
                huge = true;
            } else if (dims.matches()) {
                if (width != 0) {
                    return Optional.empty();
                }
                width = Integer.parseInt(dims.group(1));
                depth = Integer.parseInt(dims.group(2));
            } else if (n.isPresent()) {
                if (count != 0) {
                    return Optional.empty();
                }
                count = n.getAsInt();
            } else if (t.equals("ohne")) {
                without = true;
            } else if (!MODIFIERS.contains(t)) {
                return Optional.empty();
            }
        }
        hall = hall || plainHall && portalWord;
        if (!lobby && !hall || small && (big || huge) || count < 0 || count > 2 * MAX_HALL_PER_SIDE + 1) {
            return Optional.empty();
        }
        // „ohne Portale“ nur mit Portalwort; „ohne“ allein beschreibt nichts, was gebaut wird.
        if (without && (!portalWord || count != 0)) {
            return Optional.empty();
        }
        Size size = huge ? Size.HUGE : big ? Size.BIG : small ? Size.SMALL : Size.NORMAL;
        return Optional.of(new Order(lobby && !hall ? Kind.LOBBY : Kind.PORTAL_HALL, size, without ? -1 : count,
                width, depth));
    }

    static Optional<String> plan(Order o, int maxExtent) {
        return o.kind() == Kind.LOBBY ? lobby(o, maxExtent) : portalHall(o, maxExtent);
    }

    // ---------------------------------------------------------------- Lobby

    private static int lobbySide(Size s) {
        return switch (s) {
            case SMALL -> 33;
            case NORMAL -> 49;
            case BIG -> 65;
            case HUGE -> MAX_LOBBY;
        };
    }

    /** Ungerade, damit Mitte, Weg und Kuppel genau auf einem Block liegen. */
    private static int odd(int v, int min, int max) {
        int c = Math.clamp(v, min, max);
        return c % 2 == 1 ? c : c == max ? c - 1 : c + 1;
    }

    static Optional<String> lobby(Order o, int maxExtent) {
        int w = o.width() > 0 ? odd(o.width(), MIN_LOBBY, MAX_LOBBY) : lobbySide(o.size());
        int d = o.depth() > 0 ? odd(o.depth(), MIN_LOBBY, MAX_LOBBY) : lobbySide(o.size());
        int hw = w / 2;
        int z1 = d - 1;
        int cz = d / 2;
        int h = 5 + Math.max(w, d) / 8;               // oberste Innenschicht: 33 → 9, 49 → 11, 65 → 13, 81 → 15
        int plaza = Math.max(4, Math.min(w, d) / 8);  // Radius des Platzes: 4, 6, 8, 10
        int dome = plaza + 2;
        int domeTop = h + 1 + dome;
        if (Math.max(Math.max(w, d), domeTop + 4) > maxExtent) {
            return Optional.empty();
        }
        // Portale: Seitenwände gleichmäßig, Rückwand einer (groß). Ungerade Wünsche geben der Rückwand den einen.
        int maxPerSide = Math.min(MAX_LOBBY_PER_SIDE, (d - 2 * SIDE_MARGIN) / MIN_PITCH);
        int perSide;
        boolean back;
        if (o.portals() < 0) {
            perSide = 0;
            back = false;
        } else if (o.portals() == 0) {
            perSide = Math.clamp((d - 17) / 16, 1, maxPerSide);
            back = true;
        } else {
            perSide = o.portals() / 2;
            back = o.portals() % 2 == 1;
        }
        boolean capped = perSide > maxPerSide;
        perSide = Math.min(perSide, maxPerSide);
        int portals = 2 * perSide + (back ? 1 : 0);

        JsonArray s = new JsonArray();
        shell(s, hw, z1, h);
        // Fensterband rundum, Ecken bleiben Mauer; über dem Eingang erst oberhalb seines Rahmens (eh < h - 4).
        int band0 = h - 4;
        int band1 = h - 2;
        s.add(box("fill", "glass", -hw, band0, 3, -hw, band1, z1 - 3));
        s.add(box("fill", "glass", hw, band0, 3, hw, band1, z1 - 3));
        s.add(box("fill", "glass", -hw + 3, band0, z1, hw - 3, band1, z1));
        s.add(box("fill", "glass", -hw + 3, band0, 0, hw - 3, band1, 0));
        // Dach aus Platten, darin das Loch für die Kuppel über dem Platz.
        s.add(box("fill", ROOF, -hw, h + 1, 0, hw, h + 1, z1));
        s.add(disc("air", 0, h + 1, cz, dome - 1, false));
        JsonObject glassDome = sphere("glass", 0, h + 1, cz, dome);
        s.add(glassDome);
        // Weg vom Eingang zum Hauptportal, darüber die Lichtringe (sie kreuzen den Weg).
        s.add(box("fill", "polished_blackstone", -1, -1, 1, 1, -1, z1 - 2));
        int half = Math.min(hw, cz);
        for (int k = 4; half - k >= plaza + 4; k += 8) {
            s.add(box("walls", LIGHT, -hw + k, -1, k, hw - k, -1, z1 - k));
        }
        // Platz: Quarzscheibe, Lichtring am Rand, flache Plattform aus Platten in der Mitte (Spawn).
        s.add(disc("smooth_quartz", 0, -1, cz, plaza, false));
        s.add(disc(LIGHT, 0, -1, cz, plaza, true));
        s.add(disc("smooth_quartz_slab", 0, 0, cz, 2, false));
        // Vier Säulen 2 × 2 um den Platz, bis unters Dach – nur mit Platz: In einer 25 breiten Lobby stünden sie sonst
        // auf den Teppichen vor den Seitenportalen (x bis hw − 4), in einer 25 tiefen vor dem Eingang.
        int p = plaza + 3;
        if (p + 5 < hw && p + 6 <= cz) {
            for (int sx : new int[] {-1, 1}) {
                for (int sz : new int[] {-1, 1}) {
                    int xa = sx * p;
                    int za = cz + sz * p;
                    s.add(box("fill", "quartz_pillar", xa, 0, za, xa + sx, h, za + sz));
                }
            }
        }
        // Eingang vorn: Rahmen aus gemeißeltem Stein, Öffnung 5 bzw. 7 breit.
        int e = w >= 49 ? 3 : 2;
        int eh = Math.min(6, h - 5);
        s.add(box("fill", CORNICE, -e - 1, -1, 0, e + 1, eh, 0));
        s.add(box("fill", "air", -e, 0, 0, e, eh - 1, 0));
        // Portalrahmen: Seitenwände (Ebene eine Reihe vor der Wand), Mitte je Feld gleichmäßig verteilt.
        int span = d - 2 * SIDE_MARGIN;
        for (int side : new int[] {-1, 1}) {
            int x = side * (hw - 1);
            for (int i = 0; i < perSide; i++) {
                int zc = SIDE_MARGIN + span * (2 * i + 1) / (2 * perSide);
                sidePortal(s, x, zc);
                s.add(box("fill", "purple_carpet", side * (hw - 2), 0, zc - 1, side * (hw - 4), 0, zc + 1));
            }
        }
        if (back) {
            backPortal(s, z1 - 1, h >= 10);
            s.add(box("fill", "purple_carpet", -2, 0, z1 - 4, 2, 0, z1 - 2));
        }
        String design = "Spawn lobby " + w + "x" + d + ", " + (h + 2) + " high, glass dome over the central plaza, "
                + portals + " unlit obsidian portal frame(s), entrance front";
        String reply = "Spawn-Lobby " + w + " × " + d + " mit Glaskuppel über dem Platz in der Mitte"
                + (portals > 0 ? " und " + portals + " Portalrahmen aus Obsidian (noch nicht entzündet)" : "")
                + (capped ? ", mehr Portale passen bei dieser Größe nicht" : "") + ".";
        return Optional.of(plan(design, reply, s));
    }

    // ---------------------------------------------------------------- Portalhalle

    private static int hallWidth(Size s) {
        return switch (s) {
            case SMALL -> 13;
            case NORMAL -> 17;
            case BIG -> 19;
            case HUGE -> 23;
        };
    }

    private static int hallPerSide(Size s) {
        return switch (s) {
            case SMALL -> 2;
            case NORMAL -> 3;
            case BIG -> 4;
            case HUGE -> 5;
        };
    }

    static Optional<String> portalHall(Order o, int maxExtent) {
        int w = o.width() > 0 ? odd(o.width(), MIN_HALL_WIDTH, MAX_HALL_WIDTH) : hallWidth(o.size());
        int hw = w / 2;
        int perSide;
        boolean back;
        if (o.portals() < 0) {
            perSide = 0;
            back = false;
        } else if (o.portals() == 0) {
            perSide = hallPerSide(o.size());
            back = true;
        } else {
            perSide = o.portals() / 2;
            back = o.portals() % 2 == 1;
        }
        // Tiefe aus der Portalzahl (Abstand genau MIN_PITCH); „31x96“ gibt sie vor, dann verteilen sich die Portale
        // über die ganze Länge, statt vorn zu stehen und hinten eine leere Halle zu lassen.
        int d = o.depth() > 0 ? Math.clamp(o.depth(), MIN_HALL_DEPTH, MAX_HALL_DEPTH)
                : Math.clamp(MIN_PITCH * perSide + 2 * SIDE_MARGIN + 1, MIN_HALL_DEPTH, MAX_HALL_DEPTH);
        int span = d - 2 * SIDE_MARGIN - 1;
        int maxPerSide = Math.min(MAX_HALL_PER_SIDE, span / MIN_PITCH);
        boolean capped = perSide > maxPerSide;
        perSide = Math.min(perSide, maxPerSide);
        int portals = 2 * perSide + (back ? 1 : 0);
        int h = 7 + w / 6;                            // 13 → 9, 17 → 9, 19 → 10, 23 → 10, 31 → 12
        int z1 = d - 1;
        if (Math.max(w, d) > maxExtent) {
            return Optional.empty();
        }

        JsonArray s = new JsonArray();
        shell(s, hw, z1, h);
        // Obergaden: Fensterband hoch in beiden Seitenwänden, über den Portalrahmen (die enden auf y 4).
        s.add(box("fill", "glass", -hw, h - 3, 2, -hw, h - 2, z1 - 2));
        s.add(box("fill", "glass", hw, h - 3, 2, hw, h - 2, z1 - 2));
        // Dach aus Platten mit Oberlicht über dem Teppich.
        s.add(box("fill", ROOF, -hw, h + 1, 0, hw, h + 1, z1));
        s.add(box("fill", "glass", -2, h + 1, 2, 2, h + 1, z1 - 2));
        // Roter Teppich, daneben Lichtleisten im Boden; ab 25 breit eine zweite Leiste näher an den Portalen.
        s.add(box("fill", "red_carpet", -1, 0, 1, 1, 0, z1 - 3));
        s.add(box("fill", LIGHT, -3, -1, 1, -3, -1, z1 - 1));
        s.add(box("fill", LIGHT, 3, -1, 1, 3, -1, z1 - 1));
        if (hw >= 12) {
            s.add(box("fill", LIGHT, -hw + 4, -1, 1, -hw + 4, -1, z1 - 1));
            s.add(box("fill", LIGHT, hw - 4, -1, 1, hw - 4, -1, z1 - 1));
        }
        // Eingang vorn.
        int eh = Math.min(5, h - 4);
        s.add(box("fill", CORNICE, -3, -1, 0, 3, eh, 0));
        s.add(box("fill", "air", -2, 0, 0, 2, eh - 1, 0));
        // Portale gleichmäßig zwischen Eingangs- und Rückwandbereich; violetter Teppich vom roten zum Portal.
        for (int side : new int[] {-1, 1}) {
            int x = side * (hw - 1);
            for (int i = 0; i < perSide; i++) {
                int zc = SIDE_MARGIN + span * (2 * i + 1) / (2 * perSide);
                sidePortal(s, x, zc);
                s.add(box("fill", "purple_carpet", side * 2, 0, zc - 1, side * (hw - 2), 0, zc + 1));
            }
        }
        if (back) {
            backPortal(s, z1 - 1, h >= 10);
        }
        String design = "Portal hall " + w + "x" + d + ", " + (h + 2) + " high, red carpet aisle, skylight, "
                + portals + " unlit obsidian portal frame(s), entrance front";
        String reply = "Portalhalle " + w + " × " + d + " mit rotem Teppich und Oberlicht"
                + (portals > 0 ? ", " + portals + " Portalrahmen aus Obsidian (noch nicht entzündet)" : "")
                + (capped ? ", mehr Portale passen bei dieser Größe nicht" : "") + ".";
        return Optional.of(plan(design, reply, s));
    }

    // ---------------------------------------------------------------- Gemeinsam

    /** Fundament, Boden, Innenraum, Mauer, Sockel und Gesims; Mauer von y 0 bis h, Dach kommt auf h + 1. */
    private static void shell(JsonArray s, int hw, int z1, int h) {
        s.add(box("fill", "stone", -hw, -3, 0, hw, -2, z1));
        s.add(box("fill", FLOOR, -hw, -1, 0, hw, -1, z1));
        s.add(box("fill", "air", -hw + 1, 0, 1, hw - 1, h, z1 - 1));
        s.add(box("walls", WALL, -hw, 0, 0, hw, h, z1));
        s.add(box("walls", PLINTH, -hw, 0, 0, hw, 0, z1));
        s.add(box("walls", CORNICE, -hw, h, 0, hw, h, z1));
    }

    /** Rahmen außen 5 breit, 6 hoch in der Ebene x, innen 3 × 4 frei; unterste Reihe auf Bodenhöhe (y −1). */
    private static void sidePortal(JsonArray s, int x, int zc) {
        s.add(box("fill", FRAME, x, -1, zc - 2, x, 4, zc + 2));
        s.add(box("fill", "air", x, 0, zc - 1, x, 3, zc + 1));
    }

    /** Rückwand: groß 7 × 8 (innen 5 × 6), sonst wie an den Seiten 5 × 6 (innen 3 × 4). */
    private static void backPortal(JsonArray s, int z, boolean large) {
        int half = large ? 3 : 2;
        int top = large ? 6 : 4;
        s.add(box("fill", FRAME, -half, -1, z, half, top, z));
        s.add(box("fill", "air", -half + 1, 0, z, half - 1, top - 1, z));
    }

    // ---------------------------------------------------------------- JSON

    /** reply höchstens 200 Zeichen: BuildService.acceptPlan kürzt danach (Texts.clean), mitten im Satz. */
    private static String plan(String design, String reply, JsonArray steps) {
        JsonObject p = new JsonObject();
        p.addProperty("design", design);
        p.addProperty("reply", reply);
        p.add("steps", steps);
        return p.toString();
    }

    private static JsonObject box(String op, String block, int x0, int y0, int z0, int x1, int y1, int z1) {
        JsonObject s = new JsonObject();
        s.addProperty("op", op);
        s.addProperty("block", block);
        s.add("from", vec(x0, y0, z0));
        s.add("to", vec(x1, y1, z1));
        return s;
    }

    /** Scheibe (height 1) bzw. Ring (hollow) um pos. */
    private static JsonObject disc(String block, int x, int y, int z, int radius, boolean ring) {
        JsonObject s = new JsonObject();
        s.addProperty("op", "cylinder");
        s.addProperty("block", block);
        s.add("pos", vec(x, y, z));
        s.addProperty("radius", radius);
        s.addProperty("height", 1);
        if (ring) {
            s.addProperty("hollow", true);
        }
        return s;
    }

    /** Halbkugel als Schale (hollow + dome), Unterkante auf pos. */
    private static JsonObject sphere(String block, int x, int y, int z, int radius) {
        JsonObject s = new JsonObject();
        s.addProperty("op", "sphere");
        s.addProperty("block", block);
        s.add("pos", vec(x, y, z));
        s.addProperty("radius", radius);
        s.addProperty("hollow", true);
        s.addProperty("dome", true);
        return s;
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    /** Nur für Tests: alle Schritte als Liste, um sie zu zählen. */
    static List<String> stepOps(String json) {
        List<String> out = new ArrayList<>();
        com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("steps")
                .forEach(e -> out.add(e.getAsJsonObject().get("op").getAsString()));
        return out;
    }
}
