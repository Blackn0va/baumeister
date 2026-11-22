package com.blackn0va.baumeister.town.presence;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Neue Spieler bekommen die vorhandenen Bewohner in die Tab-Liste (Presence.joined). MONITOR: Nur zuschauen, nichts am
 * Beitritt ändern. Gehende Spieler brauchen nichts – ihr Client verwirft seine Liste ohnehin.
 *
 * Threads: Hauptthread. Fehler: keine eigenen (Presence fängt).
 */
public final class PresenceListener implements Listener {

    private final Presence presence;

    public PresenceListener(Presence presence) {
        this.presence = presence;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        presence.joined(e.getPlayer());
    }
}
