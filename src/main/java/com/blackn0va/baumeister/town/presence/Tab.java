package com.blackn0va.baumeister.town.presence;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

/**
 * Wohin Tab-Einträge gehen: im Betrieb PacketEvents (PacketTab), ohne Unterstützung der Serverfassung nirgends
 * (NONE – Bewohner bleiben Mannequins ohne Tab, PacketBoot meldet den Grund im Log). Eigene Schnittstelle, damit
 * Presence ohne Server und ohne PacketEvents testbar ist.
 *
 * Threads: Hauptthread. Fehler: show/hide dürfen werfen; Presence fängt je Spieler.
 */
public interface Tab {

    /** Kommen Einträge an? false = Rückfall, Presence spart sich UUID und Pakete. */
    boolean active();

    /** Einträge hinzufügen (oder auffrischen) – nur bei diesem Spieler. */
    void show(Player to, List<TabEntry> entries);

    /** Einträge entfernen – nur bei diesem Spieler. */
    void hide(Player to, List<UUID> ids);

    /** Rückfall: keine Tab-Einträge. */
    Tab NONE = new Tab() {
        @Override
        public boolean active() {
            return false;
        }

        @Override
        public void show(Player to, List<TabEntry> entries) {
            // Rückfall ohne PacketEvents: bewusst nichts – die Bewohner sind als Mannequins in der Welt zu sehen.
        }

        @Override
        public void hide(Player to, List<UUID> ids) {
            // Rückfall: Es wurde nie etwas gezeigt, also gibt es nichts zu entfernen.
        }
    };
}
