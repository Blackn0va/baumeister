package com.blackn0va.baumeister.plan;

import java.util.List;

/**
 * Fertig geordneter Bau in lokalen Koordinaten.
 *
 * @param actions  in Bau-Reihenfolge; eine Aktion ist das, was der NPC mit einem Klick tut
 *                 (meist ein Block, bei zweiteiligen Blöcken beide Hälften auf einmal)
 * @param warnings deutsche Hinweise für den Chat (ersetzte/ausgelassene Blöcke)
 * @param stepNames je Plan-Schritt "op block" – für die Konsolenausgabe beim Bauen
 */
public record CompiledBuild(List<Action> actions, List<String> warnings, Pos min, Pos max,
        List<String> stepNames) {

    /**
     * Ein Block. group ≠ 0: gehört zu einem Bauteil (module/logic-Schritt Nummer group). Ein Bauteil wird ganz oder gar
     * nicht gesetzt (Plan B6) – fehlt dem Auftraggeber für eine einzige Zelle die Stufe, entfällt das ganze Bauteil,
     * statt als halbe Maschine dazustehen (BuildJob).
     */
    public record Change(Pos pos, BlockSpec block, int group) {

        public Change(Pos pos, BlockSpec block) {
            this(pos, block, 0);
        }
    }

    public record Action(int step, List<Change> changes) {

        public Pos anchor() {
            return changes.getFirst().pos();
        }

        public boolean isRemoval() {
            return changes.stream().allMatch(c -> c.block().isAir());
        }

        public int group() {
            return changes.getFirst().group();
        }
    }

    public int blockCount() {
        return actions.stream().mapToInt(a -> a.changes().size()).sum();
    }
}
