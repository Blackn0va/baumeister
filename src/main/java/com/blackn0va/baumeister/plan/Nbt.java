package com.blackn0va.baumeister.plan;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blockdaten als Baum (Plan B2): das, was nach dem strengen Leser ({@link Snbt}) von einem SNBT-Text übrig bleibt.
 * Eingeordnet ({@link BlockTiers}) und neu geschrieben ({@link Snbt#write}) wird nur dieser Baum – nie der Rohtext.
 * So kann kein Trick im Text (Escape, Funktion, zweite Verbindung) an der Prüfung vorbei bis zu Minecraft gelangen.
 *
 * Wahrheitswerte gibt es nicht eigens: true/false sind in Minecraft Bytes (1b/0b) und werden hier so gespeichert.
 *
 * Threads: unveränderlich. Fehler: keine.
 */
public sealed interface Nbt permits Nbt.Compound, Nbt.ListTag, Nbt.Str, Nbt.Num, Nbt.Arr {

    /** Verbindung in Eingabereihenfolge (die neu geschriebene Fassung ist damit stabil). */
    record Compound(Map<String, Nbt> entries) implements Nbt {

        public static final Compound EMPTY = new Compound(Map.of());

        public Compound {
            entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }

        public Nbt get(String key) {
            return entries.get(key);
        }

        public boolean has(String key) {
            return entries.containsKey(key);
        }

        /** Kopie mit ersetztem (oder neuem) Eintrag. */
        public Compound with(String key, Nbt value) {
            Map<String, Nbt> copy = new LinkedHashMap<>(entries);
            copy.put(key, value);
            return new Compound(copy);
        }

        /** Kopie ohne den Eintrag. */
        public Compound without(String key) {
            if (!entries.containsKey(key)) {
                return this;
            }
            Map<String, Nbt> copy = new LinkedHashMap<>(entries);
            copy.remove(key);
            return new Compound(copy);
        }
    }

    record ListTag(List<Nbt> items) implements Nbt {

        public ListTag {
            items = List.copyOf(items);
        }
    }

    record Str(String value) implements Nbt {
    }

    /**
     * Zahl mit Minecraft-Typ: b byte, s short, i int, l long, f float, d double. value ohne nachlaufende Nullen
     * gespeichert, damit 1.50f und 1.5f gleich sind.
     */
    record Num(char type, BigDecimal value) implements Nbt {

        public Num {
            value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }

        public static Num ofByte(int v) {
            return new Num('b', BigDecimal.valueOf(v));
        }

        public static Num ofInt(int v) {
            return new Num('i', BigDecimal.valueOf(v));
        }

        public boolean isInteger() {
            return type == 'b' || type == 's' || type == 'i' || type == 'l';
        }

        /** Ganzzahl (nur bei isInteger sinnvoll). */
        public long asLong() {
            return value.longValueExact();
        }
    }

    /** Typisiertes Feld [B;…], [I;…] oder [L;…]; type ist 'B', 'I' oder 'L'. */
    record Arr(char type, List<Long> values) implements Nbt {

        public Arr {
            values = List.copyOf(values);
        }
    }
}
