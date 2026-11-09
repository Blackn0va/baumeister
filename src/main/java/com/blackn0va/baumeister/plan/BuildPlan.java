package com.blackn0va.baumeister.plan;

import java.util.List;

/** Antwort des LLM nach dem Einlesen, noch in lokalen Koordinaten und ohne Blockprüfung. */
public record BuildPlan(String design, String reply, String mode, List<Step> steps) {

    /** Ohne mode (Schnellaufträge, alte Pläne): ein neuer Bau. */
    public BuildPlan(String design, String reply, List<Step> steps) {
        this(design, reply, "new", steps);
    }

    /** mode "update": der Plan ersetzt den letzten Bau des Spielers vollständig (BuildService.Memory). */
    public boolean isUpdate() {
        return "update".equals(mode);
    }

    public enum Op {
        FILL, WALLS, HOLLOW, LINE, SET, CYLINDER, SPHERE, ROOF, DOOR, MODULE, SKYSCRAPER, HOUSE, LOGIC,
        /** Bauformen mit Geometrie im Plugin (Structures): Burg, Mauerzug, Brücke, Garten, Baum. */
        CASTLE, WALL, BRIDGE, GARDEN, TREE
    }

    /**
     * Alle Felder außer op sind optional; welche gebraucht werden, prüft der Parser je op.
     * name gilt nur für MODULE (Bauteil aus der Bibliothek), block für alle anderen.
     * floors und windowBlock nur für SKYSCRAPER und HOUSE (0 bzw. leer sonst), roofBlock nur für HOUSE,
     * expr nur für LOGIC (Formel für den Logik-Compiler, leer sonst).
     * width (Mauerdicke, Brückenbreite) und style (arch/beam, round/cone, round/square) nur für die Bauformen aus
     * Structures; 0 bzw. leer heißt „Vorgabe der Form“.
     */
    public record Step(Op op, String block, Pos from, Pos to, Pos pos, int radius, int height,
            boolean hollow, boolean dome, String shape, int overhang, String gableBlock, String facing,
            String name, int floors, String windowBlock, String roofBlock, String expr, int width, String style) {

        /**
         * Alte Form ohne width/style. Bleibt, damit Stellen, die einen Schritt selbst bauen (Dach des Wohnhauses in
         * PlanCompiler.house), unverändert weiter übersetzen – wer sie entfernt, muss jeden solchen Aufruf anfassen.
         */
        public Step(Op op, String block, Pos from, Pos to, Pos pos, int radius, int height,
                boolean hollow, boolean dome, String shape, int overhang, String gableBlock, String facing,
                String name, int floors, String windowBlock, String roofBlock, String expr) {
            this(op, block, from, to, pos, radius, height, hollow, dome, shape, overhang, gableBlock, facing, name,
                    floors, windowBlock, roofBlock, expr, 0, "");
        }
    }
}
