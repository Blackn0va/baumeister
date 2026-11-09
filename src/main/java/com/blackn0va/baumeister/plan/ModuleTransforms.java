package com.blackn0va.baumeister.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gespiegelte und doppelt so große Fassungen gespeicherter Bauteile: „Vera, bau meinhaus hier gespiegelt“ /
 * „… doppelt so groß“ (Vorschlag 10, 2026-11-05). Als Bibliothek über der echten: Name mit Endung _gespiegelt
 * und/oder _doppelt (beide in dieser Reihenfolge) liefert die verwandelte Fassung; das LLM kann sie genauso benutzen.
 *
 * Spiegeln an der Breitenachse (lokal x, links ↔ rechts): Positionen x → breite−1−x, die Zustände spiegelt am Server
 * BlockData.mirror nach den Regeln von Minecraft (siehe mirror). Vorne bleibt vorne.
 *
 * Doppelt: jeder Block wird zu 2×2×2. Zweiteilige Blöcke würden dabei zerrissen: Türen und hohe Pflanzen bleiben
 * einfach (unten auf 2y, oben direkt darüber), Betten entfallen (Kopf und Fuß lägen zwei Blöcke auseinander und
 * zerfielen). Höchstens MAX_CELLS Blöcke, sonst leer (zu groß für einen Bau).
 *
 * Threads: zustandslos. Fehler: keine (unbekannte Namen → leer).
 */
public final class ModuleTransforms implements ModuleLibrary {

    public static final String MIRRORED = "_gespiegelt";
    public static final String DOUBLED = "_doppelt";
    /** Obergrenze für die doppelte Fassung (8 × 4000 wäre mehr, als ein Bau tragen soll). */
    public static final int MAX_CELLS = 16_000;

    private final ModuleLibrary base;

    public ModuleTransforms(ModuleLibrary base) {
        this.base = base;
    }

    @Override
    public Optional<Module> find(String name) {
        Optional<Module> direct = base.find(name);
        if (direct.isPresent() || name == null) {
            return direct;
        }
        String stem = name;
        boolean doubled = stem.endsWith(DOUBLED);
        if (doubled) {
            stem = stem.substring(0, stem.length() - DOUBLED.length());
        }
        boolean mirrored = stem.endsWith(MIRRORED);
        if (mirrored) {
            stem = stem.substring(0, stem.length() - MIRRORED.length());
        }
        if (!doubled && !mirrored) {
            return Optional.empty();
        }
        Optional<Module> m = base.find(stem);
        if (mirrored) {
            m = m.map(ModuleTransforms::mirror);
        }
        if (doubled) {
            m = m.flatMap(ModuleTransforms::doubled);
        }
        String n = name;
        return m.map(x -> new Module(n, x.description(), x.size(), x.cells()));
    }

    @Override
    public List<Module> all() {
        return base.all();
    }

    /**
     * Positionen x → breite−1−x, Zustände bekommen eine offene Spiegelung (BlockSpec.Pending): Gespiegelt wird erst am
     * Server über BlockData.mirror, also nach den Regeln von Minecraft für jeden Zustand (Treppenformen, Türangeln,
     * Truhenhälften, Zaunverbindungen, Schilddrehung …). Vorher tauschte hier ein Wortersatz „left“ ↔ „right“ – der
     * kannte weder Himmelsrichtungs-Schlüssel noch rotation, und bei Treppen mit Blick nach vorn tauschte er Innenecken,
     * die Minecraft stehen lässt (StairBlock.mirror). Platzhalter in Befehlen spiegeln mit (x → breite−1−x).
     */
    public static Module mirror(Module m) {
        List<CompiledBuild.Change> out = new ArrayList<>(m.cells().size());
        int w = m.size().x();
        for (CompiledBuild.Change c : m.cells()) {
            Pos p = c.pos();
            out.add(new CompiledBuild.Change(new Pos(w - 1 - p.x(), p.y(), p.z()), mirror(c.block(), w)));
        }
        return new Module(m.name() + MIRRORED, m.description(), m.size(), List.copyOf(out));
    }

    static BlockSpec mirror(BlockSpec b, int width) {
        BlockSpec.Pending p = b.pending();
        // Zweimal gespiegelt ist ungespiegelt; eine schon offene Drehung dreht sich unter dem Spiegel um.
        BlockSpec.Pending next = p == null ? new BlockSpec.Pending(true, 0)
                : new BlockSpec.Pending(!p.mirror(), -p.turns());
        return b.withPending(next).mapPlaceholders(q -> new Pos(width - 1 - q.x(), q.y(), q.z()));
    }

    /**
     * Doppelt so groß: jeder Block 2×2×2. Inhalte und Befehle kommen nie mit (Plan B6): Aus einer Truhe mit Inhalt
     * würden acht, aus einem Befehlsblock acht Befehle – ein Vervielfältiger. Die Blöcke stehen leer da.
     */
    public static Optional<Module> doubled(Module m) {
        List<CompiledBuild.Change> out = new ArrayList<>();
        for (CompiledBuild.Change c : m.cells()) {
            BlockSpec b = c.block().withoutNbt();
            Pos p = c.pos();
            String id = b.id();
            if (id.endsWith("_bed")) {
                continue;
            }
            String half = b.props().get("half");
            boolean twoTall = half != null && (half.equals("upper") || half.equals("lower"))
                    && (id.endsWith("_door") || !b.props().containsKey("facing"));
            if (twoTall) {
                // Eine Tür bleibt eine Tür: unten auf 2y, oben direkt darüber (nicht auf 2y+2).
                int y = half.equals("upper") ? 2 * p.y() - 1 : 2 * p.y();
                out.add(new CompiledBuild.Change(new Pos(2 * p.x(), y, 2 * p.z()), b));
                continue;
            }
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    for (int dz = 0; dz < 2; dz++) {
                        out.add(new CompiledBuild.Change(new Pos(2 * p.x() + dx, 2 * p.y() + dy, 2 * p.z() + dz), b));
                    }
                }
            }
            if (out.size() > MAX_CELLS) {
                return Optional.empty();
            }
        }
        Pos s = m.size();
        return Optional.of(new Module(m.name() + DOUBLED, m.description(), new Pos(2 * s.x(), 2 * s.y(), 2 * s.z()),
                List.copyOf(out)));
    }
}
