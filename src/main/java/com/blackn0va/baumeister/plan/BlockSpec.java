package com.blackn0va.baumeister.plan;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Block-ID plus Zustände, z. B. {@code oak_stairs[facing=left,half=top]}, dazu optional Blockdaten wie bei /setblock:
 * {@code oak_sign[rotation=4]{front_text:{messages:["Hallo","","",""]}}}.
 * Richtungswerte bleiben bis zur {@link Orientation} lokal (front/back/left/right); Himmelsrichtungen in Werten und
 * Schlüsseln (north=true, rotation, orientation) gelten im „Nordrahmen“: so, als schaute der Spieler nach Norden.
 *
 * explicit (Plan B2/B3): welche Zustände der Plan ausdrücklich nennt. Ergänztes (decorate, Türhälften, Treppenform aus
 * ShapeHelper) darf das Plugin anpassen oder weglassen, ausdrücklich Genanntes nie – vorher verwarf toBlockData bei
 * einem einzigen falschen Zusatz alle Zustände still (oak_log[axis=x] + facing → oak_log[axis=y]).
 *
 * nbt: Blockdaten als geprüfter Baum (Snbt). NICHT in {@link #asString()} – der Client-Mod und
 * Bukkit.createBlockData lesen nur Zustände; die Daten gehen getrennt per „data merge“ an den Server. trustedNbt: aus
 * Bauteil-Dateien oder Code (true) statt aus Plantext des Modells (false) – Befehle aus dem Modell muss ein Admin
 * erst bestätigen (Plan B5, siehe PlanCommands).
 *
 * pending: noch nicht angewandte Spiegelung eines Bauteils samt Viertelumdrehungen danach. Gespiegelt wird erst am
 * Server über BlockData.mirror (Vanilla-Regeln für jeden Zustand), weil Spiegeln und Drehen nicht vertauschbar sind.
 *
 * Gleichheit: id, props, nbt, pending – explicit und trustedNbt sind Herkunft, kein Zustand.
 *
 * Threads: unveränderlich. Fehler: PlanException mit deutschem Satz.
 */
public record BlockSpec(String id, Map<String, String> props, Set<String> explicit, Nbt.Compound nbt,
        boolean trustedNbt, Pending pending) {

    /** Spiegelung (FRONT_BACK im Nordrahmen, links ↔ rechts) samt danach folgenden Viertelumdrehungen. */
    public record Pending(boolean mirror, int turns) {

        public Pending {
            turns = Math.floorMod(turns, 4);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");
    /** Schlüssel, die eine Lage beschreiben, keinen Inhalt – data merge ließe sie ohnehin nicht wirken. */
    private static final Set<String> POSITION_KEYS = Set.of("id", "x", "y", "z");

    public BlockSpec {
        props = Collections.unmodifiableMap(new LinkedHashMap<>(props));
        Set<String> kept = new HashSet<>(explicit);
        kept.retainAll(props.keySet());
        explicit = Set.copyOf(kept);
        if (nbt != null && nbt.entries().isEmpty()) {
            nbt = null;
        }
        if (pending != null && !pending.mirror() && pending.turns() == 0) {
            pending = null;
        }
    }

    /** Alle Zustände gelten als ausdrücklich (vollständig gelesene Zustände: Bauteile, Vorbilder, Rückgängig). */
    public BlockSpec(String id, Map<String, String> props) {
        this(id, props, props.keySet(), null, false, null);
    }

    public static BlockSpec of(String id) {
        return new BlockSpec(id, Map.of(), Set.of(), null, false, null);
    }

    /**
     * Liest, was das LLM als Block schreibt (höchstens {@link Snbt#MAX_FROM_MODEL} Zeichen samt Daten). Tolerant bei
     * Groß-/Kleinschreibung, Leerzeichen und "minecraft:" in ID und Zuständen, streng bei allem anderen – die Zustände
     * landen in Bukkit.createBlockData, die Daten im strengen SNBT-Leser.
     */
    public static BlockSpec parse(String raw) throws PlanException {
        return parse(raw, Snbt.MAX_FROM_MODEL, false);
    }

    /** Wie parse, für Konsole und Bauteile: bis {@link Snbt#MAX_TRUSTED} Zeichen, Daten gelten als geprüfte Quelle. */
    public static BlockSpec parseTrusted(String raw) throws PlanException {
        return parse(raw, Snbt.MAX_TRUSTED, true);
    }

    private static BlockSpec parse(String raw, int maxChars, boolean trusted) throws PlanException {
        if (raw == null || raw.isBlank()) {
            throw new PlanException("Block fehlt.");
        }
        if (raw.length() > maxChars) {
            throw new PlanException("Block zu lang: " + raw.length() + " Zeichen, erlaubt sind " + maxChars + ".");
        }
        String all = raw.trim();
        // Daten beginnen mit der ersten {. In ID und Zuständen gibt es keine geschweiften Klammern; die Daten bleiben
        // in ihrer Schreibweise (Texte und Befehle unterscheiden Groß und Klein).
        int brace = all.indexOf('{');
        String text = (brace >= 0 ? all.substring(0, brace) : all).trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("minecraft:")) {
            text = text.substring("minecraft:".length());
        }
        String idPart = text;
        Map<String, String> props = new LinkedHashMap<>();
        int open = text.indexOf('[');
        if (open >= 0) {
            if (!text.endsWith("]")) {
                throw new PlanException("Blockzustand nicht geschlossen: " + raw);
            }
            idPart = text.substring(0, open);
            String inner = text.substring(open + 1, text.length() - 1);
            for (String pair : inner.split(",")) {
                if (pair.isBlank()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    throw new PlanException("Blockzustand ungültig: " + raw);
                }
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (!TOKEN.matcher(key).matches() || !TOKEN.matcher(value).matches()) {
                    throw new PlanException("Blockzustand ungültig: " + raw);
                }
                props.put(key, value);
            }
        }
        String id = idPart.trim().replace(' ', '_');
        if (!TOKEN.matcher(id).matches()) {
            throw new PlanException("Blockname ungültig: " + raw);
        }
        Nbt.Compound nbt = brace < 0 ? null : content(id, Snbt.parseCompound(all.substring(brace), maxChars));
        return new BlockSpec(id, props, props.keySet(), nbt, trusted, null);
    }

    /**
     * Blockdaten für den Block id: Lage-Schlüssel (id, x, y, z) weg, Befehle eines Befehlsblocks geprüft
     * (CommandText.check: kein ^, ~ nur ohne Versatz in x/z). Leer → null.
     */
    static Nbt.Compound content(String id, Nbt.Compound raw) throws PlanException {
        Nbt.Compound c = raw;
        for (String k : POSITION_KEYS) {
            c = c.without(k);
        }
        if (id.endsWith("command_block") && c.has("Command")) {
            if (!(c.get("Command") instanceof Nbt.Str s)) {
                throw new PlanException("Befehl muss Text sein.");
            }
            CommandText.check(s.value());
        }
        return c.entries().isEmpty() ? null : c;
    }

    /** Ergänzter Zustand: darf das Plugin anpassen oder weglassen (nicht ausdrücklich). */
    public BlockSpec with(String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(props);
        copy.put(key, value);
        Set<String> ex = new HashSet<>(explicit);
        ex.remove(key);
        return new BlockSpec(id, copy, ex, nbt, trustedNbt, pending);
    }

    /** Ausdrücklicher Zustand: wird genau so gesetzt (Code, der einen bestimmten Zustand meint). */
    public BlockSpec withExplicit(String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(props);
        copy.put(key, value);
        Set<String> ex = new HashSet<>(explicit);
        ex.add(key);
        return new BlockSpec(id, copy, ex, nbt, trustedNbt, pending);
    }

    public BlockSpec withId(String newId) {
        return new BlockSpec(newId, props, explicit, nbt, trustedNbt, pending);
    }

    /** Zustände ersetzen, Herkunft (explicit) über keyMap mitführen; Daten und Spiegelung bleiben. */
    BlockSpec withProps(Map<String, String> newProps, Set<String> newExplicit) {
        return new BlockSpec(id, newProps, newExplicit, nbt, trustedNbt, pending);
    }

    /** Geprüfte Daten aus einer Bauteil-Datei oder Code (trusted). */
    public BlockSpec withTrustedNbt(Nbt.Compound data) throws PlanException {
        return new BlockSpec(id, props, explicit, data == null ? null : content(id, data), true, pending);
    }

    public BlockSpec withoutNbt() {
        return nbt == null ? this : new BlockSpec(id, props, explicit, null, false, pending);
    }

    public BlockSpec withPending(Pending p) {
        return new BlockSpec(id, props, explicit, nbt, trustedNbt, p);
    }

    /**
     * Platzhalter im Befehl umrechnen (Bauteil drehen/verschieben/spiegeln, Plan verschieben). Ohne Befehl unverändert.
     */
    public BlockSpec mapPlaceholders(UnaryOperator<Pos> map) {
        if (nbt == null || !(nbt.get("Command") instanceof Nbt.Str s)) {
            return this;
        }
        String moved = CommandText.mapPositions(s.value(), map);
        if (moved.equals(s.value())) {
            return this;
        }
        return new BlockSpec(id, props, explicit, nbt.with("Command", new Nbt.Str(moved)), trustedNbt, pending);
    }

    public boolean isAir() {
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air");
    }

    public boolean isDoor() {
        return id.endsWith("_door");
    }

    public boolean isStairs() {
        return id.endsWith("_stairs");
    }

    /** Form für Bukkit.createBlockData: {@code minecraft:id[k=v,...]} – bewusst ohne Daten. */
    public String asString() {
        if (props.isEmpty()) {
            return "minecraft:" + id;
        }
        StringBuilder sb = new StringBuilder("minecraft:").append(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    /** Neu geschriebene Daten oder "" – genau das geht an „data merge“. */
    public String nbtText() {
        return nbt == null ? "" : Snbt.write(nbt);
    }

    /** Wie im Plan: Zustände ohne „minecraft:“ und Daten in der neu geschriebenen Fassung. */
    public String toPlanText() {
        return asString().substring("minecraft:".length()) + nbtText();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockSpec b && id.equals(b.id) && props.equals(b.props) && Objects.equals(nbt, b.nbt)
                && Objects.equals(pending, b.pending);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, props, nbt, pending);
    }
}
