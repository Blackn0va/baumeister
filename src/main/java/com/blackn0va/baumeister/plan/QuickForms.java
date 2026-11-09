package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schnellaufträge für die Bauformen aus Structures, ohne LLM: „bau eine Burg“, „eine Brücke über 20 Blöcke“, „ein
 * Garten“, „eine große Tanne“, „eine Stadtmauer 30 Blöcke lang aus Sandstein“.
 *
 * Warum: Die Form ist ein einziger Plan-Schritt, dessen Maße aus dem Auftrag folgen. Das LLM braucht auf dem
 * Laptop-PC 42–102 s für einen Plan (gemessen 2026-10-28) und kann beim Ausfüllen nur Fehler machen; ohne LLM geht
 * der Auftrag in Millisekunden durch dieselben Prüfungen (Parser, Compiler, Überbau-Schutz, Rechte) und auch dann,
 * wenn der Tunnel zum LLM-PC unten ist.
 *
 * Bewusst eng wie QuickOrders: Ein Treffer nur mit genau einem Formwort und sonst ausschließlich Füllwörtern, Größe
 * („klein“, „groß“, „riesig“, „20x30“, „über 20 Blöcke“, „8 Blöcke hoch“) und einem Material nach „aus“. „Eine Burg
 * mit Wassergraben“ und „eine Brücke über den Fluss“ bleiben beim LLM – lieber langsam mit Graben als schnell ohne.
 * Wer die Füllwortprüfung lockert, baut Aufträge mit Zusatzwünschen still ohne diese Wünsche.
 */
final class QuickForms {

    /** Formwort → op, Vorgabe für block (null = Standard der Form) und style (leer = Standard der Form). */
    private record Form(Op op, String block, String style) {
    }

    private static final Map<String, Form> WORDS = Map.ofEntries(
            Map.entry("burg", new Form(Op.CASTLE, null, "")),
            Map.entry("ritterburg", new Form(Op.CASTLE, null, "")),
            Map.entry("festung", new Form(Op.CASTLE, null, "")),
            Map.entry("mauer", new Form(Op.WALL, null, "")),
            Map.entry("stadtmauer", new Form(Op.WALL, null, "")),
            Map.entry("burgmauer", new Form(Op.WALL, null, "")),
            Map.entry("wehrmauer", new Form(Op.WALL, null, "")),
            Map.entry("schutzmauer", new Form(Op.WALL, null, "")),
            Map.entry("bruecke", new Form(Op.BRIDGE, null, "")), // umlaut-ok: normalisierte Form
            Map.entry("steinbruecke", new Form(Op.BRIDGE, "stone_bricks", "")), // umlaut-ok
            Map.entry("holzbruecke", new Form(Op.BRIDGE, "oak_planks", "")), // umlaut-ok
            Map.entry("bogenbruecke", new Form(Op.BRIDGE, null, "arch")), // umlaut-ok
            Map.entry("balkenbruecke", new Form(Op.BRIDGE, null, "beam")), // umlaut-ok
            Map.entry("garten", new Form(Op.GARDEN, null, "")),
            Map.entry("blumengarten", new Form(Op.GARDEN, null, "")),
            Map.entry("ziergarten", new Form(Op.GARDEN, null, "")),
            Map.entry("baum", new Form(Op.TREE, null, "")),
            Map.entry("laubbaum", new Form(Op.TREE, "oak_log", "round")),
            Map.entry("eiche", new Form(Op.TREE, "oak_log", "round")),
            Map.entry("birke", new Form(Op.TREE, "birch_log", "round")),
            Map.entry("kirschbaum", new Form(Op.TREE, "cherry_log", "round")),
            Map.entry("nadelbaum", new Form(Op.TREE, "spruce_log", "cone")),
            Map.entry("tanne", new Form(Op.TREE, "spruce_log", "cone")),
            Map.entry("tannenbaum", new Form(Op.TREE, "spruce_log", "cone")),
            Map.entry("fichte", new Form(Op.TREE, "spruce_log", "cone")));

    /**
     * Nur für die Fakten an das LLM (formsIn): Mehrzahl und echte Zusammensetzungen der Formwörter. Vorher zählte
     * jedes Wort auf „-garten“, „-baum“ oder „-brücke“ – „Kindergarten“ und „Biergarten“ wurden zum garden,
     * „Schlagbaum“ zum tree (Prüfer-Befund 2026-10-29). Eine feste Liste verfehlt höchstens ein seltenes Kompositum,
     * und dann fehlt nur der Hinweis; ein falscher Hinweis schreibt dem LLM die falsche Form vor. Alle Wörter in der
     * Form nach RequestHints.normalize (ohne Umlaut).
     */
    private static final Map<String, Op> MORE_FORM_WORDS = Map.ofEntries(
            Map.entry("burgen", Op.CASTLE), Map.entry("festungen", Op.CASTLE), Map.entry("ritterburgen", Op.CASTLE),
            Map.entry("wasserburg", Op.CASTLE), Map.entry("wasserburgen", Op.CASTLE),
            Map.entry("mauern", Op.WALL), Map.entry("stadtmauern", Op.WALL), Map.entry("burgmauern", Op.WALL),
            Map.entry("wehrmauern", Op.WALL), Map.entry("schutzmauern", Op.WALL),
            Map.entry("bruecken", Op.BRIDGE), Map.entry("steinbruecken", Op.BRIDGE), // umlaut-ok
            Map.entry("holzbruecken", Op.BRIDGE), Map.entry("bogenbruecken", Op.BRIDGE), // umlaut-ok
            Map.entry("balkenbruecken", Op.BRIDGE), Map.entry("zugbruecke", Op.BRIDGE), // umlaut-ok
            Map.entry("zugbruecken", Op.BRIDGE), Map.entry("haengebruecke", Op.BRIDGE), // umlaut-ok
            Map.entry("fussgaengerbruecke", Op.BRIDGE), // umlaut-ok
            Map.entry("gaerten", Op.GARDEN), Map.entry("blumengaerten", Op.GARDEN), // umlaut-ok
            Map.entry("gemuesegarten", Op.GARDEN), Map.entry("kraeutergarten", Op.GARDEN), // umlaut-ok
            Map.entry("rosengarten", Op.GARDEN), Map.entry("obstgarten", Op.GARDEN),
            Map.entry("vorgarten", Op.GARDEN), Map.entry("schlossgarten", Op.GARDEN),
            Map.entry("baeume", Op.TREE), Map.entry("eichen", Op.TREE), Map.entry("birken", Op.TREE), // umlaut-ok
            Map.entry("tannen", Op.TREE), Map.entry("fichten", Op.TREE),
            Map.entry("tannenbaeume", Op.TREE), Map.entry("laubbaeume", Op.TREE), // umlaut-ok
            Map.entry("nadelbaeume", Op.TREE), Map.entry("kirschbaeume", Op.TREE), // umlaut-ok
            Map.entry("apfelbaum", Op.TREE), Map.entry("apfelbaeume", Op.TREE), // umlaut-ok
            Map.entry("obstbaum", Op.TREE), Map.entry("obstbaeume", Op.TREE), // umlaut-ok
            Map.entry("weihnachtsbaum", Op.TREE));

    /**
     * Pyramide (2026-11-08): Im Spiel kam auf „Vera, baue mir eine Pyramide“ vom kleinen Modell (Llama 3.2 3B) ein
     * Mauerstreifen mit Steinziegelsäule, 66 Blöcke – nichts davon eine Pyramide. Eine Stufenpyramide ist reine
     * Rechnung (Lage für Lage ein Ring, der nach oben schrumpft) und braucht kein Modell. Kein eigenes op: Sie wird aus
     * walls-Schritten zusammengesetzt, damit Grammatik des Hosts, Parser und Client-Mod unverändert bleiben.
     */
    private static final Set<String> PYRAMID_WORDS = Set.of("pyramide", "stufenpyramide", "mayapyramide");

    private enum Size {
        SMALL, MEDIUM, LARGE, HUGE
    }

    private static final Map<String, Size> SIZE_WORDS = Map.ofEntries(
            Map.entry("klein", Size.SMALL), Map.entry("kleine", Size.SMALL), Map.entry("kleinen", Size.SMALL),
            Map.entry("kleiner", Size.SMALL), Map.entry("kleines", Size.SMALL),
            Map.entry("gross", Size.LARGE), Map.entry("grosse", Size.LARGE), Map.entry("grossen", Size.LARGE),
            Map.entry("grosser", Size.LARGE), Map.entry("grosses", Size.LARGE),
            Map.entry("hohe", Size.LARGE), Map.entry("hohen", Size.LARGE), Map.entry("hoher", Size.LARGE),
            Map.entry("hohes", Size.LARGE),
            Map.entry("riesig", Size.HUGE), Map.entry("riesige", Size.HUGE), Map.entry("riesigen", Size.HUGE),
            Map.entry("riesiger", Size.HUGE), Map.entry("riesiges", Size.HUGE));

    /** Füllwörter wie QuickOrders plus die Wörter, die eine Maßangabe einleiten („über 20 Blöcke“, „von 30 m“). */
    private static final Set<String> FILLER = Set.of("bau", "baue", "bitte", "mir", "uns", "mal", "ein", "eine",
            "einen", "das", "die", "der", "hier", "mit", "hin", "ueber", "von", "spannweite", "laenge"); // umlaut-ok

    /** Grundfläche „25x30“ wie in QuickOrders. */
    private static final Pattern AREA = Pattern.compile("\\b(\\d{1,3}) ?x ?(\\d{1,3})\\b");

    /**
     * Zahl plus Einheit, wahlweise mit „lang/hoch/breit“ dahinter („20 Blöcke“, „8 Blöcke hoch“, „5 m breit“; das
     * Muster sieht sie nach RequestHints.normalize ohne Umlaut). Ohne Zusatz ist es eine Länge – bei der Brücke
     * („über 20 Blöcke“) die freie Spannweite, mit „lang“ die ganze Brücke.
     */
    private static final Pattern MEASURE = Pattern.compile(
            "\\b(\\d{1,3}) ?(?:bloecke|bloecken|block|meter|m)\\b" // umlaut-ok: normalisierte Form
            + "(?: (lang|lange|langen|langer|langes|hoch|hohe|hohen|hoher|hohes"
            + "|breit|breite|breiten|breiter|breites)\\b)?");

    private QuickForms() {
    }

    /**
     * @param text      Auftrag, schon durch RequestHints.normalize
     * @param maxExtent bau.max-ausdehnung – Maße werden darauf gekappt, damit der Compiler nicht ablehnt
     * @return Plan-JSON mit genau einem Schritt, oder leer, wenn der Auftrag ans LLM gehört
     */
    static Optional<String> match(String text, int maxExtent) {
        // Idempotent; hier noch einmal für Aufrufer, die nur normalize ausführen („Burg 30 mal 30“ → 30x30).
        text = RequestHints.sizes(text);
        Integer length = null;
        boolean lengthSaid = false;
        Integer height = null;
        Integer width = null;
        Matcher mm = MEASURE.matcher(text);
        StringBuilder rest = new StringBuilder();
        while (mm.find()) {
            int n = Integer.parseInt(mm.group(1));
            String kind = mm.group(2) == null ? "lang" : mm.group(2);
            if (kind.startsWith("hoch") || kind.startsWith("hoh")) {
                if (height != null) {
                    return Optional.empty();
                }
                height = n;
            } else if (kind.startsWith("breit")) {
                if (width != null) {
                    return Optional.empty();
                }
                width = n;
            } else {
                if (length != null) {
                    return Optional.empty();
                }
                length = n;
                lengthSaid = mm.group(2) != null;
            }
            mm.appendReplacement(rest, " ");
        }
        mm.appendTail(rest);
        int[] area = null;
        Matcher am = AREA.matcher(rest.toString());
        StringBuilder rest2 = new StringBuilder();
        while (am.find()) {
            if (area != null) {
                return Optional.empty();
            }
            area = new int[] {Integer.parseInt(am.group(1)), Integer.parseInt(am.group(2))};
            am.appendReplacement(rest2, " ");
        }
        am.appendTail(rest2);

        List<String> tokens = RequestHints.tokens(RequestHints.normalize(rest2.toString()));
        boolean[] used = new boolean[tokens.size()];
        String material = null;
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).equals("aus")) {
                continue;
            }
            Optional<String> m = RequestHints.material(tokens.get(i + 1));
            if (m.isPresent()) {
                if (material != null) {
                    return Optional.empty();
                }
                material = m.get();
                used[i] = true;
                used[i + 1] = true;
            }
        }
        Form form = null;
        Size size = null;
        boolean pyramid = false;
        for (int i = 0; i < tokens.size(); i++) {
            if (used[i]) {
                continue;
            }
            String tok = tokens.get(i);
            if (PYRAMID_WORDS.contains(tok)) {
                if (pyramid || form != null) {
                    return Optional.empty();
                }
                pyramid = true;
            } else if (WORDS.containsKey(tok)) {
                if (pyramid) {
                    return Optional.empty();
                }
                if (form != null) {
                    return Optional.empty();
                }
                form = WORDS.get(tok);
            } else if (SIZE_WORDS.containsKey(tok)) {
                if (size != null) {
                    return Optional.empty();
                }
                size = SIZE_WORDS.get(tok);
            } else if (!FILLER.contains(tok)) {
                return Optional.empty();
            }
        }
        if (pyramid) {
            return material != null && RequestHints.isWindow(material) ? Optional.empty()
                    : pyramid(size == null ? Size.MEDIUM : size, area, length, width, height, material, maxExtent);
        }
        if (form == null || material != null && RequestHints.isWindow(material)) {
            return Optional.empty();
        }
        Size s = size == null ? Size.MEDIUM : size;
        String block = material != null ? material : form.block();
        return switch (form.op()) {
            // Fläche UND Seitenmaß: unklar, welches gilt. Vorher gewann still die Fläche (Prüfer-Befund 2026-10-29).
            case CASTLE -> area != null && (length != null || width != null) ? Optional.empty()
                    : castle(s, area, length, width, height, block, form.style(), maxExtent);
            case WALL -> area != null ? Optional.empty() : wall(s, length, height, width, block, maxExtent);
            case BRIDGE -> area != null || height != null ? Optional.empty()
                    : bridge(s, length, lengthSaid, width, block, form.style(), maxExtent);
            case GARDEN -> height != null || area != null && (length != null || width != null) ? Optional.empty()
                    : garden(s, area, length, width, block, maxExtent);
            case TREE -> area != null || length != null || width != null ? Optional.empty()
                    : tree(s, height, block, form.style(), maxExtent);
            default -> Optional.empty();
        };
    }

    /**
     * Bauformen, die ein Wort im Auftrag meint – für die Fakten an das LLM (RequestHints). Nur ganze Wörter aus
     * WORDS und MORE_FORM_WORDS. Ein Wort direkt nach „aus“ ist Material, keine Form: „ein Tisch aus Birke“ ist kein
     * Baum.
     */
    static Set<Op> formsIn(List<String> tokens) {
        Set<Op> out = EnumSet.noneOf(Op.class);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (i > 0 && tokens.get(i - 1).equals("aus")) {
                continue;
            }
            Form f = WORDS.get(t);
            if (f != null) {
                out.add(f.op());
            } else if (MORE_FORM_WORDS.containsKey(t)) {
                out.add(MORE_FORM_WORDS.get(t));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Pläne

    /**
     * „breit“ ist die Torseite (x), „lang“ die Tiefe (z); nur „lang“ allein ergibt wie bisher ein Quadrat. Vorher
     * fiel „breit“ still weg: „eine Burg 40 Blöcke breit“ wurde 25×25 (Prüfer-Befund 2026-10-29).
     */
    private static Optional<String> castle(Size size, int[] area, Integer length, Integer width, Integer height,
            String block, String style, int maxExtent) {
        int maxSide = Math.min(Structures.MAX_CASTLE_SIDE, maxExtent);
        // Türme und Torstube ragen 6 über die Mauer: Die Höhe muss mit ihnen in max-ausdehnung passen.
        int maxHeight = Math.min(Structures.MAX_STRUCTURE_HEIGHT, maxExtent - 6);
        if (maxSide < Structures.MIN_CASTLE_SIDE || maxHeight < Structures.MIN_CASTLE_HEIGHT) {
            return Optional.empty();
        }
        int side = switch (size) {
            case SMALL -> 17;
            case MEDIUM -> 25;
            case LARGE -> 37;
            case HUGE -> 49;
        };
        int wall = switch (size) {
            case SMALL -> 5;
            case MEDIUM -> 6;
            case LARGE -> 8;
            case HUGE -> 10;
        };
        int w = area != null ? area[0] : width != null ? width : length != null ? length : side;
        int d = area != null ? area[1] : length != null ? length : side;
        w = Math.clamp(w, Structures.MIN_CASTLE_SIDE, maxSide);
        d = Math.clamp(d, Structures.MIN_CASTLE_SIDE, maxSide);
        int h = Math.clamp(height != null ? height : wall, Structures.MIN_CASTLE_HEIGHT, maxHeight);
        String material = block != null ? block : "stone_bricks";
        JsonObject step = step("castle", material);
        step.add("from", vec(0, 0, 0));
        step.add("to", vec(w - 1, 0, d - 1));
        step.addProperty("height", h);
        step.addProperty("facing", "front");
        style(step, style);
        return Optional.of(plan("castle " + w + "x" + d + ", walls " + h + " high, " + material
                + ", gate front middle", "Eine Burg mit Ecktürmen und Torhaus, " + w + "×" + d
                + " – ich fange sofort an!", step));
    }

    private static Optional<String> wall(Size size, Integer length, Integer height, Integer width, String block,
            int maxExtent) {
        int len = length != null ? length : switch (size) {
            case SMALL -> 12;
            case MEDIUM -> 20;
            case LARGE -> 32;
            case HUGE -> 48;
        };
        int h = height != null ? height : switch (size) {
            case SMALL -> 4;
            case MEDIUM -> 5;
            case LARGE -> 7;
            case HUGE -> 9;
        };
        int maxHeight = Math.min(Structures.MAX_STRUCTURE_HEIGHT, maxExtent - 2);
        if (maxExtent < 2 || maxHeight < 2) {
            return Optional.empty();
        }
        len = Math.clamp(len, 2, Math.min(128, maxExtent));
        h = Math.clamp(h, 2, maxHeight);
        int t = Math.clamp(width != null ? width : Structures.DEFAULT_WIDTH, 1, Structures.MAX_WALL_WIDTH);
        String material = block != null ? block : "stone_bricks";
        // Quer vor dem Spieler, Außenseite hinten: Wehrgang und Leiter liegen auf seiner Seite (z 0 bis −t).
        JsonObject step = step("wall", material);
        step.add("from", vec(0, 0, t - 1));
        step.add("to", vec(len - 1, 0, t - 1));
        step.addProperty("height", h);
        step.addProperty("facing", "back");
        step.addProperty("width", t);
        return Optional.of(plan("wall " + len + " long, " + h + " high, " + t + " thick, " + material
                + ", battlements outside (back), walkway and ladder in front", "Eine Mauer, " + len
                + " Blöcke lang, mit Zinnen und Wehrgang!", step));
    }

    /**
     * @param length     Maß aus dem Auftrag
     * @param lengthSaid true bei „N Blöcke lang“ (ganze Brücke), false bei „über N Blöcke“ (freie Spannweite). Vorher
     *                   war jede Länge die Spannweite: „Holzbrücke 12 Blöcke lang“ wurde 14 lang, die Mauer mit
     *                   demselben Wort aber 30 (Prüfer-Befund 2026-10-29).
     */
    private static Optional<String> bridge(Size size, Integer length, boolean lengthSaid, Integer width, String block,
            String style, int maxExtent) {
        Integer span = length == null ? null : lengthSaid ? length - 2 : length;
        int gap = span != null ? span : switch (size) {
            case SMALL -> 9;
            case MEDIUM -> 15;
            case LARGE -> 25;
            case HUGE -> 41;
        };
        int maxLen = Math.min(128, maxExtent);
        if (maxLen < Structures.MIN_BRIDGE_LENGTH) {
            return Optional.empty();
        }
        // Spannweite = freie Strecke; je ein Block Auflager an beiden Ufern kommt dazu.
        int len = Math.clamp(gap + 2L, Structures.MIN_BRIDGE_LENGTH, maxLen);
        int w = Math.clamp(width != null ? width : Structures.DEFAULT_WIDTH, 1, Structures.MAX_BRIDGE_WIDTH);
        String material = block != null ? block : "stone_bricks";
        JsonObject step = step("bridge", material);
        step.add("from", vec(0, 0, 0));
        step.add("to", vec(0, 0, len - 1));
        step.addProperty("width", w);
        style(step, style);
        return Optional.of(plan("bridge forward from z0 to z" + (len - 1) + ", span " + (len - 2) + ", walkway " + w
                + ", " + material, "Eine Brücke über " + (len - 2) + " Blöcke – kommt sofort!", step));
    }

    /** „breit“ ist die Torseite (x), „lang“ die Tiefe (z) – vorher fiel „breit“ still weg (Garten 11×9). */
    private static Optional<String> garden(Size size, int[] area, Integer length, Integer width, String block,
            int maxExtent) {
        int maxSide = Math.min(Structures.MAX_GARDEN_SIDE, maxExtent);
        if (maxSide < Structures.MIN_GARDEN_SIDE) {
            return Optional.empty();
        }
        int[] base = switch (size) {
            case SMALL -> new int[] {7, 7};
            case MEDIUM -> new int[] {11, 9};
            case LARGE -> new int[] {17, 13};
            case HUGE -> new int[] {25, 19};
        };
        int w = Math.clamp(area != null ? area[0] : width != null ? width : base[0], Structures.MIN_GARDEN_SIDE,
                maxSide);
        int d = Math.clamp(area != null ? area[1] : length != null ? length : base[1], Structures.MIN_GARDEN_SIDE,
                maxSide);
        String fence = block != null ? block : "oak_fence";
        JsonObject step = step("garden", fence);
        step.add("from", vec(0, 0, 0));
        step.add("to", vec(w - 1, 0, d - 1));
        step.addProperty("facing", "front");
        return Optional.of(plan("garden " + w + "x" + d + ", fence " + fence + ", gate front middle",
                "Ein Garten mit Wegen, Beeten und Zaun, " + w + "×" + d + "!", step));
    }

    private static Optional<String> tree(Size size, Integer height, String block, String style, int maxExtent) {
        String log = block != null ? block : "oak_log";
        String wood = Structures.woodOf(log);
        if (wood == null) {
            // „ein Baum aus Stein“: Vorher still eine Eiche. Was das heißen soll, entscheidet das LLM.
            return Optional.empty();
        }
        boolean cone = style.isEmpty() ? "spruce".equals(wood) : style.equals("cone");
        int h = height != null ? height : switch (size) {
            case SMALL -> cone ? 6 : 5;
            case MEDIUM -> cone ? 9 : 7;
            case LARGE -> cone ? 16 : 12;
            case HUGE -> cone ? 24 : 20;
        };
        int maxHeight = Math.min(Structures.MAX_TREE_HEIGHT, maxExtent - 1);
        if (maxHeight < Structures.MIN_TREE_HEIGHT) {
            return Optional.empty();
        }
        h = Math.clamp(h, Structures.MIN_TREE_HEIGHT, maxHeight);
        JsonObject step = step("tree", log);
        // 8 vor dem Ursprung: Die Krone (Radius bis 7, beim dicken Stamm 8) reicht nicht bis zum Spieler zurück.
        step.add("pos", vec(0, 0, 8));
        step.addProperty("height", h);
        style(step, style);
        String kind = cone ? "Nadelbaum" : "Laubbaum";
        return Optional.of(plan("tree " + (cone ? "cone" : "round") + " " + log + ", " + h + " high at x0 z8",
                "Ein " + kind + ", " + h + " Blöcke hoch – wächst sofort!", step));
    }

    /**
     * Stufenpyramide mit quadratischer, ungerader Grundseite s (Spitze genau in der Mitte), Höhe (s + 1) / 2, je Lage
     * ein Ring (walls). Gemessen 2026-11-08 (Plan-Prüfer streng): ein Ring je Lage 227 Blöcke, Accept ohne Korrektur
     * und ohne Befund; zwei Ringe (zuerst vermutet, „sonst schwebt jede Lage“) 395 Blöcke und genauso Accept – die
     * Stufen berühren sich über Kanten, durch die weder Licht noch Mobs kommen, die Hülle ist dicht und man kann sie
     * außen hinaufsteigen. Eingang vorne in der Mitte, 1 breit, 2 hoch. Seite aus „21 breit“, „21x21“ (nur gleiche
     * Seiten), „10 hoch“ (s = 2·h − 1) oder der Größe.
     */
    private static Optional<String> pyramid(Size size, int[] area, Integer length, Integer width, Integer height,
            String material, int maxExtent) {
        Integer side = null;
        if (area != null) {
            if (area[0] != area[1]) {
                return Optional.empty();  // rechteckig: das kann die Stufenpyramide nicht – LLM
            }
            side = area[0];
        }
        for (Integer v : new Integer[] {length, width}) {
            if (v != null) {
                if (side != null && !side.equals(v)) {
                    return Optional.empty();
                }
                side = v;
            }
        }
        if (height != null) {
            int fromHeight = 2 * height - 1;
            if (side != null && side != fromHeight) {
                return Optional.empty();
            }
            side = fromHeight;
        }
        int s = side != null ? side : switch (size) {
            case SMALL -> 9;
            case MEDIUM -> 15;
            case LARGE -> 25;
            case HUGE -> 41;
        };
        s = Math.clamp(s, 5, Math.min(81, maxExtent));
        if (s % 2 == 0) {
            s--;
        }
        String block = material != null ? material : "sandstone";
        int h = (s + 1) / 2;
        JsonArray steps = new JsonArray();
        for (int k = 0; k < h; k++) {
            JsonObject outer = step("walls", block);
            outer.add("from", vec(k, k, k));
            outer.add("to", vec(s - 1 - k, k, s - 1 - k));
            steps.add(outer);
        }
        int mid = s / 2;
        JsonObject door = step("fill", "air");
        door.add("from", vec(mid, 0, 0));
        // Bis z = 1: Dort steht der Ring der zweiten Lage – sonst wäre der Durchgang nur einen Block hoch.
        door.add("to", vec(mid, 1, 1));
        steps.add(door);
        JsonObject plan = new JsonObject();
        plan.addProperty("design", "step pyramid " + s + "x" + s + ", " + h + " high, " + block
                + ", entrance front middle");
        plan.addProperty("reply", "Eine Stufenpyramide, " + s + "×" + s + " und " + h + " Blöcke hoch – ich fange an!");
        plan.addProperty("mode", "new");
        plan.add("steps", steps);
        return Optional.of(plan.toString());
    }

    // ---------------------------------------------------------------- JSON

    private static JsonObject step(String op, String block) {
        JsonObject step = new JsonObject();
        step.addProperty("op", op);
        step.addProperty("block", block);
        return step;
    }

    private static void style(JsonObject step, String style) {
        if (!style.isEmpty()) {
            step.addProperty("style", style);
        }
    }

    private static String plan(String design, String reply, JsonObject step) {
        JsonObject plan = new JsonObject();
        plan.addProperty("design", design);
        plan.addProperty("reply", reply);
        plan.addProperty("mode", "new");
        JsonArray steps = new JsonArray();
        steps.add(step);
        plan.add("steps", steps);
        return plan.toString();
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }

    /** Für Tests: alle Formwörter – keins darf zugleich Bauteilname sein, sonst entschiede die Reihenfolge. */
    static List<String> words() {
        return new ArrayList<>(WORDS.keySet());
    }
}
