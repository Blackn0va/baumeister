package com.blackn0va.baumeister.plan;

import java.util.Locale;
import java.util.Map;

/**
 * Dreht den lokalen Bauplan in die Blickrichtung des Spielers.
 *
 * Lokal ist "vorne" immer die Seite zum Spieler. Das LLM bekommt bewusst keine Weltrichtungen:
 * Es weiß nicht, wohin der Spieler schaut, und die Tür landete sonst auf einer beliebigen Seite.
 * Wer auf north/south umstellt, muss dem Modell die Blickrichtung im Prompt mitgeben.
 */
public final class Orientation {

    /** Weltrichtungen; dx/dz in Minecraft-Achsen (Norden = -Z, Osten = +X). */
    public enum Cardinal {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        final int dx;
        final int dz;

        Cardinal(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public Cardinal clockwise() {
            return values()[(ordinal() + 1) % 4];
        }

        public Cardinal opposite() {
            return values()[(ordinal() + 2) % 4];
        }

        String axis() {
            return dx != 0 ? "x" : "z";
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Minecraft-Yaw: 0 = Süden, 90 = Westen, 180 = Norden, 270 = Osten. */
        public static Cardinal fromYaw(float yaw) {
            float y = ((yaw % 360) + 360) % 360;
            if (y >= 45 && y < 135) {
                return WEST;
            }
            if (y >= 135 && y < 225) {
                return NORTH;
            }
            if (y >= 225 && y < 315) {
                return EAST;
            }
            return SOUTH;
        }

        public static Cardinal parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "n", "north", "nord", "norden" -> NORTH;
                case "o", "e", "east", "ost", "osten" -> EAST;
                case "s", "south", "süd", "sued", "süden" -> SOUTH; // umlaut-ok: Eingabe ohne Umlaut-Tastatur
                case "w", "west", "westen" -> WEST;
                default -> throw new IllegalArgumentException("Richtung unbekannt: " + s);
            };
        }
    }

    private final Cardinal forward;
    private final Cardinal right;

    /** forward = Blickrichtung des Spielers = lokales +z. */
    public Orientation(Cardinal forward) {
        this.forward = forward;
        this.right = forward.clockwise();
    }

    public Cardinal forward() {
        return forward;
    }

    public static boolean isLocalDirection(String s) {
        return s.equals("front") || s.equals("back") || s.equals("left") || s.equals("right");
    }

    /** Was das LLM als facing schreiben darf: vier Seiten plus up/down (Kolben, Beobachter, Trichter). */
    public static boolean isPlanDirection(String s) {
        return isLocalDirection(s) || s.equals("up") || s.equals("down");
    }

    public Pos toWorldOffset(Pos local) {
        return new Pos(
                local.x() * right.dx + local.z() * forward.dx,
                local.y(),
                local.x() * right.dz + local.z() * forward.dz);
    }

    public Cardinal toWorld(String localDirection) {
        return switch (localDirection) {
            case "back" -> forward;
            case "front" -> forward.opposite();
            case "right" -> right;
            case "left" -> right.opposite();
            default -> throw new IllegalArgumentException("Keine lokale Richtung: " + localDirection);
        };
    }

    /** Gegenstück zu {@link #toWorldOffset}: rechts/vorwärts sind orthonormal, also Skalarprodukt. */
    public Pos toLocalOffset(Pos world) {
        return new Pos(
                world.x() * right.dx + world.z() * right.dz,
                world.y(),
                world.x() * forward.dx + world.z() * forward.dz);
    }

    public String toLocal(Cardinal c) {
        if (c == forward) {
            return "back";
        }
        if (c == forward.opposite()) {
            return "front";
        }
        return c == right ? "right" : "left";
    }

    /**
     * Lokaler Zustand → Weltzustand, ohne Server (Client-Mod, Tests, MachineProbe). Erst eine offene Spiegelung samt
     * Drehung des Bauteils (BlockSpec.Pending), dann lokale Namen in den Nordrahmen, dann die Drehung zur Blickrichtung.
     * Seit 2026-11-08 drehen auch Himmelsrichtungen in Werten und Schlüsseln mit (Nordrahmen); vorher blieben
     * „north“ als Wert stehen und Schlüssel wie north=true fielen in toLocal weg. Am Server macht dasselbe
     * BuildService.toBlockData mit BlockData.rotate/mirror.
     */
    public BlockSpec toWorld(BlockSpec spec) {
        BlockSpec s = applyPending(spec);
        if (s.props().isEmpty()) {
            return s;
        }
        Map<String, String> nf = StateTransform.northFrame(s.id(), s.props());
        StateTransform.Result r = StateTransform.rotate(s.id(), nf, s.explicit(), forward.ordinal());
        return s.withProps(r.props(), r.explicit());
    }

    /**
     * Weltzustand → lokal (gespeicherte Bauteile, Vorbilder, Vorlagen): Drehung zurück in den Nordrahmen, facing und
     * Schienenformen als lokale Namen. Himmelsrichtungs-Schlüssel (Zaun-/Leitungsverbindungen north=true …) bleiben
     * im Nordrahmen erhalten – vorher fielen sie weg, und ein Bauteil kam mit neu gerechneten statt den gespeicherten
     * Verbindungen zurück.
     */
    public BlockSpec toLocal(BlockSpec spec) {
        if (spec.props().isEmpty()) {
            return spec;
        }
        StateTransform.Result r = StateTransform.rotate(spec.id(), spec.props(), spec.explicit(),
                4 - forward.ordinal());
        return spec.withProps(StateTransform.localNames(spec.id(), r.props()), r.explicit());
    }

    /**
     * Für Bukkit.createBlockData vor BlockData.rotate/mirror: lokale Namen (facing, Schienenform) als Himmelsrichtung
     * im Nordrahmen. Ohne Drehung und ohne Spiegelung – die macht danach Minecraft (BuildService.toBlockData).
     */
    public static BlockSpec northFrame(BlockSpec spec) {
        if (spec.props().isEmpty()) {
            return spec;
        }
        return spec.withProps(StateTransform.northFrame(spec.id(), spec.props()), spec.explicit());
    }

    /** Welche Schlüssel sind in Welt-Namen ausdrücklich (Zaunverbindungen drehen mit)? Für ShapeHelper. */
    public java.util.Set<String> worldExplicit(BlockSpec spec) {
        return toWorld(spec).explicit();
    }

    /** Offene Spiegelung samt Drehung als Text anwenden (reiner Zwilling zu BlockData.mirror/rotate). */
    static BlockSpec applyPending(BlockSpec spec) {
        BlockSpec.Pending p = spec.pending();
        if (p == null) {
            return spec;
        }
        Map<String, String> props = StateTransform.northFrame(spec.id(), spec.props());
        java.util.Set<String> explicit = spec.explicit();
        if (p.mirror()) {
            StateTransform.Result m = StateTransform.mirror(spec.id(), props, explicit);
            props = m.props();
            explicit = m.explicit();
        }
        StateTransform.Result r = StateTransform.rotate(spec.id(), props, explicit, p.turns());
        return spec.withPending(null).withProps(r.props(), r.explicit());
    }

    static boolean isCompass(String s) {
        return StateTransform.isCompass(s);
    }

    // ---------------------------------------------------------------- Drehen im lokalen Rahmen

    /** Viertelumdrehungen, damit die Vorderseite eines Bauteils zur gewünschten Seite zeigt. */
    public static int quarterTurnsFor(String facing) {
        return switch (facing) {
            case "left" -> 1;
            case "back" -> 2;
            case "right" -> 3;
            default -> 0;
        };
    }

    /** Eine Viertelumdrehung bildet vorne→links→hinten→rechts→vorne ab: (x, z) → (z, -x). */
    public static Pos rotateLocal(Pos p, int quarterTurns) {
        Pos r = p;
        for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) {
            r = new Pos(r.z(), r.y(), -r.x());
        }
        return r;
    }

    /**
     * Zustände um quarterTurns Viertel drehen (vorne→links→hinten→rechts, im Nordrahmen im Uhrzeigersinn). Mit offener
     * Spiegelung zählt nur die Drehung mit – gespiegelt und gedreht wird dann zusammen am Server (siehe BlockSpec).
     */
    public static BlockSpec rotateLocal(BlockSpec spec, int quarterTurns) {
        int k = Math.floorMod(quarterTurns, 4);
        if (k == 0) {
            return spec;
        }
        if (spec.pending() != null) {
            return spec.withPending(new BlockSpec.Pending(spec.pending().mirror(), spec.pending().turns() + k));
        }
        if (spec.props().isEmpty()) {
            return spec;
        }
        StateTransform.Result r = StateTransform.rotate(spec.id(), spec.props(), spec.explicit(), k);
        return spec.withProps(r.props(), r.explicit());
    }
}
