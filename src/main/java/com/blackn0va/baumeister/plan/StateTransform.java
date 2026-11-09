package com.blackn0va.baumeister.plan;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Drehen und Spiegeln von Blockzuständen als Text – der reine Zwilling zu BlockData.rotate/mirror (Plan B3).
 *
 * Am Server dreht und spiegelt seit 2026-11-08 Minecraft selbst (BuildService.toBlockData über BlockData.rotate und
 * BlockData.mirror – Vanilla-Regeln für jeden Zustand). Hier bleibt, was ohne Server laufen muss: Bauteile im Plan
 * drehen (PlanCompiler.placeModule, Structures), der Client-Mod und die Tests. Abgedeckt sind facing, axis,
 * Schienenformen, Himmelsrichtungs-Schlüssel (Zäune, Mauern, Leitungen, Ranken), rotation (Schilder, Banner, Köpfe
 * 0–15), orientation (Werkbank/Verbundblock „north_up“), beim Spiegeln dazu Türangeln, Truhenhälften und
 * Treppenformen nach den Regeln von StairBlock.mirror.
 *
 * Richtungen stehen im Nordrahmen (der Spieler schaut nach Norden): back = north, right = east, front = south,
 * left = west. Lokale Namen bleiben beim Drehen lokale Namen, Himmelsrichtungen bleiben Himmelsrichtungen.
 * Viertelumdrehungen zählen im Uhrzeigersinn (von oben): north → east → south → west, wie Rotation.CLOCKWISE_90.
 *
 * Threads: zustandslos. Fehler: keine (unbekannte Werte bleiben stehen).
 */
final class StateTransform {

    private static final String[] COMPASS = {"north", "east", "south", "west"};
    private static final String[] LOCAL = {"back", "right", "front", "left"};

    private StateTransform() {
    }

    static boolean isCompass(String s) {
        return index(COMPASS, s) >= 0;
    }

    /** Eine Richtung (Himmelsrichtung oder lokaler Name) k Viertel im Uhrzeigersinn drehen; up/down bleiben. */
    static String rotate(String dir, int k) {
        int i = index(COMPASS, dir);
        if (i >= 0) {
            return COMPASS[Math.floorMod(i + k, 4)];
        }
        i = index(LOCAL, dir);
        if (i >= 0) {
            return LOCAL[Math.floorMod(i + k, 4)];
        }
        return dir;
    }

    /** Spiegeln links ↔ rechts (Mirror.FRONT_BACK im Nordrahmen): east ↔ west, right ↔ left. */
    static String mirror(String dir) {
        return switch (dir) {
            case "east" -> "west";
            case "west" -> "east";
            case "right" -> "left";
            case "left" -> "right";
            default -> dir;
        };
    }

    /** Lokaler Name → Himmelsrichtung im Nordrahmen; alles andere unverändert. */
    static String toCompass(String dir) {
        int i = index(LOCAL, dir);
        return i >= 0 ? COMPASS[i] : dir;
    }

    /** Himmelsrichtung im Nordrahmen → lokaler Name; alles andere unverändert. */
    static String toLocalName(String dir) {
        int i = index(COMPASS, dir);
        return i >= 0 ? LOCAL[i] : dir;
    }

    static boolean isRail(String id) {
        return id.equals("rail") || id.endsWith("_rail");
    }

    /** Neue Zustände und mitgeführte Herkunft (explicit) nach einer Umbenennung von Schlüsseln. */
    record Result(Map<String, String> props, Set<String> explicit) {
    }

    /** k Viertel im Uhrzeigersinn drehen. */
    static Result rotate(String id, Map<String, String> props, Set<String> explicit, int k) {
        int turns = Math.floorMod(k, 4);
        if (turns == 0 || props.isEmpty()) {
            return new Result(props, explicit);
        }
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> ex = new HashSet<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            String newKey = isCompass(key) ? rotate(key, turns) : key;
            switch (key) {
                case "facing" -> value = rotate(value, turns);
                case "axis" -> value = turns % 2 == 1 && value.equals("x") ? "z"
                        : turns % 2 == 1 && value.equals("z") ? "x" : value;
                case "shape" -> {
                    if (isRail(id)) {
                        value = railShape(value, d -> rotate(d, turns));
                    }
                }
                case "rotation" -> value = rotation(value, r -> r + 4 * turns);
                case "orientation" -> value = parts(value, d -> rotate(d, turns));
                default -> {
                }
            }
            out.put(newKey, value);
            if (explicit.contains(key)) {
                ex.add(newKey);
            }
        }
        return new Result(out, ex);
    }

    /** Spiegeln links ↔ rechts nach Vanilla (Mirror.FRONT_BACK). */
    static Result mirror(String id, Map<String, String> props, Set<String> explicit) {
        if (props.isEmpty()) {
            return new Result(props, explicit);
        }
        String facing = props.getOrDefault("facing", "");
        boolean facingAlongX = facing.equals("east") || facing.equals("west") || facing.equals("right")
                || facing.equals("left");
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> ex = new HashSet<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            String newKey = isCompass(key) ? mirror(key) : key;
            switch (key) {
                case "facing" -> value = mirror(value);
                case "shape" -> {
                    if (isRail(id)) {
                        value = railShape(value, StateTransform::mirror);
                    } else if (id.endsWith("_stairs") && facingAlongX) {
                        // StairBlock.mirror, FRONT_BACK bei Blick entlang x: Außenecken tauschen, Innenecken bleiben.
                        value = value.equals("outer_left") ? "outer_right"
                                : value.equals("outer_right") ? "outer_left" : value;
                    }
                }
                case "hinge" -> {
                    if (id.endsWith("_door")) {
                        value = value.equals("left") ? "right" : value.equals("right") ? "left" : value;
                    }
                }
                case "type" -> {
                    if (id.equals("chest") || id.equals("trapped_chest") || id.endsWith("copper_chest")) {
                        value = value.equals("left") ? "right" : value.equals("right") ? "left" : value;
                    }
                }
                case "rotation" -> value = rotation(value, r -> 16 - r);
                case "orientation" -> value = parts(value, StateTransform::mirror);
                default -> {
                }
            }
            out.put(newKey, value);
            if (explicit.contains(key)) {
                ex.add(newKey);
            }
        }
        return new Result(out, ex);
    }

    /** facing und Schienenformen von lokalen Namen in den Nordrahmen; Schlüssel bleiben. */
    static Map<String, String> northFrame(String id, Map<String, String> props) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String value = e.getValue();
            if (e.getKey().equals("facing")) {
                value = toCompass(value);
            } else if (e.getKey().equals("shape") && isRail(id)) {
                value = canonicalRail(railShape(value, StateTransform::toCompass));
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    /** facing und Schienenformen aus dem Nordrahmen zurück in lokale Namen (gespeicherte Bauteile). */
    static Map<String, String> localNames(String id, Map<String, String> props) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String value = e.getValue();
            if (e.getKey().equals("facing")) {
                value = toLocalName(value);
            } else if (e.getKey().equals("shape") && isRail(id)) {
                value = railShape(value, StateTransform::toLocalName);
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    /**
     * Schienenformen enthalten Richtungen („north_east“, „ascending_west“). Minecraft kennt nur kanonische Paare
     * (north/south zuerst, gerade Strecken als north_south/east_west) – nach dem Drehen wird darauf zurückgeordnet,
     * sonst lehnt createBlockData die Form ab.
     */
    static String canonicalRail(String value) {
        String[] p = value.split("_");
        if (p.length != 2 || p[0].equals("ascending") || !isCompass(p[0]) || !isCompass(p[1])) {
            return value;
        }
        boolean aZ = p[0].equals("north") || p[0].equals("south");
        boolean bZ = p[1].equals("north") || p[1].equals("south");
        if (aZ == bZ) {
            return aZ ? "north_south" : "east_west";
        }
        return aZ ? p[0] + "_" + p[1] : p[1] + "_" + p[0];
    }

    private interface DirMap {
        String apply(String dir);
    }

    private interface IntMap {
        int apply(int r);
    }

    private static String railShape(String value, DirMap map) {
        String[] p = value.split("_");
        if (p.length != 2) {
            return value;
        }
        if (p[0].equals("ascending")) {
            return "ascending_" + map.apply(p[1]);
        }
        String out = map.apply(p[0]) + "_" + map.apply(p[1]);
        return isCompass(map.apply(p[0])) ? canonicalRail(out) : out;
    }

    private static String parts(String value, DirMap map) {
        String[] p = value.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : p) {
            sb.append(sb.isEmpty() ? "" : "_").append(map.apply(part));
        }
        return sb.toString();
    }

    private static String rotation(String value, IntMap map) {
        try {
            int r = Integer.parseInt(value);
            if (r < 0 || r > 15) {
                return value;
            }
            return Integer.toString(Math.floorMod(map.apply(r), 16));
        } catch (NumberFormatException e) {
            // Kein Zahlwert (falscher Plan) – unverändert lassen, createBlockData meldet ihn.
            return value;
        }
    }

    private static int index(String[] list, String s) {
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(s)) {
                return i;
            }
        }
        return -1;
    }
}
