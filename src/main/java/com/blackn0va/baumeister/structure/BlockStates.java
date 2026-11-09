package com.blackn0va.baumeister.structure;

import com.blackn0va.baumeister.plan.BlockSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Entschlackt Blockzustände aus Bauwerk-Dateien: Werte, die der Vorgabe des Spiels entsprechen oder die das Spiel
 * beim Setzen ohnehin neu berechnet, fallen weg.
 *
 * Warum: Eine Schematic speichert jeden Zustand ausgeschrieben – oak_stairs[facing=north,half=bottom,shape=straight,
 * waterlogged=false]. Im Plan wäre das je Stufe ein Vielfaches an Token, und das Modell lernte Füllzeichen statt
 * Form. Dieselbe Funktion gilt für Ziel und Rückbau (PlanDecompiler.verify), so vergleicht die Übereinstimmung
 * Gleiches mit Gleichem.
 *
 * Bewusst behalten: alles, was man sieht und was nicht neu berechnet wird – facing, axis, hinge, half von Türen,
 * shape von Treppen außer „straight“, persistent bei Laub (ohne zerfällt es), age von Pflanzen. Verbindungen zu
 * Nachbarn (north=true …) fallen hier weg: Orientation.toLocal behält sie seit 2026-11-08 für Bauteile (exakt
 * nachbauen), das Lehrset soll sie wie bisher nicht sehen (sie rechnet der Server aus den Nachbarn).
 *
 * Threads: zustandslos. Fehler: keine.
 */
public final class BlockStates {

    /** Schlüssel=Wert-Paare, die der Vorgabe entsprechen und deshalb entfallen. */
    private static final Set<String> DEFAULTS = Set.of("waterlogged=false", "powered=false", "open=false",
            "snowy=false", "lit=false", "attached=false", "disarmed=false", "in_wall=false", "triggered=false",
            "enabled=true", "extended=false", "has_book=false", "signal_fire=false", "hanging=false",
            "occupied=false", "inverted=false", "locked=false", "unstable=false", "bottom=false", "drag=true",
            "has_bottle_0=false", "has_bottle_1=false", "has_bottle_2=false", "type=single", "shape=straight");

    /** Verbindungen zu Nachbarn (Zäune, Mauern, Scheiben, Leitungen) – rechnet der Server beim Setzen neu. */
    private static final Set<String> NEIGHBOUR_KEYS = Set.of("north", "east", "south", "west");

    /** Werte, die das Spiel aus der Umgebung oder der Zeit rechnet (Leitungsstärke, Laubabstand, Wachstumsstufe). */
    private static final Set<String> COMPUTED = Set.of("power", "distance", "stage", "level_honey", "note");

    /**
     * Alte Blocknamen → Name in 1.21.11, nur solche, die es heute nicht mehr gibt (also eindeutig). Gemessen
     * 2026-11-01 in den WorldEdit-Schematics der Serverkopie: grass (bis 1.20.2), grass_path (bis 1.16) und chain (bis
     * 1.21.8) fielen beim Rückbau auf stone bzw. grass_block zurück (32 Blöcke in vier Dateien). Neue Einträge nur
     * nach so einem Befund und gegen Material geprüft (der Server kennt den Zielnamen).
     */
    private static final Map<String, String> RENAMED = Map.of("grass", "short_grass", "grass_path", "dirt_path",
            "chain", "iron_chain");

    private BlockStates() {
    }

    /** Alten Namen auf den heutigen heben; alles andere bleibt. */
    public static BlockSpec upgrade(BlockSpec spec) {
        String now = RENAMED.get(spec.id());
        return now == null ? spec : spec.withId(now);
    }

    public static BlockSpec normalize(BlockSpec spec) {
        if (spec.props().isEmpty()) {
            return spec;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : spec.props().entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (COMPUTED.contains(k) || DEFAULTS.contains(k + "=" + v) || NEIGHBOUR_KEYS.contains(k)) {
                continue;
            }
            // half=bottom ist die Vorgabe bei Treppen und Falltüren; bei Türen und hohen Pflanzen trägt half die Hälfte.
            if (k.equals("half") && v.equals("bottom")) {
                continue;
            }
            // type=bottom bei Stufen ist die Vorgabe; bei Truhen heißt type left/right/single (single steht oben).
            if (k.equals("type") && v.equals("bottom") && spec.id().endsWith("_slab")) {
                continue;
            }
            out.put(k, v);
        }
        return out.size() == spec.props().size() ? spec : new BlockSpec(spec.id(), out);
    }
}
