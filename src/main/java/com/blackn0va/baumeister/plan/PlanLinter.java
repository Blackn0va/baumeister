package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.logic.LogicCircuit;
import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.BuildPlan.Step;
import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Prüft einen übersetzten Plan auf Baufehler, die ein Spieler auf den ersten Blick sähe: Türen mitten im Raum,
 * Fenster ohne Wand, Laternen in der Luft, zugemauertes oder auslaufendes Wasser, Vollklötze statt Gebäude und
 * schwebende Teile. Die Texte sind englisch, weil sie als Rückmeldung an das LLM gehen sollen.
 *
 * Fail-open: Der Linter dient allein der Bauqualität und ist KEIN Sicherheitspfad. Was gesetzt werden darf,
 * entscheiden {@link BlockRules} und die Grenzen des {@link PlanCompiler}; findet der Linter nichts oder irrt er,
 * wird trotzdem gebaut. Deshalb darf er auch heuristisch sein – wer ihn je als Sperre benutzt, muss ihn zuerst
 * fail-closed machen.
 *
 * Geprüft wird der Endzustand aus {@link CompiledBuild#actions()}, nicht die einzelnen Schritte: Ein Fenster, das
 * eine Wand überschreibt, ist ein Fenster in der Wand, auch wenn die Wand früher kam.
 */
public final class PlanLinter {

    public enum Severity {
        /** Sichtbar kaputt: soll in die Reparaturrunde. */
        ERROR,
        /** Wahrscheinlich ungewollt, aber baubar. */
        HINT
    }

    /**
     * Welche Regel angeschlagen hat. Gebraucht von PlanReview: Sie entscheidet je Regel, was in die Reparaturrunde
     * geht, und nennt dem Spieler die deutsche Kurzform statt des englischen LLM-Texts.
     */
    public enum Rule {
        DOOR("Tür nicht in der Wand"),
        WINDOW("Fenster nicht in der Wand"),
        SUPPORT("Angehängtes ohne Halt"),
        SEALED_WATER("Wasser eingemauert"),
        SOLID("Vollklotz statt Hohlraum"),
        LEAKING_WATER("Wasser läuft aus"),
        FLOATING("schwebende Teile"),
        OVERLAP("Bauteile überlappen sich"),
        MODULE_BURIED("Modul überbaut");

        private final String german;

        Rule(String german) {
            this.german = german;
        }

        public String german() {
            return german;
        }
    }

    /**
     * @param step    1-basierte Schrittnummer wie in den Meldungen des Compilers ("Step 7 ...")
     * @param pos     Stelle in lokalen Plankoordinaten
     * @param llmText englischer Satz für die Reparaturrunde
     * @param rule    die Regel, die die Meldung erzeugt hat
     */
    public record Issue(int step, Pos pos, Severity severity, String llmText, Rule rule) {
    }

    /**
     * Zellen, deren Endzustand einer dieser Schritte geschrieben hat, werden nicht geprüft, zählen aber als Halt.
     * Bibliotheksbauteile sind im Spiel getestet (Redstone hängt dort bewusst an Kanten), das Hochhaus rechnet der
     * Compiler selbst – Meldungen dazu könnte das LLM ohnehin nicht beheben. Kommt eine neue Compiler-Op hinzu, die
     * ganze Gebäude erzeugt, gehört sie hierher UND in {@link Site#lastWriter}, damit ihr Grundriss bekannt ist.
     * Maßgeblich ist der LETZTE schreibende Schritt, nicht Action.step (der erste): Vorher hing die Ausnahme am ersten
     * Schritt, und ein Modul in einem mit fill air ausgehöhlten Block – wie der Prompt es lehrt – wurde voll geprüft
     * (gemessen 2026-10-28: "Step 1: lever at [3,3,3] has nothing to hang on", Gegenprobe
     * moduleInCarvedSpaceIsNotChecked).
     */
    static final Set<Op> UNCHECKED_OPS = EnumSet.of(Op.MODULE, Op.SKYSCRAPER, Op.HOUSE, Op.LOGIC, Op.CASTLE, Op.WALL,
            Op.BRIDGE, Op.GARDEN, Op.TREE);

    /**
     * Überlappung (g): Ein späterer Raumschritt überschreibt mindestens drei Viertel und mindestens so viele Blöcke
     * eines früheren. Darunter sind es Pfosten in Wänden, Glasbänder oder Fensterreihen – gewollt.
     */
    static final int OVERLAP_MIN_BLOCKS = 16;

    /** Ab hier ist ein Vollkörper ein Klotz statt eines Bauteils (Werte aus der Linter-Planung). */
    static final int SOLID_MIN_EXTENT = 7;
    static final long SOLID_MIN_VOLUME = 512;

    /** Lose Füllungen (Erde, Sand, Laub, Wasser) dürfen massiv sein: Hügel, Hecken, Teiche. */
    private static final Set<String> MASSIVE_OK = Set.of("dirt", "grass_block", "sand", "gravel", "snow_block",
            "water");

    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] HORIZONTAL = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] AROUND = around();

    private PlanLinter() {
    }

    /**
     * Ohne Bauteilbibliothek ist der Grundriss eines module-Schritts unbekannt. Ein Modulblock wird dann daran
     * erkannt, dass kein gewöhnlicher Schritt an dieser Stelle denselben Block setzt. Überschreibt ein Modul eine
     * Zelle mit genau dem Block, den dort schon ein früherer LLM-Schritt gesetzt hatte, gilt die Zelle als
     * LLM-Zelle und wird geprüft – {@link #lint(BuildPlan, CompiledBuild, ModuleLibrary, boolean)} kennt den
     * Grundriss und hat diese Lücke nicht.
     */
    public static List<Issue> lint(BuildPlan plan, CompiledBuild build, boolean freeSite) {
        return lint(plan, build, ModuleLibrary.EMPTY, freeSite);
    }

    /** @param modules dieselbe Bibliothek, mit der der {@link PlanCompiler} den Plan übersetzt hat */
    public static List<Issue> lint(BuildPlan plan, CompiledBuild build, ModuleLibrary modules, boolean freeSite) {
        return lint(plan, build, modules, freeSite, p -> false);
    }

    /**
     * Mit dem, was am Bauplatz schon steht: existing sagt für eine Stelle ohne Planblock, ob dort ein fester Block
     * steht (SiteSurvey). Vorher kannte der Linter nur den Plan – eine Tür in der gemeinsamen Wand eines Anbaus
     * („Doorway“ im Altbau) hätte er als „not set in a wall“ in die Reparaturrunde geschickt, obwohl die Wand steht
     * (Gegenprobe PlanReviewTest.doorInTheSharedWallIsNotAnError). Planblöcke gehen immer vor: Setzt der Plan Luft,
     * ist dort Luft.
     */
    public static List<Issue> lint(BuildPlan plan, CompiledBuild build, ModuleLibrary modules, boolean freeSite,
            Predicate<Pos> existing) {
        Site site = new Site(plan, build, modules, existing);
        List<Issue> out = new ArrayList<>();
        if (site.cells.isEmpty()) {
            return out;
        }
        doorsInWalls(site, out);
        windowsInWalls(site, out);
        attachedHaveSupport(site, out);
        sealedWater(site, out);
        solidBodies(site, out);
        leakingWater(site, out);
        overlaps(site, out);
        buriedModules(site, out);
        if (freeSite) {
            // Neben einem bestehenden Bau kann ein Teil auf Blöcken ruhen, die der Plan nicht kennt.
            floatingParts(site, out);
        }
        out.sort(Comparator.comparing(Issue::severity));
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------- Regeln

    /**
     * (h) Ein späterer Schritt überschreibt Zellen eines Moduls (Schaltung, Maschine). Vorher fiel das durch jede
     * Regel: Modulzellen sind ungeprüft (UNCHECKED_OPS), und overlaps vergleicht nur Raumschritte. dev01 2026-10-30,
     * „bau mir eine fette maschine“: drei Module auf y 0, danach fill stone_bricks über y −1…1 und fill air darüber –
     * die Module standen im Stein, gemeldet wurden nur schwebende Fackeln. Schon ein überschriebener Block bricht eine
     * Schaltung (Redstone, Hebel, Lampe), deshalb keine Mindestzahl wie bei overlaps.
     *
     * Vorher nur über moduleCells: Ein fill quer durch eine op-logic-Schaltung blieb ohne Befund, obwohl sie genauso
     * bricht. Jetzt laufen die logic-Schritte mit. Wer sie wieder herausnimmt, baut Logik-Schaltungen wortlos ein.
     */
    private static void buriedModules(Site site, List<Issue> out) {
        List<Step> steps = site.plan.steps();
        Map<Integer, Set<Pos>> circuits = new TreeMap<>(site.moduleCells);
        circuits.putAll(site.logicCells);
        for (Map.Entry<Integer, Set<Pos>> e : circuits.entrySet()) {
            int i = e.getKey();
            Map<Integer, Integer> lostTo = new LinkedHashMap<>();
            Map<Integer, Pos> example = new HashMap<>();
            for (Pos p : e.getValue()) {
                Integer w = site.writer.get(p);
                if (w != null && w > i && w < steps.size() && steps.get(w).op() != Op.MODULE) {
                    lostTo.merge(w, 1, Integer::sum);
                    example.putIfAbsent(w, p);
                }
            }
            for (Map.Entry<Integer, Integer> l : lostTo.entrySet()) {
                int w = l.getKey();
                Step later = steps.get(w);
                Step victim = steps.get(i);
                // name() ist bei logic leer – dort nennt die Formel die Schaltung.
                String what = victim.op() == Op.LOGIC ? "logic circuit \"" + victim.expr() + "\""
                        : "module \"" + victim.name() + "\"";
                out.add(new Issue(w + 1, example.get(w), Severity.ERROR, "Step " + (w + 1) + " ("
                        + later.op().name().toLowerCase(Locale.ROOT) + " " + site.blockIds[w] + ") overwrites "
                        + l.getValue() + " block(s) of the " + what + " from step " + (i + 1)
                        + ", so the machine will not work. Never fill through a module: put floors below it and walls "
                        + "around it, leaving its own space free.", Rule.MODULE_BURIED));
            }
        }
    }

    /** (a) Eine Tür steht auf festem Grund und hat links/rechts ODER vorn/hinten Wand. Nachbartüren zählen als fest. */
    private static void doorsInWalls(Site site, List<Issue> out) {
        for (Map.Entry<Pos, BlockSpec> e : site.cells.entrySet()) {
            Pos p = e.getKey();
            BlockSpec b = e.getValue();
            if (!b.isDoor() || "upper".equals(b.props().get("half")) || !site.checked(p)) {
                continue;
            }
            boolean inWall = site.solid(p.down()) && (site.solid(p.add(-1, 0, 0)) && site.solid(p.add(1, 0, 0))
                    || site.solid(p.add(0, 0, -1)) && site.solid(p.add(0, 0, 1)));
            if (!inWall) {
                int step = site.ownerStep(p);
                out.add(new Issue(step, p, Severity.ERROR, "Step " + step + ": the door at "
                        + fmt(p) + " is not set in a wall. A door needs a solid block below it and solid wall blocks "
                        + "on both sides (left and right, or front and back). Put it into an outer wall.", Rule.DOOR));
            }
        }
    }

    /**
     * (b) Fenster aus set-Schritten ersetzen einen Wandblock, haben also auf einer waagerechten Achse beidseitig
     * festen Halt. Nur set: Glasflächen aus fill/walls sind gewollte Glaswände.
     */
    private static void windowsInWalls(Site site, List<Issue> out) {
        Set<Pos> seen = new HashSet<>();
        for (int i = 0; i < site.plan.steps().size(); i++) {
            Step s = site.plan.steps().get(i);
            if (s.op() != Op.SET || s.pos() == null) {
                continue;
            }
            String id = idOf(s.block());
            BlockSpec b = site.cells.get(s.pos());
            if (id == null || !isGlass(id) || b == null || !b.id().equals(id) || !site.checked(s.pos())
                    || !seen.add(s.pos())) {
                continue;
            }
            Pos p = s.pos();
            boolean inWall = site.solid(p.add(-1, 0, 0)) && site.solid(p.add(1, 0, 0))
                    || site.solid(p.add(0, 0, -1)) && site.solid(p.add(0, 0, 1));
            if (!inWall) {
                out.add(new Issue(i + 1, p, Severity.ERROR, "Step " + (i + 1) + ": the window (" + id + ") at "
                        + fmt(p) + " is not inside a wall. A window replaces one wall block, so it needs solid "
                        + "blocks on both sides. Move it into the wall.", Rule.WINDOW));
            }
        }
    }

    /**
     * (c) Angehängte Blöcke brauchen Halt auf der Seite, an der Minecraft 1.21 sie prüft. Vorher zählte jeder der
     * sechs Nachbarn: Eine Laterne, die nur eine Wand berührt, ging ohne Meldung durch, fällt aber in BuildJob.place
     * (setBlockData mit Physik) beim nächsten Nachbar-Update ab – gemessen 2026-10-28, Laterne bei [1,2,3] neben der
     * Wand bei [0,2,3]: 0 Meldungen. Der alte Text riet dem LLM zudem "under a ceiling or next to a wall" und
     * lehrte damit für Laternen genau den Fehler (Gegenprobe lanternBesideWallOnlyIsError).
     */
    private static void attachedHaveSupport(Site site, List<Issue> out) {
        for (Map.Entry<Pos, BlockSpec> e : site.cells.entrySet()) {
            Pos p = e.getKey();
            BlockSpec b = e.getValue();
            if (b.isAir() || !PlanCompiler.needsSupport(b.id()) || !site.checked(p)) {
                continue;
            }
            Support need = supportOf(b);
            boolean below = site.solid(p.down());
            boolean wall = false;
            for (int[] d : HORIZONTAL) {
                wall |= site.solid(p.add(d[0], d[1], d[2]));
            }
            boolean held = switch (need) {
                case BELOW -> below;
                case ABOVE -> site.solid(p.up());
                case BELOW_OR_WALL -> below || wall;
                case ANY -> below || wall || site.solid(p.up());
            };
            if (!held) {
                int step = site.ownerStep(p);
                out.add(new Issue(step, p, Severity.ERROR, "Step " + step + ": " + b.id() + " at " + fmt(p)
                        + " " + need.llmText, Rule.SUPPORT));
            }
        }
    }

    /** Woran ein angehängter Block Halt findet. Die Texte gehen unverändert an das LLM. */
    private enum Support {
        BELOW("has no solid block directly below it and will drop as an item. A wall next to it or a ceiling "
                + "above it does not hold it. Put it on top of a floor or another solid block."),
        ABOVE("hangs from nothing and will drop as an item. A hanging block needs a solid block directly above "
                + "it. Put it under a ceiling block."),
        BELOW_OR_WALL("has no solid block below it and no wall block beside it and will drop as an item. Put it on "
                + "a floor or next to a wall block."),
        ANY("has nothing to hang on and will drop as an item. Put it on a floor, under a ceiling or next to a "
                + "wall block.");

        final String llmText;

        Support(String llmText) {
            this.llmText = llmText;
        }
    }

    /**
     * Halt-Richtung je Blockart in 1.21. Laterne, Kerze, Teppich, Druckplatte, Redstone, Schiene, Repeater und
     * Comparator überleben nur mit Halt darunter, eine hängende Laterne (hanging=true; das Plugin setzt es nie selbst)
     * nur mit Block darüber, eine Fackel auf dem Boden oder an der Wand. Der Rest (Hebel, Knopf, Glocke, Schild …)
     * kann je nach Zustand an jeder Seite hängen und behält die alte Prüfung. Seelaterne und Kürbislaterne kommen
     * nicht hierher – PlanCompiler.needsSupport nimmt sie als Vollblöcke heraus.
     */
    private static Support supportOf(BlockSpec b) {
        String id = b.id();
        if (id.endsWith("lantern")) {
            return "true".equals(b.props().get("hanging")) ? Support.ABOVE : Support.BELOW;
        }
        if (id.endsWith("torch")) {
            return Support.BELOW_OR_WALL;
        }
        if (id.contains("candle") || id.endsWith("carpet") || id.endsWith("_pressure_plate")
                || id.equals("redstone_wire") || id.endsWith("rail") || id.equals("repeater")
                || id.equals("comparator")) {
            return Support.BELOW;
        }
        return Support.ANY;
    }

    /**
     * (d1) Wasser ohne jede offene Oberfläche ist eingemauert – im Spiel unsichtbar. Offen heißt: über der Zelle ist
     * weder ein fester Block noch Wasser. Wasser über Wasser zählt bewusst NICHT als offen; sonst ginge ein zwei
     * Blöcke tiefes Becken unter durchgehendem Deckel als offen durch (die untere Schicht hätte Wasser über sich).
     */
    private static void sealedWater(Site site, List<Issue> out) {
        for (List<Pos> body : site.waterBodies()) {
            boolean open = false;
            for (Pos p : body) {
                Pos up = p.up();
                open |= !site.solid(up) && !isWater(site.cells.get(up));
            }
            if (!open) {
                Pos p = body.getFirst();
                int step = site.ownerStep(p);
                out.add(new Issue(step, p, Severity.ERROR, "Step " + step + ": the water at " + fmt(p) + " ("
                        + body.size() + " blocks) is sealed in: every water block has a solid block above it. "
                        + "Leave the top of the basin open.", Rule.SEALED_WATER));
            }
        }
    }

    /**
     * (e) Gebäude sind hohl. Ein fill/cylinder/sphere ohne hollow ab {@link #SOLID_MIN_EXTENT} in allen drei
     * Richtungen oder ab {@link #SOLID_MIN_VOLUME} Blöcken ist ein Klotz – aber nur, wenn sein Kern im Endzustand
     * noch überwiegend fest ist. Vorher (Planung) zählte allein der Schritt; damit wären zwei Muster falsch rot
     * gewesen, die der Prompt selbst lehrt: "fill, dann fill air innen" (der Kern ist danach leer) und große Böden
     * wie 24×1×24 (576 Blöcke, aber gar kein Kern). Wer den Kern-Test entfernt, schickt beide in die
     * Reparaturrunde.
     */
    private static void solidBodies(Site site, List<Issue> out) {
        for (int i = 0; i < site.plan.steps().size(); i++) {
            Step s = site.plan.steps().get(i);
            if (s.hollow() || s.op() != Op.FILL && s.op() != Op.CYLINDER && s.op() != Op.SPHERE) {
                continue;
            }
            String id = idOf(s.block());
            if (id == null || isMassiveOk(id)) {
                continue;
            }
            int[] ext = extents(s);
            if (ext == null) {
                continue;
            }
            boolean big = ext[0] >= SOLID_MIN_EXTENT && ext[1] >= SOLID_MIN_EXTENT && ext[2] >= SOLID_MIN_EXTENT
                    || (long) ext[0] * ext[1] * ext[2] >= SOLID_MIN_VOLUME;
            if (!big || !coreStillSolid(site, s)) {
                continue;
            }
            Pos p = s.op() == Op.FILL ? s.from() : s.pos();
            String op = s.op().name().toLowerCase(Locale.ROOT);
            out.add(new Issue(i + 1, p, Severity.ERROR, "Step " + (i + 1) + ": " + op + " of " + id + " is a solid "
                    + "block of " + ext[0] + "x" + ext[1] + "x" + ext[2] + ". Buildings and towers must be hollow: "
                    + "use walls or hollow for boxes and hollow=true for cylinders and spheres; use fill only for "
                    + "floors, pillars and small solid parts.", Rule.SOLID));
        }
    }

    /** (d2) Wasser, das waagerecht an Luft grenzt, läuft beim Bauen aus. */
    private static void leakingWater(Site site, List<Issue> out) {
        for (List<Pos> body : site.waterBodies()) {
            leak:
            for (Pos p : body) {
                for (int[] d : HORIZONTAL) {
                    Pos n = p.add(d[0], d[1], d[2]);
                    if (site.openAir(n)) {
                        int step = site.ownerStep(p);
                        out.add(new Issue(step, p, Severity.HINT, "Step " + step + ": the water at " + fmt(p)
                                + " touches open air at " + fmt(n) + " and will flow out. Close the basin with a "
                                + "wall on that side.", Rule.LEAKING_WATER));
                        break leak;
                    }
                }
            }
        }
    }

    /**
     * (g) Überlappung: Ein späterer Raumschritt überschreibt einen früheren fast ganz mit einem anderen Block – zwei
     * Bauteile im selben Raum (ein zweites Haus in das erste, eine Glaswand über die Steinwand). Der Spieler sieht
     * dann nur den späteren; der frühere war vergeblich geplant, und meist passt der Rest des Plans zum früheren.
     * Opfer werden nur fill/walls/hollow, weil nur deren Zellen genau bekannt sind (covers); Täter nur Raumschritte
     * ab zwei Schichten Höhe. Böden, Ringe und Pfosten fallen so heraus: Ein Boden über einem Fundamentring ist
     * gewollt, ein Eckpfosten überschreibt nur 3 von 96 Wandblöcken. Luft (aushöhlen) überschreibt nie.
     */
    private static void overlaps(Site site, List<Issue> out) {
        List<Step> steps = site.plan.steps();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String id = site.blockIds[i];
            if (s.op() != Op.FILL && s.op() != Op.WALLS && s.op() != Op.HOLLOW || !isRoomStep(s) || id == null
                    || isMassiveOk(id) || s.from() == null || s.to() == null) {
                continue;
            }
            int total = 0;
            Map<Integer, Integer> lostTo = new LinkedHashMap<>();
            Map<Integer, Pos> example = new HashMap<>();
            for (int y = Math.min(s.from().y(), s.to().y()); y <= Math.max(s.from().y(), s.to().y()); y++) {
                for (int z = Math.min(s.from().z(), s.to().z()); z <= Math.max(s.from().z(), s.to().z()); z++) {
                    for (int x = Math.min(s.from().x(), s.to().x()); x <= Math.max(s.from().x(), s.to().x()); x++) {
                        Pos p = new Pos(x, y, z);
                        if (!covers(s, p)) {
                            continue;
                        }
                        total++;
                        BlockSpec b = site.cells.get(p);
                        Integer w = site.writer.get(p);
                        if (b == null || w == null || w <= i || b.isAir() || b.id().equals(id)
                                || !site.checked(p) || !isRoomStep(steps.get(w))) {
                            continue;
                        }
                        lostTo.merge(w, 1, Integer::sum);
                        example.putIfAbsent(w, p);
                    }
                }
            }
            for (Map.Entry<Integer, Integer> e : lostTo.entrySet()) {
                int lost = e.getValue();
                if (lost < OVERLAP_MIN_BLOCKS || lost * 4L < total * 3L) {
                    continue;
                }
                int w = e.getKey();
                Step later = steps.get(w);
                out.add(new Issue(w + 1, example.get(w), Severity.ERROR, "Step " + (w + 1) + " ("
                        + later.op().name().toLowerCase(Locale.ROOT) + " " + site.blockIds[w] + ") overwrites " + lost
                        + " of the " + total + " blocks of step " + (i + 1) + " ("
                        + s.op().name().toLowerCase(Locale.ROOT) + " " + id + "): two parts of the plan occupy the same space. Give each part its own place "
                        + "or remove the duplicate step.", Rule.OVERLAP));
            }
        }
    }

    /** Raumschritt: gewöhnliche Form über mindestens zwei Schichten (keine Böden, Ringe, Einzelblöcke, Dächer). */
    private static boolean isRoomStep(Step s) {
        int height = switch (s.op()) {
            case FILL, WALLS, HOLLOW -> s.from() == null || s.to() == null ? 0
                    : Math.abs(s.to().y() - s.from().y()) + 1;
            case CYLINDER -> s.height();
            case SPHERE -> s.dome() ? s.radius() + 1 : 2 * s.radius() + 1;
            default -> 0;
        };
        return height >= 2;
    }

    /**
     * (f) Schweben: eine zusammenhängende Gruppe fester Blöcke, die nicht auf Bauhöhe beginnt und weder Modul- noch
     * Hochhausblöcke berührt. Zusammenhang über alle 26 Nachbarn, nicht nur die 6 Seiten: Treppendächer steigen
     * diagonal an (die erste Schicht mit Überstand sitzt schräg neben der Wandkrone). Mit 6 Nachbarn meldet der
     * Linter schon das Satteldach ohne gable_block als schwebend (Gegenprobe roofsWithoutGableFillDoNotFloat).
     */
    private static void floatingParts(Site site, List<Issue> out) {
        Set<Pos> visited = new HashSet<>();
        for (Pos start : site.cells.keySet()) {
            if (visited.contains(start) || !site.checked(start) || !isSolid(site.cells.get(start))) {
                continue;
            }
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            Pos lowest = start;
            int size = 0;
            boolean anchored = false;
            while (!queue.isEmpty()) {
                Pos p = queue.poll();
                size++;
                if (p.y() < lowest.y()) {
                    lowest = p;
                }
                for (int[] d : AROUND) {
                    Pos n = p.add(d[0], d[1], d[2]);
                    BlockSpec b = site.cells.get(n);
                    if (b == null) {
                        // Gelände oder Bestand aus dem Scan, den der Plan nicht setzt, trägt ebenso. Vorher zählte nur
                        // der eigene Plan: Eine Plattform direkt auf einem Hügel galt als schwebend, und seit
                        // PlanReview Schweben als schwer wertet, wurde der gültige Plan abgesagt (Prüferbefund
                        // 2026-10-29, Gegenprobe PlanReviewTest.platformOnAHillDoesNotFloat).
                        if (site.existing.test(n)) {
                            anchored = true;
                        }
                        continue;
                    }
                    if (!isSolid(b)) {
                        continue;
                    }
                    if (!site.checked(n)) {
                        anchored = true;
                    } else if (visited.add(n)) {
                        queue.add(n);
                    }
                }
            }
            if (!anchored && lowest.y() > site.minY) {
                int step = site.ownerStep(lowest);
                out.add(new Issue(step, lowest, Severity.HINT, "Step " + step + ": " + size + " block(s) around "
                        + fmt(lowest) + " float in the air (lowest at y=" + lowest.y() + ", the build starts at y="
                        + site.minY + "). Connect them to the rest of the build or lower them.", Rule.FLOATING));
            }
        }
    }

    // ---------------------------------------------------------------- Endzustand

    private static final class Site {
        final BuildPlan plan;
        final Map<Pos, BlockSpec> cells = new LinkedHashMap<>();
        /** 0-basierter Schritt, der den Endzustand der Zelle geschrieben hat. */
        final Map<Pos, Integer> writer = new HashMap<>();
        final Set<Pos> unchecked = new HashSet<>();
        final int minY;
        /** Fester Block am Bauplatz, den der Plan nicht setzt (SiteSurvey); ohne Bauplatz immer false. */
        private final Predicate<Pos> existing;
        /** Zellen je module-Schritt; fehlt der Schritt hier, kennt die Bibliothek das Bauteil nicht. */
        private final Map<Integer, Set<Pos>> moduleCells = new HashMap<>();
        /** Zellen je logic-Schritt (übersetzte Schaltung); leer, wenn die Formel nicht übersetzt. */
        private final Map<Integer, Set<Pos>> logicCells = new HashMap<>();
        /** Zellen je Bauform-Schritt (castle, wall, bridge, garden, tree), wie Structures sie setzt. */
        private final Map<Integer, Set<Pos>> structureCells = new HashMap<>();
        /** Block-IDs je Schritt (block, bei roof auch gable_block), einmal geparst statt je Zelle und Schritt. */
        private final String[] blockIds;
        private final String[] gableIds;
        private List<List<Pos>> waterBodies;

        Site(BuildPlan plan, CompiledBuild build, ModuleLibrary modules, Predicate<Pos> existing) {
            this.plan = plan;
            this.existing = existing;
            blockIds = new String[plan.steps().size()];
            gableIds = new String[plan.steps().size()];
            for (int i = 0; i < plan.steps().size(); i++) {
                Step s = plan.steps().get(i);
                blockIds[i] = idOf(s.block());
                gableIds[i] = s.op() == Op.ROOF ? idOf(s.gableBlock()) : null;
                if (s.op() == Op.MODULE && s.pos() != null) {
                    int step = i;
                    modules.find(s.name()).ifPresent(m -> moduleCells.put(step, placedCells(m, s)));
                }
                // Vorher übersetzte inLogic die Formel bei JEDER Zelle des Baus neu (lastWriter läuft je Zelle
                // rückwärts über alle Schritte), und das bis zu fünfmal je Antwort auf dem Server-Thread. Jetzt
                // einmal je Schritt, mit den echten Zellen wie PlanCompiler.placeModule statt eines Quadrats.
                // Eine Formel, die nicht übersetzt, setzt der Compiler nicht (PlanException) – keine Zellen.
                if (s.op() == Op.LOGIC && s.pos() != null) {
                    try {
                        logicCells.put(i, placedCells(LogicCircuit.compile(s.expr()).module(), s));
                    } catch (IllegalArgumentException e) {
                        logicCells.put(i, Set.of());
                    }
                }
                if (Structures.handles(s.op())) {
                    structureCells.put(i, Structures.cells(s, i + 1));
                }
            }
            for (Action a : build.actions()) {
                for (Change c : a.changes()) {
                    cells.put(c.pos(), c.block());
                    int last = lastWriter(c.pos(), c.block().id(), a.step());
                    writer.put(c.pos(), last);
                    if (last >= 0 && last < plan.steps().size()
                            && UNCHECKED_OPS.contains(plan.steps().get(last).op())) {
                        unchecked.add(c.pos());
                    }
                }
            }
            // Aus den Aktionen statt build.min(): Der Compiler verwirft halbe Türen erst nach der Grenzberechnung.
            int low = Integer.MAX_VALUE;
            for (Pos p : cells.keySet()) {
                low = Math.min(low, p.y());
            }
            minY = low;
        }

        /**
         * Der letzte Schritt, der diese Zelle geschrieben hat. Action.step ist der ERSTE (PlanCompiler.Canvas.put
         * behält ihn beim Überschreiben), daher wird rückwärts gesucht, aber nie vor dem ersten Schritt: der
         * jüngste Schritt, dessen Grundriss die Stelle enthält und der – bei gewöhnlichen Ops – denselben Block
         * nennt. Hochhaus und bekannte Module zählen über ihren Grundriss allein, denn sie setzen viele Blockarten.
         * Erklärt kein Schritt den Block, stammt er aus einem Modul, das die Bibliothek nicht kennt (jüngstes ab dem
         * ersten Schritt), sonst aus einem Ersatz- oder Aliasblock – dann bleibt der erste Schritt.
         */
        private int lastWriter(Pos p, String id, int first) {
            List<Step> steps = plan.steps();
            int from = Math.max(first, 0);
            for (int i = steps.size() - 1; i >= from; i--) {
                Step s = steps.get(i);
                boolean wrote = switch (s.op()) {
                    case MODULE -> moduleCells.containsKey(i) && moduleCells.get(i).contains(p);
                    case SKYSCRAPER -> inSkyscraper(s, p);
                    case HOUSE -> inHouse(s, p);
                    case LOGIC -> logicCells.getOrDefault(i, Set.of()).contains(p);
                    // Bauformen (Structures): massive Mauern und Brückenbögen sind gewollt, kein „Klotz“. Nur die
                    // wirklich gesetzten Zellen – ein Kasten um die Form schaltete die Prüfung fremder LLM-Blöcke
                    // daneben ab (Gegenprobe StructuresTest aLaterStructureDoesNotHideForeignFloatingBlocks…).
                    case CASTLE, WALL, BRIDGE, GARDEN, TREE -> structureCells.get(i).contains(p);
                    default -> covers(s, p) && (id.equals(blockIds[i]) || id.equals(gableIds[i]));
                };
                if (wrote) {
                    return i;
                }
            }
            for (int i = steps.size() - 1; i >= from; i--) {
                if (steps.get(i).op() == Op.MODULE && !moduleCells.containsKey(i)) {
                    return i;
                }
            }
            return first;
        }

        boolean checked(Pos p) {
            return cells.containsKey(p) && !unchecked.contains(p);
        }

        /**
         * Fest im Sinne der Regeln; alles unter der untersten Bauschicht gilt als Gelände, und wo der Plan nichts
         * setzt, zählt, was am Bauplatz schon steht.
         */
        boolean solid(Pos p) {
            BlockSpec b = cells.get(p);
            return p.y() < minY || (b == null ? existing.test(p) : isSolid(b));
        }

        /** Luft, in die Wasser fließen kann: nichts gebaut oder ausdrücklich air. Gelände ist keine Luft. */
        boolean openAir(Pos p) {
            if (p.y() < minY) {
                return false;
            }
            BlockSpec b = cells.get(p);
            return b == null ? !existing.test(p) : b.isAir();
        }

        /** Wasserkörper je 6er-Nachbarschaft, nur geprüfte Zellen, in Bau-Reihenfolge. */
        List<List<Pos>> waterBodies() {
            if (waterBodies != null) {
                return waterBodies;
            }
            waterBodies = new ArrayList<>();
            Set<Pos> visited = new HashSet<>();
            for (Map.Entry<Pos, BlockSpec> e : cells.entrySet()) {
                Pos start = e.getKey();
                if (!isWater(e.getValue()) || !checked(start) || !visited.add(start)) {
                    continue;
                }
                List<Pos> body = new ArrayList<>();
                ArrayDeque<Pos> queue = new ArrayDeque<>();
                queue.add(start);
                while (!queue.isEmpty()) {
                    Pos p = queue.poll();
                    body.add(p);
                    for (int[] d : SIDES) {
                        Pos n = p.add(d[0], d[1], d[2]);
                        if (isWater(cells.get(n)) && checked(n) && visited.add(n)) {
                            queue.add(n);
                        }
                    }
                }
                waterBodies.add(body);
            }
            return waterBodies;
        }

        /**
         * 1-basierte Schrittnummer für die Meldung an das LLM. Eine Tür in der Wand zählt nach Action.step zum
         * walls-Schritt; "Step 2" wäre für das LLM die falsche Stelle, deshalb der letzte Schreiber.
         */
        int ownerStep(Pos p) {
            Integer last = writer.get(p);
            return last == null ? 0 : last + 1;
        }
    }

    /**
     * Zellen eines module-Schritts, gerechnet wie PlanCompiler.module: drehen, auf die kleinste x/z-Ecke schieben,
     * ab pos einsetzen. Weicht die Rechnung ab, hält der Linter fremde Zellen für Modulzellen (Gegenprobe
     * knownModuleOverEarlierSameBlockIsNotChecked mit facing=left).
     */
    private static Set<Pos> placedCells(ModuleLibrary.Module m, Step s) {
        int turns = Orientation.quarterTurnsFor(s.facing().isEmpty() ? "front" : s.facing());
        List<Pos> rotated = new ArrayList<>(m.cells().size());
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (Change cell : m.cells()) {
            Pos p = Orientation.rotateLocal(cell.pos(), turns);
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            rotated.add(p);
        }
        Set<Pos> out = new HashSet<>();
        for (Pos p : rotated) {
            out.add(s.pos().add(p.x() - minX, p.y(), p.z() - minZ));
        }
        return out;
    }

    /** Grundriss wie PlanCompiler.skyscraper: alle Zellen bis zum Dach, darüber nur die Brüstung am Rand. */
    private static boolean inSkyscraper(Step s, Pos p) {
        if (s.from() == null || s.to() == null) {
            return false;
        }
        int x0 = Math.min(s.from().x(), s.to().x());
        int x1 = Math.max(s.from().x(), s.to().x());
        int z0 = Math.min(s.from().z(), s.to().z());
        int z1 = Math.max(s.from().z(), s.to().z());
        int y0 = Math.min(s.from().y(), s.to().y());
        int top = y0 + s.floors() * PlanCompiler.FLOOR_HEIGHT;
        if (p.x() < x0 || p.x() > x1 || p.z() < z0 || p.z() > z1 || p.y() < y0 || p.y() > top + 1) {
            return false;
        }
        return p.y() <= top || p.x() == x0 || p.x() == x1 || p.z() == z0 || p.z() == z1;
    }

    /**
     * Bauraum von op house (PlanCompiler.house): Grundriss samt Dachüberstand 1, vom Boden bis über den First. Die
     * Dachhöhe eines Satteldachs ist höchstens die halbe kürzere Seite plus Überstand; großzügig gerechnet, denn hier
     * geht es nur um die Zuordnung zum Schritt, nicht um eine Prüfung (house steht in UNCHECKED_OPS – Laternen,
     * Leiter und Möbel setzt der Compiler absichtlich).
     */
    private static boolean inHouse(Step s, Pos p) {
        int x0 = Math.min(s.from().x(), s.to().x()) - 1;
        int x1 = Math.max(s.from().x(), s.to().x()) + 1;
        int z0 = Math.min(s.from().z(), s.to().z()) - 1;
        int z1 = Math.max(s.from().z(), s.to().z()) + 1;
        int y0 = Math.min(s.from().y(), s.to().y());
        int roof = Math.max(x1 - x0, z1 - z0) / 2 + 2;
        int top = y0 + Math.max(1, s.floors()) * PlanCompiler.FLOOR_HEIGHT + roof;
        return p.x() >= x0 && p.x() <= x1 && p.z() >= z0 && p.z() <= z1 && p.y() >= y0 && p.y() <= top;
    }

    /**
     * Ob ein gewöhnlicher Schritt die Stelle beschreiben könnte. walls und hollow genau (nur die Hülle): Ein Modul im
     * Innenraum desselben Blocks würde sonst dem Wandschritt zugeschlagen. Die übrigen Formen grob als Kasten –
     * zusammen mit dem Blockvergleich in Site.lastWriter reicht das.
     */
    private static boolean covers(Step s, Pos p) {
        return switch (s.op()) {
            case SET -> p.equals(s.pos());
            case DOOR -> s.pos() != null && (p.equals(s.pos()) || p.equals(s.pos().up()));
            case FILL, LINE -> inBox(p, s.from(), s.to(), 0, 0);
            case WALLS -> inBox(p, s.from(), s.to(), 0, 0) && onSide(p, s.from(), s.to(), false);
            case HOLLOW -> inBox(p, s.from(), s.to(), 0, 0) && onSide(p, s.from(), s.to(), true);
            case ROOF -> inBox(p, s.from(), s.to(), s.overhang(), 64);
            case CYLINDER -> s.pos() != null && Math.abs(p.x() - s.pos().x()) <= s.radius()
                    && Math.abs(p.z() - s.pos().z()) <= s.radius()
                    && p.y() >= s.pos().y() && p.y() < s.pos().y() + s.height();
            case SPHERE -> s.pos() != null && Math.abs(p.x() - s.pos().x()) <= s.radius()
                    && Math.abs(p.y() - s.pos().y()) <= s.radius() && Math.abs(p.z() - s.pos().z()) <= s.radius();
            case MODULE, SKYSCRAPER, HOUSE, LOGIC, CASTLE, WALL, BRIDGE, GARDEN, TREE -> false;
        };
    }

    private static boolean inBox(Pos p, Pos a, Pos b, int grow, int up) {
        if (a == null || b == null) {
            return false;
        }
        return p.x() >= Math.min(a.x(), b.x()) - grow && p.x() <= Math.max(a.x(), b.x()) + grow
                && p.z() >= Math.min(a.z(), b.z()) - grow && p.z() <= Math.max(a.z(), b.z()) + grow
                && p.y() >= Math.min(a.y(), b.y()) && p.y() <= Math.max(a.y(), b.y()) + up;
    }

    /** Liegt p (im Kasten a..b) auf dessen Seitenwand, mit caps auch auf Boden oder Deckel? Wie PlanCompiler.box. */
    private static boolean onSide(Pos p, Pos a, Pos b, boolean caps) {
        boolean side = p.x() == Math.min(a.x(), b.x()) || p.x() == Math.max(a.x(), b.x())
                || p.z() == Math.min(a.z(), b.z()) || p.z() == Math.max(a.z(), b.z());
        boolean cap = p.y() == Math.min(a.y(), b.y()) || p.y() == Math.max(a.y(), b.y());
        return side || caps && cap;
    }

    // ---------------------------------------------------------------- Vollkörper

    /** Ausdehnung x/y/z eines fill/cylinder/sphere-Schritts, null wenn Koordinaten fehlen. */
    private static int[] extents(Step s) {
        return switch (s.op()) {
            case FILL -> s.from() == null || s.to() == null ? null : new int[] {
                    Math.abs(s.to().x() - s.from().x()) + 1,
                    Math.abs(s.to().y() - s.from().y()) + 1,
                    Math.abs(s.to().z() - s.from().z()) + 1};
            case CYLINDER -> s.pos() == null ? null
                    : new int[] {2 * s.radius() + 1, s.height(), 2 * s.radius() + 1};
            case SPHERE -> s.pos() == null ? null
                    : new int[] {2 * s.radius() + 1, s.dome() ? s.radius() + 1 : 2 * s.radius() + 1,
                            2 * s.radius() + 1};
            default -> null;
        };
    }

    /**
     * Kern = die Zellen der Form, die hollow=true weglassen würde, ohne Deckel und Boden. Mindestens die Hälfte
     * davon muss im Endzustand fest sein: Ein Vollzylinder hat einen ganz festen Kern, ein mit fill air
     * ausgehöhlter Klotz einen leeren (beides in PlanLinterTest). Die Hälfte lässt Platz für Zwischendecken.
     */
    private static boolean coreStillSolid(Site site, Step s) {
        long total = 0;
        long solid = 0;
        switch (s.op()) {
            case FILL -> {
                int x0 = Math.min(s.from().x(), s.to().x()) + 1;
                int x1 = Math.max(s.from().x(), s.to().x()) - 1;
                int y0 = Math.min(s.from().y(), s.to().y()) + 1;
                int y1 = Math.max(s.from().y(), s.to().y()) - 1;
                int z0 = Math.min(s.from().z(), s.to().z()) + 1;
                int z1 = Math.max(s.from().z(), s.to().z()) - 1;
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            total++;
                            solid += isSolid(site.cells.get(new Pos(x, y, z))) ? 1 : 0;
                        }
                    }
                }
            }
            case CYLINDER -> {
                int r = s.radius();
                for (int dy = 1; dy < s.height() - 1; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dx = -r; dx <= r; dx++) {
                            if (inDisc(dx, dz, r) && inDisc(dx + 1, dz, r) && inDisc(dx - 1, dz, r)
                                    && inDisc(dx, dz + 1, r) && inDisc(dx, dz - 1, r)) {
                                total++;
                                solid += isSolid(site.cells.get(s.pos().add(dx, dy, dz))) ? 1 : 0;
                            }
                        }
                    }
                }
            }
            case SPHERE -> {
                int r = s.radius();
                for (int dy = s.dome() ? 1 : -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        for (int dx = -r; dx <= r; dx++) {
                            if (inBall(dx, dy, dz, r) && inBall(dx + 1, dy, dz, r) && inBall(dx - 1, dy, dz, r)
                                    && inBall(dx, dy + 1, dz, r) && inBall(dx, dy - 1, dz, r)
                                    && inBall(dx, dy, dz + 1, r) && inBall(dx, dy, dz - 1, r)) {
                                total++;
                                solid += isSolid(site.cells.get(s.pos().add(dx, dy, dz))) ? 1 : 0;
                            }
                        }
                    }
                }
            }
            default -> {
                return false;
            }
        }
        return total > 0 && solid * 2 >= total;
    }

    /** Dieselben Formeln wie im {@link PlanCompiler}; weichen sie ab, misst der Kern-Test die falschen Zellen. */
    private static boolean inDisc(int dx, int dz, int r) {
        return dx * dx + dz * dz < (r + 0.5) * (r + 0.5);
    }

    private static boolean inBall(int dx, int dy, int dz, int r) {
        return dx * dx + dy * dy + dz * dz < (r + 0.5) * (r + 0.5);
    }

    // ---------------------------------------------------------------- Blöcke

    /** Fest heißt: nicht Luft, nicht Wasser, nicht angehängt. Türen zählen als fest (Nachbartür = Wand). */
    private static boolean isSolid(BlockSpec b) {
        return b != null && !b.isAir() && !isWater(b) && !PlanCompiler.needsSupport(b.id());
    }

    private static boolean isWater(BlockSpec b) {
        return b != null && b.id().equals("water");
    }

    private static boolean isGlass(String id) {
        return id.equals("glass") || id.equals("glass_pane") || id.equals("tinted_glass")
                || id.endsWith("_stained_glass") || id.endsWith("_stained_glass_pane");
    }

    private static boolean isMassiveOk(String id) {
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air") || id.endsWith("_leaves")
                || MASSIVE_OK.contains(id);
    }

    /** Block-ID eines Schritts ohne Zustände; null, wenn der Text kein Block ist (der Compiler hat ihn ausgelassen). */
    private static String idOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BlockSpec.parse(raw).id();
        } catch (PlanException e) {
            // Fail-open: ein unlesbarer Block ist Sache des Compilers, der Linter prüft ihn nur nicht.
            return null;
        }
    }

    private static String fmt(Pos p) {
        return "[" + p.x() + "," + p.y() + "," + p.z() + "]";
    }

    private static int[][] around() {
        List<int[]> out = new ArrayList<>(26);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        out.add(new int[] {dx, dy, dz});
                    }
                }
            }
        }
        return out.toArray(new int[0][]);
    }
}
