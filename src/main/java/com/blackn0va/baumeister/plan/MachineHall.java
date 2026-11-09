package com.blackn0va.baumeister.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Schnellauftrag „bau mir eine (große/fette/kleine) Maschine“: eine Maschinenhalle mit nützlichen Anlagen aus der
 * Bibliothek, ohne LLM.
 *
 * Vorher ging der Auftrag ans LLM (dev01 2026-10-30, Qwen2.5 14B): drei Logikgatter, danach ein fill stone_bricks
 * quer durch die Module, schwebende Fackeln, eine Reparaturrunde, verworfen – 214 s ohne Ergebnis; der zweite
 * Versuch baute fünf Gatter nebeneinander. Mit geschärftem Prompt (Messprobe, 6 Läufe) wählte das Modell zwar
 * nützliche Anlagen, stellte sie aber bei x 0, 2, 4 … – die Maße aus der Bibliothek rechnet es nicht zuverlässig, die
 * Anlagen überlappten, und der Boden war ein 5 Blöcke hoher Klotz. Die Aufstellung ist reine Rechnung; hier stimmt
 * sie immer und kostet Millisekunden.
 *
 * Aufstellung: Anlagen in einer Reihe auf y 0, Front nach vorn (zum Spieler), 2 Blöcke Abstand nach ihren echten
 * Maßen. Boden EINE Schicht auf y −1, hohle Wände 2 Blöcke seitlich/hinten und 3 Blöcke vorn (Platz zum Bedienen),
 * Tür und Fenster vorn, Dach 2 Blöcke über der höchsten Anlage, Laternen in den Ecken auf dem Boden. Nichts liegt im
 * Raum einer Anlage (PlanLinter MODULE_BURIED, Gegenprobe im Test).
 */
public final class MachineHall {

    /** Reihenfolge = Aufstellung. Nur Anlagen, die etwas tun; Gatter und Zähler gehören zu „Logik“-Aufträgen. */
    static final List<String> USEFUL = List.of("schmelzanlage", "item_sortierer", "sammelstelle", "zuckerrohrfarm",
            "wasserfahrstuhl", "abwaerts_fahrstuhl");

    static final Set<String> WORDS = Set.of("maschine", "maschinen", "maschinenhalle", "fabrik");
    static final Set<String> BIG = Set.of("gross", "grosse", "grossen", "grosser", "fett", "fette", "fetten",
            "fetter", "riesig", "riesige", "riesigen", "mega", "krass", "krasse");
    static final Set<String> SMALL = Set.of("klein", "kleine", "kleinen", "kleiner");

    private static final String FLOOR = "polished_andesite";
    private static final String WALL = "stone_bricks";
    private static final String ROOF = "dark_oak_slab";
    private static final int SIDE = 2;
    private static final int FRONT = 3;

    private MachineHall() {
    }

    /** Wie viele Anlagen: klein 2, ohne Zusatz 3, groß/fett/riesig alle. */
    static int count(boolean big, boolean small) {
        return big ? USEFUL.size() : small ? 2 : 3;
    }

    /** Plan-JSON für die Halle; leer, wenn eine der Anlagen fehlt oder die Halle die maximale Ausdehnung sprengt. */
    /**
     * Bausteine eines Prozessors (Machines, alle mit Funktionsprobe), Reihenfolge = Aufstellung. Vorher sieben lose
     * Bausteine (Register, D-Speicher, Addierer, Vergleicher …); seit Stufe 2 (2026-10-31) steht in der Mitte der
     * verbundene Kern akkumulator_4bit (Rechenwerk + Speicher + Rückführung). Er ist 110 breit – mit den alten
     * Bausteinen daneben sprengte die Halle bau.max-ausdehnung 192, deshalb nur noch Takt, Zähler und ALU-Scheibe.
     */
    static final List<String> CPU = List.of("takt", "zaehler", "akkumulator_4bit", "alu_1bit");
    static final java.util.Set<String> CPU_WORDS = java.util.Set.of("prozessor", "prozessoren", "computer", "cpu",
            "rechenzentrum", "redstonecomputer", "redstoneprozessor");  // „rechner“ bleibt der Taschenrechner

    static Optional<String> plan(ModuleLibrary modules, int count, int maxExtent) {
        return plan(modules, USEFUL.subList(0, Math.min(count, USEFUL.size())), maxExtent, false);
    }

    /**
     * Prozessor-Werkstatt (2026-10-31, „prozessoren aus redstone, computer aus redstone“): der 4-Bit-Akkumulator als
     * verbundener Kern – jeder Takt addiert B zum gespeicherten Wert – dazu Takt, Zähler (Programmzähler) und eine
     * ALU-Scheibe. Alle im Spiel geprüft. Ein Steuerwerk, das Befehle aus einem Speicher liest, gibt es nicht; die
     * Antwort sagt das, statt einen Computer zu versprechen.
     */
    static Optional<String> cpuWorkshop(ModuleLibrary modules, int maxExtent) {
        return plan(modules, CPU, maxExtent, true);
    }

    private static Optional<String> plan(ModuleLibrary modules, List<String> wanted, int maxExtent, boolean cpu) {
        List<ModuleLibrary.Module> chosen = new ArrayList<>();
        for (String name : wanted) {
            Optional<ModuleLibrary.Module> m = modules.find(name);
            if (m.isEmpty()) {
                return Optional.empty();
            }
            chosen.add(m.get());
        }
        int width = -SIDE;
        int depth = 0;
        int height = 0;
        for (ModuleLibrary.Module m : chosen) {
            width += m.size().x() + SIDE;
            depth = Math.max(depth, m.size().z());
            height = Math.max(height, m.size().y());
        }
        int x0 = -SIDE - 1;
        int x1 = width + SIDE;
        int z0 = -FRONT - 1;
        int z1 = depth + SIDE;
        int roof = height + 2;
        if (Math.max(x1 - x0, Math.max(z1 - z0, roof + 2)) + 1 > maxExtent) {
            return Optional.empty();
        }
        JsonArray steps = new JsonArray();
        steps.add(box("fill", FLOOR, x0, -1, z0, x1, -1, z1));
        steps.add(box("walls", WALL, x0, 0, z0, x1, roof - 1, z1));
        steps.add(box("fill", ROOF, x0, roof, z0, x1, roof, z1));
        List<String> names = new ArrayList<>();
        int x = 0;
        for (ModuleLibrary.Module m : chosen) {
            JsonObject s = new JsonObject();
            s.addProperty("op", "module");
            s.addProperty("name", m.name());
            s.add("pos", vec(x, 0, 0));
            s.addProperty("facing", "front");
            steps.add(s);
            names.add(m.name());
            x += m.size().x() + SIDE;
        }
        int door = (x0 + x1) / 2;
        JsonObject d = new JsonObject();
        d.addProperty("op", "door");
        d.addProperty("block", "oak_door");
        d.add("pos", vec(door, 0, z0));
        d.addProperty("facing", "front");
        steps.add(d);
        for (int wx = x0 + 2; wx <= x1 - 2; wx += 4) {
            if (Math.abs(wx - door) > 1) {
                steps.add(set("glass_pane", wx, 2, z0));
            }
        }
        steps.add(set("lantern", x0 + 1, 0, z0 + 1));
        steps.add(set("lantern", x1 - 1, 0, z0 + 1));
        steps.add(set("lantern", x0 + 1, 0, z1 - 1));
        steps.add(set("lantern", x1 - 1, 0, z1 - 1));
        JsonObject plan = new JsonObject();
        plan.addProperty("design", (cpu ? "processor workshop with " : "machine hall with ")
                + String.join(", ", names));
        plan.addProperty("reply", cpu ? "Prozessor-Werkstatt: In der Mitte ein 4-Bit-Akkumulator – jeder Takt (Hebel "
                + "hinten links) addiert B zum Wert, Lampen vorn oben zeigen ihn. Dazu Takt, Zähler und ALU-Scheibe."
                : "Ich baue dir eine Maschinenhalle mit " + german(names) + " – Fronten zu dir, Tür vorn in der Mitte.");
        plan.add("steps", steps);
        return Optional.of(plan.toString());
    }

    private static String german(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            out.add(switch (n) {
                case "schmelzanlage" -> "Schmelzanlage";
                case "item_sortierer" -> "Sortierer";
                case "sammelstelle" -> "Sammelstelle";
                case "zuckerrohrfarm" -> "Zuckerrohrfarm";
                case "wasserfahrstuhl" -> "Wasserfahrstuhl";
                case "abwaerts_fahrstuhl" -> "Abwärtsfahrstuhl";
                default -> n;
            });
        }
        return out.size() == 1 ? out.getFirst()
                : String.join(", ", out.subList(0, out.size() - 1)) + " und " + out.getLast();
    }

    private static JsonObject box(String op, String block, int x0, int y0, int z0, int x1, int y1, int z1) {
        JsonObject s = new JsonObject();
        s.addProperty("op", op);
        s.addProperty("block", block);
        s.add("from", vec(x0, y0, z0));
        s.add("to", vec(x1, y1, z1));
        return s;
    }

    private static JsonObject set(String block, int x, int y, int z) {
        JsonObject s = new JsonObject();
        s.addProperty("op", "set");
        s.addProperty("block", block);
        s.add("pos", vec(x, y, z));
        return s;
    }

    private static JsonArray vec(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(y);
        a.add(z);
        return a;
    }
}
