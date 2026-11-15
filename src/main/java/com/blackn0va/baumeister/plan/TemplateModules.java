package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Macht aus einer Bauvorlage von Minecraft (Strukturdatei wie village/plains/houses/plains_small_house_1) ein Bauteil
 * für den Plan-Compiler – ohne Bukkit, damit es testbar ist. Das Einlesen der Datei macht VanillaTemplates.
 *
 * Warum: Wunsch 2026-11-04.
 * Minecraft bringt über 300 fertige, schöne Bauten mit (Dorfhäuser in fünf Stilen, Iglu, Schiff, Wachturm …). Sie sind
 * die Zwischenschicht: kein Modell, kein Raten, in Millisekunden.
 *
 * Vorderseite und Bodenhöhe stehen in der Vorlage selbst (gemessen mit tools/vorlagen_probe.py an 322 Vorlagen): Jedes
 * Dorfhaus hat genau einen waagrechten Verbund-Block (jigsaw, orientation west_up usw.) – das ist der Anschluss an die
 * Straße, also die Vorderseite, und seine Höhe ist die Schicht, die auf Straßenhöhe liegt. Die Tür zeigt jeweils
 * genau entgegen (plains_small_house_1: jigsaw west_up, Tür facing=east). Wer die Bodenschicht ignoriert, stellt
 * Häuser mit Fundament (desert_small_house_1: Boden in Schicht 1) einen Block zu hoch auf.
 *
 * Threads: zustandslos. Fehler: unlesbare Blockzustände werden übersprungen (ein Block fehlt, nicht das Haus).
 */
public final class TemplateModules {

    /** Ein Block der Vorlage in Vorlagen-Koordinaten (Weltachsen, Norden = −z), Zustand wie BlockData.getAsString(). */
    public record RawCell(int x, int y, int z, String state) {
    }

    /**
     * @param module bauteilfertig: Vorderseite bei z = 0, Zustände lokal
     * @param ground Schicht der Vorlage, die auf Bodenhöhe gehört (Plan-y = −1 − ground setzt sie in die Grasschicht)
     * @param front  Himmelsrichtung, in die die Vorderseite in der Vorlage zeigt
     */
    public record Template(ModuleLibrary.Module module, int ground, Cardinal front) {
    }

    private TemplateModules() {
    }

    public static Template build(String name, String description, List<RawCell> raw) {
        return build(name, description, raw, false);
    }

    /**
     * keepAllAir: Luft auch unter der Bodenschicht übernehmen – für die Bauwerke aus /bm spielbauwerk, deren Luft nur
     * noch ausgegrabene Luft ist (Fallgrube, Keller). Die Regel „Luft nur über dem Boden“ fände dort die unterste Tür
     * im Keller und würfe die Kammer weg.
     */
    public static Template build(String name, String description, List<RawCell> raw, boolean keepAllAir) {
        Map<Long, String> byPos = new HashMap<>();
        for (RawCell c : raw) {
            byPos.put(key(c.x(), c.y(), c.z()), c.state());
        }
        Cardinal front = Cardinal.NORTH;
        int ground = 0;
        Optional<RawCell> street = raw.stream().filter(c -> horizontalJigsaw(c.state()) != null)
                .min(Comparator.comparingInt(RawCell::y).thenComparingInt(RawCell::x).thenComparingInt(RawCell::z));
        if (street.isPresent()) {
            front = horizontalJigsaw(street.get().state());
            ground = street.get().y();
        } else {
            Optional<RawCell> door = raw.stream().filter(c -> isLowerDoor(c.state()))
                    .min(Comparator.comparingInt(RawCell::y));
            String facing = door.map(d -> prop(d.state(), "facing")).orElse("");
            if (!facing.isEmpty()) {
                // Die Tür steht auf dem Boden; facing zeigt ins Haus (gemessen), die Vorderseite ist die Gegenrichtung.
                front = Cardinal.parse(facing).opposite();
                ground = Math.max(0, door.get().y() - 1);
            }
        }
        // Wie SchematicLibrary.load: Vorderseite zeigte nach „front“ → als hätte der Spieler in die Gegenrichtung geschaut.
        Orientation o = new Orientation(front.opposite());
        List<CompiledBuild.Change> cells = new ArrayList<>();
        int lx = Integer.MAX_VALUE;
        int lz = Integer.MAX_VALUE;
        int maxY = 0;
        for (RawCell c : raw) {
            String state = c.state();
            String id = id(state);
            if (id.equals("minecraft:structure_void") || id.equals("minecraft:structure_block")) {
                continue;
            }
            if (id.equals("minecraft:jigsaw")) {
                // Was der Verbund-Block am Ende wird, steht nur in seinen NBT-Daten; die Nachbarn sagen es fast
                // immer (Boden unter dem Dorfbewohner-Anker). Ohne Ersatz klaffte im Fußboden ein Loch.
                state = neighbourMajority(byPos, c);
                if (state == null) {
                    continue;
                }
                id = id(state);
            }
            boolean air = id.equals("minecraft:air") || id.equals("minecraft:cave_air");
            // Luft nur über der Bodenschicht: Sie räumt Gras und Büsche aus dem Haus; darunter grübe sie Löcher in die
            // Wiese, wo die Vorlage keinen Grundriss hat (Ecken eines L-förmigen Hauses).
            if (air && c.y() <= ground && !keepAllAir) {
                continue;
            }
            BlockSpec spec;
            try {
                spec = o.toLocal(BlockSpec.parse(air ? "air" : state));
            } catch (PlanException e) {
                continue;
            }
            Pos rel = o.toLocalOffset(new Pos(c.x(), c.y(), c.z()));
            lx = Math.min(lx, rel.x());
            lz = Math.min(lz, rel.z());
            maxY = Math.max(maxY, c.y());
            cells.add(new CompiledBuild.Change(rel, spec));
        }
        List<CompiledBuild.Change> normalized = new ArrayList<>(cells.size());
        int maxX = 0;
        int maxZ = 0;
        for (CompiledBuild.Change c : cells) {
            Pos p = c.pos().add(-lx, 0, -lz);
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
            normalized.add(new CompiledBuild.Change(p, c.block()));
        }
        ModuleLibrary.Module m = new ModuleLibrary.Module(name, description, new Pos(maxX + 1, maxY + 1, maxZ + 1),
                List.copyOf(normalized));
        return new Template(m, ground, front);
    }

    /** Richtung eines waagrechten Verbund-Blocks („minecraft:jigsaw[orientation=west_up]“ → WEST), sonst null. */
    static Cardinal horizontalJigsaw(String state) {
        if (!id(state).equals("minecraft:jigsaw")) {
            return null;
        }
        String o = prop(state, "orientation");
        int u = o.indexOf('_');
        String first = u < 0 ? o : o.substring(0, u);
        return switch (first) {
            case "north" -> Cardinal.NORTH;
            case "east" -> Cardinal.EAST;
            case "south" -> Cardinal.SOUTH;
            case "west" -> Cardinal.WEST;
            default -> null;
        };
    }

    private static boolean isLowerDoor(String state) {
        return id(state).endsWith("_door") && prop(state, "half").equals("lower");
    }

    private static String neighbourMajority(Map<Long, String> byPos, RawCell c) {
        Map<String, Integer> votes = new HashMap<>();
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : around) {
            String s = byPos.get(key(c.x() + d[0], c.y(), c.z() + d[1]));
            if (s == null) {
                continue;
            }
            String id = id(s);
            if (id.equals("minecraft:air") || id.equals("minecraft:jigsaw") || id.equals("minecraft:structure_void")
                    || s.contains("[")) {
                continue;  // nur einfache Blöcke – eine Treppe oder Tür als Ersatz wäre falsch gedreht
            }
            votes.merge(s, 1, Integer::sum);
        }
        return votes.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).orElse(null);
    }

    static String id(String state) {
        int b = state.indexOf('[');
        String id = b < 0 ? state : state.substring(0, b);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    static String prop(String state, String key) {
        int b = state.indexOf('[');
        if (b < 0) {
            return "";
        }
        for (String kv : state.substring(b + 1, state.length() - 1).split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).trim().equals(key)) {
                return kv.substring(eq + 1).trim();
            }
        }
        return "";
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
