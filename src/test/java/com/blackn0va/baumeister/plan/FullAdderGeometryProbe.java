package com.blackn0va.baumeister.plan;

import com.blackn0va.baumeister.logic.LogicCircuit;

import java.util.Map;
import java.util.TreeMap;

/**
 * Messprobe (kein Test): Geometrie des Volladdierers aus dem Logik-Compiler – Größe, Hebel, Lampen und die belegten
 * Zellen am Rand, als Grundlage für die Verdrahtung mehrerer Bits (4-Bit-Rechenwerk, 2026-10-31).
 */
public final class FullAdderGeometryProbe {

    public static void main(String[] args) {
        LogicCircuit.Circuit c = LogicCircuit.compile(Machines.FULL_ADDER);
        System.out.println("groesse=" + c.module().size());  // umlaut-ok: Ausgabe
        System.out.println("eingaenge=" + c.inputs() + " hebel=" + c.inputPins());  // umlaut-ok
        for (LogicCircuit.Output o : c.outputs()) {
            System.out.println("ausgang " + o.name() + " lampe=" + o.lamp());
        }
        Map<String, String> cells = new TreeMap<>();
        for (CompiledBuild.Change ch : c.module().cells()) {
            Pos p = ch.pos();
            if (p.y() > 0) {
                cells.put(String.format("z%02d y%d x%02d", p.z(), p.y(), p.x()), ch.block().asString());
            }
        }
        cells.forEach((k, v) -> System.out.println(k + " " + v));
    }
}
