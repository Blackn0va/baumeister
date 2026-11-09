package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Anbau deterministisch vorbereiten: Das Plugin legt die Anschlusswand fest (Bauwerk, Seite, Wandebene, Bodenhöhe,
 * freie Tiefe), das LLM plant nur noch den Inhalt, und danach rückt das Plugin den Plan an diese Wand.
 *
 * Warum: Gemessen 2026-10-28 (Testserver, Qwen2.5-VL-7B) setzte das Modell eine Werkstatt ohne Anschlussstellen
 * 5 Blöcke neben das Haus; mit den vorgerechneten Anschlussstellen traf es die Wandebene x=4, baute aber AUF statt
 * neben die Wand – der Überbau-Schutz verwarf beide Versuche. Räumliches Planen reicht beim 7B für Anbauten nicht.
 * Vorher gab es drei Seiten je Bauwerk zur Auswahl; jetzt genau eine, und ein Plan, der daneben, darauf oder hinein
 * gerät, wird verschoben statt verworfen.
 *
 * Was hier NICHT passiert: den Überbau-Schutz ersetzen. {@link #fit} verschiebt nur und lässt allein Planblöcke auf
 * der gemeinsamen Wandebene weg, die einen bestehenden Wandblock gegen einen anderen tauschen würden (die Wand bleibt
 * stehen und wird geteilt). Alles andere – Durchgänge, Blöcke jenseits der Wandebene im Altbau, Nachbarbauten im Weg –
 * zählt danach unverändert in {@link PlanReview#checkOverbuild}. Weglassen kann einen Bestandsblock nie verändern,
 * also nie etwas freigeben, was der Schutz verbieten würde.
 *
 * Koordinaten wie {@link SiteSurvey}: lokal, x rechts, z vom Spieler weg. Die Seiten sind lokal; die Drehung in die
 * Welt macht wie immer SiteFrame/Orientation.
 */
public final class AttachPlanner {

    /**
     * Abgetrenntes „an“ am Satzende NUR mit einem Bau-Verb davor („bau rechts eine Werkstatt an“, trennbares
     * „anbauen“). Vorher brauchte das Muster zwei „an“ und hielt diesen Auftrag für einen Neubau – ohne Wandvorgabe
     * und ohne Anschlussprüfung (Gegenprobe AttachPlannerTest.separatedVerbAtTheEndIsAnAttachOrder).
     *
     * Das Bau-Verb ist Pflicht (Prüferbefund 2026-10-29): Ein nacktes „an$“ machte auch „Leg hinter dem Haus einen
     * Teich an“, „pflanz Blumen an“ und „mach das Licht an“ zum Anbau, und die Ausrichtung schob den Teich an die
     * Hauswand (Gegenprobe AttachPlannerTest.separableVerbsWithoutBuildingAreNoAttachOrder). Wer das Verb wieder
     * weglässt, verschiebt frei geplante Gärten und Teiche.
     */
    private static final String BUILD_VERB_AN = "\\b(bau|baue|setz|setze|stell|stelle)\\b.*\\ban\\s*[.!]?\\s*$";

    /**
     * Breites Muster für die Anschlussprüfung im Überbau-Schutz (PlanReview.checkOverbuild): Jeder Auftrag, der das
     * Bestehende fortsetzt („bau an das Haus an“, „daneben“, „erweitere“, „verbinde“, „dran“), muss ein Bauwerk
     * berühren. Aus BuildService übernommen (dort ATTACH_WORDS), ergänzt nur um {@link #BUILD_VERB_AN}.
     */
    public static final Pattern WORDS = Pattern.compile(
            "\\ban\\b.*\\ban\\b|" + BUILD_VERB_AN + "|anbau|anbauen|daneben|angebaut|erweiter|verbind|nebenan|dran\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Enges Muster für Wandvorgabe und Ausrichtung: nur „daneben anbauen“. Vorher entschied {@link #WORDS} auch
     * darüber – dann rückte {@link #fit} „Erweitere das Haus um ein Stockwerk“ vom Dach auf den Boden neben das Haus
     * und eine Brücke zwischen zwei Häusern („verbinde“) als Bodenstreifen in Haus 1 (Prüferbefund 2026-10-29,
     * Gegenproben storeyOnTopStaysOnTop, bridgeBetweenTwoHousesIsNotMoved). „erweitern“, „verbinden“ und „dran“
     * sagen nicht, WO der neue Teil hin soll; sie bekommen weiter nur die Anschlussprüfung, wie vor dieser Klasse.
     */
    static final Pattern BESIDE_WORDS = Pattern.compile(
            "\\ban\\b.*\\ban\\b|" + BUILD_VERB_AN + "|anbau|angebaut|daneben|nebenan", Pattern.CASE_INSENSITIVE);

    /**
     * Ausbau nach oben: Stockwerk, Etage, aufstocken, aufs Dach. Dort ist das Dach der richtige Ort, keine Wand
     * daneben – auch bei „bau oben ein Stockwerk an“, das {@link #BESIDE_WORDS} sonst trifft.
     */
    static final Pattern UP_WORDS = Pattern.compile(
            "stockwerk|etage|aufstock|obergeschoss|dachgeschoss|obendrauf|oben drauf|aufs dach|auf (das|dem) dach",
            Pattern.CASE_INSENSITIVE);

    /** Weiter als so viele Blöcke sucht choose keine freie Tiefe; ein Anbau ist selten breiter. */
    static final int MAX_DEPTH = 16;

    /**
     * Ab dieser freien Tiefe zählt nur noch die Vorzugsreihenfolge (rechts, links, hinten, vorne): Mehr Platz als
     * für einen üblichen Anbau macht eine Seite nicht besser, und „rechts“ ist für den Spieler vorhersehbar.
     */
    static final int ENOUGH_DEPTH = 8;

    /** Normalisierte Wörter (RequestHints.normalize) für eine ausdrücklich verlangte Seite; umlaut-ok. */
    private static final Set<String> RIGHT_WORDS = Set.of("rechts", "rechte", "rechten", "rechter");
    private static final Set<String> LEFT_WORDS = Set.of("links", "linke", "linken", "linker");
    private static final Set<String> BACK_WORDS = Set.of("hinten", "hinter", "dahinter", "hintere", "hinteren",
            "rueckseite"); // umlaut-ok: normalisierte Form
    private static final Set<String> FRONT_WORDS = Set.of("vorne", "vorn", "davor", "vordere", "vorderen",
            "vorderseite");

    private AttachPlanner() {
    }

    /** Seite des Bauwerks in lokalen Koordinaten; out ist die Richtung von der Wand weg nach außen. */
    public enum Side {
        RIGHT("right", "rechts", true, 1, "left"),
        LEFT("left", "links", true, -1, "right"),
        BACK("back", "hinten", false, 1, "front"),
        FRONT("front", "vorne", false, -1, "back");

        final String english;
        final String german;
        /** true: Wandebene ist ein x-Wert, Wand läuft entlang z. false: Ebene ist ein z-Wert, Wand entlang x. */
        final boolean xPlane;
        final int out;
        /** Welche Wand des neuen Teils die geteilte ist – für den Text an das LLM. */
        final String ownWall;

        Side(String english, String german, boolean xPlane, int out, String ownWall) {
            this.english = english;
            this.german = german;
            this.xPlane = xPlane;
            this.out = out;
            this.ownWall = ownWall;
        }

        public String german() {
            return german;
        }

        /** Koordinate quer zur Wand (x bei rechts/links, z bei hinten/vorne). */
        int across(Pos p) {
            return xPlane ? p.x() : p.z();
        }

        /** Koordinate entlang der Wand. */
        int along(Pos p) {
            return xPlane ? p.z() : p.x();
        }

        Pos at(int across, int y, int along) {
            return xPlane ? new Pos(across, y, along) : new Pos(along, y, across);
        }
    }

    /**
     * Die festgelegte Anschlusswand.
     *
     * @param structure Nummer des Bauwerks wie in SiteSurvey.describe
     * @param plane     Wandebene (x bei rechts/links, z bei hinten/vorne) – die äußere Wandschicht des Altbaus
     * @param spanMin   Wand entlang der Seite von spanMin bis spanMax
     * @param floorY    Bodenhöhe des Altbaus (unterste Schicht des Bauwerks)
     * @param topY      oberste Schicht des Altbaus
     * @param freeDepth freie Blöcke nach außen, ohne fremdes Bauwerk und innerhalb des Scans (höchstens MAX_DEPTH)
     * @param material  häufigster Block des Altbaus
     */
    public record Wall(int structure, Side side, int plane, int spanMin, int spanMax, int floorY, int topY,
            int freeDepth, String material) {

        /**
         * Harte Vorgabe für das LLM, englisch wie der System-Prompt und so kurz wie möglich: Prompt-Token kosten auf
         * der CPU Zeit. Nur Zahlen zum Abschreiben – das Ableiten der gemeinsamen Wand schafft das 7B-Modell nicht.
         */
        public String instruction() {
            int mid = (spanMin + spanMax) / 2;
            int far = plane + side.out * Math.max(freeDepth, 1);
            String axis = side.xPlane ? "x" : "z";
            String alongAxis = side.xPlane ? "z" : "x";
            String range = side.out > 0 ? plane + ".." + far : far + ".." + plane;
            Pos d0 = side.at(plane, floorY + 1, mid);
            Pos d1 = side.at(plane, floorY + 2, mid);
            return "ATTACH (fixed by the server, follow it exactly): put the new part on the " + side.english
                    + " side of structure " + structure + " and share its wall.\n"
                    + "- Shared wall: " + axis + "=" + plane + ", " + alongAxis + " " + spanMin + ".." + spanMax
                    + ", y " + floorY + ".." + topY + ". It stays; it is the " + side.ownWall
                    + " wall of the new part.\n"
                    + "- New part: " + axis + " within " + range + " (free), " + alongAxis + " within " + spanMin
                    + ".." + spanMax + ", floor at y=" + floorY + ", walls from y=" + (floorY + 1) + ", main "
                    + "material " + material + ".\n"
                    + "- Doorway: fill air " + fmt(d0) + " to " + fmt(d1) + ". Roof overhang 0. Never build on top "
                    + "of or inside structure " + structure + ".\n";
        }
    }

    /**
     * Ergebnis von {@link #fit}: der verschobene Plan (JSON fürs Gedächtnis, geparst, übersetzt und geklemmt) und
     * was geschah.
     *
     * @param clamped Planblöcke auf der gemeinsamen Wandebene, die weggelassen wurden, weil dort die Wand steht
     */
    public record Fit(String json, BuildPlan plan, CompiledBuild build, int dx, int dy, int dz, int clamped) {

        public boolean moved() {
            return dx != 0 || dy != 0 || dz != 0;
        }
    }

    /** Anbau daneben verlangt (enges Muster) und nicht nach oben? Nur dann gibt es Wandvorgabe und Ausrichtung. */
    public static boolean wanted(String prompt) {
        return prompt != null && BESIDE_WORDS.matcher(prompt).find() && !UP_WORDS.matcher(prompt).find();
    }

    /**
     * Anschlusswand für einen Anbau-Auftrag, sonst leer. Bauwerk ist das größte im Scan (dasselbe, das die
     * Anschlussprüfung nennt). Eine im Auftrag genannte Seite gilt immer, auch ohne freien Platz – dann meldet der
     * Überbau-Schutz den Konflikt, statt dass das Plugin heimlich eine andere Seite nimmt. Sonst die Seite mit dem
     * meisten Platz, bei Gleichstand rechts, links, hinten, vorne (vorne steht der Spieler).
     */
    public static Optional<Wall> choose(SiteSurvey survey, String prompt) {
        if (survey == null || !wanted(prompt) || !survey.hasStructures()) {
            return Optional.empty();
        }
        SiteSurvey.Structure s = survey.structures().getFirst();
        Side asked = requestedSide(prompt);
        List<Side> order = asked != null ? List.of(asked) : List.of(Side.RIGHT, Side.LEFT, Side.BACK, Side.FRONT);
        Wall best = null;
        int bestScore = -1;
        for (Side side : order) {
            Wall w = wallOf(survey, s, side);
            int score = Math.min(w.freeDepth(), ENOUGH_DEPTH);
            if (score > bestScore) {
                best = w;
                bestScore = score;
            }
        }
        return Optional.of(best);
    }

    /** Ausdrücklich genannte Seite oder null. Bei mehreren Seitenwörtern gilt das erste im Auftrag. */
    static Side requestedSide(String prompt) {
        for (String t : RequestHints.tokens(RequestHints.normalize(prompt))) {
            if (RIGHT_WORDS.contains(t)) {
                return Side.RIGHT;
            }
            if (LEFT_WORDS.contains(t)) {
                return Side.LEFT;
            }
            if (BACK_WORDS.contains(t)) {
                return Side.BACK;
            }
            if (FRONT_WORDS.contains(t)) {
                return Side.FRONT;
            }
        }
        return null;
    }

    static Wall wallOf(SiteSurvey survey, SiteSurvey.Structure s, Side side) {
        int plane = switch (side) {
            case RIGHT -> s.max().x();
            case LEFT -> s.min().x();
            case BACK -> s.max().z();
            case FRONT -> s.min().z();
        };
        int spanMin = side.along(s.min());
        int spanMax = side.along(s.max());
        String main = s.materials().isEmpty() ? "stone" : s.materials().keySet().iterator().next();
        int depth = freeDepth(survey, side, plane, spanMin, spanMax, s.min().y(), s.max().y());
        return new Wall(s.number(), side, plane, spanMin, spanMax, s.min().y(), s.max().y(), depth, main);
    }

    /**
     * Wie viele Schichten nach außen frei sind: keine Zelle eines erkannten Bauwerks über die ganze Wandlänge und
     * Höhe, und noch im Scan (außerhalb ist der Platz unbekannt, nicht frei). Gelände und Bäume zählen nicht – über
     * die baut der Compiler ohnehin hinweg.
     */
    static int freeDepth(SiteSurvey survey, Side side, int plane, int spanMin, int spanMax, int y0, int y1) {
        for (int d = 1; d <= MAX_DEPTH; d++) {
            int across = plane + side.out * d;
            for (int a = spanMin; a <= spanMax; a++) {
                for (int y = y0; y <= y1; y++) {
                    Pos p = side.at(across, y, a);
                    if (!survey.inScan(p) || survey.isStructure(p)) {
                        return d - 1;
                    }
                }
            }
        }
        return MAX_DEPTH;
    }

    // ---------------------------------------------------------------- Ausrichten

    /**
     * Rückt den Plan an die Wand: verschiebt ihn (falls nötig), übersetzt ihn neu und lässt Planblöcke auf der
     * gemeinsamen Wandebene weg, die einen bestehenden Wandblock ändern würden. Türen und Luft (Durchgang) bleiben
     * und zählen im Überbau-Schutz.
     *
     * Verschoben wird nur, wenn der Plan NICHT schon ordentlich anschließt (berührt ein Bauwerk, nichts im Inneren
     * oder auf dem Dach des Altbaus, Boden auf Höhe des Altbaus ±1) – ein Plan, der an einer anderen Seite sauber
     * anbaut, bleibt, wo er ist.
     */
    public static Fit fit(SiteSurvey survey, Wall wall, String json, BuildPlan plan, CompiledBuild build,
            PlanCompiler compiler) throws PlanException {
        int[] d = shift(survey, wall, build);
        String fitted = json;
        BuildPlan p = plan;
        CompiledBuild b = build;
        if (d[0] != 0 || d[1] != 0 || d[2] != 0) {
            fitted = translate(json, d[0], d[1], d[2]);
            p = PlanParser.parse(fitted);
            b = compiler.compile(p);
        }
        int[] clamped = {0};
        b = clamp(survey, wall, b, clamped);
        return new Fit(fitted, p, b, d[0], d[1], d[2], clamped[0]);
    }

    /** Verschiebung {dx, dy, dz}, die den Plan an die Wand bringt; {0, 0, 0}, wenn er schon passt. */
    static int[] shift(SiteSurvey survey, Wall wall, CompiledBuild build) {
        Side side = wall.side();
        int nearMin = Integer.MAX_VALUE;
        int nearMax = Integer.MIN_VALUE;
        int alongMin = Integer.MAX_VALUE;
        int alongMax = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        boolean any = false;
        for (Action a : build.actions()) {
            for (Change c : a.changes()) {
                if (c.block().isAir()) {
                    continue;  // ausgehöhlte Stellen (Durchgang) bestimmen nicht, wo der neue Teil steht
                }
                any = true;
                Pos p = c.pos();
                nearMin = Math.min(nearMin, side.across(p));
                nearMax = Math.max(nearMax, side.across(p));
                alongMin = Math.min(alongMin, side.along(p));
                alongMax = Math.max(alongMax, side.along(p));
                minY = Math.min(minY, p.y());
            }
        }
        if (!any) {
            return new int[] {0, 0, 0};
        }
        // Berührt der Plan zwei Bauwerke, verbindet er sie (Gang, Brücke) – seine Lage ist dann der Auftrag. Vorher
        // wurde eine Brücke auf y3 zwischen zwei Häusern auf den Boden vor Haus 1 gerückt und ersetzte 5 Bruchsteine
        // (Prüferbefund 2026-10-29, Gegenprobe AttachPlannerTest.bridgeBetweenTwoHousesIsNotMoved).
        if (touchedStructures(survey, build) >= 2) {
            return new int[] {0, 0, 0};
        }
        boolean floorOk = minY >= wall.floorY() - 1 && minY <= wall.floorY() + 1;
        SiteSurvey.Structure s = survey.structures().get(wall.structure() - 1);
        if (floorOk && intrusion(survey, s, build) == 0 && survey.touchesStructure(build)) {
            return new int[] {0, 0, 0};
        }
        int dy = floorOk ? 0 : wall.floorY() - minY;
        // Quer zur Wand: die Wandseite des neuen Teils auf die Wandebene (geteilt) oder direkt daneben (eigene Wand).
        int near = side.out > 0 ? nearMin : nearMax;
        int dAcross = near == wall.plane() || near == wall.plane() + side.out ? 0 : wall.plane() - near;
        // Entlang der Wand: wenigstens ein Durchgang (3 Blöcke) breit gemeinsam, sonst an den Wandanfang.
        int overlap = Math.min(alongMax, wall.spanMax()) - Math.max(alongMin, wall.spanMin()) + 1;
        int need = Math.min(3, Math.min(alongMax - alongMin + 1, wall.spanMax() - wall.spanMin() + 1));
        int dAlong = overlap >= need ? 0 : wall.spanMin() - alongMin;
        return side.xPlane ? new int[] {dAcross, dy, dAlong} : new int[] {dAlong, dy, dAcross};
    }

    /**
     * Wie viele verschiedene Bauwerke feste Planblöcke berühren (gleiche Nähe wie SiteSurvey.touchesStructure: im
     * Kasten des Bauwerks oder direkt daneben). Zählt nur bis 2 – mehr braucht {@link #shift} nicht.
     */
    static int touchedStructures(SiteSurvey survey, CompiledBuild build) {
        Set<Integer> touched = new HashSet<>();
        for (Action a : build.actions()) {
            for (Change c : a.changes()) {
                if (c.block().isAir()) {
                    continue;
                }
                Pos p = c.pos();
                for (SiteSurvey.Structure s : survey.structures()) {
                    if (p.x() >= s.min().x() - 1 && p.x() <= s.max().x() + 1 && p.y() >= s.min().y() - 1
                            && p.y() <= s.max().y() + 1 && p.z() >= s.min().z() - 1 && p.z() <= s.max().z() + 1
                            && touched.add(s.number()) && touched.size() >= 2) {
                        return 2;
                    }
                }
            }
        }
        return touched.size();
    }

    /**
     * Feste Planblöcke im Altbau: im Grundriss ohne die Außenwände, ab dessen Boden und auch darüber (auf dem Dach),
     * und nur, wo sie etwas anderes setzen als dort steht. Gleiche Blöcke zählen nicht – ein Plan, der den Altbau
     * mitzeichnet, ändert ihn nicht und darf nicht wegverschoben werden (sonst stünde das Haus doppelt da).
     */
    static int intrusion(SiteSurvey survey, SiteSurvey.Structure s, CompiledBuild build) {
        int n = 0;
        for (Action a : build.actions()) {
            for (Change c : a.changes()) {
                Pos p = c.pos();
                if (c.block().isAir() || p.y() < s.min().y() || p.x() <= s.min().x() || p.x() >= s.max().x()
                        || p.z() <= s.min().z() || p.z() >= s.max().z()) {
                    continue;
                }
                if (!c.block().id().equals(survey.idAt(p))) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Lässt Planblöcke weg, die auf der Wandebene einen Bauwerksblock gegen einen anderen festen Block tauschen
     * würden: Die bestehende Wand IST die Wand des Anbaus. Luft (Durchgang) und Türen bleiben drin und zählen im
     * Überbau-Schutz. Ohne das Klemmen scheiterte jeder Anbau aus anderem Material an der eigenen Wandebene
     * (Gegenprobe AttachPlannerTest.sharedWallKeepsTheOldMaterial: 15 geänderte Wandblöcke > max-überbauen 12).
     *
     * Bliebe nichts übrig, liegt der ganze Plan in der Altbauwand: Das ist ein Planfehler und geht in die
     * Reparaturrunde. Vorher kam dann der UNGEKLEMMTE Bau zurück, gemeldet als „geklemmt“ – gesetzt wurden genau die
     * Wandblöcke, die das Klemmen verhindern soll, solange es nicht mehr als max-überbauen waren (Prüferbefund
     * 2026-10-29, Gegenprobe AttachPlannerTest.planInsideTheSharedWallGoesToRepair).
     */
    static CompiledBuild clamp(SiteSurvey survey, Wall wall, CompiledBuild build, int[] count) throws PlanException {
        Side side = wall.side();
        List<Action> kept = new ArrayList<>(build.actions().size());
        int dropped = 0;
        for (Action a : build.actions()) {
            List<Change> changes = new ArrayList<>(a.changes().size());
            for (Change c : a.changes()) {
                Pos p = c.pos();
                boolean onWall = side.across(p) == wall.plane() && survey.isStructure(p);
                if (onWall && !c.block().isAir() && !c.block().isDoor() && !c.block().id().equals(survey.idAt(p))) {
                    dropped++;
                    continue;
                }
                changes.add(c);
            }
            if (!changes.isEmpty()) {
                kept.add(changes.size() == a.changes().size() ? a : new Action(a.step(), List.copyOf(changes)));
            }
        }
        if (dropped > 0 && kept.isEmpty()) {
            String axis = side.xPlane ? "x" : "z";
            throw new PlanException("Your plan lies entirely in the existing wall of structure " + wall.structure()
                    + " (" + axis + "=" + wall.plane() + ") and would only swap its blocks. Keep that wall and build "
                    + "the new part beside it on the " + side.english + " side, floor at y=" + wall.floorY() + ".");
        }
        count[0] = dropped;
        if (dropped == 0) {
            return build;
        }
        Pos min = null;
        Pos max = null;
        for (Action a : kept) {
            for (Change c : a.changes()) {
                Pos p = c.pos();
                min = min == null ? p : new Pos(Math.min(min.x(), p.x()), Math.min(min.y(), p.y()),
                        Math.min(min.z(), p.z()));
                max = max == null ? p : new Pos(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()),
                        Math.max(max.z(), p.z()));
            }
        }
        return new CompiledBuild(List.copyOf(kept), build.warnings(), min, max, build.stepNames());
    }

    /**
     * from/to/pos aller Schritte um (dx, dy, dz) verschieben. Anders als BuildService.translate (Gedächtnis, nur
     * waagerecht, ohne reply) auch senkrecht – „auf dem Dach“ ist genau der Fehler, der hier behoben wird.
     */
    static String translate(String planJson, int dx, int dy, int dz) throws PlanException {
        int start = planJson.indexOf('{');
        int end = planJson.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new PlanException("Die Antwort enthält kein JSON-Objekt.");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(planJson.substring(start, end + 1)).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new PlanException("JSON fehlerhaft: " + e.getMessage());
        }
        if (root.has("steps") && root.get("steps").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("steps")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject st = el.getAsJsonObject();
                for (String k : List.of("from", "to", "pos")) {
                    if (st.has(k) && st.get(k).isJsonArray() && st.getAsJsonArray(k).size() == 3) {
                        JsonArray v = st.getAsJsonArray(k);
                        JsonArray moved = new JsonArray();
                        moved.add(v.get(0).getAsInt() + dx);
                        moved.add(v.get(1).getAsInt() + dy);
                        moved.add(v.get(2).getAsInt() + dz);
                        st.add(k, moved);
                    }
                }
                PlanCommands.shiftPlaceholders(st, dx, dy, dz);
            }
        }
        return root.toString();
    }

    private static String fmt(Pos p) {
        return "[" + p.x() + "," + p.y() + "," + p.z() + "]";
    }
}
