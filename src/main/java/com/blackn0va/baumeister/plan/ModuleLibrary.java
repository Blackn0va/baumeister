package com.blackn0va.baumeister.plan;

import java.util.List;
import java.util.Optional;

/**
 * Fertige, von Hand gebaute und geprüfte Bauteile (Kolbentür, Farm, Sortierer …).
 *
 * Warum es das gibt: Ein LLM entwirft funktionierende Redstone-Schaltungen aus dem Kopf praktisch
 * nie richtig – ein Block falsch gedreht und die Tür klemmt. Was einmal funktioniert hat, wird
 * hier als Schematic abgelegt; das LLM wählt nur noch aus und platziert.
 */
public interface ModuleLibrary {

    /**
     * Bauteile, die nicht ganz oder gar nicht gebaut werden, sondern Zelle für Zelle (Plan B6 gilt Maschinen): die
     * Bauwerke des Spiels aus /bm spielbauwerk (Dateien „sp_…“, auch gespiegelt/doppelt). Vorher entfiel für einen
     * Spieler ohne Stufe „gefährlich“ der ganze Wüstentempel wegen der TNT-Falle, die ganze Bastion wegen der Lava
     * (Review 2026-11-15). Sicherheit unverändert: PlacementGate prüft jede Zelle, gesperrt bleibt gesperrt.
     */
    static boolean cellByCell(String moduleName) {
        return moduleName != null && moduleName.startsWith("sp_");
    }

    /**
     * @param size  Ausdehnung in lokalen Achsen (x breit, y hoch, z tief)
     * @param cells lokale Positionen ab (0,0,0), Vorderseite bei z=0; Zustände mit lokalen Richtungen
     */
    record Module(String name, String description, Pos size, List<CompiledBuild.Change> cells) {
    }

    Optional<Module> find(String name);

    List<Module> all();

    ModuleLibrary EMPTY = new ModuleLibrary() {
        @Override
        public Optional<Module> find(String name) {
            return Optional.empty();
        }

        @Override
        public List<Module> all() {
            return List.of();
        }
    };
}
