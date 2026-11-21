package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.logic.LogicCircuit;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.BuildPlan.Step;
import com.blackn0va.baumeister.plan.CompiledBuild.Action;
import com.blackn0va.baumeister.plan.CompiledBuild.Change;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Macht aus den Grundformen des LLM einzelne Blöcke und ordnet sie so, wie ein Spieler baut.
 *
 * Reihenfolge: Schritt für Schritt in der Plan-Reihenfolge (Fundament → Wände → Dach → Deko,
 * so fordert es der Prompt an), darin Schicht für Schicht von unten nach oben, und in jeder
 * Schicht immer zum nächstgelegenen Block. Ein Block, den ein späterer Schritt überschreibt
 * (Fenster in der Wand), wird gleich mit dem Endzustand gesetzt und zählt zum ersten Schritt –
 * der NPC mauert also nicht erst zu, um danach das Loch wieder auszuschlagen.
 */
public final class PlanCompiler {

    /**
     * @param singleModuleBlocks Grenze für einen Plan aus genau einem Bauteil (Schematic): Wunsch 2026-11-08 – der
     * Olymp vom eigenen Server hat
     *                           952 911 Blöcke, bau.max-blöcke ist 150 000. Ein LLM-Plan aus vielen Schritten bleibt
     *                           bei maxBlocks; eine Datei, die jemand bewusst bauen lässt, darf so groß sein wie sie ist
     *                           (bis schematics.max-blöcke).
     * @param singleModuleExtent dasselbe für die Ausdehnung (schematics.max-ausdehnung, bedwars.schem 327)
     */
    public record Limits(int maxBlocks, int maxExtent, int singleModuleBlocks, int singleModuleExtent) {

        public Limits(int maxBlocks, int maxExtent) {
            this(maxBlocks, maxExtent, maxBlocks, maxExtent);
        }

        /** Die Grenzen, die für diesen Plan gelten. */
        Limits forPlan(BuildPlan plan) {
            boolean single = plan.steps().size() == 1 && plan.steps().getFirst().op() == Op.MODULE;
            return single ? new Limits(Math.max(maxBlocks, singleModuleBlocks), Math.max(maxExtent,
                    singleModuleExtent)) : this;
        }
    }

    private static final int MAX_ROOF_LAYERS = 24;

    private final BlockCatalog catalog;
    private final String fallbackId;
    private final Limits limits;
    private final ModuleLibrary modules;
    /** Burg, Mauer, Brücke, Garten, Baum: Geometrie in eigener Klasse, hier nur die Weiche in emit(). */
    private final Structures structures;

    public PlanCompiler(BlockCatalog catalog, String fallbackId, Limits limits) {
        this(catalog, fallbackId, limits, ModuleLibrary.EMPTY);
    }

    public PlanCompiler(BlockCatalog catalog, String fallbackId, Limits limits, ModuleLibrary modules) {
        this.catalog = catalog;
        this.fallbackId = fallbackId;
        this.limits = limits;
        this.modules = modules;
        this.structures = new Structures(catalog, limits);
    }

    private record Cell(BlockSpec spec, int firstStep, int group) {
    }

    /**
     * Sammelt die Blöcke; spätere Schritte überschreiben den Zustand, nicht den Zeitpunkt. group: Bauteil des laufenden
     * Schritts (0 = kein Bauteil) – ein späterer Schritt, der eine Zelle überschreibt, nimmt sie dem Bauteil weg.
     */
    private final class Canvas {
        final Map<Pos, Cell> cells = new LinkedHashMap<>();
        final Limits active;
        int step;
        int group;

        Canvas(Limits active) {
            this.active = active;
        }

        void put(int x, int y, int z, BlockSpec spec) throws PlanException {
            put(new Pos(x, y, z), spec);
        }

        /**
         * Zweiteilige Blöcke ohne Angabe der Hälfte (TwoPart.expand) bekommen die zweite gleich dazu: „sunflower“ wird
         * zu unterer und oberer Hälfte, „red_bed“ zu Fuß und Kopf. Vorher waren hohe Pflanzen und Betten gesperrt.
         */
        void put(Pos p, BlockSpec spec) throws PlanException {
            TwoPart.Pair pair = TwoPart.expand(p, spec);
            if (pair != null) {
                putOne(pair.firstPos(), pair.first());
                putOne(pair.secondPos(), pair.second());
                return;
            }
            putOne(p, spec);
        }

        private void putOne(Pos p, BlockSpec spec) throws PlanException {
            Cell old = cells.get(p);
            cells.put(p, new Cell(spec, old == null ? step : old.firstStep(), group));
            if (cells.size() > active.maxBlocks()) {
                throw new PlanException("Bauwerk zu groß: mehr als " + active.maxBlocks() + " Blöcke.");
            }
        }
    }

    /** Bis zu diesem Vielfachen von maxExtent wird gekürzt statt abgesagt (siehe compile). */
    static final double CROP_TOLERANCE = 1.3;

    public CompiledBuild compile(BuildPlan plan) throws PlanException {
        List<String> warnings = new ArrayList<>();
        List<String> stepNames = new ArrayList<>();
        Limits active = limits.forPlan(plan);
        Canvas canvas = new Canvas(active);
        checkStackedRoofs(plan);
        for (int i = 0; i < plan.steps().size(); i++) {
            canvas.step = i;
            Step s = plan.steps().get(i);
            stepNames.add(s.op().name().toLowerCase(Locale.ROOT) + " "
                    + (s.op() == Op.MODULE ? s.name() : s.block()));
            // Bauteile (module, logic) ganz oder gar nicht: ihre Zellen tragen die Schrittnummer als Gruppe – außer
            // Bauwerken des Spiels, die Zelle für Zelle geprüft werden (ModuleLibrary.cellByCell).
            canvas.group = s.op() == Op.LOGIC || s.op() == Op.MODULE && !ModuleLibrary.cellByCell(s.name()) ? i + 1
                    : 0;
            emit(s, i + 1, canvas, warnings);
        }
        if (canvas.cells.isEmpty()) {
            throw new PlanException("Der Plan setzt keinen einzigen Block.");
        }
        Pos min = null;
        Pos max = null;
        for (Pos p : canvas.cells.keySet()) {
            min = min == null ? p : new Pos(Math.min(min.x(), p.x()), Math.min(min.y(), p.y()), Math.min(min.z(), p.z()));
            max = max == null ? p : new Pos(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()), Math.max(max.z(), p.z()));
        }
        int ex = max.x() - min.x() + 1;
        int ey = max.y() - min.y() + 1;
        int ez = max.z() - min.z() + 1;
        int limit = active.maxExtent();
        if (ex > limit || ey > limit || ez > limit) {
            // Knapp darüber (bis CROP_TOLERANCE) wird gekürzt statt abgesagt: dev01 2026-10-31, 13×201×5 und
            // 35×3×241 bei 192 – beide nach der Reparaturrunde unverändert zu groß und verworfen. Gekürzt wird die
            // ferne Seite (Plankoordinaten wachsen vom Spieler weg und nach oben); die Grenze selbst hält weiter,
            // der Bau passt danach hinein. Weit darüber bleibt es ein Planfehler.
            long tolerated = (long) Math.floor(limit * CROP_TOLERANCE);
            if (ex > tolerated || ey > tolerated || ez > tolerated) {
                throw new PlanException("Bauwerk zu groß: " + ex + "×" + ey + "×" + ez
                        + ", erlaubt sind " + limit + " je Richtung.");
            }
            Pos lo = min;
            Pos hi = new Pos(min.x() + limit - 1, min.y() + limit - 1, min.z() + limit - 1);
            canvas.cells.keySet().removeIf(p -> p.x() > hi.x() || p.y() > hi.y() || p.z() > hi.z());
            max = lo;
            for (Pos p : canvas.cells.keySet()) {
                max = new Pos(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()), Math.max(max.z(), p.z()));
            }
            warnings.add("Bauwerk " + ex + "×" + ey + "×" + ez + " auf " + limit + " je Richtung gekürzt.");
        }
        List<Action> ordered = order(canvas.cells, warnings);
        return new CompiledBuild(ordered, List.copyOf(warnings), min, max, List.copyOf(stepNames));
    }

    /**
     * Zwei Dächer über überlappenden Grundrissen sind nie gewollt. Gemessen 2026-10-28: Das 7B-Modell
     * stapelte beim Anbau sieben roof-Schritte übereinander (y 4..8 und quer). Abgelehnt geht der
     * Plan mit dieser Meldung in die Reparaturrunde.
     */
    private static void checkStackedRoofs(BuildPlan plan) throws PlanException {
        List<int[]> rects = new ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            Step s = plan.steps().get(i);
            if (s.op() != Op.ROOF) {
                continue;
            }
            int[] r = {Math.min(s.from().x(), s.to().x()), Math.max(s.from().x(), s.to().x()),
                    Math.min(s.from().z(), s.to().z()), Math.max(s.from().z(), s.to().z()), i + 1};
            for (int[] o : rects) {
                if (r[0] <= o[1] && o[0] <= r[1] && r[2] <= o[3] && o[2] <= r[3]) {
                    throw new PlanException("Step " + r[4] + " puts a second roof over the area of the roof in step "
                            + o[4] + ". Use exactly one roof step per building part; a roof op builds all its layers "
                            + "itself.");
                }
            }
            rects.add(r);
        }
    }

    // ---------------------------------------------------------------- Grundformen

    private void emit(Step s, int n, Canvas c, List<String> warnings) throws PlanException {
        if (s.op() == Op.MODULE) {
            module(c, s, n, warnings);
            return;
        }
        if (s.op() == Op.LOGIC) {
            // Formel → Raster-Schaltung (LogicCircuit), eingesetzt wie ein Bibliotheksbauteil. Der Parser hat die
            // Formel schon geprüft; ein Fehler hier wäre ein Fehler im Compiler und geht trotzdem verständlich raus.
            try {
                placeModule(c, s, LogicCircuit.compile(s.expr()).module(), warnings);
            } catch (IllegalArgumentException e) {
                throw new PlanException("Schritt " + n + " (logic): " + e.getMessage());
            }
            return;
        }
        BlockSpec raw;
        try {
            raw = BlockSpec.parse(s.block());
        } catch (PlanException e) {
            warnings.add("Schritt " + n + " ausgelassen: " + e.getMessage());
            return;
        }
        Optional<BlockSpec> resolved = BlockRules.resolve(raw, catalog, fallbackId, warnings);
        if (resolved.isEmpty()) {
            return;
        }
        BlockSpec block = decorate(resolved.get(), s);

        switch (s.op()) {
            // fill mit hollow:true baut die Hülle wie op hollow. Vorher massiv – PlanLinter.solidBodies ließ den
            // Schritt aber als hohl durch, Prüfung und Bau widersprachen sich. fill air räumt immer ganz aus.
            case FILL -> box(c, s, block, s.hollow() && !block.isAir() ? Shell.ALL : Shell.NONE);
            case WALLS -> box(c, s, block, Shell.WALLS);
            case HOLLOW -> box(c, s, block, Shell.ALL);
            case LINE -> line(c, s.from(), s.to(), block);
            case SET -> single(c, s.pos(), block);
            case DOOR -> door(c, s.pos(), block, Orientation.isLocalDirection(s.facing()) ? s.facing() : "front",
                    n, warnings);
            case CYLINDER -> cylinder(c, s, block);
            case SPHERE -> sphere(c, s, block);
            case ROOF -> roof(c, s, block, warnings);
            case SKYSCRAPER -> skyscraper(c, s, block, warnings);
            case HOUSE -> house(c, s, block, n, warnings);
            // Unverziert (resolved statt block): decorate hängte facing (= Torseite) an den Mauerstein.
            case CASTLE, WALL, BRIDGE, GARDEN, TREE -> structures.build(s, n, resolved.get(), c::put, warnings);
            case MODULE -> throw new IllegalStateException("oben behandelt");
        }
    }

    /** Geschosshöhe: Decke + 3 Blöcke Raum – mit 1 Block je Stockwerk (wie das 8B-Modell rechnete) wird es ein Klotz. */
    static final int FLOOR_HEIGHT = 4;

    /**
     * Hochhaus aus Grundriss und Geschosszahl. Vorher (dev01, 2026-10-28) entwarf das LLM Hochhäuser selbst: Llama 3.1 8B
     * baute „40 Stockwerke“ als 40 Blöcke hohen Vollzylinder, nach der Fehlermeldung 47 einzelne Wandstreifen, beim
     * „20-stöckigen“ stapelte es Logikgatter in die Fassade. Ein Hochhaus ist aber Wiederholung – das rechnet der
     * Compiler zuverlässig: je Geschoss Decke (Rand im Fassadenblock, innen Boden), drei Reihen Fassade mit Fenstern
     * und Pfeilern alle 4 Blöcke, Innenraum frei, Licht in jeder Decke, Leiterschacht hinten links bis aufs Dach,
     * Eingang vorn in der Mitte, Flachdach mit Brüstung.
     */
    private void skyscraper(Canvas c, Step s, BlockSpec facade, List<String> warnings) throws PlanException {
        int x0 = Math.min(s.from().x(), s.to().x());
        int x1 = Math.max(s.from().x(), s.to().x());
        int z0 = Math.min(s.from().z(), s.to().z());
        int z1 = Math.max(s.from().z(), s.to().z());
        int y0 = Math.min(s.from().y(), s.to().y());
        if (x1 - x0 < 4 || z1 - z0 < 4) {
            throw new PlanException("skyscraper braucht mindestens 5×5 Grundfläche.");
        }
        int floors = s.floors();
        int top = y0 + floors * FLOOR_HEIGHT;
        checkVolume((long) (x1 - x0 + 1) * (z1 - z0 + 1) * (floors * 2L + 2));
        BlockSpec window = named(s.windowBlock().isBlank() ? "glass" : s.windowBlock(), warnings);
        BlockSpec floor = named("polished_andesite", warnings);
        BlockSpec light = named("sea_lantern", warnings);
        BlockSpec air = named("air", warnings);
        BlockSpec ladder = named("ladder", warnings);
        if (air == null) {
            throw new PlanException("skyscraper: Block air fehlt im Katalog.");
        }
        // Fehlt ein Zierblock in dieser Serverfassung, lieber schlichter bauen als den Plan verwerfen.
        window = window != null ? window : facade;
        floor = floor != null ? floor : facade;
        light = light != null ? light : floor;
        ladder = ladder != null ? ladder.with("facing", "front") : air;
        int shaftX = x0 + 1;
        int shaftZ = z1 - 1;
        int midX = (x0 + x1) / 2;
        int midZ = (z0 + z1) / 2;
        for (int f = 0; f <= floors; f++) {
            int yb = y0 + f * FLOOR_HEIGHT;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    BlockSpec slab = edge || f == floors ? facade : floor;
                    if (!edge && x == shaftX && z == shaftZ && f > 0) {
                        slab = ladder;  // Loch in der Decke, die Leiter läuft durch
                    } else if (!edge && f > 0 && x == midX && z == midZ) {
                        slab = light;
                    }
                    c.put(x, yb, z, slab);
                    if (f == floors) {
                        continue;  // Dach: nur die Decke, Brüstung unten
                    }
                    for (int dy = 1; dy < FLOOR_HEIGHT; dy++) {
                        BlockSpec b;
                        if (!edge) {
                            b = x == shaftX && z == shaftZ ? ladder : air;
                        } else {
                            boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                            boolean pillar = corner || (z == z0 || z == z1) && (x - x0) % 4 == 0
                                    || (x == x0 || x == x1) && (z - z0) % 4 == 0;
                            b = pillar ? facade : window;
                        }
                        c.put(x, yb + dy, z, b);
                    }
                }
            }
        }
        // Brüstung und Ausstieg aufs Dach
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x == x0 || x == x1 || z == z0 || z == z1) {
                    c.put(x, top + 1, z, facade);
                }
            }
        }
        // Eingang: 3 breit, 3 hoch, vorn in der Mitte
        for (int x = midX - 1; x <= midX + 1; x++) {
            for (int dy = 1; dy < FLOOR_HEIGHT; dy++) {
                c.put(x, y0 + dy, z0, air);
            }
        }
    }

    /**
     * Wohnhaus aus Grundriss, Geschosszahl und Wandblock. Vorher entwarf das LLM Häuser Block für Block nach dem
     * Prompt-Beispiel (gemessen mit 7B: kopiert das Beispiel, Tür und Fenster teils innen); Geschosse, Leiter und
     * Einrichtung kamen im Beispiel gar nicht vor, außer einer Laterne. Wie beim Hochhaus ist das Wiederholung, die
     * der Compiler sicher rechnet: Boden und Decken im Raster FLOOR_HEIGHT, Eckstämme, Tür vorn
     * Mitte, Leiter hinten links mit Loch in jeder Zwischendecke, Fenster jeden 2. Block auf Augenhöhe, Satteldach und
     * Einrichtung nach Innenmaß. Was nicht in den Katalog passt oder keinen Platz hat, entfällt still – lieber
     * schlichter bauen als den Plan verwerfen.
     */
    private void house(Canvas c, Step s, BlockSpec wall, int n, List<String> warnings) throws PlanException {
        int x0 = Math.min(s.from().x(), s.to().x());
        int x1 = Math.max(s.from().x(), s.to().x());
        int z0 = Math.min(s.from().z(), s.to().z());
        int z1 = Math.max(s.from().z(), s.to().z());
        int y0 = Math.min(s.from().y(), s.to().y());
        int floors = s.floors();
        int top = y0 + floors * FLOOR_HEIGHT;
        checkVolume((long) (x1 - x0 + 1) * (z1 - z0 + 1) * (top - y0 + 1));

        String wood = wall.id().endsWith("_planks")
                ? wall.id().substring(0, wall.id().length() - "_planks".length()) : "oak";
        BlockSpec air = BlockSpec.of("air");
        // Eckstämme nur zu Brettern derselben Holzart; Netherholz hat _stem statt _log.
        BlockSpec corner = wall.id().endsWith("_planks") ? firstKnown(wood + "_log", wood + "_stem") : null;
        corner = corner != null ? corner : wall;
        BlockSpec window = s.windowBlock().isBlank() ? firstKnown("glass_pane") : namedOrWarn(s.windowBlock(), n,
                warnings);
        window = window != null ? window : wall;
        BlockSpec ladder = firstKnown("ladder");
        ladder = ladder != null ? ladder.with("facing", "front") : air;
        // Einstöckig führt die Leiter nirgendwohin (die oberste Decke bleibt zu) – dann keine.
        boolean withLadder = floors > 1;
        int ladderX = x0 + 1;
        int ladderZ = z1 - 1;
        int midX = (x0 + x1) / 2;

        for (int f = 0; f <= floors; f++) {
            int yb = y0 + f * FLOOR_HEIGHT;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    boolean isCorner = (x == x0 || x == x1) && (z == z0 || z == z1);
                    BlockSpec slab = wall;
                    if (withLadder && f > 0 && f < floors && x == ladderX && z == ladderZ) {
                        // Deckenloch nur in den Zwischendecken; die oberste Decke trägt das Dach und bleibt zu.
                        slab = ladder;
                    } else if (isCorner && f > 0 && f < floors) {
                        slab = corner;  // Eckstamm läuft durch die Zwischendecke durch
                    }
                    c.put(x, yb, z, slab);
                    if (f == floors) {
                        continue;
                    }
                    for (int dy = 1; dy < FLOOR_HEIGHT; dy++) {
                        BlockSpec b;
                        if (!edge) {
                            b = withLadder && x == ladderX && z == ladderZ ? ladder : air;
                        } else if (isCorner) {
                            b = corner;
                        } else {
                            b = dy == 2 && houseWindow(x, z, x0, x1, z0, z1, midX, ladderX) ? window : wall;
                        }
                        c.put(x, yb + dy, z, b);
                    }
                }
            }
            if (f < floors) {
                furnish(c, yb, x0, x1, z0, z1, midX, ladderX, ladderZ, wood);
            }
        }

        BlockSpec door = firstKnown(wood + "_door", "oak_door");
        if (door != null) {
            door(c, new Pos(midX, y0 + 1, z0), door, "front", n, warnings);
        } else {
            c.put(midX, y0 + 1, z0, air);
            c.put(midX, y0 + 2, z0, air);
        }

        BlockSpec roof = s.roofBlock().isBlank() ? null : namedOrWarn(s.roofBlock(), n, warnings);
        if (roof == null) {
            roof = firstKnown(stairsCandidates(wall.id()));
        }
        if (roof == null) {
            roof = firstKnown("oak_stairs");
        }
        roof = roof != null ? roof : wall;
        // Erste Dachschicht auf Höhe der obersten Decke: Mit Überstand 1 liegen ihre Treppen außerhalb der Wände,
        // der Giebel (Wandblock) setzt die Wand in derselben Schicht fort – keine Lücke zwischen Wand und Dach.
        Step roofStep = new Step(Op.ROOF, roof.asString(), new Pos(x0, top, z0), new Pos(x1, top, z1), null, 0, 1,
                false, false, "gable", 1, wall.asString(), "", "", 0, "", "", "");
        roof(c, roofStep, roof, warnings);
    }

    /**
     * Fenster jeden 2. Block. Nie in Ecken (Eckstamm), nie in der Türachse (vorn, alle Geschosse – über der Tür
     * sitzt sonst ein einzelnes Fenster schief zur Reihe) und nie hinter der Leiter: Eine Leiter hängt an dem Block
     * hinter ihr; an einer Scheibe hält sie nicht und fällt beim nächsten Nachbar-Update ab.
     */
    private static boolean houseWindow(int x, int z, int x0, int x1, int z0, int z1, int midX, int ladderX) {
        boolean frontOrBack = z == z0 || z == z1;
        if (frontOrBack) {
            if (z == z0 && x == midX || z == z1 && x == ladderX) {
                return false;
            }
            return x != x0 && x != x1 && (x - x0) % 2 == 1;
        }
        return z != z0 && z != z1 && (z - z0) % 2 == 1;
    }

    /**
     * Einrichtung eines Geschosses, nur hinten rechts und an der rechten Wand. Frei bleiben die Leiterzelle, die
     * Zelle davor und der Gang Tür → Leiter (Türachse nach hinten, dann vor der Rückwand nach links) – sonst steht
     * der Spieler vor einer Truhe statt vor der Leiter. Gestaffelt nach Innenmaß (das kleinere von Breite und Tiefe).
     */
    private void furnish(Canvas c, int yb, int x0, int x1, int z0, int z1, int midX, int ladderX, int ladderZ,
            String wood) throws PlanException {
        int ix0 = x0 + 1;
        int ix1 = x1 - 1;
        int iz0 = z0 + 1;
        int iz1 = z1 - 1;
        int inner = Math.min(ix1 - ix0 + 1, iz1 - iz0 + 1);
        Set<Pos> taken = new HashSet<>();
        taken.add(new Pos(ladderX, 0, ladderZ));
        taken.add(new Pos(ladderX, 0, ladderZ - 1));
        for (int z = iz0; z <= ladderZ - 1; z++) {
            taken.add(new Pos(midX, 0, z));
        }
        for (int x = ladderX; x <= midX; x++) {
            taken.add(new Pos(x, 0, ladderZ - 1));
        }
        int y = yb + 1;
        if (inner >= 3) {
            place(c, taken, ix0, ix1, iz0, iz1, ix1, y, iz1, firstKnown("crafting_table"));
            place(c, taken, ix0, ix1, iz0, iz1, ix1 - 1, y, iz1, facing(firstKnown("chest"), "front"));
        }
        if (inner >= 5) {
            place(c, taken, ix0, ix1, iz0, iz1, ix1 - 2, y, iz1, facing(firstKnown("furnace"), "front"));
            // Hängelaterne unter der Decke, rechts neben der Raummitte – nie unter dem Leiterloch.
            BlockSpec lantern = firstKnown("lantern");
            int lx = midX + 1;
            int lz = (iz0 + iz1) / 2;
            if (lantern != null && lx <= ix1 && !(lx == ladderX && lz == ladderZ)) {
                c.put(lx, yb + FLOOR_HEIGHT - 1, lz, lantern.with("hanging", "true"));
            }
        }
        if (inner >= 7) {
            // Tisch an der rechten Wand, je ein Stuhl davor und dahinter; die Lehne (facing) zeigt vom Tisch weg.
            int tz = iz1 - 3;
            BlockSpec fence = firstKnown(wood + "_fence", "oak_fence");
            BlockSpec chair = firstKnown(wood + "_stairs", "oak_stairs");
            Pos table = new Pos(ix1, 0, tz);
            Pos chairFront = new Pos(ix1, 0, tz - 1);
            Pos chairBack = new Pos(ix1, 0, tz + 1);
            if (fence != null && chair != null && tz - 1 >= iz0 && !taken.contains(table)
                    && !taken.contains(chairFront) && !taken.contains(chairBack)) {
                place(c, taken, ix0, ix1, iz0, iz1, ix1, y, tz, fence);
                BlockSpec plate = firstKnown(wood + "_pressure_plate", "oak_pressure_plate");
                if (plate != null) {
                    c.put(ix1, y + 1, tz, plate);
                }
                BlockSpec seat = chair.with("half", "bottom");
                place(c, taken, ix0, ix1, iz0, iz1, ix1, y, tz - 1, seat.with("facing", "front"));
                place(c, taken, ix0, ix1, iz0, iz1, ix1, y, tz + 1, seat.with("facing", "back"));
            }
            // Regale an der rechten Wand vor dem Tisch, nur 1 hoch – darüber liegt die Fensterreihe.
            BlockSpec shelf = firstKnown("bookshelf");
            for (int z = iz0; z <= tz - 2; z++) {
                place(c, taken, ix0, ix1, iz0, iz1, ix1, y, z, shelf);
            }
        }
        if (inner >= 9) {
            BlockSpec carpet = firstKnown("red_carpet");
            int tz = iz1 - 3;
            for (int x = ix1 - 2; x <= ix1 - 1; x++) {
                for (int z = tz - 1; z <= tz + 1; z++) {
                    place(c, taken, ix0, ix1, iz0, iz1, x, y, z, carpet);
                }
            }
        }
    }

    /** Setzt ein Möbelstück nur auf eine freie Innenzelle; belegt sie danach. Fehlt der Block, entfällt es still. */
    private static void place(Canvas c, Set<Pos> taken, int ix0, int ix1, int iz0, int iz1, int x, int y, int z,
            BlockSpec spec) throws PlanException {
        Pos cell = new Pos(x, 0, z);
        if (spec == null || x < ix0 || x > ix1 || z < iz0 || z > iz1 || taken.contains(cell)) {
            return;
        }
        c.put(x, y, z, spec);
        taken.add(cell);
    }

    private static BlockSpec facing(BlockSpec spec, String dir) {
        return spec == null ? null : spec.with("facing", dir);
    }

    /** Treppe zum Wandblock: spruce_planks → spruce_stairs, stone_bricks → stone_brick_stairs (auch für Structures). */
    static String[] stairsCandidates(String wallId) {
        List<String> out = new ArrayList<>(3);
        if (wallId.endsWith("_planks")) {
            out.add(wallId.substring(0, wallId.length() - "_planks".length()) + "_stairs");
        }
        if (wallId.endsWith("_block")) {
            out.add(wallId.substring(0, wallId.length() - "_block".length()) + "_stairs");
        }
        if (wallId.endsWith("s")) {
            out.add(wallId.substring(0, wallId.length() - 1) + "_stairs");
        }
        out.add(wallId + "_stairs");
        return out.toArray(String[]::new);
    }

    /**
     * Erster Block der Liste, den der Server kennt und der nicht eingeschränkt ist – ohne Ersatzblock und ohne
     * Warnung. Für Zubehör, das der Compiler selbst wählt: Fehlt es in dieser Serverfassung, fällt es weg, statt als
     * Ersatzblock (Stein) mitten im Raum zu stehen.
     */
    private BlockSpec firstKnown(String... ids) {
        for (String id : ids) {
            if (!BlockRules.isRestricted(id) && catalog.isBlock(id)) {
                return BlockSpec.of(id);
            }
        }
        return null;
    }

    /** Vom LLM genannter Zusatzblock (window_block, roof_block): wie block durch den Katalog. */
    private BlockSpec namedOrWarn(String raw, int n, List<String> warnings) {
        try {
            return BlockRules.resolve(BlockSpec.parse(raw), catalog, fallbackId, warnings).orElse(null);
        } catch (PlanException e) {
            warnings.add("Schritt " + n + ": " + e.getMessage());
            return null;
        }
    }

    /** Fester Blockname des Compilers (nicht vom LLM) – trotzdem durch den Katalog. */
    private BlockSpec named(String id, List<String> warnings) throws PlanException {
        Optional<BlockSpec> r = BlockRules.resolve(BlockSpec.parse(id), catalog, fallbackId, warnings);
        return r.orElse(null);
    }

    /**
     * Bauteil aus der Bibliothek: gedreht, damit seine Vorderseite zur gewünschten Seite zeigt,
     * dann ab pos (linke vordere untere Ecke) eingesetzt. Ob der Auftraggeber jede Zelle darf, prüft BuildJob beim
     * Setzen – für das ganze Bauteil auf einmal (Gruppe, ganz oder gar nicht).
     */
    private void module(Canvas c, Step s, int n, List<String> warnings) throws PlanException {
        Optional<ModuleLibrary.Module> found = modules.find(s.name());
        if (found.isEmpty()) {
            warnings.add("Schritt " + n + ": Bauteil '" + s.name() + "' gibt es nicht, ausgelassen");
            return;
        }
        placeModule(c, s, found.get(), warnings);
    }

    /** Bauteil gedreht einsetzen: Vorderseite zur Seite facing, linke vordere untere Ecke bei pos. */
    private void placeModule(Canvas c, Step s, ModuleLibrary.Module m, List<String> warnings) throws PlanException {
        int turns = Orientation.quarterTurnsFor(s.facing().isEmpty() ? "front" : s.facing());
        // Grenze des Plans (Limits.forPlan): Ein einzelnes Schematic darf so groß sein wie schematics.max-blöcke.
        if (m.cells().size() > (long) c.active.maxBlocks() * 2) {
            throw new PlanException("Ein Schritt ist zu groß (" + m.cells().size() + " Blöcke, erlaubt "
                    + c.active.maxBlocks() + ").");
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        List<Change> rotated = new ArrayList<>(m.cells().size());
        for (Change cell : m.cells()) {
            Pos p = Orientation.rotateLocal(cell.pos(), turns);
            minX = Math.min(minX, p.x());
            minZ = Math.min(minZ, p.z());
            rotated.add(new Change(p, Orientation.rotateLocal(cell.block(), turns)));
        }
        int mx = minX;
        int mz = minZ;
        Pos at = s.pos();
        for (Change cell : rotated) {
            Optional<BlockSpec> spec = BlockRules.resolve(cell.block(), catalog, fallbackId, warnings);
            if (spec.isPresent()) {
                Pos p = cell.pos();
                // Platzhalter im Befehl ({x,y,z}) sind Bauteil-Koordinaten: dieselbe Drehung und Verschiebung wie die
                // Zellen, sonst zeigte ein Befehl nach dem Drehen ins Leere.
                BlockSpec placed = spec.get().mapPlaceholders(q -> {
                    Pos r = Orientation.rotateLocal(q, turns);
                    return at.add(r.x() - mx, r.y(), r.z() - mz);
                });
                c.put(at.add(p.x() - minX, p.y(), p.z() - minZ), placed);
            }
        }
    }

    /** Zustände, die ein Spieler beim Setzen automatisch bekäme. */
    private BlockSpec decorate(BlockSpec block, Step s) {
        BlockSpec out = block;
        // HOUSE: block ist der Wandblock (Bretter, Stein) – ein facing daran ergäbe einen ungültigen Blockzustand.
        if (!s.facing().isEmpty() && s.op() != Op.DOOR && s.op() != Op.ROOF && s.op() != Op.HOUSE
                && !out.props().containsKey("facing")) {
            out = out.with("facing", s.facing());
        }
        // Von Spielern gesetztes Laub ist persistent; ohne das zerfällt die Hecke nach Sekunden.
        if (out.id().endsWith("_leaves") && !out.props().containsKey("persistent")) {
            out = out.with("persistent", "true");
        }
        return out;
    }

    private enum Shell {
        NONE, WALLS, ALL
    }

    private void box(Canvas c, Step s, BlockSpec block, Shell shell) throws PlanException {
        int x0 = Math.min(s.from().x(), s.to().x());
        int x1 = Math.max(s.from().x(), s.to().x());
        int y0 = Math.min(s.from().y(), s.to().y());
        int y1 = Math.max(s.from().y(), s.to().y());
        int z0 = Math.min(s.from().z(), s.to().z());
        int z1 = Math.max(s.from().z(), s.to().z());
        checkVolume((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1));
        if (block.isDoor()) {
            // Eine "Tür-Füllung" ist fast immer als einzelne Tür gemeint.
            door(c, new Pos(x0, y0, z0), block, "front", -1, null);
            return;
        }
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    boolean side = x == x0 || x == x1 || z == z0 || z == z1;
                    boolean cap = y == y0 || y == y1;
                    boolean keep = switch (shell) {
                        case NONE -> true;
                        case WALLS -> side;
                        case ALL -> side || cap;
                    };
                    if (keep) {
                        c.put(x, y, z, block);
                    }
                }
            }
        }
    }

    private void single(Canvas c, Pos p, BlockSpec block) throws PlanException {
        if (block.isDoor()) {
            door(c, p, block, "front", -1, null);
            return;
        }
        c.put(p, block);
    }

    /** Gerade Linie: auf der längsten Achse jeden Block, die anderen gerundet. */
    private void line(Canvas c, Pos a, Pos b, BlockSpec block) throws PlanException {
        int dx = b.x() - a.x();
        int dy = b.y() - a.y();
        int dz = b.z() - a.z();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        checkVolume(steps + 1L);
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            c.put((int) Math.round(a.x() + dx * t), (int) Math.round(a.y() + dy * t),
                    (int) Math.round(a.z() + dz * t), block);
        }
    }

    /**
     * side = Wandseite, in der die Tür sitzt. Minecraft-facing ist die Blickrichtung des
     * Spielers, der sie von außen setzt – also die Gegenrichtung. Das LLM soll nur sagen
     * "Tür vorne"; die Umkehrung hier hält das aus dem Prompt heraus.
     */
    private void door(Canvas c, Pos p, BlockSpec block, String side, int n, List<String> warnings)
            throws PlanException {
        if (!block.isDoor()) {
            if (warnings != null) {
                warnings.add("Schritt " + n + ": " + block.id() + " ist keine Tür, gesetzt als Block");
            }
            c.put(p, block);
            return;
        }
        BlockSpec base = block;
        if (!base.props().containsKey("facing")) {
            base = base.with("facing", oppositeLocal(side));
        }
        if (!base.props().containsKey("hinge")) {
            base = base.with("hinge", "left");
        }
        c.put(p, base.with("half", "lower"));
        c.put(p.up(), base.with("half", "upper"));
    }

    private static String oppositeLocal(String dir) {
        return switch (dir) {
            case "front" -> "back";
            case "back" -> "front";
            case "left" -> "right";
            default -> "left";
        };
    }

    private void cylinder(Canvas c, Step s, BlockSpec block) throws PlanException {
        int r = s.radius();
        int h = s.height();
        checkVolume((long) (2 * r + 1) * (2 * r + 1) * h);
        Pos base = s.pos();
        for (int dy = 0; dy < h; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (!inDisc(dx, dz, r)) {
                        continue;
                    }
                    boolean edge = !(inDisc(dx + 1, dz, r) && inDisc(dx - 1, dz, r)
                            && inDisc(dx, dz + 1, r) && inDisc(dx, dz - 1, r));
                    if (!s.hollow() || edge) {
                        c.put(base.add(dx, dy, dz), block);
                    }
                }
            }
        }
    }

    /**
     * Öffentlich für structure.PlanDecompiler: Er erkennt Zylinder an genau dieser Form, sonst stimmte der Rückbau
     * nicht (PlanDecompilerTest.ownHouseRoundTripsExactlyFromEverySide).
     */
    public static boolean inDisc(int dx, int dz, int r) {
        return dx * dx + dz * dz < (r + 0.5) * (r + 0.5);
    }

    private void sphere(Canvas c, Step s, BlockSpec block) throws PlanException {
        int r = s.radius();
        checkVolume((long) (2 * r + 1) * (2 * r + 1) * (2 * r + 1));
        Pos m = s.pos();
        for (int dy = s.dome() ? 0 : -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (!inBall(dx, dy, dz, r)) {
                        continue;
                    }
                    boolean edge = !(inBall(dx + 1, dy, dz, r) && inBall(dx - 1, dy, dz, r)
                            && inBall(dx, dy + 1, dz, r) && inBall(dx, dy - 1, dz, r)
                            && inBall(dx, dy, dz + 1, r) && inBall(dx, dy, dz - 1, r));
                    if (!s.hollow() || edge) {
                        c.put(m.add(dx, dy, dz), block);
                    }
                }
            }
        }
    }

    private static boolean inBall(int dx, int dy, int dz, int r) {
        return dx * dx + dy * dy + dz * dz < (r + 0.5) * (r + 0.5);
    }

    /**
     * Dach über dem Wandrechteck from..to, erste Schicht auf from.y. Treppen zeigen mit der
     * hohen Seite zur Mitte; die Eckform (outer/inner) berechnet erst das Plugin am echten
     * Nachbarn, genau wie Minecraft beim Setzen durch einen Spieler.
     */
    private void roof(Canvas c, Step s, BlockSpec block, List<String> warnings) throws PlanException {
        int minX = Math.min(s.from().x(), s.to().x());
        int maxX = Math.max(s.from().x(), s.to().x());
        int minZ = Math.min(s.from().z(), s.to().z());
        int maxZ = Math.max(s.from().z(), s.to().z());
        int baseY = Math.min(s.from().y(), s.to().y());
        int o = s.overhang();
        int x0 = minX - o;
        int x1 = maxX + o;
        int z0 = minZ - o;
        int z1 = maxZ + o;
        checkVolume((long) (x1 - x0 + 1) * (z1 - z0 + 1) * 2);

        boolean stairs = block.isStairs();
        BlockSpec top = stairs ? slabFor(block) : block;
        BlockSpec gable = null;
        if (!s.gableBlock().isBlank()) {
            try {
                gable = BlockRules.resolve(BlockSpec.parse(s.gableBlock()), catalog, fallbackId, warnings)
                        .orElse(null);
            } catch (PlanException e) {
                warnings.add("Giebelblock ausgelassen: " + e.getMessage());
            }
        }

        switch (s.shape()) {
            case "flat" -> {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        c.put(x, baseY, z, top);
                    }
                }
            }
            case "hip" -> {
                for (int i = 0; i < MAX_ROOF_LAYERS; i++) {
                    int xa = x0 + i;
                    int xb = x1 - i;
                    int za = z0 + i;
                    int zb = z1 - i;
                    if (xa > xb || za > zb) {
                        break;
                    }
                    int y = baseY + i;
                    if (xa == xb || za == zb) {
                        for (int z = za; z <= zb; z++) {
                            for (int x = xa; x <= xb; x++) {
                                c.put(x, y, z, top);
                            }
                        }
                        break;
                    }
                    for (int x = xa; x <= xb; x++) {
                        c.put(x, y, za, slope(block, stairs, "back"));
                        c.put(x, y, zb, slope(block, stairs, "front"));
                    }
                    for (int z = za + 1; z < zb; z++) {
                        c.put(xa, y, z, slope(block, stairs, "right"));
                        c.put(xb, y, z, slope(block, stairs, "left"));
                    }
                }
            }
            default -> {
                // Satteldach: First entlang der längeren Seite, wie man es von Hand macht.
                boolean ridgeAlongX = (x1 - x0) >= (z1 - z0);
                int lo = ridgeAlongX ? z0 : x0;
                int hi = ridgeAlongX ? z1 : x1;
                int ridgeLo = ridgeAlongX ? x0 : z0;
                int ridgeHi = ridgeAlongX ? x1 : z1;
                int wallLo = ridgeAlongX ? minZ : minX;
                int wallHi = ridgeAlongX ? maxZ : maxX;
                int gableA = ridgeAlongX ? minX : minZ;
                int gableB = ridgeAlongX ? maxX : maxZ;
                for (int i = 0; i < MAX_ROOF_LAYERS && lo + i <= hi - i; i++) {
                    int y = baseY + i;
                    int a = lo + i;
                    int b = hi - i;
                    for (int r = ridgeLo; r <= ridgeHi; r++) {
                        if (a == b) {
                            putRoof(c, ridgeAlongX, r, y, a, top);
                        } else {
                            putRoof(c, ridgeAlongX, r, y, a, slope(block, stairs, ridgeAlongX ? "back" : "right"));
                            putRoof(c, ridgeAlongX, r, y, b, slope(block, stairs, ridgeAlongX ? "front" : "left"));
                        }
                    }
                    if (gable != null) {
                        for (int t = Math.max(a + 1, wallLo); t <= Math.min(b - 1, wallHi); t++) {
                            putRoof(c, ridgeAlongX, gableA, y, t, gable);
                            putRoof(c, ridgeAlongX, gableB, y, t, gable);
                        }
                    }
                }
            }
        }
    }

    /** r = Koordinate entlang des Firsts, t = quer dazu. */
    private static void putRoof(Canvas c, boolean ridgeAlongX, int r, int y, int t, BlockSpec spec)
            throws PlanException {
        if (ridgeAlongX) {
            c.put(r, y, t, spec);
        } else {
            c.put(t, y, r, spec);
        }
    }

    private static BlockSpec slope(BlockSpec block, boolean stairs, String facing) {
        return stairs ? block.with("facing", facing).with("half", "bottom") : block;
    }

    /** oak_stairs → oak_slab; gibt es die Platte nicht, bleibt die Treppe auch am First. */
    private BlockSpec slabFor(BlockSpec stairs) {
        String slabId = stairs.id().substring(0, stairs.id().length() - "_stairs".length()) + "_slab";
        if (catalog.isBlock(slabId)) {
            return BlockSpec.of(slabId).with("type", "bottom");
        }
        return stairs.with("facing", "back").with("half", "bottom");
    }

    private void checkVolume(long volume) throws PlanException {
        if (volume > (long) limits.maxBlocks() * 2) {
            throw new PlanException("Ein Schritt ist zu groß (" + volume + " Blöcke, erlaubt "
                    + limits.maxBlocks() + ").");
        }
    }

    // ---------------------------------------------------------------- Reihenfolge

    private List<Action> order(Map<Pos, Cell> cells, List<String> warnings) {
        // Zweiteilige Blöcke (TwoPart): die zweite Hälfte hängt an der ersten und wird mit ihr in einem Klick gesetzt.
        TreeMap<Integer, List<Action>> byStep = new TreeMap<>();
        int halves = 0;
        for (Map.Entry<Pos, Cell> e : cells.entrySet()) {
            Pos p = e.getKey();
            Cell cell = e.getValue();
            BlockSpec spec = cell.spec();
            if (TwoPart.isSecond(spec)) {
                // Kommt mit der ersten Hälfte; ohne sie (überschrieben) würde sie sofort zerfallen. Eine Truhe ohne
                // Partner bleibt als einzelne Truhe stehen (Minecraft macht sie beim nächsten Nachbar-Update dazu).
                Pos first = firstOf(p, spec, cells);
                if (first != null || !TwoPart.isChest(spec)) {
                    if (first == null) {
                        halves++;
                    }
                    continue;
                }
            }
            List<Change> changes = new ArrayList<>(2);
            changes.add(new Change(p, spec, cell.group()));
            if (TwoPart.isFirst(spec)) {
                Pos partner = TwoPart.partnerOf(p, spec);
                Cell other = partner == null ? null : cells.get(partner);
                if (other != null && TwoPart.matches(spec, other.spec())) {
                    changes.add(new Change(partner, other.spec(), other.group()));
                } else if (!TwoPart.isChest(spec)) {
                    // Ohne zweite Hälfte keine Tür, kein Bett, keine hohe Pflanze – sonst steht eine halbe herum.
                    halves++;
                    continue;
                }
            }
            int step = cell.firstStep();
            byStep.computeIfAbsent(step, k -> new ArrayList<>()).add(new Action(step, List.copyOf(changes)));
        }
        if (halves > 0) {
            warnings.add(halves + " halbe zweiteilige Blöcke (Tür, Bett, hohe Pflanze) ausgelassen");
        }

        List<Action> out = new ArrayList<>(cells.size());
        Pos cursor = new Pos(0, 0, -3);
        for (List<Action> group : byStep.values()) {
            List<Action> removals = new ArrayList<>();
            List<Action> placements = new ArrayList<>();
            List<Action> attached = new ArrayList<>();
            for (Action a : group) {
                if (a.isRemoval()) {
                    removals.add(a);
                } else if (needsSupport(a.changes().getFirst().block().id())) {
                    attached.add(a);
                } else {
                    placements.add(a);
                }
            }
            // Abreißen von oben nach unten, Bauen von unten nach oben – sonst hängt etwas in der Luft.
            // Angehängtes (Fackeln, Hebel, Redstone, Schienen …) erst, wenn sein Halt steht: Vorher
            // gesetzt, fällt es beim nächsten Nachbar-Update als Gegenstand ab – wie die Fackel, die
            // ein Spieler an eine Wand hängen will, die es noch nicht gibt.
            cursor = layered(removals, Comparator.comparingInt((Action a) -> -a.anchor().y()), cursor, out);
            cursor = layered(placements, Comparator.comparingInt((Action a) -> a.anchor().y()), cursor, out);
            cursor = layered(attached, Comparator.comparingInt((Action a) -> a.anchor().y()), cursor, out);
        }
        return List.copyOf(out);
    }

    private static final List<String> SUPPORT_MARKERS = List.of("torch", "lever", "button", "redstone_wire",
            "rail", "ladder", "sign", "carpet", "pressure_plate", "repeater", "comparator", "banner", "vine",
            "tripwire", "candle", "flower_pot", "lantern", "item_frame", "bell");

    /**
     * Vollblöcke, die nur wegen eines Wortteils („lantern“) auf die Markerliste fielen. Vorher galten Seelaterne und
     * Kürbislaterne als anhängend: Der Linter meldete eine frei stehende Säule aus Seelaternen als „ohne Halt“,
     * PlanReview ließ sie weg, und eine Fackel darauf galt als haltlos (gesehen 2026-11-01, 64 Meldungen an einer echten
     * Schematic). Geprüft gegen alle Blocknamen von 1.21.11 mit einem Marker-Wortteil: sonst keine Vollblöcke.
     */
    private static final java.util.Set<String> FULL_BLOCKS = java.util.Set.of("sea_lantern", "jack_o_lantern");

    static boolean needsSupport(String id) {
        if (id.endsWith("_door") || id.endsWith("_trapdoor") || FULL_BLOCKS.contains(id)) {
            return false;
        }
        for (String m : SUPPORT_MARKERS) {
            if (id.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** Stelle der ersten Hälfte, zu der die zweite Hälfte an p gehört – oder null, wenn es sie nicht gibt. */
    private static Pos firstOf(Pos p, BlockSpec second, Map<Pos, Cell> cells) {
        Pos[] candidates;
        if (TwoPart.isVertical(second)) {
            candidates = new Pos[] {p.down()};
        } else {
            candidates = new Pos[] {p.add(1, 0, 0), p.add(-1, 0, 0), p.add(0, 0, 1), p.add(0, 0, -1)};
        }
        for (Pos c : candidates) {
            Cell first = cells.get(c);
            if (first != null && TwoPart.isFirst(first.spec()) && p.equals(TwoPart.partnerOf(c, first.spec()))
                    && TwoPart.matches(first.spec(), second)) {
                return c;
            }
        }
        return null;
    }

    private static Pos layered(List<Action> actions, Comparator<Action> layerOrder, Pos cursor, List<Action> out) {
        actions.sort(layerOrder);
        int i = 0;
        while (i < actions.size()) {
            int y = actions.get(i).anchor().y();
            int j = i;
            while (j < actions.size() && actions.get(j).anchor().y() == y) {
                j++;
            }
            cursor = nearestNeighbour(new ArrayList<>(actions.subList(i, j)), cursor, out);
            i = j;
        }
        return cursor;
    }

    /** Ab so vielen Blöcken je Schicht sucht ein Raster; darunter ist die einfache Suche schneller. */
    static final int GRID_FROM = 256;

    /**
     * Gierig zum nächsten Block – der NPC läuft so an der Wand entlang statt kreuz und quer.
     *
     * Vorher suchte jeder Schritt alle übrigen Blöcke der Schicht ab, quadratisch in der Schichtgröße. Die riesige
     * Lobby hat Schichten mit 6 241 Zellen: 1,1 s je Übersetzung auf dem PC, und auf dev01 stand der Server dafür
     * zweimal über 10 s (Paper-Watchdog 2026-11-15 20:10 und 20:14, Stack hier; nach dem Verschieben übersetzt
     * acceptPlan ein zweites Mal). Jetzt sucht ein Raster Ring um Ring um die letzte Stelle, bis kein näherer Block mehr
     * möglich ist. Die Reihenfolge bleibt Block für Block dieselbe (kleinster Abstand, dann kleineres x, dann z),
     * PlanCompilerOrderTest vergleicht mit der alten Suche. Wer das zurückdreht, friert den Server bei großen Bauten
     * wieder ein.
     */
    static Pos nearestNeighbour(List<Action> layer, Pos cursor, List<Action> out) {
        if (layer.size() < GRID_FROM) {
            return nearestNeighbourLinear(layer, cursor, out);
        }
        Map<Long, Action> grid = new HashMap<>(layer.size() * 2);
        for (Action a : layer) {
            if (grid.put(flatKey(a.anchor().x(), a.anchor().z()), a) != null) {
                // Zwei Aktionen auf einer Stelle liefert der Compiler nicht; falls doch, die alte Suche statt Verlust.
                return nearestNeighbourLinear(layer, cursor, out);
            }
        }
        Pos current = cursor;
        while (!grid.isEmpty()) {
            Action next = nearestInGrid(grid, current);
            grid.remove(flatKey(next.anchor().x(), next.anchor().z()));
            out.add(next);
            current = next.anchor();
        }
        return current;
    }

    /**
     * Ring um Ring um from; fertig, sobald jeder weitere Ring weiter weg läge als der beste Fund (nach Ring r liegt
     * alles Übrige mindestens r + 1 weit). Wird die abgesuchte Fläche größer als die Zahl der übrigen Blöcke, zählt
     * sie diese direkt durch – dann ist das billiger, und ein Sprung zur nächsten Gruppe kostet nie mehr als vorher.
     */
    private static Action nearestInGrid(Map<Long, Action> grid, Pos from) {
        Action best = null;
        long bestDist = Long.MAX_VALUE;
        int cx = from.x();
        int cz = from.z();
        for (int r = 0; ; r++) {
            if ((long) (2 * r + 1) * (2 * r + 1) > grid.size()) {
                for (Action a : grid.values()) {
                    long d = flatDistance(a.anchor(), from);
                    if (best == null || d < bestDist || d == bestDist && tieBreak(a.anchor(), best.anchor()) < 0) {
                        best = a;
                        bestDist = d;
                    }
                }
                return best;
            }
            for (int dx = -r; dx <= r; dx++) {
                boolean edge = dx == -r || dx == r;
                for (int dz = -r; dz <= r; dz += edge || r == 0 ? 1 : 2 * r) {
                    Action a = grid.get(flatKey(cx + dx, cz + dz));
                    if (a == null) {
                        continue;
                    }
                    long d = flatDistance(a.anchor(), from);
                    if (best == null || d < bestDist || d == bestDist && tieBreak(a.anchor(), best.anchor()) < 0) {
                        best = a;
                        bestDist = d;
                    }
                }
            }
            if (best != null && bestDist < (long) (r + 1) * (r + 1)) {
                return best;
            }
        }
    }

    /**
     * Eindeutig für jedes (x, z) und gut gestreut. Vorher nur „x << 32 | z“: Long.hashCode macht daraus x ^ z, auf einem
     * 81 × 81-Feld gut 100 verschiedene Werte – die HashMap kippte in Baumknoten (Flight Recorder 2026-11-15: die
     * meisten Proben in HashMap.getNode und TreeNode). Mal einer ungeraden Zahl ist modulo 2^64 umkehrbar, also
     * bleibt jeder Schlüssel eindeutig.
     */
    private static long flatKey(int x, int z) {
        return ((long) x << 32 | z & 0xFFFFFFFFL) * 0x9E3779B97F4A7C15L;
    }

    /** Die einfache Suche: jeder Schritt über alle übrigen Blöcke. Für kleine Schichten und als Vergleich im Test. */
    static Pos nearestNeighbourLinear(List<Action> layer, Pos cursor, List<Action> out) {
        Pos current = cursor;
        while (!layer.isEmpty()) {
            int best = 0;
            long bestDist = Long.MAX_VALUE;
            for (int k = 0; k < layer.size(); k++) {
                Pos p = layer.get(k).anchor();
                long d = flatDistance(p, current);
                if (d < bestDist || (d == bestDist && tieBreak(p, layer.get(best).anchor()) < 0)) {
                    best = k;
                    bestDist = d;
                }
            }
            Action next = layer.get(best);
            layer.set(best, layer.getLast());
            layer.removeLast();
            out.add(next);
            current = next.anchor();
        }
        return current;
    }

    /** Innerhalb einer Schicht zählt nur der Weg in der Ebene; der Höhensprung zur nächsten Schicht ist fix. */
    private static long flatDistance(Pos a, Pos b) {
        long dx = a.x() - b.x();
        long dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private static int tieBreak(Pos a, Pos b) {
        return a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z());
    }
}
