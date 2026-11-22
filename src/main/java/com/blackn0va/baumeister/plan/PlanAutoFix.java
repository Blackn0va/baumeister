package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.BuildPlan.Op;
import com.blackn0va.baumeister.plan.PlanLinter.Issue;
import com.blackn0va.baumeister.plan.PlanLinter.Rule;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reparaturen ohne LLM – was der Baumeister selbst richten kann, statt den Plan in eine Reparaturrunde zu schicken
 * oder zu verwerfen. Gemessen in allen dev01-Logs bis 2026-10-31: 136 LLM-Anfragen, 55 verworfene Pläne, und die
 * Gründe wiederholten sich – das LLM machte denselben Fehler auch in der Reparaturrunde:
 * 26× Modul ohne erkannten Maschinenwunsch (meist hatte das LLM richtig ein Schloss oder eine Klingel gewählt),
 * 9× Grundriss knapp außerhalb (house 32×1, skyscraper unter 5×5), 2× knapp zu groß (201 bzw. 241 bei 192),
 * 3× schwebende Teile mit 93–712 Blöcken, dazu Vollklötze. Alles hier ist rein und ohne Server testbar
 * (PlanAutoFixTest, je Log-Fall ein Test).
 *
 * Grenze: Hier wird nur gerichtet, was die Bauqualität betrifft. Überbau-Schutz, Ausdehnung, Blockzahl und Rechte
 * prüfen weiter Compiler, PlanReview.checkOverbuild und BuildService – nach diesen Reparaturen, auf dem fertigen Bau.
 */
public final class PlanAutoFix {

    private PlanAutoFix() {
    }

    /** Geänderter Plan-Text und was geändert wurde (deutsch, für Chat, Log und App). */
    public record Fixed(String json, List<String> notes) {
    }

    // ---------------------------------------------------------------- Grundriss

    /** Gültige Seitenlängen je Bauform (wie PlanParser/Structures/PlanCompiler sie prüfen). */
    /** Material, wenn es bei einer Bauform fehlt – wie die Vorgaben in QuickForms (castle/wall/bridge/garden/tree). */
    static final Map<String, String> FORM_BLOCKS = Map.of("castle", "stone_bricks", "wall", "stone_bricks",
            "bridge", "stone_bricks", "garden", "oak_fence", "tree", "oak_log");

    private static final Map<String, int[]> SIDES = Map.of(
            "house", new int[] {PlanParser.MIN_HOUSE_SIDE, PlanParser.MAX_HOUSE_SIDE},
            "skyscraper", new int[] {5, Integer.MAX_VALUE},
            "castle", new int[] {Structures.MIN_CASTLE_SIDE, Structures.MAX_CASTLE_SIDE},
            "garden", new int[] {Structures.MIN_GARDEN_SIDE, Structures.MAX_GARDEN_SIDE});

    /**
     * Grundriss von house, skyscraper, castle und garden in den gültigen Bereich: from bleibt, to rückt nach.
     * Vorher warf der Parser („house braucht einen Grundriss von 5×5 bis 24×24, nicht 32×1“), das LLM bekam die
     * Meldung – und lieferte in 9 von 9 Fällen auf dev01 denselben Grundriss noch einmal (verworfen). Ein Haus mit
     * 24 statt 32 Breite ist näher am Wunsch als gar keins. Unlesbares JSON bleibt unverändert (meldet der Parser).
     */
    public static Fixed footprints(String json) {
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject() || !el.getAsJsonObject().has("steps")
                    || !el.getAsJsonObject().get("steps").isJsonArray()) {
                return new Fixed(json, List.of());
            }
            root = el.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return new Fixed(json, List.of());
        }
        List<String> notes = new ArrayList<>();
        JsonArray steps = root.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).isJsonObject()) {
                continue;
            }
            JsonObject s = steps.get(i).getAsJsonObject();
            String op = s.has("op") && s.get("op").isJsonPrimitive()
                    ? s.get("op").getAsString().toLowerCase(Locale.ROOT) : "";
            // Material fehlt bei einer Bauform, deren Geometrie das Plugin rechnet (2026-11-08, Host-Log, Qwen2.5
            // 14B): „bridge“ ohne block → „Schritt 1: block fehlt“, die Reparaturrunde kam mit demselben Fehler
            // zurück (84 s), der Auftrag „Kanalbrücke“ wurde verworfen. Jetzt die Vorgabe der Schnellaufträge
            // (QuickForms) – eine Steinbrücke ist näher am Wunsch als keine. Vorgaben dort und hier gleich halten.
            String fallback = FORM_BLOCKS.get(op);
            if (fallback != null && (!s.has("block") || !s.get("block").isJsonPrimitive()
                    || s.get("block").getAsString().isBlank())) {
                s.addProperty("block", fallback);
                notes.add("Schritt " + (i + 1) + ": " + op + " ohne Material – " + fallback + " genommen");
            }
            int[] range = SIDES.get(op);
            int[] from = vec(s, "from");
            int[] to = vec(s, "to");
            if (range == null || from == null || to == null) {
                continue;
            }
            int w = Math.abs(to[0] - from[0]) + 1;
            int d = Math.abs(to[2] - from[2]) + 1;
            int nx = clampSide(from[0], to[0], range);
            int nz = clampSide(from[2], to[2], range);
            if (nx != to[0] || nz != to[2]) {
                JsonArray fixed = new JsonArray();
                fixed.add(nx);
                fixed.add(to[1]);
                fixed.add(nz);
                s.add("to", fixed);
                notes.add("Schritt " + (i + 1) + ": " + op + "-Grundriss " + w + "×" + d + " auf "
                        + (Math.abs(nx - from[0]) + 1) + "×" + (Math.abs(nz - from[2]) + 1) + " angepasst");
            }
        }
        return notes.isEmpty() ? new Fixed(json, List.of()) : new Fixed(root.toString(), List.copyOf(notes));
    }

    /** Neues Ende einer Seite: Länge in [min, max], Richtung von from nach to bleibt. */
    private static int clampSide(int from, int to, int[] range) {
        int side = Math.abs(to - from) + 1;
        int want = Math.clamp(side, range[0], range[1]);
        if (want == side) {
            return to;
        }
        int dir = to >= from ? 1 : -1;
        return from + dir * (want - 1);
    }

    private static int[] vec(JsonObject s, String key) {
        if (!s.has(key) || !s.get(key).isJsonArray() || s.getAsJsonArray(key).size() != 3) {
            return null;
        }
        JsonArray a = s.getAsJsonArray(key);
        int[] v = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!a.get(i).isJsonPrimitive() || !a.get(i).getAsJsonPrimitive().isNumber()) {
                return null;
            }
            v[i] = a.get(i).getAsInt();
        }
        return v;
    }

    // ---------------------------------------------------------------- Seite der Bauformen

    /** Bauformen, deren facing eine der vier Seiten sein muss (Structures.side) und bei denen leer die Vorgabe ist. */
    static final Set<String> SIDE_FORMS = Set.of("castle", "wall", "garden");

    /**
     * Ungültiges facing bei castle, wall und garden fällt weg – dann gilt die Vorgabe der Bauform (Tor vorn, Außenseite
     * der Mauer quer zu ihr, siehe Structures.wall). Vorher warf Structures.side („wall: facing muss front, back, left
     * oder right sein“) bzw. der Parser bei einem Wort außerhalb up/down, und das Modell schrieb in der Reparaturrunde
     * dasselbe noch einmal: dev01-Logs bis 2026-11-21, 3× verworfen. Eine Mauer mit der Außenseite nach oben gibt es
     * nicht – welche Seite gemeint war, steht nirgends, die Vorgabe ist so gut wie jede geratene Seite und besser als
     * keine Mauer. Andere Ops (Tür, Kolben, Modul) bleiben unberührt: Dort trägt facing die Funktion, und ein Wegfall
     * wäre geraten.
     */
    public static Fixed facings(String json) {
        JsonObject root = stepsRoot(json);
        if (root == null) {
            return new Fixed(json, List.of());
        }
        List<String> notes = new ArrayList<>();
        JsonArray steps = root.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).isJsonObject()) {
                continue;
            }
            JsonObject s = steps.get(i).getAsJsonObject();
            String op = s.has("op") && s.get("op").isJsonPrimitive()
                    ? s.get("op").getAsString().toLowerCase(Locale.ROOT) : "";
            if (!SIDE_FORMS.contains(op) || !s.has("facing") || s.get("facing").isJsonNull()) {
                continue;
            }
            JsonElement f = s.get("facing");
            String facing = f.isJsonPrimitive() ? f.getAsString().trim().toLowerCase(Locale.ROOT) : f.toString();
            if (facing.isEmpty() || Orientation.isLocalDirection(facing)) {
                continue;
            }
            s.remove("facing");
            notes.add("Schritt " + (i + 1) + ": " + op + "-facing „" + facing + "“ ungültig – Vorgabe der Bauform "
                    + "genommen");
        }
        return notes.isEmpty() ? new Fixed(json, List.of()) : new Fixed(root.toString(), List.copyOf(notes));
    }

    // ---------------------------------------------------------------- Bäume ohne Ort

    /** Abstand der Baumreihe vor dem Bauwerk: Krone bis Radius 7 (QuickForms.tree setzt einen Baum 8 vor den Spieler). */
    static final int TREE_FRONT = 8;

    /**
     * Abstand der Bäume in der Reihe: zwei Kronen mit Radius bis 7 (hoher Laubbaum) berühren sich nicht. Vorher 10 –
     * reichte nur für Radius 4 (Gegenprobe Welle 10, 2026-11-22).
     */
    static final int TREE_SPACING = 14;

    /**
     * tree ohne pos bekommt einen Platz in einer Reihe vor dem übrigen Bauwerk (z = vorderste Kante − TREE_FRONT, auf
     * dem Boden y = 0, mittig nebeneinander). Gemessen 2026-11-22 (PlanBench, Qwen2.5 7B): „eine Burg mit Bäumen
     * davor“ kam zweimal mit zwei tree-Schritten ohne pos zurück, die Reparaturrunde ergänzte nur einen, und der Plan
     * wurde verworfen („Schritt 2 (tree): pos fehlt“). Vorn ist die Seite, von der der Spieler schaut (System-Prompt:
     * negative z) – also „davor“, wenn nichts anderes gesagt ist. Andere Ops ohne pos (set, door, module, logic)
     * bleiben eine Absage: Dort trägt der Ort die Funktion, ein geratener Platz wäre falsch. Wer das zurückdreht,
     * bekommt Bäume ohne Ort wieder als Absage.
     */
    public static Fixed treePositions(String json) {
        JsonObject root = stepsRoot(json);
        if (root == null) {
            return new Fixed(json, List.of());
        }
        JsonArray steps = root.getAsJsonArray("steps");
        List<JsonObject> loose = new ArrayList<>();
        List<Integer> index = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (int i = 0; i < steps.size(); i++) {
            if (!steps.get(i).isJsonObject()) {
                continue;
            }
            JsonObject s = steps.get(i).getAsJsonObject();
            String op = s.has("op") && s.get("op").isJsonPrimitive()
                    ? s.get("op").getAsString().toLowerCase(Locale.ROOT) : "";
            // Nur ein Baum ganz ohne Ortsangabe. Vorher zählte ein tree mit from statt pos als „ohne Ort“ – der Parser
            // nimmt from aber als pos (PlanParser), und der Ort des Modells ging verloren; ein kaputtes pos ([3,0])
            // wurde still überschrieben statt abgelehnt (Gegenprobe Welle 10).
            if (op.equals("tree") && !s.has("pos") && !s.has("from")) {
                loose.add(s);
                index.add(i + 1);
                continue;
            }
            // Rundformen reichen um ihren Radius über pos hinaus; vorher zählte nur der Mittelpunkt, und ein Baum landete
            // im Turm (Zylinder Radius 12 an [0,0,0] → Baum auf [0,0,-8], Gegenprobe Welle 10).
            int r = (op.equals("cylinder") || op.equals("sphere")) && s.has("radius")
                    && s.get("radius").isJsonPrimitive() && s.get("radius").getAsJsonPrimitive().isNumber()
                    ? Math.max(0, s.get("radius").getAsInt()) : 0;
            for (String key : List.of("pos", "from", "to")) {
                int[] v = vec(s, key);
                if (v != null) {
                    int pad = key.equals("pos") ? r : 0;
                    minX = Math.min(minX, v[0] - pad);
                    maxX = Math.max(maxX, v[0] + pad);
                    minZ = Math.min(minZ, v[2] - pad);
                }
            }
        }
        if (loose.isEmpty()) {
            return new Fixed(json, List.of());
        }
        // Ohne anderes Bauwerk: wie der Schnellauftrag Baum, 8 vor dem Ursprung, nebeneinander.
        boolean alone = minX == Integer.MAX_VALUE;
        int centerX = alone ? 0 : Math.floorDiv(minX + maxX, 2);
        // y = 0 wie im Schnellauftrag: Der Plan legt den Boden auf y = 0, ein Keller darunter trägt keinen Baum.
        int y = 0;
        int z = alone ? TREE_FRONT : minZ - TREE_FRONT;
        List<String> notes = new ArrayList<>();
        for (int k = 0; k < loose.size(); k++) {
            int x = centerX + (2 * k - (loose.size() - 1)) * TREE_SPACING / 2;
            JsonArray pos = new JsonArray();
            pos.add(x);
            pos.add(y);
            pos.add(z);
            loose.get(k).add("pos", pos);
            notes.add("Schritt " + index.get(k) + ": tree ohne pos – vor das Bauwerk auf [" + x + "," + y + "," + z
                    + "] gesetzt");
        }
        return new Fixed(root.toString(), List.copyOf(notes));
    }

    /** Wurzelobjekt mit steps-Feld oder null (unlesbar – das meldet dann der Parser). */
    private static JsonObject stepsRoot(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject() || !el.getAsJsonObject().has("steps")
                    || !el.getAsJsonObject().get("steps").isJsonArray()) {
                return null;
            }
            return el.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Module ohne Maschinenwunsch

    /**
     * Ab diesem Anteil der Blöcke aus Modulen gilt der Plan als Maschine – das LLM hat den Auftrag als Maschinenwunsch
     * verstanden, auch wenn RequestHints kein Maschinenwort fand. Gemessen auf dev01 (2026-10-31): 26 Pläne
     * verworfen, fast alle mit genau einem Modul (kombischloss, klingel) als ganzem Plan – das war der Auftrag.
     * Darunter sind Module Verzierung eines Gebäudes (2026-10-28: 60 Logikgatter in einer Hochhausfassade) und
     * werden entfernt.
     */
    static final double MACHINE_SHARE = 0.5;

    /** Anteil der Blöcke des Baus, die aus Modul-Schritten stammen (0..1). */
    static double moduleShare(BuildPlan plan, CompiledBuild build) {
        long all = 0;
        long fromModules = 0;
        for (CompiledBuild.Action a : build.actions()) {
            all += a.changes().size();
            if (a.step() >= 0 && a.step() < plan.steps().size() && plan.steps().get(a.step()).op() == Op.MODULE) {
                fromModules += a.changes().size();
            }
        }
        return all == 0 ? 0 : (double) fromModules / all;
    }

    /** Der Plan ohne die Schritte an diesen Stellen (0-basiert). */
    public static Optional<Fixed> withoutSteps(String json, List<Integer> drop, String note) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray steps = root.getAsJsonArray("steps");
            Set<Integer> gone = new HashSet<>(drop);
            JsonArray kept = new JsonArray();
            for (int i = 0; i < steps.size(); i++) {
                if (!gone.contains(i)) {
                    kept.add(steps.get(i));
                }
            }
            if (kept.isEmpty()) {
                return Optional.empty();
            }
            root.add("steps", kept);
            return Optional.of(new Fixed(root.toString(), List.of(note)));
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- Doppelte Modulzelle

    /**
     * set-Schritte, die genau den Block auf eine Modul- oder Logikzelle setzen, der dort schon steht, fallen weg.
     * Vorher meldete PlanLinter.buriedModules sie als Überbau, und der Plan ging in die Reparaturrunde bzw. wurde
     * verworfen: gemessen auf dev01 seit 2026-11-01, 4 von 14 verworfenen Plänen – „(set redstone_lamp) overwrites
     * 1 block(s) of the module register_4bit“, „(set lever) overwrites 1 block(s) of the logic circuit“. Das LLM
     * setzt die Ausgangslampe oder den Eingangshebel, den die Maschine selbst schon hat; der Schritt ist überflüssig,
     * nicht schädlich.
     *
     * Gleich heißt: dieselbe Block-ID, und jeder Zustand, den der set ausdrücklich verlangt (BlockSpec.explicit),
     * hat im Modul denselben Wert; Daten (NBT) nur, wenn sie gleich sind. Ein Hebel mit anderer Ausrichtung oder
     * eine Lampe auf einer Redstoneleitung bleibt ein Überbau-Befund wie bisher – dort hätte der set die Maschine
     * tatsächlich verändert. Wer hier auf „gleiche ID genügt“ lockert, baut einen gedrehten Hebel stillschweigend
     * nicht, obwohl das LLM ihn anders wollte, und verliert den Hinweis an das LLM.
     *
     * Nur set: fill und Bauformen überschreiben mehr als eine Zelle, ein Teil davon wäre echter Überbau.
     *
     * Der übersetzte Bau kennt je Zelle nur den Endzustand (PlanCompiler.order: eine Action je Zelle, ohne
     * Geschichte) – was das Modul dort hatte, liefert deshalb ein zweiter Bau ohne den set-Schritt. Gemessen
     * 2026-11-21: Eine Lampe auf der Lampe von register_4bit steht im Bau als Action von Schritt 1 (dem Modul), die des
     * set taucht gar nicht auf; ein Vergleich über die Actions fand nie etwas.
     *
     * @param compiler derselbe Compiler, der build übersetzt hat
     */
    public static Optional<Fixed> moduleCells(String json, BuildPlan plan, CompiledBuild build, List<Issue> issues,
            PlanCompiler compiler) {
        Map<Integer, String> drop = new java.util.TreeMap<>();
        for (Issue i : issues) {
            int w = i.step() - 1;
            if (i.rule() != Rule.MODULE_BURIED || w < 0 || w >= plan.steps().size() || i.pos() == null
                    || plan.steps().get(w).op() != Op.SET || drop.containsKey(w)) {
                continue;
            }
            sameModuleBlock(json, plan, build, w, i.pos(), compiler).ifPresent(note -> drop.put(w, note));
        }
        if (drop.isEmpty()) {
            return Optional.empty();
        }
        return withoutSteps(json, List.copyOf(drop.keySet()), "").map(f -> new Fixed(f.json(),
                List.copyOf(drop.values())));
    }

    /** Leer, wenn der set-Schritt w an p etwas anderes setzt als das Modul bzw. die Schaltung, die dort zuvor stand. */
    private static Optional<String> sameModuleBlock(String json, BuildPlan plan, CompiledBuild build, int w, Pos p,
            PlanCompiler compiler) {
        BlockSpec want = cellAt(build, p).map(CompiledBuild.Change::block).orElse(null);
        Optional<Fixed> rest = withoutSteps(json, List.of(w), "");
        if (want == null || rest.isEmpty()) {
            return Optional.empty();
        }
        CompiledBuild.Change before;
        try {
            before = cellAt(compiler.compile(PlanParser.parse(rest.get().json())), p).orElse(null);
        } catch (PlanException e) {
            // Ohne den Schritt nicht übersetzbar: dann wird nicht gerichtet, der Befund bleibt wie bisher.
            return Optional.empty();
        }
        // group = Schrittnummer + 1 bei module/logic (PlanCompiler.compile); 0 heißt: kein Bauteil schrieb zuletzt.
        int victimIndex = before == null ? -1 : before.group() - 1;
        if (victimIndex < 0 || victimIndex >= w) {
            return Optional.empty();
        }
        BuildPlan.Step victim = plan.steps().get(victimIndex);
        if (victim.op() != Op.MODULE && victim.op() != Op.LOGIC) {
            return Optional.empty();
        }
        BlockSpec have = before.block();
        if (!want.id().equals(have.id()) || want.nbt() != null && !want.nbt().equals(have.nbt())) {
            return Optional.empty();
        }
        for (String key : want.explicit()) {
            if (!want.props().get(key).equals(have.props().get(key))) {
                return Optional.empty();
            }
        }
        String what = victim.op() == Op.LOGIC ? "die Logikschaltung „" + victim.expr() + "“"
                : "das Modul „" + victim.name() + "“";
        return Optional.of("Schritt " + (w + 1) + ": set " + want.id() + " weggelassen – " + what + " hat dort "
                + "schon diesen Block");
    }

    private static Optional<CompiledBuild.Change> cellAt(CompiledBuild build, Pos p) {
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                if (c.pos().equals(p)) {
                    return Optional.of(c);
                }
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- Mantel um ein Modul

    /** Zellen, die weg sollen, und der Satz für Chat und Log. */
    public record Cells(Set<Pos> cells, String note) {
    }

    /**
     * Lose Füllmasse, aus der das LLM einen Mantel um ein Modul gießt (gemessen: Sand, Wasser) – nie Bauteile, Ziegel
     * oder Stein. Vorher standen stone und cobblestone mit drin (Runde 2), ohne dass ein Mantel aus Stein je gemessen
     * war; ein Steinsockel oder -podest neben der Maschine ist dagegen ein üblicher Bau, und der Fix nahm ihn still
     * weg (18 Blöcke im Test onlyLooseFillerCountsAsShell, Prüfung Runde 3, 2026-11-22). Wer Stein wieder aufnimmt,
     * schneidet Sockel an; ein Steinmantel geht wie vor diesem Fix ungerichtet durch.
     */
    static final Set<String> SHELL_FILLERS = Set.of("sand", "red_sand", "gravel", "dirt", "coarse_dirt", "grass_block",
            "clay", "water");

    /** So weit darf ein Mantel-Schritt waagerecht über den Kasten des Moduls hinausreichen (gemessen: 2). */
    static final int SHELL_REACH = 2;

    /**
     * Ein früherer fill aus Füllmasse, der den Kasten eines Moduls ummantelt, fällt als Ganzes weg, soweit er nach dem
     * Bau noch steht. Gemessen 2026-11-22, PlanBench „eine Zuckerrohrfarm und eine Schmelzanlage
     * nebeneinander“ (Qwen2.5 7B): Das Modell baut Sand und Wasser VOR die Module – das Modul überschreibt seine
     * eigenen Zellen, der Rest bleibt als Mantel stehen (70 Wasser und 18 Sand um die Farm, 46 um die Schmelzanlage),
     * und der Plan ging „sofort“ durch. PlanLinter.buriedModules sieht nur spätere Schritte, die ein Modul überbauen;
     * genau so war es gedacht, denn das Modul gewinnt. Nur der Rand um das Modul blieb ungeprüft – und der Prompt
     * verlangt dort ausdrücklich 1 Block frei (PlanPrompt.TECHNICAL_RULES).
     *
     * Ummanteln heißt: op fill (nicht hollow) aus SHELL_FILLERS, sein Kasten schneidet den des Moduls und reicht
     * waagerecht höchstens SHELL_REACH darüber hinaus, in der Höhe höchstens 1. Vorher fiel alles, was ein früherer
     * Schritt in den Rand ±1 gesetzt hatte – die Prüfung fand drei Gegenbeispiele (2026-11-22): eine Hallenwand dicht
     * am Modul verlor 90 Blöcke und behielt Reste darüber, ein Boden auf Modulhöhe bekam einen Graben aus 18 Blöcken,
     * eine Truhe am Eingang verschwand. Wände (walls/hollow), Böden über den Rand hinaus und Bauteile bleiben jetzt
     * unverändert wie vor diesem Fix – lieber weniger richten als ein Bauwerk still zerstören. Fällt ein Schritt weg,
     * dann ganz: Vorher blieb vom gemessenen Mantel die Reihe bei x=4 stehen, eine Wasserwand neben der Farm.
     *
     * Luft bleibt (Aushöhlen vor dem Modul ist gewollt), ebenso was ein Schritt NACH dem Modul dort setzt (Lampe am
     * Ausgang, Hebel am Eingang – das hat buriedModules schon geprüft). Module, die Zelle für Zelle bauen
     * (ModuleLibrary.cellByCell), tragen keine group und sind hier nicht erfasst. Wer die Bedingungen lockert (andere
     * Ops, Ziegel, größere Reichweite), schneidet wieder Hallenwände und Böden an.
     *
     * @param compiler derselbe Compiler, der build übersetzt hat (für die Zellen der späteren Schritte)
     */
    public static Optional<Cells> moduleShells(String json, BuildPlan plan, CompiledBuild build, PlanCompiler compiler) {
        Map<Integer, int[]> boxes = new java.util.TreeMap<>();
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                int g = c.group();
                if (g <= 0 || g > plan.steps().size() || plan.steps().get(g - 1).op() != Op.MODULE) {
                    continue;
                }
                int[] b = boxes.computeIfAbsent(g, k -> new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE});
                b[0] = Math.min(b[0], c.pos().x());
                b[1] = Math.min(b[1], c.pos().y());
                b[2] = Math.min(b[2], c.pos().z());
                b[3] = Math.max(b[3], c.pos().x());
                b[4] = Math.max(b[4], c.pos().y());
                b[5] = Math.max(b[5], c.pos().z());
            }
        }
        if (boxes.isEmpty()) {
            return Optional.empty();
        }
        Set<Pos> gone = new HashSet<>();
        Map<String, Integer> what = new java.util.TreeMap<>();
        for (Map.Entry<Integer, int[]> e : boxes.entrySet()) {
            int moduleIndex = e.getKey() - 1;
            int[] b = e.getValue();
            List<Integer> shellSteps = new ArrayList<>();
            for (int j = 0; j < moduleIndex; j++) {
                if (encases(plan.steps().get(j), b)) {
                    shellSteps.add(j);
                }
            }
            if (shellSteps.isEmpty()) {
                continue;
            }
            Set<Pos> later = laterCells(json, plan, moduleIndex, compiler);
            if (later == null) {
                continue;
            }
            for (int j : shellSteps) {
                BuildPlan.Step s = plan.steps().get(j);
                String id = blockId(s.block());
                for (CompiledBuild.Action a : build.actions()) {
                    for (CompiledBuild.Change c : a.changes()) {
                        Pos p = c.pos();
                        if (c.group() == 0 && c.block().id().equals(id) && !later.contains(p)
                                && inBox(p, s.from(), s.to()) && gone.add(p)) {
                            what.merge(id, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        if (gone.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        what.forEach((id, n) -> sb.append(sb.isEmpty() ? "" : ", ").append(n).append(' ').append(id));
        return Optional.of(new Cells(Set.copyOf(gone), gone.size() + " Block/Blöcke um Module weggelassen (" + sb
                + ") – das LLM hatte die Maschine ummantelt, sie braucht 1 Block frei."));
    }

    /** Ob ein früherer Schritt den Modulkasten b (x0,y0,z0,x1,y1,z1) als Füllmasse ummantelt – siehe moduleShells. */
    private static boolean encases(BuildPlan.Step s, int[] b) {
        String id = blockId(s.block());
        if (s.op() != Op.FILL || s.hollow() || id == null || !SHELL_FILLERS.contains(id) || s.from() == null
                || s.to() == null) {
            return false;
        }
        int x0 = Math.min(s.from().x(), s.to().x());
        int x1 = Math.max(s.from().x(), s.to().x());
        int y0 = Math.min(s.from().y(), s.to().y());
        int y1 = Math.max(s.from().y(), s.to().y());
        int z0 = Math.min(s.from().z(), s.to().z());
        int z1 = Math.max(s.from().z(), s.to().z());
        boolean cuts = x0 <= b[3] && x1 >= b[0] && y0 <= b[4] && y1 >= b[1] && z0 <= b[5] && z1 >= b[2];
        boolean close = x0 >= b[0] - SHELL_REACH && x1 <= b[3] + SHELL_REACH && z0 >= b[2] - SHELL_REACH
                && z1 <= b[5] + SHELL_REACH && y0 >= b[1] - 1 && y1 <= b[4] + 1;
        return cuts && close;
    }

    private static boolean inBox(Pos p, Pos a, Pos b) {
        return p.x() >= Math.min(a.x(), b.x()) && p.x() <= Math.max(a.x(), b.x())
                && p.y() >= Math.min(a.y(), b.y()) && p.y() <= Math.max(a.y(), b.y())
                && p.z() >= Math.min(a.z(), b.z()) && p.z() <= Math.max(a.z(), b.z());
    }

    /** Block-ID ohne Zustände, null wenn unlesbar (dann richtet hier nichts). */
    private static String blockId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return BlockSpec.parse(raw).id();
        } catch (PlanException e) {
            // Unlesbar: Sache des Compilers, hier wird nur nicht gerichtet.
            return null;
        }
    }

    /** Zellen, die Schritte nach moduleIndex schreiben; null, wenn der Rest nicht übersetzt (dann nicht richten). */
    private static Set<Pos> laterCells(String json, BuildPlan plan, int moduleIndex, PlanCompiler compiler) {
        if (moduleIndex + 1 >= plan.steps().size()) {
            return Set.of();
        }
        List<Integer> before = new ArrayList<>();
        for (int i = 0; i <= moduleIndex; i++) {
            before.add(i);
        }
        Optional<Fixed> rest = withoutSteps(json, before, "");
        if (rest.isEmpty()) {
            // Unlesbar, obwohl Schritte folgen: unklar, was sie setzen – nicht richten. Vorher leere Menge, also
            // „nichts geschützt“, entgegen dem Grundsatz unten (Prüfung 2026-11-22).
            return null;
        }
        try {
            Set<Pos> out = new HashSet<>();
            for (CompiledBuild.Action a : compiler.compile(PlanParser.parse(rest.get().json())).actions()) {
                for (CompiledBuild.Change c : a.changes()) {
                    out.add(c.pos());
                }
            }
            return out;
        } catch (PlanException e) {
            // Der Rest allein übersetzt nicht (z. B. Grenzen): lieber nichts weglassen als einen späteren Block.
            return null;
        }
    }

    // ---------------------------------------------------------------- Tür in die Wand

    /** Ops, deren Tür-Schritt hier ersetzt werden darf: Der Compiler macht aus jedem davon genau eine Tür. */
    private static final Set<Op> DOOR_OPS = Set.of(Op.DOOR, Op.SET, Op.FILL);

    /** Bauteile mit Funktion: Deren Zellen werden nie zur Tür ausgebrochen. */
    private static final Set<Op> KEEP_OPS = Set.of(Op.MODULE, Op.LOGIC);

    /** Außenrichtung (dx, dz) → lokale Wandseite für op door (Orientation: lokal +z = hinten, +x = rechts). */
    private static final int[][] OUTWARD = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}};
    private static final String[] OUTWARD_SIDE = {"front", "back", "right", "left"};
    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Eine Tür, die nicht in der Wand steht (frei, in der Luft, im Inneren), rückt in die nächste passende Außenwand
     * auf Bodenhöhe. Vorher ging sie als PlanLinter.Rule.DOOR in die Reparaturrunde, und das 7B lieferte sie dort
     * wieder daneben: PlanBench-Grundlinie 2026-11-01, Wachturm und Kirche „Tür nicht in der Wand“ auch nach der
     * Reparatur verworfen; im Gedächtnis offen für Wachturm, Leuchtturm und Stall. Die Wand selbst hatte das Modell
     * jedes Mal richtig gebaut – es fehlte nur die Stelle der Tür.
     *
     * Passend heißt: zwei übereinanderliegende feste Wandblöcke (keine Tür, kein Modul), darunter fester Grund, entlang
     * der Wand beidseitig fest (keine Ecke), quer dazu beidseitig begehbar, und eine Seite gehört zum Außenraum
     * (Flutfüllung in 3D vom Rand des Baus, auch über niedrige Mauern hinweg). Gewählt wird zuerst eine Wand mit
     * umschlossenem Innenraum, dann die niedrigste Lage (Bodenhöhe), dann die kleinste Entfernung zur alten Tür, dann
     * die Seite, die das LLM wollte, sonst
     * vorn, hinten, rechts, links, zuletzt x, z – deterministisch, damit derselbe
     * Plan immer dieselbe Tür bekommt. Der Tür-Schritt wandert ans Ende: Stünde er vor dem Wandschritt, überschriebe
     * die Wand ihn wieder.
     *
     * Vorher zählte die Lage vor dem Innenraum, und der Außenraum wurde je Lage gerechnet: Ein Haus (Boden y0, Wände ab
     * y1) in einem Garten mit 2 hoher Mauer bekam die Tür in die Gartenmauer bei y0 – der Hof galt in seiner Lage als
     * umschlossen, und das Haus blieb ohne Eingang (Gegenbeispiel der Prüfung 2026-11-22). Mauern ohne Dach sind jetzt
     * nur noch Rückfall, wenn es keine Wand mit Innenraum gibt.
     *
     * Ohne passende Wand bleibt der Plan unverändert und der Befund geht wie bisher an das LLM bzw. in die Absage –
     * eine Tür in einen Zaun oder eine Säule zu setzen, wäre geraten. Wer hier den Außenraum-Test weglässt, setzt
     * Türen in Zwischenwände, durch die man von draußen nie hineinkommt.
     *
     * @param existing was am Bauplatz schon fest steht (wie PlanLinter); ohne Scan nichts
     */
    public static Optional<Fixed> doors(String json, BuildPlan plan, CompiledBuild build, List<Issue> issues,
            Predicate<Pos> existing) {
        Map<Integer, Pos> misplaced = new java.util.TreeMap<>();
        for (Issue i : issues) {
            int w = i.step() - 1;
            if (i.rule() != Rule.DOOR || i.pos() == null || w < 0 || w >= plan.steps().size()
                    || misplaced.containsKey(w)) {
                continue;
            }
            BuildPlan.Step s = plan.steps().get(w);
            if (DOOR_OPS.contains(s.op()) && doorId(s.block()) != null) {
                misplaced.put(w, i.pos());
            }
        }
        if (misplaced.isEmpty()) {
            return Optional.empty();
        }
        Map<Pos, BlockSpec> cells = new HashMap<>();
        Set<Pos> keep = new HashSet<>();
        for (CompiledBuild.Action a : build.actions()) {
            boolean functional = a.step() >= 0 && a.step() < plan.steps().size()
                    && KEEP_OPS.contains(plan.steps().get(a.step()).op());
            for (CompiledBuild.Change c : a.changes()) {
                cells.put(c.pos(), c.block());
                if (functional) {
                    keep.add(c.pos());
                }
            }
        }
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        // Die falsch gesetzten Türen verschwinden; sie dürfen weder Wand sein noch den Durchgang versperren.
        for (Pos p : misplaced.values()) {
            removeDoorAt(cells, p);
            removeDoorAt(cells, p.up());
        }
        DoorSite site = new DoorSite(cells, keep, existing);
        Set<Pos> taken = new HashSet<>();
        // Nur die wirklich gerichteten Schritte fallen weg; eine Tür ohne passende Wand bleibt als Befund stehen.
        Set<Integer> gone = new HashSet<>();
        List<JsonObject> moved = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Map.Entry<Integer, Pos> e : misplaced.entrySet()) {
            int w = e.getKey();
            Optional<DoorSpot> spot = site.best(e.getValue(), plan.steps().get(w).facing(), taken);
            if (spot.isEmpty()) {
                continue;
            }
            DoorSpot d = spot.get();
            taken.add(d.pos());
            gone.add(w);
            JsonObject door = new JsonObject();
            door.addProperty("op", "door");
            door.addProperty("block", doorId(plan.steps().get(w).block()));
            JsonArray pos = new JsonArray();
            pos.add(d.pos().x());
            pos.add(d.pos().y());
            pos.add(d.pos().z());
            door.add("pos", pos);
            door.addProperty("facing", d.side());
            moved.add(door);
            Pos old = e.getValue();
            notes.add("Schritt " + (w + 1) + ": Tür von [" + old.x() + "," + old.y() + "," + old.z() + "] in die "
                    + "Außenwand bei [" + d.pos().x() + "," + d.pos().y() + "," + d.pos().z() + "] (" + d.side()
                    + ") gerichtet");
        }
        if (moved.isEmpty()) {
            return Optional.empty();
        }
        JsonObject root = stepsRoot(json);
        if (root == null) {
            return Optional.empty();
        }
        JsonArray steps = root.getAsJsonArray("steps");
        JsonArray kept = new JsonArray();
        for (int i = 0; i < steps.size(); i++) {
            if (!gone.contains(i)) {
                kept.add(steps.get(i));
            }
        }
        moved.forEach(kept::add);
        root.add("steps", kept);
        return Optional.of(new Fixed(root.toString(), List.copyOf(notes)));
    }

    /** Block-ID der Tür ohne Zustände (facing/half/hinge setzt der Compiler neu) oder null, wenn keine Tür. */
    private static String doorId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BlockSpec b = BlockSpec.parse(raw);
            return b.isDoor() ? b.id() : null;
        } catch (PlanException e) {
            // Unlesbarer Block: kein Tür-Schritt, den der Baumeister richten könnte – der Befund bleibt.
            return null;
        }
    }

    private static void removeDoorAt(Map<Pos, BlockSpec> cells, Pos p) {
        BlockSpec b = cells.get(p);
        if (b != null && b.isDoor()) {
            cells.remove(p);
        }
    }

    /** Neue Türstelle (untere Hälfte) und die Wandseite für op door. */
    record DoorSpot(Pos pos, String side) {
    }

    /** Endzustand des Baus für die Türsuche, mit Außenraum je Lage (einmal gerechnet, dann gemerkt). */
    private static final class DoorSite {
        private final Map<Pos, BlockSpec> cells;
        private final Set<Pos> keep;
        private final Predicate<Pos> existing;
        private final int minY;
        /** Größter Rechenkasten für den Außenraum; darüber wird nicht gerichtet (Befund bleibt beim LLM). */
        static final long MAX_VOLUME = 2_000_000;

        private final int maxY;
        private final int x0;
        private final int x1;
        private final int z0;
        private final int z1;
        private Set<Pos> outside;

        DoorSite(Map<Pos, BlockSpec> cells, Set<Pos> keep, Predicate<Pos> existing) {
            this.cells = cells;
            this.keep = keep;
            this.existing = existing;
            int lowY = Integer.MAX_VALUE;
            int highY = Integer.MIN_VALUE;
            int ax = Integer.MAX_VALUE;
            int bx = Integer.MIN_VALUE;
            int az = Integer.MAX_VALUE;
            int bz = Integer.MIN_VALUE;
            for (Pos p : cells.keySet()) {
                lowY = Math.min(lowY, p.y());
                highY = Math.max(highY, p.y());
                ax = Math.min(ax, p.x());
                bx = Math.max(bx, p.x());
                az = Math.min(az, p.z());
                bz = Math.max(bz, p.z());
            }
            this.minY = lowY;
            this.maxY = highY;
            this.x0 = ax - 1;
            this.x1 = bx + 1;
            this.z0 = az - 1;
            this.z1 = bz + 1;
        }

        /** Wie PlanLinter.Site.solid: unter der untersten Bauschicht Gelände, ohne Planblock der Bestand. */
        boolean solid(Pos p) {
            BlockSpec b = cells.get(p);
            return p.y() < minY || (b == null ? existing.test(p) : PlanAutoFix.solid(b)
                    && !PlanCompiler.needsSupport(b.id()));
        }

        /** Ein Block, der als Türrahmen herausgenommen werden darf: fest, keine Tür, kein Bauteil mit Funktion. */
        boolean wall(Pos p) {
            BlockSpec b = cells.get(p);
            return b != null && solid(p) && !b.isDoor() && !keep.contains(p);
        }

        /**
         * Begehbar: nicht fest und kein Wasser oder Lava, unten und oben. Vorher reichte „nicht fest“, und die Tür landete
         * in der Wand eines eingemauerten Beckens (PlanReviewTest.feedbackNamesEveryKindOfErrorFirst rot, 2026-11-22) –
         * eine Tür, durch die man ins Wasser fällt, ist keine Lösung, das Becken gehört dem LLM.
         */
        boolean passable(Pos p) {
            return walkable(p) && walkable(p.up());
        }

        private boolean walkable(Pos p) {
            BlockSpec b = cells.get(p);
            return !solid(p) && (b == null || !b.id().equals("water") && !b.id().equals("lava"));
        }

        boolean tooBig() {
            return (long) (x1 - x0 + 1) * (maxY - minY + 2) * (z1 - z0 + 1) > MAX_VOLUME;
        }

        /**
         * Außenraum: alles Nichtfeste, das vom Rand des Kastens (eine Zelle Abstand, eine Lage über dem Bau) in 3D
         * erreichbar ist. Ein Hof hinter einer niedrigen Mauer gehört dazu, der Innenraum eines Hauses mit Dach nicht.
         */
        Set<Pos> outside() {
            if (outside != null) {
                return outside;
            }
            Set<Pos> seen = new HashSet<>();
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            int top = maxY + 1;
            for (int y = minY; y <= top; y++) {
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        if (x == x0 || x == x1 || z == z0 || z == z1 || y == top) {
                            Pos p = new Pos(x, y, z);
                            if (!solid(p) && seen.add(p)) {
                                queue.add(p);
                            }
                        }
                    }
                }
            }
            while (!queue.isEmpty()) {
                Pos p = queue.poll();
                for (int[] d : SIX) {
                    Pos n = p.add(d[0], d[1], d[2]);
                    if (n.x() >= x0 && n.x() <= x1 && n.z() >= z0 && n.z() <= z1 && n.y() >= minY && n.y() <= top
                            && !solid(n) && seen.add(n)) {
                        queue.add(n);
                    }
                }
            }
            outside = seen;
            return seen;
        }

        /** @param wanted die Wandseite, die das LLM der Tür gab (leer bei set/fill) – entscheidet bei Gleichstand */
        Optional<DoorSpot> best(Pos old, String wanted, Set<Pos> taken) {
            if (tooBig()) {
                return Optional.empty();
            }
            DoorSpot best = null;
            long[] bestKey = null;
            for (Pos p : cells.keySet()) {
                if (!wall(p) || !wall(p.up()) || !solid(p.down()) || taken.contains(p) || nearDoor(p)) {
                    continue;
                }
                for (int i = 0; i < OUTWARD.length; i++) {
                    int dx = OUTWARD[i][0];
                    int dz = OUTWARD[i][1];
                    Pos out = p.add(dx, 0, dz);
                    Pos in = p.add(-dx, 0, -dz);
                    // Entlang der Wand (quer zur Außenrichtung) beidseitig fest – sonst Ecke oder Wandende.
                    Pos side1 = p.add(dz, 0, dx);
                    Pos side2 = p.add(-dz, 0, -dx);
                    if (!solid(side1) || !solid(side2) || !solid(side1.up()) || !solid(side2.up())
                            || !passable(out) || !passable(in) || !outside().contains(out)) {
                        continue;
                    }
                    // 0 = Innenraum umschlossen (echte Außenwand), 1 = beidseitig Außenraum (Mauer, Zaun ohne Dach).
                    long openInside = outside().contains(in) ? 1 : 0;
                    long dist = (long) (p.x() - old.x()) * (p.x() - old.x()) + (long) (p.z() - old.z()) * (p.z()
                            - old.z()) + (long) (p.y() - old.y()) * (p.y() - old.y());
                    // Bei gleicher Entfernung die gewünschte Seite, sonst vorn (lokal vorn = Spielerseite): Gemessen
                    // 2026-11-22, Wachturm der PlanBench – Tür in der Mitte eines Rundturms, alle vier Wandstellen
                    // gleich weit; nach x allein landete sie links statt vorn, wo der Spieler steht.
                    long sideRank = OUTWARD_SIDE[i].equals(wanted) ? 0 : i + 1;
                    long[] key = {openInside, p.y(), dist, sideRank, p.x(), p.z()};
                    if (bestKey == null || java.util.Arrays.compare(key, bestKey) < 0) {
                        bestKey = key;
                        best = new DoorSpot(p, OUTWARD_SIDE[i]);
                    }
                }
            }
            return Optional.ofNullable(best);
        }

        /** Direkt neben einer anderen Tür kein zweiter Durchbruch (Doppeltüren plant das LLM selbst). */
        private boolean nearDoor(Pos p) {
            for (int[] d : OUTWARD) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockSpec b = cells.get(p.add(d[0], dy, d[1]));
                    if (b != null && b.isDoor()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- Vollklötze

    /**
     * Vollklotz → Hohlkörper: cylinder/sphere mit hollow=true, ein fill als geschlossene Hülle (op hollow). Vorher
     * Reparaturrunde, und auf dev01 kam der Zylinder aus Steinziegeln (33×12×33) auch danach massiv zurück
     * (verworfen, 2026-10-30). Dass ein Turm innen hohl sein soll, steht im Prompt; das LLM hat es nur vergessen.
     */
    public static Optional<Fixed> hollowSolids(String json, List<Issue> issues) {
        Set<Integer> steps = new LinkedHashSet<>();
        for (Issue i : issues) {
            if (i.rule() == Rule.SOLID) {
                steps.add(i.step() - 1);
            }
        }
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("steps");
            List<String> notes = new ArrayList<>();
            for (int i : steps) {
                if (i < 0 || i >= arr.size() || !arr.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject s = arr.get(i).getAsJsonObject();
                String op = s.has("op") ? s.get("op").getAsString().toLowerCase(Locale.ROOT) : "";
                switch (op) {
                    case "cylinder", "sphere" -> s.addProperty("hollow", true);
                    case "fill" -> s.addProperty("op", "hollow");
                    default -> {
                        continue;
                    }
                }
                notes.add("Schritt " + (i + 1) + ": " + op + " innen hohl gemacht");
            }
            return notes.isEmpty() ? Optional.empty() : Optional.of(new Fixed(root.toString(), List.copyOf(notes)));
        } catch (JsonSyntaxException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- Stützen unter Schwebendem

    /** Höchste Stütze. Darüber ist es kein vergessener Pfeiler mehr, sondern ein falsch gesetztes Teil – ans LLM. */
    static final int MAX_PILLAR = 32;

    /** Blöcke, die als Pfeiler nicht taugen (fallen, durchsichtig, keine volle Fläche). */
    private static final Set<String> NOT_A_PILLAR_SUFFIX = Set.of("_stairs", "_slab", "_wall", "_fence", "_pane",
            "_glass", "_leaves", "_carpet", "_button", "_plate", "_door", "_trapdoor", "_sign", "_banner", "_torch");
    private static final Set<String> NOT_A_PILLAR = Set.of("sand", "red_sand", "gravel", "glass", "anvil", "ice",
            "lantern", "lever", "redstone_wire", "repeater", "comparator", "chain", "ladder", "scaffolding");

    /**
     * Unter jede schwebende Gruppe Pfeiler (1×1, aus ihrem Block, sonst Steinziegel) an den Ecken ihrer untersten
     * Lage bis zum nächsten festen Block – eigener Bau, Gelände/Bestand (existing) oder Bauhöhe floorY. Vorher ging
     * jede größere Gruppe ans LLM; auf dev01 (2026-10-30/31) schwebten danach noch 93, 546 bzw. 712 Blöcke, und der
     * Plan wurde verworfen. Meist fehlte genau das: Säulen unter einem Dach oder einer Plattform. Ist eine Stütze
     * höher als MAX_PILLAR, bleibt die Gruppe unverändert (dann sitzt sie falsch, nicht nur ohne Halt).
     *
     * @return der Bau mit Pfeilern (als erste Aktion, damit sie vor dem Getragenen stehen) oder leer
     */
    public static Optional<CompiledBuild> supports(CompiledBuild build, List<Issue> issues, Predicate<Pos> existing) {
        Map<Pos, BlockSpec> cells = new HashMap<>();
        for (CompiledBuild.Action a : build.actions()) {
            for (CompiledBuild.Change c : a.changes()) {
                cells.put(c.pos(), c.block());
            }
        }
        int floorY = build.min().y();
        List<CompiledBuild.Change> pillars = new ArrayList<>();
        Set<Pos> done = new HashSet<>();
        int parts = 0;
        int firstStep = -1;
        for (Issue i : issues) {
            if (i.rule() != Rule.FLOATING || done.contains(i.pos()) || !solid(cells.get(i.pos()))) {
                continue;
            }
            Set<Pos> part = component(cells, i.pos());
            done.addAll(part);
            List<CompiledBuild.Change> mine = pillarsUnder(part, cells, existing, floorY);
            if (mine.isEmpty()) {
                continue;
            }
            for (CompiledBuild.Change c : mine) {
                cells.put(c.pos(), c.block());
            }
            pillars.addAll(mine);
            parts++;
            if (firstStep < 0) {
                firstStep = i.step() - 1;
            }
        }
        if (pillars.isEmpty()) {
            return Optional.empty();
        }
        List<CompiledBuild.Action> actions = new ArrayList<>(build.actions().size() + 1);
        actions.add(new CompiledBuild.Action(Math.max(0, firstStep), List.copyOf(pillars)));
        actions.addAll(build.actions());
        List<String> warnings = new ArrayList<>(build.warnings());
        warnings.add(parts + " schwebende(s) Teil(e) mit " + pillars.size() + " Block/Blöcken Stützen getragen – das "
                + "LLM hatte die Pfeiler vergessen.");
        return Optional.of(new CompiledBuild(List.copyOf(actions), List.copyOf(warnings), build.min(), build.max(),
                build.stepNames()));
    }

    private static List<CompiledBuild.Change> pillarsUnder(Set<Pos> part, Map<Pos, BlockSpec> cells,
            Predicate<Pos> existing, int floorY) {
        int low = Integer.MAX_VALUE;
        for (Pos p : part) {
            low = Math.min(low, p.y());
        }
        List<Pos> bottom = new ArrayList<>();
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (Pos p : part) {
            if (p.y() == low) {
                bottom.add(p);
                x0 = Math.min(x0, p.x());
                x1 = Math.max(x1, p.x());
                z0 = Math.min(z0, p.z());
                z1 = Math.max(z1, p.z());
            }
        }
        // Je Ecke des Umrisses die nächste Zelle der untersten Lage; kleine Teile bekommen so 1–4 Pfeiler.
        Set<Pos> sites = new LinkedHashSet<>();
        int[][] corners = {{x0, z0}, {x0, z1}, {x1, z0}, {x1, z1}};
        for (int[] c : corners) {
            Pos best = null;
            long bestD = Long.MAX_VALUE;
            for (Pos p : bottom) {
                long d = (long) (p.x() - c[0]) * (p.x() - c[0]) + (long) (p.z() - c[1]) * (p.z() - c[1]);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            sites.add(best);
        }
        List<CompiledBuild.Change> out = new ArrayList<>();
        for (Pos top : sites) {
            BlockSpec block = pillarBlock(cells.get(top));
            List<CompiledBuild.Change> column = new ArrayList<>();
            Pos p = top.add(0, -1, 0);
            boolean reached = false;
            while (column.size() <= MAX_PILLAR) {
                if (solid(cells.get(p)) || existing.test(p)) {
                    reached = true;
                    break;
                }
                if (cells.containsKey(p)) {
                    // Luft, Wasser oder ein Zierblock des Plans an dieser Stelle – nicht überbauen.
                    break;
                }
                column.add(new CompiledBuild.Change(p, block));
                if (p.y() <= floorY) {
                    reached = true;
                    break;
                }
                p = p.add(0, -1, 0);
            }
            if (!reached || column.size() > MAX_PILLAR) {
                return List.of();
            }
            out.addAll(column);
        }
        return out;
    }

    private static BlockSpec pillarBlock(BlockSpec top) {
        if (top != null && top.props().isEmpty() && !NOT_A_PILLAR.contains(top.id())
                && NOT_A_PILLAR_SUFFIX.stream().noneMatch(top.id()::endsWith)) {
            return top;
        }
        return BlockSpec.of("stone_bricks");
    }

    /** Ganze zusammenhängende feste Gruppe um start (26 Nachbarn), wie PlanLinter.floatingParts. */
    private static Set<Pos> component(Map<Pos, BlockSpec> cells, Pos start) {
        Set<Pos> seen = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Pos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Pos n = p.add(dx, dy, dz);
                        if (solid(cells.get(n)) && seen.add(n)) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return seen;
    }

    private static boolean solid(BlockSpec b) {
        return b != null && !b.isAir() && !b.id().equals("water") && !b.id().equals("lava");
    }
}
